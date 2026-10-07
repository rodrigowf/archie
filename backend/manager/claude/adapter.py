"""Claude Code JSONL adapter — Claude's native format is already the
normalized shape, so this adapter is mostly identity on the message content.

Claude JSONL characteristics:
- ``type`` field: "user", "assistant", "system", and a handful of internal
  event types ("queue-operation", "ai-title", "attachment", "last-prompt",
  "file-history-snapshot")
- User messages: ``message.content`` is a plain string
- Assistant messages: ``message.content`` is a list of content blocks
- Tool calls/results are embedded as content blocks within messages
"""

from __future__ import annotations

import json
from pathlib import Path

from ..protocol import ProviderAdapter, _parse_timestamp, extract_text, register_provider
from ..registry import HarnessSpec, register_harness
from ..types import SessionInfo


# Backward-compat alias: tests and SessionStore import this name.
_extract_text = extract_text


_CLAUDE_INTERNAL_TYPES = frozenset({
    "queue-operation", "ai-title", "attachment",
    "file-history-snapshot", "last-prompt",
})


class ClaudeAdapter(ProviderAdapter):
    """Adapter for Claude Code's native JSONL format."""

    @property
    def provider_name(self) -> str:
        return "claude"

    def detect_provider(self, jsonl_path: Path) -> bool:
        """Detect Claude format by looking for Claude-specific event types
        or the ``message.content`` shape (vs Qwen's ``message.parts``)."""
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

                    if obj.get("type") in _CLAUDE_INTERNAL_TYPES:
                        return True

                    msg = obj.get("message")
                    if isinstance(msg, dict) and "content" in msg and "parts" not in msg:
                        return True
        except (OSError, PermissionError):
            pass
        return False

    def read_messages(self, jsonl_path: Path) -> list[dict]:
        """Read user/assistant/system messages from a Claude JSONL file.

        Claude's native format already matches the normalized shape, so
        we just filter to the relevant event types.  Orchestrator files are
        folded into one assistant message per turn, top-level ``tool_use`` /
        ``tool_result`` lines included (O-1, see
        :func:`fold_orchestrator_lines`).
        """
        objs = _read_objs(jsonl_path)
        if _is_orchestrator_file(objs):
            return [msg for msg, _anchor in fold_orchestrator_lines(objs)]
        return [
            o for o in objs
            if o is not None and o.get("type") in ("user", "assistant", "system")
        ]

    def visible_line_indices(self, objs: list[dict | None]) -> list[int]:
        """For orchestrator files, one index per visible folded message
        (its anchor line), so ``drop_last_n`` computed from REST data lands
        on the same place in the file.  Other files: the per-line rule."""
        if not _is_orchestrator_file(objs):
            return super().visible_line_indices(objs)
        from ..protocol import is_visible_message_default
        return [
            anchor for msg, anchor in fold_orchestrator_lines(objs)
            if is_visible_message_default(msg)
        ]

    def parse_session_info(
        self,
        jsonl_path: Path,
        session_id: str,
        titles: dict[str, str] | None = None,
    ) -> SessionInfo | None:
        """Extract summary metadata from a Claude JSONL file."""
        first_user_text: str = ""
        first_timestamp: str | None = None
        last_timestamp: str | None = None
        message_count = 0
        is_orchestrator = False

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

                    msg_type = obj.get("type")
                    ts = obj.get("timestamp")

                    if msg_type == "orchestrator_meta" and obj.get("orchestrator"):
                        is_orchestrator = True

                    if ts:
                        if first_timestamp is None:
                            first_timestamp = ts
                        last_timestamp = ts

                    if msg_type in ("user", "assistant"):
                        message_count += 1
                        if msg_type == "user" and not first_user_text:
                            first_user_text = extract_text(obj)
        except (OSError, PermissionError):
            return None

        if first_timestamp is None:
            return None

        title = (titles or {}).get(session_id) or (
            first_user_text[:100] if first_user_text else "(empty session)"
        )
        return SessionInfo(
            session_id=session_id,
            started_at=_parse_timestamp(first_timestamp),
            last_activity=_parse_timestamp(last_timestamp or first_timestamp),
            title=title,
            message_count=message_count,
            is_orchestrator=is_orchestrator,
        )


def _read_objs(jsonl_path: Path) -> list[dict | None]:
    """Parse every line; ``None`` for blank / unparseable / non-dict lines
    so indices stay aligned with the raw file."""
    objs: list[dict | None] = []
    try:
        with open(jsonl_path) as f:
            for line in f:
                line = line.strip()
                obj = None
                if line:
                    try:
                        obj = json.loads(line)
                    except json.JSONDecodeError:
                        obj = None
                objs.append(obj if isinstance(obj, dict) else None)
    except (OSError, PermissionError):
        pass
    return objs


def _is_orchestrator_tool_line(obj: dict | None) -> bool:
    """Top-level tool lines written by the orchestrator (text + voice)."""
    return (
        isinstance(obj, dict)
        and obj.get("type") in ("tool_use", "tool_result")
        and "tool_call_id" in obj
    )


def _orchestrator_tool_block(obj: dict) -> dict:
    if obj.get("type") == "tool_use":
        tool_input = obj.get("tool_input")
        return {
            "type": "tool_use",
            "id": obj.get("tool_call_id", ""),
            "name": obj.get("tool_name", ""),
            "input": tool_input if isinstance(tool_input, dict) else {},
        }
    output = obj.get("output", "")
    if output is not None and not isinstance(output, (str, list)):
        output = json.dumps(output)
    return {
        "type": "tool_result",
        "tool_use_id": obj.get("tool_call_id", ""),
        "content": output,
        "is_error": bool(obj.get("is_error", False)),
    }


def _content_blocks(content) -> list:
    if isinstance(content, list):
        return list(content)
    if isinstance(content, str) and content:
        return [{"type": "text", "text": content}]
    return []


def _is_orchestrator_file(objs: list[dict | None]) -> bool:
    return any(
        isinstance(o, dict) and (
            (o.get("type") == "orchestrator_meta" and o.get("orchestrator"))
            or _is_orchestrator_tool_line(o)
        )
        for o in objs
    )


def fold_orchestrator_lines(
    objs: list[dict | None],
) -> list[tuple[dict, int]]:
    """Fold an orchestrator JSONL into one assistant message per turn.

    Returns ``(message, anchor_line_index)`` for every user/assistant/system
    message, in file order.  Every ``assistant`` line and top-level
    ``tool_use`` / ``tool_result`` line between two visible ``user`` lines
    becomes ONE assistant message whose blocks keep file order (text,
    tool_use, tool_result, text, ...).  That matches the live clients,
    which render one assistant bubble per turn, so ``drop_last_n`` counted
    from REST data lands where the user clicked.

    The message keeps the top-level fields (timestamp, source) of the
    group's first line; a group that starts with a tool line gets a plain
    assistant shell.  Its anchor is the group's LAST line, so a truncate
    that keeps the turn keeps all of it (no text or tool result cut off).
    Old files (joined text after the tools) fold the same way.
    """
    from ..protocol import is_visible_message_default

    out: list[list] = []  # [message, anchor]
    group: int | None = None  # index into ``out`` of the turn's assistant message

    for idx, obj in enumerate(objs):
        if obj is None:
            continue
        msg_type = obj.get("type")
        is_tool = _is_orchestrator_tool_line(obj)
        if is_tool or msg_type == "assistant":
            if is_tool:
                blocks = [_orchestrator_tool_block(obj)]
            else:
                blocks = _content_blocks(obj.get("message", {}).get("content"))
            if group is None:
                if is_tool:
                    base: dict = {"type": "assistant"}
                    if obj.get("timestamp"):
                        base["timestamp"] = obj["timestamp"]
                    inner: dict = {"role": "assistant"}
                else:
                    base = obj
                    inner = obj.get("message", {})
                out.append([{**base, "message": {**inner, "content": blocks}}, idx])
                group = len(out) - 1
            else:
                entry = out[group]
                msg = entry[0]
                msg["message"]["content"] = msg["message"]["content"] + blocks
                entry[1] = idx
        elif msg_type == "user":
            if is_visible_message_default(obj):
                group = None
            out.append([obj, idx])
        elif msg_type == "system":
            out.append([obj, idx])
    return [(m, a) for m, a in out]

_adapter = ClaudeAdapter()
register_provider(_adapter)


def _load_claude_session_class():
    from .session import ClaudeSessionManager
    return ClaudeSessionManager


def _load_claude_kill_helper():
    from .session import kill_claude_subprocess
    return kill_claude_subprocess


def _load_claude_catalog():
    # Lazy: catalog.py pulls in httpx only when the settings UI asks.
    from .catalog import load_claude_catalog
    return load_claude_catalog()


def _claude_jsonl_candidates(session_id: str):
    # Claude writes to the top-level context dir (context/<id>.jsonl).
    # Kept as a single-element list so the resolver contract stays uniform
    # with harnesses (like Qwen) that have multiple historical layouts.
    from utils.paths import get_context_dir
    return [get_context_dir() / f"{session_id}.jsonl"]


register_harness(HarnessSpec(
    name="claude",
    label="Claude Code",
    description="Anthropic's official Claude Code CLI (the canonical harness).",
    session_class_loader=_load_claude_session_class,
    adapter_loader=lambda: _adapter,
    comm_prefix="claude",
    kill_helper_loader=_load_claude_kill_helper,
    ssh_control_path_prefix="claude",
    jsonl_path_resolver=_claude_jsonl_candidates,
    requirements_file="requirements-claude.txt",
    npm_package="@anthropic-ai/claude-code",
    cli_binary="claude",
    env_keys=(),
    catalog_loader=_load_claude_catalog,
))
