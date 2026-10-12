"""The orchestrator runner follows a delegated turn's background subagents
end to end: real :class:`SessionPool` + Claude :class:`SessionManager` (SDK
client mocked in the CLI 2.1.292 shapes, see ``test_subagents.py``) +
:class:`BackgroundAgentRunner`.

With CLI 2.1.292 every Agent call is backgrounded: the delegated prompt's
turn ends right after the launch and each finished agent makes the CLI start
a ``<task-notification>`` turn, which travels on the pool's out-of-turn path.
"""

from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

from claude_agent_sdk import AssistantMessage, TextBlock

from api.pool import SessionPool
from manager.claude.session import SessionManager
from manager.config import ManagerConfig
from manager.types import TurnComplete, UnpromptedTurnStarted
from orchestrator.runner import BackgroundAgentRunner, NotificationQueue
from tests.test_session import _make_mock_client
from tests.test_subagents import (
    _capture_ws,
    _init,
    _launch_result,
    _result,
    _spawn,
    _sub_tool,
    _task_notification,
    _task_started,
)


def _store() -> MagicMock:
    store = MagicMock()
    store.get_session_info = MagicMock(return_value=None)
    return store


async def _wait_for(pred, timeout: float = 3.0) -> None:
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while not pred():
        assert loop.time() < deadline, "condition not met in time"
        await asyncio.sleep(0.01)


async def test_delegated_turn_with_two_background_agents_notifies_once_with_the_final_text():
    first, second = asyncio.Event(), asyncio.Event()

    async def response():
        yield _init()
        yield _spawn("t1", background=True, description="Scan backend")
        yield _task_started("a1", "t1", run_id="r1")
        yield _launch_result("t1", "a1")
        yield _spawn("t2", background=True, description="Scan frontend")
        yield _task_started("a2", "t2", run_id="r2")
        yield _launch_result("t2", "a2")
        yield AssistantMessage(content=[TextBlock(text="I started 2 agents.")], model="m")
        yield _result(text="I started 2 agents.")
        await first.wait()
        yield _sub_tool("t1", "b1", name="Grep")
        yield _task_notification("a1", "t1", summary="Backend is clean.", run_id="r1")
        yield _init()
        yield AssistantMessage(content=[TextBlock(text="Backend done; frontend still running.")], model="m")
        yield _result(origin={"kind": "task-notification", "runId": "r1"})
        await second.wait()
        yield _task_notification("a2", "t2", summary="Frontend is clean.", run_id="r2")
        yield _init()
        yield AssistantMessage(content=[TextBlock(text="Both scans are clean.")], model="m")
        yield _result(origin={"kind": "task-notification", "runId": "r2"})

    client = _make_mock_client([], response)
    with patch("manager.claude.session.ClaudeSDKClient", return_value=client), \
            patch("api.pool._session_manager_for", side_effect=lambda cfg, **kw: SessionManager(**kw)):
        pool = SessionPool()
        lid = await pool.create(ManagerConfig(), local_id="L1")
        watched: list[dict] = []
        pool.watch(_capture_ws(watched))
        queue = NotificationQueue()
        runner = BackgroundAgentRunner(pool, _store(), queue, idle_timeout=10.0, settle_grace=5.0)

        handle = await runner.spawn(lid, "audit the repo")
        record = runner._turns[handle.turn_id]
        await _wait_for(lambda: record.phase == "subagents")
        assert runner.peek(lid)["waiting_for_subagents"] == 2
        assert queue.drain() == []

        first.set()
        await _wait_for(lambda: record.notification_turns == 1)
        await asyncio.sleep(0.1)
        assert not record.finished and queue.drain() == []
        assert runner.peek(lid)["waiting_for_subagents"] == 1

        second.set()
        await asyncio.wait_for(record.task, 3.0)  # type: ignore[arg-type]
        [n] = queue.drain()
        assert n.status == "succeeded"
        assert n.preview == "Both scans are clean."
        assert n.subagents == 2 and record.notification_turns == 2
        assert pool._event_taps == {}

        # Device notifications: one per agent turn (the prompt + 2 reports), never doubled.
        await asyncio.sleep(0.05)
        finished = [w for w in watched if w["type"] == "agent_turn_finished"]
        assert [w["status"] for w in finished] == ["ok", "ok", "ok"]
        assert [w["preview"] for w in finished] == [
            "I started 2 agents.", "Backend done; frontend still running.", "Both scans are clean.",
        ]
        await pool.close(lid)


async def test_runner_interrupt_of_a_notification_turn_is_announced_as_interrupted():
    """The pool announces a harness-started turn stopped via interrupt() as
    ``interrupted`` (no device notification), like a prompted turn."""
    pool = SessionPool()
    sm = MagicMock()
    sm.interrupt = AsyncMock()
    sm.sdk_session_id = "sdk-1"
    sm.provider_name = "claude"
    pool._sessions["L1"] = sm
    pool._subscribers["L1"] = set()
    announced: list[dict] = []
    pool.announce_turn_finished = lambda sid, **kw: announced.append(kw)  # type: ignore[method-assign]

    seen: list[tuple[str, bool]] = []
    untap = pool.tap_events("L1", lambda ev, oot: seen.append((type(ev).__name__, oot)))
    await pool._broadcast_out_of_turn("L1", UnpromptedTurnStarted(text="<task-notification/>"))
    await pool.interrupt("L1")
    await pool._broadcast_out_of_turn("L1", TurnComplete(is_error=False, origin="task-notification"))
    assert announced[-1]["status"] == "interrupted"
    assert seen == [("UnpromptedTurnStarted", True), ("TurnComplete", True)]

    untap()
    assert pool._event_taps == {}
    await pool._broadcast_out_of_turn("L1", UnpromptedTurnStarted(text="<task-notification/>"))
    await pool._broadcast_out_of_turn("L1", TurnComplete(is_error=False, origin="task-notification"))
    assert announced[-1]["status"] == "ok"  # a later turn, not interrupted
    assert len(seen) == 2


async def test_a_failing_tap_never_breaks_the_broadcast():
    pool = SessionPool()
    pool._sessions["L1"] = MagicMock(sdk_session_id="sdk-1", provider_name="claude")
    pool._subscribers["L1"] = set()

    def boom(ev, oot):
        raise RuntimeError("observer bug")

    pool.tap_events("L1", boom)
    seen: list[str] = []
    pool.tap_events("L1", lambda ev, oot: seen.append(type(ev).__name__))
    await pool._broadcast_out_of_turn("L1", TurnComplete())
    assert seen == ["TurnComplete"]
