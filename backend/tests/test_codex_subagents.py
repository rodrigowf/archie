"""Codex multi-agent subagents (spec 12 §4.8): child-thread routing, lifecycle,
stop, history and discovery.

The live shapes follow a real ``codex app-server`` 0.161 run (2026-10-12):
the parent's ``collabAgentToolCall`` ``spawnAgent`` item starts with no
receiver, the child thread's ``thread/status/changed`` / ``turn/started``
arrive *before* the spawn item completes with ``receiverThreadIds``, every
child notification carries the child ``threadId``, no ``thread/started`` is
sent for the child, and the parent then ``wait``s.  Rollout shapes (snake
case, ``spawn_agent``, ``receiver_agents``, ``session_meta.source.subagent``)
are from the same run's files.
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

import pytest

from manager.base_session import SubagentNotFound, SubagentNotRunning
from manager.codex import adapter as codex_adapter
from manager.codex.adapter import CodexAdapter, _codex_discover_sessions
from manager.codex.session import CodexSessionManager
from manager.types import (
    SessionStatus,
    SubagentEvent,
    SubagentFinished,
    SubagentProgress,
    SubagentStarted,
    TextComplete,
    ToolResult,
    ToolUse,
    TurnComplete,
)
from tests.test_codex_session import THREAD, _config, _req, fake_codex  # noqa: F401 (fixture)

CHILD = "01a118ca-0000-7000-8000-0000000000c1"
SPAWN = "exec-spawn-1"


def _kinds(events) -> list[str]:
    out = []
    for e in events:
        if isinstance(e, SubagentEvent):
            out.append(f"sub:{type(e.event).__name__}")
        else:
            out.append(type(e).__name__)
    return out


# ---------------------------------------------------------------------------
# Live, against the scripted app-server
# ---------------------------------------------------------------------------

async def test_spawn_wait_routes_child_thread(fake_codex, tmp_path):
    sm = CodexSessionManager(config=_config(tmp_path))
    await sm.start()
    try:
        events = [ev async for ev in sm.send("please spawn an agent")]
    finally:
        await sm.stop()
    kinds = _kinds(events)
    # the spawn card, then the lifecycle start before the card's result (SA-2)
    i = kinds.index("ToolUse")
    assert kinds[i:i + 3] == ["ToolUse", "SubagentStarted", "ToolResult"]
    spawn_use = events[i]
    assert (spawn_use.tool_use_id, spawn_use.tool_name) == (SPAWN, "Task")
    assert spawn_use.tool_input["prompt"] == "Read note.txt and report it"
    started = events[i + 1]
    assert isinstance(started, SubagentStarted) and started.tool_use_id == SPAWN
    assert started.parent_tool_use_id is None and started.description == "Read note.txt and report it"
    # the child's command and answer are its own frames
    inner = [e for e in events if isinstance(e, SubagentEvent)]
    assert {e.tool_use_id for e in inner} == {SPAWN}
    assert [type(e.event).__name__ for e in inner] == ["ToolUse", "ToolResult", "TextDelta", "TextComplete"]
    assert inner[0].event.tool_name == "Bash" and inner[0].event.tool_input == {"command": "cat note.txt"}
    prog = [e for e in events if isinstance(e, SubagentProgress)]
    assert prog and prog[-1].usage["tokens"] == 9803
    fin = [e for e in events if isinstance(e, SubagentFinished)]
    assert [(f.status, f.summary) for f in fin] == [("done", "hello world")]
    assert fin[0].usage == {"tokens": 9803, "tool_uses": 1, "duration_ms": 4242}
    # wait is a small card of its own, never a Task
    waits = [e for e in events if isinstance(e, ToolUse) and e.tool_use_id == "exec-wait-1"]
    assert [w.tool_name for w in waits] == ["wait_agent"]
    # the child's turn/completed and error never end or alter the parent turn
    tcs = [e for e in events if isinstance(e, TurnComplete)]
    assert len(tcs) == 1 and not tcs[0].is_error and tcs[0].result == "The agent said hello world"
    assert tcs[0].usage["total_tokens"] == 120
    assert isinstance(events[-1], TurnComplete)
    assert [e.text for e in events if isinstance(e, TextComplete)] == ["The agent said hello world"]
    snap = sm.subagent_snapshot()
    assert [(r["tool_use_id"], r["status"], r["summary"]) for r in snap] == [(SPAWN, "done", "hello world")]


async def test_child_outliving_the_parent_turn_goes_out_of_turn(fake_codex, tmp_path):
    sm = CodexSessionManager(config=_config(tmp_path))
    oot: list = []
    sm.set_out_of_turn_sink(oot.append)
    await sm.start()
    try:
        events = [ev async for ev in sm.send("spawn later")]
        assert isinstance(events[-1], TurnComplete)
        assert sm.status == SessionStatus.IDLE
        for _ in range(100):
            if any(isinstance(getattr(i, "event", None), SubagentFinished) for i in oot):
                break
            await asyncio.sleep(0.05)
    finally:
        await sm.stop()
    got = [i.event for i in oot]
    assert any(isinstance(e, SubagentEvent) and e.tool_use_id == SPAWN for e in got)
    assert [e.status for e in got if isinstance(e, SubagentFinished)] == ["done"]
    # seq-stamped and continuing the turn's numbering, in order
    seqs = [i.seq for i in oot]
    assert seqs == sorted(seqs) and seqs[0] > 0
    assert not any(isinstance(e, TurnComplete) for e in got)


async def test_stop_subagent_interrupts_the_child_thread(fake_codex, tmp_path):
    sm = CodexSessionManager(config=_config(tmp_path))
    oot: list = []
    sm.set_out_of_turn_sink(oot.append)
    await sm.start()
    try:
        [ev async for ev in sm.send("spawn stopme")]
        for _ in range(100):
            if any(isinstance(getattr(i, "event", None), SubagentProgress) for i in oot):
                break
            await asyncio.sleep(0.05)
        with pytest.raises(SubagentNotFound):
            await sm.stop_subagent("nope")
        await sm.stop_subagent(SPAWN)
        for _ in range(100):
            if any(isinstance(getattr(i, "event", None), SubagentFinished) for i in oot):
                break
            await asyncio.sleep(0.05)
        with pytest.raises(SubagentNotRunning):
            await sm.stop_subagent(SPAWN)
    finally:
        await sm.stop()
    interrupts = _req(fake_codex, "turn/interrupt")
    assert interrupts == [{"threadId": CHILD, "turnId": "child-turn-1"}]
    assert [e.event.status for e in oot if isinstance(e.event, SubagentFinished)] == ["stopped"]


# ---------------------------------------------------------------------------
# Translation details (direct)
# ---------------------------------------------------------------------------

def _collab(tool: str, item_id: str, status: str, receivers: list[str], states: dict | None = None,
            prompt: str | None = None, sender: str = THREAD) -> dict:
    return {"type": "collabAgentToolCall", "id": item_id, "tool": tool, "status": status,
            "senderThreadId": sender, "receiverThreadIds": receivers, "prompt": prompt,
            "model": None, "agentsStates": states or {}}


async def test_nested_spawn_and_parent_state_untouched():
    sm = CodexSessionManager(config=_config(Path("/tmp")))
    sm._thread_id = THREAD
    got: list = []
    sm.set_out_of_turn_sink(got.append)
    grand = "01a118ca-0000-7000-8000-0000000000c2"
    n = sm._on_notification
    n("item/started", {"threadId": THREAD, "item": _collab("spawnAgent", SPAWN, "inProgress", [], prompt="outer")})
    n("item/completed", {"threadId": THREAD, "item": _collab("spawnAgent", SPAWN, "completed", [CHILD], prompt="outer")})
    n("turn/started", {"threadId": CHILD, "turn": {"id": "ct1"}})
    n("thread/tokenUsage/updated", {"threadId": CHILD, "tokenUsage": {"total": {"totalTokens": 5}, "last": {"totalTokens": 5}}})
    n("error", {"threadId": CHILD, "willRetry": False, "error": {"message": "child only"}})
    n("item/started", {"threadId": CHILD, "item": _collab("spawnAgent", "exec-nested", "inProgress", [], prompt="inner", sender=CHILD)})
    # the grandchild speaks before its spawn item completes: held, then replayed
    n("item/completed", {"threadId": grand, "item": {"type": "agentMessage", "id": "g1", "text": "early"}})
    n("item/completed", {"threadId": CHILD, "item": _collab("spawnAgent", "exec-nested", "completed", [grand], prompt="inner", sender=CHILD)})
    n("turn/completed", {"threadId": grand, "turn": {"id": "gt1", "status": "failed", "error": {"message": "bad"}}})
    events = [i.event for i in got]
    assert sm._turn_usage == {} and sm._turn_error is None and sm._active_turn_id is None
    starts = [e for e in events if isinstance(e, SubagentStarted)]
    assert [(s.tool_use_id, s.parent_tool_use_id) for s in starts] == [(SPAWN, None), ("exec-nested", SPAWN)]
    nested_card = [e for e in events if isinstance(e, SubagentEvent) and e.tool_use_id == SPAWN
                   and isinstance(e.event, ToolResult)]
    assert nested_card and nested_card[0].event.tool_use_id == "exec-nested"
    # started before the nested card's result
    assert events.index(starts[1]) < events.index(nested_card[0])
    early = [e for e in events if isinstance(e, SubagentEvent) and e.tool_use_id == "exec-nested"]
    assert [x.event.text for x in early if isinstance(x.event, TextComplete)] == ["early"]
    fin = [e for e in events if isinstance(e, SubagentFinished)]
    assert [(f.tool_use_id, f.status, f.summary) for f in fin] == [("exec-nested", "failed", "bad")]


async def test_failed_spawn_and_close_agent_states():
    sm = CodexSessionManager(config=_config(Path("/tmp")))
    sm._thread_id = THREAD
    got: list = []
    sm.set_out_of_turn_sink(got.append)
    n = sm._on_notification
    n("item/started", {"threadId": THREAD, "item": _collab("spawnAgent", "bad", "inProgress", [], prompt="x")})
    n("item/completed", {"threadId": THREAD, "item": _collab("spawnAgent", "bad", "failed", [], prompt="x")})
    n("item/completed", {"threadId": THREAD, "item": _collab("spawnAgent", SPAWN, "completed", [CHILD], prompt="y")})
    n("item/completed", {"threadId": THREAD, "item": _collab("closeAgent", "close-1", "completed", [CHILD],
                                                              {CHILD: {"status": "shutdown", "message": None}})})
    events = [i.event for i in got]
    fin = [(e.tool_use_id, e.status) for e in events if isinstance(e, SubagentFinished)]
    assert fin == [("bad", "failed"), (SPAWN, "stopped")]
    close = [e for e in events if isinstance(e, ToolUse) and e.tool_use_id == "close-1"]
    assert close and close[0].tool_name == "close_agent"
    card = [e for e in events if isinstance(e, ToolResult) and e.tool_use_id == "bad"]
    assert card and card[0].is_error


# ---------------------------------------------------------------------------
# History + discovery
# ---------------------------------------------------------------------------

def _write(path: Path, objs: list[dict]) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(json.dumps(o) for o in objs) + "\n")
    return path


def _ev(item: dict, ts: str = "2026-10-12T16:16:37Z") -> dict:
    return {"timestamp": ts, "type": "event_msg", "payload": {"type": "item_completed", "item": item}}


def _rollouts(root: Path) -> tuple[Path, Path]:
    day = root / "2026" / "10" / "12"
    parent = _write(day / f"rollout-2026-10-12T13-16-25-{THREAD}.jsonl", [
        {"timestamp": "2026-10-12T16:16:25Z", "type": "session_meta", "payload": {
            "id": THREAD, "timestamp": "2026-10-12T16:16:25Z", "cwd": "/tmp", "originator": "archie", "source": "vscode"}},
        _ev({"type": "UserMessage", "id": "u1", "content": [{"type": "text", "text": "go"}]}),
        _ev({"type": "CollabAgentToolCall", "id": SPAWN, "tool": "spawn_agent", "status": "completed",
             "sender_thread_id": THREAD, "receiver_thread_ids": [CHILD],
             "receiver_agents": [{"thread_id": CHILD, "agent_nickname": "Locke"}],
             "prompt": "Read note.txt", "agents_states": {CHILD: "pending_init"}}),
        _ev({"type": "CollabAgentToolCall", "id": "exec-wait-1", "tool": "wait", "status": "completed",
             "sender_thread_id": THREAD, "receiver_thread_ids": [CHILD],
             "receiver_agents": [{"thread_id": CHILD, "agent_nickname": "Locke"}],
             "agents_states": {CHILD: {"completed": "hello world"}}}),
        _ev({"type": "AgentMessage", "id": "m1", "content": [{"type": "Text", "text": "done"}]}),
    ])
    child = _write(day / f"rollout-2026-10-12T13-16-37-{CHILD}.jsonl", [
        {"timestamp": "2026-10-12T16:16:37.574Z", "type": "session_meta", "payload": {
            "id": CHILD, "session_id": THREAD, "parent_thread_id": THREAD, "timestamp": "2026-10-12T16:16:37.574Z",
            "cwd": "/tmp", "originator": "archie", "agent_nickname": "Locke",
            "source": {"subagent": {"thread_spawn": {"parent_thread_id": THREAD, "depth": 1,
                                                     "agent_nickname": "Locke", "agent_role": None}}}}},
        _ev({"type": "UserMessage", "id": "cu1", "content": [{"type": "text", "text": "Read note.txt"}]}),
        _ev({"type": "CommandExecution", "id": "exec-c", "command": ["/bin/bash", "-c", "cat note.txt"],
             "status": "completed", "aggregated_output": "hello world\n", "exit_code": 0}),
        {"timestamp": "2026-10-12T16:16:41Z", "type": "event_msg", "payload": {"type": "token_count", "info": {
            "total_token_usage": {"total_tokens": 19638}}}},
        _ev({"type": "AgentMessage", "id": "cm", "content": [{"type": "Text", "text": "hello world"}]}),
        {"timestamp": "2026-10-12T16:16:41.863Z", "type": "event_msg", "payload": {
            "type": "task_complete", "last_agent_message": "hello world", "duration_ms": 4242}},
    ])
    return parent, child


def test_history_rows_and_transcript(tmp_path):
    parent, child = _rollouts(tmp_path / "sessions")
    a = CodexAdapter()
    rows = a.list_subagents(parent)
    assert rows == [{
        "tool_use_id": SPAWN, "parent_tool_use_id": None, "agent_type": "agent",
        "description": "Read note.txt", "background": False, "status": "done", "activity": None,
        "usage": {"tokens": 19638, "tool_uses": 1, "duration_ms": 4242}, "summary": "hello world",
        "started_at": "2026-10-12T16:16:37.574Z", "ended_at": "2026-10-12T16:16:41.863Z",
    }]
    msgs = a.read_subagent_messages(parent, SPAWN)
    assert [m["type"] for m in msgs] == ["user", "assistant", "user", "assistant"]
    assert msgs[0]["message"]["content"] == "Read note.txt"
    assert a.read_subagent_messages(parent, "nope") is None
    # the parent's own history: the spawn is the Task card, wait a small card
    blocks = [b for m in a.read_messages(parent) if isinstance(m["message"]["content"], list)
              for b in m["message"]["content"]]
    uses = {b["id"]: b["name"] for b in blocks if b.get("type") == "tool_use"}
    assert uses == {SPAWN: "Task", "exec-wait-1": "wait_agent"}
    results = {b["tool_use_id"]: b["content"] for b in blocks if b.get("type") == "tool_result"}
    assert results == {SPAWN: "Started agent Locke", "exec-wait-1": "Locke: completed — hello world"}


def test_child_rollouts_are_not_sessions(tmp_path, monkeypatch):
    root = tmp_path / "sessions"
    parent, child = _rollouts(root)
    monkeypatch.setattr(codex_adapter.home, "sessions_roots", lambda project_dir: [root])
    codex_adapter._meta_cache.clear()
    found = dict(_codex_discover_sessions(str(tmp_path)))
    assert found == {THREAD: parent}
