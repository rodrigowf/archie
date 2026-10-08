"""Tests for manager/gemini/adapter.py — Gemini CLI JSONL parsing.

Gemini's native format differs from both Claude and Qwen:

1. A header line ``{"sessionId":..., "projectHash":..., "kind":"main"}``
   opens every session JSONL.
2. Assistant role is ``"gemini"`` (not ``"assistant"``).
3. Assistant content is a plain string (not a list of blocks).
4. ``thoughts`` is a top-level array, not inline thinking blocks.
5. ``{"$set": {...}}`` lines are bookkeeping markers and must be skipped.

The adapter's job is to normalize all of these into the same shape the
rest of the wrapper (SessionStore, MessagePreview, UI) understands.
"""

from __future__ import annotations

import json
import uuid
from pathlib import Path

import pytest

from manager.gemini.adapter import (
    GeminiAdapter,
    _gemini_jsonl_candidates,
    _is_metadata_line,
    _normalize_message,
)
from manager.types import ContentBlock


@pytest.fixture
def adapter() -> GeminiAdapter:
    return GeminiAdapter()


# ---------------------------------------------------------------------------
# Fixture helpers — mirror Gemini's real-world JSONL shape
# ---------------------------------------------------------------------------


def _header_line(
    session_id: str = "11111111-1111-1111-1111-111111111111",
    started: str = "2026-05-15T20:55:00.745Z",
) -> dict:
    return {
        "sessionId": session_id,
        "projectHash": "abc123",
        "startTime": started,
        "lastUpdated": started,
        "kind": "main",
    }


def _uid() -> str:
    # Real files never reuse an id for two different messages (a reused id
    # is an upsert), so every fixture line gets its own.
    return str(uuid.uuid4())


def _user_line(
    text: str, ts: str = "2026-05-15T20:57:09.556Z", id: str | None = None,
) -> dict:
    return {
        "id": id or _uid(),
        "timestamp": ts,
        "type": "user",
        "content": [{"text": text}],
    }


def _gemini_line(
    text: str,
    ts: str = "2026-05-15T20:57:11.910Z",
    thoughts: list[dict] | None = None,
    tool_calls: list[dict] | None = None,
    id: str | None = None,
) -> dict:
    out = {
        "id": id or _uid(),
        "timestamp": ts,
        "type": "gemini",
        "content": text,
        "thoughts": thoughts or [],
        "tokens": {"input": 10, "output": 5, "cached": 0, "thoughts": 0, "tool": 0, "total": 15},
        "model": "gemini-3-flash-preview",
    }
    if tool_calls is not None:
        out["toolCalls"] = tool_calls
    return out


def _tool_call(
    tool_id: str = "read_file_1",
    name: str = "read_file",
    args: dict | None = None,
    output: str | None = "file contents",
    status: str = "success",
    result_display: str | None = None,
    error_message: str | None = None,
) -> dict:
    """Build one entry shaped like the real ``toolCalls[i]`` Gemini writes."""
    args = args if args is not None else {"file_path": "foo.txt"}
    if status == "error":
        response = {"error": error_message or "boom"}
    else:
        response = {"output": output if output is not None else ""}
    return {
        "id": tool_id,
        "name": name,
        "args": args,
        "result": [{
            "functionResponse": {"id": tool_id, "name": name, "response": response},
        }],
        "status": status,
        "resultDisplay": result_display if result_display is not None else (output or ""),
        "timestamp": "2026-05-16T17:18:54.002Z",
        "description": "test tool",
        "displayName": name,
        "renderOutputAsMarkdown": True,
    }


def _set_line(ts: str = "2026-05-15T20:57:11.910Z") -> dict:
    return {"$set": {"lastUpdated": ts}}


def _write_jsonl(path: Path, lines: list[dict]) -> None:
    path.write_text("\n".join(json.dumps(line) for line in lines) + "\n")


# ---------------------------------------------------------------------------
# detect_provider


def test_detect_provider_returns_true_for_header_line(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    p = tmp_path / "session.jsonl"
    _write_jsonl(p, [_header_line(), _user_line("hi")])
    assert adapter.detect_provider(p) is True


def test_detect_provider_returns_true_for_gemini_type(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    """Even without a header line, ``type: 'gemini'`` is a unique signature."""
    p = tmp_path / "session.jsonl"
    _write_jsonl(p, [_gemini_line("hi")])
    assert adapter.detect_provider(p) is True


def test_detect_provider_returns_false_for_claude_jsonl(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    """Claude's native format has neither the header line nor 'gemini' type."""
    p = tmp_path / "session.jsonl"
    _write_jsonl(p, [
        {
            "type": "user",
            "timestamp": "2026-05-15T20:57:09Z",
            "message": {"role": "user", "content": "hi"},
        },
    ])
    assert adapter.detect_provider(p) is False


def test_detect_provider_returns_false_for_qwen_jsonl(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    """Qwen's parts-shape doesn't trigger Gemini detection."""
    p = tmp_path / "session.jsonl"
    _write_jsonl(p, [
        {
            "type": "user",
            "timestamp": "2026-05-15T20:57:09Z",
            "message": {"role": "user", "parts": [{"text": "hi"}]},
        },
    ])
    assert adapter.detect_provider(p) is False


def test_detect_provider_tolerates_unreadable_file(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    p = tmp_path / "missing.jsonl"
    assert adapter.detect_provider(p) is False


# ---------------------------------------------------------------------------
# _is_metadata_line / _normalize_message — internal helpers


def test_is_metadata_line_recognizes_set_lines() -> None:
    assert _is_metadata_line({"$set": {"lastUpdated": "2026-05-15T20:00:00Z"}}) is True
    # A line with both $set AND a type field is a real message, not metadata.
    assert _is_metadata_line({"$set": {"x": 1}, "type": "user", "content": []}) is False
    assert _is_metadata_line({"type": "user", "content": []}) is False


def test_normalize_message_user_joins_content_parts() -> None:
    raw = {
        "type": "user",
        "timestamp": "t1",
        "content": [{"text": "hello"}, {"text": "world"}],
    }
    msgs = _normalize_message(raw)
    assert len(msgs) == 1
    assert msgs[0]["type"] == "user"
    assert msgs[0]["message"]["content"] == "hello\nworld"


def test_normalize_message_user_accepts_plain_string_content() -> None:
    """Future-proofing: if the CLI ever emits user.content as a string,
    the adapter should still parse it."""
    raw = {"type": "user", "timestamp": "t1", "content": "hello"}
    msgs = _normalize_message(raw)
    assert len(msgs) == 1
    assert msgs[0]["message"]["content"] == "hello"


def test_normalize_message_assistant_emits_text_block() -> None:
    raw = {
        "type": "gemini",
        "timestamp": "t1",
        "content": "the answer is 42",
        "thoughts": [],
    }
    msgs = _normalize_message(raw)
    assert len(msgs) == 1
    assert msgs[0]["type"] == "assistant"
    assert msgs[0]["message"]["role"] == "assistant"
    assert msgs[0]["message"]["content"] == [
        {"type": "text", "text": "the answer is 42"},
    ]


def test_normalize_message_assistant_includes_thoughts_as_thinking_blocks() -> None:
    raw = {
        "type": "gemini",
        "timestamp": "t1",
        "content": "answer",
        "thoughts": [
            {"subject": "Step 1", "description": "Think hard", "timestamp": "t0"},
        ],
    }
    msgs = _normalize_message(raw)
    assert len(msgs) == 1
    blocks = msgs[0]["message"]["content"]
    # Thinking blocks come first (the order users expect — think then say).
    assert blocks[0] == {"type": "thinking", "text": "Step 1\nThink hard"}
    assert blocks[1] == {"type": "text", "text": "answer"}


def test_normalize_message_returns_empty_for_unrelated_types() -> None:
    """Header line, $set markers, and unknown event types all map to []."""
    assert _normalize_message(_header_line()) == []
    assert _normalize_message(_set_line()) == []
    assert _normalize_message({"type": "unknown"}) == []


def test_normalize_message_emits_tool_use_block_for_each_tool_call() -> None:
    """A gemini line with ``toolCalls`` should add a ``tool_use`` block per
    entry to the assistant message, *and* synthesize a user message with
    matching ``tool_result`` blocks (so the frontend can pair them by id)."""
    raw = _gemini_line(
        "result text",
        tool_calls=[
            _tool_call(tool_id="t1", name="read_file", args={"path": "a.txt"}),
            _tool_call(tool_id="t2", name="write_file", args={"path": "b.txt"}),
        ],
    )
    msgs = _normalize_message(raw)
    assert len(msgs) == 2

    assistant = msgs[0]
    assert assistant["type"] == "assistant"
    blocks = assistant["message"]["content"]
    tool_uses = [b for b in blocks if b["type"] == "tool_use"]
    assert len(tool_uses) == 2
    assert tool_uses[0] == {
        "type": "tool_use",
        "id": "t1",
        "name": "read_file",
        "input": {"path": "a.txt"},
    }
    assert tool_uses[1]["name"] == "write_file"

    # Tool-result user message paired by id.
    tool_user = msgs[1]
    assert tool_user["type"] == "user"
    results = tool_user["message"]["content"]
    assert len(results) == 2
    assert results[0]["type"] == "tool_result"
    assert results[0]["tool_use_id"] == "t1"
    assert results[1]["tool_use_id"] == "t2"


def test_normalize_message_marks_tool_result_error_status() -> None:
    """Tool calls with ``status: "error"`` produce ``is_error=True`` results
    whose output is the error message, not the success output."""
    raw = _gemini_line(
        "",
        tool_calls=[
            _tool_call(
                tool_id="t1",
                status="error",
                error_message="path is not allowed",
                result_display="path is not allowed",
            ),
        ],
    )
    msgs = _normalize_message(raw)
    tool_user = msgs[1]
    res = tool_user["message"]["content"][0]
    assert res["type"] == "tool_result"
    assert res["tool_use_id"] == "t1"
    assert res["is_error"] is True
    assert "path is not allowed" in res["content"]


def test_normalize_message_skips_tool_result_for_in_flight_call() -> None:
    """A ``toolCalls`` entry without ``status`` or ``result`` is still in
    flight — the live event stream will publish the result.  We should
    emit the tool_use but no tool_result on disk-replay."""
    raw_call = {
        "id": "t1",
        "name": "shell",
        "args": {"command": "ls"},
        # no status, no result
    }
    raw = _gemini_line("", tool_calls=[raw_call])
    msgs = _normalize_message(raw)
    assistant = msgs[0]
    tool_uses = [b for b in assistant["message"]["content"] if b["type"] == "tool_use"]
    assert len(tool_uses) == 1
    # Only the assistant message, no synthetic user message.
    assert len(msgs) == 1


def test_normalize_message_tool_use_blocks_round_trip_to_content_blocks(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    """End-to-end: read a JSONL with toolCalls, run it through
    ``extract_blocks``, and verify the frontend sees ``tool_use`` +
    ``tool_result`` ContentBlocks paired by id."""
    from manager.protocol import extract_blocks

    p = tmp_path / "session.jsonl"
    _write_jsonl(p, [
        _header_line(),
        _user_line("read the file"),
        _gemini_line(
            "Here you go.",
            tool_calls=[
                _tool_call(tool_id="abc", name="read_file", output="contents"),
            ],
        ),
    ])
    msgs = adapter.read_messages(p)
    # user, assistant, tool-result-user
    assert len(msgs) == 3

    assistant_blocks = extract_blocks(msgs[1])
    tool_use_blocks = [b for b in assistant_blocks if b.type == "tool_use"]
    assert len(tool_use_blocks) == 1
    assert tool_use_blocks[0].tool_use_id == "abc"
    assert tool_use_blocks[0].tool_name == "read_file"

    tool_result_blocks = extract_blocks(msgs[2])
    result_blocks = [b for b in tool_result_blocks if b.type == "tool_result"]
    assert len(result_blocks) == 1
    assert result_blocks[0].tool_use_id == "abc"
    assert result_blocks[0].is_error is False
    assert "contents" in (result_blocks[0].output or "")


# ---------------------------------------------------------------------------
# read_messages


def test_read_messages_skips_header_and_set_lines(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    p = tmp_path / "session.jsonl"
    _write_jsonl(p, [
        _header_line(),
        _user_line("hi"),
        _set_line(),
        _gemini_line("hello back"),
        _set_line(),
    ])
    msgs = adapter.read_messages(p)
    assert len(msgs) == 2
    assert msgs[0]["type"] == "user"
    assert msgs[0]["message"]["content"] == "hi"
    assert msgs[1]["type"] == "assistant"
    assert msgs[1]["message"]["content"][0]["text"] == "hello back"


def test_read_messages_handles_empty_file(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    p = tmp_path / "empty.jsonl"
    p.write_text("")
    assert adapter.read_messages(p) == []


def test_read_messages_skips_malformed_lines(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    """A garbage line shouldn't blow up the whole file read."""
    p = tmp_path / "session.jsonl"
    content = "\n".join([
        json.dumps(_header_line()),
        "{this is not valid JSON",
        json.dumps(_user_line("hi")),
    ])
    p.write_text(content + "\n")
    msgs = adapter.read_messages(p)
    assert len(msgs) == 1
    assert msgs[0]["message"]["content"] == "hi"


# ---------------------------------------------------------------------------
# parse_session_info


def test_parse_session_info_extracts_title_and_counts(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    p = tmp_path / "session.jsonl"
    _write_jsonl(p, [
        _header_line(started="2026-05-15T20:55:00.000Z"),
        _user_line("First question?"),
        _set_line("2026-05-15T20:57:09.556Z"),
        _gemini_line("First answer.", ts="2026-05-15T20:57:11.910Z"),
        _set_line("2026-05-15T20:57:11.910Z"),
        _user_line("Second?", ts="2026-05-15T21:00:00.000Z"),
        _gemini_line("Second answer.", ts="2026-05-15T21:00:05.000Z"),
    ])
    info = adapter.parse_session_info(p, "session-test")
    assert info is not None
    assert info.title == "First question?"
    assert info.message_count == 4
    assert info.started_at is not None
    assert info.last_activity is not None


def test_parse_session_info_uses_provided_title_override(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    p = tmp_path / "session.jsonl"
    _write_jsonl(p, [_header_line(), _user_line("auto-title")])
    info = adapter.parse_session_info(
        p, "sid", titles={"sid": "Custom Title"},
    )
    assert info is not None
    assert info.title == "Custom Title"


def test_parse_session_info_returns_none_for_empty_file(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    p = tmp_path / "empty.jsonl"
    p.write_text("")
    assert adapter.parse_session_info(p, "sid") is None


# ---------------------------------------------------------------------------
# Blocks integration — round-trip through extract_blocks


def test_assistant_blocks_round_trip_through_extract_blocks(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    """The normalized output should work with the existing
    ``extract_blocks`` helper, since SessionStore relies on it."""
    from manager.protocol import extract_blocks

    p = tmp_path / "session.jsonl"
    _write_jsonl(p, [
        _header_line(),
        _gemini_line(
            "hello",
            thoughts=[{"subject": "Greeting", "description": "say hi", "timestamp": "t0"}],
        ),
    ])
    msgs = adapter.read_messages(p)
    blocks = extract_blocks(msgs[0])
    # Both the thinking and the text content should land as ContentBlocks
    # (extract_blocks collapses thinking blocks into text blocks for display).
    assert any(b.type == "text" and "hello" in (b.text or "") for b in blocks)


# ---------------------------------------------------------------------------
# JSONL path resolver (registered on the HarnessSpec)


def test_jsonl_path_resolver_returns_empty_when_tmp_missing(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    """If ~/.gemini/tmp doesn't exist (fresh install), resolver returns []."""
    monkeypatch.setenv("GEMINI_HOME", str(tmp_path / "no-such-dir"))
    assert _gemini_jsonl_candidates("11111111-1111-1111-1111-111111111111") == []


def test_jsonl_path_resolver_finds_session_by_uuid_prefix(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The session filename embeds only the first 8 chars of the UUID, so
    the resolver must glob for that prefix across every project dir."""
    monkeypatch.setenv("GEMINI_HOME", str(tmp_path))
    chats = tmp_path / "tmp" / "assistant" / "chats"
    chats.mkdir(parents=True)
    sid = "11111111-1111-1111-1111-111111111111"
    target = chats / f"session-2026-05-15T20-55-{sid[:8]}.jsonl"
    target.write_text("{}\n")

    found = _gemini_jsonl_candidates(sid)
    assert target in found


def test_jsonl_path_resolver_ignores_unrelated_files(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("GEMINI_HOME", str(tmp_path))
    chats = tmp_path / "tmp" / "assistant" / "chats"
    chats.mkdir(parents=True)
    # File belongs to a different session — first 8 chars differ.
    (chats / "session-2026-05-15T20-55-deadbeef.jsonl").write_text("{}\n")
    found = _gemini_jsonl_candidates("11111111-1111-1111-1111-111111111111")
    assert found == []


def test_jsonl_path_resolver_returns_empty_for_short_id(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Edge: an empty session id shouldn't match every file in the dir."""
    monkeypatch.setenv("GEMINI_HOME", str(tmp_path))
    chats = tmp_path / "tmp" / "assistant" / "chats"
    chats.mkdir(parents=True)
    (chats / "session-anything.jsonl").write_text("{}\n")
    assert _gemini_jsonl_candidates("") == []


# ---------------------------------------------------------------------------
# The log semantics of CLI 0.42 / 0.63 (upserts, snapshots, rewinds)
# ---------------------------------------------------------------------------


def _shell_call(tool_id: str = "run_shell_command_1", output: str = "a.txt\nb.txt") -> dict:
    return _tool_call(tool_id=tool_id, name="run_shell_command",
                      args={"command": "ls"}, output=output)


def _fr_user(tool_id: str, output: str, mid: str = "fr1") -> dict:
    """0.63: the tool answer recorded as a user message of functionResponse parts."""
    return {
        "id": mid, "timestamp": "2026-10-07T23:40:03Z", "type": "user",
        "content": [{"functionResponse": {
            "id": tool_id, "name": "run_shell_command", "response": {"output": output},
        }}],
    }


def _ctx_user() -> dict:
    return {
        "id": "ctx", "timestamp": "2026-10-07T23:40:00Z", "type": "user",
        "content": [{"text": "<session_context>\nThis is the Gemini CLI. OS: linux\n"
                             "--- Context from: GEMINI.md ---\n# Instructions"}],
    }


def test_042_duplicate_ids_are_upserted_last_write_wins(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    """0.42 re-appends a gemini message when it gains toolCalls (real file
    shape: thoughts first, then the same id with toolCalls)."""
    thought = [{"subject": "Plan", "description": "list files", "timestamp": "t"}]
    p = tmp_path / "s.jsonl"
    _write_jsonl(p, [
        _header_line(),
        _user_line("list files", id="u1"),
        _set_line(),
        _gemini_line("", thoughts=thought, id="g1"),
        _set_line(),
        _gemini_line("", thoughts=thought, tool_calls=[_shell_call()], id="g1"),
        _gemini_line("Two files.", id="g2"),
        _set_line(),
    ])
    msgs = adapter.read_messages(p)
    shape = [
        (m["type"], m["message"]["content"] if isinstance(m["message"]["content"], str)
         else [b["type"] for b in m["message"]["content"]])
        for m in msgs
    ]
    assert shape == [
        ("user", "list files"),
        ("assistant", ["thinking", "tool_use"]),
        ("user", ["tool_result"]),
        ("assistant", ["text"]),
    ]
    info = adapter.parse_session_info(p, "sid")
    assert info.message_count == 3


def test_063_snapshot_replaces_history_and_hides_session_context(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    """On resume 0.63 writes ``$set.messages``: a <session_context> user turn,
    gemini content as part lists, and functionResponse user turns."""
    snapshot = [
        _ctx_user(),
        {"id": "u1", "timestamp": "t1", "type": "user", "content": [{"text": "list files"}]},
        {"id": "g1", "timestamp": "t2", "type": "gemini", "model": "gemini-3-flash-preview",
         "content": [
             {"text": "Let me look.", "thought": True},
             {"functionCall": {"id": "sh1", "name": "run_shell_command", "args": {"command": "ls"}}},
         ],
         "thoughts": [{"subject": "Plan", "description": "run ls"}],
         "toolCalls": [_shell_call("sh1")]},
        _fr_user("sh1", "a.txt\nb.txt"),
        {"id": "g2", "timestamp": "t3", "type": "gemini", "content": [{"text": "Two files."}]},
    ]
    p = tmp_path / "s.jsonl"
    _write_jsonl(p, [
        _header_line(),
        # stale pre-snapshot lines are replaced wholesale
        _user_line("stale", id="old"),
        {"$set": {"messages": snapshot, "lastUpdated": "2026-10-07T23:41:00Z"}},
        _user_line("and now?", id="u2"),
        _gemini_line("Still two.", id="g3"),
    ])
    msgs = adapter.read_messages(p)
    texts = [m["message"]["content"] for m in msgs if isinstance(m["message"]["content"], str)]
    assert texts == ["list files", "and now?"]
    assistant = [m for m in msgs if m["type"] == "assistant"]
    # thoughts[] wins over thought parts; toolCalls wins over functionCall parts
    assert assistant[0]["message"]["content"] == [
        {"type": "thinking", "text": "Plan\nrun ls"},
        {"type": "tool_use", "id": "sh1", "name": "run_shell_command", "input": {"command": "ls"}},
    ]
    assert assistant[1]["message"]["content"] == [{"type": "text", "text": "Two files."}]
    # one tool_result for sh1 (from toolCalls), the functionResponse turn adds nothing
    results = [b for m in msgs if m["type"] == "user" and isinstance(m["message"]["content"], list)
               for b in m["message"]["content"]]
    assert [r["tool_use_id"] for r in results] == ["sh1"]
    # no empty user messages anywhere
    assert all(m["message"]["content"] for m in msgs)
    info = adapter.parse_session_info(p, "sid")
    assert info.title == "list files"
    assert info.message_count == 5


def test_063_function_response_user_folds_into_tool_result(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    """A functionResponse-only user line answers a functionCall part that has
    no toolCalls entry: it becomes tool_result blocks, not an empty turn."""
    p = tmp_path / "s.jsonl"
    _write_jsonl(p, [
        _header_line(),
        _user_line("go", id="u1"),
        {"id": "g1", "timestamp": "t", "type": "gemini", "content": [
            {"functionCall": {"id": "sh9", "name": "run_shell_command", "args": {"command": "pwd"}}},
        ]},
        _fr_user("sh9", "/home"),
        _gemini_line("You are in /home.", id="g2"),
    ])
    msgs = adapter.read_messages(p)
    assert msgs[1]["message"]["content"][0]["type"] == "tool_use"
    assert msgs[2]["message"]["content"] == [
        {"type": "tool_result", "tool_use_id": "sh9", "content": "/home", "is_error": False},
    ]
    assert len(msgs) == 4


def test_rewind_truncates_history(adapter: GeminiAdapter, tmp_path: Path) -> None:
    p = tmp_path / "s.jsonl"
    _write_jsonl(p, [
        _header_line(),
        _user_line("one", id="u1"),
        _gemini_line("1", id="g1"),
        _user_line("two", id="u2"),
        _gemini_line("2", id="g2"),
        {"$rewindTo": "u2"},
        _user_line("three", id="u3"),
    ])
    texts = [extract for m in adapter.read_messages(p)
             for extract in ([m["message"]["content"]] if isinstance(m["message"]["content"], str)
                             else [b.get("text") for b in m["message"]["content"]])]
    assert texts == ["one", "1", "three"]


def test_display_content_preferred_and_session_context_title_skipped(
    adapter: GeminiAdapter, tmp_path: Path,
) -> None:
    p = tmp_path / "s.jsonl"
    _write_jsonl(p, [
        _header_line(),
        _ctx_user(),
        {"id": "u1", "timestamp": "t", "type": "user",
         "content": [{"text": "explain @a.py"}, {"text": "--- a.py ---\nprint(1)"}],
         "displayContent": [{"text": "explain @a.py"}]},
    ])
    msgs = adapter.read_messages(p)
    assert [m["message"]["content"] for m in msgs] == ["explain @a.py"]
    assert adapter.parse_session_info(p, "sid").title == "explain @a.py"


def test_info_error_records_are_not_turns(adapter: GeminiAdapter, tmp_path: Path) -> None:
    p = tmp_path / "s.jsonl"
    _write_jsonl(p, [
        _header_line(), _user_line("hi", id="u1"),
        {"id": "i1", "timestamp": "t", "type": "info", "content": "Switched model"},
        {"id": "e1", "timestamp": "t", "type": "error", "content": "Quota"},
        _gemini_line("hello", id="g1"),
    ])
    assert len(adapter.read_messages(p)) == 2


def test_header_only_stub_is_an_empty_session(adapter: GeminiAdapter, tmp_path: Path) -> None:
    from manager.gemini.adapter import gemini_session_is_resumable

    p = tmp_path / "s.jsonl"
    _write_jsonl(p, [_header_line(), {"$set": {"messages": [_ctx_user()]}}])
    assert adapter.read_messages(p) == []
    assert adapter.parse_session_info(p, "sid").message_count == 0
    assert gemini_session_is_resumable(p) is False
    _write_jsonl(p, [_header_line(), _gemini_line("", thoughts=[{"subject": "s", "description": "d"}])])
    assert gemini_session_is_resumable(p) is True


def test_is_visible_message_rules(adapter: GeminiAdapter) -> None:
    assert adapter.is_visible_message(_user_line("hi"))
    assert not adapter.is_visible_message(_ctx_user())
    assert not adapter.is_visible_message(_fr_user("x", "out"))
    assert adapter.is_visible_message(_gemini_line("", tool_calls=[_shell_call()]))
    assert not adapter.is_visible_message({"id": "g", "type": "gemini", "content": ""})


def test_visible_line_indices_cut_between_turns(adapter: GeminiAdapter, tmp_path: Path) -> None:
    """Dropping the last N turns keeps a prefix that replays to the turns before."""
    from manager.gemini.adapter import _load_conversation

    lines = [
        _header_line(),                                   # 0
        _user_line("one", id="u1"),                       # 1
        _set_line(),                                      # 2
        _gemini_line("", id="g1", thoughts=[{"subject": "a", "description": "b"}]),  # 3
        _gemini_line("", id="g1", tool_calls=[_shell_call()],
                     thoughts=[{"subject": "a", "description": "b"}]),           # 4
        _set_line(),                                      # 5
        {"$set": {"messages": [                           # 6 (resume snapshot)
            _ctx_user(), _user_line("one", id="u1"),
            _gemini_line("", id="g1", tool_calls=[_shell_call()],
                         thoughts=[{"subject": "a", "description": "b"}]),
        ]}},
        _user_line("two", id="u2"),                       # 7
        _gemini_line("2", id="g2"),                       # 8
    ]
    idx = adapter.visible_line_indices(lines)
    assert idx == [2, 6, 7, 8]
    # drop the last 2 visible turns → keep lines[: idx[1] + 1]
    conv = _load_conversation(lines[: idx[1] + 1])
    assert [r["id"] for r in conv.records if adapter.is_visible_message(r)] == ["u1", "g1"]
