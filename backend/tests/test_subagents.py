"""Subagents (spec 12 §4.8): Claude event routing, lifecycle mapping, the
out-of-turn broadcast path, serializer shapes against the shared protocol
fixtures, the registry, and the runner bucket.

SDK messages are synthesized in the shapes the bundled CLI 2.1.292 emits
(seen in a live probe, 2026-10-12): every Agent call is backgrounded
(``is_backgrounded: true``, ``async_launched`` tool result), the parent turn
ends early, and each finished agent makes the CLI start a turn of its own
whose ``ResultMessage.origin.kind`` is ``task-notification``.
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import orjson
import pytest
from claude_agent_sdk import (
    AssistantMessage,
    ResultMessage,
    SystemMessage,
    TextBlock,
    ToolResultBlock,
    ToolUseBlock,
    UserMessage,
)
from claude_agent_sdk.types import (
    TaskNotificationMessage,
    TaskProgressMessage,
    TaskStartedMessage,
    TaskUpdatedMessage,
)
from starlette.websockets import WebSocketState

from api.pool import SessionPool
from api.serializers import serialize_event
from manager.claude.session import SessionManager
from manager.subagents import SubagentRegistry, merge_summaries, wire_status
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
    UnpromptedTurnStarted,
)
from tests.test_session import _make_mock_client

FIXTURES = Path(__file__).resolve().parents[2] / "apps" / "protocol-fixtures"


# ---------------------------------------------------------------------------
# SDK message builders
# ---------------------------------------------------------------------------

def _spawn(tid, *, agent_type="Explore", description="Find it", background=False, parent=None):
    inp = {"subagent_type": agent_type, "description": description, "prompt": "Do it."}
    if background:
        inp["run_in_background"] = True
    return AssistantMessage(
        content=[ToolUseBlock(id=tid, name="Agent", input=inp)], model="m", parent_tool_use_id=parent,
    )


def _task_started(task_id, tid, *, task_type="local_agent", backgrounded=True, run_id="r1", **extra):
    data = {"type": "system", "subtype": "task_started", "task_id": task_id, "tool_use_id": tid,
            "task_type": task_type, "is_backgrounded": backgrounded, "run_id": run_id,
            "subagent_type": "Explore", **extra}
    return TaskStartedMessage(
        subtype="task_started", data=data, task_id=task_id, description="Find it",
        uuid="u", session_id="s", tool_use_id=tid, task_type=task_type,
    )


def _task_progress(task_id, tid, activity="Reading a.ts", tool="Read"):
    usage = {"total_tokens": 900, "tool_uses": 1, "duration_ms": 1500}
    return TaskProgressMessage(
        subtype="task_progress", data={"run_id": "r1"}, task_id=task_id, description=activity,
        usage=usage, uuid="u", session_id="s", tool_use_id=tid, last_tool_name=tool,
    )


def _task_updated(task_id, status):
    return TaskUpdatedMessage(
        subtype="task_updated", data={}, task_id=task_id, patch={"status": status}, status=status,
    )


def _task_notification(task_id, tid, status="completed", summary="It is in a.ts.", run_id="r1"):
    return TaskNotificationMessage(
        subtype="task_notification", data={"run_id": run_id}, task_id=task_id, status=status,
        output_file=f"/tmp/tasks/{task_id}.output", summary=summary, uuid="u", session_id="s",
        tool_use_id=tid, usage={"total_tokens": 1200, "tool_uses": 2, "duration_ms": 3000},
    )


def _sub_tool(parent, tid, name="Grep", inp=None):
    return AssistantMessage(
        content=[ToolUseBlock(id=tid, name=name, input=inp or {"pattern": "x"})],
        model="m", parent_tool_use_id=parent,
    )


def _sub_result(parent, tid, out="a.ts:1"):
    return UserMessage(content=[ToolResultBlock(tool_use_id=tid, content=out, is_error=False)],
                       parent_tool_use_id=parent)


def _sub_text(parent, text):
    return AssistantMessage(content=[TextBlock(text=text)], model="m", parent_tool_use_id=parent)


def _launch_result(tid, agent_id):
    return UserMessage(
        content=[ToolResultBlock(tool_use_id=tid, is_error=False,
                                 content=f"Async agent launched successfully.\nagentId: {agent_id}")],
        tool_use_result={"isAsync": True, "status": "async_launched", "agentId": agent_id},
    )


def _result(origin=None, text="done"):
    return ResultMessage(
        subtype="success", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=1,
        session_id="sdk-1", total_cost_usd=0.0, usage={"input_tokens": 1, "output_tokens": 1},
        result=text, origin=origin,
    )


def _init():
    return SystemMessage(subtype="init", data={"session_id": "sdk-1"})


async def _process(sm, *msgs):
    out = []
    for m in msgs:
        async for ev in sm._process_message(m):
            out.append(ev)
    return out


def _sm():
    return SessionManager()


# ---------------------------------------------------------------------------
# _process_message routing
# ---------------------------------------------------------------------------

class TestRouting:
    async def test_subagent_content_is_wrapped_never_flattened(self):
        sm = _sm()
        evs = await _process(
            sm,
            _spawn("t1"),
            _task_started("a1", "t1", backgrounded=False),
            _sub_tool("t1", "g1"),
            _sub_result("t1", "g1"),
            _sub_text("t1", "Found it in a.ts."),
        )
        assert isinstance(evs[0], ToolUse) and evs[0].tool_name == "Agent"
        assert isinstance(evs[1], SubagentStarted)
        assert evs[1].tool_use_id == "t1" and evs[1].agent_type == "Explore"
        assert evs[1].parent_tool_use_id is None and evs[1].background is False
        inner = [e for e in evs if isinstance(e, SubagentEvent)]
        assert [type(e.event) for e in inner] == [ToolUse, ToolResult, TextComplete]
        assert all(e.tool_use_id == "t1" for e in inner)
        assert inner[1].event.tool_use_id == "g1" and inner[1].event.output == "a.ts:1"
        # Nothing of the subagent reached the parent's own event stream.
        top = [e for e in evs if isinstance(e, (ToolUse, ToolResult, TextComplete))]
        assert [e.tool_use_id for e in top] == ["t1"]

    async def test_subagent_events_do_not_flip_status(self):
        sm = _sm()
        sm._status = SessionStatus.IDLE
        await _process(sm, _sub_tool("t9", "g1"), _sub_text("t9", "hi"))
        assert sm._status == SessionStatus.IDLE
        await _process(sm, _spawn("t1"))
        assert sm._status == SessionStatus.TOOL_USE  # the parent's own call still does

    async def test_lifecycle_progress_and_finish_with_summary(self):
        sm = _sm()
        evs = await _process(
            sm,
            _spawn("t1"),
            _task_started("a1", "t1"),
            _task_progress("a1", "t1"),
            _task_updated("a1", "completed"),   # summary still to come
            _task_notification("a1", "t1"),
        )
        prog = [e for e in evs if isinstance(e, SubagentProgress)]
        assert prog and prog[0].activity == "Reading a.ts"
        assert prog[0].usage == {"tokens": 900, "tool_uses": 1, "duration_ms": 1500}
        fin = [e for e in evs if isinstance(e, SubagentFinished)]
        assert len(fin) == 1
        assert fin[0].status == "done" and fin[0].summary == "It is in a.ts."
        assert fin[0].usage == {"tokens": 1200, "tool_uses": 2, "duration_ms": 3000}
        assert fin[0].ended_at

    async def test_task_updated_terminal_without_notification_is_flushed(self):
        sm = _sm()
        await _process(sm, _spawn("t1"), _task_started("a1", "t1"))
        evs = await _process(sm, _task_updated("a1", "failed"))
        assert not [e for e in evs if isinstance(e, SubagentFinished)]
        evs = await _process(sm, _sub_text("t2", "unrelated"))
        fin = [e for e in evs if isinstance(e, SubagentFinished)]
        assert [(f.tool_use_id, f.status) for f in fin] == [("t1", "failed")]

    async def test_killed_task_finishes_stopped_at_once_and_only_once(self):
        sm = _sm()
        await _process(sm, _spawn("t1"), _task_started("a1", "t1"))
        evs = await _process(sm, _task_updated("a1", "killed"),
                             _task_notification("a1", "t1", status="stopped", summary="Partial work"))
        fin = [e for e in evs if isinstance(e, SubagentFinished)]
        # Stopped at once; the late notification only fills the summary in (SA-3).
        assert [(f.status, f.summary) for f in fin] == [("stopped", "Stopped"), ("stopped", "Partial work")]
        evs = await _process(sm, _task_notification("a1", "t1", status="stopped", summary="Partial work"))
        assert not [e for e in evs if isinstance(e, SubagentFinished)]  # nothing new: no third

    async def test_sync_spawn_result_finishes_agent(self):
        sm = _sm()
        await _process(sm, _spawn("t1"), _task_started("a1", "t1", backgrounded=False))
        evs = await _process(sm, UserMessage(
            content=[ToolResultBlock(tool_use_id="t1", content="Report", is_error=False)],
            tool_use_result={"status": "completed", "agentId": "a1", "totalTokens": 50,
                             "totalToolUseCount": 3, "totalDurationMs": 700},
        ))
        assert isinstance(evs[0], ToolResult) and evs[0].tool_use_id == "t1"
        fin = [e for e in evs if isinstance(e, SubagentFinished)]
        assert fin[0].status == "done" and fin[0].summary == "Report"
        assert fin[0].usage == {"tokens": 50, "tool_uses": 3, "duration_ms": 700}

    async def test_async_launch_result_keeps_agent_running(self):
        sm = _sm()
        await _process(sm, _spawn("t1", background=True), _task_started("a1", "t1"))
        evs = await _process(sm, _launch_result("t1", "a1"))
        assert [type(e) for e in evs] == [ToolResult]
        assert sm.subagent_snapshot()[0]["status"] == "running"

    async def test_failed_spawn_without_task_started(self):
        sm = _sm()
        await _process(sm, _spawn("t1"))
        evs = await _process(sm, UserMessage(
            content=[ToolResultBlock(tool_use_id="t1", content="Agent type 'x' not found", is_error=True)],
        ))
        fin = [e for e in evs if isinstance(e, SubagentFinished)]
        assert fin and fin[0].status == "failed"

    async def test_nested_agent_links_parent(self):
        sm = _sm()
        evs = await _process(
            sm,
            _spawn("t1", agent_type="general-purpose"),
            _task_started("a1", "t1"),
            _spawn("n1", parent="t1"),                        # spawned inside t1
            _task_started("a2", "n1", spawn_depth=2),
            _sub_tool("n1", "x1", name="Glob"),
        )
        started = {e.tool_use_id: e for e in evs if isinstance(e, SubagentStarted)}
        assert started["n1"].parent_tool_use_id == "t1"
        # The nested spawn card belongs to t1's conversation; n1's own tool to n1 (flat routing).
        wrapped = [(e.tool_use_id, e.event.tool_use_id) for e in evs if isinstance(e, SubagentEvent)]
        assert wrapped == [("t1", "n1"), ("n1", "x1")]
        snap = {r["tool_use_id"]: r for r in sm.subagent_snapshot()}
        assert snap["n1"]["parent_tool_use_id"] == "t1"

    async def test_shell_task_of_a_subagent_is_not_a_subagent(self):
        sm = _sm()
        await _process(sm, _spawn("t1"), _task_started("a1", "t1"))
        evs = await _process(
            sm,
            _sub_tool("t1", "b1", name="Bash", inp={"command": "sleep 1"}),
            _task_started("bash1", "b1", task_type="local_bash", backgrounded=False,
                          owned_by_subagent=True, parent_task_id="a1"),
            _task_notification("bash1", "b1", summary="sleep 1"),
        )
        assert not [e for e in evs if isinstance(e, (SubagentStarted, SubagentFinished))]
        assert not sm._pending_notifications

    async def test_string_result_fallback_never_lands_on_the_agent_card(self):
        """PLAN §1: a subagent's plain-string tool_use_result used to be
        attached to the parent's Agent card (tool_use_id = parent_tool_use_id)."""
        sm = _sm()
        await _process(sm, _spawn("t1"))
        evs = await _process(sm, UserMessage(content="", parent_tool_use_id="t1",
                                             tool_use_result="raw stdout"))
        assert len(evs) == 1 and isinstance(evs[0], SubagentEvent)
        assert evs[0].tool_use_id == "t1"
        assert evs[0].event.tool_use_id == "" and evs[0].event.output == "raw stdout"

    async def test_string_result_fallback_unchanged_without_a_known_spawn(self):
        sm = _sm()
        evs = await _process(sm, UserMessage(content="", parent_tool_use_id="toolu_x",
                                             tool_use_result="raw stdout"))
        assert isinstance(evs[0], ToolResult) and evs[0].tool_use_id == "toolu_x"

    async def test_turn_complete_carries_origin(self):
        sm = _sm()
        (tc,) = await _process(sm, _result())
        assert tc.origin is None and tc.prompted
        (tc,) = await _process(sm, _result(origin={"kind": "task-notification", "runId": "r1"}))
        assert tc.origin == "task-notification" and not tc.prompted

    async def test_notification_turn_is_announced_out_of_turn(self):
        sm = _sm()
        await _process(sm, _spawn("t1", background=True, description="Digest logs"),
                       _task_started("a1", "t1", run_id="r1"), _launch_result("t1", "a1"))
        await _process(sm, _task_notification("a1", "t1", summary="Logs are clean.", run_id="r1"))
        assert sm._event_inbox is None  # no send() in flight
        evs = await _process(sm, _init())
        assert len(evs) == 1 and isinstance(evs[0], UnpromptedTurnStarted)
        text = evs[0].text
        assert text.startswith("<task-notification>") and text.endswith("</task-notification>")
        assert "<task-id>a1</task-id>" in text and "<tool-use-id>t1</tool-use-id>" in text
        assert "<status>completed</status>" in text
        assert '<summary>Agent "Digest logs" finished</summary>' in text
        assert "<result>Logs are clean.</result>" in text
        assert sm._status == SessionStatus.STREAMING
        await _process(sm, _result(origin={"kind": "task-notification", "runId": "r1"}))
        assert not sm._pending_notifications and sm._announced_notification is None
        # The next init (no notification pending) announces nothing.
        assert await _process(sm, _init()) == []

    async def test_foreground_task_never_queues_a_notification_turn(self):
        sm = _sm()
        await _process(sm, _spawn("t1"), _task_started("a1", "t1", backgrounded=False),
                       _task_notification("a1", "t1"))
        assert not sm._pending_notifications

    async def test_forward_subagent_text_is_on(self):
        assert _sm()._build_options().forward_subagent_text is True


class TestResumedAgent:
    """An agent that backgrounds its own command and ends its turn finishes
    (task_notification completed); when the command ends the CLI wakes it:
    a second task_started with the same task_id / tool_use_id, its inner
    messages, task_updated completed and a second notification (probe
    2026-10-12, CLI 2.1.292)."""

    async def test_second_task_started_reopens_and_finishes_again(self):
        sm = _sm()
        await _process(sm, _spawn("t1", background=True), _task_started("a1", "t1", run_id="r1"),
                       _launch_result("t1", "a1"), _sub_tool("t1", "b1", name="Bash"))
        evs = await _process(sm, _task_updated("a1", "completed"),
                             _task_notification("a1", "t1", summary="waiting", run_id="r1"))
        (fin1,) = [e for e in evs if isinstance(e, SubagentFinished)]
        assert fin1.status == "done" and fin1.summary == "waiting"
        first_start = sm._subagents.get("t1").started_at

        evs = await _process(sm, _task_started("a1", "t1", run_id="r2"))
        (again,) = [e for e in evs if isinstance(e, SubagentStarted)]
        assert again.tool_use_id == "t1" and again.started_at == first_start
        rec = sm._subagents.get("t1")
        assert rec.status == "running" and rec.ended_at is None
        assert sm.subagent_snapshot()[0]["status"] == "running"

        evs = await _process(sm, _sub_tool("t1", "r9", name="Read"), _sub_text("t1", "slept"),
                             _task_updated("a1", "completed"),
                             _task_notification("a1", "t1", summary="slept", run_id="r2"))
        assert any(isinstance(e, SubagentEvent) for e in evs)
        (fin2,) = [e for e in evs if isinstance(e, SubagentFinished)]
        assert fin2.status == "done" and fin2.summary == "slept"
        # Its real end makes the CLI start a parent turn again: the note is queued.
        assert [n["run_id"] for n in sm._pending_notifications] == ["r1", "r2"]

    async def test_task_updated_running_after_finish_reopens(self):
        sm = _sm()
        await _process(sm, _spawn("t1"), _task_started("a1", "t1"), _task_notification("a1", "t1"))
        evs = await _process(sm, _task_updated("a1", "running"))
        assert [type(e) for e in evs] == [SubagentStarted]
        # Running already: nothing more.
        assert await _process(sm, _task_updated("a1", "running")) == []

    async def test_duplicate_task_started_while_running_emits_nothing(self):
        sm = _sm()
        await _process(sm, _spawn("t1"), _task_started("a1", "t1"))
        assert await _process(sm, _task_started("a1", "t1")) == []


# ---------------------------------------------------------------------------
# send() and the out-of-turn path (manager level)
# ---------------------------------------------------------------------------

class TestSendAndOutOfTurn:
    async def test_send_ends_at_its_own_turn_and_later_events_go_out_of_turn(self):
        late = asyncio.Event()

        async def response():
            yield _init()
            yield _spawn("t1", background=True)
            yield _task_started("a1", "t1")
            yield _launch_result("t1", "a1")
            yield _result()
            await late.wait()
            yield _sub_tool("t1", "b1", name="Bash")
            yield _task_notification("a1", "t1", summary="clean")
            yield _init()
            yield AssistantMessage(content=[TextBlock(text="Agent done.")], model="m")
            yield _result(origin={"kind": "task-notification", "runId": "r1"})

        client = _make_mock_client([], response)
        with patch("manager.claude.session.ClaudeSDKClient", return_value=client):
            sm = SessionManager()
            await sm.start()
            sink: list = []
            sm.set_out_of_turn_sink(sink.append)
            events = [e async for e in sm.send("go")]
            assert isinstance(events[-1], TurnComplete) and events[-1].prompted
            assert not any(isinstance(e, SubagentEvent) for e in events)
            late.set()
            for _ in range(100):
                if any(isinstance(i.event, TurnComplete) for i in sink):
                    break
                await asyncio.sleep(0.01)
            kinds = [type(i.event).__name__ for i in sink]
            assert kinds == ["SubagentEvent", "SubagentFinished", "UnpromptedTurnStarted",
                             "TextComplete", "TurnComplete"]
            seqs = [i.seq for i in sink]
            assert seqs == sorted(seqs) and seqs[0] > max(sm._replay_buffer[0][0], 0)
            assert sm.status == SessionStatus.IDLE
            await sm.stop()

    async def test_injected_turn_inside_send_does_not_end_it(self):
        async def response():
            # The CLI runs a pending notification turn before our prompt.
            yield _init()
            yield AssistantMessage(content=[TextBlock(text="(notification reply)")], model="m")
            yield _result(origin={"kind": "task-notification", "runId": "r9"})
            yield _init()
            yield AssistantMessage(content=[TextBlock(text="answer")], model="m")
            yield _result()

        client = _make_mock_client([], response)
        with patch("manager.claude.session.ClaudeSDKClient", return_value=client):
            sm = SessionManager()
            await sm.start()
            events = [e async for e in sm.send("go")]
            tcs = [e for e in events if isinstance(e, TurnComplete)]
            assert [tc.prompted for tc in tcs] == [False, True]
            assert [e.text for e in events if isinstance(e, TextComplete)] == ["(notification reply)", "answer"]
            await sm.stop()

    async def test_events_after_turn_complete_keep_order_via_backlog(self):
        sm = _sm()
        inbox: asyncio.Queue = asyncio.Queue()
        sm._event_inbox = inbox
        sink: list = []
        sm.set_out_of_turn_sink(sink.append)
        sm._inject_event(TextComplete(text="a"))
        sm._inject_event(TurnComplete())
        sm._inject_event(SubagentEvent(tool_use_id="t1", event=TextComplete(text="late")))
        assert inbox.qsize() == 2 and sink == []      # held until send() returns
        sm._event_inbox = None
        sm._flush_post_turn_backlog()
        assert [i.event.event.text for i in sink] == ["late"]
        sm._inject_event(TextComplete(text="later"))
        assert [i.seq for i in sink] == [2, 3]

    async def test_stop_subagent_calls_stop_task(self):
        sm = _sm()
        await _process(sm, _spawn("t1"), _task_started("a1", "t1"))
        sm._client = MagicMock()
        sm._client.stop_task = AsyncMock()
        await sm.stop_subagent("t1")
        sm._client.stop_task.assert_awaited_once_with("a1")
        from manager.base_session import SubagentNotFound, SubagentNotRunning
        with pytest.raises(SubagentNotFound):
            await sm.stop_subagent("nope")
        await _process(sm, _task_updated("a1", "killed"))
        with pytest.raises(SubagentNotRunning):
            await sm.stop_subagent("t1")


# ---------------------------------------------------------------------------
# Pool: live broadcast of out-of-turn frames, seq order, watchers
# ---------------------------------------------------------------------------

def _capture_ws(store: list):
    ws = MagicMock()
    ws.client_state = WebSocketState.CONNECTED

    async def _send(data):
        store.append(orjson.loads(data))
    ws.send_bytes = _send
    return ws


class TestPoolOutOfTurn:
    async def test_background_agent_and_notification_turn_reach_subscribers_live(self):
        late = asyncio.Event()

        async def response():
            yield _init()
            yield _spawn("t1", background=True, agent_type="general-purpose", description="Digest logs")
            yield _task_started("a1", "t1", run_id="r1")
            yield _launch_result("t1", "a1")
            yield AssistantMessage(content=[TextBlock(text="Started a background agent.")], model="m")
            yield _result()
            await late.wait()
            yield _sub_tool("t1", "b1", name="Bash", inp={"command": "ls logs"})
            yield _sub_result("t1", "b1", "app.log")
            yield _task_progress("a1", "t1", activity="Running ls logs", tool="Bash")
            yield _sub_text("t1", "Logs are clean.")
            yield _task_updated("a1", "completed")
            yield _task_notification("a1", "t1", summary="Logs are clean.", run_id="r1")
            yield _init()
            yield AssistantMessage(content=[TextBlock(text="The background agent found the logs clean.")], model="m")
            yield _result(origin={"kind": "task-notification", "runId": "r1"})

        client = _make_mock_client([], response)
        with patch("manager.claude.session.ClaudeSDKClient", return_value=client), \
                patch("api.pool._session_manager_for", side_effect=lambda cfg, **kw: SessionManager(**kw)):
            pool = SessionPool()
            from manager.config import ManagerConfig
            lid = await pool.create(ManagerConfig(), local_id="L1")
            frames: list[dict] = []
            watched: list[dict] = []
            pool.subscribe(lid, _capture_ws(frames))
            pool.watch(_capture_ws(watched))
            async for _ in pool.send(lid, "go"):
                pass
            in_turn = len(frames)
            late.set()
            for _ in range(200):
                if frames[-1:] and frames[-1]["type"] == "turn_complete" and len(frames) > in_turn:
                    break
                await asyncio.sleep(0.01)
            await asyncio.sleep(0.05)

            types = [f["type"] for f in frames]
            assert types[:in_turn] == ["user_message", "status", "tool_use", "subagent_started",
                                       "tool_result", "text_complete", "turn_complete"]
            assert types[in_turn:] == [
                "subagent_event", "subagent_event", "subagent_progress", "subagent_event",
                "subagent_finished", "user_message", "status", "text_complete", "turn_complete",
            ]
            # Seq-stamped frames go out in seq order (clients drop seq <= checkpoint).
            seqs = [f["seq"] for f in frames if "seq" in f]
            assert seqs == sorted(seqs) and len(set(seqs)) == len(seqs)
            # Inner frames never carry seq/stream_id.
            inner = [f["frame"] for f in frames if f["type"] == "subagent_event"]
            assert [i["type"] for i in inner] == ["tool_use", "tool_result", "text_complete"]
            assert all("seq" not in i and "stream_id" not in i for i in inner)
            note = frames[in_turn + 5]
            assert note["source"] == "task_notification" and note["text"].startswith("<task-notification>")
            assert frames[in_turn + 6] == {"type": "status", "status": "processing"}
            fin = frames[in_turn + 4]
            assert fin["status"] == "done" and fin["summary"] == "Logs are clean."
            # The parent never saw the subagent's own tool calls.
            assert [f["tool_use_id"] for f in frames if f["type"] == "tool_use"] == ["t1"]
            # Pool status follows the CLI's turn back to idle.
            assert pool.list_sessions()[0]["status"] == "idle"
            # The harness-started turn is announced to watchers like any agent turn.
            finished = [w for w in watched if w["type"] == "agent_turn_finished"]
            assert [w["preview"] for w in finished][-1] == "The background agent found the logs clean."
            # And the snapshot used by session_started knows the agent.
            (row,) = pool.subagents_for(lid)
            assert row["status"] == "done" and row["background"] is True
            await pool.close(lid)

    async def test_send_waits_for_queued_out_of_turn_frames(self):
        pool = SessionPool()
        sm = _sm()
        sm._stream_id = "L1:1"
        from collections import deque
        pool._sessions["L1"] = sm
        pool._subscribers["L1"] = set()
        pool._locks["L1"] = asyncio.Lock()
        pool._pending_prompts["L1"] = deque()
        pool._pending_locks["L1"] = asyncio.Lock()
        pool._attach_out_of_turn("L1", sm)
        gate = asyncio.Event()
        frames: list[dict] = []
        ws = _capture_ws(frames)
        orig = ws.send_bytes

        async def slow(data):
            await gate.wait()
            await orig(data)
        ws.send_bytes = slow
        pool.subscribe("L1", ws)
        sm._inject_event(SubagentEvent(tool_use_id="t1", event=TextComplete(text="bg")))
        waiter = asyncio.create_task(pool._await_out_of_turn_idle("L1"))
        await asyncio.sleep(0.02)
        assert not waiter.done()
        gate.set()
        await asyncio.wait_for(waiter, 1)
        assert frames[0]["type"] == "subagent_event" and frames[0]["seq"] == 0
        pool._detach_out_of_turn("L1", sm)


# ---------------------------------------------------------------------------
# Serializer shapes == the shared protocol fixtures (apps/protocol-fixtures)
# ---------------------------------------------------------------------------

def _inner_event(frame: dict):
    t = frame["type"]
    if t == "tool_use":
        return ToolUse(tool_use_id=frame["tool_use_id"], tool_name=frame["tool_name"], tool_input=frame["tool_input"])
    if t == "tool_result":
        return ToolResult(tool_use_id=frame["tool_use_id"], output=frame["output"], is_error=frame["is_error"])
    if t == "text_complete":
        return TextComplete(text=frame["text"])
    raise AssertionError(f"unexpected inner frame {t}")


def _event_for(frame: dict):
    t = frame["type"]
    if t == "subagent_started":
        return SubagentStarted(**{k: frame[k] for k in (
            "tool_use_id", "parent_tool_use_id", "agent_type", "description", "background", "started_at")})
    if t == "subagent_progress":
        return SubagentProgress(tool_use_id=frame["tool_use_id"], activity=frame["activity"], usage=frame["usage"])
    if t == "subagent_finished":
        return SubagentFinished(tool_use_id=frame["tool_use_id"], status=frame["status"],
                                summary=frame["summary"], usage=frame["usage"], ended_at=frame["ended_at"])
    if t == "subagent_event":
        return SubagentEvent(tool_use_id=frame["tool_use_id"], event=_inner_event(frame["frame"]))
    if t == "user_message" and frame.get("source") == "task_notification":
        return UnpromptedTurnStarted(text=frame["text"], source="task_notification")
    return None


_FIXTURE_FILES = sorted(FIXTURES.glob("subagent_*.json"))


@pytest.mark.skipif(not _FIXTURE_FILES, reason="protocol fixtures not present")
@pytest.mark.parametrize("path", _FIXTURE_FILES, ids=lambda p: p.stem)
def test_serializer_matches_fixture_frames(path: Path):
    data = json.loads(path.read_text())
    checked = 0
    for frame in data["events"]:
        ev = _event_for(frame)
        if ev is None:
            continue
        expected = {k: v for k, v in frame.items() if k not in ("seq", "stream_id")}
        assert serialize_event(ev) == expected, frame["type"]
        checked += 1
    if not checked:
        pytest.skip("fixture carries no subagent frames (REST / history only)")


# ---------------------------------------------------------------------------
# Registry / merge
# ---------------------------------------------------------------------------

class TestRegistry:
    def test_started_and_finished_once(self):
        reg = SubagentRegistry()
        reg.spawn("t1", parent_tool_use_id=None, agent_type="Explore", description="d", background=False)
        assert reg.start("t1", harness_id="a1") is not None
        assert reg.start("t1", harness_id="a1") is None
        assert reg.by_harness_id("a1").tool_use_id == "t1"
        assert reg.finish("t1", status="done", summary="ok").status == "done"
        assert reg.finish("t1", status="failed") is None
        assert reg.progress("t1", activity="x", usage=None) is None
        # Other harnesses never reopen by accident.
        assert reg.start("t1", harness_id="a1") is None
        assert reg.get("t1").status == "done"

    def test_reopen_after_finish_is_a_new_run(self):
        reg = SubagentRegistry()
        first = reg.start("t1", harness_id="a1")
        assert reg.start("t1", reopen=True) is None  # still running: nothing new
        reg.finish("t1", status="done", summary="half")
        again = reg.start("t1", reopen=True)
        assert again is not None and again.started_at == first.started_at
        assert reg.get("t1").status == "running" and reg.get("t1").ended_at is None
        assert reg.progress("t1", activity="Read", usage=None) is not None
        fin = reg.finish("t1", status="done", summary="all")
        assert fin is not None and fin.summary == "all"

    def test_stopped_without_fresh_duration_gets_the_wall_clock_span(self):
        reg = SubagentRegistry()
        reg.start("t1")
        reg.get("t1").started_at = "2026-10-12T12:00:00Z"
        reg.progress("t1", activity="Bash", usage={"tokens": 10, "tool_uses": 1, "duration_ms": 2000})
        with patch("manager.subagents.now_iso", return_value="2026-10-12T12:01:07Z"):
            fin = reg.finish("t1", status="stopped", summary="Stopped")
        assert fin.usage == {"tokens": 10, "tool_uses": 1, "duration_ms": 67000}
        # A fresh duration (the notification's) is kept as reported; done keeps its own.
        reg.start("t2")
        fin = reg.finish("t2", status="failed", usage={"tokens": 1, "tool_uses": 1, "duration_ms": 5})
        assert fin.usage["duration_ms"] == 5
        reg.start("t3")
        reg.progress("t3", activity="x", usage={"tokens": 1, "tool_uses": 1, "duration_ms": 7})
        assert reg.finish("t3", status="done").usage["duration_ms"] == 7

    def test_merge_live_wins_and_keeps_disk_order(self):
        disk = [{"tool_use_id": "a", "status": "stopped", "agent_type": "Explore", "description": "old"},
                {"tool_use_id": "b", "status": "done", "agent_type": "x", "description": "y"}]
        live = [{"tool_use_id": "c", "status": "running"},
                {"tool_use_id": "a", "status": "running", "activity": "Grep", "description": None}]
        rows = merge_summaries(disk, live)
        assert [r["tool_use_id"] for r in rows] == ["a", "b", "c"]
        assert rows[0]["status"] == "running" and rows[0]["description"] == "old"
        assert rows[0]["activity"] == "Grep"
        assert set(rows[2]) == {"tool_use_id", "parent_tool_use_id", "agent_type", "description",
                                "background", "status", "activity", "usage", "summary",
                                "started_at", "ended_at"}

    def test_status_mapping(self):
        assert [wire_status(s) for s in ("completed", "failed", "stopped", "killed", "running", None)] == [
            "done", "failed", "stopped", "stopped", None, None]


# ---------------------------------------------------------------------------
# Orchestrator runner: subagent events are progress, not ring entries
# ---------------------------------------------------------------------------

def test_runner_buckets_subagent_events_apart_from_the_ring():
    from orchestrator.runner import BackgroundAgentRunner, NotificationQueue, _TurnRecord

    runner = BackgroundAgentRunner(MagicMock(), MagicMock(), NotificationQueue(), peek_buffer_size=3)
    rec = _TurnRecord(turn_id="T", session_id="S", session_title=None, origin_tool_use_id=None,
                      started_at=0.0, started_at_wall=0.0, last_progress_at=0.0)
    runner._turns["T"] = rec
    runner._latest_by_session["S"] = "T"
    runner._note_subagent(rec, SubagentStarted("t1", None, "Explore", "Find", False, "now"))
    for i in range(10):
        runner._note_subagent(rec, SubagentEvent("t1", ToolUse(f"g{i}", "Grep", {})))
    runner._note_subagent(rec, SubagentFinished("t1", "done", "found"))
    assert rec.last_progress_at > 0 and len(rec.events) == 0
    snap = runner.peek("S")
    assert snap["subagents"] == [{"tool_use_id": "t1", "agent_type": "Explore", "description": "Find",
                                  "status": "done", "activity": "Grep", "summary": "found"}]
    # The CLI resumed it: running again until its new finish.
    runner._note_subagent(rec, SubagentStarted("t1", None, "Explore", "Find", False, "now"))
    assert runner.peek("S")["subagents"][0]["status"] == "running"
    seq = rec.last_finish_seq
    runner._note_subagent(rec, SubagentFinished("t1", "done", "slept"))
    assert runner.peek("S")["subagents"][0]["status"] == "done"
    assert rec.last_finish_seq > seq  # its second report turn is waited for too


# ---------------------------------------------------------------------------
# Review fixes (2026-10-12 follow-up)
# ---------------------------------------------------------------------------

def _requesting():
    return SystemMessage(subtype="status", data={"status": "requesting"})


class TestNotificationMatching:
    """Finding 1: notes folded into a turn are dropped; announcements follow
    the CLI's turns, never re-queued at the head."""

    async def _two_agents(self, sm):
        await _process(sm, _spawn("tA", background=True, description="Agent A"),
                       _task_started("aA", "tA", run_id="rA"), _launch_result("tA", "aA"),
                       _spawn("tB", background=True, description="Agent B"),
                       _task_started("aB", "tB", run_id="rB"), _launch_result("tB", "aB"))

    async def test_note_folded_into_the_prompted_turn_is_never_announced(self):
        sm = _sm()
        sm._event_inbox = asyncio.Queue()           # a send() of ours is running
        await _process(sm, _init(), _requesting())
        await self._two_agents(sm)
        # A finishes while the main agent waits on a tool; the next request includes it.
        await _process(sm, _task_notification("aA", "tA", run_id="rA"), _requesting())
        assert not sm._pending_notifications
        await _process(sm, _result())
        sm._event_inbox = None
        # B finishes while idle: its own turn follows and is announced as B.
        await _process(sm, _task_notification("aB", "tB", run_id="rB"))
        (ev,) = await _process(sm, _init())
        assert isinstance(ev, UnpromptedTurnStarted) and "<tool-use-id>tB</tool-use-id>" in ev.text
        await _process(sm, _result(origin={"kind": "task-notification", "runId": "rB"}))
        assert not sm._pending_notifications and not sm.awaits_unprompted_turn()

    async def test_note_without_a_later_request_keeps_its_own_turn(self):
        """e2e 2026-10-12: agents that finish while the final request is
        already in flight get turns of their own after the prompted turn."""
        sm = _sm()
        sm._event_inbox = asyncio.Queue()
        await _process(sm, _init(), _requesting())
        await self._two_agents(sm)
        await _process(sm, _task_notification("aA", "tA", run_id="rA"), _result())
        sm._event_inbox = None
        (ev,) = await _process(sm, _init())
        assert "<tool-use-id>tA</tool-use-id>" in ev.text

    async def test_mismatched_run_id_does_not_requeue_the_announced_note(self):
        sm = _sm()
        await self._two_agents(sm)
        await _process(sm, _task_notification("aA", "tA", run_id="rA"),
                       _task_notification("aB", "tB", run_id="rB"))
        (ev,) = await _process(sm, _init())
        assert "tA" in ev.text
        await _process(sm, _result(origin={"kind": "task-notification", "runId": "rB"}))
        assert not sm._pending_notifications   # A not put back at the head
        assert await _process(sm, _init()) == []

    async def test_notes_without_a_turn_are_dropped_after_a_while(self, monkeypatch):
        import manager.claude.session as mod
        monkeypatch.setattr(mod, "_NOTE_TURN_WAIT_S", 0.05)
        sm = _sm()
        await self._two_agents(sm)
        await _process(sm, _task_notification("aA", "tA", run_id="rA"))
        assert sm.awaits_unprompted_turn()
        await asyncio.sleep(0.15)
        assert not sm._pending_notifications and not sm.awaits_unprompted_turn()

    async def test_a_turn_starting_in_time_keeps_the_note(self, monkeypatch):
        import manager.claude.session as mod
        monkeypatch.setattr(mod, "_NOTE_TURN_WAIT_S", 0.05)
        sm = _sm()
        await self._two_agents(sm)
        await _process(sm, _task_notification("aA", "tA", run_id="rA"))
        (ev,) = await _process(sm, _init())
        await asyncio.sleep(0.15)
        assert sm._announced_notification is not None and isinstance(ev, UnpromptedTurnStarted)


class TestLateNotificationAmends:
    """Finding 3: a finish flushed without a summary is completed by a later
    task_notification (a second subagent_finished, spec SA-3)."""

    async def test_flushed_finish_uses_last_text_then_notification_amends(self):
        sm = _sm()
        await _process(sm, _spawn("t1", background=True), _task_started("a1", "t1"),
                       _sub_text("t1", "Draft answer"), _task_updated("a1", "completed"))
        evs = await _process(sm, _sub_text("t2", "unrelated"))
        (first,) = [e for e in evs if isinstance(e, SubagentFinished)]
        assert first.status == "done" and first.summary == "Draft answer"
        evs = await _process(sm, _task_notification("a1", "t1", summary="Final answer"))
        (second,) = [e for e in evs if isinstance(e, SubagentFinished)]
        assert second.summary == "Final answer"
        assert second.usage == {"tokens": 1200, "tool_uses": 2, "duration_ms": 3000}
        assert sm.subagent_snapshot()[0]["summary"] == "Final answer"


class TestStopUsesTaskId:
    """Finding B: stop_task takes task_started's task_id, even if the spawn
    result names another agent id (identical on CLI 2.1.292)."""

    async def test_spawn_result_does_not_relink_over_task_id(self):
        sm = _sm()
        await _process(sm, _spawn("t1", background=True), _task_started("task-1", "t1"),
                       _launch_result("t1", "agent-other"))
        sm._client = MagicMock()
        sm._client.stop_task = AsyncMock()
        await sm.stop_subagent("t1")
        sm._client.stop_task.assert_awaited_once_with("task-1")

    async def test_old_sdk_without_stop_task_is_unsupported(self):
        sm = _sm()
        await _process(sm, _spawn("t1"), _task_started("task-1", "t1"))
        sm._client = MagicMock(spec=["query"])
        with pytest.raises(NotImplementedError):
            await sm.stop_subagent("t1")


class TestFoldedPromptFallback:
    """Finding A (not reproduced on CLI 2.1.292, guarded anyway): when the CLI
    starts no turn for our prompt after a turn of its own, send() ends."""

    async def test_send_ends_when_no_own_turn_follows(self, monkeypatch):
        import manager.claude.session as mod
        monkeypatch.setattr(mod, "_UNPROMPTED_GRACE_S", 0.3)

        async def response():
            yield _init()
            yield AssistantMessage(content=[TextBlock(text="(notification reply + answer)")], model="m")
            yield _result(origin={"kind": "task-notification", "runId": "r9"})
            # …and nothing more: the prompt was folded into that turn.

        client = _make_mock_client([], response)
        with patch("manager.claude.session.ClaudeSDKClient", return_value=client):
            sm = SessionManager()
            await sm.start()
            events = await asyncio.wait_for(_collect(sm.send("go")), 5)
            tcs = [e for e in events if isinstance(e, TurnComplete)]
            assert [tc.prompted for tc in tcs] == [False, True]
            # The CLI's own TurnComplete inside our send() is not in the replay ring.
            assert not [e for _, e in sm._replay_buffer if isinstance(e, TurnComplete)]
            assert sm.status == SessionStatus.IDLE
            await sm.stop()


async def _collect(agen):
    return [e async for e in agen]


class TestPoolHarnessTurns:
    async def test_prompt_waits_for_a_running_notification_turn(self):
        """Finding 8: the user's prompt is echoed only after the CLI's own turn
        ended; that turn's start/finish reach watchers balanced."""
        release = asyncio.Event()

        async def response():
            yield _init()
            yield _spawn("t1", background=True)
            yield _task_started("a1", "t1", run_id="r1")
            yield _launch_result("t1", "a1")
            yield _result()
            yield _task_notification("a1", "t1", summary="done!", run_id="r1")
            yield _init()                                   # the CLI's own turn starts
            yield AssistantMessage(content=[TextBlock(text="Agent finished.")], model="m")
            await release.wait()
            yield _result(origin={"kind": "task-notification", "runId": "r1"})

        async def response2():
            yield _init()
            yield AssistantMessage(content=[TextBlock(text="second answer")], model="m")
            yield _result()

        calls = {"n": 0}

        def factory():
            calls["n"] += 1
            return response() if calls["n"] == 1 else response2()
        client = _make_mock_client([], factory)

        with patch("manager.claude.session.ClaudeSDKClient", return_value=client), \
                patch("api.pool._session_manager_for", side_effect=lambda cfg, **kw: SessionManager(**kw)):
            pool = SessionPool()
            from manager.config import ManagerConfig
            lid = await pool.create(ManagerConfig(), local_id="L2")
            frames: list[dict] = []
            watched: list[dict] = []
            pool.subscribe(lid, _capture_ws(frames))
            pool.watch(_capture_ws(watched))
            async for _ in pool.send(lid, "first"):
                pass
            for _ in range(100):
                if any(f.get("source") == "task_notification" for f in frames):
                    break
                await asyncio.sleep(0.01)
            second = asyncio.create_task(_drain(pool.send(lid, "second")))
            await asyncio.sleep(0.3)
            assert not any(f.get("text") == "second" for f in frames)   # held back
            release.set()
            await asyncio.wait_for(second, 5)
            types = [(f["type"], f.get("text", "")[:12]) for f in frames]
            i_tc = max(i for i, f in enumerate(frames[:-3]) if f["type"] == "turn_complete")
            i_user = next(i for i, f in enumerate(frames) if f.get("type") == "user_message" and f.get("text") == "second")
            assert i_tc < i_user, types
            await asyncio.sleep(0.05)
            starts = [w for w in watched if w["type"] == "agent_turn_started"]
            ends = [w for w in watched if w["type"] == "agent_turn_finished"]
            assert len(starts) == len(ends) == 3
            await pool.close(lid)

    async def test_harness_turn_end_inside_send_is_not_broadcast(self):
        from tests.test_pool_turn import _install, _stub_session_manager
        pool = SessionPool()
        sm = _stub_session_manager([])

        async def _send(text):
            pool._oot_turns[sm.local_id] = {"text": None}   # announced out of turn just before
            yield TextComplete(text="notification reply")
            yield TurnComplete(session_id="sdk-test", origin="task-notification")
            yield TextComplete(text="answer")
            yield TurnComplete(session_id="sdk-test")
        sm.send = _send
        sm.stream_id = None
        sm.last_yielded_seq = None
        _install(pool, sm)
        frames: list[dict] = []
        watched: list[dict] = []
        pool._subscribers[sm.local_id].add(_capture_ws(frames))
        pool.watch(_capture_ws(watched))
        async for _ in pool.send(sm.local_id, "go"):
            pass
        await asyncio.sleep(0.05)
        assert [f["type"] for f in frames].count("turn_complete") == 1
        assert sm.local_id not in pool._oot_turns
        finished = [w for w in watched if w["type"] == "agent_turn_finished"]
        assert [w["preview"] for w in finished] == ["notification reply", "answer"]

    async def test_stop_during_a_harness_turn_interrupts_the_cli(self):
        """Finding 2: no turn task of ours, but the CLI runs its own turn."""
        from tests.test_pool_turn import _install, _stub_session_manager
        pool = SessionPool()
        sm = _stub_session_manager([])
        sm.interrupt = AsyncMock()
        _install(pool, sm)
        assert await pool.cancel_turn(sm.local_id) is False     # idle: nothing to stop
        sm.status = SessionStatus.STREAMING
        assert await pool.cancel_turn(sm.local_id) is True
        sm.interrupt.assert_awaited_once()
        sm.status = SessionStatus.IDLE
        pool._oot_turns[sm.local_id] = {"text": None}
        assert await pool.cancel_turn(sm.local_id) is True
        assert pool._oot_turns[sm.local_id]["interrupted"] is True


async def _drain(agen):
    async for _ in agen:
        pass

