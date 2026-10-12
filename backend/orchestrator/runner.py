"""Background agent-turn lifecycle for the orchestrator.

Owns asyncio tasks that drive ``pool.send()`` for fire-and-forget agent
calls (the orchestrator's ``send_to_agent_session`` tool), buffers per-turn
events so ``read_agent_session`` can return a live tail while a turn is in
flight, and pushes structured ``Notification`` records onto a queue the
orchestrator drains at the start of each turn.

The runner does **not** import from ``orchestrator.session`` or
``orchestrator.agent`` — it depends only on ``SessionPool``, ``SessionStore``,
and the manager's event types — so it stays unit-testable with simple
mock pools.
"""

from __future__ import annotations

import asyncio
import inspect
import json
import logging
import re
import time
import uuid
from collections import deque
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

# TurnAbandoned is the provider-agnostic base for both SessionAbandoned
# (Claude) and QwenAbandoned (Qwen) — catching it handles both.  Avoid
# importing the provider-specific subclasses here so this module loads
# even when the matching SDK isn't installed.
from manager.base_session import TurnAbandoned
from manager.subagents import is_spawn_tool
from manager.types import (
    PermissionRequest,
    PermissionResolved,
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

if TYPE_CHECKING:
    from api.pool import SessionPool
    from manager.store import SessionStore

logger = logging.getLogger(__name__)

# Finished turns a runner keeps for read_agent_session / peek.
_MAX_FINISHED_TURNS = 100
# Subagents listed per turn by peek() (their events never enter the turn's ring).
_MAX_SUBAGENTS_PER_TURN = 20
# Subagents tracked per turn (bounds memory; a turn waits only for tracked ones).
_MAX_TRACKED_SUBAGENTS = 200
# How long a turn whose subagents all ended waits for the report turn the
# harness starts for them (Claude: a <task-notification> turn), counted from
# the last event the session produced.  Normally that turn starts within a
# second; this only bounds the wait when it never comes.
_SETTLE_GRACE_S = 30.0
# Max wait for one stop_subagent call during cancel / timeout.
_STOP_SUBAGENT_TIMEOUT_S = 10.0
_SUBAGENT_EVENTS = (SubagentStarted, SubagentProgress, SubagentFinished, SubagentEvent)
# The spawning tool call a Claude <task-notification> prompt reports on.
_NOTIFICATION_TOOL_USE_RE = re.compile(r"<tool-use-id>\s*([^<\s]+)\s*</tool-use-id>")


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class AgentTurnEvent:
    """A single buffered event from an in-flight agent turn.

    Buffered in a bounded ring so read_agent_session can return a live tail
    of recent activity without holding the entire turn in memory.  ``seq``
    is monotonic per (session_id, turn_id).
    """

    seq: int
    kind: str  # "text" | "tool_use" | "tool_result" | "permission_request" | "permission_resolved"
    payload: dict[str, Any]
    timestamp: float


@dataclass(slots=True)
class _TurnRecord:
    """Internal mutable state for one in-flight (or recently finished) turn."""

    turn_id: str
    session_id: str
    session_title: str | None
    origin_tool_use_id: str | None
    started_at: float  # time.monotonic
    started_at_wall: float  # time.time, for surfacing to the LLM
    # time.monotonic of the last event the agent produced (text, tool, permission).
    # The no-progress timeout counts from here, not from started_at.
    last_progress_at: float = 0.0
    status: str = "running"
    task: asyncio.Task | None = None
    finished: bool = False
    cost: float = 0.0
    turns: int = 0
    error: str | None = None
    events: deque[AgentTurnEvent] = field(default_factory=deque)
    next_seq: int = 1
    last_assistant_text: str = ""
    pending_permission_ids: tuple[str, ...] = ()
    # Subagents of this turn, by spawning tool_use_id: a one-line summary
    # each (spec 12 §4.8).  Kept apart from ``events`` so a busy subagent
    # cannot evict the parent's own events from the ring.  ``status`` is
    # running | done | failed | stopped, or ``unknown`` when only content
    # events were seen (never waited for).
    subagents: dict[str, dict[str, Any]] = field(default_factory=dict)
    # tool_use_ids of the spawning calls (Agent / Task ...) this turn made.
    spawn_ids: set[str] = field(default_factory=set)
    # "agent": pool.send() of the delegated prompt is running.
    # "subagents": that prompt's turn ended but subagents it started are
    # still running or have yet to be reported on — the runner follows the
    # session's own notification turns until they are done.
    phase: str = "agent"
    # A notification turn reporting on this turn's subagents is running.
    nt_open: bool = False
    # Notification turns of this turn that ended (Claude: task-notification).
    notification_turns: int = 0
    # Ordering of what the runner observed: a counter bumped by subagent
    # finishes and notification-turn ends, so "a report came after the last
    # finish" is exact even when both land in the same instant.
    obs_seq: int = 0
    last_finish_seq: int = 0
    last_nt_end_seq: int = 0
    # time.monotonic of the last event of this session the runner observed
    # while following (anything: also another tab's turn).
    last_session_event_at: float = 0.0
    # Last text of another prompted turn on the session (follow phase).
    foreign_text: str | None = None
    # Set by every observed event: wakes the follow loop.
    wake: asyncio.Event = field(default_factory=asyncio.Event)
    # cancel() was asked for: the turn ends as "cancelled" whatever happens next.
    cancel_requested: bool = False
    stopped_subagents: int = 0


@dataclass(frozen=True, slots=True)
class AgentTurnHandle:
    """Public, read-only view of an in-flight turn.

    Returned by :meth:`BackgroundAgentRunner.spawn` and used by the prompt
    builder to render the active-sessions section.  Frozen so consumers
    can't accidentally mutate runner state.
    """

    turn_id: str
    session_id: str
    session_title: str | None
    origin_tool_use_id: str | None
    started_at: float  # wall-clock seconds since epoch
    elapsed_seconds: float
    status: str
    pending_permission_ids: tuple[str, ...]
    # "agent" (the delegated prompt is running), "waiting_for_subagents" or
    # "notification_turn" (the session reports on this turn's subagents).
    phase: str = "agent"
    waiting_for_subagents: int = 0


@dataclass(frozen=True, slots=True)
class Notification:
    """A completed-turn notification destined for the orchestrator's LLM.

    Pushed by ``_drive`` exactly once per turn (success/failure/timeout/
    cancel) and drained by the orchestrator session before each LLM call.
    """

    notification_id: str
    turn_id: str
    session_id: str
    session_title: str | None
    origin_tool_use_id: str | None
    status: str  # "succeeded" | "failed" | "cancelled" | "timeout"
    cost: float
    turns: int
    duration_seconds: float
    error: str | None  # set when status != "succeeded"
    #: One line of the turn's final assistant text — after background
    #: subagents, the text of the last report turn, not "I started 3 agents".
    preview: str | None = None
    #: Subagents the turn started (top level).
    subagents: int = 0


# ---------------------------------------------------------------------------
# NotificationQueue
# ---------------------------------------------------------------------------


class NotificationQueue:
    """Append-only queue of :class:`Notification`, drained on idle.

    Decouples background runners from the orchestrator's turn lifecycle.
    A single async ``wake_callback`` is invoked best-effort after every
    push; listeners decide whether to start a new orchestrator turn or
    just let the next user prompt pick it up.
    """

    def __init__(self) -> None:
        self._items: list[Notification] = []
        self._wake_cb: Callable[[], Awaitable[None]] | None = None
        self._wake_owner: object | None = None

    def push(self, n: Notification) -> None:
        self._items.append(n)
        cb = self._wake_cb
        if cb is None:
            return
        # Fire-and-forget so a slow callback can't block the pushing task.
        # The callback is responsible for its own error handling.
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            logger.debug("NotificationQueue.push: no running loop, skipping wake")
            return
        loop.create_task(_safe_invoke(cb), name="notification-wake")

    def drain(self) -> list[Notification]:
        out = self._items
        self._items = []
        return out

    def has_pending(self) -> bool:
        return bool(self._items)

    def pending_count(self) -> int:
        return len(self._items)

    def set_wake_callback(
        self, cb: Callable[[], Awaitable[None]] | None, *, owner: object | None = None,
    ) -> None:
        self._wake_cb = cb
        self._wake_owner = owner if cb is not None else None

    def release_wake_callback(self, owner: object) -> None:
        """Clear the wake callback only if ``owner`` installed it.

        The queue outlives orchestrator sessions (:class:`AgentRuntimes`): an
        old session stopping must not unhook the callback a newer session of
        the same conversation already installed."""
        if self._wake_owner is owner:
            self._wake_cb = None
            self._wake_owner = None


async def _safe_invoke(cb: Callable[[], Awaitable[None]]) -> None:
    try:
        await cb()
    except Exception:  # noqa: BLE001
        logger.exception("NotificationQueue wake callback raised")


class _TurnTimedOut(Exception):
    """Raised by :meth:`BackgroundAgentRunner._run_watched`; ``reason`` is human-readable."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


# ---------------------------------------------------------------------------
# BackgroundAgentRunner
# ---------------------------------------------------------------------------


class BackgroundAgentRunner:
    """Lifecycle manager for fire-and-forget agent turns.

    One instance per orchestrator conversation (:class:`AgentRuntimes`), shared
    by every :class:`OrchestratorSession` of that conversation.  Tools call
    :meth:`spawn` to dispatch a turn; the runner drives ``pool.send()`` to
    completion in a background task and pushes a :class:`Notification`
    onto the shared queue when done.  The orchestrator drains the queue
    on its next turn and renders each notification as a structured
    ``[SESSION xxx event: ...]`` line.

    Two concurrent ``spawn()`` calls for the same ``session_id`` serialize
    naturally inside ``pool.send()``'s per-session lock — no extra lock
    here.  Across distinct sessions, turns run in parallel.
    """

    def __init__(
        self,
        pool: SessionPool,
        store: SessionStore,
        notifications: NotificationQueue,
        *,
        peek_buffer_size: int = 200,
        idle_timeout: float = 1800.0,
        max_turn_seconds: float | None = None,
        settle_grace: float = _SETTLE_GRACE_S,
    ) -> None:
        """``idle_timeout``: stop a turn only after this long WITHOUT any event
        from the agent (text, tool call, tool result, permission, its
        subagents' events and its notification turns) — a working agent is
        never stopped, however long the task.  ``SessionStalled`` notices
        don't count as progress, and a turn waiting on a permission answer is
        never idle.  ``max_turn_seconds``: optional hard cap on the whole turn
        (``None`` = none).  ``settle_grace``: how long a turn whose subagents
        all ended waits for the harness's report turn (see
        :meth:`_follow_subagents`)."""
        self._pool = pool
        self._store = store
        self._notifications = notifications
        self._peek_buffer_size = peek_buffer_size
        self._idle_timeout = idle_timeout
        self._max_turn_seconds = max_turn_seconds
        self._settle_grace = settle_grace
        # turn_id → record
        self._turns: dict[str, _TurnRecord] = {}
        # session_id → last turn_id (for peek without explicit turn_id)
        self._latest_by_session: dict[str, str] = {}

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    @property
    def notifications(self) -> NotificationQueue:
        return self._notifications

    async def spawn(
        self,
        session_id: str,
        message: str,
        *,
        origin_tool_use_id: str | None = None,
        timeout: float | None = None,
    ) -> AgentTurnHandle:
        """Validate, generate a ``turn_id``, spawn the driver task, return a handle.

        Raises ``ValueError`` if the pool has no open session with that ID.
        A session restored after a restart is spawned first; otherwise this
        returns at once — it does not await the agent's response.
        """
        if not await self._pool.ensure_live(session_id):
            raise ValueError(f"No active session with ID {session_id}")

        title = self._resolve_title(session_id)
        turn_id = str(uuid.uuid4())
        now_mono = time.monotonic()
        now_wall = time.time()
        record = _TurnRecord(
            turn_id=turn_id,
            session_id=session_id,
            session_title=title,
            origin_tool_use_id=origin_tool_use_id,
            started_at=now_mono,
            started_at_wall=now_wall,
            last_progress_at=now_mono,
        )
        self._turns[turn_id] = record
        self._latest_by_session[session_id] = turn_id

        record.task = asyncio.create_task(
            self._drive(record, message, timeout or self._idle_timeout),
            name=f"runner-{session_id[:8]}-{turn_id[:8]}",
        )
        return self._handle(record)

    def peek(
        self,
        session_id: str,
        *,
        turn_id: str | None = None,
        since_seq: int = 0,
        limit: int = 50,
    ) -> dict[str, Any]:
        """Snapshot of buffered events for the most recent (or specified) turn.

        Returns a dict with keys ``session_id``, ``turn_id``, ``status``,
        ``finished``, ``next_seq``, ``last_assistant_text``, ``events`` —
        suitable for direct JSON serialization by the tool layer.
        """
        if turn_id is None:
            turn_id = self._latest_by_session.get(session_id)
        if turn_id is None or turn_id not in self._turns:
            return {
                "session_id": session_id,
                "turn_id": turn_id,
                "status": "unknown",
                "finished": True,
                "next_seq": 0,
                "last_assistant_text": "",
                "events": [],
                "error": "no in-flight or buffered turn for that session/turn_id",
            }
        record = self._turns[turn_id]
        events = [e for e in record.events if e.seq > since_seq][:limit]
        return {
            "session_id": record.session_id,
            "turn_id": record.turn_id,
            "session_title": record.session_title,
            "status": record.status,
            "finished": record.finished,
            "phase": _phase(record),
            "waiting_for_subagents": 0 if record.finished else len(self._running(record)),
            "notification_turns": record.notification_turns,
            "next_seq": record.next_seq,
            "last_assistant_text": record.last_assistant_text,
            "subagents": [_subagent_row(r) for r in list(record.subagents.values())[-_MAX_SUBAGENTS_PER_TURN:]],
            "events": [
                {
                    "seq": e.seq,
                    "kind": e.kind,
                    "payload": e.payload,
                    "timestamp": e.timestamp,
                }
                for e in events
            ],
        }

    def list_in_flight(self) -> list[AgentTurnHandle]:
        """Snapshot of currently running turns. Used by the prompt builder."""
        return [self._handle(r) for r in self._turns.values() if not r.finished]

    async def cancel(self, turn_id: str) -> bool:
        """Cancel a single turn. Returns True if a task was found and cancelled.

        Stops the subagents the turn started that are still running
        (``stop_subagent``), and calls ``pool.interrupt(session_id)`` while
        the session is working on the turn (its prompt, or a notification
        turn reporting on its subagents) so the SDK actually stops —
        cancelling our coroutine alone leaves the bundled ``claude``
        subprocess running.
        """
        record = self._turns.get(turn_id)
        if record is None or record.finished or record.task is None:
            return False
        record.cancel_requested = True
        record.stopped_subagents += await self._stop_subagents(record)
        if record.finished:
            return True  # ended (as cancelled) while its subagents were being stopped
        if record.phase == "agent" or record.nt_open:
            # Best-effort SDK interrupt.  No await may separate it from
            # task.cancel(): the interrupt ends pool.send(), and the turn must
            # still read as cancelled.
            try:
                await self._pool.interrupt(record.session_id)
            except Exception:  # noqa: BLE001
                logger.exception(
                    "Failed to interrupt SDK for session %s during cancel(%s)",
                    record.session_id, turn_id,
                )
        record.task.cancel()
        return True

    async def cancel_session(self, session_id: str) -> list[str]:
        """Cancel every in-flight turn on ``session_id`` (``interrupt_agent_session``).

        Returns the ids of the turns cancelled (empty: none was running)."""
        out: list[str] = []
        for r in [r for r in self._turns.values() if r.session_id == session_id and not r.finished]:
            if await self.cancel(r.turn_id):
                out.append(r.turn_id)
        return out

    def stopped_subagents(self, turn_id: str) -> int:
        record = self._turns.get(turn_id)
        return record.stopped_subagents if record is not None else 0

    async def cancel_all(self) -> None:
        """Cancel every in-flight task (tests and explicit teardown only —
        stopping an orchestrator session deliberately does NOT call this).

        After all tasks complete, finalise any record whose task was cancelled
        before its body ran (asyncio can cancel-before-start, in which case
        ``_drive``'s own finally never executes).  This guarantees one
        Notification per turn even in shutdown races.
        """
        records = [r for r in self._turns.values() if not r.finished and r.task is not None]
        if not records:
            return

        # Best-effort SDK interrupts (one per distinct session_id)
        seen: set[str] = set()
        for r in records:
            if r.session_id in seen:
                continue
            seen.add(r.session_id)
            try:
                await self._pool.interrupt(r.session_id)
            except Exception:  # noqa: BLE001
                logger.exception(
                    "Failed to interrupt SDK for session %s during cancel_all",
                    r.session_id,
                )

        for r in records:
            assert r.task is not None  # for type-checkers
            r.task.cancel()
        await asyncio.gather(
            *(r.task for r in records if r.task is not None),
            return_exceptions=True,
        )

        # Safety net for cancel-before-start: any record still un-finalised
        # had its task killed before _drive could run its own except/finally.
        for r in records:
            if not r.finished:
                self._finalise(r, status="cancelled", error="cancelled before start")

    # ------------------------------------------------------------------
    # Internals
    # ------------------------------------------------------------------

    def _handle(self, r: _TurnRecord) -> AgentTurnHandle:
        elapsed = time.monotonic() - r.started_at
        return AgentTurnHandle(
            turn_id=r.turn_id,
            session_id=r.session_id,
            session_title=r.session_title,
            origin_tool_use_id=r.origin_tool_use_id,
            started_at=r.started_at_wall,
            elapsed_seconds=elapsed,
            status=r.status,
            pending_permission_ids=r.pending_permission_ids,
            phase=_phase(r),
            waiting_for_subagents=0 if r.finished else len(self._running(r)),
        )

    def _resolve_title(self, session_id: str) -> str | None:
        """Best-effort lookup of the session's display title."""
        sm = self._pool.get(session_id)
        if sm is None:
            return None
        sdk_id = sm.sdk_session_id
        if not sdk_id:
            return None
        try:
            info = self._store.get_session_info(sdk_id)
        except Exception:  # noqa: BLE001
            return None
        return info.title if info is not None else None

    def _buffer(self, record: _TurnRecord, kind: str, payload: dict[str, Any]) -> None:
        """Append an event to the per-turn ring buffer (bounded).

        Every buffered event is agent progress: it resets the no-progress timeout."""
        record.last_progress_at = time.monotonic()
        seq = record.next_seq
        record.next_seq += 1
        evt = AgentTurnEvent(seq=seq, kind=kind, payload=payload, timestamp=time.monotonic())
        record.events.append(evt)
        if len(record.events) > self._peek_buffer_size:
            record.events.popleft()

    # ------------------------------------------------------------------
    # Subagents of a turn (spec 12 §4.8)
    # ------------------------------------------------------------------

    def _top_level(self, record: _TurnRecord) -> list[dict[str, Any]]:
        """The turn's own subagents (not the ones they spawned in turn)."""
        subs = record.subagents
        return [r for r in subs.values() if r.get("parent_tool_use_id") not in subs]

    def _running(self, record: _TurnRecord) -> list[str]:
        """tool_use_ids of the turn's top-level subagents still running."""
        return [r["tool_use_id"] for r in self._top_level(record) if r["status"] == "running"]

    def _mark_finished(self, record: _TurnRecord, row: dict[str, Any], status: str, summary: str | None) -> None:
        was_running = row["status"] in ("running", "unknown")
        row["status"] = status
        if summary:
            row["summary"] = _excerpt(summary, 300)
        if was_running:
            record.obs_seq += 1
            if row.get("parent_tool_use_id") not in record.subagents:
                record.last_finish_seq = record.obs_seq

    def _note_subagent(self, record: _TurnRecord, event: Any, *, in_turn: bool = True) -> bool:
        """Track a subagent event if it belongs to this turn; returns whether it did.

        A subagent is this turn's when its spawning call was made in the turn
        (``spawn_ids``), its parent is one of the turn's subagents, or — for a
        ``subagent_started`` without a parent — it started during the turn's
        own prompt or one of its notification turns.  Its events count as
        progress for the no-progress timeout but never enter the ring."""
        tid = event.tool_use_id
        row = record.subagents.get(tid)
        if row is None:
            parent = event.parent_tool_use_id if isinstance(event, SubagentStarted) else None
            if tid in record.spawn_ids or (parent is not None and parent in record.subagents):
                mine = True
            elif isinstance(event, SubagentStarted) and parent is None:
                mine = in_turn or record.nt_open
            else:
                mine = False
            if not mine or len(record.subagents) >= _MAX_TRACKED_SUBAGENTS:
                return False
            row = record.subagents[tid] = {
                "tool_use_id": tid, "parent_tool_use_id": parent, "agent_type": "",
                "description": "", "background": False, "status": "unknown", "activity": None,
            }
        record.last_progress_at = time.monotonic()
        if isinstance(event, SubagentStarted):
            row["agent_type"] = event.agent_type
            row["description"] = event.description
            row["background"] = event.background
            if row["status"] == "unknown":
                row["status"] = "running"
        elif isinstance(event, SubagentProgress):
            row["activity"] = event.activity or row["activity"]
        elif isinstance(event, SubagentFinished):
            self._mark_finished(record, row, event.status, event.summary)
        elif isinstance(event, SubagentEvent) and isinstance(event.event, ToolUse):
            row["activity"] = event.event.tool_name
        return True

    def _reconcile(self, record: _TurnRecord) -> None:
        """Align the turn's subagents with the session's live registry
        (``pool.subagents_for``): a finish the runner missed, a spawn whose
        ``subagent_started`` it never saw."""
        fn = getattr(self._pool, "subagents_for", None)
        if fn is None:
            return
        try:
            rows = fn(record.session_id)
        except Exception:  # noqa: BLE001
            return
        if not isinstance(rows, list):
            return
        for live in rows:
            if not isinstance(live, dict):
                continue
            tid = live.get("tool_use_id")
            status = live.get("status")
            mine = record.subagents.get(tid) if isinstance(tid, str) else None
            if mine is None:
                if tid in record.spawn_ids and status == "running" and len(record.subagents) < _MAX_TRACKED_SUBAGENTS:
                    record.subagents[tid] = {
                        "tool_use_id": tid, "parent_tool_use_id": None,
                        "agent_type": live.get("agent_type") or "",
                        "description": live.get("description") or "",
                        "background": bool(live.get("background")),
                        "status": "running", "activity": live.get("activity"),
                    }
                continue
            if status in ("done", "failed", "stopped") and mine["status"] in ("running", "unknown"):
                self._mark_finished(record, mine, status, live.get("summary"))
            elif status == "running" and mine["status"] == "unknown":
                mine["status"] = "running"

    def _needs_follow(self, record: _TurnRecord) -> bool:
        """After the delegated prompt's turn: is there more to wait for?"""
        top = self._top_level(record)
        if not top:
            return False
        if any(r["status"] == "running" for r in top):
            return True
        if not any(r.get("background") for r in top):
            return False  # foreground agents: done within the turn
        # Background agents that ended during the turn: their report turn may still come.
        reported = record.notification_turns > 0 and record.last_nt_end_seq > record.last_finish_seq
        return not reported or self._expects_unprompted_turn(record)

    def _expects_unprompted_turn(self, record: _TurnRecord) -> bool:
        sm = self._pool.get(record.session_id)
        fn = getattr(sm, "awaits_unprompted_turn", None) if sm is not None else None
        if fn is None:
            return False
        try:
            out = fn()
        except Exception:  # noqa: BLE001
            return False
        return out if isinstance(out, bool) else False

    def _notification_is_ours(self, record: _TurnRecord, text: str) -> bool:
        """Does a harness-started turn report on one of this turn's subagents?"""
        if not record.subagents:
            return False
        m = _NOTIFICATION_TOOL_USE_RE.search(text or "")
        if m:
            return m.group(1) in record.subagents
        return True  # no id to match: the turn is following subagents, take it

    async def _stop_subagents(self, record: _TurnRecord) -> int:
        """Stop the turn's running subagents via ``stop_subagent``; returns how many."""
        self._reconcile(record)
        running = self._running(record)
        if not running:
            return 0
        sm = self._pool.get(record.session_id)
        stop = getattr(sm, "stop_subagent", None) if sm is not None else None
        if stop is None:
            return 0
        stopped = 0
        for tid in running:
            try:
                res = stop(tid)
                if inspect.isawaitable(res):
                    await asyncio.wait_for(res, timeout=_STOP_SUBAGENT_TIMEOUT_S)
                stopped += 1
            except NotImplementedError:
                break  # the harness cannot stop a single subagent
            except Exception as exc:  # noqa: BLE001 — already finished, unknown, slow
                logger.info("stop_subagent(%s) on %s failed: %s", tid, record.session_id, exc)
        if stopped:
            logger.info("Stopped %d subagent(s) of turn %s", stopped, record.turn_id)
        return stopped

    def _buffer_content(self, record: _TurnRecord, event: Any) -> None:
        """Buffer one of the agent's own content events in the turn's ring."""
        if isinstance(event, TextDelta):
            self._buffer(record, "text_delta", {"text": event.text})
        elif isinstance(event, TextComplete):
            self._buffer(record, "text", {"text": event.text})
        elif isinstance(event, ToolUse):
            if is_spawn_tool(event.tool_name):
                record.spawn_ids.add(event.tool_use_id)
            self._buffer(record, "tool_use", {
                "tool_use_id": event.tool_use_id,
                "tool_name": event.tool_name,
                "tool_input": event.tool_input,
            })
        elif isinstance(event, ToolResult):
            self._buffer(record, "tool_result", {
                "tool_use_id": event.tool_use_id,
                "output_excerpt": _excerpt(event.output, 800),
                "is_error": event.is_error,
            })
        elif isinstance(event, PermissionRequest):
            self._buffer(record, "permission_request", {
                "request_id": event.request_id,
                "tool_name": event.tool_name,
                "tool_input": event.tool_input,
            })
        elif isinstance(event, PermissionResolved):
            self._buffer(record, "permission_resolved", {
                "request_id": event.request_id,
                "decision": event.decision,
                "responder": event.responder,
                "message": event.message,
            })

    def _tap(self, record: _TurnRecord) -> Callable[[], None]:
        """Observe the session's events outside our own ``pool.send()``
        (``pool.tap_events``); returns the remover."""
        def noop() -> None:
            return None

        tap = getattr(self._pool, "tap_events", None)
        if tap is None:
            return noop
        try:
            untap = tap(record.session_id, lambda ev, oot: self._observe(record, ev, oot))
        except Exception:  # noqa: BLE001
            logger.exception("tap_events failed for %s", record.session_id)
            return noop
        return untap if callable(untap) else noop

    def _observe(self, record: _TurnRecord, event: Any, out_of_turn: bool) -> None:
        """Tap callback: an event of the session that our ``_consume`` does
        not see — produced out of turn (background subagents, the harness's
        notification turns), or by another prompted turn while we follow."""
        if record.finished:
            return
        if not out_of_turn and record.phase == "agent":
            return  # our own pool.send(): _consume has it
        try:
            self._observe_event(record, event, out_of_turn)
        except Exception:  # noqa: BLE001 — never break the pool's broadcast
            logger.exception("runner failed to observe %s for turn %s", type(event).__name__, record.turn_id)
        finally:
            record.wake.set()

    def _observe_event(self, record: _TurnRecord, event: Any, out_of_turn: bool) -> None:
        record.last_session_event_at = time.monotonic()
        if isinstance(event, _SUBAGENT_EVENTS):
            self._note_subagent(record, event, in_turn=False)
            return
        if not out_of_turn:
            # Another prompted turn on the session while we follow: a notification
            # turn the CLI runs inside it is ours to count (it has no start marker).
            if isinstance(event, TextComplete) and event.text.strip():
                record.foreign_text = event.text
            elif isinstance(event, TurnComplete):
                if not event.prompted and record.subagents:
                    record.obs_seq += 1
                    record.notification_turns += 1
                    record.last_nt_end_seq = record.obs_seq
                    if record.foreign_text:
                        record.last_assistant_text = record.foreign_text
                record.foreign_text = None
            return
        if isinstance(event, UnpromptedTurnStarted):
            if not record.nt_open and self._notification_is_ours(record, event.text):
                record.nt_open = True
                self._buffer(record, "notification_turn", {"text": _excerpt(event.text, 600)})
            return
        if not record.nt_open:
            return  # out-of-turn noise of another turn
        sm = self._pool.get(record.session_id)
        if sm is not None and hasattr(sm, "pending_permission_ids"):
            try:
                ids = sm.pending_permission_ids()
                record.pending_permission_ids = tuple(ids) if isinstance(ids, (list, tuple, set)) else ()
            except Exception:  # noqa: BLE001
                pass
        if isinstance(event, TurnComplete):
            record.nt_open = False
            record.pending_permission_ids = ()
            record.cost += event.cost or 0.0
            record.turns += event.num_turns or 0
            record.obs_seq += 1
            record.notification_turns += 1
            record.last_nt_end_seq = record.obs_seq
            self._buffer(record, "notification_turn_end", {"is_error": event.is_error})
            return
        if isinstance(event, TextComplete) and event.text.strip():
            record.last_assistant_text = event.text
        self._buffer_content(record, event)

    async def _wait_out_of_turn_idle(self, session_id: str) -> None:
        fn = getattr(self._pool, "wait_out_of_turn_idle", None)
        if fn is None:
            return
        try:
            res = fn(session_id)
            if inspect.isawaitable(res):
                await res
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            logger.debug("wait_out_of_turn_idle failed for %s", session_id, exc_info=True)

    async def _follow_subagents(self, record: _TurnRecord) -> None:
        """Keep the turn open while subagents it started still run.

        With CLI 2.1.292 every Agent call runs in the background: the
        delegated prompt's turn ends right after launching them ("I started 3
        agents…") and the real conclusion comes later, in turns the CLI
        starts on its own (``<task-notification>``).  Those travel only on
        the pool's out-of-turn path, which the runner observes through
        :meth:`_tap`.  The turn ends when none of its subagents is running,
        no notification turn of it is running, and a notification turn ended
        after the last subagent finished — unless the session says another
        is still owed (``awaits_unprompted_turn``).  If no report turn comes
        (a harness without them, or batched notifications), it ends
        ``settle_grace`` after the session's last event.  The no-progress
        timeout keeps counting: subagent events and notification turns are
        progress, silence is not."""
        if record.cancel_requested:
            return
        self._reconcile(record)
        if not self._needs_follow(record):
            return
        record.phase = "subagents"
        record.last_session_event_at = time.monotonic()
        logger.info(
            "Turn %s on %s: prompt done, following %d running subagent(s)",
            record.turn_id, record.session_id, len(self._running(record)),
        )
        tick = max(0.02, min(1.0, self._settle_grace / 4))
        while not record.cancel_requested:
            if self._pool.get(record.session_id) is None:
                running = self._running(record)
                if running:
                    raise RuntimeError(f"session closed while {len(running)} subagent(s) were still running")
                return
            self._reconcile(record)
            if not record.nt_open and not self._running(record):
                reported = record.notification_turns > 0 and record.last_nt_end_seq > record.last_finish_seq
                if reported and not self._expects_unprompted_turn(record):
                    # Whatever the pool still has queued for the session reaches us first.
                    seen = (record.obs_seq, record.next_seq)
                    await self._wait_out_of_turn_idle(record.session_id)
                    if (record.obs_seq, record.next_seq) == seen and not record.nt_open:
                        return
                    continue
                if time.monotonic() - record.last_session_event_at >= self._settle_grace:
                    return
            record.wake.clear()
            try:
                await asyncio.wait_for(record.wake.wait(), timeout=tick)
            except asyncio.TimeoutError:
                pass

    async def _drive(
        self,
        record: _TurnRecord,
        message: str,
        idle_timeout: float,
    ) -> None:
        """Task body. Iterate ``pool.send``, buffer events, follow the turn's
        background subagents, push Notification.

        Always finalises in a ``try/except/finally`` so a Notification is
        emitted exactly once per turn — whether the turn succeeded, failed,
        timed out, or got cancelled.  Without this, a cancelled turn would
        leak into the runner's records forever (the queue would never see
        a closing notification).
        """
        sm = self._pool.get(record.session_id)
        if sm is None:
            self._finalise(record, status="failed", error=f"session {record.session_id} not in pool")
            return

        async def _consume() -> None:
            async for event in self._pool.send(record.session_id, message):
                # Track pending permissions for the prompt-builder snapshot
                if hasattr(sm, "pending_permission_ids"):
                    record.pending_permission_ids = tuple(sm.pending_permission_ids())

                if isinstance(event, TextComplete):
                    record.last_assistant_text = event.text
                if isinstance(event, _SUBAGENT_EVENTS):
                    # Session activity either way (also another turn's agent).
                    record.last_progress_at = time.monotonic()
                    self._note_subagent(record, event, in_turn=True)
                elif isinstance(event, TurnComplete):
                    if event.prompted:
                        record.cost = event.cost or 0.0
                        record.turns = event.num_turns or 0
                    elif record.last_finish_seq:
                        # A notification turn the CLI ran inside our send,
                        # after one of our subagents ended.
                        record.obs_seq += 1
                        record.notification_turns += 1
                        record.last_nt_end_seq = record.obs_seq
                else:
                    self._buffer_content(record, event)

        async def _consume_with_retry() -> None:
            """Run _consume; if it raises TurnAbandoned, interrupt the SDK
            and retry once.  Single retry only — repeated abandonment means
            the network path is durably broken, not a transient blip."""
            try:
                await _consume()
            except TurnAbandoned as exc:
                logger.warning(
                    "Turn %s abandoned after %.0fs (no SDK messages); retrying once",
                    record.turn_id, exc.elapsed_seconds,
                )
                self._buffer(record, "retry", {
                    "reason": "abandoned",
                    "elapsed_seconds": exc.elapsed_seconds,
                })
                await self._tell_tabs(record.session_id, {
                    "type": "status", "status": "retrying",
                    "detail": f"upstream silent for {exc.elapsed_seconds:.0f}s, retrying",
                })
                # Interrupt the wedged SDK turn so the bundled `claude`
                # subprocess isn't left stuck on the original query.
                try:
                    try:
                        await self._pool.interrupt(record.session_id)
                    except Exception:  # noqa: BLE001
                        logger.exception("Failed to interrupt abandoned turn %s", record.turn_id)
                    # Brief pause so the SDK can settle before the retry.
                    await asyncio.sleep(1.0)
                except BaseException as gap:
                    # No pool.send() runs between the attempts: announce the end here.
                    announce_gap = getattr(self._pool, "announce_turn_aborted", None)
                    if announce_gap is not None:
                        announce_gap(record.session_id, gap)
                    raise
                await _consume()

        async def _work() -> None:
            await _consume_with_retry()
            await self._follow_subagents(record)

        untap = self._tap(record)
        # The try/except below sets a specific status; the outer finally is a
        # safety net for the case where the task is cancelled BEFORE the body
        # runs at all (asyncio can do that — cancel-before-start) so _finalise
        # would otherwise never run and a Notification would leak.
        try:
            try:
                await self._run_watched(record, _work(), idle_timeout)
            except _TurnTimedOut as exc:
                # Following: the delegated prompt's turn already ended (and was
                # announced) — only its subagents / a notification turn remain.
                following = record.phase != "agent"
                busy = not following or record.nt_open
                stopped = await self._stop_subagents(record)
                record.stopped_subagents += stopped
                if busy:
                    try:
                        await self._pool.interrupt(record.session_id)
                    except Exception:  # noqa: BLE001
                        logger.exception("Failed to interrupt session %s after timeout", record.session_id)
                reason = exc.reason
                if following:
                    reason += " while waiting for its subagents"
                if stopped:
                    reason += f"; stopped {stopped} subagent(s)"
                self._finalise(record, status="timeout", error=reason)
                if not following:
                    # pool.send() saw a cancellation ("interrupted"); devices hear it failed.
                    # (A notification turn we interrupt is announced by the pool itself.)
                    self._announce_failed(record.session_id, f"Stopped: {exc.reason}")
                # The cancelled send() emits no turn_complete: end the turn in every open
                # tab (as the chat route's own interrupt does) and say why.
                # Reason first (an error notice that ends the turn), then the
                # status every client already knows (ends it on older clients).
                await self._tell_tabs(record.session_id, {
                    "type": "error", "error": "turn_timeout",
                    "detail": f"The orchestrator stopped this turn: {reason}.",
                })
                if busy:
                    await self._tell_tabs(record.session_id, {"type": "status", "status": "interrupted"})
            except asyncio.CancelledError:
                error = "cancelled by orchestrator"
                if record.stopped_subagents:
                    error += f"; stopped {record.stopped_subagents} subagent(s)"
                busy = record.phase == "agent" or record.nt_open
                self._finalise(record, status="cancelled", error=error)
                if busy:
                    await self._tell_tabs(record.session_id, {"type": "status", "status": "interrupted"})
                raise
            except TurnAbandoned as exc:
                # Retry already happened inside _consume_with_retry and
                # also failed — give up and surface the failure.
                logger.error("Turn %s abandoned twice (%.0fs); giving up", record.turn_id, exc.elapsed_seconds)
                self._finalise(record, status="failed", error=f"upstream wedged after retry: {exc}")
                # pool.send() leaves an abandoned turn unannounced (it may be retried).
                self._announce_failed(record.session_id, "Upstream did not respond after retry")
                await self._tell_tabs(record.session_id, {
                    "type": "error", "error": "upstream_wedged",
                    "detail": (
                        f"Upstream did not respond after retry "
                        f"({exc.elapsed_seconds:.0f}s). Try again in a moment."
                    ),
                })
            except Exception as exc:  # noqa: BLE001
                logger.exception("BackgroundAgentRunner._drive failed for turn %s", record.turn_id)
                self._finalise(record, status="failed", error=str(exc))
                await self._tell_tabs(record.session_id, {
                    "type": "error", "error": "send_failed", "detail": str(exc),
                })
            else:
                if record.cancel_requested:
                    error = "cancelled by orchestrator"
                    if record.stopped_subagents:
                        error += f"; stopped {record.stopped_subagents} subagent(s)"
                    self._finalise(record, status="cancelled", error=error)
                else:
                    self._finalise(record, status="succeeded", error=None)
        finally:
            untap()
            # Backstop: if the task was cancelled before the body executed (or
            # any other path missed _finalise), still emit a notification.
            if not record.finished:
                self._finalise(record, status="cancelled", error="cancelled before start")

    async def _run_watched(
        self,
        record: _TurnRecord,
        coro: Awaitable[None],
        idle_timeout: float,
    ) -> None:
        """Await ``coro``; raise :class:`_TurnTimedOut` (after cancelling it) when
        the agent has produced nothing for ``idle_timeout`` seconds, or the turn
        passed ``max_turn_seconds``.  A turn waiting on a permission answer is
        waiting on a person, so it is never idle."""
        task = asyncio.ensure_future(coro)
        tick = max(0.05, min(15.0, idle_timeout / 4))
        try:
            while True:
                done, _ = await asyncio.wait({task}, timeout=tick)
                if done:
                    task.result()
                    return
                now = time.monotonic()
                reason: str | None = None
                if self._max_turn_seconds is not None and now - record.started_at > self._max_turn_seconds:
                    reason = f"turn exceeded {self._max_turn_seconds:g}s"
                elif record.pending_permission_ids:
                    record.last_progress_at = now
                elif now - record.last_progress_at > idle_timeout:
                    reason = f"no progress for {idle_timeout:g}s"
                if reason is not None:
                    await _cancel_and_wait(task)
                    raise _TurnTimedOut(reason)
        finally:
            # Our own cancellation (orchestrator cancel): stop the send too, and
            # let it finish unwinding so pool.send() announces the end first.
            if not task.done():
                await _cancel_and_wait(task)

    async def _tell_tabs(self, session_id: str, frame: dict[str, Any]) -> None:
        """Send ``frame`` to every chat tab watching ``session_id``.

        ``pool.send()`` only broadcasts events it streams, so a turn the runner
        ends (timeout, cancel, failure) would otherwise leave every open tab
        showing it as running.  Best effort: never raises."""
        broadcast = getattr(self._pool, "broadcast_session", None)
        if broadcast is None:
            return
        try:
            await broadcast(session_id, frame)
        except Exception:  # noqa: BLE001
            logger.exception("Failed to tell the tabs of %s about %s", session_id, frame.get("type"))

    def _announce_failed(self, session_id: str, error: str) -> None:
        """``agent_turn_finished{status: error}`` for the failures ``pool.send`` can't see."""
        try:
            self._pool.announce_turn_finished(session_id, status="error", error=error)
        except Exception:  # noqa: BLE001
            logger.exception("Failed to announce the failed turn of %s", session_id)

    def _prune_finished(self) -> None:
        """Forget old finished turns (the runner lives as long as its conversation):
        keep at most ``_MAX_FINISHED_TURNS``, never a session's latest turn."""
        finished = [r for r in self._turns.values() if r.finished]
        excess = len(finished) - _MAX_FINISHED_TURNS
        if excess <= 0:
            return
        latest = set(self._latest_by_session.values())
        for r in finished:  # dict order = spawn order, oldest first
            if excess <= 0:
                break
            if r.turn_id in latest:
                continue
            del self._turns[r.turn_id]
            excess -= 1

    def _finalise(
        self,
        record: _TurnRecord,
        *,
        status: str,
        error: str | None,
    ) -> None:
        """Mark a turn finished and push its Notification. Idempotent."""
        if record.finished:
            return
        record.finished = True
        record.status = status
        record.error = error
        self._prune_finished()
        duration = time.monotonic() - record.started_at
        notification = Notification(
            notification_id=str(uuid.uuid4()),
            turn_id=record.turn_id,
            session_id=record.session_id,
            session_title=record.session_title,
            origin_tool_use_id=record.origin_tool_use_id,
            status=status,
            cost=record.cost,
            turns=record.turns,
            duration_seconds=duration,
            error=error,
            preview=_one_line(record.last_assistant_text, 240) or None,
            subagents=len(self._top_level(record)),
        )
        if record.phase != "agent":
            logger.info(
                "Turn %s on %s finished %s after %d subagent(s) and %d notification turn(s)",
                record.turn_id, record.session_id, status, notification.subagents, record.notification_turns,
            )
        try:
            self._notifications.push(notification)
        except Exception:  # noqa: BLE001
            logger.exception("Failed to push notification for turn %s", record.turn_id)


# ---------------------------------------------------------------------------
# AgentRuntimes
# ---------------------------------------------------------------------------


class AgentRuntimes:
    """The runner + notification queue of each orchestrator conversation.

    Keyed by the conversation's ``jsonl_id`` and held by the pool, so they
    outlive :class:`OrchestratorSession` objects.  Closing the orchestrator
    tab, ``switch_conversation``, a voice rebuild, or a crash/restart of the
    orchestrator conversation never stops the agent turns it delegated: they
    run to completion, and their notifications wait in the queue until that
    conversation is open again (the next session for the same ``jsonl_id``
    gets the same runner and queue, in-flight turns included).
    """

    def __init__(self) -> None:
        self._by_conversation: dict[str, tuple[BackgroundAgentRunner, NotificationQueue]] = {}

    def acquire(
        self, conversation_id: str, pool: SessionPool, store: SessionStore,
    ) -> tuple[BackgroundAgentRunner, NotificationQueue]:
        rt = self._by_conversation.get(conversation_id)
        if rt is None:
            queue = NotificationQueue()
            rt = (BackgroundAgentRunner(pool, store, queue), queue)
            self._by_conversation[conversation_id] = rt
        return rt

    def get(self, conversation_id: str) -> tuple[BackgroundAgentRunner, NotificationQueue] | None:
        return self._by_conversation.get(conversation_id)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _cancel_and_wait(task: asyncio.Future[Any]) -> None:
    task.cancel()
    try:
        await task
    except (asyncio.CancelledError, Exception):  # noqa: BLE001 — the cancellation (or its fallout) is expected
        pass


def _phase(record: _TurnRecord) -> str:
    """The turn's phase as shown to the orchestrator."""
    if record.finished:
        return "finished"
    if record.phase == "agent":
        return "agent"
    return "notification_turn" if record.nt_open else "waiting_for_subagents"


def _subagent_row(row: dict[str, Any]) -> dict[str, Any]:
    out = {k: row.get(k) for k in ("tool_use_id", "agent_type", "description", "status", "activity")}
    if row.get("parent_tool_use_id"):
        out["parent_tool_use_id"] = row["parent_tool_use_id"]
    if row.get("summary"):
        out["summary"] = row["summary"]
    return out


def _one_line(text: str | None, limit: int) -> str:
    """Collapse whitespace and trim — a notification's one-line preview."""
    flat = " ".join((text or "").split())
    return flat if len(flat) <= limit else flat[: limit - 1].rstrip() + "…"


def _excerpt(text: str, limit: int) -> str:
    """Trim a tool output for buffering — full text is in the agent's JSONL."""
    if not text:
        return ""
    if len(text) <= limit:
        return text
    return text[:limit] + f"... [+{len(text) - limit} chars]"


__all__ = [
    "AgentRuntimes",
    "AgentTurnEvent",
    "AgentTurnHandle",
    "BackgroundAgentRunner",
    "Notification",
    "NotificationQueue",
]
