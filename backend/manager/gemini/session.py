"""GeminiSessionManager — wraps a single Google Gemini CLI conversation.

Shape: spawn-per-turn, just like Qwen.  Each ``send()`` spawns a fresh
``gemini -p '<prompt>' --output-format stream-json --session-id <uuid>``
subprocess, parses its stdout line-by-line, and yields normalized events.

Why not stream-json on stdin like Qwen?  The Gemini CLI's headless
``--prompt`` flag takes the prompt directly on argv (no stdin protocol)
and emits the same stream-json shape on stdout.  Argv is the simplest
path — ``asyncio.create_subprocess_exec`` doesn't shell-interpret args,
so even prompts with quotes/newlines survive.

Authentication
--------------

Google stopped serving Gemini CLI to personal Google logins
(``oauth-personal``, "Gemini Code Assist for individuals") on 2026-06-18;
the CLI now fails with ``IneligibleTierError … UNSUPPORTED_CLIENT``.  The
working path is an AI Studio key in ``GEMINI_API_KEY``.  The repo's
workspace settings select ``gemini-api-key`` (overridable through
``ARCHIE_GEMINI_AUTH_TYPE``), and for other working directories the
manager sets ``GEMINI_CLI_AUTH_OVERRIDE``, so a leftover ``oauth-personal``
in ``~/.gemini/settings.json`` no longer wins.  Without a key a local turn
fails at once with an explanation instead of an empty turn.

Workspace settings and per-turn options
---------------------------------------

The CLI has no per-run settings flag, so per-turn choices (thinking level,
thinking budget) travel as env vars that the static workspace settings
file expands — see :mod:`.workspace_settings`.  Before every local spawn in
the repo root the manager makes sure that file carries Archie's keys,
most importantly ``general.sessionRetention.enabled=false``: the CLI's
retention sweep would otherwise delete old sessions in ``context/chats/``.

Interrupt
---------

The CLI normally relaunches itself as a child Node process while the
parent ignores SIGINT/SIGTERM, so signalling the PID we spawned did
nothing and killing it orphaned the worker.  ``GEMINI_CLI_NO_RELAUNCH=true``
keeps it a single process; the subprocess also gets its own process group
so interrupt / kill reach it.  The CLI's shell tool starts commands
*detached* (own process group) and does not stop them when it exits on
SIGINT, so the manager snapshots the process tree before signalling and
reaps whatever survives the CLI.

SSH remote execution
--------------------

When ``ManagerConfig.ssh_host`` is set the CLI runs on the remote host,
wrapped by an ``ssh ...`` argv produced via :mod:`manager._ssh` — same
pattern as :class:`manager.qwen.session.QwenSessionManager`.  Only
non-secret vars (relaunch, trust, per-turn options) are forwarded; the
remote host brings its own ``GEMINI_API_KEY`` and workspace settings.

Trust prompt
------------

Gemini CLI defaults to refusing headless runs in directories it doesn't
"trust."  We pass ``--skip-trust`` on every invocation and set
``GEMINI_CLI_TRUST_WORKSPACE=true`` (trust is also what makes the CLI load
the workspace settings file).

Resume + session ids
--------------------

We generate session ids ourselves (UUIDv4) and pass them via
``--session-id`` on a fresh session's first turn so the CLI uses ours
instead of inventing one.  Once the CLI has written resumable content for
the id, every turn passes ``--resume <session-id>``; the CLI refuses
``--session-id`` for an id that already exists on disk.  A turn that fails
early still leaves a header-only stub (the CLI writes it before
authenticating); that stub is removed so the next turn can pin again.

Thinking
--------

stream-json carries no thoughts.  The CLI writes them into the session
JSONL when it records each model step, so the manager tails that file
during the turn and emits them as thinking events (local sessions only).

Storage layout
--------------

The CLI writes session JSONL to
``~/.gemini/tmp/<project-label>/chats/session-<short-iso>-<uuid-prefix>.jsonl``.
``<project-label>`` is the value the CLI assigns to ``cwd`` inside
``~/.gemini/projects.json``.  This means our session manager and the
JSONL adapter must agree on cwd: we pass ``self._config.project_dir``
as the subprocess cwd so the CLI lands files in the directory we expect.
The installer symlinks ``~/.gemini/tmp/<project-label>`` to ``context/``,
so the files end up in ``context/chats/``.
"""

from __future__ import annotations

import asyncio
import collections
import json
import logging
import os
import signal
import uuid
from collections.abc import AsyncIterator
from pathlib import Path

from utils.paths import PROJECT_ROOT

from .._ssh import (
    RemoteCommand,
    RemoteHostUnreachableError,
    SshTarget,
    build_remote_argv,
    probe_host_reachable,
    resolve_remote_cli_path,
)
from ..base_session import BaseSessionManager, TurnAbandoned
from ..config import ManagerConfig
from ..types import (
    Event,
    SessionStalled,
    SessionStatus,
    TextComplete,
    TextDelta,
    ThinkingComplete,
    ThinkingDelta,
    ToolResult,
    ToolUse,
    TurnComplete,
)
from . import catalog as gcat
from . import workspace_settings as ws

logger = logging.getLogger(__name__)


# Same watchdog policy as Qwen: warn after 2 min of silence, repeat every 60s.
_STALL_FIRST_NOTICE_S = 120.0
_STALL_REPEAT_INTERVAL_S = 60.0
# Abandoned-turn detection — produced zero events for this long → give up.
_TURN_ABANDON_S = 240.0
# stderr lines kept per turn for error reporting.
_STDERR_TAIL_LINES = 40
_ERROR_TEXT_MAX = 2000

# stderr lines that carry no diagnostic value.
_STDERR_NOISE = (
    "256-color",
    "ripgrep",
    "yolo mode",
    "shell cwd was reset",
    "loaded cached credentials",
)

AUTH_HELP = (
    "Gemini CLI needs GEMINI_API_KEY. Google stopped serving Gemini CLI to personal "
    "Google-account logins (oauth-personal, \"Gemini Code Assist for individuals\") on "
    "2026-06-18. Create an AI Studio key (https://aistudio.google.com/apikey), put it in "
    "context/.env as GEMINI_API_KEY and restart the backend. Vertex AI or Code Assist "
    "Standard/Enterprise users can set ARCHIE_GEMINI_AUTH_TYPE (e.g. vertex-ai) instead."
)


class GeminiAbandoned(TurnAbandoned):
    """Raised when a Gemini turn produced no events for so long the request
    almost certainly never landed.

    Inherits :class:`manager.base_session.TurnAbandoned` so catch sites
    that want to handle all providers uniformly can do so with a single
    ``except TurnAbandoned`` clause.
    """


def _gemini_executable() -> str:
    """Resolve the path to the ``gemini`` CLI.

    Honors ``GEMINI_CLI_PATH`` if set; otherwise relies on ``$PATH`` resolution.
    """
    return os.environ.get("GEMINI_CLI_PATH", "gemini")


def _is_noise(line: str) -> bool:
    low = line.lower()
    return any(n in low for n in _STDERR_NOISE)


def _error_from_stderr(lines: list[str], rc: int | None) -> str:
    """A user-facing error for a run that died without a ``result`` event."""
    useful = [ln for ln in lines if ln.strip() and not _is_noise(ln)]
    text = "\n".join(useful[-15:]).strip()
    if len(text) > _ERROR_TEXT_MAX:
        text = "…" + text[-_ERROR_TEXT_MAX:]
    joined = "\n".join(lines)
    if (
        "IneligibleTierError" in joined
        or "UNSUPPORTED_CLIENT" in joined
        or "no longer supported for Gemini Code Assist" in joined
    ):
        return AUTH_HELP + ("\n\nCLI said:\n" + text if text else "")
    head = f"Gemini CLI exited with status {rc}" if rc is not None else "Gemini CLI failed"
    return f"{head}: {text}" if text else f"{head} (no output)."


# Process-tree helpers are shared with the Qwen harness (manager._proc).
from .._proc import (  # noqa: E402
    descendants as _descendants,
    proc_table as _proc_table,
    process_tree as _process_tree,
    reap_descendants as _reap_descendants,
    signal_group as _signal_group,
    signal_survivors as _signal_survivors,
)


class _ThoughtTail:
    """Follows the session JSONL during a turn and yields new thoughts.

    The CLI queues thoughts while a model step streams and writes them with
    the step's record, so they reach the file at the end of each step.  We
    remember what every message id carried when the turn started (including
    old turns restated by a resume snapshot) and only report additions.
    """

    def __init__(self, session_id: str) -> None:
        self._session_id = session_id
        self._path: Path | None = None
        self._offset = 0
        self._partial = b""
        self._seen: dict[str, int] = {}

    def _find(self) -> Path | None:
        if self._path is not None:
            return self._path
        from .adapter import _gemini_jsonl_candidates, _read_gemini_session_id

        for p in _gemini_jsonl_candidates(self._session_id):
            if _read_gemini_session_id(p) == self._session_id:
                self._path = Path(p)
                return self._path
        return None

    def prime(self) -> None:
        """Mark everything already on disk as seen."""
        try:
            for _ in self.poll():
                pass
        except Exception:  # noqa: BLE001 — thinking is best-effort
            logger.debug("gemini thought tail prime failed", exc_info=True)

    def _records(self, obj: dict):
        if obj.get("type") == "gemini":
            yield obj
        s = obj.get("$set") if "type" not in obj else None
        if isinstance(s, dict) and isinstance(s.get("messages"), list):
            for m in s["messages"]:
                if isinstance(m, dict) and m.get("type") == "gemini":
                    yield m

    def poll(self) -> list[str]:
        """Return thought texts written since the last poll."""
        path = self._find()
        if path is None:
            return []
        try:
            with open(path, "rb") as f:
                f.seek(self._offset)
                data = f.read()
        except OSError:
            return []
        if not data:
            return []
        self._offset += len(data)
        data = self._partial + data
        lines = data.split(b"\n")
        self._partial = lines.pop()  # incomplete last line (or b"")
        out: list[str] = []
        for raw in lines:
            raw = raw.strip()
            if not raw:
                continue
            try:
                obj = json.loads(raw)
            except ValueError:
                continue
            if not isinstance(obj, dict):
                continue
            for rec in self._records(obj):
                rid = rec.get("id")
                thoughts = rec.get("thoughts")
                if not isinstance(rid, str) or not isinstance(thoughts, list):
                    continue
                done = self._seen.get(rid, 0)
                for t in thoughts[done:]:
                    if not isinstance(t, dict):
                        continue
                    subj = t.get("subject", "") or ""
                    desc = t.get("description", "") or ""
                    text = f"{subj}\n{desc}".strip()
                    if text:
                        out.append(text)
                self._seen[rid] = max(done, len(thoughts))
        return out


class GeminiSessionManager(BaseSessionManager):
    """Manage a single Google Gemini CLI conversation.

    Because ``gemini -p`` is one-shot, the lifecycle here is much smaller
    than Claude's: ``start()`` just records that the session exists; each
    ``send()`` spawns a fresh subprocess for the turn.  Resume is handled
    transparently via ``--resume <session-id>`` once the CLI has written
    the session (from the first turn when the manager was created with a
    resume id).
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
        # The currently-running ``gemini`` subprocess for an in-flight turn.
        # None when idle.
        self._proc: asyncio.subprocess.Process | None = None
        # Last stderr lines of the current turn (error reporting).
        self._stderr_tail: collections.deque[str] = collections.deque(
            maxlen=_STDERR_TAIL_LINES,
        )
        # Background reapers for processes an interrupted turn left behind.
        self._reaper_tasks: set[asyncio.Task] = set()

    @property
    def provider_name(self) -> str:
        return "gemini"

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    async def _run_lifecycle(self) -> None:
        """Gemini has no persistent connection — the lifecycle is just bookkeeping.

        Mirror of Qwen's lifecycle: mark IDLE, signal connect_done, block
        on _stop_requested, reap on shutdown.  If no session id was passed
        in (fresh session), we generate one here so the very first send()
        can pin it via ``--session-id``.

        Remote (SSH) sessions get an ICMP reachability pre-probe before
        IDLE so a hibernated/offline target fails fast at start() instead
        of hanging on the first turn's SSH TCP timeout.  Same rationale
        as Qwen's lifecycle and Claude's ``_assert_ssh_reachable``.

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
            else:
                # Generate up front so the first send() can stamp it via
                # ``--session-id`` on argv.  Without this the CLI would
                # invent its own id, and we'd lose track of where the
                # JSONL landed until we sniffed the stream-json init event.
                self._provider_session_id = str(uuid.uuid4())
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
            self._status = SessionStatus.DISCONNECTED

    @staticmethod
    def _signal_group(proc: asyncio.subprocess.Process, sig: int) -> None:
        """Signal the subprocess's process group (see ``manager._proc.signal_group``)."""
        _signal_group(proc, sig)

    async def _kill_proc(self) -> None:
        """Terminate any in-flight gemini subprocess (and its group).  Idempotent."""
        proc = self._proc
        if proc is None:
            return
        if proc.returncode is not None:
            self._proc = None
            return
        tree = self._tree(proc)
        try:
            self._signal_group(proc, signal.SIGTERM)
        except ProcessLookupError:
            self._proc = None
            await _reap_descendants(proc, tree, grace_s=0)
            return
        try:
            await asyncio.wait_for(proc.wait(), timeout=2.0)
        except asyncio.TimeoutError:
            try:
                self._signal_group(proc, signal.SIGKILL)
            except ProcessLookupError:
                pass
            try:
                await asyncio.wait_for(proc.wait(), timeout=2.0)
            except asyncio.TimeoutError:
                logger.warning(
                    "gemini subprocess pid=%s did not exit after SIGKILL", proc.pid,
                )
        self._proc = None
        await _reap_descendants(proc, tree, grace_s=0)

    @staticmethod
    def _tree(proc: asyncio.subprocess.Process) -> list[tuple[int, str]]:
        """Descendants of a real local subprocess (shell commands the CLI ran)."""
        return _process_tree(proc)

    async def interrupt(self) -> None:
        """Send SIGINT to the in-flight gemini subprocess's process group.

        With ``GEMINI_CLI_NO_RELAUNCH=true`` the CLI is a single process
        that exits on SIGINT; the group signal also stops a running shell
        command.
        """
        proc = self._proc
        if proc is not None and proc.returncode is None:
            tree = self._tree(proc)
            try:
                self._signal_group(proc, signal.SIGINT)
            except ProcessLookupError:
                pass
            if tree:
                # The CLI exits on SIGINT without stopping the shell command
                # it was running (spawned detached); reap it afterwards.
                task = asyncio.create_task(
                    _reap_descendants(proc, tree), name="gemini-reap",
                )
                self._reaper_tasks.add(task)
                task.add_done_callback(self._reaper_tasks.discard)
        self._status = SessionStatus.INTERRUPTED

    # ------------------------------------------------------------------
    # Sending messages
    # ------------------------------------------------------------------

    @property
    def subprocess_pid(self) -> int | None:
        """PID of the in-flight gemini subprocess (only set while a turn
        is running, since Gemini is one-shot per turn)."""
        proc = self._proc
        return proc.pid if proc is not None and proc.returncode is None else None

    def _preflight_error(self) -> str | None:
        """A reason this turn cannot run locally, or None.

        * no usable auth (see :data:`AUTH_HELP`);
        * the repo's workspace settings can't be made safe (without
          ``sessionRetention.enabled=false`` the CLI would delete old
          sessions in ``context/chats/``).
        """
        if self._config.ssh_host:
            return None
        if not os.environ.get("GEMINI_API_KEY") and not os.environ.get(ws.ENV_AUTH_TYPE):
            return AUTH_HELP
        if self._is_repo_root():
            try:
                if ws.ensure_workspace_settings(self._config.project_dir):
                    logger.info(
                        "Updated Archie keys in %s",
                        ws.settings_path(self._config.project_dir),
                    )
            except (ws.WorkspaceSettingsError, OSError) as e:
                return (
                    f"Refusing to run Gemini CLI: {e}. Fix or remove "
                    ".gemini/settings.json — without Archie's keys (session "
                    "retention off) the CLI would delete old sessions in "
                    "context/chats/."
                )
        return None

    def _is_repo_root(self) -> bool:
        try:
            return Path(self._config.project_dir).resolve() == Path(PROJECT_ROOT).resolve()
        except OSError:
            return False

    async def send(self, prompt: str) -> AsyncIterator[Event]:
        """Send a prompt by spawning a fresh ``gemini`` subprocess.

        Yields the same normalized :class:`Event` types as the other
        harnesses.  The subprocess is killed automatically if the
        iterator is closed mid-stream.  A run that fails (non-zero exit
        without a ``result``, auth problems) ends with an error
        ``TurnComplete`` carrying the reason — never an empty turn.
        """
        if self._status == SessionStatus.DISCONNECTED:
            raise RuntimeError(
                "GeminiSessionManager is not connected — call start() first"
            )

        # If a previous turn's subprocess is still alive (e.g. from a
        # previous send() that wasn't fully drained), reap it first.
        if self._proc is not None and self._proc.returncode is None:
            await self._kill_proc()

        problem = self._preflight_error()
        if problem:
            self._status = SessionStatus.IDLE
            yield TurnComplete(
                is_error=True, result=problem, session_id=self._provider_session_id or "",
            )
            return

        self._status = SessionStatus.STREAMING
        self._stderr_tail = collections.deque(maxlen=_STDERR_TAIL_LINES)

        local_argv = self._build_argv(prompt)
        env = self._build_env()

        # SSH or local?  Mirrors Qwen's _maybe_wrap_with_ssh contract:
        # returns the argv that will actually be exec'd plus the local
        # cwd (project_dir for local sessions, None for SSH where the
        # remote `cd` is embedded in the SSH command).
        argv, cwd = self._maybe_wrap_with_ssh(local_argv)

        thought_tail: _ThoughtTail | None = None
        if not self._config.ssh_host and self._provider_session_id:
            thought_tail = _ThoughtTail(self._provider_session_id)
            thought_tail.prime()

        try:
            proc = await asyncio.create_subprocess_exec(
                *argv,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                cwd=cwd,
                env=env,
                # Own process group: interrupt/kill reach the CLI and the
                # shell commands it runs, nothing else.
                start_new_session=True,
            )
        except FileNotFoundError as e:
            self._status = SessionStatus.IDLE
            # argv[0] is either the local gemini path or "ssh".  Either
            # way the missing binary points to a misconfiguration.
            raise RuntimeError(
                f"Executable not found ({argv[0]!r}). For local sessions, "
                "set GEMINI_CLI_PATH or install gemini via `npm install -g "
                "@google/gemini-cli`.  For SSH sessions, make sure the "
                "local `ssh` client is installed."
            ) from e

        self._proc = proc

        # Notify the pool that a new PID is alive.
        if self._on_pid_spawn is not None:
            try:
                self._on_pid_spawn(proc.pid)
            except Exception:
                logger.exception("on_pid_spawn callback raised for pid=%d", proc.pid)

        # Close stdin immediately — the prompt is on argv via --prompt.
        # If we leave stdin open the CLI might wait for input that won't
        # come.
        try:
            assert proc.stdin is not None
            proc.stdin.close()
        except (BrokenPipeError, ConnectionResetError):
            pass

        stderr_task = asyncio.create_task(
            self._drain_stderr(proc), name="gemini-stderr",
        )

        # The whole thing is wrapped so we always reap the subprocess.
        try:
            async for event in self._stream_events(proc, stderr_task, thought_tail):
                yield event
        finally:
            # Stop the stderr drainer.
            stderr_task.cancel()
            try:
                await stderr_task
            except (asyncio.CancelledError, Exception):
                pass

            # Reap.
            if proc.returncode is None:
                await self._kill_proc()
            else:
                self._proc = None

            if self._on_pid_exit is not None:
                try:
                    self._on_pid_exit(proc.pid)
                except Exception:
                    logger.exception(
                        "on_pid_exit callback raised for pid=%d", proc.pid,
                    )

            self._turns += 1
            if self._status != SessionStatus.INTERRUPTED:
                self._status = SessionStatus.IDLE

    async def _drain_stderr(self, proc: asyncio.subprocess.Process) -> None:
        """Drain stderr in the background, logging it and keeping a tail.

        The Gemini CLI is chatty on stderr (terminal-color warnings,
        ripgrep-not-found, rate-limit retries).  The stream-json on stdout
        is authoritative; the tail is only used to explain a run that died
        before producing a ``result``.
        """
        assert proc.stderr is not None
        try:
            while True:
                line = await proc.stderr.readline()
                if not line:
                    return
                text = line.decode("utf-8", errors="replace").rstrip()
                if not text:
                    continue
                self._stderr_tail.append(text)
                if _is_noise(text):
                    logger.debug("gemini stderr: %s", text)
                else:
                    logger.info("gemini stderr: %s", text)
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("gemini stderr drain failed")

    async def _stream_events(
        self,
        proc: asyncio.subprocess.Process,
        stderr_task: asyncio.Task | None = None,
        thought_tail: _ThoughtTail | None = None,
    ) -> AsyncIterator[Event]:
        """Consume ``proc.stdout`` line-by-line and yield normalized events.

        Includes the same stall/abandon watchdog the other harnesses have.
        """
        assert proc.stdout is not None
        loop = asyncio.get_running_loop()

        turn_started_at = loop.time()
        last_event_at = turn_started_at
        stall_notified_at: float | None = None
        events_received = 0

        # Streaming-text accumulator — Gemini sends assistant text in
        # multiple ``{"type":"message", "role":"assistant", "delta":true}``
        # events; we yield TextDelta for each chunk and TextComplete at
        # the end of the turn.
        text_buffer: list[str] = []

        # Track tool-use ids by name so tool_result lines (which carry
        # only the id) can be paired up if needed.
        tool_uses_in_flight: dict[str, str] = {}  # tool_id → tool_name

        # Turn-level state shared with _translate_event.
        state: dict = {"completed": False, "error": None}

        def _thoughts() -> list[Event]:
            if thought_tail is None:
                return []
            try:
                texts = thought_tail.poll()
            except Exception:  # noqa: BLE001 — thinking is best-effort
                logger.debug("gemini thought tail poll failed", exc_info=True)
                return []
            out: list[Event] = []
            for t in texts:
                out += [ThinkingDelta(text=t), ThinkingComplete(text=t)]
            return out

        async def _read_one_line() -> bytes:
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
                    raise GeminiAbandoned(now - turn_started_at)
                last_tool_name = (
                    next(iter(tool_uses_in_flight.values()), None)
                    if tool_uses_in_flight
                    else None
                )
                last_tool_use_id = (
                    next(iter(tool_uses_in_flight.keys()), None)
                    if tool_uses_in_flight
                    else None
                )
                yield SessionStalled(
                    elapsed_seconds=now - last_event_at,
                    last_tool_name=last_tool_name,
                    last_tool_use_id=last_tool_use_id,
                )
                stall_notified_at = now
                continue

            if not line:
                # EOF — gemini exited.  Flush any pending streaming text
                # as a TextComplete so the UI sees the final assistant
                # message even if the result event was missing.
                if text_buffer:
                    yield TextComplete(text="".join(text_buffer))
                    text_buffer.clear()
                for ev in _thoughts():
                    yield ev
                rc = await proc.wait()
                if state["completed"] or self._status == SessionStatus.INTERRUPTED:
                    break
                # No ``result`` event: never end the turn silently.  Let the
                # stderr drainer catch up so the tail holds the CLI's error.
                if stderr_task is not None and not stderr_task.done():
                    try:
                        await asyncio.wait_for(asyncio.shield(stderr_task), timeout=1.0)
                    except (asyncio.TimeoutError, asyncio.CancelledError, Exception):
                        pass
                if rc != 0:
                    logger.warning(
                        "gemini exited with non-zero status %d for session %s",
                        rc, self._local_id,
                    )
                    message = state["error"] or _error_from_stderr(list(self._stderr_tail), rc)
                    yield TurnComplete(
                        is_error=True, result=message,
                        session_id=self._provider_session_id or "",
                    )
                else:
                    yield TurnComplete(session_id=self._provider_session_id or "")
                break

            last_event_at = loop.time()
            stall_notified_at = None
            events_received += 1

            line_text = line.decode("utf-8", errors="replace").strip()
            if not line_text:
                continue

            # Skip non-JSON stderr-like lines that occasionally end up on
            # stdout (the CLI prints a "Shell cwd was reset" trailer on
            # stdout in some builds).
            if not line_text.startswith("{"):
                logger.debug("gemini stdout (non-JSON): %s", line_text[:200])
                continue

            try:
                obj = json.loads(line_text)
            except json.JSONDecodeError:
                logger.warning(
                    "Could not parse gemini stdout line: %r", line_text[:200],
                )
                continue

            otype = obj.get("type") if isinstance(obj, dict) else None
            # Thoughts of the step that just ended land on disk before the
            # next tool call / result; pick them up at those boundaries.
            if otype in ("tool_use", "tool_result", "result"):
                pending = _thoughts()
            else:
                pending = []
            if otype == "result" and pending and text_buffer:
                # Close the streamed answer before its thinking arrives.
                yield TextComplete(text="".join(text_buffer))
                text_buffer.clear()
            for ev in pending:
                yield ev

            for ev in self._translate_event(obj, text_buffer, tool_uses_in_flight, state):
                yield ev

    def _translate_event(
        self,
        obj: dict,
        text_buffer: list[str],
        tool_uses_in_flight: dict[str, str],
        state: dict | None = None,
    ) -> list[Event]:
        """Translate one Gemini stream-json event into zero or more events.

        Event vocabulary
        ----------------
        - ``init``: session id + model.  Capture session id if we didn't
          already pin it via ``--session-id``.
        - ``message`` (``role="user"``): echo of the user prompt — ignore
          (the wrapper already broadcast it).
        - ``message`` (``role="assistant"``, ``delta=true``): one streamed
          text chunk.
        - ``tool_use``: model wants to call a tool.
        - ``tool_result``: tool returned a value.
        - ``error``: ``severity`` warning (loop detected, blocked tool) or
          error (quota, invalid stream) — logged; an error's message is
          kept for the turn's error result.
        - ``result``: terminal event; yield TextComplete (if text was
          accumulated) and TurnComplete (``is_error`` with the CLI's
          message when ``status == "error"``).
        """
        if state is None:
            state = {"completed": False, "error": None}
        out: list[Event] = []
        obj_type = obj.get("type", "")

        if obj_type == "init":
            sid = obj.get("session_id")
            if sid and not self._provider_session_id:
                self._provider_session_id = sid
            return out

        if obj_type == "message":
            role = obj.get("role", "")
            if role == "user":
                # Wrapper already broadcast the user message — skip.
                return out
            if role == "assistant":
                content = obj.get("content", "")
                if not isinstance(content, str) or not content:
                    return out
                self._status = SessionStatus.STREAMING
                text_buffer.append(content)
                out.append(TextDelta(text=content))
            return out

        if obj_type == "tool_use":
            tool_name = obj.get("tool_name", "")
            tool_id = obj.get("tool_id", "")
            params = obj.get("parameters", {}) or {}
            if tool_id:
                tool_uses_in_flight[tool_id] = tool_name
            # Flush any accumulated text first so the UI shows
            # "thinking text…" then the tool call rather than the call
            # showing up before the text it was preceded by.
            if text_buffer:
                out.append(TextComplete(text="".join(text_buffer)))
                text_buffer.clear()
            out.append(ToolUse(
                tool_use_id=tool_id,
                tool_name=tool_name,
                tool_input=params if isinstance(params, dict) else {},
            ))
            return out

        if obj_type == "tool_result":
            tool_id = obj.get("tool_id", "")
            status = obj.get("status", "success")
            is_error = status == "error"
            # ``output`` is the display text (0.63 also sends it on
            # errors); ``error.message`` is what the model saw.
            if is_error:
                err = obj.get("error", {})
                output = (
                    err.get("message", "") if isinstance(err, dict) else str(err)
                ) or obj.get("output", "")
            else:
                output = obj.get("output", "")
            tool_uses_in_flight.pop(tool_id, None)
            out.append(ToolResult(
                tool_use_id=tool_id,
                output=str(output) if output is not None else "",
                is_error=is_error,
            ))
            return out

        if obj_type == "error":
            message = str(obj.get("message") or "").strip()
            if obj.get("severity") == "error":
                logger.warning("gemini error event: %s", message)
                if message:
                    state["error"] = message
            else:
                logger.info("gemini warning event: %s", message)
            return out

        if obj_type == "result":
            # End of turn.  Flush any accumulated text and emit
            # TurnComplete with usage stats if present.
            if text_buffer:
                out.append(TextComplete(text="".join(text_buffer)))
                text_buffer.clear()
            stats = obj.get("stats", {}) or {}
            usage = {}
            if isinstance(stats, dict):
                # Normalize a few common keys.  The full stats blob is
                # noisy (per-model breakdowns); we surface only the
                # rolled-up tokens.
                if "input_tokens" in stats:
                    usage["input_tokens"] = stats.get("input_tokens", 0)
                if "output_tokens" in stats:
                    usage["output_tokens"] = stats.get("output_tokens", 0)
                if "total_tokens" in stats:
                    usage["total_tokens"] = stats.get("total_tokens", 0)
                if "cached" in stats:
                    usage["cache_read_input_tokens"] = stats.get("cached", 0)
                models = stats.get("models")
                if isinstance(models, dict) and models:
                    logger.debug("gemini turn ran on %s", ", ".join(models))
            is_error = obj.get("status") == "error"
            result_text: str | None = None
            if is_error:
                err = obj.get("error")
                result_text = (
                    (err.get("message") if isinstance(err, dict) else None)
                    or state.get("error")
                    or "Gemini CLI reported an error."
                )
            state["completed"] = True
            out.append(TurnComplete(
                usage=usage, is_error=is_error, result=result_text,
                session_id=self._provider_session_id or "",
            ))
            return out

        # Anything else is informational; log and skip.
        logger.debug("Unhandled gemini event type: %s", obj_type)
        return out

    # ------------------------------------------------------------------
    # Argv / env construction
    # ------------------------------------------------------------------

    def _options(self) -> dict:
        return dict(self._config.harness_options or {})

    def _approval_mode(self) -> str:
        mode = self._options().get(gcat.APPROVAL_MODE)
        return mode if mode in gcat.APPROVAL_MODES else gcat.DEFAULT_APPROVAL_MODE

    def _build_argv(self, prompt: str) -> list[str]:
        """Construct the ``gemini`` argv for this turn."""
        argv: list[str] = [
            _gemini_executable(),
            # ``--prompt`` runs in non-interactive headless mode.  Prompt
            # is the next positional argument.
            "--prompt", prompt,
            # ``--skip-trust`` so the CLI doesn't refuse to run headless
            # in directories it doesn't know about.  We trust the cwd
            # ourselves at the wrapper level.
            "--skip-trust",
            "--output-format", "stream-json",
            # Tool approval is enforced at the wrapper level via the
            # conversational-checkpoint policy; by default let the CLI
            # auto-approve everything (headless runs cannot ask — any
            # "ask" decision becomes "deny").  ``approval_mode`` option.
            "--approval-mode", self._approval_mode(),
        ]

        if self._provider_session_id:
            # A fresh session's first turn PINS the id we generated in
            # _run_lifecycle with --session-id.  Every later turn, and
            # every turn of a resumed session (the id already exists on
            # disk), passes --resume so the CLI loads prior turns.  The
            # CLI rejects --session-id for an existing id ("Session ID
            # ... already exists. Use --resume") and exits.
            if self._session_written():
                argv += ["--resume", self._provider_session_id]
            else:
                argv += ["--session-id", self._provider_session_id]

        if self._config.model:
            argv += ["--model", self._config.model]

        return argv

    def _session_written(self) -> bool:
        """True if the CLI has written this session, so it must be ``--resume``d.

        Decided by the JSONL on disk, not by the turn count, and by the
        CLI's own rule — the file must hold *resumable* content.  Both CLI
        versions write the header line (0.63 also a ``$set`` snapshot) before
        authenticating, so a turn that failed early leaves a stub that
        ``--resume`` rejects ("invalid session identifier") while
        ``--session-id`` refuses its id ("already exists").  Such stubs are
        removed here so the turn can pin the id again.  Remote (SSH)
        sessions write on the remote host, so there a resume id or a
        completed turn is trusted as-is.
        """
        if self._config.ssh_host:
            return bool(self._resume_id) or self._turns > 0
        from .adapter import (
            _gemini_jsonl_candidates,
            _read_gemini_session_id,
            gemini_session_is_resumable,
        )

        sid = self._provider_session_id
        stubs: list[Path] = []
        for cand in _gemini_jsonl_candidates(sid):
            p = Path(cand)
            if gemini_session_is_resumable(p):
                return True
            if p.is_file() and _read_gemini_session_id(p) == sid:
                stubs.append(p)
        for p in stubs:
            try:
                p.unlink()
                logger.info("Removed non-resumable Gemini session stub %s", p)
            except OSError:
                logger.warning("Could not remove Gemini session stub %s", p, exc_info=True)
        return False

    def _option_env(self) -> dict[str, str]:
        """Per-turn env vars for the workspace-settings thinking overrides.

        Only options the chosen model family understands are mapped
        (``thinking_level`` → Gemini 3 and the aliases, ``thinking_budget``
        → Gemini 2.5); values are clamped to what the model accepts.
        Nothing is set for an unset option, so the CLI's defaults apply.
        """
        opts = self._options()
        model = self._config.model
        family = gcat.model_family(model)
        env: dict[str, str] = {}

        level = opts.get(gcat.THINKING_LEVEL)
        if isinstance(level, str) and level in gcat.THINKING_LEVELS and family in ("3", "alias"):
            env[ws.ENV_THINKING_LEVEL] = gcat.clamp_thinking_level(model, level).upper()
            if family == "3" and model:
                # Ids outside the CLI's alias table don't extend chat-base-3.
                env[ws.ENV_LEVEL_MODEL] = model

        budget = opts.get(gcat.THINKING_BUDGET)
        if (
            isinstance(budget, (int, float)) and not isinstance(budget, bool)
            and family == "2.5" and model
        ):
            env[ws.ENV_BUDGET_MODEL] = model
            env[ws.ENV_THINKING_BUDGET] = str(gcat.clamp_thinking_budget(model, int(budget)))
        return env

    def _build_env(self) -> dict[str, str]:
        """Construct the env for the gemini subprocess."""
        env = dict(os.environ)
        # Belt + suspenders: also set the trust env var (in case the
        # CLI's --skip-trust flag is ever renamed/removed).  Trust is also
        # what makes the CLI load the workspace settings file.
        env["GEMINI_CLI_TRUST_WORKSPACE"] = "true"
        # Single process: without this the CLI relaunches itself as a
        # child and the parent ignores SIGINT/SIGTERM (interrupt no-op,
        # kill orphans the worker).
        env["GEMINI_CLI_NO_RELAUNCH"] = "true"
        # Strip markers from other harnesses so gemini doesn't get
        # confused if the wrapper itself was launched from inside one.
        env.pop("CLAUDECODE", None)
        # Per-turn option vars come only from this session's options.
        for k in ws.PER_TURN_ENV_VARS:
            env.pop(k, None)
        env.update(self._option_env())
        # ``~/.gemini/settings.json`` may still select the retired
        # oauth-personal login.  The repo's workspace settings override it;
        # this covers other working directories (it only replaces the
        # user-level choice).
        if env.get("GEMINI_API_KEY") and not env.get(ws.ENV_AUTH_TYPE):
            env["GEMINI_CLI_AUTH_OVERRIDE"] = "gemini-api-key"
        return env

    def _remote_env(self) -> dict[str, str]:
        """Non-secret vars forwarded to an SSH-remote CLI."""
        env = {
            "GEMINI_CLI_TRUST_WORKSPACE": "true",
            "GEMINI_CLI_NO_RELAUNCH": "true",
        }
        env.update(self._option_env())
        return env

    def _maybe_wrap_with_ssh(
        self, local_argv: list[str],
    ) -> tuple[list[str], str | None]:
        """Return ``(argv, cwd)`` to feed ``asyncio.create_subprocess_exec``.

        Local sessions: returns *local_argv* and the configured
        ``project_dir`` as cwd — no SSH involvement.

        SSH sessions: swaps ``local_argv[0]`` (the local gemini path)
        for the resolved remote path and wraps everything in an
        ``ssh ... "cd '<remote_dir>' && VAR='v' exec '<remote_gemini>' ..."``
        argv.  cwd is irrelevant in that case (the remote cwd is set
        inside the SSH command), so we return ``None`` and let
        ``create_subprocess_exec`` inherit the parent's cwd.

        Mirror of :meth:`manager.qwen.session.QwenSessionManager._maybe_wrap_with_ssh`
        — the two providers share the same argv shape (full argv built
        by the wrapper, no ``"$@"`` forwarding), so
        :func:`build_remote_argv` works for both.  The Claude path is
        different because the SDK builds its own argv, hence the
        wrapper-script detour in :mod:`manager._ssh`.

        Gemini spawns a fresh subprocess per turn, which means each
        turn opens an SSH connection.  ``ControlMaster=auto`` +
        ``ControlPersist=60s`` (set in :func:`_ssh.build_ssh_argv`)
        keep a single TCP connection alive across a burst — without
        that we'd pay the SSH handshake on every turn.
        """
        if not self._config.ssh_host:
            return local_argv, self._config.project_dir

        target = SshTarget(
            host=self._config.ssh_host,
            user=self._config.ssh_user,
            key=self._config.ssh_key,
            # Distinct ControlMaster socket per provider so two providers
            # on the same host don't share lifetimes — one's
            # ControlPersist timeout would otherwise tear down the other.
            control_path_prefix="gemini",
        )
        remote_gemini = resolve_remote_cli_path(
            "gemini",
            target,
        )
        remote_cmd = RemoteCommand(
            project_dir=self._config.project_dir,
            remote_cli=remote_gemini,
            # Only non-secret vars: the remote host has its own .env (e.g.
            # GEMINI_API_KEY) set up at install time.  Forwarding the local
            # env would leak credentials (visible in `ps` on the remote).
            env=self._remote_env(),
        )
        # ``local_argv[0]`` is the LOCAL gemini path; the remote path is
        # already embedded inside ``remote_cmd``.  Drop it and pass the
        # rest of the flags as positional args — ``build_remote_argv``
        # shell-quotes each one so prompts with spaces/quotes/newlines
        # survive across SSH intact.
        argv = build_remote_argv(
            target=target,
            remote_cmd=remote_cmd,
            remote_args=local_argv[1:],
        )
        return argv, None

    async def _prewarm(self) -> None:
        """Pre-pay the slow first-prompt costs at session start.

        Without this, the FIRST send() on a fresh session blocks the
        user for several seconds while we either:

        1. **Remote sessions**: open SSH, run ``which gemini`` (2-10s
           for the first call, cached for subsequent calls — see
           :func:`manager._ssh.resolve_remote_cli_path`).
        2. **Local sessions**: cold-start the Node runtime that backs
           the ``gemini`` CLI.  On a fresh boot the Node binary + the
           CLI's JS modules aren't in the OS page cache, so the first
           invocation pays a multi-second I/O hit; later invocations
           are warm.

        Running both probes here moves that latency off the user's
        first prompt and onto the session-open step (which already
        shows a spinner / connecting indicator).

        Best-effort — exceptions are logged and swallowed.  If the
        warmup itself fails, the user will simply pay the cost on the
        real first turn instead, which is no worse than today.

        Mirror of :meth:`manager.qwen.session.QwenSessionManager._prewarm`.
        """
        if self._config.ssh_host:
            try:
                target = SshTarget(
                    host=self._config.ssh_host,
                    user=self._config.ssh_user,
                    key=self._config.ssh_key,
                    control_path_prefix="gemini",
                )
                # resolve_remote_cli_path is synchronous (runs `ssh ...
                # which gemini` via subprocess.run with a 10s timeout) —
                # offload to a worker thread so we don't block the loop.
                await asyncio.get_running_loop().run_in_executor(
                    None,
                    lambda: resolve_remote_cli_path(
                        "gemini",
                        target,
                    ),
                )
            except Exception:
                logger.exception(
                    "Gemini remote CLI path warmup failed for %s; first turn "
                    "will pay the resolution cost instead.",
                    self._local_id,
                )
            return

        # Local-only Node-runtime warmup.  Spawn `gemini --version` with
        # a short timeout — its only purpose is to fault in the Node
        # binary and the CLI's JS bundle so the FS page cache is hot
        # before the user's first real turn.  We don't care about the
        # exit code or output.
        try:
            proc = await asyncio.create_subprocess_exec(
                _gemini_executable(), "--version",
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
            )
            try:
                await asyncio.wait_for(proc.wait(), timeout=10.0)
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
                    "Gemini local CLI warmup exceeded 10s for %s; skipping.",
                    self._local_id,
                )
        except FileNotFoundError:
            # Missing CLI surfaces on the real first turn with a clearer
            # error message; no point duplicating it here.
            pass
        except Exception:
            logger.exception(
                "Gemini local CLI warmup failed for %s; first turn will "
                "pay the cold-start cost instead.",
                self._local_id,
            )
