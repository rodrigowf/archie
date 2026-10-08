"""OpenAI Codex rollout adapter + HarnessSpec.

Codex writes one *rollout* JSONL per thread::

    $CODEX_HOME/sessions/YYYY/MM/DD/rollout-<local YYYY-MM-DDTHH-MM-SS>-<thread id>.jsonl

The file name carries the full thread id (a UUIDv7), so the resolver can
glob for it; the nested date directories keep rollouts out of
``SessionStore``'s flat ``context/*.jsonl`` / ``context/chats/*.jsonl``
scans.  With Archie's dedicated ``CODEX_HOME`` (``~/.codex-archie``) the
installer symlinks ``sessions/`` to ``context/codex/sessions``; on the
shared ``~/.codex`` fallback they stay there (see :mod:`manager.codex.home`).

Rollout generations
-------------------

Every line is ``{"timestamp", "type", "payload"}`` (current CLIs also add
``ordinal``).  Line 0 is ``session_meta`` (``payload.id``, ``cwd``,
``originator``, ``cli_version``, a ~18 KB ``base_instructions``).  Three
shapes for the conversation itself:

* **items** (CLI ≥ 0.15x): ``event_msg`` / ``item_completed`` with
  ``payload.item.type`` ∈ ``UserMessage``, ``AgentMessage``, ``Reasoning``,
  ``CommandExecution``, ``FileChange``, ``McpToolCall``, ``WebSearch`` …
  — one line per finished item, in order.  Preferred when present.
* **events** (CLI 0.4x–0.6x): ``event_msg`` ``user_message`` /
  ``agent_message`` / ``agent_reasoning`` for the text, ``response_item``
  ``function_call`` / ``function_call_output`` (and ``custom_tool_call``
  for ``apply_patch``) for tools.
* **responses** (oldest, 2025-09): bare Responses-API items with no
  ``payload`` wrapper (``{"type": "message", "role": …}``) after a
  ``{"id", "timestamp", "instructions"}`` header.

``response_item`` ``message`` lines are the raw model input — they include
the ``developer`` instructions and an ``<environment_context>`` user
message Codex injects every session; those are never shown.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from utils.paths import PROJECT_ROOT

from ..protocol import ProviderAdapter, _parse_timestamp, register_provider
from ..registry import HarnessSpec, register_harness
from ..types import SessionInfo
from . import home, items

_ROLLOUT_RE = re.compile(
    r"^rollout-.+-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$", re.I,
)

# Injected user-role messages that are context, not a real turn.
_INJECTED_PREFIXES = (
    "<environment_context>",
    "<user_instructions>",
    "<permissions instructions>",
    "<skills_instructions>",
    "<apps_instructions>",
    "<turn_aborted>",
    "# AGENTS.md instructions",
)

_SHELL_TOOLS = {"shell", "shell_command", "exec_command", "local_shell", "container.exec"}


def session_id_from_path(path: Path) -> str | None:
    m = _ROLLOUT_RE.match(path.stem)
    return m.group(1) if m else None


def _is_injected(text: str) -> bool:
    return text.lstrip().startswith(_INJECTED_PREFIXES)


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


def _is_header(obj: dict) -> bool:
    if obj.get("type") == "session_meta":
        payload = obj.get("payload")
        return isinstance(payload, dict) and bool(payload.get("id") or payload.get("session_id"))
    # 2025-09 rollouts: bare header line.
    return (
        "type" not in obj and "id" in obj and "timestamp" in obj and "instructions" in obj
    )


def _response_record(obj: dict) -> dict | None:
    """The Responses-API item of a line (wrapped or bare), else None."""
    if obj.get("type") == "response_item" and isinstance(obj.get("payload"), dict):
        return obj["payload"]
    if "payload" not in obj and obj.get("type") in (
        "message", "reasoning", "function_call", "function_call_output",
        "custom_tool_call", "custom_tool_call_output", "local_shell_call",
    ):
        return obj
    return None


def _event(obj: dict) -> dict | None:
    if obj.get("type") == "event_msg" and isinstance(obj.get("payload"), dict):
        return obj["payload"]
    return None


def _message_content_text(content: Any) -> str:
    if isinstance(content, str):
        return content
    parts: list[str] = []
    if isinstance(content, list):
        for c in content:
            if isinstance(c, dict) and isinstance(c.get("text"), str):
                parts.append(c["text"])
    return "\n".join(parts)


def _function_call_tool(rec: dict) -> tuple[str, dict]:
    """Legacy ``function_call`` / ``custom_tool_call`` → (name, input)."""
    name = str(rec.get("name") or "tool")
    raw = rec.get("arguments") if "arguments" in rec else rec.get("input")
    args: Any = raw
    if isinstance(raw, str):
        try:
            args = json.loads(raw)
        except json.JSONDecodeError:
            args = raw
    if name in _SHELL_TOOLS and isinstance(args, dict):
        cmd = args.get("command") if "command" in args else args.get("cmd")
        return "Bash", {"command": items.display_command(cmd)}
    if name == "apply_patch":
        if isinstance(args, dict):
            args = args.get("input", "")
        return "apply_patch", {"patch": args if isinstance(args, str) else ""}
    if isinstance(args, dict):
        return name, args
    return name, {"input": args}


def _function_output_text(rec: dict) -> tuple[str, bool]:
    out = rec.get("output")
    if isinstance(out, list):
        return _message_content_text(out), False
    if isinstance(out, dict):
        text = out.get("output") or out.get("content") or json.dumps(out)
        code = (out.get("metadata") or {}).get("exit_code")
        return str(text), isinstance(code, int) and code != 0
    if isinstance(out, str):
        # apply_patch outputs are JSON {"output", "metadata": {"exit_code"}}.
        try:
            parsed = json.loads(out)
        except json.JSONDecodeError:
            parsed = None
        if isinstance(parsed, dict) and "output" in parsed:
            code = (parsed.get("metadata") or {}).get("exit_code")
            return str(parsed.get("output") or ""), isinstance(code, int) and code != 0
        m = re.match(r"^Exit code: (-?\d+)", out)
        return out, bool(m and m.group(1) != "0")
    return "" if out is None else str(out), False


class _Builder:
    """Accumulates normalized messages, merging consecutive same-role blocks.

    Each message remembers the index of the last raw line that contributed
    to it (``_last``), so :meth:`CodexAdapter.visible_line_indices` can map
    REST messages back onto lines for ``truncate_session``.
    """

    def __init__(self) -> None:
        self.messages: list[dict] = []
        self.line = -1

    def _new(self, role: str, content: Any, ts: str | None) -> None:
        self.messages.append({
            "type": role, "timestamp": ts, "message": {"role": role, "content": content},
            "_last": self.line,
        })

    def user_text(self, text: str, ts: str | None) -> None:
        self._new("user", text, ts)

    def assistant_block(self, block: dict, ts: str | None) -> None:
        last = self.messages[-1] if self.messages else None
        if last and last["type"] == "assistant":
            last["message"]["content"].append(block)
            last["_last"] = self.line
            return
        self._new("assistant", [block], ts)

    def tool_result(self, tool_use_id: str, output: str, is_error: bool, ts: str | None) -> None:
        block = {"type": "tool_result", "tool_use_id": tool_use_id, "content": output, "is_error": is_error}
        last = self.messages[-1] if self.messages else None
        if (
            last and last["type"] == "user" and isinstance(last["message"]["content"], list)
            and all(b.get("type") == "tool_result" for b in last["message"]["content"])
        ):
            last["message"]["content"].append(block)
            last["_last"] = self.line
            return
        self._new("user", [block], ts)


def _generation(objs: list[dict | None]) -> str:
    has_events = False
    for obj in objs:
        ev = _event(obj) if obj is not None else None
        if ev is None:
            continue
        if ev.get("type") == "item_completed":
            return "items"
        if ev.get("type") in ("user_message", "agent_message"):
            has_events = True
    return "events" if has_events else "responses"


def _normalize(objs: list[dict | None]) -> list[dict]:
    """Normalized messages (with internal ``_last`` line indices)."""
    b = _Builder()
    gen = _generation(objs)
    for idx, obj in enumerate(objs):
        if obj is None:
            continue
        b.line = idx
        ts = obj.get("timestamp") if isinstance(obj.get("timestamp"), str) else None
        if gen == "items":
            ev = _event(obj)
            if not ev or ev.get("type") != "item_completed" or not isinstance(ev.get("item"), dict):
                continue
            item = ev["item"]
            kind = items.item_kind(item)
            if kind == "userMessage":
                text = items.message_text(item)
                if text and not _is_injected(text):
                    b.user_text(text, ts)
            elif kind in ("agentMessage", "plan"):
                text = items.message_text(item)
                if text:
                    b.assistant_block({"type": "text", "text": text}, ts)
            elif kind == "reasoning":
                text = items.reasoning_text(item)
                if text:
                    b.assistant_block({"type": "thinking", "text": text}, ts)
            else:
                call = items.tool_call(item)
                if call is None or not item.get("id"):
                    continue
                b.assistant_block({"type": "tool_use", "id": item["id"], "name": call[0], "input": call[1]}, ts)
                output, is_error = items.tool_result(item)
                b.tool_result(item["id"], output, is_error, ts)
        elif gen == "events":
            ev = _event(obj)
            if ev is not None:
                et = ev.get("type")
                if et == "user_message":
                    text = ev.get("message") or ""
                    if isinstance(text, str) and text and not _is_injected(text):
                        b.user_text(text, ts)
                elif et == "agent_message":
                    text = ev.get("message") or ""
                    if isinstance(text, str) and text:
                        b.assistant_block({"type": "text", "text": text}, ts)
                elif et == "agent_reasoning":
                    text = ev.get("text") or ""
                    if isinstance(text, str) and text:
                        b.assistant_block({"type": "thinking", "text": text}, ts)
                continue
            rec = _response_record(obj)
            if rec is not None:
                _tool_from_record(b, rec, ts)
        else:  # responses
            rec = _response_record(obj)
            if rec is None:
                continue
            rtype = rec.get("type")
            if rtype == "message":
                text = _message_content_text(rec.get("content"))
                if not text:
                    continue
                if rec.get("role") == "user" and not _is_injected(text):
                    b.user_text(text, ts)
                elif rec.get("role") == "assistant":
                    b.assistant_block({"type": "text", "text": text}, ts)
            elif rtype == "reasoning":
                summary = rec.get("summary") or []
                text = "\n\n".join(
                    s.get("text", "") for s in summary if isinstance(s, dict) and s.get("text")
                )
                if text:
                    b.assistant_block({"type": "thinking", "text": text}, ts)
            else:
                _tool_from_record(b, rec, ts)
    return b.messages


def _strip(messages: list[dict]) -> list[dict]:
    for m in messages:
        m.pop("_last", None)
    return messages


def _tool_from_record(b: _Builder, rec: dict, ts: str | None) -> None:
    rtype = rec.get("type")
    if rtype in ("function_call", "custom_tool_call"):
        call_id = rec.get("call_id") or rec.get("id")
        if not call_id:
            return
        name, tool_input = _function_call_tool(rec)
        b.assistant_block({"type": "tool_use", "id": call_id, "name": name, "input": tool_input}, ts)
    elif rtype in ("function_call_output", "custom_tool_call_output"):
        call_id = rec.get("call_id")
        if not call_id:
            return
        output, is_error = _function_output_text(rec)
        b.tool_result(call_id, output, is_error, ts)


def _visible(msg: dict) -> bool:
    content = msg["message"]["content"]
    if msg["type"] == "user" and isinstance(content, list):
        return any(b.get("type") != "tool_result" for b in content)
    return True


class CodexAdapter(ProviderAdapter):
    """Adapter for Codex rollout JSONL (all three generations)."""

    @property
    def provider_name(self) -> str:
        return "codex"

    def detect_provider(self, jsonl_path: Path) -> bool:
        """Line 0 is ``session_meta`` with an id (or the 2025-09 bare header).

        No other harness writes ``session_meta``; Gemini's header has
        ``sessionId``/``projectHash``, Claude's lines carry ``message``.
        """
        for obj in _iter_lines(jsonl_path):
            return _is_header(obj)
        return False

    def read_messages(self, jsonl_path: Path) -> list[dict]:
        return _strip(_normalize(list(_iter_lines(jsonl_path))))

    def is_visible_message(self, obj: dict) -> bool:
        return any(_visible(m) for m in _normalize([obj]))

    def visible_line_indices(self, objs: list[dict | None]) -> list[int]:
        """One index per visible REST message: its last contributing line.

        Consistent with :meth:`read_messages`, so ``truncate_session``'s
        "drop the last N messages" keeps whole messages.
        """
        return [m["_last"] for m in _normalize(objs) if _visible(m)]

    def parse_session_info(
        self,
        jsonl_path: Path,
        session_id: str,
        titles: dict[str, str] | None = None,
    ) -> SessionInfo | None:
        objs = list(_iter_lines(jsonl_path))
        if not objs or not _is_header(objs[0]):
            return None
        header = objs[0]
        payload = header.get("payload") if isinstance(header.get("payload"), dict) else header
        first_ts = payload.get("timestamp") or header.get("timestamp")
        last_ts = None
        for obj in reversed(objs):
            if isinstance(obj.get("timestamp"), str):
                last_ts = obj["timestamp"]
                break
        visible = [m for m in _normalize(objs) if _visible(m)]
        first_user = next(
            (m["message"]["content"] for m in visible
             if m["type"] == "user" and isinstance(m["message"]["content"], str)),
            "",
        )
        if not first_ts:
            return None
        if not last_ts:
            try:
                from datetime import datetime, timezone
                last_ts = datetime.fromtimestamp(jsonl_path.stat().st_mtime, timezone.utc).isoformat()
            except OSError:
                last_ts = first_ts
        title = (titles or {}).get(session_id) or (first_user[:100] if first_user else "(empty session)")
        return SessionInfo(
            session_id=session_id,
            started_at=_parse_timestamp(first_ts),
            last_activity=_parse_timestamp(last_ts),
            title=title,
            message_count=len(visible),
        )


_adapter = CodexAdapter()
register_provider(_adapter)


# ---------------------------------------------------------------------------
# Storage: resolver + discoverer
# ---------------------------------------------------------------------------

# path → originator (line 0 never changes, so no mtime check needed).
_originator_cache: dict[str, str | None] = {}


def _originator(path: Path) -> str | None:
    key = str(path)
    if key in _originator_cache:
        return _originator_cache[key]
    value: str | None = None
    for obj in _iter_lines(path):
        payload = obj.get("payload") if isinstance(obj.get("payload"), dict) else {}
        value = payload.get("originator") if isinstance(payload.get("originator"), str) else None
        break
    _originator_cache[key] = value
    return value


def _codex_jsonl_candidates(session_id: str) -> list[Path]:
    """Rollout path(s) for *session_id* across every sessions root."""
    if not session_id or "/" in session_id:
        return []
    out: list[Path] = []
    for root in home.sessions_roots(_project_dir()):
        try:
            if root.is_dir():
                out.extend(p for p in root.glob(f"*/*/*/rollout-*-{session_id}.jsonl") if p.is_file())
        except OSError:
            continue
    return out


def _project_dir() -> str:
    try:
        from manager.config import ManagerConfig
        return ManagerConfig.load().project_dir
    except Exception:
        return str(PROJECT_ROOT)


def _codex_discover_sessions(project_dir: str):
    """Yield ``(thread_id, path)`` for Archie's Codex rollouts.

    Every rollout under ``context/codex/sessions`` and the dedicated home
    is Archie's.  In the shared ``~/.codex`` (VS Code, the TUI and other
    tools write there too) only rollouts whose ``originator`` is Archie's
    client name are listed.
    """
    seen: set[str] = set()
    try:
        shared = (home.shared_home() / "sessions").resolve()
        dedicated = (home.dedicated_home() / "sessions").resolve()
    except OSError:
        shared = dedicated = None
    filter_shared = shared is not None and shared != dedicated
    for root in home.sessions_roots(project_dir):
        if not root.is_dir():
            continue
        try:
            only_archie = filter_shared and root.resolve() == shared
        except OSError:
            only_archie = False
        try:
            paths = list(root.glob("*/*/*/rollout-*.jsonl"))
        except OSError:
            continue
        for path in paths:
            sid = session_id_from_path(path)
            if not sid or sid in seen or not path.is_file():
                continue
            if only_archie and _originator(path) != home.CLIENT_NAME:
                continue
            seen.add(sid)
            yield sid, path


# ---------------------------------------------------------------------------
# HarnessSpec registration
# ---------------------------------------------------------------------------


def _load_codex_session_class():
    from .session import CodexSessionManager
    return CodexSessionManager


def _load_codex_kill_helper():
    from .session import kill_codex_subprocess
    return kill_codex_subprocess


def _load_codex_catalog():
    from .catalog import load_codex_catalog
    return load_codex_catalog()


register_harness(HarnessSpec(
    name="codex",
    label="Codex",
    description="OpenAI's Codex CLI over `codex app-server` — ChatGPT login (or CODEX_API_KEY), GPT models.",
    session_class_loader=_load_codex_session_class,
    adapter_loader=lambda: _adapter,
    # Native Rust binary (we exec it directly, not the npm Node shim).
    comm_prefix="codex",
    kill_helper_loader=_load_codex_kill_helper,
    ssh_control_path_prefix="codex",
    jsonl_path_resolver=_codex_jsonl_candidates,
    session_discoverer=_codex_discover_sessions,
    requirements_file=None,  # external CLI; no Python deps
    npm_package="@openai/codex",
    cli_binary="codex",
    env_keys=(),  # ChatGPT OAuth via `codex login`; CODEX_API_KEY optional
    catalog_loader=_load_codex_catalog,
))
