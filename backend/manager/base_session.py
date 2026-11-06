"""BaseSessionManager — abstract interface for a single agent session.

Both :class:`manager.claude.session.ClaudeSessionManager` and
:class:`manager.qwen.session.QwenSessionManager` implement this contract.
The pool, the WebSocket chat handler, and the orchestrator all interact
with sessions through this interface — they should never see provider
specifics.

Subclasses own:
- Spawning and reaping their provider's subprocess
- Translating native streaming events into the normalized
  :mod:`manager.types` Event hierarchy
- Permission gating (for providers that support a popup mechanism)
- Any provider-specific quirks (Claude's stall watchdog, Qwen's
  one-shot-per-turn lifecycle, etc.)

What the base class provides:
- The dual-ID model (stable ``local_id`` + provider-supplied
  ``provider_session_id``)
- Permission state (pending Futures, gated-tool set)
- Status tracking and the common property surface used by the pool
- A standard async-context-manager protocol
"""

from __future__ import annotations

import asyncio
import uuid
from abc import ABC, abstractmethod
from collections.abc import AsyncIterator, Callable
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from .config import ManagerConfig
from .types import (
    Event,
    PermissionRequest,
    PermissionResolved,
    QuestionRequest,
    QuestionResolved,
    SessionStatus,
    TerminationReason,
)

if TYPE_CHECKING:
    pass


@dataclass
class PendingPermission:
    """One unanswered ``PermissionRequest``: the Future the gating callback
    is awaiting plus the request itself.

    The request is kept because ``permission_request`` is a live-only frame —
    it is never written to the session's JSONL — so a client that attaches
    after it was emitted (another device, a reopened view, a replay that
    overflowed) can only learn about it from the session's own state.
    :meth:`BaseSessionManager.pending_permissions` is what
    ``session_started`` advertises (spec 12 PM-6).
    """

    future: "asyncio.Future[tuple[str, str | None, str]]"
    tool_name: str = ""
    tool_input: dict = field(default_factory=dict)


@dataclass
class QuestionAnswer:
    """What the user chose, in the shape the CLI's ``AskUserQuestion`` reads.

    ``answers`` is keyed by the question's **text** (that is how the CLI looks
    them up) and holds an option label, or a list of labels for a multi-select;
    a label the question never offered is allowed — it is how "Other" free text
    is attributed to its question. ``response`` is freeform text typed instead
    of choosing anything.
    """

    answers: dict = field(default_factory=dict)
    response: str | None = None
    annotations: dict = field(default_factory=dict)
    responder: str = "user"
    message: str | None = None

    def answered(self) -> bool:
        return bool(self.answers) or bool((self.response or "").strip()) or bool(self.annotations)


@dataclass
class PendingQuestion:
    """One unanswered ``QuestionRequest``: the Future the ``AskUserQuestion``
    gate is awaiting plus the questions themselves.

    Kept for the same reason as :class:`PendingPermission` — the request frame
    is live-only, so a client that attaches later can only learn about it from
    :meth:`BaseSessionManager.pending_questions` (spec 12 QU-6).
    """

    future: "asyncio.Future[QuestionAnswer]"
    questions: list = field(default_factory=list)
    tool_use_id: str | None = None


class TurnAbandoned(Exception):
    """Raised when a turn produced no events for so long the upstream
    request is considered wedged.  Subclassed by each provider to add
    provider-specific context (Claude: ``SessionAbandoned``; Qwen:
    ``QwenAbandoned``).

    Catch ``TurnAbandoned`` to handle both providers uniformly.
    """

    def __init__(self, elapsed_seconds: float) -> None:
        super().__init__(
            f"Turn produced no events after {elapsed_seconds:.0f}s "
            "(upstream request appears wedged)"
        )
        self.elapsed_seconds = elapsed_seconds


class SessionDeadError(RuntimeError):
    """Raised when a caller tries to use a session whose underlying
    receive loop / subprocess has exited.

    Pool's ``_drive_turn`` catches this distinctly from generic errors
    and translates it into a typed ``SessionTerminated`` broadcast so
    the client can offer auto-resume rather than displaying an opaque
    "send_failed" string.

    Carries a :class:`TerminationReason` so the pool layer can surface
    the same reason it would have broadcast on its own (the reaper has
    the same signal source — the receive loop's exit path).
    """

    def __init__(
        self,
        reason: TerminationReason,
        detail: str | None = None,
    ) -> None:
        super().__init__(
            detail or f"Session is no longer usable ({reason.value})"
        )
        self.reason = reason
        self.detail = detail


class SubagentNotFound(LookupError):
    """``stop_subagent`` for a tool_use_id the session does not know."""


class SubagentNotRunning(RuntimeError):
    """``stop_subagent`` for a subagent that already finished."""


class BaseSessionManager(ABC):
    """Abstract base for all session managers.

    Concrete subclasses must implement:
        - :meth:`_run_lifecycle` — connect, idle-wait on ``_stop_requested``, disconnect
        - :meth:`send` — stream a turn's events
        - :meth:`interrupt` — cancel the in-flight turn
        - :meth:`provider_name` — provider identifier

    Optional overrides:
        - :meth:`compact` — provider-specific compaction (Claude only by default)
        - :meth:`command` — slash command (Claude only by default)
        - :meth:`subprocess_pid` property — for the pool's orphan reaper
    """

    def __init__(
        self,
        session_id: str | None = None,
        *,
        local_id: str | None = None,
        fork: bool = False,
        config: ManagerConfig | None = None,
    ) -> None:
        self._config = config or ManagerConfig.load()
        self._local_id: str = local_id or str(uuid.uuid4())
        self._resume_id: str | None = session_id  # provider-side session id to resume
        self._fork: bool = fork

        # Provider-supplied session id. Distinct from local_id: the latter
        # is stable across reconnects/restarts; this one is whatever the
        # underlying CLI hands us (Claude SDK session id, Qwen session id).
        self._provider_session_id: str | None = None

        self._status: SessionStatus = SessionStatus.DISCONNECTED
        self._cost: float = 0.0
        self._turns: int = 0

        # Lifecycle task — owns connect and disconnect from the same task.
        self._lifecycle_task: asyncio.Task[None] | None = None
        self._connect_done: asyncio.Event = asyncio.Event()
        self._connect_error: BaseException | None = None
        self._stop_requested: asyncio.Event = asyncio.Event()

        # Optional callbacks the pool installs so it can track per-turn
        # subprocess PIDs without polling.  Qwen spawns a fresh subprocess
        # for every turn; Claude has one persistent PID for the session.
        # Either provider can register/deregister PIDs through these hooks
        # so the pool's orphan reaper sees them.
        self._on_pid_spawn: "Callable[[int], None] | None" = None
        self._on_pid_exit: "Callable[[int], None] | None" = None

        # Permission gating shared state. ``_event_inbox`` is set by send()
        # so the permission callback can inject events into the live stream;
        # ``_pending_permissions`` holds the asyncio.Future per request plus
        # the request itself, so a client attaching later can be told what is
        # still waiting for an answer (``pending_permissions``, spec 12 PM-6).
        self._gated_tools: set[str] = set()
        self._pending_permissions: dict[str, PendingPermission] = {}
        # Unanswered AskUserQuestion calls (spec 12 QU-1): same lifecycle as a
        # permission — the harness blocks on the future until someone answers.
        self._pending_questions: dict[str, PendingQuestion] = {}
        self._event_inbox: asyncio.Queue[Event] | None = None

        # Out-of-turn events (spec 12 §4.8): what the harness emits while no
        # ``send()`` is consuming — a background subagent still working after
        # the parent turn ended, a turn the CLI started on its own.  The pool
        # installs a sink that broadcasts them; harnesses that never produce
        # such events ignore it.
        self._out_of_turn_sink: "Callable[[object], None] | None" = None

    # ------------------------------------------------------------------
    # Abstract / required interface
    # ------------------------------------------------------------------

    @property
    @abstractmethod
    def provider_name(self) -> str:
        """Canonical provider identifier — registered harness id."""

    @abstractmethod
    async def _run_lifecycle(self) -> None:
        """Subclass-owned body of the lifecycle task.

        Responsibilities:
        1. Establish the underlying connection (spawn subprocess, exchange
           init handshake, capture provider session id, etc.).
        2. If connection succeeds, set ``self._status = SessionStatus.IDLE``
           and call ``self._connect_done.set()``.
        3. If connection fails, set ``self._connect_error`` and call
           ``self._connect_done.set()``, then return.
        4. Await ``self._stop_requested.wait()`` (idle).
        5. In a ``finally``: clean up the connection / subprocess and set
           ``self._status = SessionStatus.DISCONNECTED``.
        """

    @abstractmethod
    async def send(self, prompt: str) -> AsyncIterator[Event]:
        """Send a prompt and stream typed events back."""

    @abstractmethod
    async def interrupt(self) -> None:
        """Stop the in-flight turn at the provider level."""

    #: The harness can take messages into a running turn (:meth:`steer`).
    supports_steering: bool = False

    async def steer(self, text: str) -> bool:
        """Deliver *text* into the turn the active ``send()`` is running
        (spec 12 I-16): the harness hands it to the model mid-turn, at its
        next model request, instead of after the turn.  Returns ``False``
        when the harness cannot (the default) or no turn of ours is running
        — the caller then queues the message behind the turn."""
        return False

    # ------------------------------------------------------------------
    # Async context manager
    # ------------------------------------------------------------------

    async def __aenter__(self):
        await self.start()
        return self

    async def __aexit__(self, *exc: object) -> None:
        await self.stop()

    # ------------------------------------------------------------------
    # Lifecycle — uniform across providers
    # ------------------------------------------------------------------

    async def start(self) -> str:
        """Connect to the underlying agent and return the stable local id.

        The lifecycle task is created here and runs until :meth:`stop`.
        Implementations that need provider-specific pre-flight checks
        (e.g. an SSH reachability probe) should override
        :meth:`_pre_start_check` rather than this method.
        """
        if self._lifecycle_task is not None:
            raise RuntimeError(f"{type(self).__name__}.start() called twice")

        await self._pre_start_check()

        self._lifecycle_task = asyncio.create_task(
            self._lifecycle(), name=f"sm-lifecycle-{self._local_id}",
        )
        await self._connect_done.wait()
        if self._connect_error is not None:
            self._lifecycle_task = None
            err = self._connect_error
            self._connect_error = None
            raise err
        return self._local_id

    async def _pre_start_check(self) -> None:
        """Synchronous-ish pre-flight check. Override to raise before the
        lifecycle task is spawned (e.g. for SSH reachability)."""
        return None

    async def _lifecycle(self) -> None:
        """Wrapper around the subclass-provided ``_run_lifecycle``.

        Existence of this thin wrapper means the lifecycle-task naming and
        creation logic only lives in one place; subclasses focus on the
        actual connect/idle/disconnect dance.
        """
        await self._run_lifecycle()

    async def stop(self) -> None:
        """Request disconnect and wait for the lifecycle task to finish.

        Safe to call from any task. The lifecycle task is shielded so a
        cancellation in the caller doesn't propagate into the in-flight
        disconnect — that would tear down the SDK / subprocess in a way
        that leaks file descriptors and pins the event loop.
        """
        if self._lifecycle_task is None:
            self._status = SessionStatus.DISCONNECTED
            return
        self._stop_requested.set()
        task = self._lifecycle_task
        # Clear the slot before awaiting so concurrent stop() callers
        # short-circuit instead of racing on the same Task reference.
        self._lifecycle_task = None
        try:
            await asyncio.shield(task)
        except asyncio.CancelledError:
            raise
        except Exception:
            import logging
            logging.getLogger(__name__).exception(
                "Lifecycle task for session %s exited with error", self._local_id,
            )

    # ------------------------------------------------------------------
    # Optional capabilities (defaults: not supported)
    # ------------------------------------------------------------------

    async def compact(self) -> AsyncIterator[Event]:
        """Trigger conversation compaction. Default: not supported.

        Subclasses that support compaction should override this.
        """
        if False:  # pragma: no cover  — keeps this an async generator
            yield  # type: ignore[unreachable]
        raise NotImplementedError(
            f"{type(self).__name__} does not support compaction",
        )

    async def command(self, slash_command: str) -> AsyncIterator[Event]:
        """Send an arbitrary slash command. Default: delegate to ``send``.

        Subclasses that have a dedicated command channel may override.
        """
        async for event in self.send(slash_command):
            yield event

    # ------------------------------------------------------------------
    # Subagents and out-of-turn events (spec 12 §4.8)
    # ------------------------------------------------------------------

    def set_out_of_turn_sink(self, sink: "Callable[[object], None] | None") -> None:
        """Install the pool's receiver for events produced outside ``send()``.

        Called synchronously with each item (a harness-specific wrapper such
        as Claude's ``SequencedEvent``, or a bare :class:`Event`) in dispatch
        order; it must not block or raise.
        """
        self._out_of_turn_sink = sink

    def _emit_out_of_turn(self, item: object) -> None:
        sink = self._out_of_turn_sink
        if sink is None:
            return
        try:
            sink(item)
        except Exception:  # noqa: BLE001 — a broken sink must not kill the receive loop
            import logging
            logging.getLogger(__name__).exception(
                "out-of-turn sink failed for session %s", self._local_id,
            )

    def subagent_snapshot(self) -> list[dict]:
        """Live subagents of this session as ``SubagentSummary`` dicts (spawn
        order).  History on disk is merged in by the caller
        (:func:`manager.subagents.merge_summaries`).  Default: none."""
        return []

    def awaits_unprompted_turn(self) -> bool:
        """True while the harness owes a turn of its own: a background task
        ended and the turn that reports on it (Claude: ``<task-notification>``)
        has not run yet, or is running.  The orchestrator runner keeps a
        delegated turn open until it ran.  Default: never."""
        return False

    async def stop_subagent(self, tool_use_id: str) -> None:
        """Ask the harness to stop one running subagent (spec 12 SA-9).

        The outcome arrives as the normal ``subagent_finished{stopped}``.
        Raises :class:`NotImplementedError` when the harness cannot stop a
        single subagent, :class:`SubagentNotFound` / :class:`SubagentNotRunning`
        otherwise.
        """
        raise NotImplementedError(f"{type(self).__name__} cannot stop a subagent")

    # ------------------------------------------------------------------
    # Permission gating — shared resolution; injection is subclass-specific
    # ------------------------------------------------------------------

    def resolve_permission(
        self,
        request_id: str,
        decision: str,
        *,
        message: str | None = None,
        responder: str = "user",
    ) -> bool:
        """Answer a pending PermissionRequest. First call wins."""
        pending = self._pending_permissions.get(request_id)
        if pending is None or pending.future.done():
            return False
        pending.future.set_result((decision, message, responder))
        return True

    def pending_permission_ids(self) -> list[str]:
        return [rid for rid, p in self._pending_permissions.items() if not p.future.done()]

    def pending_permissions(self) -> list[dict]:
        """Every unanswered permission request, oldest first (spec 12 PM-6).

        Same shape as the ``permission_request`` frame minus the envelope, so
        a client can seed the card it never received::

            [{"request_id": ..., "tool_name": ..., "tool_input": {...}}, ...]
        """
        return [
            {
                "request_id": rid,
                "tool_name": p.tool_name,
                "tool_input": dict(p.tool_input),
            }
            for rid, p in self._pending_permissions.items()
            if not p.future.done()
        ]

    def _inject_event(self, event: Event) -> None:
        """Push an out-of-band event into the active ``send()`` stream.

        Default implementation enqueues directly on the inbox.  Subclasses
        that track replay/sequencing (e.g. Claude's persistent receive
        loop) override this to assign a seq, append to the replay ring,
        and wrap the event before queueing.

        Safe to call when there is no active stream — the call is a no-op
        in that case so callers don't need to None-check.
        """
        inbox = self._event_inbox
        if inbox is None:
            return
        try:
            inbox.put_nowait(event)
        except asyncio.QueueFull:
            import logging
            logging.getLogger(__name__).warning(
                "send() inbox full for session %s; injected event dropped",
                self._local_id,
            )

    async def _emit_permission_request(
        self,
        tool_name: str,
        tool_input: dict,
    ) -> tuple[str, str | None]:
        """Helper for subclasses: emit a ``PermissionRequest`` into the
        active stream and await the resolution. Returns ``(decision, message)``.

        Auto-allows (returns ``("allow", None)``) when there's no active
        stream — a permission popup without a UI to display it would deadlock.
        """
        if self._event_inbox is None:
            return "allow", None

        request_id = str(uuid.uuid4())
        loop = asyncio.get_running_loop()
        future: asyncio.Future[tuple[str, str | None, str]] = loop.create_future()
        self._pending_permissions[request_id] = PendingPermission(
            future=future, tool_name=tool_name, tool_input=dict(tool_input),
        )

        self._inject_event(PermissionRequest(
            request_id=request_id,
            tool_name=tool_name,
            tool_input=dict(tool_input),
        ))

        try:
            decision, message, responder = await future
        finally:
            self._pending_permissions.pop(request_id, None)

        self._inject_event(PermissionResolved(
            request_id=request_id,
            decision=decision,
            responder=responder,
            message=message,
        ))
        return decision, message

    def _drain_pending_permissions(self) -> None:
        """Resolve every still-pending permission as 'deny' (stream ended).

        Called at the end of a send() so the SDK doesn't leak a future
        nothing will ever resolve.
        """
        for rid, pending in list(self._pending_permissions.items()):
            if not pending.future.done():
                pending.future.set_result(("deny", "stream ended", "system"))
            self._pending_permissions.pop(rid, None)

    # ------------------------------------------------------------------
    # Questions (``AskUserQuestion``, spec 12 QU-1..QU-6)
    # ------------------------------------------------------------------

    def resolve_question(
        self,
        request_id: str,
        *,
        answers: dict | None = None,
        response: str | None = None,
        annotations: dict | None = None,
        responder: str = "user",
    ) -> bool:
        """Answer a pending :class:`QuestionRequest`. First call wins.

        ``response`` and ``answers`` are not independent on the wire the CLI
        reads: a non-empty ``response`` makes it ignore ``answers`` entirely.
        So when both arrive (the user picked options *and* typed something) the
        typed text is demoted to a note on the first question, which the CLI
        renders alongside the selection instead of replacing it.
        """
        pending = self._pending_questions.get(request_id)
        if pending is None or pending.future.done():
            return False

        answers = dict(answers or {})
        annotations = {k: dict(v) for k, v in (annotations or {}).items() if isinstance(v, dict)}
        text = (response or "").strip() or None
        if text and answers:
            first = next(
                (q.get("question") for q in pending.questions if isinstance(q, dict) and q.get("question")),
                None,
            )
            if first:
                note = annotations.setdefault(str(first), {})
                note["notes"] = f"{note['notes']}\n{text}" if note.get("notes") else text
                text = None

        pending.future.set_result(QuestionAnswer(
            answers=answers, response=text, annotations=annotations, responder=responder,
        ))
        return True

    def pending_question_ids(self) -> list[str]:
        return [rid for rid, q in self._pending_questions.items() if not q.future.done()]

    def pending_questions(self) -> list[dict]:
        """Every unanswered question, oldest first (spec 12 QU-6).

        Same shape as the ``question_request`` frame minus the envelope, so a
        client that attached after it was asked can seed the picker it missed.
        """
        return [
            {
                "request_id": rid,
                "questions": [dict(q) for q in p.questions if isinstance(q, dict)],
                "tool_use_id": p.tool_use_id,
            }
            for rid, p in self._pending_questions.items()
            if not p.future.done()
        ]

    async def _emit_question_request(
        self,
        questions: list,
        *,
        tool_use_id: str | None = None,
    ) -> QuestionAnswer | None:
        """Emit a ``QuestionRequest`` into the active stream and await the
        answer. Returns ``None`` when nobody answered (the harness then lets
        the tool report that itself).

        No active stream → ``None`` immediately: a question nobody can see
        would block the turn forever.
        """
        if self._event_inbox is None:
            return None

        request_id = str(uuid.uuid4())
        loop = asyncio.get_running_loop()
        future: "asyncio.Future[QuestionAnswer]" = loop.create_future()
        rows = [dict(q) for q in (questions or []) if isinstance(q, dict)]
        self._pending_questions[request_id] = PendingQuestion(
            future=future, questions=rows, tool_use_id=tool_use_id,
        )

        self._inject_event(QuestionRequest(
            request_id=request_id, questions=rows, tool_use_id=tool_use_id,
        ))

        try:
            answer = await future
        finally:
            self._pending_questions.pop(request_id, None)

        self._inject_event(QuestionResolved(
            request_id=request_id,
            responder=answer.responder,
            answers=dict(answer.answers),
            response=answer.response,
            annotations=dict(answer.annotations),
            message=answer.message,
        ))
        return answer if answer.answered() else None

    def _drain_pending_questions(self) -> None:
        """Release every still-pending question as unanswered (stream ended)."""
        for rid, pending in list(self._pending_questions.items()):
            if not pending.future.done():
                pending.future.set_result(QuestionAnswer(
                    responder="system", message="stream ended",
                ))
            self._pending_questions.pop(rid, None)

    # ------------------------------------------------------------------
    # Read-only properties shared by every session
    # ------------------------------------------------------------------

    @property
    def local_id(self) -> str:
        """Stable local identifier (never changes)."""
        return self._local_id

    @property
    def session_id(self) -> str:
        """Alias for local_id — the stable identifier the pool keys on."""
        return self._local_id

    @property
    def sdk_session_id(self) -> str | None:
        """Provider-supplied session id (Claude SDK id, Qwen session id).

        Named for backward compatibility — both providers expose it under
        the same attribute so the pool can key on it interchangeably.
        """
        return self._provider_session_id

    @property
    def is_active(self) -> bool:
        """False only for a dead session.  ``INTERRUPTED`` is alive: it is the
        settled state after a stopped turn (Codex, Gemini and Qwen keep it until
        the next prompt), and the next ``send()`` works normally."""
        return self._status != SessionStatus.DISCONNECTED

    @property
    def status(self) -> SessionStatus:
        return self._status

    @property
    def cost(self) -> float:
        return self._cost

    @property
    def turns(self) -> int:
        return self._turns

    @property
    def is_resumed(self) -> bool:
        """True if this session was resumed from an existing one."""
        return self._resume_id is not None

    @property
    def subprocess_pid(self) -> int | None:
        """PID of the provider subprocess, if the implementation tracks it.

        Used by the pool's orphan reaper. Default: None (no tracking).
        Note: this only returns a value while a subprocess is actually
        running — for Qwen that means mid-turn.  Callers that need
        continuous tracking should register via :meth:`set_pid_callbacks`
        so they're notified at spawn and exit.
        """
        return None

    def set_pid_callbacks(
        self,
        on_spawn: Callable[[int], None] | None,
        on_exit: Callable[[int], None] | None,
    ) -> None:
        """Register callbacks the session invokes when a subprocess starts
        or exits.  The pool installs these so its orphan reaper can track
        Qwen's per-turn PIDs (and could track Claude's long-lived PID too
        if we ever needed to).

        Either callback may be ``None``; both are called best-effort and
        must not raise.  Implementations that don't fork subprocesses
        ignore the callbacks entirely.
        """
        self._on_pid_spawn = on_spawn
        self._on_pid_exit = on_exit
