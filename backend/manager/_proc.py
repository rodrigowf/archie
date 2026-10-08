"""Provider-agnostic process helpers — alive checks, comm lookup, signal escalation.

These are shared between :mod:`manager.claude.session` (where Claude's bundled
SDK subprocess may need force-killing) and :mod:`manager.qwen.session` (where
the per-turn qwen subprocess can in theory be reaped the same way).  The pool's
orphan reaper also reaches for ``_process_alive`` and a per-provider
``looks_like(pid)`` check before sending signals.

Keeping these in their own tiny module means importing them does NOT pull in
``claude-agent-sdk`` (which ``manager.claude.session`` imports at module load).
Crucial for Qwen-only installs where the SDK may not even be present at
import time.
"""

from __future__ import annotations

import asyncio
import errno
import logging
import os
import signal
import time
from pathlib import Path

logger = logging.getLogger(__name__)


def process_alive(pid: int) -> bool:
    """Return True if a process with *pid* exists and we can signal it.

    Uses ``os.kill(pid, 0)`` — the kernel resolves the pid and checks
    permissions but doesn't actually deliver any signal.  Distinguishes
    cleanly between "process is gone" (ESRCH) and "process exists but we
    can't touch it" (EPERM, treated as alive).
    """
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError as e:
        return e.errno != errno.ESRCH


def process_comm(pid: int) -> str | None:
    """Read /proc/<pid>/comm and return its content (the kernel's view of
    the executable basename, capped at 15 chars).  Returns None if the
    process is gone or /proc isn't readable.

    Used as a sanity check before SIGKILL: PIDs are reused by the kernel
    after a process exits, so before nuking pid X we verify it still looks
    like the subprocess we spawned — not some innocent process that
    happened to be assigned the recycled pid.
    """
    try:
        return Path(f"/proc/{pid}/comm").read_text().strip() or None
    except (OSError, FileNotFoundError):
        return None


def looks_like(pid: int, comm_prefix: str) -> bool:
    """Return True iff /proc/<pid>/comm starts with *comm_prefix*."""
    comm = process_comm(pid)
    return comm is not None and comm.startswith(comm_prefix)


def kill_subprocess(
    pid: int,
    *,
    comm_prefix: str,
    sigterm_grace_s: float = 0.5,
) -> bool:
    """Force-kill an orphaned subprocess identified by *pid*.

    Verifies the pid still belongs to a process whose ``/proc/<pid>/comm``
    starts with *comm_prefix* before signalling — the kernel can recycle
    pids immediately after a process exits, and we never want to SIGKILL
    an unrelated process that happened to inherit the number.

    First sends SIGTERM (giving the subprocess *sigterm_grace_s* seconds
    to wind down via its normal handlers — flushing JSONL, etc.); if the
    process is still alive after that, escalates to SIGKILL.  Returns
    True if a signal was sent (process was alive and matched the comm
    prefix), False otherwise.

    Safe to call concurrently from the per-session lifecycle finally and
    from the pool's orphan reaper — the second caller will simply observe
    the process is gone (or no longer matches the prefix) and no-op.
    """
    if not process_alive(pid):
        return False
    if not looks_like(pid, comm_prefix):
        # PID was reused by the kernel for an unrelated process — bail
        # out instead of nuking something innocent.
        logger.info(
            "Skipping kill of pid %d: comm=%r does not start with %r",
            pid, process_comm(pid), comm_prefix,
        )
        return False

    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return False
    except OSError:
        logger.exception("SIGTERM to pid %d failed", pid)

    # Brief grace period — the subprocess can take a moment to flush JSONL
    # before exiting.  Synchronous poll (no asyncio) so this helper is
    # safely callable from sync contexts (e.g. the orphan reaper running
    # in a thread executor).
    end = time.monotonic() + sigterm_grace_s
    while time.monotonic() < end:
        if not process_alive(pid):
            return True
        time.sleep(0.05)

    if process_alive(pid):
        try:
            os.kill(pid, signal.SIGKILL)
            logger.warning(
                "Pid %d (%s*) ignored SIGTERM after %.1fs; sent SIGKILL",
                pid, comm_prefix, sigterm_grace_s,
            )
        except ProcessLookupError:
            return False
        except OSError:
            logger.exception("SIGKILL to pid %d failed", pid)
    return True


# ── Process trees (spawn-per-turn Node CLIs) ─────────────────────────────


def proc_table() -> dict[int, tuple[int, str]]:
    """``{pid: (ppid, starttime)}`` from ``/proc`` (empty off Linux)."""
    table: dict[int, tuple[int, str]] = {}
    try:
        names = os.listdir("/proc")
    except OSError:
        return table
    for name in names:
        if not name.isdigit():
            continue
        try:
            with open(f"/proc/{name}/stat") as f:
                stat = f.read()
        except OSError:
            continue
        # comm may contain spaces/parens: split after the last ')'.
        fields = stat[stat.rfind(")") + 2:].split()
        try:
            table[int(name)] = (int(fields[1]), fields[19])
        except (IndexError, ValueError):
            continue
    return table


def descendants(pid: int) -> list[tuple[int, str]]:
    """Every live descendant of *pid* as ``(pid, starttime)``.

    Node CLIs' shell tools (Gemini, Qwen) spawn commands ``detached`` (their
    own process group), so a group signal misses them and they outlive the CLI.  We
    snapshot the tree *before* signalling (afterwards they are reparented
    and untraceable) and reap survivors once the CLI is gone.
    """
    table = proc_table()
    children: dict[int, list[int]] = {}
    for p, (ppid, _) in table.items():
        children.setdefault(ppid, []).append(p)
    out: list[tuple[int, str]] = []
    stack = list(children.get(pid, []))
    while stack:
        p = stack.pop()
        out.append((p, table[p][1]))
        stack.extend(children.get(p, []))
    return out


def signal_survivors(procs: list[tuple[int, str]], sig: int) -> int:
    """Signal the processes of *procs* that still run (same pid + start time)."""
    if not procs:
        return 0
    table = proc_table()
    n = 0
    for pid, start in procs:
        cur = table.get(pid)
        if cur is None or cur[1] != start:
            continue
        try:
            os.kill(pid, sig)
            n += 1
        except OSError:
            pass
    return n


async def reap_descendants(
    proc: asyncio.subprocess.Process, procs: list[tuple[int, str]], grace_s: float = 3.0,
) -> None:
    """Once *proc* has exited (or after *grace_s*), stop what it left behind."""
    if not procs:
        return
    try:
        await asyncio.wait_for(proc.wait(), timeout=grace_s)
    except (asyncio.TimeoutError, Exception):
        pass
    if signal_survivors(procs, signal.SIGTERM):
        await asyncio.sleep(1.0)
        signal_survivors(procs, signal.SIGKILL)


def signal_group(proc, sig: int) -> None:
    """Signal *proc*'s process group, falling back to the process itself.

    Only a real subprocess spawned with ``start_new_session=True`` leads its
    own group (pgid == pid); anything else (SSH wrapper quirks, test
    doubles) gets a plain signal.  Raises ProcessLookupError when the
    process is gone.
    """
    import asyncio

    if isinstance(proc, asyncio.subprocess.Process):
        try:
            pgid = os.getpgid(proc.pid)
        except ProcessLookupError:
            raise
        except OSError:
            pgid = None
        if pgid == proc.pid:
            os.killpg(pgid, sig)
            return
    proc.send_signal(sig)


def process_tree(proc) -> list[tuple[int, str]]:
    """Descendants of a real local subprocess (e.g. shell commands a CLI ran)."""
    import asyncio

    if not isinstance(proc, asyncio.subprocess.Process):
        return []
    try:
        return descendants(proc.pid)
    except Exception:  # noqa: BLE001 — best effort
        logger.debug("could not list descendants of pid %s", getattr(proc, "pid", None), exc_info=True)
        return []
