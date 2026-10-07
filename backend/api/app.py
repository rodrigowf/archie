"""FastAPI application factory."""

from __future__ import annotations

import asyncio
import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path
from utils.paths import PROJECT_ROOT, is_within_memory

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, RedirectResponse

from manager.auth import AuthManager
from manager.config import ManagerConfig
from manager.loop_watchdog import start_loop_watchdog
from manager.store import SessionStore

from .connections import ConnectionManager
from .indexer import HistoryIndexer, MemoryWatcher
from .pool import SessionPool
from .routes import agents, auth, browser, chat, config, debug, mcp, memory, orchestrator, sessions, skills, uploads, visualizations, voice

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Ensure CLAUDE_CONFIG_DIR is always set, even if not launched via run.sh.
    os.environ.setdefault("CLAUDE_CONFIG_DIR", str(PROJECT_ROOT / ".claude_config"))

    # Event-loop liveness watchdog: forces a process restart if the loop ever
    # stops servicing callbacks for >30s.  Defense against claude-agent-sdk#378 /
    # anyio#695 (busy-loop in _deliver_cancellation pinning the loop forever).
    # systemd restarts the service; sessions resume from their JSONL.
    start_loop_watchdog(asyncio.get_running_loop())

    config = ManagerConfig.load()
    app.state.config = config
    app.state.store = SessionStore(config.project_dir)

    # Detect headless mode: no DISPLAY, or explicit HEADLESS=1 env var
    headless = os.environ.get("HEADLESS", "").lower() in ("1", "true", "yes") or (
        not os.environ.get("DISPLAY") and os.name != "nt"
    )
    app.state.auth = AuthManager(headless=headless)

    app.state.connections = ConnectionManager()
    app.state.pool = SessionPool()
    # Background orphan reaper — last-line defense against leaked
    # bundled-claude subprocesses (per-session SIGKILL inside
    # SessionManager is the primary defense).  Cheap when nothing is
    # leaked: a few os.kill(0) liveness checks every 30s.
    await app.state.pool.start_orphan_reaper()
    # Background dead-session reaper — sweeps for SessionManagers whose
    # receive loop has exited (SSH transport died, subprocess crashed)
    # and closes them with a typed broadcast so the UI learns the truth
    # within a few seconds instead of seeing a frozen "streaming" status.
    await app.state.pool.start_dead_session_reaper()

    project_path = Path(config.project_dir)

    memory_watcher = MemoryWatcher(project_path)
    memory_task = asyncio.create_task(memory_watcher.run())
    app.state.memory_watcher = memory_watcher

    history_indexer = HistoryIndexer(project_path, interval_seconds=300)
    history_task = asyncio.create_task(history_indexer.run())
    app.state.history_indexer = history_indexer

    # Pre-warm the SessionStore cache off the event loop so the first
    # /api/sessions request doesn't pay the cold-cache cost (which can
    # be 30–60s on slow storage like the Jetson's SD card).
    async def _prewarm_sessions() -> None:
        try:
            count = await asyncio.to_thread(lambda: len(app.state.store.list_sessions()))
            logger.info("SessionStore cache pre-warmed (%d sessions)", count)
        except Exception:
            logger.exception("SessionStore pre-warm failed")

    prewarm_task = asyncio.create_task(_prewarm_sessions())
    app.state.prewarm_task = prewarm_task

    # Pre-warm the search server so the embedding model is already loaded
    # when the first search_memory/search_history call arrives (~100s on Jetson).
    async def _prewarm_search_server() -> None:
        try:
            from orchestrator.tools.search import _ensure_server
            proc = await _ensure_server()
            if proc is not None:
                logger.info("Search server pre-warmed (PID %d)", proc.pid)
            else:
                logger.warning("Search server pre-warm failed (will retry on first query)")
        except Exception:
            logger.exception("Search server pre-warm failed")

    search_prewarm_task = asyncio.create_task(_prewarm_search_server())
    app.state.search_prewarm_task = search_prewarm_task

    try:
        yield
    finally:
        # Stop the reaper before close_all so it doesn't race against the
        # final shutdown drain (kill_claude_subprocess on a pid that
        # close_all is also handling would just be a redundant SIGTERM).
        try:
            await app.state.pool.stop_orphan_reaper()
        except Exception:
            logger.exception("Error stopping orphan reaper on shutdown")

        try:
            await app.state.pool.stop_dead_session_reaper()
        except Exception:
            logger.exception("Error stopping dead-session reaper on shutdown")

        # Drain the session pool first so remote SSH + claude children get
        # clean SIGTERMs instead of being orphaned by the backend exiting.
        try:
            await app.state.pool.close_all()
        except Exception:
            logger.exception("Error draining session pool on shutdown")

        # Shut down the warm search server subprocess
        try:
            from orchestrator.tools.search import shutdown_server
            await shutdown_server()
        except Exception:
            logger.exception("Error shutting down search server")

        memory_watcher.stop()
        history_indexer.stop()
        memory_task.cancel()
        history_task.cancel()
        prewarm_task.cancel()
        search_prewarm_task.cancel()
        for task in [memory_task, history_task, prewarm_task, search_prewarm_task]:
            try:
                await task
            except asyncio.CancelledError:
                pass


def _spa_dirs() -> list[tuple[str, Path]]:
    """SPA builds served under a path prefix (cutover, spec 13 §1.7).

    ``apps/web/`` is the current web app: ``dist/`` is served at ``/`` by the
    root catch-all in :func:`create_app`, ``dist-compat/`` (Safari 12 / iOS 12)
    at ``/compat/``.  The previous apps live on, untouched, under ``legacy/``:
    ``legacy/frontend/dist`` at ``/legacy/`` and ``legacy/frontend-compat/dist``
    at ``/legacy_compat/`` (built with those Vite bases).
    """
    root = PROJECT_ROOT
    return [
        ("compat", root / "apps" / "web" / "dist-compat"),
        ("legacy", root / "legacy" / "frontend" / "dist"),
        ("legacy_compat", root / "legacy" / "frontend-compat" / "dist"),
    ]


# The new web app was previewed at /next/ and /next-compat/ before the cutover;
# old bookmarks land on the app that replaced them (hash routing: the path tail
# carries no state).
_RETIRED_PREFIXES = {"next": "/", "next-compat": "/compat/"}


def _register_spa(
    app: FastAPI, prefix: str, root: Path, no_cache: dict[str, str],
) -> bool:
    """Serve the SPA build in *root* at ``/{prefix}/``.

    No-op (returns False) unless ``root/index.html`` exists.  Hashed assets
    are mounted under ``/{prefix}/assets``; other paths return the file when
    it resolves inside *root*, 404 when it escapes *root*, and ``index.html``
    (no-cache) otherwise.  Must be called before the root SPA catch-all.
    """
    index = root / "index.html"
    if not index.is_file():
        return False
    root_resolved = root.resolve()
    assets = root / "assets"
    if assets.is_dir():
        app.mount(
            f"/{prefix}/assets", StaticFiles(directory=assets),
            name=f"{prefix}-assets",
        )

    async def serve_spa_index():
        return FileResponse(index, headers=no_cache)

    async def serve_spa_path(full_path: str):
        candidate = (root / full_path).resolve()
        if not candidate.is_relative_to(root_resolved):
            raise HTTPException(status_code=404)
        if full_path and candidate.is_file():
            return FileResponse(candidate)
        return FileResponse(index, headers=no_cache)

    for path in (f"/{prefix}", f"/{prefix}/"):
        app.add_api_route(
            path, serve_spa_index, methods=["GET"],
            include_in_schema=False, name=f"{prefix}-index",
        )
    app.add_api_route(
        f"/{prefix}/{{full_path:path}}", serve_spa_path, methods=["GET"],
        include_in_schema=False, name=f"{prefix}-spa",
    )
    return True


def create_app() -> FastAPI:
    app = FastAPI(title="Assistant API", lifespan=lifespan)

    # Browser-control extension hub.  Plain object with no async startup, so
    # it's built here rather than in lifespan — that also keeps it available
    # to tests that mount the router without running the full lifespan.
    app.state.browser_hub = browser.BrowserHub()

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],  # Allow all origins for Android app and local dev
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.include_router(sessions.router)
    app.include_router(chat.router)
    app.include_router(auth.router)
    app.include_router(orchestrator.router)
    app.include_router(voice.router)
    app.include_router(mcp.router)
    app.include_router(config.router)
    app.include_router(skills.router)
    app.include_router(agents.router)
    app.include_router(uploads.router)
    app.include_router(debug.router)
    app.include_router(browser.router)
    app.include_router(visualizations.router)
    app.include_router(memory.router)

    # index.html must never be cached — it's the bootstrap that points to
    # the hashed bundle, so a cached copy traps the device on old code
    # forever even after a deploy. Hashed assets under /assets/ and
    # /<prefix>/assets/ are already immutable, so caching them is fine.
    _no_cache = {"Cache-Control": "no-cache, no-store, must-revalidate"}

    # /compat/, /legacy/, /legacy_compat/ (see _spa_dirs), then the retired
    # /next* redirects. Registered before the root SPA catch-all, which would
    # swallow them; each only when its build exists.
    for prefix, spa_root in _spa_dirs():
        _register_spa(app, prefix, spa_root, _no_cache)

    def _redirect_to(target: str):
        async def redirect_retired():
            return RedirectResponse(target, status_code=307)
        return redirect_retired

    for prefix, target in _RETIRED_PREFIXES.items():
        for i, path in enumerate((f"/{prefix}", f"/{prefix}/", f"/{prefix}/{{full_path:path}}")):
            app.add_api_route(
                path, _redirect_to(target), methods=["GET"],
                include_in_schema=False, name=f"{prefix}-retired-{i}",
            )

    # Public files directory (context/public/ — synced across machines, served at URL root).
    # Anything placed under context/public/ is reachable at the matching URL path
    # (e.g. context/public/photo-server/file.py → /photo-server/file.py).
    project_root = PROJECT_ROOT
    context_public = project_root / "context" / "public"
    context_public_resolved = context_public.resolve() if context_public.exists() else None

    # Projects directory (projects/ at repo root). Exposed at /projects/* so
    # build artifacts (rendered videos, generated assets, etc.) are reachable
    # from peripherals on the network without copying them into context/public/.
    projects_dir = project_root / "projects"
    projects_dir_resolved = projects_dir.resolve() if projects_dir.exists() else None

    # Projects directory mount: /projects/<path> → projects/<path>.
    # Registered before the SPA catch-all so /projects never falls through.
    if projects_dir_resolved is not None:
        @app.get("/projects/{full_path:path}")
        async def serve_projects(full_path: str):
            if not full_path:
                raise HTTPException(status_code=404)
            candidate = (projects_dir / full_path).resolve()
            if (
                candidate.is_relative_to(projects_dir_resolved)
                and candidate.is_file()
            ):
                return FileResponse(candidate)
            raise HTTPException(status_code=404)

    # Uploads directory (context/uploads/ — files shared from peripherals).
    # Exposed at /uploads/<path> so a link to an uploaded file resolves on the
    # local network. Written by POST /api/uploads (api/routes/uploads.py).
    # Registered before the SPA catch-all so /uploads never falls through.
    @app.get("/uploads/{full_path:path}")
    async def serve_upload(full_path: str):
        if not full_path:
            raise HTTPException(status_code=404)
        # Resolve fresh each request — the directory may be created lazily by
        # the first upload after startup, so a snapshot taken here at boot
        # could be stale/None.
        uploads_root = (project_root / "context" / "uploads").resolve()
        candidate = (project_root / "context" / "uploads" / full_path).resolve()
        if candidate.is_relative_to(uploads_root) and candidate.is_file():
            return FileResponse(candidate)
        raise HTTPException(status_code=404)

    # Memory directory mount: /memory/<path> → context/memory/<path>.
    # Exposes the structured memory wiki over the local network so a
    # peripheral can fetch / render any memory file directly. Registered
    # before the SPA catch-all so /memory never falls through.
    context_memory = project_root / "context" / "memory"
    context_memory_resolved = context_memory.resolve() if context_memory.exists() else None
    if context_memory_resolved is not None:
        @app.get("/memory")
        @app.get("/memory/")
        async def serve_memory_index():
            candidate = context_memory / "MEMORY.md"
            if candidate.is_file():
                return FileResponse(candidate)
            raise HTTPException(status_code=404)

        @app.get("/memory/{full_path:path}")
        async def serve_memory(full_path: str):
            if not full_path:
                raise HTTPException(status_code=404)
            candidate = context_memory / full_path
            # Symlinks may point into docs/ (context/memory/archie), never elsewhere.
            if is_within_memory(candidate, context_memory) and candidate.is_file():
                return FileResponse(candidate.resolve())
            # Directory listing not supported — return 404. Use the index
            # at /memory/ or fetch specific files.
            raise HTTPException(status_code=404)

    # Serve the production frontend build if it exists
    frontend_dist = project_root / "apps" / "web" / "dist"
    if frontend_dist.exists():
        app.mount("/assets", StaticFiles(directory=frontend_dist / "assets"), name="assets")

        # Same no-cache policy as the compat index — see comment above.
        @app.get("/")
        async def serve_index():
            return FileResponse(frontend_dist / "index.html", headers=_no_cache)

        @app.get("/{full_path:path}")
        async def serve_spa(full_path: str):
            # 1) Check context/public/ first — runtime-served public files
            #    (visualizations, photo-server, downloads, etc.) without rebuild.
            if context_public_resolved is not None and full_path:
                candidate = (context_public / full_path).resolve()
                # Path traversal guard: candidate must stay under context/public/.
                if (
                    candidate.is_relative_to(context_public_resolved)
                    and candidate.is_file()
                ):
                    return FileResponse(candidate)

            # 2) Then check the built frontend dist for static assets.
            file_path = frontend_dist / full_path
            if file_path.exists() and file_path.is_file():
                return FileResponse(file_path)

            # 3) SPA fallback — serve index.html for client-side routing.
            return FileResponse(frontend_dist / "index.html", headers=_no_cache)

    return app
