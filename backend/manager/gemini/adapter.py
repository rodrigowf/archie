"""Google Gemini CLI JSONL adapter.

Gemini stores per-session JSONL files at::

    ~/.gemini/tmp/<project-label>/chats/session-<short-iso>-<uuid-prefix>.jsonl

where ``<project-label>`` is the value the CLI assigns to the current
working directory in ``~/.gemini/projects.json``, and ``<uuid-prefix>``
is the first 8 chars of the session UUID (the file name does NOT carry
the full session id — we have to peek at the header line to learn it).
The installer symlinks ``~/.gemini/tmp/<project-label>`` to the
project's ``context/``, so these files land in ``context/chats/``.

JSONL line shapes
-----------------

The on-disk JSONL is a heterogeneous append-only log; lines fall into a
few categories::

    {"sessionId": "...", "projectHash": "...",
     "startTime": "...", "lastUpdated": "...", "kind": "main"}
        — header line, written once per session start (also re-written
        when the same session id is resumed across a fresh CLI launch).

    {"id": "...", "timestamp": "...",
     "type": "user",
     "content": [{"text": "<prompt>"}]}
        — user prompt.  Note ``content`` is a list of objects with a
        ``text`` key (vs. Claude's plain-string user content).

    {"id": "...", "timestamp": "...",
     "type": "gemini",
     "content": "<assistant reply as a plain string>",
     "thoughts": [{"subject": "...", "description": "...", "timestamp": "..."}],
     "tokens": {...},
     "model": "..."}
        — assistant turn.  ``type`` is ``"gemini"`` (not ``"assistant"``),
        ``content`` is a plain string, and ``thoughts`` is a top-level
        array of {subject, description, timestamp} objects (NOT a
        ``thinking`` block inside content).

    {"$set": {"lastUpdated": "..."}}
        — bookkeeping line emitted after every meaningful change.

The file is a log, not a list of messages (CLI ``loadConversationRecord``):

* a message is re-appended with the same ``id`` every time it changes
  (thoughts, then toolCalls, then results) — **upsert by id**, last
  write wins, first position kept;
* ``{"$set": {"messages": [...]}}`` (CLI ≥ 0.45, on every resume, on
  compression and rollback) **replaces** the whole list; in these
  snapshots gemini ``content`` is a part list (``{text}``,
  ``{text, thought: true}``, ``{functionCall}``) and the first user
  message is the CLI's injected ``<session_context>`` dump (hidden);
* CLI 0.63 also records the tool answers as user messages whose content
  is only ``{functionResponse}`` parts — folded into ``tool_result``
  blocks, never shown as empty user turns;
* ``{"$rewindTo": id}`` truncates; ``info`` / ``error`` / ``warning``
  records are not turns.

Tool calls
----------

Tool calls are written *inline* on the same JSONL line as the assistant
turn, under a top-level ``toolCalls`` array (NOT as separate lines like
Qwen/Claude do).  Each entry has the shape::

    {"id": "...", "name": "...", "args": {...},
     "result": [{"functionResponse": {"id": "...", "name": "...",
                                       "response": {"output": "..."}}}],
     "status": "success" | "error",
     "resultDisplay": "<markdown rendered output>",
     "timestamp": "...",
     ...}

The adapter splits one such line into TWO normalized messages: the
assistant turn (with a ``tool_use`` block per ``toolCalls`` entry) and
a synthetic user message carrying matched ``tool_result`` blocks.  This
mirrors the Anthropic shape the frontend pairs via ``tool_use_id``.
Without this, re-opened Gemini conversations show only the text replies
and the tool calls vanish from the UI.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from utils.paths import PROJECT_ROOT

from ..protocol import ProviderAdapter, _parse_timestamp, register_provider
from ..registry import HarnessSpec, register_harness
from ..types import SessionInfo


# ``$set`` lines aren't messages — adapter skips them.
def _is_metadata_line(obj: dict) -> bool:
    return "$set" in obj and "type" not in obj


def _is_header_line(obj: dict) -> bool:
    return "sessionId" in obj and "type" not in obj and "$set" not in obj


# Map Gemini's ``type: "gemini"`` to the normalized ``assistant`` role.
def _normalize_type(t: str) -> str | None:
    if t == "user":
        return "user"
    if t == "gemini":
        return "assistant"
    return None


# User text the CLI injects itself and never shows as a user turn
# (``isIgnoredUserContent`` in the CLI; we keep ``/`` and ``?`` prompts,
# which Archie users type on purpose).
_IGNORED_USER_PREFIXES = ("<session_context>", "<hook_context>")


def _parts(content: Any) -> list[Any]:
    if isinstance(content, list):
        return content
    if isinstance(content, (str, dict)):
        return [content]
    return []


def _text_of(content: Any, *, thoughts: bool = False) -> str:
    """Join the text parts of a PartListUnion (str | part | [part]).

    ``thoughts=False`` skips ``{text, thought: true}`` parts; ``True``
    returns only those.
    """
    if isinstance(content, str):
        return "" if thoughts else content
    out: list[str] = []
    for p in _parts(content):
        if isinstance(p, str):
            if not thoughts:
                out.append(p)
        elif isinstance(p, dict) and isinstance(p.get("text"), str) and p.get("text"):
            if bool(p.get("thought")) == thoughts:
                out.append(p["text"])
    return "\n".join(out)


def _user_text(obj: dict) -> str:
    """The user-visible text of a user record (``displayContent`` first)."""
    display = obj.get("displayContent")
    text = _text_of(display) if display else ""
    if not text:
        text = _text_of(obj.get("content"))
    return text


def _is_ignored_user_text(text: str) -> bool:
    return text.lstrip().startswith(_IGNORED_USER_PREFIXES)


def _function_responses(content: Any) -> list[dict]:
    out = []
    for p in _parts(content):
        if isinstance(p, dict) and isinstance(p.get("functionResponse"), dict):
            out.append(p["functionResponse"])
    return out


def _function_response_text(resp: dict) -> tuple[str, bool]:
    inner = resp.get("response")
    if isinstance(inner, dict):
        if inner.get("output") is not None:
            return str(inner.get("output")), False
        if inner.get("error") is not None:
            return str(inner.get("error")), True
        return json.dumps(inner, ensure_ascii=False), False
    return ("" if inner is None else str(inner)), False


def _extract_tool_result_text(call: dict) -> str:
    """Pull the human-visible output for one ``toolCalls`` entry.

    Prefers ``resultDisplay`` (the markdown the CLI renders to its own
    UI) and falls back to digging through ``result[].functionResponse.
    response.output`` or ``.error``.
    """
    display = call.get("resultDisplay")
    if isinstance(display, str) and display.strip():
        return display
    result = call.get("result")
    if isinstance(result, list):
        parts: list[str] = []
        for entry in result:
            if not isinstance(entry, dict):
                continue
            resp = entry.get("functionResponse")
            if not isinstance(resp, dict):
                continue
            inner = resp.get("response")
            if not isinstance(inner, dict):
                continue
            if "output" in inner and inner.get("output") is not None:
                parts.append(str(inner.get("output")))
            elif "error" in inner and inner.get("error") is not None:
                parts.append(str(inner.get("error")))
        if parts:
            return "\n".join(parts)
    return ""


def _assistant_blocks(obj: dict) -> tuple[list[dict], list[dict]]:
    """``(assistant blocks, tool_result blocks)`` for one gemini record.

    ``content`` is a plain string in records the CLI appends while
    streaming, and a part list (``{text}``, ``{text, thought: true}``,
    ``{functionCall}``) in the history snapshots of 0.45+.  ``thoughts`` /
    ``toolCalls`` are authoritative when present; the thought and
    functionCall parts are only used when they are missing, so nothing is
    shown twice.
    """
    blocks: list[dict] = []
    thoughts = obj.get("thoughts")
    content = obj.get("content")
    if isinstance(thoughts, list) and thoughts:
        for thought in thoughts:
            if not isinstance(thought, dict):
                continue
            subj = thought.get("subject", "") or ""
            desc = thought.get("description", "") or ""
            text = f"{subj}\n{desc}".strip() if subj or desc else ""
            if text:
                blocks.append({"type": "thinking", "text": text})
    else:
        thought_text = _text_of(content, thoughts=True)
        if thought_text:
            blocks.append({"type": "thinking", "text": thought_text})

    text = _text_of(content)
    if text:
        blocks.append({"type": "text", "text": text})

    tool_result_blocks: list[dict] = []
    raw_calls = obj.get("toolCalls")
    if isinstance(raw_calls, list) and raw_calls:
        for call in raw_calls:
            if not isinstance(call, dict):
                continue
            tool_id = call.get("id")
            args = call.get("args", {})
            blocks.append({
                "type": "tool_use",
                "id": tool_id,
                "name": call.get("name", ""),
                "input": args if isinstance(args, dict) else {},
            })
            # Only a completed call (status or result present) has a
            # result; an in-flight one is the live stream's business.
            status = call.get("status")
            if status is None and "result" not in call:
                continue
            tool_result_blocks.append({
                "type": "tool_result",
                "tool_use_id": tool_id,
                "content": _extract_tool_result_text(call),
                "is_error": status == "error",
            })
    else:
        for p in _parts(content):
            fc = p.get("functionCall") if isinstance(p, dict) else None
            if isinstance(fc, dict):
                args = fc.get("args", {})
                blocks.append({
                    "type": "tool_use",
                    "id": fc.get("id"),
                    "name": fc.get("name", ""),
                    "input": args if isinstance(args, dict) else {},
                })
    return blocks, tool_result_blocks


def _normalize_message(obj: dict, answered: set[str] | None = None) -> list[dict]:
    """Translate one Gemini message record to zero or more normalized messages.

    * user text → one user message (CLI-injected ``<session_context>`` /
      ``<hook_context>`` turns and empty ones → nothing);
    * a user record carrying only ``functionResponse`` parts (0.63 records
      those) → a user message of ``tool_result`` blocks for the calls not
      already answered from ``toolCalls`` (*answered*), never an empty
      user message;
    * a gemini record → the assistant message (thinking, text, tool_use)
      plus, when it carries finished ``toolCalls``, a synthetic user message
      with the matching ``tool_result`` blocks so clients pair them by id.
    """
    raw_type = obj.get("type")
    role = _normalize_type(raw_type) if isinstance(raw_type, str) else None
    if role is None:
        return []
    timestamp = obj.get("timestamp")

    if role == "user":
        out: list[dict] = []
        text = _user_text(obj)
        if text and not _is_ignored_user_text(text):
            out.append({
                "type": "user",
                "timestamp": timestamp,
                "message": {"role": "user", "content": text},
            })
        results = []
        for resp in _function_responses(obj.get("content")):
            rid = resp.get("id")
            if answered is not None and rid in answered:
                continue
            output, is_error = _function_response_text(resp)
            results.append({
                "type": "tool_result",
                "tool_use_id": rid,
                "content": output,
                "is_error": is_error,
            })
            if answered is not None and rid:
                answered.add(rid)
        if results:
            out.append({
                "type": "user",
                "timestamp": timestamp,
                "message": {"role": "user", "content": results},
            })
        return out

    blocks, tool_result_blocks = _assistant_blocks(obj)
    if not blocks:
        return []
    out = [{
        "type": "assistant",
        "timestamp": timestamp,
        "message": {"role": "assistant", "content": blocks},
    }]
    if answered is not None:
        tool_result_blocks = [
            b for b in tool_result_blocks if b["tool_use_id"] not in answered
        ]
        answered.update(b["tool_use_id"] for b in tool_result_blocks if b["tool_use_id"])
    if tool_result_blocks:
        out.append({
            "type": "user",
            "timestamp": timestamp,
            "message": {"role": "user", "content": tool_result_blocks},
        })
    return out


def _is_visible_record(obj: dict) -> bool:
    """A record that becomes a visible turn (user text or an assistant message)."""
    t = obj.get("type")
    if t == "user":
        text = _user_text(obj)
        return bool(text) and not _is_ignored_user_text(text)
    if t == "gemini":
        return bool(_assistant_blocks(obj)[0])
    return False


@dataclass
class _Conversation:
    """A Gemini session as the CLI's own loader sees it."""

    records: list[dict] = field(default_factory=list)
    start_time: str | None = None
    last_updated: str | None = None
    session_id: str | None = None


def _iter_objs(jsonl_path: Path):
    with open(jsonl_path) as f:
        for line in f:
            line = line.strip()
            if not line:
                yield None
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                yield None
                continue
            yield obj if isinstance(obj, dict) else None


def _load_conversation(objs) -> _Conversation:
    """Replay the append-only log like ``loadConversationRecord`` in the CLI.

    * message records are **upserted by id** (the CLI re-appends a message
      every time it changes: thoughts → toolCalls → results), keeping the
      position of the first write and the content of the last;
    * ``{"$set": {"messages": [...]}}`` replaces the whole list (history
      snapshots on resume / compression / rollback);
    * ``{"$rewindTo": id}`` drops that message and everything after it
      (all of it when the id is unknown);
    * ``info`` / ``error`` / ``warning`` records are not turns.
    """
    conv = _Conversation()
    by_id: dict[str, dict] = {}
    anon = 0

    def put(rec: dict) -> None:
        nonlocal anon
        rid = rec.get("id")
        if not isinstance(rid, str) or not rid:
            anon += 1
            rid = f"\x00anon-{anon}"
        by_id[rid] = rec

    for obj in objs:
        if not isinstance(obj, dict):
            continue
        if "$rewindTo" in obj and "type" not in obj:
            target = obj.get("$rewindTo")
            keys = list(by_id)
            if target in by_id:
                for k in keys[keys.index(target):]:
                    del by_id[k]
            else:
                by_id.clear()
            continue
        if _is_metadata_line(obj):
            s = obj.get("$set")
            if isinstance(s, dict):
                if isinstance(s.get("lastUpdated"), str):
                    conv.last_updated = s["lastUpdated"]
                if isinstance(s.get("messages"), list):
                    by_id.clear()
                    for m in s["messages"]:
                        if isinstance(m, dict) and m.get("type") in ("user", "gemini"):
                            put(m)
            continue
        if _is_header_line(obj):
            if conv.start_time is None and isinstance(obj.get("startTime"), str):
                conv.start_time = obj["startTime"]
            if conv.session_id is None and isinstance(obj.get("sessionId"), str):
                conv.session_id = obj["sessionId"]
            last = obj.get("lastUpdated") or obj.get("startTime")
            if isinstance(last, str):
                conv.last_updated = last
            continue
        if obj.get("type") in ("user", "gemini"):
            put(obj)
            ts = obj.get("timestamp")
            if isinstance(ts, str) and conv.start_time is None:
                conv.start_time = ts
            if isinstance(ts, str):
                conv.last_updated = ts
    conv.records = list(by_id.values())
    return conv


def _load_conversation_file(jsonl_path: Path) -> _Conversation | None:
    try:
        return _load_conversation(_iter_objs(jsonl_path))
    except (OSError, PermissionError):
        return None


def _normalize_records(records: list[dict]) -> list[dict]:
    answered: set[str] = set()
    out: list[dict] = []
    for rec in records:
        out.extend(_normalize_message(rec, answered))
    return out


def _is_resumable_record(obj: dict) -> bool:
    """The CLI's ``isResumableMessageRecord``: what makes ``--resume`` accept a file."""
    t = obj.get("type")
    if t == "user":
        text = _text_of(obj.get("content")).strip()
        return bool(text) and not text.startswith(("/", "?") + _IGNORED_USER_PREFIXES)
    if t == "gemini":
        return bool(
            _text_of(obj.get("content")).strip()
            or obj.get("toolCalls")
            or obj.get("thoughts")
        )
    return False


def gemini_session_is_resumable(jsonl_path: Path) -> bool:
    """True when the CLI's ``--resume`` would find content in *jsonl_path*.

    Both CLI versions write the header line (0.63 also a ``$set`` snapshot)
    *before* authenticating, so a turn that failed early leaves a stub that
    ``--resume`` rejects while ``--session-id`` refuses its id.
    """
    conv = _load_conversation_file(jsonl_path)
    return bool(conv and any(_is_resumable_record(r) for r in conv.records))


class GeminiAdapter(ProviderAdapter):
    """Adapter for Google Gemini CLI's native JSONL format."""

    @property
    def provider_name(self) -> str:
        return "gemini"

    def detect_provider(self, jsonl_path: Path) -> bool:
        """Detect Gemini format by looking for its characteristic header line
        (``sessionId`` + ``projectHash`` + ``kind`` field, present on the
        first non-empty line of every Gemini session JSONL) or the
        ``type: "gemini"`` assistant marker that no other harness uses."""
        try:
            with open(jsonl_path) as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        obj = json.loads(line)
                    except json.JSONDecodeError:
                        continue

                    # Most reliable signature — present on line 1 of every
                    # Gemini session file.  Claude has no "projectHash"
                    # field and Qwen has no "kind" field.
                    if (
                        isinstance(obj, dict)
                        and "sessionId" in obj
                        and "projectHash" in obj
                        and "kind" in obj
                    ):
                        return True

                    # Fallback for sessions where the header line is
                    # somehow missing or malformed: the assistant role
                    # ``"gemini"`` is unique to this harness.
                    if obj.get("type") == "gemini":
                        return True
        except (OSError, PermissionError):
            pass
        return False

    def is_visible_message(self, obj: dict) -> bool:
        """Visibility of one raw Gemini record.

        Gemini's native shape is flat (``{type, content, ...}``, assistant
        role ``"gemini"``), so the protocol default misses it.  An assistant
        record with empty ``content`` but thoughts and/or tool calls is a
        real turn; a user record is visible only with real text (not the
        CLI's ``<session_context>`` or a bare ``functionResponse``).
        """
        if not isinstance(obj, dict):
            return False
        return _is_visible_record(obj)

    def visible_line_indices(self, objs: list[dict | None]) -> list[int]:
        """Line positions to cut at when dropping the last N visible turns.

        Records are rewritten many times (upserts, snapshots), so a visible
        turn maps to the last line *before* the next record's first write —
        a prefix ending there replays to exactly the turns before it.
        """
        conv = _load_conversation(objs)
        first_line: dict[str, int] = {}
        for i, obj in enumerate(objs):
            if isinstance(obj, dict) and obj.get("type") in ("user", "gemini"):
                rid = obj.get("id")
                if isinstance(rid, str):
                    first_line.setdefault(rid, i)
        last = len(objs) - 1
        out: list[int] = []
        records = conv.records
        for pos, rec in enumerate(records):
            if not _is_visible_record(rec):
                continue
            mine = first_line.get(rec.get("id"), -1)
            cut = last
            for nxt in records[pos + 1:]:
                nl = first_line.get(nxt.get("id"))
                if nl is not None and nl > mine:
                    cut = nl - 1
                    break
            if out and cut < out[-1]:
                cut = out[-1]
            out.append(cut)
        return out

    def read_messages(self, jsonl_path: Path) -> list[dict]:
        """Read the conversation as the CLI would reload it, normalized.

        Each message appears once (upsert by id, snapshots, rewinds); an
        assistant turn that used tools fans out into the assistant message
        plus a synthetic tool-result user message.
        """
        conv = _load_conversation_file(jsonl_path)
        if conv is None:
            return []
        return _normalize_records(conv.records)

    def parse_session_info(
        self,
        jsonl_path: Path,
        session_id: str,
        titles: dict[str, str] | None = None,
    ) -> SessionInfo | None:
        """Summary metadata: header times, first real user text as title,
        visible turn count (each message counted once)."""
        conv = _load_conversation_file(jsonl_path)
        if conv is None or conv.start_time is None:
            return None
        first_user_text = ""
        message_count = 0
        for rec in conv.records:
            if not _is_visible_record(rec):
                continue
            message_count += 1
            if not first_user_text and rec.get("type") == "user":
                first_user_text = _user_text(rec)
        title = (titles or {}).get(session_id) or (
            first_user_text[:100] if first_user_text else "(empty session)"
        )
        try:
            started = _parse_timestamp(conv.start_time)
        except (TypeError, ValueError):
            return None
        try:
            last = _parse_timestamp(conv.last_updated) if conv.last_updated else started
        except (TypeError, ValueError):
            last = started
        return SessionInfo(
            session_id=session_id,
            started_at=started,
            last_activity=last,
            title=title,
            message_count=message_count,
        )


_adapter = GeminiAdapter()
register_provider(_adapter)


# ---------------------------------------------------------------------------
# HarnessSpec registration
# ---------------------------------------------------------------------------


def _load_gemini_session_class():
    from .session import GeminiSessionManager
    return GeminiSessionManager


def _load_gemini_kill_helper():
    # Gemini runs as Node.js (same as Qwen), so /proc/<pid>/comm shows up
    # as ``node``.  See the orphan reaper note in :mod:`manager.registry`
    # — the registry dispatches by spec name from ``_tracked_pids``, so
    # sharing a comm prefix with Qwen doesn't cause misdirected kills.
    from .._proc import kill_subprocess

    def kill_gemini_subprocess(pid: int, *, sigterm_grace_s: float = 0.5) -> bool:
        return kill_subprocess(pid, comm_prefix="node", sigterm_grace_s=sigterm_grace_s)

    return kill_gemini_subprocess


def _gemini_home() -> Path:
    """Resolve the Gemini CLI's storage directory.

    Honors ``GEMINI_HOME`` if set; falls back to ``~/.gemini`` (the CLI's
    own default).  Used by both the JSONL path resolver and the
    list-models endpoint.
    """
    explicit = os.environ.get("GEMINI_HOME")
    if explicit:
        return Path(explicit).expanduser()
    return Path.home() / ".gemini"


def _gemini_project_label(project_dir: str | None = None) -> str | None:
    """Return the Gemini CLI's per-project subdirectory name for *project_dir*.

    Gemini maintains ``~/.gemini/projects.json`` mapping absolute cwd
    strings to short labels (e.g. ``/home/rodrigo/assistant`` → ``assistant``).
    Session JSONL files live under ``~/.gemini/tmp/<label>/chats/``, so
    we need this lookup to find the right directory.

    Returns None if the project hasn't been registered yet (i.e. the
    user hasn't run ``gemini`` from that directory).  The session
    manager handles this case by deriving the label from the cwd basename
    instead (which is what the CLI itself does on first run).
    """
    projects_file = _gemini_home() / "projects.json"
    if not projects_file.is_file():
        return None
    try:
        data = json.loads(projects_file.read_text())
    except (json.JSONDecodeError, OSError):
        return None
    projects = data.get("projects", {}) if isinstance(data, dict) else {}
    if not isinstance(projects, dict):
        return None
    # If a project_dir was given, look it up; otherwise return None.
    if project_dir is None:
        return None
    label = projects.get(project_dir)
    return label if isinstance(label, str) else None


def _gemini_chats_dir(project_dir: str) -> Path:
    """Where Gemini JSONLs live for *project_dir*.

    install.sh symlinks ``~/.gemini/tmp/<label>`` → ``<project_dir>/context``
    (the Gemini step of ``install/linux/install.sh``), so the Gemini CLI writes its
    session files into the same ``context/chats/`` directory Qwen uses.
    Same path resolution as :meth:`SessionStore._resolve_chats_dir`.
    """
    return Path(project_dir) / "context" / "chats"


def _gemini_jsonl_candidates(session_id: str) -> list[Path]:
    """Return candidate JSONL paths for *session_id*.

    Gemini's file name is ``session-<short-iso>-<uuid-prefix>.jsonl`` where
    ``<uuid-prefix>`` is the first 8 chars of the session UUID, so the full
    id alone isn't enough to construct a deterministic path — we glob by
    prefix instead.  Scans both the live ``context/chats/`` (where the CLI
    writes now, via the symlink) and the legacy ``~/.gemini/tmp/<label>/chats/``
    layout so pre-migration sessions stay reachable on hosts where the
    symlink hasn't been set up yet.
    """
    short = session_id[:8]
    if not short:
        return []
    out: list[Path] = []
    # Live location: context/chats/.  Project dir comes from ManagerConfig,
    # which we don't have here — fall back to the default project dir, since
    # the JSONLs are project-scoped and there's only one ``context/`` per
    # install.
    try:
        from manager.config import ManagerConfig
        project_dir = ManagerConfig.load().project_dir
    except Exception:
        project_dir = str(PROJECT_ROOT)
    chats_dir = _gemini_chats_dir(project_dir)
    if chats_dir.is_dir():
        try:
            out.extend(
                f for f in chats_dir.glob(f"session-*-{short}.jsonl") if f.is_file()
            )
        except OSError:
            pass
    # Legacy location: ~/.gemini/tmp/<label>/chats/.  Only relevant on hosts
    # where the installer hasn't created the symlink yet; once the symlink
    # is in place the chats_dir resolves to the same path via two routes.
    tmp_root = _gemini_home() / "tmp"
    if tmp_root.is_dir():
        try:
            for label_dir in tmp_root.iterdir():
                legacy_chats = label_dir / "chats"
                # Skip the symlinked label — we already scanned that path above.
                try:
                    if legacy_chats.resolve() == chats_dir.resolve():
                        continue
                except OSError:
                    pass
                if not legacy_chats.is_dir():
                    continue
                try:
                    out.extend(
                        f for f in legacy_chats.glob(f"session-*-{short}.jsonl")
                        if f.is_file()
                    )
                except OSError:
                    continue
        except OSError:
            pass
    return out


def _gemini_discover_sessions(project_dir: str):
    """Yield ``(session_id, jsonl_path)`` for Gemini sessions in *project_dir*.

    Scans the project's ``context/chats/`` directory for the
    ``session-<iso>-<uuid-prefix>.jsonl`` naming pattern the Gemini CLI
    uses (Qwen's JSONLs in the same directory are ``<full-uuid>.jsonl``
    and don't match the glob, so the two harnesses coexist cleanly).

    The full session id is NOT in the file name (only the first 8 chars
    are), so we peek at the header line to recover it.  Skips files with
    malformed headers rather than crashing.
    """
    chats_dir = _gemini_chats_dir(project_dir)
    if not chats_dir.is_dir():
        return
    try:
        jsonl_files = list(chats_dir.glob("session-*.jsonl"))
    except OSError:
        return
    for jsonl_path in jsonl_files:
        if not jsonl_path.is_file():
            continue
        session_id = _read_gemini_session_id(jsonl_path)
        if session_id:
            yield session_id, jsonl_path


def _read_gemini_session_id(jsonl_path: Path) -> str | None:
    """Read the header line of a Gemini JSONL and return its ``sessionId``."""
    try:
        with open(jsonl_path) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    return None
                if isinstance(obj, dict):
                    sid = obj.get("sessionId")
                    if isinstance(sid, str) and sid:
                        return sid
                return None
    except OSError:
        return None
    return None


def _load_gemini_catalog():
    from .catalog import load_gemini_catalog
    return load_gemini_catalog()


register_harness(HarnessSpec(
    name="gemini",
    label="Gemini CLI",
    description="Google's Gemini CLI — Node-based; needs GEMINI_API_KEY (Google retired personal-account logins on 2026-06-18).",
    session_class_loader=_load_gemini_session_class,
    adapter_loader=lambda: _adapter,
    # Same as Qwen — Node-based; the spec name is what the reaper uses
    # to dispatch, not the comm prefix alone.
    comm_prefix="node",
    kill_helper_loader=_load_gemini_kill_helper,
    ssh_control_path_prefix="gemini",
    jsonl_path_resolver=_gemini_jsonl_candidates,
    session_discoverer=_gemini_discover_sessions,
    requirements_file=None,  # external Node CLI; no Python deps
    npm_package="@google/gemini-cli",
    cli_binary="gemini",
    # AI Studio key — the only auth Google still serves to individuals
    # (Vertex / Code Assist Standard users set ARCHIE_GEMINI_AUTH_TYPE).
    env_keys=("GEMINI_API_KEY",),
    catalog_loader=_load_gemini_catalog,
))
