"""Run allowlisted project scripts: one place for the allowlists, the command line, timeouts,
output capture and background jobs.

Two callers:

* the orchestrator's ``run_script`` tool (``orchestrator/tools/run_script.py``) — scripts listed
  in ``context/memory/ORCHESTRATOR_SCRIPTS.md`` (``path:`` lines in fenced blocks);
* the scripts API (``api/routes/scripts.py``) that visualizations call — scripts listed in
  ``context/scripts/allowed_scripts.json`` **or** in ``ORCHESTRATOR_SCRIPTS.md`` (already approved
  for unattended single-shot runs).

Both lists are read from disk on every call, so an edit takes effect without a backend restart.

``allowed_scripts.json``::

    {"scripts": {
        "context/scripts/tuya_lamps.py": {
            "description": "Control the apartment lamps.",
            "usage": "on|off|bright <name|all> [value]",
            "timeout": 60,           # seconds, a request without background (cap 300)
            "max_runtime": 1800      # seconds, a background job (cap 4 h)
        }}}

A script runs from the repo root with no shell: ``.py`` files through ``context/scripts/run.sh``
(the venv, ``context/.env`` loaded), anything else directly if it is executable. Arguments are
passed as separate argv entries, so nothing in them is interpreted by a shell. The process gets
its own process group, so a timeout or a cancel also kills whatever it started.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import signal
import time
import uuid
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import utils.paths as paths

logger = logging.getLogger(__name__)

ALLOWLIST_NAME = "allowed_scripts.json"
ORCHESTRATOR_LIST_NAME = "ORCHESTRATOR_SCRIPTS.md"

# A request waits this long by default; an allowlist entry or the request can change it up to the cap.
DEFAULT_TIMEOUT_S = 60
MAX_TIMEOUT_S = 300
# Background jobs.
DEFAULT_MAX_RUNTIME_S = 1800
MAX_RUNTIME_CAP_S = 4 * 3600
# What a run may write to stdin.
MAX_INPUT_CHARS = 1_000_000
# Output kept per stream: the head of a request's output, the tail of a job's (the newest lines
# are the ones a page polling a job wants).
MAX_OUTPUT_CHARS = 200_000
# Requests waiting for a script at once, and background jobs running at once.
MAX_CONCURRENT_RUNS = 4
MAX_RUNNING_JOBS = 4
# Finished jobs are kept this long (and at most this many) for pages to read.
JOB_RETENTION_S = 3600
MAX_KEPT_JOBS = 50

_PATH_LINE_RE = re.compile(r"^\s*path:\s*[`'\"]?([^`'\"\s]+)[`'\"]?\s*$", re.MULTILINE)


class ScriptError(Exception):
    """A request the runner refuses. ``status`` is the HTTP status the API answers with."""

    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message


@dataclass(frozen=True)
class AllowedScript:
    path: str  # repo-relative, normalized
    description: str = ""
    usage: str = ""
    timeout: int = DEFAULT_TIMEOUT_S
    max_runtime: int = DEFAULT_MAX_RUNTIME_S
    source: str = "allowlist"  # "allowlist" | "orchestrator"

    def as_dict(self) -> dict[str, Any]:
        return {
            "script": self.path,
            "description": self.description,
            "usage": self.usage,
            "timeout": self.timeout,
            "max_runtime": self.max_runtime,
            "source": self.source,
            "exists": (paths.PROJECT_ROOT / self.path).is_file(),
        }


# ---------------------------------------------------------------------------
# Allowlists
# ---------------------------------------------------------------------------

def allowlist_file() -> Path:
    return paths.PROJECT_ROOT / "context" / "scripts" / ALLOWLIST_NAME


def orchestrator_list_file() -> Path:
    return paths.PROJECT_ROOT / "context" / "memory" / ORCHESTRATOR_LIST_NAME


def normalize(path: str) -> str:
    """A script path relative to the repo root (``./x``, absolute in-repo paths and ``a/../b``
    collapse). A path outside the repo comes back unchanged, so it matches no allowlist entry."""
    p = (path or "").strip()
    root = paths.PROJECT_ROOT
    try:
        resolved = (root / Path(p).expanduser()).resolve()
        return str(resolved.relative_to(root.resolve()))
    except (ValueError, OSError):
        return p


def load_orchestrator_paths() -> set[str]:
    """The ``path:`` lines of ``ORCHESTRATOR_SCRIPTS.md``, normalized."""
    f = orchestrator_list_file()
    if not f.is_file():
        return set()
    try:
        text = f.read_text(encoding="utf-8")
    except OSError:
        logger.exception("Failed to read script allowlist %s", f)
        return set()
    return {normalize(m) for m in _PATH_LINE_RE.findall(text)}


def _int_in(value: Any, default: int, cap: int) -> int:
    try:
        n = int(value)
    except (TypeError, ValueError):
        return default
    return max(1, min(n, cap))


def load_allowlist() -> dict[str, AllowedScript]:
    """Every script the scripts API may run: ``allowed_scripts.json`` first (its descriptions and
    limits win), then the orchestrator's list."""
    out: dict[str, AllowedScript] = {}
    f = allowlist_file()
    if f.is_file():
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            logger.exception("Failed to read script allowlist %s", f)
            data = {}
        entries = data.get("scripts") if isinstance(data, dict) else None
        if isinstance(entries, dict):
            for raw_path, meta in entries.items():
                meta = meta if isinstance(meta, dict) else {}
                p = normalize(str(raw_path))
                out[p] = AllowedScript(
                    path=p,
                    description=str(meta.get("description") or ""),
                    usage=str(meta.get("usage") or ""),
                    timeout=_int_in(meta.get("timeout"), DEFAULT_TIMEOUT_S, MAX_TIMEOUT_S),
                    max_runtime=_int_in(meta.get("max_runtime"), DEFAULT_MAX_RUNTIME_S, MAX_RUNTIME_CAP_S),
                )
    for p in sorted(load_orchestrator_paths()):
        out.setdefault(p, AllowedScript(path=p, source="orchestrator"))
    return out


def resolve(script: str, allowed: dict[str, AllowedScript] | None = None) -> tuple[AllowedScript, Path]:
    """The allowlist entry and file for *script*, or :class:`ScriptError` (403 / 404)."""
    allowed = load_allowlist() if allowed is None else allowed
    key = normalize(script)
    entry = allowed.get(key)
    if entry is None:
        raise ScriptError(
            403,
            f"Script {script!r} is not allowlisted. Add it to context/scripts/{ALLOWLIST_NAME} "
            "(see the create-viz skill).",
        )
    target = (paths.PROJECT_ROOT / key).resolve()
    if not target.is_relative_to(paths.PROJECT_ROOT.resolve()) or not target.is_file():
        raise ScriptError(404, f"Script {key!r} is allowlisted but the file does not exist.")
    return entry, target


def command_for(target: Path, args: list[str]) -> list[str]:
    """``.py`` → ``run.sh <script> args…`` (the venv, ``context/.env``); an executable → itself."""
    if target.suffix == ".py":
        run_sh = paths.PROJECT_ROOT / "context" / "scripts" / "run.sh"
        return [str(run_sh), str(target), *args]
    if not os.access(target, os.X_OK):
        raise ScriptError(400, f"{target.name} is neither a .py file nor executable.")
    return [str(target), *args]


def clean_args(args: Any) -> list[str]:
    if args is None:
        return []
    if not isinstance(args, list):
        raise ScriptError(400, "args must be a list of strings")
    # Numbers and booleans become strings; anything nested is refused.
    out = []
    for a in args:
        if isinstance(a, (dict, list)) or a is None:
            raise ScriptError(400, "each arg must be a string or a number")
        out.append(str(a).lower() if isinstance(a, bool) else str(a))
    return out


# ---------------------------------------------------------------------------
# Running
# ---------------------------------------------------------------------------

def clip(text: str, limit: int = MAX_OUTPUT_CHARS) -> str:
    if len(text) <= limit:
        return text
    return text[:limit] + f"\n... [truncated at {limit} chars]"


def parse_json(stdout: str) -> Any:
    """``stdout`` as JSON when the whole of it is a JSON document, else ``None``."""
    s = stdout.strip()
    if not s or s[0] not in "{[":
        return None
    try:
        return json.loads(s)
    except ValueError:
        return None


async def _spawn(cmd: list[str], with_input: bool) -> asyncio.subprocess.Process:
    return await asyncio.create_subprocess_exec(
        *cmd,
        cwd=str(paths.PROJECT_ROOT),
        stdin=asyncio.subprocess.PIPE if with_input else asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        start_new_session=True,  # own process group: a kill reaches the script's children too
    )


def _kill_group(proc: asyncio.subprocess.Process) -> None:
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        try:
            proc.kill()
        except ProcessLookupError:
            pass


async def run_command(cmd: list[str], timeout: float, stdin: str | None = None) -> dict[str, Any]:
    """Run *cmd* to completion (or until *timeout*). Returns ``exit_code`` (``None`` on timeout),
    ``stdout``, ``stderr`` (clipped), ``timed_out`` and ``duration_ms``."""
    started = time.monotonic()
    proc = await _spawn(cmd, stdin is not None)
    out: list[bytes] = []
    err: list[bytes] = []

    async def collect() -> None:
        # Our own readers (not communicate()), so what was printed before a timeout is kept.
        await _feed(proc, stdin)
        await asyncio.gather(_read_all(proc.stdout, out), _read_all(proc.stderr, err))
        await proc.wait()

    timed_out = False
    try:
        await asyncio.wait_for(collect(), timeout=timeout)
    except asyncio.TimeoutError:
        timed_out = True
        _kill_group(proc)
        await proc.wait()
    except asyncio.CancelledError:
        _kill_group(proc)
        raise
    return {
        "exit_code": None if timed_out else proc.returncode,
        "stdout": clip(b"".join(out).decode(errors="replace")),
        "stderr": clip(b"".join(err).decode(errors="replace")),
        "timed_out": timed_out,
        "duration_ms": int((time.monotonic() - started) * 1000),
    }


async def _feed(proc: asyncio.subprocess.Process, text_in: str | None) -> None:
    if text_in is None or proc.stdin is None:
        return
    try:
        proc.stdin.write(text_in.encode())
        await proc.stdin.drain()
    except (BrokenPipeError, ConnectionResetError):
        pass
    finally:
        proc.stdin.close()


async def _read_all(stream: asyncio.StreamReader | None, sink: list[bytes], limit: int = MAX_OUTPUT_CHARS * 4) -> None:
    """Read *stream* to EOF; keep at most about *limit* bytes (clip() trims the text later)."""
    if stream is None:
        return
    kept = 0
    while chunk := await stream.read(4096):
        if kept < limit:
            sink.append(chunk)
            kept += len(chunk)


_run_slots: asyncio.Semaphore | None = None


def _slots() -> asyncio.Semaphore:
    global _run_slots
    if _run_slots is None:
        _run_slots = asyncio.Semaphore(MAX_CONCURRENT_RUNS)
    return _run_slots


async def run_allowed(script: str, args: Any = None, timeout: Any = None, stdin: Any = None) -> dict[str, Any]:
    """The scripts API's synchronous run: allowlist check, then wait for the script."""
    entry, target = resolve(script)
    argv = clean_args(args)
    text_in = _clean_input(stdin)
    limit = _int_in(timeout, entry.timeout, MAX_TIMEOUT_S) if timeout is not None else entry.timeout
    cmd = command_for(target, argv)
    slots = _slots()
    if slots.locked():
        raise ScriptError(429, f"{MAX_CONCURRENT_RUNS} scripts are already running; try again shortly.")
    async with slots:
        logger.info("scripts API: run %s %s (timeout %ss)", entry.path, argv, limit)
        result = await run_command(cmd, limit, text_in)
    logger.info(
        "scripts API: %s exited %s in %d ms%s", entry.path, result["exit_code"],
        result["duration_ms"], " (timed out)" if result["timed_out"] else "",
    )
    return {"script": entry.path, "args": argv, **result, "json": parse_json(result["stdout"])}


def _clean_input(stdin: Any) -> str | None:
    if stdin is None:
        return None
    if not isinstance(stdin, str):
        stdin = json.dumps(stdin)
    if len(stdin) > MAX_INPUT_CHARS:
        raise ScriptError(400, f"input is longer than {MAX_INPUT_CHARS} characters")
    return stdin


# ---------------------------------------------------------------------------
# Background jobs
# ---------------------------------------------------------------------------

def _iso(ts: float | None) -> str | None:
    return None if ts is None else datetime.fromtimestamp(ts, tz=timezone.utc).isoformat()


class _Tail:
    """The newest ``limit`` characters of a stream, and how many were dropped before them."""

    def __init__(self, limit: int = MAX_OUTPUT_CHARS) -> None:
        self.limit = limit
        self.parts: deque[str] = deque()
        self.size = 0
        self.dropped = 0

    def add(self, text: str) -> None:
        self.parts.append(text)
        self.size += len(text)
        while self.size > self.limit and self.parts:
            extra = self.size - self.limit
            head = self.parts[0]
            if len(head) <= extra:
                self.parts.popleft()
                self.size -= len(head)
                self.dropped += len(head)
            else:
                self.parts[0] = head[extra:]
                self.size -= extra
                self.dropped += extra

    def text(self) -> str:
        return "".join(self.parts)


@dataclass
class Job:
    id: str
    script: str
    args: list[str]
    max_runtime: int
    started: float = field(default_factory=time.time)
    finished: float | None = None
    status: str = "running"  # running | succeeded | failed | timed_out | cancelled
    exit_code: int | None = None
    error: str | None = None
    stdout: _Tail = field(default_factory=_Tail)
    stderr: _Tail = field(default_factory=_Tail)
    proc: asyncio.subprocess.Process | None = None
    task: asyncio.Task | None = None

    def as_dict(self, output: bool = True) -> dict[str, Any]:
        end = self.finished if self.finished is not None else time.time()
        d: dict[str, Any] = {
            "job_id": self.id,
            "script": self.script,
            "args": self.args,
            "status": self.status,
            "exit_code": self.exit_code,
            "started_at": _iso(self.started),
            "finished_at": _iso(self.finished),
            "duration_ms": int((end - self.started) * 1000),
            "max_runtime": self.max_runtime,
        }
        if self.error:
            d["error"] = self.error
        if output:
            out = self.stdout.text()
            d.update({
                "stdout": out,
                "stderr": self.stderr.text(),
                "stdout_dropped": self.stdout.dropped,
                "stderr_dropped": self.stderr.dropped,
                "json": parse_json(out) if self.status != "running" and not self.stdout.dropped else None,
            })
        return d


class JobManager:
    """Background script runs, kept in memory on this backend (a restart cancels running jobs and
    forgets finished ones)."""

    def __init__(self) -> None:
        self._jobs: dict[str, Job] = {}

    def _prune(self) -> None:
        now = time.time()
        done = [j for j in self._jobs.values() if j.finished is not None]
        for j in done:
            if now - (j.finished or now) > JOB_RETENTION_S:
                self._jobs.pop(j.id, None)
        done = sorted((j for j in self._jobs.values() if j.finished is not None), key=lambda j: j.finished or 0)
        while len(self._jobs) > MAX_KEPT_JOBS and done:
            self._jobs.pop(done.pop(0).id, None)

    def running_count(self) -> int:
        return sum(1 for j in self._jobs.values() if j.status == "running")

    async def start(self, script: str, args: Any = None, max_runtime: Any = None, stdin: Any = None) -> Job:
        entry, target = resolve(script)
        argv = clean_args(args)
        text_in = _clean_input(stdin)
        limit = _int_in(max_runtime, entry.max_runtime, MAX_RUNTIME_CAP_S) if max_runtime is not None else entry.max_runtime
        cmd = command_for(target, argv)
        self._prune()
        if self.running_count() >= MAX_RUNNING_JOBS:
            raise ScriptError(429, f"{MAX_RUNNING_JOBS} background jobs are already running.")
        job = Job(id=uuid.uuid4().hex[:12], script=entry.path, args=argv, max_runtime=limit)
        job.proc = await _spawn(cmd, text_in is not None)
        self._jobs[job.id] = job
        logger.info("scripts API: job %s started %s %s (max %ss)", job.id, entry.path, argv, limit)
        job.task = asyncio.create_task(self._watch(job, text_in))
        return job

    async def _pump(self, stream: asyncio.StreamReader | None, tail: _Tail) -> None:
        if stream is None:
            return
        while chunk := await stream.read(4096):
            tail.add(chunk.decode(errors="replace"))

    async def _watch(self, job: Job, text_in: str | None) -> None:
        proc = job.proc
        assert proc is not None
        await _feed(proc, text_in)
        pumps = asyncio.gather(self._pump(proc.stdout, job.stdout), self._pump(proc.stderr, job.stderr))
        try:
            await asyncio.wait_for(asyncio.shield(pumps), timeout=job.max_runtime)
            await proc.wait()
            job.exit_code = proc.returncode
            job.status = "succeeded" if proc.returncode == 0 else "failed"
        except asyncio.TimeoutError:
            _kill_group(proc)
            await proc.wait()
            await pumps
            job.status = "timed_out"
        except asyncio.CancelledError:
            _kill_group(proc)
            await proc.wait()
            pumps.cancel()
            job.status = "cancelled"
        except Exception as e:  # never leave a job "running" forever
            _kill_group(proc)
            job.status = "failed"
            job.error = str(e)
            logger.exception("scripts API: job %s crashed", job.id)
        finally:
            job.finished = time.time()
            logger.info("scripts API: job %s %s (exit %s)", job.id, job.status, job.exit_code)

    def get(self, job_id: str) -> Job | None:
        self._prune()
        return self._jobs.get(job_id)

    def list(self) -> list[Job]:
        self._prune()
        return sorted(self._jobs.values(), key=lambda j: j.started, reverse=True)

    async def cancel(self, job_id: str) -> Job | None:
        job = self._jobs.get(job_id)
        if job is None:
            return None
        if job.status == "running" and job.task is not None:
            job.task.cancel()
            try:
                await job.task
            except asyncio.CancelledError:
                pass
        return job

    async def shutdown(self) -> None:
        for job in list(self._jobs.values()):
            if job.status == "running":
                await self.cancel(job.id)
