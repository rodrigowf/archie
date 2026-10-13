"""REST for subagents (spec 12 SA-8) against a temp ``context/`` laid out like
the Claude CLI writes it.  All content is synthetic."""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import MagicMock

import pytest
from httpx import ASGITransport, AsyncClient

from api.app import create_app
from api.deps import get_pool, get_store
from api.pool import SessionPool
from manager.store import SessionStore

SID = "11111111-2222-3333-4444-555555555555"


def _line(**kw) -> str:
    return json.dumps(kw)


def _assistant(ts, *blocks, sidechain=False):
    return _line(type="assistant", timestamp=ts, isSidechain=sidechain,
                 message={"role": "assistant", "content": list(blocks)})


def _user(ts, content, tur=None, sidechain=False, **extra):
    d = dict(type="user", timestamp=ts, isSidechain=sidechain,
             message={"role": "user", "content": content}, **extra)
    if tur is not None:
        d["toolUseResult"] = tur
    return json.dumps(d)


def _tool_use(tid, name, inp):
    return {"type": "tool_use", "id": tid, "name": name, "input": inp}


def _tool_result(tid, text, is_error=False):
    return {"type": "tool_result", "tool_use_id": tid, "is_error": is_error,
            "content": [{"type": "text", "text": text}]}


def _write(path: Path, lines: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n")


@pytest.fixture
def ctx(tmp_path: Path) -> Path:
    context = tmp_path / "context"
    sub = context / SID / "subagents"
    # Parent conversation: a sync (old-style) agent, a background agent that
    # finished via a task notification, one that never finished, a failed spawn.
    _write(context / f"{SID}.jsonl", [
        _user("2026-10-12T10:00:00Z", "Investigate"),
        _assistant("2026-10-12T10:00:01Z", _tool_use("t_old", "Task", {
            "subagent_type": "Explore", "description": "Old search", "prompt": "Search."})),
        _user("2026-10-12T10:00:09Z", [_tool_result("t_old", "Old report")], tur={
            "status": "completed", "agentId": "a0ld", "totalTokens": 500,
            "totalToolUseCount": 4, "totalDurationMs": 8000}),
        _assistant("2026-10-12T10:01:00Z", _tool_use("t_bg", "Agent", {
            "subagent_type": "general-purpose", "description": "Digest logs",
            "prompt": "Read the logs.", "run_in_background": True})),
        _user("2026-10-12T10:01:01Z", [_tool_result("t_bg", "Async agent launched successfully.\nagentId: abg1")],
              tur={"isAsync": True, "status": "async_launched", "agentId": "abg1"}),
        _assistant("2026-10-12T10:02:00Z", _tool_use("t_dead", "Agent", {
            "subagent_type": "Explore", "description": "Never ends", "prompt": "Loop."})),
        _user("2026-10-12T10:02:01Z", [_tool_result("t_dead", "Async agent launched successfully.\nagentId: adead")],
              tur={"isAsync": True, "status": "async_launched", "agentId": "adead"}),
        _assistant("2026-10-12T10:03:00Z", _tool_use("t_bad", "Agent", {
            "subagent_type": "nope", "description": "Bad type", "prompt": "x"})),
        _user("2026-10-12T10:03:01Z", [_tool_result("t_bad", "Agent type 'nope' not found", is_error=True)]),
        _user("2026-10-12T10:04:00Z",
              "<task-notification>\n<task-id>abg1</task-id>\n<tool-use-id>t_bg</tool-use-id>\n"
              "<status>completed</status>\n<summary>Agent \"Digest logs\" finished</summary>\n"
              "<result>Logs are clean.</result>\n<usage><subagent_tokens>1200</subagent_tokens>"
              "<tool_uses>2</tool_uses><duration_ms>3000</duration_ms></usage>\n</task-notification>",
              origin={"kind": "task-notification"}),
        _assistant("2026-10-12T10:04:02Z", {"type": "text", "text": "The logs are clean."}),
        # Mid-2026 CLI: the agent id only in a queue-operation line.
        _assistant("2026-10-12T10:05:00Z", _tool_use("t_q", "Task", {
            "subagent_type": "Explore", "description": "Queued", "prompt": "Queue it."})),
        _line(type="queue-operation", operation="enqueue", timestamp="2026-10-12T10:05:00Z",
              content=json.dumps({"task_id": "aq1", "tool_use_id": "t_q", "task_type": "local_agent"})),
        _user("2026-10-12T10:05:09Z", [_tool_result("t_q", "Queued report")]),
        # No id anywhere: matched by the spawn's prompt.
        _assistant("2026-10-12T10:06:00Z", _tool_use("t_p", "Task", {
            "subagent_type": "Explore", "description": "By prompt", "prompt": "Unique prompt P."})),
        _user("2026-10-12T10:06:09Z", [_tool_result("t_p", "Prompt report")]),
    ])
    _write(sub / "agent-aq1.jsonl", [
        _user("2026-10-12T10:05:01Z", "Queue it.", sidechain=True),
        _assistant("2026-10-12T10:05:08Z", {"type": "text", "text": "Queued report"}, sidechain=True),
    ])
    _write(sub / "agent-ap1.jsonl", [
        _user("2026-10-12T10:06:01Z", "Unique prompt P.", sidechain=True),
        _assistant("2026-10-12T10:06:08Z", {"type": "text", "text": "Prompt report"}, sidechain=True),
    ])
    # Old CLI: no sidecar, matched through toolUseResult.agentId.
    _write(sub / "agent-a0ld.jsonl", [
        _user("2026-10-12T10:00:01Z", "Search.", sidechain=True),
        _assistant("2026-10-12T10:00:02Z", _tool_use("g1", "Grep", {"pattern": "x"}), sidechain=True),
        _user("2026-10-12T10:00:03Z", [_tool_result("g1", "a.ts:1")], sidechain=True),
        _assistant("2026-10-12T10:00:08Z", {"type": "text", "text": "Old report"}, sidechain=True),
    ])
    # New CLI: sidecar with toolUseId; it spawns a nested agent.
    _write(sub / "agent-abg1.jsonl", [
        _user("2026-10-12T10:01:01Z", "Read the logs.", sidechain=True),
        _assistant("2026-10-12T10:01:02Z", _tool_use("t_nest", "Agent", {
            "subagent_type": "Explore", "description": "Count lines", "prompt": "wc"}), sidechain=True),
        _user("2026-10-12T10:01:05Z", [_tool_result("t_nest", "42 lines")],
              tur={"status": "completed", "agentId": "anest"}, sidechain=True),
        _assistant("2026-10-12T10:01:06Z", {"type": "text", "text": "Logs are clean."}, sidechain=True),
    ])
    (sub / "agent-abg1.meta.json").write_text(json.dumps({
        "agentType": "general-purpose", "description": "Digest logs", "toolUseId": "t_bg",
        "spawnDepth": 1, "requestShape": "background"}))
    _write(sub / "agent-anest.jsonl", [
        _user("2026-10-12T10:01:02Z", "wc", sidechain=True),
        _assistant("2026-10-12T10:01:04Z", {"type": "text", "text": "42 lines"}, sidechain=True),
    ])
    (sub / "agent-anest.meta.json").write_text(json.dumps({
        "agentType": "Explore", "description": "Count lines", "toolUseId": "t_nest",
        "spawnDepth": 2, "parentAgentId": "abg1"}))
    # Running when the process died: last line is a tool call.
    _write(sub / "agent-adead.jsonl", [
        _user("2026-10-12T10:02:01Z", "Loop.", sidechain=True),
        _assistant("2026-10-12T10:02:02Z", _tool_use("b1", "Bash", {"command": "sleep 999"}), sidechain=True),
    ])
    (sub / "agent-adead.meta.json").write_text(json.dumps({"agentType": "Explore", "toolUseId": "t_dead"}))
    # Compaction helper: never a subagent.
    _write(sub / "agent-acompact-1234.jsonl", [_user("2026-10-12T10:05:00Z", "Summarize.")])
    return tmp_path


def _app(project: Path, pool: SessionPool | None = None):
    app = create_app()
    store = SessionStore(project)
    app.dependency_overrides[get_store] = lambda: store
    app.dependency_overrides[get_pool] = lambda: pool or SessionPool()
    return app


async def _get(app, url):
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://localhost") as c:
        return await c.get(url)


async def test_list_subagents_from_disk(ctx):
    r = await _get(_app(ctx), f"/api/sessions/{SID}/subagents")
    assert r.status_code == 200
    rows = {row["tool_use_id"]: row for row in r.json()["subagents"]}
    assert set(rows) == {"t_old", "t_bg", "t_nest", "t_dead", "t_bad", "t_q", "t_p"}
    order = [row["tool_use_id"] for row in r.json()["subagents"]]
    assert order.index("t_old") < order.index("t_bg") < order.index("t_dead")

    assert rows["t_old"]["status"] == "done" and rows["t_old"]["summary"] == "Old report"
    assert rows["t_old"]["agent_type"] == "Explore" and rows["t_old"]["background"] is False
    assert rows["t_old"]["usage"] == {"tokens": 500, "tool_uses": 4, "duration_ms": 8000}

    bg = rows["t_bg"]
    assert (bg["status"], bg["summary"], bg["background"]) == ("done", "Logs are clean.", True)
    assert bg["usage"] == {"tokens": 1200, "tool_uses": 2, "duration_ms": 3000}
    assert bg["description"] == "Digest logs" and bg["parent_tool_use_id"] is None

    assert rows["t_nest"]["parent_tool_use_id"] == "t_bg"
    assert rows["t_nest"]["status"] == "done" and rows["t_nest"]["summary"] == "42 lines"
    assert rows["t_dead"]["status"] == "stopped"
    assert rows["t_bad"]["status"] == "failed"


async def test_list_merges_the_live_registry(ctx):
    pool = SessionPool()
    sm = MagicMock()
    sm.sdk_session_id = SID
    sm.subagent_snapshot.return_value = [
        {"tool_use_id": "t_dead", "status": "running", "activity": "Bash"},
        {"tool_use_id": "t_new", "status": "running", "agent_type": "Explore", "description": "Live one"},
    ]
    pool._sessions["L1"] = sm
    rows = {row["tool_use_id"]: row for row in
            (await _get(_app(ctx, pool), f"/api/sessions/{SID}/subagents")).json()["subagents"]}
    assert rows["t_dead"]["status"] == "running" and rows["t_dead"]["activity"] == "Bash"
    assert rows["t_dead"]["description"] == "Never ends"  # disk fills what live lacks
    assert rows["t_new"]["description"] == "Live one"


async def test_subagent_messages_page(ctx):
    r = await _get(_app(ctx), f"/api/sessions/{SID}/subagents/t_old/messages")
    assert r.status_code == 200
    body = r.json()
    assert body["total_count"] == 4 and body["has_more"] is False and body["start_index"] == 0
    first, second, third, last = body["messages"]
    assert first["role"] == "user" and first["text"] == "Search."
    assert second["blocks"][0]["type"] == "tool_use" and second["blocks"][0]["tool_name"] == "Grep"
    assert third["blocks"][0] == {"type": "tool_result", "text": None, "tool_use_id": "g1", "tool_name": None,
                                  "tool_input": None, "output": "a.ts:1", "is_error": False}
    assert last["text"] == "Old report"

    r = await _get(_app(ctx), f"/api/sessions/{SID}/subagents/t_bg/messages?limit=2")
    body = r.json()
    assert body["total_count"] == 4 and body["has_more"] is True and body["start_index"] == 2
    r = await _get(_app(ctx), f"/api/sessions/{SID}/subagents/t_bg/messages?limit=2&before=2")
    assert [m["text"] for m in r.json()["messages"]][0] == "Read the logs."


async def test_legacy_transcripts_matched_by_queue_operation_and_prompt(ctx):
    for tid, text in (("t_q", "Queue it."), ("t_p", "Unique prompt P.")):
        r = await _get(_app(ctx), f"/api/sessions/{SID}/subagents/{tid}/messages")
        assert r.status_code == 200 and r.json()["messages"][0]["text"] == text


async def test_known_subagent_without_transcript_is_an_empty_page(ctx):
    r = await _get(_app(ctx), f"/api/sessions/{SID}/subagents/t_bad/messages")
    assert r.status_code == 200 and r.json()["messages"] == [] and r.json()["total_count"] == 0


async def test_unknown_subagent_and_session_404(ctx):
    assert (await _get(_app(ctx), f"/api/sessions/{SID}/subagents/t_nope/messages")).status_code == 404
    assert (await _get(_app(ctx), "/api/sessions/nope/subagents/t1/messages")).status_code == 404
    assert (await _get(_app(ctx), "/api/sessions/nope/subagents")).status_code == 404


async def test_live_only_subagent_messages_empty_page(ctx):
    pool = SessionPool()
    sm = MagicMock()
    sm.sdk_session_id = SID
    sm.subagent_snapshot.return_value = [{"tool_use_id": "t_new", "status": "running"}]
    pool._sessions["L1"] = sm
    r = await _get(_app(ctx, pool), f"/api/sessions/{SID}/subagents/t_new/messages")
    assert r.status_code == 200 and r.json()["messages"] == []


async def test_session_started_carries_subagents(ctx):
    """The chat WS snapshot: history merged with the live registry."""
    from api.routes import chat as chat_mod

    pool = SessionPool()
    sm = MagicMock()
    sm.sdk_session_id = SID
    sm.provider_name = "claude"
    sm._config.model = None
    sm.stream_id = None
    sm.replay_after.return_value = ("ok", [])
    sm.subagent_snapshot.return_value = [{"tool_use_id": "t_dead", "status": "running"}]
    pool._sessions["L1"] = sm
    sent: list[bytes] = []
    ws = MagicMock()
    ws.app.state.store = SessionStore(ctx)

    async def _send(data):
        sent.append(data)
    ws.send_bytes = _send
    await chat_mod._send_session_started(ws, pool, sm, "L1", None)
    env = json.loads(sent[0])
    assert env["type"] == "session_started"
    rows = {r["tool_use_id"]: r for r in env["subagents"]}
    assert rows["t_dead"]["status"] == "running" and rows["t_bg"]["status"] == "done"


async def test_stop_subagent_unsupported_harness():
    from api.routes import chat as chat_mod
    from manager.base_session import BaseSessionManager

    sent: list[dict] = []
    ws = MagicMock()

    async def _send(data):
        sent.append(json.loads(data))
    ws.send_bytes = _send
    sm = MagicMock()
    sm.provider_name = "gemini"
    sm.stop_subagent = lambda tid: BaseSessionManager.stop_subagent(sm, tid)
    await chat_mod._handle_stop_subagent(ws, sm, "t1")
    assert sent[-1]["type"] == "error" and sent[-1]["error"] == "unsupported"
    assert sent[-1]["detail"] == "This session can't stop a single agent."
    await chat_mod._handle_stop_subagent(ws, sm, None)
    assert sent[-1]["error"] == "invalid_stop_subagent"


async def test_stop_subagent_errors_are_human_text():
    """Clients toast ``detail`` verbatim: never a raw tool_use_id."""
    from api.routes import chat as chat_mod
    from manager.base_session import SubagentNotFound, SubagentNotRunning

    sent: list[dict] = []
    ws = MagicMock()

    async def _send(data):
        sent.append(json.loads(data))
    ws.send_bytes = _send
    sm = MagicMock()
    for exc, code, text in (
        (SubagentNotFound("toolu_x"), "unknown_subagent", "Unknown agent."),
        (SubagentNotRunning("toolu_x"), "subagent_not_running", "This agent already finished."),
        (RuntimeError("boom"), "stop_subagent_failed", "Couldn't stop the agent. Try again."),
    ):
        async def _stop(tid, _e=exc):
            raise _e
        sm.stop_subagent = _stop
        await chat_mod._handle_stop_subagent(ws, sm, "toolu_x")
        assert (sent[-1]["error"], sent[-1]["detail"]) == (code, text)
        assert "toolu_x" not in sent[-1]["detail"]


async def test_session_started_reads_history_once_per_live_session(ctx):
    """Finding 11: the disk scan runs once per live session, not on every
    start / reattach (the live registry covers everything after it)."""
    from api.routes import chat as chat_mod

    class _SM:  # weak-referenceable stand-in for a session manager
        sdk_session_id = SID
        is_resumed = True

    pool = SessionPool()
    store = MagicMock()
    store.list_subagents.return_value = [{"tool_use_id": "t_old", "status": "done"}]
    ws = MagicMock()
    ws.app.state.store = store
    sm = _SM()
    for _ in range(3):
        rows = await chat_mod._subagents_snapshot(ws, pool, sm, "L1")
        assert [r["tool_use_id"] for r in rows] == ["t_old"]
    assert store.list_subagents.call_count == 1
    fresh = _SM()
    fresh.is_resumed = False  # a brand-new session has no history on disk
    assert await chat_mod._subagents_snapshot(ws, pool, fresh, "L2") == []
    assert store.list_subagents.call_count == 1
