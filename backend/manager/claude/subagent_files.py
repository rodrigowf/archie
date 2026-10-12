"""Claude Code subagent history on disk (spec 12 SA-8).

Layout (the CLI's, under the project dir that ``.claude_config/projects/<repo>``
points at, i.e. ``context/``)::

    <sid>.jsonl                               the parent conversation
    <sid>/subagents/agent-<agentId>.jsonl     one transcript per subagent
    <sid>/subagents/agent-<agentId>.meta.json {agentType, description, toolUseId,
                                               spawnDepth, parentAgentId, ...}

The transcripts are written incrementally while the agent runs (checked with
CLI 2.1.292: the file appears at spawn and grows with every message), so an
agent can be opened mid-run.  ``agent-acompact-*`` files are compaction
helpers, not subagents.  Older CLIs write no ``toolUseId`` in the sidecar (or
no sidecar at all): those files are matched to their spawning call through the
parent's ``toolUseResult.agentId``, the ``agentId: …`` text of an async launch
result, or a ``<task-notification>``'s ``<task-id>``/``<tool-use-id>``.

The SDK's ``list_subagents()`` / ``get_subagent_messages()`` read the same
files but locate the session through ``CLAUDE_CONFIG_DIR/projects`` by cwd and
return raw SDK messages; here the parent JSONL path is already known and the
REST shape is the parent's, so the files are read directly and the transcript
goes through the same :class:`ClaudeAdapter` normalisation as the parent.
"""

from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any

from ..subagents import is_spawn_tool, merge_summaries, spawn_fields, usage_dict, wire_status

_AGENT_ID_RE = re.compile(r"agentId:\s*([A-Za-z0-9_-]+)")
_TAG_RE = {
    tag: re.compile(rf"<{tag}>(.*?)</{tag}>", re.DOTALL)
    for tag in ("task-id", "tool-use-id", "status", "result", "summary",
                "subagent_tokens", "total_tokens", "tool_uses", "duration_ms")
}
# Lines worth parsing while scanning (cheap substring pre-filter).
_INTERESTING = ('"Agent"', '"Task"', "agentId", "task-notification", "queue-operation")
_TAIL_BYTES = 256 * 1024

# parent JSONL path → (signature, rows, files)
_CACHE: dict[str, tuple[tuple, list[dict[str, Any]], dict[str, Path]]] = {}
_CACHE_MAX = 64


def _subagents_dir(parent_jsonl: Path) -> Path:
    return parent_jsonl.with_suffix("") / "subagents"


def _agent_files(sub_dir: Path) -> list[tuple[str, Path]]:
    """``(agent_id, path)`` for every transcript (nested dirs included), name order."""
    out: list[tuple[str, Path]] = []
    if not sub_dir.is_dir():
        return out
    for root, dirs, files in os.walk(sub_dir):
        dirs.sort()
        for name in sorted(files):
            if name.startswith("agent-") and name.endswith(".jsonl"):
                agent_id = name[len("agent-"):-len(".jsonl")]
                if agent_id.startswith("acompact"):
                    continue  # compaction helper, not a subagent
                out.append((agent_id, Path(root) / name))
    return out


def _signature(parent_jsonl: Path, files: list[tuple[str, Path]]) -> tuple:
    def st(p: Path) -> tuple[int, int]:
        try:
            s = p.stat()
            return (s.st_mtime_ns, s.st_size)
        except OSError:
            return (0, -1)
    return (st(parent_jsonl),) + tuple((aid, st(p)) for aid, p in files)


def _read_meta(transcript: Path) -> dict[str, Any]:
    meta_path = transcript.with_name(transcript.name[: -len(".jsonl")] + ".meta.json")
    try:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return meta if isinstance(meta, dict) else {}


def _tag(text: str, tag: str) -> str | None:
    m = _TAG_RE[tag].search(text)
    return m.group(1).strip() if m else None


def _text_of(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "\n".join(
            b.get("text", "") for b in content
            if isinstance(b, dict) and b.get("type") == "text"
        )
    return ""


def _blank(tool_use_id: str, parent: str | None) -> dict[str, Any]:
    return {
        "tool_use_id": tool_use_id,
        "parent_tool_use_id": parent,
        "agent_type": "",
        "description": "",
        "background": False,
        "status": None,  # unknown until a terminal line is seen
        "activity": None,
        "usage": None,
        "summary": None,
        "started_at": None,
        "ended_at": None,
    }


class _Scan:
    def __init__(self) -> None:
        self.records: dict[str, dict[str, Any]] = {}
        self.agent_to_tid: dict[str, str] = {}
        # spawn prompt → tool_use_id: last-resort match for a transcript whose
        # first user line is that prompt (CLIs that logged no agent id at all).
        self.prompts: dict[str, str] = {}

    def scan(self, path: Path, parent_tid: str | None) -> None:
        try:
            fh = open(path, encoding="utf-8")
        except OSError:
            return
        with fh:
            for line in fh:
                if not any(k in line for k in _INTERESTING) and not (
                    '"tool_result"' in line and any(tid in line for tid in self.records)
                ):
                    continue
                try:
                    obj = json.loads(line)
                except ValueError:
                    continue
                if isinstance(obj, dict):
                    self._line(obj, parent_tid)

    def _line(self, obj: dict[str, Any], parent_tid: str | None) -> None:
        typ = obj.get("type")
        ts = obj.get("timestamp")
        if typ == "queue-operation":
            # Mid-2026 CLIs: ``{"task_id", "tool_use_id", "task_type": "local_agent"}``.
            try:
                op = json.loads(obj.get("content") or "")
            except (TypeError, ValueError):
                return
            if isinstance(op, dict) and op.get("task_id") and op.get("tool_use_id"):
                self.agent_to_tid.setdefault(str(op["task_id"]), str(op["tool_use_id"]))
            return
        msg = obj.get("message") if isinstance(obj.get("message"), dict) else {}
        content = msg.get("content")
        if typ == "assistant" and isinstance(content, list):
            for b in content:
                if (isinstance(b, dict) and b.get("type") == "tool_use"
                        and is_spawn_tool(b.get("name")) and b.get("id")):
                    agent_type, description, background = spawn_fields(b.get("input"))
                    rec = self.records.setdefault(b["id"], _blank(b["id"], parent_tid))
                    rec["parent_tool_use_id"] = parent_tid
                    rec["agent_type"] = rec["agent_type"] or agent_type
                    rec["description"] = rec["description"] or description
                    rec["background"] = rec["background"] or background
                    rec["started_at"] = rec["started_at"] or ts
                    prompt = (b.get("input") or {}).get("prompt") if isinstance(b.get("input"), dict) else None
                    if isinstance(prompt, str) and prompt.strip():
                        self.prompts.setdefault(prompt.strip(), b["id"])
        elif typ == "user" and isinstance(content, list):
            meta = obj.get("toolUseResult") if isinstance(obj.get("toolUseResult"), dict) else {}
            for b in content:
                if not (isinstance(b, dict) and b.get("type") == "tool_result"):
                    continue
                tid = b.get("tool_use_id")
                rec = self.records.get(tid) if tid else None
                if rec is None:
                    continue
                text = _text_of(b.get("content"))
                agent_id = meta.get("agentId")
                launch = _AGENT_ID_RE.search(text)
                if not agent_id and launch:
                    agent_id = launch.group(1)
                if agent_id:
                    self.agent_to_tid[str(agent_id)] = tid
                if meta.get("status") == "async_launched" or meta.get("isAsync") or (
                    launch and not meta.get("status") and not b.get("is_error")
                ):
                    rec["background"] = True
                    continue
                rec["status"] = "failed" if b.get("is_error") else (wire_status(meta.get("status")) or "done")
                rec["summary"] = text
                rec["ended_at"] = ts
                if meta:
                    rec["usage"] = usage_dict(
                        meta.get("totalTokens"), meta.get("totalToolUseCount"), meta.get("totalDurationMs"),
                    )
        elif typ == "user" and isinstance(content, str) and content.lstrip().startswith("<task-notification>"):
            task_id = _tag(content, "task-id")
            tid = _tag(content, "tool-use-id") or (self.agent_to_tid.get(task_id) if task_id else None)
            rec = self.records.get(tid) if tid else None
            if rec is None:
                return  # a background shell, or an agent we never saw spawn
            if task_id:
                self.agent_to_tid[task_id] = tid
            rec["background"] = True
            rec["status"] = wire_status(_tag(content, "status")) or "done"
            rec["summary"] = _tag(content, "result") or _tag(content, "summary") or rec["summary"]
            rec["ended_at"] = ts
            tokens = _tag(content, "subagent_tokens") or _tag(content, "total_tokens")
            if tokens or _tag(content, "tool_uses"):
                rec["usage"] = usage_dict(tokens, _tag(content, "tool_uses"), _tag(content, "duration_ms"))


def _first_prompt(path: Path) -> str | None:
    """The transcript's first user line text (the agent's prompt)."""
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                try:
                    obj = json.loads(line)
                except ValueError:
                    continue
                if isinstance(obj, dict) and obj.get("type") == "user":
                    text = _text_of((obj.get("message") or {}).get("content")).strip()
                    return text or None
    except OSError:
        pass
    return None


def _first_timestamp(path: Path) -> str | None:
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                try:
                    ts = json.loads(line).get("timestamp")
                except (ValueError, AttributeError):
                    continue
                if ts:
                    return ts
    except OSError:
        pass
    return None


def _last_report(path: Path) -> tuple[str | None, str | None]:
    """``(text, timestamp)`` of the transcript's last assistant line when it
    is a plain answer (no tool call pending) — the agent finished."""
    try:
        with open(path, "rb") as fh:
            fh.seek(0, os.SEEK_END)
            size = fh.tell()
            fh.seek(max(0, size - _TAIL_BYTES))
            tail = fh.read().decode("utf-8", errors="replace")
    except OSError:
        return None, None
    for line in reversed(tail.splitlines()):
        try:
            obj = json.loads(line)
        except ValueError:
            continue
        if not isinstance(obj, dict) or obj.get("type") not in ("assistant", "user"):
            continue
        if obj.get("type") == "user":
            return None, None  # waiting on a tool result / a prompt
        content = (obj.get("message") or {}).get("content")
        if isinstance(content, list) and any(
            isinstance(b, dict) and b.get("type") == "tool_use" for b in content
        ):
            return None, None
        text = _text_of(content).strip()
        if text:
            return text, obj.get("timestamp")
        # thinking-only line: keep looking back
    return None, None


def scan(parent_jsonl: Path) -> tuple[list[dict[str, Any]], dict[str, Path]]:
    """Subagents of the session whose JSONL is *parent_jsonl*: ``(rows, files)``.

    ``rows`` are ``SubagentSummary`` dicts, oldest first; ``files`` maps each
    spawning ``tool_use_id`` to its transcript.  An agent with no terminal
    line in history is reported as ``done`` when its transcript ends with a
    plain answer, else ``stopped`` (its process is gone; a live session's
    registry overrides these rows).  Cached per (mtime, size) of the files.
    """
    files_by_agent = _agent_files(_subagents_dir(parent_jsonl))
    key = str(parent_jsonl)
    sig = _signature(parent_jsonl, files_by_agent)
    cached = _CACHE.get(key)
    if cached is not None and cached[0] == sig:
        return [dict(r) for r in cached[1]], dict(cached[2])

    sc = _Scan()
    sc.scan(parent_jsonl, None)
    files: dict[str, Path] = {}
    pending = list(files_by_agent)
    # Nested agents spawn inside other subagents' transcripts, so matching can
    # need another pass once their parents are scanned (old files without a
    # toolUseId in the sidecar).
    while pending:
        progressed = False
        rest: list[tuple[str, Path]] = []
        for agent_id, path in pending:
            meta = _read_meta(path)
            tid = meta.get("toolUseId") if isinstance(meta.get("toolUseId"), str) else None
            tid = tid or sc.agent_to_tid.get(agent_id)
            if not tid:
                prompt = _first_prompt(path)
                cand = sc.prompts.get(prompt) if prompt else None
                if cand and cand not in files:
                    tid = cand
            if not tid:
                rest.append((agent_id, path))
                continue
            progressed = True
            sc.agent_to_tid[agent_id] = tid
            files[tid] = path
            rec = sc.records.get(tid)
            if rec is None:
                parent_agent = meta.get("parentAgentId")
                rec = sc.records.setdefault(tid, _blank(tid, sc.agent_to_tid.get(parent_agent) if parent_agent else None))
            rec["agent_type"] = rec["agent_type"] or str(meta.get("agentType") or "")
            rec["description"] = rec["description"] or str(meta.get("description") or "")
            if meta.get("requestShape") == "background":
                rec["background"] = True
            rec["started_at"] = rec["started_at"] or _first_timestamp(path)
            sc.scan(path, tid)
        pending = rest if progressed else []

    rows: list[dict[str, Any]] = []
    for tid, rec in sc.records.items():
        if rec["status"] is None:
            path = files.get(tid)
            text, ts = _last_report(path) if path is not None else (None, None)
            if text is not None:
                rec["status"], rec["summary"], rec["ended_at"] = "done", text, ts
            else:
                rec["status"] = "stopped"
        rows.append(rec)
    rows.sort(key=lambda r: r.get("started_at") or "")
    rows = merge_summaries(rows, None)

    if len(_CACHE) >= _CACHE_MAX:
        _CACHE.pop(next(iter(_CACHE)))
    _CACHE[key] = (sig, [dict(r) for r in rows], dict(files))
    return rows, files
