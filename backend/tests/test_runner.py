"""Tests for orchestrator.runner — BackgroundAgentRunner / NotificationQueue."""

from __future__ import annotations

import asyncio
import time
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest

from manager.types import (
    PermissionRequest,
    PermissionResolved,
    SessionInfo,
    SubagentEvent,
    SubagentFinished,
    SubagentProgress,
    SubagentStarted,
    TextComplete,
    TextDelta,
    ToolResult,
    ToolUse,
    TurnComplete,
    UnpromptedTurnStarted,
)
from orchestrator.runner import (
    AgentTurnHandle,
    BackgroundAgentRunner,
    Notification,
    NotificationQueue,
)


# ---------------------------------------------------------------------------
# FakePool — minimal shim with the surface the runner uses.
# ---------------------------------------------------------------------------


class FakePool:
    """A SessionPool double that lets each session's send() be scripted.

    ``script`` maps session_id → list-of-events to yield (or a callable that
    returns such a list / async generator).  ``send()`` is an async generator
    so the runner's ``async for`` works as in production.
    """

    def __init__(self) -> None:
        self.sessions: dict[str, MagicMock] = {}
        self.scripts: dict[str, Any] = {}
        self.interrupts: list[str] = []
        self._send_started: dict[str, asyncio.Event] = {}
        self._block_until: dict[str, asyncio.Event] = {}
        self.taps: dict[str, list[Any]] = {}

    def add_session(self, sid: str, sdk_id: str | None = "sdk-" + "x") -> None:
        sm = MagicMock()
        sm.sdk_session_id = sdk_id
        sm.pending_permission_ids = MagicMock(return_value=[])
        self.sessions[sid] = sm
        self._send_started[sid] = asyncio.Event()
        self._block_until[sid] = asyncio.Event()
        self._block_until[sid].set()  # unblocked by default

    def has(self, sid: str) -> bool:
        return sid in self.sessions

    async def ensure_live(self, sid: str) -> bool:
        return sid in self.sessions

    def get(self, sid: str) -> Any:
        return self.sessions.get(sid)

    async def interrupt(self, sid: str) -> None:
        self.interrupts.append(sid)
        # Free any blocked send() so the cancel/timeout path can finish.
        if sid in self._block_until:
            self._block_until[sid].set()

    async def send(self, sid: str, message: str, *, source_ws=None):
        self._send_started[sid].set()
        await self._block_until[sid].wait()
        events = self.scripts.get(sid, [])
        for evt in events:
            self.emit(sid, evt, out_of_turn=False)  # as pool.send: taps, then yield
            yield evt

    # Event taps (pool.tap_events) and the out-of-turn path.
    def tap_events(self, sid: str, cb: Any):
        self.taps.setdefault(sid, []).append(cb)
        return lambda: self.taps[sid].remove(cb)

    def emit(self, sid: str, event: Any, *, out_of_turn: bool = True) -> None:
        for cb in list(self.taps.get(sid, [])):
            cb(event, out_of_turn)

    async def wait_out_of_turn_idle(self, sid: str, timeout: float = 15.0) -> None:
        return None

    def block_send(self, sid: str) -> None:
        self._block_until[sid].clear()

    def unblock_send(self, sid: str) -> None:
        self._block_until[sid].set()


class FakeStore:
    def __init__(self, titles: dict[str, str] | None = None) -> None:
        self.titles = titles or {}

    def get_session_info(self, sdk_id: str) -> SessionInfo | None:
        title = self.titles.get(sdk_id)
        if title is None:
            return None
        from datetime import datetime, timezone
        now = datetime.now(timezone.utc)
        return SessionInfo(
            session_id=sdk_id,
            started_at=now,
            last_activity=now,
            title=title,
            message_count=0,
        )


@pytest.fixture
def queue() -> NotificationQueue:
    return NotificationQueue()


@pytest.fixture
def pool() -> FakePool:
    return FakePool()


@pytest.fixture
def store() -> FakeStore:
    return FakeStore()


@pytest.fixture
def runner(pool: FakePool, store: FakeStore, queue: NotificationQueue) -> BackgroundAgentRunner:
    return BackgroundAgentRunner(pool, store, queue, idle_timeout=2.0)


# ---------------------------------------------------------------------------
# spawn() returns immediately
# ---------------------------------------------------------------------------


async def test_spawn_returns_immediately_with_turn_id(
    runner: BackgroundAgentRunner, pool: FakePool, queue: NotificationQueue
) -> None:
    pool.add_session("s1")
    pool.block_send("s1")  # send() will block; spawn must NOT wait on it
    pool.scripts["s1"] = [TurnComplete(cost=0.0, num_turns=1)]

    t0 = time.monotonic()
    handle = await runner.spawn("s1", "do work")
    elapsed = time.monotonic() - t0

    assert isinstance(handle, AgentTurnHandle)
    assert handle.session_id == "s1"
    assert handle.turn_id  # non-empty
    assert handle.status == "running"
    assert elapsed < 0.5, f"spawn took {elapsed:.3f}s — must be near-instant"

    # Cleanup so the test loop can shut down cleanly
    await runner.cancel_all()


async def test_spawn_unknown_session_raises(runner: BackgroundAgentRunner) -> None:
    with pytest.raises(ValueError, match="No active session"):
        await runner.spawn("missing", "hi")


# ---------------------------------------------------------------------------
# Successful completion → succeeded notification
# ---------------------------------------------------------------------------


async def test_completed_turn_pushes_notification(
    runner: BackgroundAgentRunner, pool: FakePool, queue: NotificationQueue
) -> None:
    pool.add_session("s1")
    pool.scripts["s1"] = [
        TextComplete(text="hello"),
        TurnComplete(cost=0.0042, num_turns=1),
    ]

    handle = await runner.spawn("s1", "go")
    # Wait for the driver task to complete
    record_task = runner._turns[handle.turn_id].task
    assert record_task is not None
    await record_task

    pending = queue.drain()
    assert len(pending) == 1
    n = pending[0]
    assert isinstance(n, Notification)
    assert n.turn_id == handle.turn_id
    assert n.session_id == "s1"
    assert n.status == "succeeded"
    assert n.cost == pytest.approx(0.0042)
    assert n.turns == 1
    assert n.error is None
    assert n.duration_seconds >= 0.0


async def test_failed_turn_pushes_failure_notification(
    runner: BackgroundAgentRunner, pool: FakePool, queue: NotificationQueue
) -> None:
    pool.add_session("s1")

    async def boom_send(*args, **kwargs):
        # Need to make this an async generator that raises.
        if False:
            yield  # pragma: no cover
        raise RuntimeError("synthetic failure")

    pool.send = boom_send

    handle = await runner.spawn("s1", "go")
    await runner._turns[handle.turn_id].task  # type: ignore[arg-type]

    pending = queue.drain()
    assert len(pending) == 1
    assert pending[0].status == "failed"
    assert pending[0].error is not None
    assert "synthetic failure" in pending[0].error


# ---------------------------------------------------------------------------
# Timeout / cancellation
# ---------------------------------------------------------------------------


async def test_timeout_pushes_timeout_notification(
    pool: FakePool, store: FakeStore, queue: NotificationQueue
) -> None:
    pool.add_session("s1")
    pool.block_send("s1")  # never unblock except via interrupt
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=0.2)

    handle = await runner.spawn("s1", "go")
    # Driver should hit its 0.2s timeout
    await runner._turns[handle.turn_id].task  # type: ignore[arg-type]

    pending = queue.drain()
    assert len(pending) == 1
    assert pending[0].status == "timeout"
    assert pending[0].error and "no progress for 0.2s" in pending[0].error
    assert "s1" in pool.interrupts


async def test_cancel_all_finalises_every_turn(
    runner: BackgroundAgentRunner, pool: FakePool, queue: NotificationQueue
) -> None:
    """cancel_all() must produce exactly one notification per in-flight turn,
    interrupt every distinct session, and leave no in-flight handles behind.

    Whether the final status is 'cancelled' or 'succeeded' depends on whether
    the pool's generator exited via cancellation or after a graceful interrupt
    yielded TurnComplete — both are acceptable end states.  What's NOT
    acceptable is a leaked task or a missing notification.
    """
    for sid in ("a", "b", "c"):
        pool.add_session(sid)
        pool.block_send(sid)

    await runner.spawn("a", "go")
    await runner.spawn("b", "go")
    await runner.spawn("c", "go")

    await runner.cancel_all()

    pending = queue.drain()
    assert len(pending) == 3
    assert {n.session_id for n in pending} == {"a", "b", "c"}
    # Each notification has a terminal status (not 'running')
    assert all(n.status in ("succeeded", "failed", "cancelled", "timeout") for n in pending)
    # Each session got an interrupt
    assert set(pool.interrupts) >= {"a", "b", "c"}
    # No more in-flight handles
    assert runner.list_in_flight() == []


async def test_cancel_propagates_when_task_blocks_through_interrupt(
    runner: BackgroundAgentRunner, store: FakeStore, queue: NotificationQueue
) -> None:
    """Verify the 'cancelled' status path specifically: a pool whose
    interrupt() does NOT free the generator forces the runner to fall back
    to task.cancel() and produce a 'cancelled' notification."""
    class StubbornPool:
        def __init__(self) -> None:
            self.sessions = {"a": MagicMock(sdk_session_id="sdk-a", pending_permission_ids=lambda: [])}
            self.interrupts: list[str] = []
        def has(self, sid): return sid in self.sessions
        async def ensure_live(self, sid): return sid in self.sessions
        def get(self, sid): return self.sessions.get(sid)
        async def interrupt(self, sid): self.interrupts.append(sid)
        async def send(self, sid, message, *, source_ws=None):
            # Pure block: never yields, never exits except by cancellation
            await asyncio.Event().wait()
            yield  # pragma: no cover

    pool = StubbornPool()
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=10.0)  # type: ignore[arg-type]

    handle = await runner.spawn("a", "go")
    # Yield so the driver actually enters pool.send()
    await asyncio.sleep(0.05)
    await runner.cancel_all()

    pending = queue.drain()
    assert len(pending) == 1
    assert pending[0].status == "cancelled"
    assert pool.interrupts == ["a"]


# ---------------------------------------------------------------------------
# Concurrency
# ---------------------------------------------------------------------------


async def test_two_concurrent_spawns_different_sessions_run_parallel(
    runner: BackgroundAgentRunner, pool: FakePool, queue: NotificationQueue
) -> None:
    pool.add_session("s1")
    pool.add_session("s2")
    pool.block_send("s1")
    pool.block_send("s2")

    h1 = await runner.spawn("s1", "go")
    h2 = await runner.spawn("s2", "go")

    # Both driver tasks should have started consuming pool.send (and thus
    # be parked on the block_until event for their session) within a beat.
    await asyncio.wait_for(pool._send_started["s1"].wait(), timeout=1.0)
    await asyncio.wait_for(pool._send_started["s2"].wait(), timeout=1.0)

    # Snapshot reflects both as in-flight
    in_flight = runner.list_in_flight()
    assert {h.turn_id for h in in_flight} == {h1.turn_id, h2.turn_id}

    # Cleanup
    await runner.cancel_all()


# ---------------------------------------------------------------------------
# peek
# ---------------------------------------------------------------------------


async def test_peek_returns_buffered_text_and_tool_events(
    runner: BackgroundAgentRunner, pool: FakePool, queue: NotificationQueue
) -> None:
    pool.add_session("s1")
    pool.scripts["s1"] = [
        TextDelta(text="hel"),
        TextDelta(text="lo"),
        TextComplete(text="hello"),
        ToolUse(tool_use_id="tu_1", tool_name="Read", tool_input={"path": "x"}),
        ToolResult(tool_use_id="tu_1", output="contents", is_error=False),
        TurnComplete(cost=0.0, num_turns=1),
    ]

    handle = await runner.spawn("s1", "go")
    await runner._turns[handle.turn_id].task  # type: ignore[arg-type]

    snap = runner.peek("s1")
    assert snap["session_id"] == "s1"
    assert snap["turn_id"] == handle.turn_id
    assert snap["finished"] is True
    assert snap["last_assistant_text"] == "hello"

    kinds = [e["kind"] for e in snap["events"]]
    assert "text_delta" in kinds
    assert "text" in kinds
    assert "tool_use" in kinds
    assert "tool_result" in kinds


async def test_peek_since_seq_is_incremental(
    runner: BackgroundAgentRunner, pool: FakePool, queue: NotificationQueue
) -> None:
    pool.add_session("s1")
    pool.scripts["s1"] = [
        TextComplete(text="one"),
        TextComplete(text="two"),
        TextComplete(text="three"),
        TurnComplete(cost=0.0, num_turns=1),
    ]
    handle = await runner.spawn("s1", "go")
    await runner._turns[handle.turn_id].task  # type: ignore[arg-type]

    first = runner.peek("s1", limit=2)
    assert len(first["events"]) == 2
    last_seq = first["events"][-1]["seq"]

    second = runner.peek("s1", since_seq=last_seq)
    # Remaining events should have seq > last_seq
    assert all(e["seq"] > last_seq for e in second["events"])
    assert len(second["events"]) >= 1


def test_peek_unknown_session_returns_error_shape(
    runner: BackgroundAgentRunner,
) -> None:
    snap = runner.peek("missing")
    assert snap["status"] == "unknown"
    assert snap["events"] == []
    assert "error" in snap


# ---------------------------------------------------------------------------
# NotificationQueue
# ---------------------------------------------------------------------------


async def test_drain_returns_and_clears(queue: NotificationQueue) -> None:
    n1 = _make_notification("t1")
    n2 = _make_notification("t2")
    queue.push(n1)
    queue.push(n2)
    assert queue.has_pending() and queue.pending_count() == 2
    items = queue.drain()
    assert items == [n1, n2]
    assert not queue.has_pending()
    assert queue.drain() == []


async def test_wake_callback_fires_on_each_push(queue: NotificationQueue) -> None:
    invocations = 0
    fired = asyncio.Event()

    async def cb() -> None:
        nonlocal invocations
        invocations += 1
        fired.set()

    queue.set_wake_callback(cb)
    for i in range(3):
        queue.push(_make_notification(f"t{i}"))

    # The callback is dispatched via loop.create_task so we yield to let it run
    # All three pushes should produce three callback invocations
    for _ in range(20):
        await asyncio.sleep(0.01)
        if invocations >= 3:
            break
    assert invocations == 3


async def test_wake_callback_unset_clears(queue: NotificationQueue) -> None:
    calls = 0

    async def cb() -> None:
        nonlocal calls
        calls += 1

    queue.set_wake_callback(cb)
    queue.set_wake_callback(None)
    queue.push(_make_notification("t1"))
    await asyncio.sleep(0.05)
    assert calls == 0


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_notification(turn_id: str) -> Notification:
    return Notification(
        notification_id="nid-" + turn_id,
        turn_id=turn_id,
        session_id="s1",
        session_title=None,
        origin_tool_use_id=None,
        status="succeeded",
        cost=0.0,
        turns=1,
        duration_seconds=0.1,
        error=None,
    )


# ---------------------------------------------------------------------------
# No-progress timeout, tab signalling, per-conversation runtimes
# ---------------------------------------------------------------------------


class TabPool(FakePool):
    """FakePool that records what the runner tells a session's open tabs."""

    def __init__(self) -> None:
        super().__init__()
        self.tab_frames: list[tuple[str, dict[str, Any]]] = []

    async def broadcast_session(self, sid: str, payload: dict[str, Any], *, exclude=None) -> None:
        self.tab_frames.append((sid, payload))


async def test_working_agent_is_not_stopped_past_the_idle_timeout(
    store: FakeStore, queue: NotificationQueue
) -> None:
    """The limit counts time WITHOUT progress: a turn that keeps producing
    events runs well past it (the 2026-10-10 digest turn was killed at 600 s
    while still editing files)."""
    pool = TabPool()
    pool.add_session("s1")

    async def slow_send(sid: str, message: str, *, source_ws=None):
        for i in range(6):
            await asyncio.sleep(0.1)
            yield ToolUse(tool_use_id=f"t{i}", tool_name="Edit", tool_input={})
        yield TurnComplete(session_id="sdk", cost=0.0, num_turns=1)

    pool.send = slow_send  # type: ignore[method-assign]
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=0.3)
    handle = await runner.spawn("s1", "go")
    await runner._turns[handle.turn_id].task  # type: ignore[arg-type]

    [n] = queue.drain()
    assert n.status == "succeeded"
    assert n.duration_seconds > 0.5  # longer than the idle timeout
    assert pool.interrupts == []
    assert pool.tab_frames == []


async def test_timeout_ends_the_turn_in_every_open_tab(
    store: FakeStore, queue: NotificationQueue
) -> None:
    pool = TabPool()
    pool.add_session("s1")
    pool.block_send("s1")
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=0.2)
    handle = await runner.spawn("s1", "go")
    await runner._turns[handle.turn_id].task  # type: ignore[arg-type]

    [n] = queue.drain()
    assert n.status == "timeout"
    assert "no progress" in (n.error or "")
    frames = [f for sid, f in pool.tab_frames if sid == "s1"]
    assert frames[0]["type"] == "error" and frames[0]["error"] == "turn_timeout"
    assert "no progress" in frames[0]["detail"]
    assert frames[-1] == {"type": "status", "status": "interrupted"}


async def test_max_turn_seconds_caps_a_busy_turn(
    store: FakeStore, queue: NotificationQueue
) -> None:
    pool = TabPool()
    pool.add_session("s1")

    async def chatty_send(sid: str, message: str, *, source_ws=None):
        while True:
            await asyncio.sleep(0.02)
            yield TextDelta(text=".")

    pool.send = chatty_send  # type: ignore[method-assign]
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=5.0, max_turn_seconds=0.2)
    handle = await runner.spawn("s1", "go")
    await runner._turns[handle.turn_id].task  # type: ignore[arg-type]
    [n] = queue.drain()
    assert n.status == "timeout"
    assert "exceeded" in (n.error or "")


async def test_pending_permission_is_never_idle(
    store: FakeStore, queue: NotificationQueue
) -> None:
    pool = TabPool()
    pool.add_session("s1")
    pool.sessions["s1"].pending_permission_ids = MagicMock(return_value=["perm-1"])
    gate = asyncio.Event()

    async def waiting_send(sid: str, message: str, *, source_ws=None):
        yield PermissionRequest(request_id="perm-1", tool_name="ExitPlanMode", tool_input={})
        await gate.wait()  # the user takes their time answering
        yield TurnComplete(session_id="sdk", cost=0.0, num_turns=1)

    pool.send = waiting_send  # type: ignore[method-assign]
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=0.2)
    handle = await runner.spawn("s1", "go")
    await asyncio.sleep(0.6)
    assert not runner._turns[handle.turn_id].finished
    gate.set()
    await runner._turns[handle.turn_id].task  # type: ignore[arg-type]
    [n] = queue.drain()
    assert n.status == "succeeded"


async def test_orchestrator_cancel_ends_the_turn_in_every_open_tab(
    store: FakeStore, queue: NotificationQueue
) -> None:
    pool = TabPool()
    pool.add_session("s1")
    pool.block_send("s1")
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=10.0)
    handle = await runner.spawn("s1", "go")
    await asyncio.sleep(0.05)
    assert await runner.cancel(handle.turn_id)
    await asyncio.gather(runner._turns[handle.turn_id].task, return_exceptions=True)  # type: ignore[arg-type]
    assert ("s1", {"type": "status", "status": "interrupted"}) in pool.tab_frames
    [n] = queue.drain()
    assert n.status == "cancelled"


async def test_agent_runtimes_are_per_conversation(store: FakeStore) -> None:
    from orchestrator.runner import AgentRuntimes

    pool = FakePool()
    rts = AgentRuntimes()
    a1 = rts.acquire("conv-a", pool, store)  # type: ignore[arg-type]
    a2 = rts.acquire("conv-a", pool, store)  # type: ignore[arg-type]
    b = rts.acquire("conv-b", pool, store)  # type: ignore[arg-type]
    assert a1 is a2
    assert a1[0] is not b[0] and a1[1] is not b[1]


def test_release_wake_callback_only_clears_its_owner(queue: NotificationQueue) -> None:
    old, new = object(), object()

    async def cb() -> None:
        return None

    queue.set_wake_callback(cb, owner=old)
    queue.set_wake_callback(cb, owner=new)  # the conversation's next session
    queue.release_wake_callback(old)        # the old session stops afterwards
    assert queue._wake_cb is cb
    queue.release_wake_callback(new)
    assert queue._wake_cb is None


async def test_finished_turns_are_pruned_but_latest_per_session_kept(
    pool: FakePool, store: FakeStore, queue: NotificationQueue, monkeypatch: pytest.MonkeyPatch
) -> None:
    import orchestrator.runner as runner_mod

    monkeypatch.setattr(runner_mod, "_MAX_FINISHED_TURNS", 3)
    pool.add_session("s1")
    pool.add_session("s2")
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=2.0)
    h2 = await runner.spawn("s2", "once")
    await runner._turns[h2.turn_id].task  # type: ignore[arg-type]
    last = None
    for _ in range(6):
        last = await runner.spawn("s1", "go")
        await runner._turns[last.turn_id].task  # type: ignore[arg-type]
    assert len(runner._turns) <= 4
    assert h2.turn_id in runner._turns and last.turn_id in runner._turns


# ---------------------------------------------------------------------------
# Background subagents: the turn stays open until they are reported on
# ---------------------------------------------------------------------------

_ISO = "2026-10-12T12:00:00Z"


def _bg_spawn(tid: str, description: str = "Find it", *, background: bool = True) -> list[Any]:
    """What the Claude session yields for one Agent call (CLI 2.1.292: async launch)."""
    return [
        ToolUse(tool_use_id=tid, tool_name="Agent", tool_input={"description": description}),
        SubagentStarted(
            tool_use_id=tid, parent_tool_use_id=None, agent_type="Explore",
            description=description, background=background, started_at=_ISO,
        ),
        ToolResult(tool_use_id=tid, output=f"Async agent launched successfully.\nagentId: a-{tid}"),
    ]


def _note(tid: str) -> UnpromptedTurnStarted:
    return UnpromptedTurnStarted(
        text=f"<task-notification>\n<task-id>a-{tid}</task-id>\n<tool-use-id>{tid}</tool-use-id>\n"
             f"<status>completed</status>\n</task-notification>",
    )


def _report_turn(pool: FakePool, sid: str, tid: str, text: str, *, cost: float = 0.001) -> None:
    """The CLI's own task-notification turn, as the pool's out-of-turn pump delivers it."""
    pool.emit(sid, _note(tid))
    pool.emit(sid, ToolUse(tool_use_id=f"r-{tid}", tool_name="Read", tool_input={}))
    pool.emit(sid, TextComplete(text=text))
    pool.emit(sid, TurnComplete(cost=cost, num_turns=1, origin="task-notification"))


async def _until(pred, timeout: float = 2.0) -> None:
    deadline = time.monotonic() + timeout
    while not pred():
        assert time.monotonic() < deadline, "condition not met in time"
        await asyncio.sleep(0.01)


def _sub_pool(cls: type[FakePool] = FakePool) -> FakePool:
    pool = cls()
    pool.add_session("s1")
    sm = pool.sessions["s1"]
    sm.stop_subagent = AsyncMock()
    sm.awaits_unprompted_turn = MagicMock(return_value=False)
    return pool


async def test_background_subagents_keep_the_turn_open_until_the_last_report(
    store: FakeStore, queue: NotificationQueue
) -> None:
    """2 background agents + 2 notification turns → exactly one notification,
    after the last report, carrying the final text (not "I started 2 agents")."""
    pool = _sub_pool()
    pool.scripts["s1"] = [
        *_bg_spawn("t1", "Scan backend"), *_bg_spawn("t2", "Scan frontend"),
        TextComplete(text="I started 2 agents."),
        TurnComplete(cost=0.01, num_turns=1),
    ]
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=2.0, settle_grace=5.0)
    handle = await runner.spawn("s1", "audit")
    record = runner._turns[handle.turn_id]
    await _until(lambda: record.phase == "subagents")

    snap = runner.peek("s1")
    assert snap["finished"] is False and snap["status"] == "running"
    assert snap["phase"] == "waiting_for_subagents" and snap["waiting_for_subagents"] == 2
    assert [r["status"] for r in snap["subagents"]] == ["running", "running"]
    [h] = runner.list_in_flight()
    assert h.phase == "waiting_for_subagents" and h.waiting_for_subagents == 2

    pool.emit("s1", SubagentProgress(tool_use_id="t1", activity="Grep"))
    pool.emit("s1", SubagentEvent(tool_use_id="t1", event=ToolUse(tool_use_id="x1", tool_name="Grep", tool_input={})))
    pool.emit("s1", SubagentFinished(tool_use_id="t1", status="done", summary="backend ok"))
    _report_turn(pool, "s1", "t1", "Backend scan is done; waiting for frontend.")
    await asyncio.sleep(0.1)
    assert not record.finished and queue.drain() == []
    assert runner.peek("s1")["waiting_for_subagents"] == 1

    pool.emit("s1", SubagentFinished(tool_use_id="t2", status="done", summary="frontend ok"))
    pool.emit("s1", _note("t2"))
    assert runner.peek("s1")["phase"] == "notification_turn"
    pool.emit("s1", TextComplete(text="Both scans are clean: final answer."))
    pool.emit("s1", TurnComplete(cost=0.002, num_turns=1, origin="task-notification"))
    await asyncio.wait_for(record.task, 2.0)  # type: ignore[arg-type]

    [n] = queue.drain()
    assert n.status == "succeeded" and n.error is None
    assert n.preview == "Both scans are clean: final answer."
    assert n.subagents == 2
    assert n.cost == pytest.approx(0.013) and n.turns == 3
    snap = runner.peek("s1", limit=200)
    kinds = [e["kind"] for e in snap["events"]]
    assert kinds.count("notification_turn") == 2 and kinds.count("notification_turn_end") == 2
    # Subagent content never enters the ring; the report turns' own events do.
    assert "x1" not in {e["payload"].get("tool_use_id") for e in snap["events"]}
    assert "r-t1" in {e["payload"].get("tool_use_id") for e in snap["events"]}
    assert snap["last_assistant_text"] == "Both scans are clean: final answer."
    assert pool.taps["s1"] == []  # untapped
    assert pool.interrupts == []


async def test_turn_without_subagents_is_unchanged(store: FakeStore, queue: NotificationQueue) -> None:
    pool = _sub_pool()
    pool.scripts["s1"] = [TextComplete(text="All done."), TurnComplete(cost=0.0, num_turns=1)]
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=2.0, settle_grace=10.0)
    handle = await runner.spawn("s1", "go")
    t0 = time.monotonic()
    await asyncio.wait_for(runner._turns[handle.turn_id].task, 1.0)  # type: ignore[arg-type]
    assert time.monotonic() - t0 < 0.5  # never waits the settle grace
    [n] = queue.drain()
    assert n.status == "succeeded" and n.preview == "All done." and n.subagents == 0
    assert runner._turns[handle.turn_id].phase == "agent"


async def test_foreground_subagent_finished_in_turn_is_not_followed(
    store: FakeStore, queue: NotificationQueue
) -> None:
    pool = _sub_pool()
    pool.scripts["s1"] = [
        *_bg_spawn("t1", background=False),
        SubagentFinished(tool_use_id="t1", status="done", summary="found"),
        TextComplete(text="The agent found it."),
        TurnComplete(cost=0.0, num_turns=1),
    ]
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=2.0, settle_grace=10.0)
    handle = await runner.spawn("s1", "go")
    await asyncio.wait_for(runner._turns[handle.turn_id].task, 1.0)  # type: ignore[arg-type]
    [n] = queue.drain()
    assert n.status == "succeeded" and n.subagents == 1 and n.preview == "The agent found it."


async def test_idle_timeout_still_fires_while_waiting_for_subagents(
    store: FakeStore, queue: NotificationQueue
) -> None:
    pool = _sub_pool(TabPool)
    pool.scripts["s1"] = [*_bg_spawn("t1"), TextComplete(text="Started."), TurnComplete(cost=0.0, num_turns=1)]
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=0.3, settle_grace=5.0)
    handle = await runner.spawn("s1", "go")
    await asyncio.wait_for(runner._turns[handle.turn_id].task, 3.0)  # type: ignore[arg-type]

    [n] = queue.drain()
    assert n.status == "timeout"
    assert "no progress" in (n.error or "") and "waiting for its subagents" in (n.error or "")
    assert "stopped 1 subagent" in (n.error or "")
    pool.sessions["s1"].stop_subagent.assert_awaited_once_with("t1")
    # Only subagents were left: no SDK turn to interrupt, nothing to end in the tabs.
    assert pool.interrupts == []
    frames = [f for sid, f in pool.tab_frames if sid == "s1"]
    assert frames == [{"type": "error", "error": "turn_timeout", "detail": frames[0]["detail"]}]
    assert "waiting for its subagents" in frames[0]["detail"]


async def test_subagent_progress_counts_as_progress_while_following(
    store: FakeStore, queue: NotificationQueue
) -> None:
    pool = _sub_pool()
    pool.scripts["s1"] = [*_bg_spawn("t1"), TurnComplete(cost=0.0, num_turns=1)]
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=0.3, settle_grace=5.0)
    handle = await runner.spawn("s1", "go")
    record = runner._turns[handle.turn_id]
    await _until(lambda: record.phase == "subagents")
    for i in range(8):  # 0.8 s of work, well past the 0.3 s idle timeout
        await asyncio.sleep(0.1)
        pool.emit("s1", SubagentProgress(tool_use_id="t1", activity=f"step {i}"))
    assert not record.finished
    pool.emit("s1", SubagentFinished(tool_use_id="t1", status="done"))
    _report_turn(pool, "s1", "t1", "Done after a long scan.")
    await asyncio.wait_for(record.task, 1.0)  # type: ignore[arg-type]
    [n] = queue.drain()
    assert n.status == "succeeded" and n.preview == "Done after a long scan."


async def test_interrupt_stops_the_turns_subagents(store: FakeStore, queue: NotificationQueue) -> None:
    from orchestrator.tools.agent_sessions import interrupt_agent_session
    import json as _json

    pool = _sub_pool(TabPool)
    pool.scripts["s1"] = [*_bg_spawn("t1"), *_bg_spawn("t2"), TurnComplete(cost=0.0, num_turns=1)]
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=5.0, settle_grace=5.0)
    handle = await runner.spawn("s1", "go")
    record = runner._turns[handle.turn_id]
    await _until(lambda: record.phase == "subagents")
    pool.emit("s1", SubagentFinished(tool_use_id="t2", status="done"))

    out = _json.loads(await interrupt_agent_session({"pool": pool, "runner": runner}, "s1"))
    await asyncio.gather(record.task, return_exceptions=True)  # type: ignore[arg-type]
    assert out["status"] == "interrupted" and out["cancelled_turns"] == [handle.turn_id]
    assert out["stopped_subagents"] == 1
    pool.sessions["s1"].stop_subagent.assert_awaited_once_with("t1")  # t2 had already finished
    assert pool.interrupts == []  # no SDK turn was running
    [n] = queue.drain()
    assert n.status == "cancelled" and "stopped 1 subagent" in (n.error or "")


async def test_cancel_mid_prompt_interrupts_and_stops_spawned_subagents(
    store: FakeStore, queue: NotificationQueue
) -> None:
    pool = _sub_pool(TabPool)
    gate = asyncio.Event()

    async def send(sid: str, message: str, *, source_ws=None):
        for ev in _bg_spawn("t1"):
            yield ev
        await gate.wait()
        yield TurnComplete(cost=0.0, num_turns=1)

    pool.send = send  # type: ignore[method-assign]
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=5.0)
    handle = await runner.spawn("s1", "go")
    record = runner._turns[handle.turn_id]
    await _until(lambda: "t1" in record.subagents)
    assert await runner.cancel(handle.turn_id)
    await asyncio.gather(record.task, return_exceptions=True)  # type: ignore[arg-type]
    pool.sessions["s1"].stop_subagent.assert_awaited_once_with("t1")
    assert pool.interrupts == ["s1"]
    assert ("s1", {"type": "status", "status": "interrupted"}) in pool.tab_frames
    [n] = queue.drain()
    assert n.status == "cancelled"


async def test_interrupt_without_a_runner_turn_still_interrupts_the_session(
    store: FakeStore, queue: NotificationQueue
) -> None:
    from orchestrator.tools.agent_sessions import interrupt_agent_session
    import json as _json

    pool = _sub_pool()
    runner = BackgroundAgentRunner(pool, store, queue)
    out = _json.loads(await interrupt_agent_session({"pool": pool, "runner": runner}, "s1"))
    assert out == {"session_id": "s1", "status": "interrupted"}
    assert pool.interrupts == ["s1"]


async def test_report_turn_of_another_turns_subagent_is_not_ours(
    store: FakeStore, queue: NotificationQueue
) -> None:
    pool = _sub_pool()
    pool.scripts["s1"] = [*_bg_spawn("t1"), TurnComplete(cost=0.0, num_turns=1)]
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=5.0, settle_grace=5.0)
    handle = await runner.spawn("s1", "go")
    record = runner._turns[handle.turn_id]
    await _until(lambda: record.phase == "subagents")
    pool.emit("s1", SubagentFinished(tool_use_id="old", status="done"))  # an earlier turn's agent
    _report_turn(pool, "s1", "old", "Old agent report.")
    await asyncio.sleep(0.05)
    assert not record.finished and record.notification_turns == 0
    assert record.last_assistant_text != "Old agent report."
    pool.emit("s1", SubagentFinished(tool_use_id="t1", status="failed", summary="crashed"))
    _report_turn(pool, "s1", "t1", "The agent failed: crashed.")
    await asyncio.wait_for(record.task, 1.0)  # type: ignore[arg-type]
    [n] = queue.drain()
    assert n.status == "succeeded" and n.preview == "The agent failed: crashed."


async def test_waits_for_every_owed_report_turn(store: FakeStore, queue: NotificationQueue) -> None:
    """Both agents end before the first report: the session still owes the
    second report turn (awaits_unprompted_turn), so the turn waits for it."""
    pool = _sub_pool()
    owed = {"n": 0}
    pool.sessions["s1"].awaits_unprompted_turn = MagicMock(side_effect=lambda: owed["n"] > 0)
    pool.scripts["s1"] = [*_bg_spawn("t1"), *_bg_spawn("t2"), TurnComplete(cost=0.0, num_turns=1)]
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=5.0, settle_grace=5.0)
    handle = await runner.spawn("s1", "go")
    record = runner._turns[handle.turn_id]
    await _until(lambda: record.phase == "subagents")
    owed["n"] = 2
    pool.emit("s1", SubagentFinished(tool_use_id="t1", status="done"))
    pool.emit("s1", SubagentFinished(tool_use_id="t2", status="done"))
    owed["n"] = 1
    _report_turn(pool, "s1", "t1", "First report.")
    await asyncio.sleep(0.1)
    assert not record.finished
    owed["n"] = 0
    _report_turn(pool, "s1", "t2", "Second report: all done.")
    await asyncio.wait_for(record.task, 1.0)  # type: ignore[arg-type]
    [n] = queue.drain()
    assert n.preview == "Second report: all done."


async def test_background_agent_that_ended_during_the_turn_waits_for_its_report(
    store: FakeStore, queue: NotificationQueue
) -> None:
    pool = _sub_pool()
    pool.scripts["s1"] = [
        *_bg_spawn("t1"), SubagentFinished(tool_use_id="t1", status="done"),
        TextComplete(text="Started one."), TurnComplete(cost=0.0, num_turns=1),
    ]
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=5.0, settle_grace=5.0)
    handle = await runner.spawn("s1", "go")
    record = runner._turns[handle.turn_id]
    await _until(lambda: record.phase == "subagents")
    assert runner.peek("s1")["waiting_for_subagents"] == 0
    _report_turn(pool, "s1", "t1", "It finished quickly: result.")
    await asyncio.wait_for(record.task, 1.0)  # type: ignore[arg-type]
    [n] = queue.drain()
    assert n.preview == "It finished quickly: result."


async def test_no_report_turn_ends_after_the_settle_grace(store: FakeStore, queue: NotificationQueue) -> None:
    """A harness without report turns: the turn ends settle_grace after the
    last subagent finished, with the prompt's own text."""
    pool = _sub_pool()
    pool.scripts["s1"] = [*_bg_spawn("t1"), TextComplete(text="Launched."), TurnComplete(cost=0.0, num_turns=1)]
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=5.0, settle_grace=0.2)
    handle = await runner.spawn("s1", "go")
    record = runner._turns[handle.turn_id]
    await _until(lambda: record.phase == "subagents")
    pool.emit("s1", SubagentFinished(tool_use_id="t1", status="done", summary="ok"))
    await asyncio.wait_for(record.task, 2.0)  # type: ignore[arg-type]
    [n] = queue.drain()
    assert n.status == "succeeded" and n.preview == "Launched."
    assert runner.peek("s1")["subagents"][0]["summary"] == "ok"


async def test_missed_finish_is_reconciled_from_the_live_registry(
    store: FakeStore, queue: NotificationQueue
) -> None:
    pool = _sub_pool()
    live = [{"tool_use_id": "t1", "status": "running"}]
    pool.subagents_for = lambda sid: live  # type: ignore[attr-defined]
    pool.scripts["s1"] = [*_bg_spawn("t1"), TurnComplete(cost=0.0, num_turns=1)]
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=5.0, settle_grace=0.2)
    handle = await runner.spawn("s1", "go")
    record = runner._turns[handle.turn_id]
    await _until(lambda: record.phase == "subagents")
    live[0] = {"tool_use_id": "t1", "status": "done", "summary": "seen only in the registry"}
    await asyncio.wait_for(record.task, 3.0)  # type: ignore[arg-type]
    [n] = queue.drain()
    assert n.status == "succeeded"


async def test_session_closed_while_subagents_run_fails_the_turn(
    store: FakeStore, queue: NotificationQueue
) -> None:
    pool = _sub_pool()
    pool.scripts["s1"] = [*_bg_spawn("t1"), TurnComplete(cost=0.0, num_turns=1)]
    runner = BackgroundAgentRunner(pool, store, queue, idle_timeout=5.0, settle_grace=0.2)
    handle = await runner.spawn("s1", "go")
    record = runner._turns[handle.turn_id]
    await _until(lambda: record.phase == "subagents")
    del pool.sessions["s1"]
    await asyncio.wait_for(record.task, 3.0)  # type: ignore[arg-type]
    [n] = queue.drain()
    assert n.status == "failed" and "session closed" in (n.error or "")


async def test_notification_renders_subagents_and_last_message() -> None:
    from orchestrator.session import _notification_entry, _render_notifications

    n = Notification(
        "nid", "turn-1234", "agent-1", "Audit", None, "succeeded", 0.0, 3, 12.0, None,
        preview='Both scans are "clean".', subagents=2,
    )
    line = _render_notifications([n])
    assert "subagents=2" in line and "last_message=\"Both scans are 'clean'.\"" in line
    entry = _notification_entry(n)
    assert entry["subagents"] == 2 and entry["preview"] == 'Both scans are "clean".'
