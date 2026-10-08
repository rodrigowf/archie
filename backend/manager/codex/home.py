"""Where Codex lives on this machine: the CLI binary and the ``CODEX_HOME``.

Kept free of heavy imports so the adapter, the catalog loader and the
session manager can all share it.

CODEX_HOME
----------

Codex keeps its login (``auth.json``), SQLite state, caches and session
rollouts under ``CODEX_HOME`` (default ``~/.codex``).  Archie prefers a
dedicated home, ``~/.codex-archie``, created by::

    CODEX_HOME=~/.codex-archie codex login --device-auth

The installer (``--with-codex``) seeds that home with a ``config.toml`` and
symlinks its ``sessions/`` to ``<repo>/context/codex/sessions`` so rollouts
sync between machines with the rest of ``context/``.

When the dedicated home has no ``auth.json`` we fall back to the shared
``~/.codex`` (which the VS Code extension and the ``codex`` TUI also use).
That works out of the box, but rollouts then stay in ``~/.codex/sessions``
(the adapter searches both places) and the two clients share one login.

**Never copy ``auth.json`` between homes or machines**: ChatGPT refresh
tokens rotate, so two homes holding the same token family break each other
(``refresh_token_reused``).  A second home needs its own login.

``ARCHIE_CODEX_HOME`` overrides the choice outright.
"""

from __future__ import annotations

import glob
import os
import platform
import shutil
from pathlib import Path

from utils.paths import PROJECT_ROOT

# The dedicated home's default location and the one-line login hint the
# catalog shows when Archie is running on the shared fallback.
ARCHIE_HOME_DEFAULT = "~/.codex-archie"
LOGIN_HINT = "CODEX_HOME=~/.codex-archie codex login --device-auth"

# Name sent as ``clientInfo.name`` on ``initialize``.  Codex records it as
# the rollout's ``session_meta.payload.originator``, which is how the
# discoverer tells Archie's sessions apart from VS Code's in a shared home.
CLIENT_NAME = "archie"


def shared_home() -> Path:
    """The CLI's own default home (``$CODEX_HOME`` if exported, else ``~/.codex``)."""
    explicit = os.environ.get("CODEX_HOME")
    if explicit:
        return Path(explicit).expanduser()
    return Path.home() / ".codex"


def dedicated_home() -> Path:
    """Archie's dedicated home (``~/.codex-archie``), whether or not it exists."""
    return Path(ARCHIE_HOME_DEFAULT).expanduser()


def codex_home() -> Path:
    """The ``CODEX_HOME`` Archie runs Codex with.

    ``ARCHIE_CODEX_HOME`` wins; otherwise the dedicated home when it holds a
    login, otherwise the shared home.
    """
    override = os.environ.get("ARCHIE_CODEX_HOME")
    if override:
        return Path(override).expanduser()
    dedicated = dedicated_home()
    if (dedicated / "auth.json").is_file():
        return dedicated
    return shared_home()


def is_shared_fallback() -> bool:
    """True when Archie runs on the shared home (no dedicated login)."""
    if os.environ.get("ARCHIE_CODEX_HOME"):
        return False
    try:
        return codex_home().resolve() == shared_home().resolve()
    except OSError:
        return codex_home() == shared_home()


def _is_own_project(project_dir: str | Path | None) -> bool:
    if project_dir is None:
        return True
    try:
        return Path(project_dir).resolve() == Path(PROJECT_ROOT).resolve()
    except OSError:
        return False


def context_sessions_dir(project_dir: str | Path | None = None) -> Path:
    """``<repo>/context/codex/sessions`` — the synced rollout directory."""
    return Path(project_dir or PROJECT_ROOT) / "context" / "codex" / "sessions"


def sessions_roots(project_dir: str | Path | None = None) -> list[Path]:
    """Every directory that may hold Archie's rollouts, de-duplicated.

    ``context/codex/sessions`` first (the dedicated home's ``sessions/``
    symlinks there), then the active home's ``sessions/``, then the shared
    home's — a session started on the fallback stays reachable after the
    user creates the dedicated login.

    The homes are per machine, not per project: they are only searched for
    this install's own project dir (``PROJECT_ROOT``), so a store opened on
    another directory (tests, a second checkout) sees only its own
    ``context/codex/sessions``.
    """
    candidates = [context_sessions_dir(project_dir)]
    if _is_own_project(project_dir):
        candidates += [
            codex_home() / "sessions",
            dedicated_home() / "sessions",
            shared_home() / "sessions",
        ]
    out: list[Path] = []
    seen: set[str] = set()
    for c in candidates:
        try:
            key = str(c.resolve())
        except OSError:
            key = str(c)
        if key in seen:
            continue
        seen.add(key)
        out.append(c)
    return out


def home_for_thread(thread_id: str) -> Path:
    """The home whose ``sessions/`` holds *thread_id*'s rollout.

    ``thread/resume`` only finds rollouts under the running ``CODEX_HOME``,
    so a session started on the shared fallback must be resumed there even
    after a dedicated login exists.  Defaults to :func:`codex_home`.
    """
    active = codex_home()
    pattern = f"*/*/*/rollout-*-{thread_id}.jsonl"
    for candidate in (active, dedicated_home(), shared_home()):
        sessions = candidate / "sessions"
        try:
            if sessions.is_dir() and next(sessions.glob(pattern), None) is not None:
                if candidate != active and not (candidate / "auth.json").is_file():
                    continue
                return candidate
        except OSError:
            continue
    return active


# Variables stripped from Codex's environment.  The backend's env carries
# OPENAI_API_KEY (voice / orchestrator); Codex would use it for auth and
# bill API credits instead of the ChatGPT plan.  Set CODEX_API_KEY to opt
# into API-key auth deliberately.
_STRIPPED_ENV = ("CLAUDECODE", "OPENAI_API_KEY", "OPENAI_BASE_URL", "CODEX_HOME")


def codex_env(codex_home_dir: Path | None = None) -> dict[str, str]:
    """Environment for a local Codex process."""
    env = {k: v for k, v in os.environ.items() if k not in _STRIPPED_ENV}
    env["CODEX_HOME"] = str(codex_home_dir or codex_home())
    return env


# ---------------------------------------------------------------------------
# CLI binary
# ---------------------------------------------------------------------------

_TRIPLES = {
    ("Linux", "x86_64"): ("codex-linux-x64", "x86_64-unknown-linux-musl"),
    ("Linux", "aarch64"): ("codex-linux-arm64", "aarch64-unknown-linux-musl"),
    ("Linux", "arm64"): ("codex-linux-arm64", "aarch64-unknown-linux-musl"),
    ("Darwin", "x86_64"): ("codex-darwin-x64", "x86_64-apple-darwin"),
    ("Darwin", "arm64"): ("codex-darwin-arm64", "aarch64-apple-darwin"),
}


def _native_in_package(package_root: Path) -> Path | None:
    """The native binary inside an ``@openai/codex`` npm package, if present."""
    key = (platform.system(), platform.machine())
    pkg, triple = _TRIPLES.get(key, (None, None))
    if pkg is None:
        return None
    for vendor in (
        package_root / "node_modules" / "@openai" / pkg / "vendor",
        package_root.parent / pkg / "vendor",  # hoisted layout
        package_root / "vendor",
    ):
        exe = vendor / triple / "bin" / "codex"
        if exe.is_file():
            return exe
    return None


def codex_executable() -> str:
    """Resolve the Codex binary to exec.

    Order: ``CODEX_CLI_PATH``; the **native** binary inside the global npm
    package (so the PID we track is the agent itself — the npm ``bin/codex.js``
    is a Node shim that forwards signals, and a SIGKILL to it would orphan the
    Rust child); ``codex`` on ``PATH``; the VS Code extension's bundled copy;
    finally the bare name (spawning then fails with a clear error).
    """
    explicit = os.environ.get("CODEX_CLI_PATH")
    if explicit:
        return explicit
    on_path = shutil.which("codex")
    if on_path:
        # Follow the npm shim (<prefix>/bin/codex -> ../lib/node_modules/@openai/codex/bin/codex.js)
        # back to its package and prefer the native binary next to it.
        try:
            real = Path(on_path).resolve()
            if real.name == "codex.js":
                native = _native_in_package(real.parent.parent)
                if native is not None:
                    return str(native)
        except OSError:
            pass
    # Global npm roots without ``codex`` on PATH (nvm installs not on the
    # backend's PATH): look for the package directly.
    for root in sorted(
        glob.glob(os.path.expanduser("~/.nvm/versions/node/*/lib/node_modules/@openai/codex")),
        key=lambda p: os.path.getmtime(p) if os.path.exists(p) else 0,
        reverse=True,
    ) + ["/usr/local/lib/node_modules/@openai/codex", "/usr/lib/node_modules/@openai/codex"]:
        native = _native_in_package(Path(root))
        if native is not None:
            return str(native)
    if on_path:
        return on_path
    bundled = sorted(
        glob.glob(os.path.expanduser("~/.vscode/extensions/openai.chatgpt-*/bin/*/codex")),
        key=lambda p: os.path.getmtime(p),
        reverse=True,
    )
    if bundled:
        return bundled[0]
    return "codex"
