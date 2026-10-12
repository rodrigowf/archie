"""QwenSessionManager — wraps a single Qwen Code conversation.

Unlike Claude Code, the bundled ``qwen`` CLI is one-shot per turn: it
reads stream-json from stdin, runs the agent loop, writes stream-json
to stdout, and exits.  Multi-turn conversations are stitched together
by passing ``--resume <session-id>`` on subsequent invocations.

This file implements the same :class:`BaseSessionManager` contract as
:class:`manager.claude.session.ClaudeSessionManager`, so the pool and
WebSocket layer don't need provider-specific code paths.

Event format: Qwen's ``--output-format stream-json`` emits Anthropic-style
events — ``system.init``, ``stream_event.{message_start, content_block_*}``,
``assistant`` (complete message), ``result``.  We translate them into the
normalized :mod:`manager.types` event hierarchy.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import signal
import uuid
from collections.abc import AsyncIterator
from pathlib import Path

from .._ssh import (
    kill_remote_tree,
    parse_remote_pid,
    RemoteCommand,
    RemoteHostUnreachableError,
    SshTarget,
    build_remote_argv,
    build_ssh_argv,
    probe_host_reachable,
    resolve_remote_cli_path,
)
from .._proc import process_tree, reap_descendants, signal_group
from ..base_session import BaseSessionManager, TurnAbandoned
from ..config import ManagerConfig
from ..memory_context import memory_instructions
from ..subagents import SubagentRegistry, is_spawn_tool, spawn_fields, usage_dict, wire_status
from . import run_settings as _run_settings
from ..types import (
    CompactComplete,
    Event,
    SessionStalled,
    SessionStatus,
    SubagentEvent,
    SubagentFinished,
    TextComplete,
    TextDelta,
    ThinkingComplete,
    ThinkingDelta,
    ToolResult,
    ToolUse,
    TurnComplete,
    UnpromptedTurnStarted,
)

logger = logging.getLogger(__name__)


# Same watchdog policy as Claude: warn after 2 min of silence, repeat every 60s.
_STALL_FIRST_NOTICE_S = 120.0
_STALL_REPEAT_INTERVAL_S = 60.0
# Abandoned-turn detection — produced zero events for this long → give up.
_TURN_ABANDON_S = 240.0



class QwenAbandoned(TurnAbandoned):
    """Raised when a Qwen turn produced no events for so long the request
    almost certainly never landed.

    Inherits :class:`manager.base_session.TurnAbandoned` so catch sites
    that want to handle both Claude and Qwen abandoned turns can do so
    with a single ``except TurnAbandoned`` clause.
    """


def _qwen_executable() -> str:
    """Resolve the path to the ``qwen`` CLI.

    Honors ``QWEN_CLI_PATH`` if set; otherwise relies on ``$PATH`` resolution.
    """
    return os.environ.get("QWEN_CLI_PATH", "qwen")


# ``--fork-session`` arrived in qwen-code 0.16.0; older CLIs reject it.
_FORK_SESSION_MIN_VERSION = (0, 16, 0)
# Seconds allowed for writing the per-run settings file on an SSH remote.
_REMOTE_SETTINGS_TIMEOUT_S = 10.0


def _parse_version(text: str | None) -> tuple[int, int, int] | None:
    """``"0.25.0"`` → ``(0, 25, 0)``; ``None`` for anything unparseable."""
    m = re.search(r"(\d+)\.(\d+)\.(\d+)", text or "")
    return (int(m.group(1)), int(m.group(2)), int(m.group(3))) if m else None


def _error_message(obj: dict) -> str | None:
    """Turn-level failure reason from a ``result`` line.

    0.2x puts it in ``error.message`` (API errors, loop detection, the
    ``--max-wall-time`` / tool-call budgets) and leaves ``result`` empty.
    """
    err = obj.get("error")
    if isinstance(err, dict):
        msg = err.get("message")
        if isinstance(msg, str) and msg.strip():
            return msg
    elif isinstance(err, str) and err.strip():
        return err
    subtype = obj.get("subtype")
    return subtype if isinstance(subtype, str) and subtype != "success" else None


#: qwen-code 0.25's default ``subagent_type`` when the call names none.
_DEFAULT_SUBAGENT_TYPE = "general-purpose"
#: Text of a background launch's tool result (and the task id it names).
_BG_LAUNCH_PREFIX = "Background agent launched"
_TASK_ID_RE = re.compile(r"task_id:\s*([A-Za-z0-9_.:-]+)")


def _notification_text(
    task_id: object, tool_use_id: str, status: str, description: str,
    result: str | None, usage: dict,
) -> str:
    """The ``<task-notification>`` Qwen records in the session JSONL for a
    finished background agent (its stream only carries a display line)."""
    lines = ["<task-notification>"]
    if task_id:
        lines.append(f"<task-id>{task_id}</task-id>")
    lines.append(f"<tool-use-id>{tool_use_id}</tool-use-id>")
    lines.append(f"<status>{status}</status>")
    lines.append(f'<summary>Agent "{description or "agent"}" {status or "finished"}.</summary>')
    if result:
        lines.append(f"<result>{result}</result>")
    if usage:
        lines.append("<usage>")
        for k in ("total_tokens", "tool_uses", "duration_ms"):
            if usage.get(k) is not None:
                lines.append(f"<{k}>{usage[k]}</{k}>")
        lines.append("</usage>")
    lines.append("</task-notification>")
    return "\n".join(lines)


class QwenSessionManager(BaseSessionManager):
    """Manage a single Qwen Code conversation.

    Because ``qwen`` is one-shot, the lifecycle here is much smaller than
    Claude's: ``start()`` just records that the session exists; each
    ``send()`` spawns a fresh subprocess for the turn.  Resume is handled
    transparently via ``--resume <session-id>`` after the first turn.
    """

    def __init__(
        self,
        session_id: str | None = None,
        *,
        local_id: str | None = None,
        fork: bool = False,
        config: ManagerConfig | None = None,
    ) -> None:
        super().__init__(
            session_id=session_id, local_id=local_id, fork=fork, config=config,
        )
        # The currently-running ``qwen`` subprocess for an in-flight turn.
        # None when idle.
        self._proc: asyncio.subprocess.Process | None = None
        # SSH sessions: the remote CLI's PID (announced on stdout before the
        # CLI starts) and target, so interrupt/kill can stop it over SSH.
        self._remote_pid: int | None = None
        self._remote_target: SshTarget | None = None
        # Reaps shell commands an interrupted turn left behind (see interrupt()).
        self._reaper_tasks: set[asyncio.Task] = set()
        # Optional handle to the reader task; used by the watchdog only.
        self._reader_task: asyncio.Task[None] | None = None
        # ``--fork-session`` goes on the first turn of a forked session only;
        # the result's session id then becomes this session's own id.
        self._fork_pending: bool = fork
        self._forking_turn: bool = False
        # Local CLI version from the prewarm ``qwen --version`` (None = unknown).
        self._cli_version: tuple[int, int, int] | None = None
        # Set once a per-run settings file was written on the SSH remote, so
        # stop() can remove it (best-effort).
        self._remote_settings_written: bool = False
        # Subagents (spec 12 §4.8), keyed by the spawning ``agent`` call id.
        # The registry lives as long as the session (snapshot for late
        # joiners); the rest is per turn (see _reset_subagent_turn_state).
        self._subagents = SubagentRegistry()
        self._sub_text: dict[str, list[str]] = {}
        self._sub_thinking: dict[str, list[str]] = {}
        # The subagent that produced the latest parent_tool_use_id line: a
        # failed subagent's error ``result`` line carries no parent id.
        self._last_sub: str | None = None

    @property
    def provider_name(self) -> str:
        return "qwen"

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    async def _run_lifecycle(self) -> None:
        """Qwen has no persistent connection — the lifecycle is just bookkeeping.

        We mark the session IDLE immediately, signal connect_done, then
        block on ``_stop_requested``.  ``stop()`` triggers it, the finally
        block reaps any subprocess that's somehow still alive, and we exit.

        Remote (SSH) sessions get an ICMP reachability pre-probe before
        IDLE so a hibernated/offline target fails fast at start() instead
        of hanging on the first turn's SSH TCP timeout.  Mirrors Claude's
        ``_assert_ssh_reachable`` behavior; same rationale.

        We also pre-warm two slow things before signaling connect_done,
        so the cost lands at tab-open rather than on the user's first
        prompt.  See :meth:`_prewarm` for the rationale.
        """
        try:
            if self._config.ssh_host:
                reachable = await asyncio.get_running_loop().run_in_executor(
                    None, probe_host_reachable, self._config.ssh_host, 2.0,
                )
                if not reachable:
                    raise RemoteHostUnreachableError(
                        f"SSH host {self._config.ssh_host!r} did not reply to "
                        "ICMP ping; refusing to open SSH connection."
                    )
            if self._resume_id:
                self._provider_session_id = self._resume_id
            # Move the slow first-prompt costs (remote `which` probe,
            # local Node startup) here so start() pays them once instead
            # of the user staring at an unresponsive prompt.  Failures
            # are logged but NOT raised — a flaky warmup shouldn't block
            # the session from opening.
            await self._prewarm()
            self._status = SessionStatus.IDLE
        except BaseException as e:
            self._connect_error = e
            self._connect_done.set()
            return

        self._connect_done.set()
        try:
            await self._stop_requested.wait()
        finally:
            await self._kill_proc()
            if self._remote_settings_written:
                await self._remove_remote_settings()
            self._status = SessionStatus.DISCONNECTED

    async def _prewarm(self) -> None:
        """Pre-pay the slow first-prompt costs at session start.

        Without this, the FIRST send() on a fresh session blocks the
        user for several seconds while we either:

        1. **Remote sessions**: open SSH, run ``which qwen`` (2-10s for
           the first call, cached for subsequent calls — see
           :func:`manager._ssh.resolve_remote_cli_path`).
        2. **Local sessions**: cold-start the Node runtime that backs
           the ``qwen`` CLI.  On a fresh boot the Node binary + the
           CLI's JS modules aren't in the OS page cache, so the first
           invocation pays a multi-second I/O hit; later invocations
           are warm.

        Running both probes here moves that latency off the user's
        first prompt and onto the session-open step (which already
        shows a spinner / connecting indicator).

        Best-effort — exceptions are logged and swallowed.  If the
        warmup itself fails, the user will simply pay the cost on the
        real first turn instead, which is no worse than today.
        """
        if self._config.ssh_host:
            try:
                target = SshTarget(
                    host=self._config.ssh_host,
                    user=self._config.ssh_user,
                    key=self._config.ssh_key,
                    control_path_prefix="qwen",
                )
                # resolve_remote_cli_path is synchronous (runs `ssh ...
                # which qwen` via subprocess.run with a 10s timeout) —
                # offload to a worker thread so we don't block the loop.
                await asyncio.get_running_loop().run_in_executor(
                    None,
                    lambda: resolve_remote_cli_path(
                        "qwen",
                        target,
                    ),
                )
            except Exception:
                logger.exception(
                    "Qwen remote CLI path warmup failed for %s; first turn "
                    "will pay the resolution cost instead.",
                    self._local_id,
                )
            return

        # Local-only Node-runtime warmup.  Spawn `qwen --version` with a
        # short timeout — its only purpose is to fault in the Node
        # binary and the CLI's JS bundle so the FS page cache is hot
        # before the user's first real turn.  We don't care about the
        # exit code or output.
        try:
            proc = await asyncio.create_subprocess_exec(
                _qwen_executable(), "--version",
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
            )
            try:
                out, _ = await asyncio.wait_for(proc.communicate(), timeout=10.0)
                if isinstance(out, (bytes, bytearray)):
                    self._cli_version = _parse_version(out.decode("utf-8", "replace"))
            except asyncio.TimeoutError:
                # Warmup overran our budget — kill and move on.  The
                # real turn might still be slow but at least we won't
                # delay session-open further.
                try:
                    proc.kill()
                    await proc.wait()
                except ProcessLookupError:
                    pass
                logger.warning(
                    "Qwen local CLI warmup exceeded 10s for %s; skipping.",
                    self._local_id,
                )
        except FileNotFoundError:
            # Missing CLI surfaces on the real first turn with a clearer
            # error message; no point duplicating it here.
            pass
        except Exception:
            logger.exception(
                "Qwen local CLI warmup failed for %s; first turn will "
                "pay the cold-start cost instead.",
                self._local_id,
            )

    def _take_remote(self) -> tuple[SshTarget, int] | None:
        """``(target, pid)`` of a running SSH turn's remote CLI, once.

        Signalling the local ``ssh`` client does not reach the remote
        command (no tty, so no SIGHUP), so interrupt/kill stop the remote
        process tree with :func:`kill_remote_tree` instead.  None for local
        turns or before the remote shell announced its PID.
        """
        pid, target = self._remote_pid, self._remote_target
        if not (self._config.ssh_host and pid and target):
            return None
        self._remote_pid = None
        return target, pid

    async def _kill_proc(self) -> None:
        """Terminate any in-flight qwen subprocess (and its group).  Idempotent.

        Shell commands the CLI started run detached (their own process
        group), so they are snapshotted first and reaped afterwards.
        """
        proc = self._proc
        if proc is None:
            return
        if proc.returncode is not None:
            self._proc = None
            return
        remote = self._take_remote()
        if remote is not None:
            await kill_remote_tree(*remote)
        tree = process_tree(proc)
        try:
            signal_group(proc, signal.SIGTERM)
        except ProcessLookupError:
            self._proc = None
            await reap_descendants(proc, tree, grace_s=0)
            return
        try:
            await asyncio.wait_for(proc.wait(), timeout=2.0)
        except asyncio.TimeoutError:
            try:
                signal_group(proc, signal.SIGKILL)
            except ProcessLookupError:
                pass
            try:
                await asyncio.wait_for(proc.wait(), timeout=2.0)
            except asyncio.TimeoutError:
                logger.warning(
                    "qwen subprocess pid=%s did not exit after SIGKILL", proc.pid,
                )
        self._proc = None
        await reap_descendants(proc, tree, grace_s=0)

    async def interrupt(self) -> None:
        """Send SIGINT to the in-flight qwen subprocess's process group.

        With ``QWEN_CODE_NO_RELAUNCH=true`` the CLI is a single process that
        exits on SIGINT.  (By default Qwen Code — a Gemini CLI fork —
        relaunches itself as a child with a bigger heap and the parent
        ignores SIGINT, so an interrupt used to let the turn run to the
        end.)  A shell command it was running is reaped afterwards.  If no
        turn is running, no-op.
        """
        proc = self._proc
        if proc is not None and proc.returncode is None:
            tree = process_tree(proc)
            try:
                signal_group(proc, signal.SIGINT)
            except ProcessLookupError:
                pass
            if tree:
                task = asyncio.create_task(reap_descendants(proc, tree), name="qwen-reap")
                self._reaper_tasks.add(task)
                task.add_done_callback(self._reaper_tasks.discard)
        remote = self._take_remote()
        if remote is not None:
            task = asyncio.create_task(kill_remote_tree(*remote), name="qwen-remote-kill")
            self._reaper_tasks.add(task)
            task.add_done_callback(self._reaper_tasks.discard)
        self._status = SessionStatus.INTERRUPTED

    # ------------------------------------------------------------------
    # Sending messages
    # ------------------------------------------------------------------

    @property
    def subprocess_pid(self) -> int | None:
        """PID of the in-flight qwen subprocess, for the pool's orphan reaper.

        Note: Qwen's subprocess is short-lived (one per turn), so this is
        only non-None while a turn is actively running.
        """
        proc = self._proc
        return proc.pid if proc is not None and proc.returncode is None else None

    async def send(self, prompt: str) -> AsyncIterator[Event]:
        """Send a prompt by spawning a fresh ``qwen`` subprocess for the turn.

        Yields the same normalized :class:`Event` types as Claude.  The
        subprocess is killed automatically if the iterator is closed mid-stream.
        """
        if self._status == SessionStatus.DISCONNECTED:
            raise RuntimeError("QwenSessionManager is not connected — call start() first")

        # If a previous turn's subprocess is still alive (e.g. from a
        # previous send() that wasn't fully drained), reap it first.
        if self._proc is not None and self._proc.returncode is None:
            await self._kill_proc()

        self._status = SessionStatus.STREAMING
        self._reset_subagent_turn_state()

        local_argv = self._build_argv()
        self._forking_turn = "--fork-session" in local_argv
        self._fork_pending = False

        # Per-run settings file (memory extractor off, output language,
        # harness options …) — see manager/qwen/run_settings.py.  Local runs
        # get a private 0600 file removed after the turn; SSH runs get a
        # copy written on the remote.  Failures fall back to the old
        # behaviour (no file) rather than failing the turn.
        settings_path: str | None = None
        remote_settings_path: str | None = None
        if self._config.ssh_host:
            remote_settings_path = await self._write_remote_settings()
        else:
            settings_path = self._write_local_settings()
        env = self._build_env(settings_path)

        # SSH or local?  _maybe_wrap_with_ssh returns the argv that will
        # actually be exec'd plus the local cwd to spawn from (which is
        # the project_dir for local, irrelevant for SSH since the remote
        # cwd is set inside the SSH command via `cd`).
        try:
            argv, cwd = self._maybe_wrap_with_ssh(
                local_argv, settings_path=remote_settings_path,
            )
        except BaseException:
            _run_settings.remove_run_settings(settings_path)
            self._status = SessionStatus.IDLE
            raise

        # Pipe the prompt as a single stream-json line on stdin.
        stdin_payload = self._render_prompt(prompt).encode("utf-8")

        try:
            proc = await asyncio.create_subprocess_exec(
                *argv,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                cwd=cwd,
                env=env,
                # Own process group, so interrupt/kill reach the whole CLI.
                start_new_session=True,
            )
        except FileNotFoundError as e:
            self._status = SessionStatus.IDLE
            _run_settings.remove_run_settings(settings_path)
            # argv[0] is either the local qwen path or "ssh".  Either way
            # the missing binary points to a misconfiguration: qwen CLI
            # not installed locally, or ssh binary absent.
            raise RuntimeError(
                f"Executable not found ({argv[0]!r}). For local sessions, "
                "set QWEN_CLI_PATH or install qwen via npm.  For SSH "
                "sessions, make sure the local `ssh` client is installed."
            ) from e

        self._proc = proc
        self._remote_pid = None

        # Notify the pool (if it installed a callback) that a new PID is
        # alive.  The pool tracks it for the orphan reaper so we can
        # SIGKILL leaks if this turn's normal cleanup paths get bypassed
        # (e.g. caller cancels the lifecycle task hard).
        if self._on_pid_spawn is not None:
            try:
                self._on_pid_spawn(proc.pid)
            except Exception:
                logger.exception("on_pid_spawn callback raised for pid=%d", proc.pid)

        # Feed stdin in a background task so we can stream stdout
        # concurrently.  Close stdin after writing so qwen knows there's
        # no more input.
        async def _feed_stdin() -> None:
            try:
                assert proc.stdin is not None
                proc.stdin.write(stdin_payload)
                await proc.stdin.drain()
                proc.stdin.close()
            except (BrokenPipeError, ConnectionResetError):
                pass  # qwen exited before we finished writing

        stdin_task = asyncio.create_task(_feed_stdin(), name="qwen-stdin")
        stderr_task = asyncio.create_task(
            self._drain_stderr(proc), name="qwen-stderr",
        )

        last_tool_name: str | None = None
        last_tool_use_id: str | None = None
        text_buffer: list[str] = []
        thinking_buffer: list[str] = []

        # The whole thing is wrapped so we always reap the subprocess.
        try:
            async for event in self._stream_events(proc, prompt):
                if isinstance(event, ToolUse):
                    last_tool_name = event.tool_name
                    last_tool_use_id = event.tool_use_id
                elif isinstance(event, (ToolResult, TurnComplete)):
                    last_tool_name = None
                    last_tool_use_id = None
                elif isinstance(event, TextDelta):
                    text_buffer.append(event.text)
                elif isinstance(event, ThinkingDelta):
                    thinking_buffer.append(event.text)
                elif isinstance(event, SessionStalled):
                    # Attach the in-flight tool to the stall event.
                    event = SessionStalled(
                        elapsed_seconds=event.elapsed_seconds,
                        last_tool_name=last_tool_name,
                        last_tool_use_id=last_tool_use_id,
                    )
                yield event
        finally:
            stdin_task.cancel()
            try:
                await stdin_task
            except (asyncio.CancelledError, Exception):
                pass
            stderr_task.cancel()
            try:
                await stderr_task
            except (asyncio.CancelledError, Exception):
                pass
            if proc.returncode is None:
                await self._kill_proc()
            else:
                self._proc = None
            # Tell the pool the PID is done — keeps _tracked_pids clean
            # so the reaper doesn't have to scan dead pids every iteration.
            if self._on_pid_exit is not None:
                try:
                    self._on_pid_exit(proc.pid)
                except Exception:
                    logger.exception("on_pid_exit callback raised for pid=%d", proc.pid)
            self._event_inbox = None
            self._drain_pending_permissions()
            # The CLI process is gone: a subagent it did not report on
            # (killed or interrupted turn) can no longer run.
            for ev in self._subagents.stop_all():
                self._emit_out_of_turn(ev)
            _run_settings.remove_run_settings(settings_path)
            self._forking_turn = False
            self._status = SessionStatus.IDLE

    async def _stream_events(
        self,
        proc: asyncio.subprocess.Process,
        prompt: str,
    ) -> AsyncIterator[Event]:
        """Consume ``proc.stdout`` line-by-line and yield normalized events.

        Includes the same stall/abandon watchdog Claude has.
        """
        assert proc.stdout is not None
        loop = asyncio.get_running_loop()

        # Adapter state for translating Anthropic-style stream events.
        text_buffer: list[str] = []
        thinking_buffer: list[str] = []
        # Each content_block_start carries metadata we need at content_block_stop
        # time, keyed by (parent_tool_use_id, index): a subagent's blocks are
        # numbered from 0 in its own message, like the parent's.
        block_meta: dict[tuple[str | None, int], dict] = {}

        turn_started_at = loop.time()
        last_event_at = turn_started_at
        stall_notified_at: float | None = None
        events_received = 0

        async def _read_one_line() -> bytes | None:
            return await proc.stdout.readline()

        while True:
            now = loop.time()
            if stall_notified_at is None:
                next_notice_in = max(0.0, _STALL_FIRST_NOTICE_S - (now - last_event_at))
            else:
                next_notice_in = max(
                    0.0, _STALL_REPEAT_INTERVAL_S - (now - stall_notified_at),
                )

            try:
                line = await asyncio.wait_for(
                    _read_one_line(), timeout=max(next_notice_in, 0.5),
                )
            except asyncio.TimeoutError:
                now = loop.time()
                if events_received == 0 and (now - turn_started_at) >= _TURN_ABANDON_S:
                    raise QwenAbandoned(now - turn_started_at)
                yield SessionStalled(
                    elapsed_seconds=now - last_event_at,
                    last_tool_name=None,
                    last_tool_use_id=None,
                )
                stall_notified_at = now
                continue

            if not line:
                # EOF — qwen exited.
                rc = await proc.wait()
                if rc != 0:
                    logger.warning(
                        "qwen exited with non-zero status %d for session %s",
                        rc, self._local_id,
                    )
                break

            last_event_at = loop.time()
            stall_notified_at = None
            events_received += 1

            remote_pid = parse_remote_pid(line.decode("utf-8", errors="replace").strip())
            if remote_pid is not None:
                self._remote_pid = remote_pid
                continue
            try:
                obj = json.loads(line.decode("utf-8").strip())
            except json.JSONDecodeError:
                logger.warning("Could not parse qwen stdout line: %r", line[:200])
                continue

            for ev in self._translate_event(obj, block_meta, text_buffer, thinking_buffer):
                yield ev

    def _translate_event(
        self,
        obj: dict,
        block_meta: dict,
        text_buffer: list[str],
        thinking_buffer: list[str],
    ) -> list[Event]:
        """Translate one qwen JSONL event into zero or more normalized events.

        Returned as a list so a single source event can fan out (e.g.
        content_block_stop → TextComplete + reset buffer).

        Lines carrying ``parent_tool_use_id`` come from a subagent (spec 12
        §4.8): they are wrapped in :class:`SubagentEvent` and never touch the
        session status.  ``agent`` calls, ``task_started`` /
        ``task_notification`` and the spawning call's result drive the
        subagent lifecycle.
        """
        out: list[Event] = []
        obj_type = obj.get("type", "")
        parent = obj.get("parent_tool_use_id") or None
        if not isinstance(parent, str):
            parent = None
        if parent:
            self._last_sub = parent

        def emit(ev: Event) -> None:
            out.append(SubagentEvent(tool_use_id=parent, event=ev) if parent else ev)

        if obj_type == "system":
            subtype = obj.get("subtype", "")
            if subtype == "init":
                sid = obj.get("session_id")
                if sid and (not self._provider_session_id or self._forking_turn):
                    self._provider_session_id = sid
            elif subtype == "compact":
                out.append(CompactComplete(
                    trigger=obj.get("trigger", "manual"),
                    summary=obj.get("summary", ""),
                ))
            elif subtype == "task_started":
                out.extend(self._on_task_started(obj.get("data")))
            elif subtype == "task_notification":
                out.extend(self._on_task_notification(obj.get("data")))
            return out

        if obj_type == "stream_event":
            event = obj.get("event", {})
            evt_type = event.get("type", "")
            if parent:
                texts = self._sub_text.setdefault(parent, [])
                thinks = self._sub_thinking.setdefault(parent, [])
            else:
                texts, thinks = text_buffer, thinking_buffer

            if evt_type == "content_block_start":
                index = event.get("index", 0)
                block = event.get("content_block", {}) or {}
                block_meta[(parent, index)] = block
                # Reset buffers for fresh blocks.
                if block.get("type") == "text":
                    texts.clear()
                elif block.get("type") == "thinking":
                    thinks.clear()

            elif evt_type == "content_block_delta":
                delta = event.get("delta", {}) or {}
                dtype = delta.get("type", "")
                if dtype == "text_delta":
                    if not parent:
                        self._status = SessionStatus.STREAMING
                    text = delta.get("text", "")
                    if text:
                        texts.append(text)
                        emit(TextDelta(text=text))
                elif dtype == "thinking_delta":
                    if not parent:
                        self._status = SessionStatus.THINKING
                    text = delta.get("thinking", "") or delta.get("text", "")
                    if text:
                        thinks.append(text)
                        emit(ThinkingDelta(text=text))
                # input_json_delta intentionally ignored — we use the
                # complete tool_use block emitted with the assistant message.

            elif evt_type == "content_block_stop":
                index = event.get("index", 0)
                meta = block_meta.pop((parent, index), {})
                btype = meta.get("type", "")
                if btype == "text" and texts:
                    emit(TextComplete(text="".join(texts)))
                    texts.clear()
                elif btype == "thinking" and thinks:
                    emit(ThinkingComplete(text="".join(thinks)))
                    thinks.clear()

            return out

        if obj_type == "assistant":
            # Complete assistant message — extract any tool_use blocks that
            # weren't streamed via stream_event (qwen sends them whole here).
            message = obj.get("message", {})
            for block in message.get("content", []):
                if not isinstance(block, dict):
                    continue
                btype = block.get("type")
                if btype == "tool_use":
                    tool_id = block.get("id", "")
                    name = block.get("name", "")
                    tool_input = block.get("input", {}) or {}
                    if parent:
                        self._subagents.note_activity(parent, name)
                    else:
                        self._status = SessionStatus.TOOL_USE
                    emit(ToolUse(tool_use_id=tool_id, tool_name=name, tool_input=tool_input))
                    if tool_id and is_spawn_tool(name):
                        out.extend(self._on_spawn(tool_id, parent, tool_input))
                # text / thinking are already covered by stream_event deltas.
            return out

        if obj_type == "user":
            # Tool results arrive as user messages with tool_result blocks.
            # A subagent's first user line is its prompt (text): the
            # transcript has it; nothing to stream.
            message = obj.get("message", {})
            content = message.get("content", [])
            if not isinstance(content, list):
                return out
            for block in content:
                if not isinstance(block, dict):
                    continue
                if block.get("type") == "tool_result":
                    result_content = block.get("content", "")
                    if isinstance(result_content, list):
                        parts: list[str] = []
                        for item in result_content:
                            if isinstance(item, dict) and item.get("type") == "text":
                                parts.append(item.get("text", ""))
                            elif isinstance(item, str):
                                parts.append(item)
                        result_content = "\n".join(parts)
                    result = ToolResult(
                        tool_use_id=block.get("tool_use_id", ""),
                        output=str(result_content) if result_content else "",
                        is_error=block.get("is_error", False),
                    )
                    emit(result)
                    if result.tool_use_id in self._subagents:
                        out.extend(self._on_spawn_result(result))
            return out

        if obj_type == "result":
            if obj.get("is_error") and not parent and self._is_subagent_error_result(obj):
                # A failed / cancelled subagent: the CLI emits a parentless
                # error ``result`` mid-turn (``emitSubagentErrorResult``).
                # It ends that agent, never the user's turn.
                target = self._subagent_error_target()
                if target is not None:
                    ev = self._subagents.finish(
                        target, status="failed",
                        summary=_error_message(obj) or "Subagent failed",
                    )
                    if ev is not None:
                        out.append(ev)
                return out
            usage = obj.get("usage", {}) or {}
            num_turns = obj.get("num_turns", 0)
            self._turns += num_turns
            sid = obj.get("session_id")
            if sid:
                self._provider_session_id = sid
            is_error = bool(obj.get("is_error", False))
            result = obj.get("result")
            if is_error and not result:
                # 0.2x: the reason lives in ``error.message``.
                result = _error_message(obj)
            # The CLI waits for its background agents before this final
            # result (runNonInteractive), so whatever is still running now
            # was cut short (interrupt, budget, error).
            out.extend(self._subagents.stop_all())
            out.append(TurnComplete(
                cost=None,  # qwen doesn't report cost
                usage=usage,
                num_turns=num_turns,
                session_id=sid or "",
                is_error=is_error,
                result=result,
            ))
            return out

        return out

    # ------------------------------------------------------------------
    # Subagents (spec 12 §4.8)
    # ------------------------------------------------------------------

    def _reset_subagent_turn_state(self) -> None:
        self._sub_text.clear()
        self._sub_thinking.clear()
        self._last_sub = None

    def subagent_snapshot(self) -> list[dict]:
        return self._subagents.summaries()

    def _on_spawn(self, tool_id: str, parent: str | None, tool_input: dict) -> list[Event]:
        """An ``agent`` call: the subagent starts now (Qwen runs it inside
        the call, or in the background right after it)."""
        agent_type, description, background = spawn_fields(tool_input)
        self._subagents.spawn(
            tool_id, parent_tool_use_id=parent,
            agent_type=agent_type or _DEFAULT_SUBAGENT_TYPE,
            description=description, background=background,
        )
        ev = self._subagents.start(tool_id)
        return [ev] if ev is not None else []

    def _on_spawn_result(self, result: ToolResult) -> list[Event]:
        """The spawning call's own result: a foreground agent ends here; a
        background launch only names the task id (its end is a
        ``task_notification``)."""
        rec = self._subagents.get(result.tool_use_id)
        if rec is None:
            return []
        m = _TASK_ID_RE.search(result.output or "")
        if m:
            self._subagents.link(result.tool_use_id, m.group(1))
        if not result.is_error and (rec.background or _BG_LAUNCH_PREFIX in (result.output or "")):
            rec.background = True
            return []
        ev = self._subagents.finish(
            result.tool_use_id,
            status="failed" if result.is_error else "done",
            summary=result.output,
        )
        return [ev] if ev is not None else []

    def _on_task_started(self, data: object) -> list[Event]:
        data = data if isinstance(data, dict) else {}
        tid = data.get("tool_use_id")
        if not isinstance(tid, str) or tid not in self._subagents:
            return []  # a monitor task etc.: not a subagent
        rec = self._subagents.get(tid)
        if rec is not None:
            rec.background = True
            if data.get("subagent_type") and rec.agent_type in ("", _DEFAULT_SUBAGENT_TYPE):
                rec.agent_type = str(data["subagent_type"])
        ev = self._subagents.start(
            tid, harness_id=data.get("task_id") or None,
            description=data.get("description") or None, background=True,
        )
        return [ev] if ev is not None else []

    def _on_task_notification(self, data: object) -> list[Event]:
        """A background agent ended.  The stream carries no report (the CLI
        hands its ``<result>`` to the model only), so it is read from the
        agent's transcript; the notice mirrors the line Qwen writes to the
        session JSONL (shown as a background notice, SA-7 / BG-2)."""
        data = data if isinstance(data, dict) else {}
        tid = data.get("tool_use_id")
        rec = self._subagents.get(tid) if isinstance(tid, str) else None
        if rec is None:
            return []
        task_id = data.get("task_id") or rec.harness_id
        if task_id:
            self._subagents.link(rec.tool_use_id, str(task_id))
        raw_status = str(data.get("status") or "")
        status = wire_status(raw_status) or "done"
        summary = self._background_report(str(task_id)) if task_id else None
        u = data.get("usage") if isinstance(data.get("usage"), dict) else {}
        usage = usage_dict(u.get("total_tokens"), u.get("tool_uses"), u.get("duration_ms")) if u else None
        out: list[Event] = [UnpromptedTurnStarted(
            text=_notification_text(task_id, rec.tool_use_id, raw_status, rec.description, summary, u),
            source="task_notification",
        )]
        ev = self._subagents.finish(rec.tool_use_id, status=status, summary=summary or "", usage=usage)
        if ev is not None:
            out.append(ev)
        return out

    def _background_report(self, task_id: str) -> str | None:
        """Final answer of a background agent, from its transcript (local only)."""
        if self._config.ssh_host or not self._provider_session_id:
            return None
        from .subagent_files import qwen_subagent_dir, transcript_report

        path = qwen_subagent_dir(self._config.project_dir, self._provider_session_id) / f"agent-{task_id}.jsonl"
        try:
            return transcript_report(path)
        except Exception:  # noqa: BLE001 — the report is a nicety
            logger.debug("could not read qwen subagent transcript %s", path, exc_info=True)
            return None

    def _is_subagent_error_result(self, obj: dict) -> bool:
        """The shape of ``buildSubagentErrorResult`` (qwen-code 0.25): zero
        durations, while a subagent of this turn is still running.  The
        turn's own final result always carries its real ``duration_ms``."""
        if not self._subagents.running():
            return False
        return not obj.get("duration_ms") and not obj.get("duration_api_ms")

    def _subagent_error_target(self) -> str | None:
        last = self._subagents.get(self._last_sub)
        if last is not None and last.status == "running":
            return last.tool_use_id
        running = self._subagents.running()
        fg = [r for r in running if not r.background]
        pick = (fg or running)
        return pick[-1].tool_use_id if pick else None

    async def _drain_stderr(self, proc: asyncio.subprocess.Process) -> None:
        """Forward qwen stderr to the logger for visibility."""
        if proc.stderr is None:
            return
        try:
            while True:
                line = await proc.stderr.readline()
                if not line:
                    return
                text = line.decode("utf-8", errors="replace").rstrip()
                if text:
                    logger.warning("qwen stderr [%s]: %s", self._local_id, text)
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("qwen stderr drain failed for %s", self._local_id)

    # ------------------------------------------------------------------
    # Subprocess construction
    # ------------------------------------------------------------------

    def _build_argv(self) -> list[str]:
        """Construct the ``qwen`` argv for this turn."""
        argv: list[str] = [
            _qwen_executable(),
            "--input-format", "stream-json",
            "--output-format", "stream-json",
            "--include-partial-messages",
            # We approve tools via our own gate; tell qwen to auto-approve
            # everything else (the wrapper enforces the gate at a higher
            # level via the conversational checkpoint policy).
            "--approval-mode", "yolo",
            # Tag the channel so qwen's logs distinguish wrapper-driven runs.
            "--channel", "SDK",
        ]

        if self._provider_session_id:
            argv += ["--resume", self._provider_session_id]
            if self._fork_pending and self._fork_supported():
                # 0.16+: continue the history under a new session id.
                argv.append("--fork-session")
        # A fork without a resume id is just a fresh session.

        if self._config.model:
            argv += ["--model", self._config.model]

        if self._config.max_turns is not None:
            argv += ["--max-session-turns", str(self._config.max_turns)]

        # Archie's memory index (context/memory/MEMORY.md), read live, the
        # way Claude Code's auto-memory loads it.  Qwen rebuilds its system
        # prompt on every spawn, so this is never stored in the history.
        memory = memory_instructions(self._config.project_dir)
        if memory:
            argv += ["--append-system-prompt", memory]

        return argv

    def _fork_supported(self) -> bool:
        """``--fork-session`` needs qwen-code ≥ 0.16.  Unknown version (SSH,
        prewarm skipped) → assume the pinned version, which has it."""
        if self._cli_version is None:
            return True
        return self._cli_version >= _FORK_SESSION_MIN_VERSION

    def _build_env(self, settings_path: str | os.PathLike | None = None) -> dict[str, str]:
        """Construct the env for the qwen subprocess."""
        env = dict(os.environ)
        # Strip Claude-specific markers so qwen doesn't get confused if
        # the wrapper itself was launched from inside Claude Code.
        env.pop("CLAUDECODE", None)
        # 0.2x prints a "yolo without sandbox" warning to stderr every turn.
        env["QWEN_CODE_SUPPRESS_YOLO_WARNING"] = "1"
        # Run the CLI as ONE process: by default it relaunches itself as a
        # child (with a heap limit of half the machine's RAM) and the parent
        # ignores SIGINT, so interrupt() could not stop a turn.
        env["QWEN_CODE_NO_RELAUNCH"] = "true"
        if settings_path:
            env[_run_settings.SYSTEM_SETTINGS_ENV] = str(settings_path)
        else:
            # Never inherit a stray system-settings path from the backend env.
            env.pop(_run_settings.SYSTEM_SETTINGS_ENV, None)
        return env

    def _run_settings_dict(self, *, remote: bool) -> dict:
        """The per-run settings for this turn (see :mod:`.run_settings`)."""
        if remote:
            # The remote's own ~/.qwen/settings.json provider list is unknown
            # here, so SSH runs only get the fixed keys (no per-run knobs).
            return _run_settings.build_run_settings(
                self._config.model, None, None, include_providers=False,
            )
        from .catalog import load_user_settings

        return _run_settings.build_run_settings(
            self._config.model,
            self._config.harness_options,
            load_user_settings(),
        )

    def _write_local_settings(self) -> str | None:
        try:
            settings = self._run_settings_dict(remote=False)
            return str(_run_settings.write_run_settings(settings, name=self._local_id))
        except Exception:
            logger.exception(
                "Could not write Qwen run settings for %s; running without them",
                self._local_id,
            )
            return None

    def _ssh_target(self) -> SshTarget:
        return SshTarget(
            host=self._config.ssh_host or "",
            user=self._config.ssh_user,
            key=self._config.ssh_key,
            control_path_prefix="qwen",
        )

    def _remote_settings_path(self) -> str:
        safe = re.sub(r"[^A-Za-z0-9_.-]", "_", self._local_id)[:80]
        return f"/tmp/archie-qwen-{safe}.json"

    async def _write_remote_settings(self) -> str | None:
        """Write the fixed per-run settings on the SSH remote (0600, /tmp).

        One extra SSH round trip per turn, multiplexed over the existing
        ControlMaster connection.  Returns the remote path, or ``None`` when
        the write failed (the turn then runs as before, without the file).
        """
        path = self._remote_settings_path()
        payload = json.dumps(self._run_settings_dict(remote=True)).encode("utf-8")
        argv = build_ssh_argv(self._ssh_target()) + [
            f"umask 077 && cat > '{path}'",
        ]
        try:
            proc = await asyncio.create_subprocess_exec(
                *argv,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.PIPE,
            )
            _, err = await asyncio.wait_for(
                proc.communicate(payload), timeout=_REMOTE_SETTINGS_TIMEOUT_S,
            )
        except Exception as e:  # noqa: BLE001 — degrade to the old behaviour
            logger.warning(
                "Could not write Qwen run settings on %s (%s); running without them",
                self._config.ssh_host, e,
            )
            return None
        if proc.returncode != 0:
            logger.warning(
                "Writing Qwen run settings on %s failed (rc=%s): %s",
                self._config.ssh_host, proc.returncode,
                (err or b"").decode("utf-8", "replace").strip()[:200],
            )
            return None
        self._remote_settings_written = True
        return path

    async def _remove_remote_settings(self) -> None:
        """Best-effort ``rm`` of the remote settings file at session stop."""
        argv = build_ssh_argv(self._ssh_target()) + [
            f"rm -f '{self._remote_settings_path()}'",
        ]
        try:
            proc = await asyncio.create_subprocess_exec(
                *argv,
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
            )
            await asyncio.wait_for(proc.wait(), timeout=5.0)
        except Exception:  # noqa: BLE001 — a leftover non-secret /tmp file is harmless
            logger.debug("Could not remove remote Qwen run settings", exc_info=True)

    def _maybe_wrap_with_ssh(
        self, local_argv: list[str], *, settings_path: str | None = None,
    ) -> tuple[list[str], str | None]:
        """Return ``(argv, cwd)`` to feed ``asyncio.create_subprocess_exec``.

        For local sessions this is a no-op: returns *local_argv* and the
        configured ``project_dir`` as cwd.

        For SSH sessions, swaps ``local_argv[0]`` (the local qwen path)
        for the resolved remote path and wraps everything in an
        ``ssh ... "cd '<remote_dir>' && exec '<remote_qwen>' ..."`` argv.
        cwd is irrelevant in that case (the remote cwd is set by the
        SSH command itself), so we return ``None`` and let
        ``create_subprocess_exec`` inherit the parent's cwd.

        Qwen spawns a fresh subprocess per turn, which means each turn
        opens an SSH connection.  ``ControlMaster=auto`` +
        ``ControlPersist=60s`` (set in :func:`_ssh.build_ssh_argv`) keep
        a single TCP connection alive across the burst — without that
        we'd pay the SSH handshake on every turn.
        """
        if not self._config.ssh_host:
            return local_argv, self._config.project_dir

        target = self._ssh_target()
        remote_qwen = resolve_remote_cli_path(
            "qwen",
            target,
        )
        # The local env is NOT forwarded: the remote machine has its own
        # .env (DASHSCOPE_API_KEY, ASSISTANT_PROVIDER, …) set up at install
        # time, just like Claude's remote installs.  Forwarding it would
        # either leak the local DASHSCOPE key (visible in `ps` on the
        # remote) or quietly miss other vars the remote setup expects.
        # Only two non-secret switches go along.
        remote_env = {"QWEN_CODE_SUPPRESS_YOLO_WARNING": "1", "QWEN_CODE_NO_RELAUNCH": "true"}
        if settings_path:
            remote_env[_run_settings.SYSTEM_SETTINGS_ENV] = settings_path
        self._remote_target = target
        remote_cmd = RemoteCommand(
            announce_pid=True,
            project_dir=self._config.project_dir,
            remote_cli=remote_qwen,
            env=remote_env,
        )
        # ``local_argv[0]`` is the LOCAL qwen path (resolved by
        # :func:`_qwen_executable`); on the remote machine that path is
        # meaningless.  We drop it here — the remote path goes into
        # ``remote_cmd`` (which renders ``... exec '<remote_qwen>'``)
        # and the rest of the flags pass through as-is.
        # ``build_remote_argv`` shell-quotes each arg before joining, so
        # values with spaces or metacharacters survive across SSH.
        argv = build_remote_argv(
            target=target,
            remote_cmd=remote_cmd,
            remote_args=local_argv[1:],
        )
        return argv, None

    @staticmethod
    def _render_prompt(prompt: str) -> str:
        """Serialize a user prompt as a single stream-json line."""
        return json.dumps({
            "type": "user",
            "message": {
                "role": "user",
                "content": [{"type": "text", "text": prompt}],
            },
        }) + "\n"
