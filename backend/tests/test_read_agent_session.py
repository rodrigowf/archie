"""read_agent_session pages a transcript instead of cutting it.

The tool sizes its own output (voice: under the realtime providers' tool-output
cap; text: a default the model can raise) and returns cursors — ``earlier``
to page back, ``continue`` to read the rest of a long message — so the
orchestrator can always reach the full text, newest first.
"""

from __future__ import annotations

import asyncio
import json
from datetime import datetime, timezone
from types import SimpleNamespace
from typing import Any

from orchestrator.providers.voice_base import (
    VOICE_TOOL_OUTPUT_MAX_CHARS,
    truncate_voice_tool_output,
)
from orchestrator.tools.agent_sessions import read_agent_session


def _msg(role: str, text: str) -> SimpleNamespace:
    return SimpleNamespace(role=role, text=text, timestamp=datetime(2026, 10, 13, tzinfo=timezone.utc))


class _Store:
    def __init__(self, previews: list[SimpleNamespace]):
        self.previews = previews

    def get_preview(self, session_id: str, max_messages: int | None = 5):
        return self.previews if max_messages is None else self.previews[-max_messages:]


class _Pool:
    def get(self, session_id: str):
        return None

    def restored_sdk_id(self, session_id: str):
        return None


class _Runner:
    def __init__(self, snapshot: dict[str, Any]):
        self.snapshot = snapshot

    def peek(self, session_id: str):
        return self.snapshot


def _ctx(previews, *, voice: bool = False, runner=None) -> dict[str, Any]:
    ctx: dict[str, Any] = {
        "pool": _Pool(),
        "store": _Store(previews),
        "session": SimpleNamespace(is_voice=voice),
    }
    if runner is not None:
        ctx["runner"] = runner
    return ctx


def _read(ctx, **kwargs) -> tuple[str, dict]:
    out = asyncio.run(read_agent_session(ctx, "sess", **kwargs))
    return out, json.loads(out)


def _transcript() -> list[SimpleNamespace]:
    """Text messages at even indexes, tool-only (empty text) at odd ones."""
    out = []
    for i in range(20):
        out.append(_msg("assistant" if i % 4 else "user", f"message {i}" if i % 2 == 0 else ""))
    return out


def _follow(ctx, body: dict) -> str:
    """Reassemble the cut message by following ``continue`` cursors."""
    text = body["messages"][-1]["text"]
    while "continue" in body:
        out, body = _read(ctx, **body["continue"])
        if ctx["session"].is_voice:
            assert len(out) <= VOICE_TOOL_OUTPUT_MAX_CHARS
        text += body["messages"][0]["text"]
    return text


def test_skips_tool_only_entries_and_indexes_messages():
    _, body = _read(_ctx(_transcript()))
    assert [m["index"] for m in body["messages"]] == [10, 12, 14, 16, 18]
    assert body["total_messages"] == 20
    assert body["earlier"] == {"before_index": 10, "text_messages": 5}
    assert "before_index=10" in body["more"]


def test_pages_back_with_before_index():
    _, body = _read(_ctx(_transcript()), before_index=10)
    assert [m["index"] for m in body["messages"]] == [0, 2, 4, 6, 8]
    assert "earlier" not in body and "more" not in body


def test_text_mode_returns_a_long_report_whole():
    report = "x" * 30_000
    _, body = _read(_ctx([_msg("user", "go"), _msg("assistant", report)]))
    assert body["messages"][-1]["text"] == report
    assert "continue" not in body


def test_voice_mode_returns_newest_head_and_cursor_and_fits_the_cap():
    report = "".join(f"line {i}: \"quoted\" ção\n" for i in range(3000))
    ctx = _ctx([_msg("user", "go"), _msg("assistant", "short"), _msg("assistant", report)], voice=True)
    out, body = _read(ctx)
    assert len(out) <= VOICE_TOOL_OUTPUT_MAX_CHARS
    # The generic voice clipper must leave it untouched.
    assert truncate_voice_tool_output(out) == out
    newest = body["messages"][-1]
    assert newest["index"] == 2 and newest["truncated"] and newest["chars_total"] == len(report)
    assert report.startswith(newest["text"])
    assert body["earlier"] == {"before_index": 2, "text_messages": 2}
    assert _follow(ctx, body) == report


def test_max_chars_is_honoured_in_text_mode_and_clamped_in_voice():
    report = "y" * 50_000
    ctx = _ctx([_msg("assistant", report)])
    out, body = _read(ctx, max_chars=5_000)
    assert len(out) <= 5_000 and body["continue"]["message_index"] == 0
    assert _follow(ctx, body) == report

    out, _ = _read(_ctx([_msg("assistant", report)], voice=True), max_chars=100_000)
    assert len(out) <= VOICE_TOOL_OUTPUT_MAX_CHARS


def test_chunk_read_from_offset():
    ctx = _ctx([_msg("assistant", "abcdefghij")])
    _, body = _read(ctx, message_index=0, char_offset=4)
    assert body["messages"][0]["text"] == "efghij"
    assert body["messages"][0]["char_offset"] == 4
    assert "continue" not in body


def test_chunk_read_out_of_range():
    _, body = _read(_ctx([_msg("assistant", "hi")]), message_index=5)
    assert "out of range" in body["error"]


def test_live_block_keeps_newest_events_within_budget():
    events = [{"seq": i, "kind": "tool_result", "payload": {"output_excerpt": "z" * 800}} for i in range(50)]
    runner = _Runner({
        "status": "running", "finished": False, "turn_id": "t1", "phase": "agent",
        "last_assistant_text": "working", "events": events,
    })
    ctx = _ctx([_msg("assistant", "hello")], voice=True, runner=runner)
    out, body = _read(ctx)
    assert len(out) <= VOICE_TOOL_OUTPUT_MAX_CHARS
    live = body["live"]
    assert live["status"] == "running" and live["events_omitted"] > 0
    assert live["events"][-1]["seq"] == 49
    assert body["messages"][0]["text"] == "hello"


def test_empty_idle_session_is_an_error():
    _, body = _read(_ctx([]))
    assert "No messages found" in body["error"]
