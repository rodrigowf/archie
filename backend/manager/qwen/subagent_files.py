"""Qwen Code subagent history on disk (spec 12 SA-8).

Layout (qwen-code 0.25, under the project's Qwen data dir — for the Archie
repo ``~/.qwen/projects/<mangled repo>`` is a symlink to ``context/``)::

    chats/<sid>.jsonl                                  the parent conversation
    subagents/<sid>/agent-<agentId>.jsonl              one transcript per subagent
    subagents/<sid>/agent-<agentId>.meta.json          {agentId, agentType, description,
                                                        toolUseId, parentAgentId, status,
                                                        isBackgrounded, createdAt,
                                                        lastUpdatedAt, depth, ...}

``agentId`` is ``<subagent type>-<spawning call id>``; ``toolUseId`` is the
spawning ``agent`` call (the subagent key).  Transcripts use the session
JSONL's own line format (``isSidechain: true``), so the parent's
:class:`~manager.qwen.adapter.QwenAdapter` normalisation applies; the first
user line is the agent's prompt.  The files are written while the agent runs.

A spawning call with no sidecar (it failed before the agent started) is still
listed from the parent JSONL so the client's placeholder gets a final status.
"""

from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any

from ..subagents import is_spawn_tool, spawn_fields, usage_dict, wire_status

_DEFAULT_TYPE = "general-purpose"


def qwen_home() -> Path:
    home = os.environ.get("QWEN_HOME")
    return Path(home) if home else Path.home() / ".qwen"


def sanitize_cwd(path: str) -> str:
    """Qwen's project key: every non-alphanumeric character becomes ``-``."""
    return re.sub(r"[^A-Za-z0-9]", "-", path)


def qwen_subagent_dir(project_dir: str, session_id: str) -> Path:
    """Live location of a session's subagent transcripts (local runs)."""
    return qwen_home() / "projects" / sanitize_cwd(project_dir) / "subagents" / session_id


def subagent_dir_for(parent_jsonl: Path) -> Path:
    """``<data dir>/subagents/<sid>`` for ``<data dir>/chats/<sid>.jsonl``."""
    return parent_jsonl.parent.parent / "subagents" / parent_jsonl.stem


def _read_json(path: Path) -> dict[str, Any]:
    try:
        obj = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return obj if isinstance(obj, dict) else {}


def _iter_lines(path: Path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            for raw in f:
                raw = raw.strip()
                if not raw:
                    continue
                try:
                    obj = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                if isinstance(obj, dict):
                    yield obj
    except OSError:
        return


def _answer_text(obj: dict) -> str:
    parts = (obj.get("message") or {}).get("parts") or []
    return "".join(
        p.get("text", "") for p in parts
        if isinstance(p, dict) and isinstance(p.get("text"), str) and not p.get("thought")
    ).strip()


def transcript_report(path: Path) -> str | None:
    """The agent's last answer (non-thought assistant text) in *path*."""
    report = None
    for obj in _iter_lines(path):
        if obj.get("type") == "assistant":
            text = _answer_text(obj)
            if text:
                report = text
    return report


def _transcript_stats(path: Path) -> tuple[str | None, int, str | None]:
    """``(report, tool calls, last timestamp)`` of a transcript."""
    report, calls, last_ts = None, 0, None
    for obj in _iter_lines(path):
        if isinstance(obj.get("timestamp"), str):
            last_ts = obj["timestamp"]
        if obj.get("type") != "assistant":
            continue
        parts = (obj.get("message") or {}).get("parts") or []
        calls += sum(1 for p in parts if isinstance(p, dict) and isinstance(p.get("functionCall"), dict))
        text = _answer_text(obj)
        if text:
            report = text
    return report, calls, last_ts


def _transcripts(sub_dir: Path) -> list[tuple[dict[str, Any], Path]]:
    """``(meta, transcript path)`` per subagent, oldest first."""
    out: list[tuple[dict[str, Any], Path]] = []
    if not sub_dir.is_dir():
        return out
    try:
        names = sorted(os.listdir(sub_dir))
    except OSError:
        return out
    for name in names:
        if not (name.startswith("agent-") and name.endswith(".jsonl")):
            continue
        path = sub_dir / name
        meta = _read_json(path.with_name(name[: -len(".jsonl")] + ".meta.json"))
        meta.setdefault("agentId", name[len("agent-"):-len(".jsonl")])
        out.append((meta, path))
    out.sort(key=lambda mp: str(mp[0].get("createdAt") or ""))
    return out


_TAG_RE = {
    tag: re.compile(rf"<{tag}>(.*?)</{tag}>", re.DOTALL)
    for tag in ("tool-use-id", "status", "total_tokens", "tool_uses", "duration_ms")
}


def _tag(text: str, tag: str) -> str | None:
    m = _TAG_RE[tag].search(text)
    return m.group(1).strip() if m else None


def _spawn_calls(parent_jsonl: Path) -> list[dict[str, Any]]:
    """Spawning ``agent`` calls in the parent JSONL, with their recorded
    outcome (tool result; a background agent's ``<task-notification>``)."""
    calls: dict[str, dict[str, Any]] = {}
    for obj in _iter_lines(parent_jsonl):
        t = obj.get("type")
        parts = (obj.get("message") or {}).get("parts") or []
        if t == "user" and obj.get("subtype") == "notification":
            text = "".join(p.get("text", "") for p in parts if isinstance(p, dict) and isinstance(p.get("text"), str))
            tid = _tag(text, "tool-use-id") if text.startswith("<task-notification>") else None
            if tid in calls:
                calls[tid]["notification"] = {
                    "status": _tag(text, "status"),
                    "usage": usage_dict(_tag(text, "total_tokens"), _tag(text, "tool_uses"), _tag(text, "duration_ms")),
                    "ts": obj.get("timestamp"),
                }
        elif t == "assistant":
            for p in parts:
                fc = p.get("functionCall") if isinstance(p, dict) else None
                if isinstance(fc, dict) and fc.get("id") and is_spawn_tool(fc.get("name")):
                    calls[fc["id"]] = {"args": fc.get("args") or {}, "ts": obj.get("timestamp"),
                                       "status": None, "output": None}
        elif t == "tool_result":
            res = obj.get("toolCallResult") if isinstance(obj.get("toolCallResult"), dict) else {}
            for p in parts:
                fr = p.get("functionResponse") if isinstance(p, dict) else None
                if isinstance(fr, dict) and fr.get("id") in calls:
                    call = calls[fr["id"]]
                    resp = fr.get("response") if isinstance(fr.get("response"), dict) else {}
                    call["status"] = res.get("status") or ("error" if resp.get("error") else "success")
                    out = resp.get("output") if resp.get("output") is not None else resp.get("error")
                    call["output"] = out if isinstance(out, str) else (json.dumps(out) if out is not None else None)
                    call["end_ts"] = obj.get("timestamp")
    return [dict(v, id=k) for k, v in calls.items()]


def scan(parent_jsonl: Path) -> tuple[list[dict[str, Any]], dict[str, Path]]:
    """``(SubagentSummary rows, tool_use_id → transcript)`` for one session."""
    rows: dict[str, dict[str, Any]] = {}
    files: dict[str, Path] = {}
    by_agent_id: dict[str, str] = {}

    for call in _spawn_calls(parent_jsonl):
        agent_type, description, background = spawn_fields(call["args"])
        status = call.get("status")
        output = call.get("output") or ""
        row = {
            "tool_use_id": call["id"], "parent_tool_use_id": None,
            "agent_type": agent_type or _DEFAULT_TYPE, "description": description,
            "background": background, "status": None, "activity": None, "usage": None,
            "summary": None, "started_at": call.get("ts"), "ended_at": None,
        }
        if status in ("error", "cancelled"):
            row["status"] = "failed" if status == "error" else "stopped"
            row["summary"] = output or None
            row["ended_at"] = call.get("end_ts")
        elif status == "success" and not background and "Background agent launched" not in output:
            row["status"], row["summary"], row["ended_at"] = "done", output or None, call.get("end_ts")
        note = call.get("notification")
        if note:
            row["background"] = True
            row["status"] = wire_status(note.get("status")) or row["status"]
            row["usage"] = note.get("usage")
            row["ended_at"] = note.get("ts") or row["ended_at"]
        rows[call["id"]] = row

    for meta, path in _transcripts(subagent_dir_for(parent_jsonl)):
        tid = meta.get("toolUseId")
        if not isinstance(tid, str) or not tid:
            continue
        files[tid] = path
        if isinstance(meta.get("agentId"), str):
            by_agent_id[meta["agentId"]] = tid
        row = rows.get(tid) or {
            "tool_use_id": tid, "parent_tool_use_id": None, "agent_type": "", "description": "",
            "background": False, "status": None, "activity": None, "usage": None,
            "summary": None, "started_at": None, "ended_at": None,
        }
        row["agent_type"] = str(meta.get("agentType") or meta.get("subagentName") or row["agent_type"] or _DEFAULT_TYPE)
        row["description"] = str(meta.get("description") or row["description"] or "")
        row["background"] = bool(meta.get("isBackgrounded")) or bool(row["background"])
        row["started_at"] = meta.get("createdAt") or row["started_at"]
        row["_parent_agent"] = meta.get("parentAgentId")
        report, calls, last_ts = _transcript_stats(path)
        status = wire_status(meta.get("status"))
        if status is not None:
            row["status"] = status
            row["ended_at"] = meta.get("lastUpdatedAt") or last_ts or row["ended_at"]
        row["summary"] = report or row["summary"]
        if not row.get("usage"):
            row["usage"] = usage_dict(None, calls, None)
        rows[tid] = row

    out: list[dict[str, Any]] = []
    for row in rows.values():
        parent_agent = row.pop("_parent_agent", None)
        if parent_agent:
            row["parent_tool_use_id"] = by_agent_id.get(parent_agent)
        if row["status"] is None:
            # Not running any more (live rows win while a turn runs): the CLI
            # process that ran it is gone.
            row["status"] = "done" if row.get("summary") else "stopped"
        out.append(row)
    return out, files
