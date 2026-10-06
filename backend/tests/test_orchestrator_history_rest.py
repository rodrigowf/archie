"""REST history fidelity for orchestrator files and thinking blocks (O-1, O-2).

- Orchestrator top-level ``tool_use`` / ``tool_result`` JSONL lines are folded
  into the assistant messages returned by ``SessionStore`` (tool cards survive
  a reload), for both the new per-TextComplete layout and old files.
- ``truncate_session`` counts visible messages the same way the REST data
  does, so ``drop_last_n`` computed from REST lands on the same message.
- Claude ``thinking`` blocks come back as ``type: "thinking"``.
"""

from __future__ import annotations

import json

import pytest

from manager.store import SessionStore


def _orch_meta(ts="2026-01-01T00:00:00Z"):
    return {"type": "orchestrator_meta", "orchestrator": True, "timestamp": ts}


def _user(text, ts="2026-01-01T00:00:01Z"):
    return {"type": "user", "message": {"role": "user", "content": text}, "timestamp": ts}


def _assistant(text, ts="2026-01-01T00:00:02Z"):
    return {"type": "assistant", "message": {"role": "assistant", "content": text}, "timestamp": ts}


def _tool_use(cid, name="search_memory", ts="2026-01-01T00:00:03Z"):
    return {"type": "tool_use", "tool_call_id": cid, "tool_name": name,
            "tool_input": {"q": cid}, "timestamp": ts}


def _tool_result(cid, output="ok", ts="2026-01-01T00:00:04Z", **extra):
    return {"type": "tool_result", "tool_call_id": cid, "output": output,
            "is_error": False, "timestamp": ts, **extra}


@pytest.fixture
def store(tmp_path, monkeypatch):
    import utils.paths as _paths
    monkeypatch.setattr(_paths, "PROJECT_ROOT", tmp_path)
    (tmp_path / "context").mkdir(parents=True)
    return SessionStore(str(tmp_path))


def _write(store, tmp_path, sid, lines):
    path = tmp_path / "context" / f"{sid}.jsonl"
    path.write_text("".join(json.dumps(l) + "\n" for l in lines))
    return path


def _shape(messages):
    return [
        (m.role, [b.type for b in m.blocks])
        for m in messages
    ]


def test_new_layout_interleaves_text_and_tools(store, tmp_path):
    _write(store, tmp_path, "orch-new", [
        _orch_meta(),
        _user("find x"),
        _assistant("Let me look."),
        _tool_use("c1"),
        _tool_result("c1", "found x"),
        _assistant("Found it."),
    ])
    msgs, total, _ = store.get_messages_paginated("orch-new")
    assert total == 2
    assert _shape(msgs) == [
        ("user", ["text"]),
        ("assistant", ["text", "tool_use", "tool_result", "text"]),
    ]
    tool_use = msgs[1].blocks[1]
    assert tool_use.tool_use_id == "c1"
    assert tool_use.tool_name == "search_memory"
    assert tool_use.tool_input == {"q": "c1"}
    result = msgs[1].blocks[2]
    assert (result.tool_use_id, result.output, result.is_error) == ("c1", "found x", False)
    assert msgs[1].text == "Let me look.\nFound it."
    assert msgs[1].timestamp is not None


def test_old_layout_prepends_tools_to_joined_text(store, tmp_path):
    _write(store, tmp_path, "orch-old", [
        _orch_meta(),
        _user("go"),
        _tool_use("c1"),
        _tool_result("c1"),
        _tool_use("c2"),
        _tool_result("c2", {"answer": 42}),  # non-string output (Gemini voice)
        _assistant("a\nb"),
    ])
    msgs, total, _ = store.get_messages_paginated("orch-old")
    assert total == 2
    assert _shape(msgs)[1] == (
        "assistant", ["tool_use", "tool_result", "tool_use", "tool_result", "text"],
    )
    assert msgs[1].blocks[3].output == json.dumps({"answer": 42})


def test_tool_only_turn_gets_a_synthetic_message(store, tmp_path):
    _write(store, tmp_path, "orch-tools-only", [
        _orch_meta(),
        _user("one"),
        _tool_use("c1", ts="2026-01-01T00:00:05Z"),
        _tool_result("c1"),
        _user("two", ts="2026-01-01T00:00:09Z"),
        _assistant("answer"),
    ])
    msgs, total, _ = store.get_messages_paginated("orch-tools-only")
    assert _shape(msgs) == [
        ("user", ["text"]),
        ("assistant", ["tool_use", "tool_result"]),
        ("user", ["text"]),
        ("assistant", ["text"]),
    ]
    assert msgs[1].timestamp is not None


def test_trailing_tools_attach_to_previous_assistant(store, tmp_path):
    _write(store, tmp_path, "orch-trailing", [
        _orch_meta(),
        _user("go"),
        _assistant("Starting."),
        _tool_use("c1"),
        _tool_result("c1"),
    ])
    msgs, total, _ = store.get_messages_paginated("orch-trailing")
    assert total == 2
    assert _shape(msgs)[1] == ("assistant", ["text", "tool_use", "tool_result"])


def test_voice_tool_lines_fold_too(store, tmp_path):
    _write(store, tmp_path, "orch-voice", [
        _orch_meta(),
        {**_user("[voice] lights on"), "source": "voice_transcription"},
        {**_tool_use("v1", name="run_script"), "source": "voice"},
        _tool_result("v1", "done", source="voice"),
        {**_assistant("Done."), "source": "voice_response"},
    ])
    msgs, _, _ = store.get_messages_paginated("orch-voice")
    assert _shape(msgs)[1] == ("assistant", ["tool_use", "tool_result", "text"])


@pytest.mark.parametrize("drop", [1, 2, 3])
def test_truncate_matches_rest_counts(store, tmp_path, drop):
    """drop_last_n computed from REST messages keeps exactly the REST
    messages above the cut — a kept turn keeps all its lines — and never
    separates a tool call from its result."""
    lines = [
        _orch_meta(),
        _user("one"),
        _tool_use("c1"),
        _tool_result("c1"),
        _user("two"),
        _assistant("a1"),
        _tool_use("c2"),
        _tool_result("c2"),
        _assistant("a2"),
    ]
    _write(store, tmp_path, "orch-trunc", lines)
    before, total, _ = store.get_messages_paginated("orch-trunc")
    assert total == 4

    assert store.truncate_session("orch-trunc", drop) is True
    after, _, _ = store.get_messages_paginated("orch-trunc")
    assert _shape(after) == _shape(before[: total - drop])

    raw = [json.loads(l) for l in
           (tmp_path / "context" / "orch-trunc.jsonl").read_text().splitlines()]
    uses = {o["tool_call_id"] for o in raw if o["type"] == "tool_use"}
    results = {o["tool_call_id"] for o in raw if o["type"] == "tool_result"}
    assert uses == results


def _reviewer_turn_lines():
    # 0 meta, 1 user, 2 text, 3 tool_use, 4 tool_result, 5 text
    return [
        _orch_meta(),
        _user("check the lights"),
        _assistant("Let me check."),
        _tool_use("c1"),
        _tool_result("c1", "on"),
        _assistant("They are on."),
    ]


def test_text_tool_text_turn_is_one_message_and_rewinds_whole(store, tmp_path):
    """Reviewer's case: a text → tool → text turn used to count as two
    visible lines ([1, 2, 5]) while the live clients show one bubble, so
    dropLastN cut inside the turn.  Now: one REST message, one visible
    index for the turn, and rewinding to the user message drops it all."""
    from manager.claude.adapter import ClaudeAdapter

    lines = _reviewer_turn_lines()
    path = _write(store, tmp_path, "orch-review", lines)
    msgs, total, _ = store.get_messages_paginated("orch-review")
    assert total == 2
    assert _shape(msgs)[1] == ("assistant", ["text", "tool_use", "tool_result", "text"])

    objs = [json.loads(l) for l in path.read_text().splitlines()]
    assert ClaudeAdapter().visible_line_indices(objs) == [1, 5]

    # Rewind to the user message (the UI's dropLastN = 1 bubble below it).
    assert store.truncate_session("orch-review", 1) is True
    raw = [json.loads(l) for l in path.read_text().splitlines()]
    assert [o["type"] for o in raw] == ["orchestrator_meta", "user"]


def test_rewinding_a_later_turn_keeps_the_previous_turn_whole(store, tmp_path):
    lines = _reviewer_turn_lines() + [
        _user("and the fan?", ts="2026-01-01T00:01:00Z"),
        _assistant("Fan is off.", ts="2026-01-01T00:01:01Z"),
    ]
    path = _write(store, tmp_path, "orch-review2", lines)
    # Bubbles: user, assistant, user, assistant → drop the last two.
    assert store.truncate_session("orch-review2", 2) is True
    raw = [json.loads(l) for l in path.read_text().splitlines()]
    assert len(raw) == 6  # the whole first turn, nothing of the second
    msgs, total, _ = store.get_messages_paginated("orch-review2")
    assert total == 2
    assert msgs[1].text == "Let me check.\nThey are on."


def test_wake_reply_without_user_line_merges_into_previous_turn(store, tmp_path):
    """Background-notification wake turns have no user line; the live web
    merges the reply into the previous bubble, and so does REST."""
    _write(store, tmp_path, "orch-wake", [
        _orch_meta(),
        _user("start the job"),
        _assistant("Started."),
        {"type": "background_notification", "notification_id": "n1"},
        _assistant("The job finished."),
    ])
    msgs, total, _ = store.get_messages_paginated("orch-wake")
    assert total == 2
    assert _shape(msgs)[1] == ("assistant", ["text", "text"])


def test_claude_session_unaffected_by_fold(store, tmp_path):
    """A plain Claude agent file (no top-level tool lines) reads as before."""
    _write(store, tmp_path, "agent", [
        _user("hi"),
        {"type": "assistant", "message": {"role": "assistant", "content": [
            {"type": "tool_use", "id": "t1", "name": "Bash", "input": {}},
        ]}, "timestamp": "2026-01-01T00:00:02Z"},
        {"type": "user", "message": {"role": "user", "content": [
            {"type": "tool_result", "tool_use_id": "t1", "content": "out"},
        ]}, "timestamp": "2026-01-01T00:00:03Z"},
    ])
    msgs, total, _ = store.get_messages_paginated("agent")
    assert total == 3
    assert _shape(msgs) == [
        ("user", ["text"]), ("assistant", ["tool_use"]), ("user", ["tool_result"]),
    ]


def test_claude_thinking_block_survives_history(store, tmp_path):
    _write(store, tmp_path, "thinker", [
        _user("hi"),
        {"type": "assistant", "message": {"role": "assistant", "content": [
            {"type": "thinking", "thinking": "Let me reason.", "signature": "sig"},
        ]}, "timestamp": "2026-01-01T00:00:02Z"},
        {"type": "assistant", "message": {"role": "assistant", "content": [
            {"type": "text", "text": "Answer."},
        ]}, "timestamp": "2026-01-01T00:00:03Z"},
    ])
    msgs, _, _ = store.get_messages_paginated("thinker")
    thinking = msgs[1].blocks
    assert [(b.type, b.text) for b in thinking] == [("thinking", "Let me reason.")]
    # Thinking is not part of the message's plain text.
    assert msgs[1].text == ""
    assert msgs[2].blocks[0].type == "text"


def test_normalized_thinking_text_field_is_thinking_block():
    """Qwen/Gemini adapters normalize thinking under ``text``."""
    from manager.protocol import extract_blocks

    blocks = extract_blocks({"message": {"content": [
        {"type": "thinking", "text": "hmm"},
        {"type": "text", "text": "hi"},
    ]}})
    assert [(b.type, b.text) for b in blocks] == [("thinking", "hmm"), ("text", "hi")]
