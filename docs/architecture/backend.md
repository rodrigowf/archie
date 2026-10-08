---
name: backend
category: archie/architecture
tags: [backend, fastapi, routes, static-serving, spa, config, paths, startup, testing]
created: 2026-02-23
modified: 2026-10-08
summary: The FastAPI backend — app factory, startup tasks, every route module, static/SPA serving, config files, how to run and test.
source: curated (consolidated from memory notes assistant/architecture/project-overview.md, assistant/infrastructure/repo_layout_cutover_2026_10.md, assistant/infrastructure/features_and_integrations_summary.md, docs/projects/frontend-refactor/inventory/01-backend-api.md; verified against code 2026-10-06)
references:
  - system-overview.md
  - agent-sessions.md
  - orchestrator.md
  - memory-and-search.md
  - ../voice/architecture.md
  - ../harnesses/registry.md
  - ../clients/web.md
  - ../clients/browser-extension.md
  - ../integrations/visualizations-and-sharing.md
  - ../infrastructure/deployment.md
  - ../infrastructure/jetson-server.md
  - ../infrastructure/ssh-remote-execution.md
  - ../operations/debugging.md
  - ../overview/repo-layout.md
---

# Backend

The backend is one FastAPI process (`uvicorn api.app:create_app --factory`, port **8765**) that
serves the REST API, the two WebSockets clients talk to (`/api/sessions/chat`,
`/api/orchestrator/chat`), the built web apps, and static files out of `context/`. All Python lives
in `backend/`: packages `api/`, `manager/`, `orchestrator/`, `utils/`, plus `tests/`, `vendor/`
(Silero VAD model), `requirements*.txt` and `pyproject.toml`. Module names are top-level (`api`,
`manager`, ...) because `shared/scripts/run.sh` puts `backend/` on `PYTHONPATH`.

For how the pieces fit together see [system-overview.md](system-overview.md). Agent sessions are
in [agent-sessions.md](agent-sessions.md), the orchestrator in [orchestrator.md](orchestrator.md),
search in [memory-and-search.md](memory-and-search.md).

## File map

| Path | What it is |
|---|---|
| `backend/api/app.py` | `create_app()` factory, `lifespan()` startup/shutdown, SPA and static routes |
| `backend/api/pool.py` | `SessionPool` — every live agent session plus the single orchestrator ([agent-sessions.md](agent-sessions.md)) |
| `backend/api/routes/*.py` | One router per area (table below) |
| `backend/api/session_factory.py` | `build_session_config()` — resolves a `ManagerConfig` from global + per-session config; shared by the chat WS and the orchestrator's `open_agent_session` |
| `backend/api/indexer.py` | `MemoryWatcher` and `HistoryIndexer` background tasks ([memory-and-search.md](memory-and-search.md)) |
| `backend/api/serializers.py` | `serialize_event()` — typed manager events → wire JSON |
| `backend/api/models.py` | Pydantic response models |
| `backend/api/connections.py`, `deps.py` | `ConnectionManager`; FastAPI dependencies (`get_pool`, `get_store`) |
| `backend/manager/` | Session managers wrapping the harness CLIs, `SessionStore`, `ManagerConfig`, `AuthManager`, loop watchdog |
| `backend/orchestrator/` | The orchestrator agent, model and voice providers, tools |
| `backend/utils/paths.py` | Every repo/context path (below) |
| `backend/utils/` (rest) | Search indexes and service, MCP config resolution, time words |

## Startup and shutdown (`lifespan()` in `backend/api/app.py`)

In order:

1. `CLAUDE_CONFIG_DIR` defaults to `<repo>/.claude_config` if the launcher did not set it.
2. `start_loop_watchdog()` (`backend/manager/loop_watchdog.py`) — a daemon thread that exits the
   process if the event loop stops servicing callbacks (see [agent-sessions.md](agent-sessions.md#loop-watchdog)).
3. `ManagerConfig.load()` → `app.state.config`; `SessionStore(config.project_dir)` → `app.state.store`.
4. `AuthManager(headless=...)` — headless when `HEADLESS=1|true|yes` or there is no `DISPLAY`.
5. `SessionPool()`, then `start_orphan_reaper()` (every 30 s) and `start_dead_session_reaper()` (every 5 s).
6. `MemoryWatcher` task (re-indexes memory on file change) and `HistoryIndexer` task
   (`interval_seconds=300`).
7. `_prewarm_sessions` — fills the `SessionStore` cache off the loop (cold listing can take 30–60 s
   on the Jetson's SD card).
8. `_prewarm_search_server` — starts the warm search server (`orchestrator.tools.search._ensure_server`)
   so the first search does not pay the model load.

On shutdown: stop both reapers, `pool.close_all()` (so local and SSH `claude` children get a clean
SIGTERM instead of being orphaned), `shutdown_server()` for the search server, then cancel the
watcher/indexer/prewarm tasks.

## Routes

All `/api/*` routers are registered before the static routes. CORS is open (`allow_origins=["*"]`,
no credentials).

| Module (`backend/api/routes/`) | Prefix / paths | Purpose |
|---|---|---|
| `sessions.py` | `/api/sessions` | History list (`GET ""`), live pool (`GET /pool/live`), detail, paginated `/messages`, `/preview`, `PATCH /rename`, `DELETE` (soft delete to `context/trash/`), `/duplicate`, `/truncate`, `/fork`, `POST /inject` (push a user message into a live session), `POST /{local_id}/close`, `POST /{local_id}/permission` (REST twin of `permission_response`), `GET/PUT /{session_id}/config` |
| `chat.py` | `WS /api/sessions/chat` | Agent-session socket: `start`, `send`, `command`, `interrupt`, `compact`, `permission_response`, `stop` |
| `orchestrator.py` | `WS /api/orchestrator/chat` | Orchestrator socket: text turns, voice signaling/relay, wake callback ([orchestrator.md](orchestrator.md)) |
| `voice.py` | `/api/orchestrator/voice/session`, `/voice/models`, `/audio`, `/models`, `/models/audio` | Ephemeral voice credentials / connection info, voice model list, audio upload for voice messages, orchestrator model lists ([voice architecture](../voice/architecture.md)) |
| `session_config.py` | (no router) | `load_session_config()` / `save_session_config()` for `context/<sdk_session_id>.config.json`, used by `sessions.py` and the session factory |
| `config.py` | `/api/config` | `GET`/`PUT` the global `assistant_config.json`; `/openai-key` (hands the raw key to LAN clients for the Android Whisper wake-word confirm); `/providers` (registered harnesses); `/harness/qwen/models`; `/voice/google/models` |
| `auth.py` | `/api/auth` | `/status`, `/login`, `/credentials` — whether the backend's bundled Claude Code CLI has valid credentials (not client auth) |
| `mcp.py` | `/api/mcp` | `/servers`, `/servers/{name}` — MCP servers from `.claude_config/.claude.json` and `.mcp.json` (`backend/utils/mcp_config.py`) |
| `skills.py`, `agents.py` | `/api/skills`, `/api/agents` | Discovery lists for the config UI |
| `memory.py` | `/api/memory/tree` | Markdown tree of `context/memory/` for the memory browser (file content comes from `/memory/<path>`) |
| `visualizations.py` | `/api/visualizations` | List HTML files under `context/public/` with titles, `PATCH /rename`, `GET/POST /cast` (show on the Fire TV) ([visualizations](../integrations/visualizations-and-sharing.md)) |
| `uploads.py` | `/api/uploads` | `POST` a file (≤ 200 MB) into `context/uploads/`, returns its `/uploads/...` link |
| `debug.py` | `/api/debug/log` | Remote console: clients POST log lines, appended to `logs/remote_console.log` ([debugging](../operations/debugging.md)) |
| `browser.py` | `/api/browser/status`, `POST /api/browser/command`, `WS /api/browser/ws` | Hub for the Chrome extension ([browser-extension](../clients/browser-extension.md)); the WS needs the `BROWSER_CONTROL_TOKEN`, `command` is loopback-only |

The wire protocol of the two main sockets (frames, `session_started`, seq/replay, permissions,
voice signaling) is specified in `docs/specs/12-client-protocol.md`; the as-is
REST contract in `docs/projects/frontend-refactor/inventory/01-backend-api.md` (it predates the move to
`backend/` but its shapes are still accurate).

## Static and SPA serving

Registered after the API, in this order (earlier wins):

| URL | Served from | Notes |
|---|---|---|
| `/compat/` | `apps/web/dist-compat` | Safari 12 build ([web client](../clients/web.md)) |
| `/legacy/` | `legacy/frontend/dist` | Frozen old web app |
| `/legacy_compat/` | `legacy/frontend-compat/dist` | Frozen old compat app |
| `/next/...`, `/next-compat/...` | 307 → `/`, `/compat/` | Retired pre-cutover preview URLs (`_RETIRED_PREFIXES`) |
| `/projects/<path>` | repo `projects/` | Only if the dir exists at startup |
| `/uploads/<path>` | `context/uploads/` | Resolved per request (dir may appear after startup) |
| `/memory`, `/memory/` | `context/memory/MEMORY.md` | |
| `/memory/<path>` | raw file under `context/memory/` | No directory listing |
| `/assets/*`, `/` | `apps/web/dist` | `index.html` sent with `Cache-Control: no-cache, no-store, must-revalidate` |
| `/<anything else>` | 1) `context/public/<path>`, 2) `apps/web/dist/<path>`, 3) `index.html` | Visualizations, photo server, downloads, PWA files |

`_spa_dirs()` returns the three prefixed SPAs; `_register_spa()` mounts each only when its
`index.html` exists (hashed `assets/` mounted separately, path-traversal guarded, SPA fallback to
`index.html`). Tests: `backend/tests/test_spa_routes.py`.

## Auth

There is **no client authentication** on any REST or WebSocket endpoint except the browser-extension
channel. The trust model is "anyone on the LAN". TLS (self-signed) is terminated by nginx on the
Jetson, which proxies to `127.0.0.1:8765` ([jetson-server](../infrastructure/jetson-server.md)).
`/api/auth/*` is only about the backend's own Claude CLI credentials in `.claude_config/`.

## Configuration files

| File | Read by | Contents |
|---|---|---|
| `assistant_config.json` (repo root, gitignored) | `backend/api/routes/config.py` `_load_config()`; re-read on every session start by `build_session_config()` | Global settings: `working_directory` (an entry id) + `working_directory_history` (entries with optional `ssh_host`/`ssh_user`/`ssh_key`/`claude_config_dir`), `enabled_mcps`, `chrome_extension`, `provider` (harness id), `harness_model` (per harness), `default_model` / `default_audio_model` / `summarizer_model` (orchestrator), `default_voice_provider/model/name/transcription_language/endpoint`, `voice_recording_enabled`, `voice_vad_threshold` (0.28), `voice_vad_min_silence_ms` (2500), `voice_mic_gain`. Missing keys are filled from `_default_config()` |
| `.manager.json` (repo root, gitignored) | `ManagerConfig.load()` (`backend/manager/config.py`) | Base `ManagerConfig`: `project_dir`, `provider`, `model`, `permission_mode` (default `"default"`), `max_budget_usd`, `max_turns`, `mcp_servers`, `extra_args`, SSH fields. Env vars override it: `MANAGER_PROJECT_DIR`, `ASSISTANT_PROVIDER`, `MANAGER_MODEL`, `MANAGER_PERMISSION_MODE`, `MANAGER_MAX_BUDGET_USD`, `MANAGER_MAX_TURNS` |
| `context/<sdk_session_id>.config.json` | `session_config.py` | Per-session overrides ([agent-sessions.md](agent-sessions.md#per-session-config)) |
| `.mcp.json`, `.claude_config/.claude.json` | `backend/utils/mcp_config.py` | MCP server definitions (both sources merged) |
| `context/.env` (private) | sourced by `run.sh`; `config.py` falls back to parsing it for `OPENAI_API_KEY` | API keys and tokens (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `BROWSER_CONTROL_TOKEN`, provider keys). Never commit or print values |

`ManagerConfig.provider` is validated against the harness registry
([registry.md](../harnesses/registry.md)); an unknown name raises at load time.

## Paths (`backend/utils/paths.py`)

`PROJECT_ROOT` is the repo root (`backend/utils/paths.py` → three levels up). Every module builds
paths from it — never from `__file__` or the cwd.

| Function | Path |
|---|---|
| `get_context_dir()` / `get_sessions_dir()` | `context/` (Claude JSONL files live at its root) |
| `get_chats_dir()` | `context/chats/` (other harnesses) |
| `get_memory_dir()` | `context/memory/` |
| `get_docs_dir()` | `docs/` |
| `get_memory_link_targets()` | `(docs/,)` — directories the memory tree may link into (`context/memory/archie` → `docs/`) |
| `is_within_memory(path)` | True inside `context/memory/` or a link target |
| `get_public_dir()` | `context/public/` (served at URL root) |
| `get_uploads_dir()` | `context/uploads/` |
| `get_trash_dir()` | `context/trash/` (soft-deleted sessions) |
| `get_index_dir()` | `index/` (search indexes) |
| `get_titles_path()` | `context/.titles.json` |
| `get_session_path(id)` | `context/<id>.jsonl` |

## Running

From the repo root:

```bash
context/scripts/run.sh -m uvicorn api.app:create_app --factory --host 0.0.0.0 --port 8765
./start.sh                                   # same, in the background, log in logs/api_<timestamp>.log
.venv/bin/python -m uvicorn api.app:create_app --factory --app-dir backend --port 8765   # without run.sh
```

`shared/scripts/run.sh` (reached as `context/scripts/run.sh`) exports
`PYTHONPATH=<repo>/backend`, `CLAUDE_CONFIG_DIR=<repo>/.claude_config`, sources `context/.env`, and
on aarch64 sets `LD_PRELOAD=.../libgomp.so.1` (fixes "cannot allocate memory in static TLS block"
when torch loads). On the Jetson the backend runs as `agentic-backend.service`
([jetson-server](../infrastructure/jetson-server.md), [deployment](../infrastructure/deployment.md)).

The backend also serves the web app, but only what is built: `apps/web/dist` must exist for `/` and
for `context/public/` files to be served (the public-file lookup lives inside the dist catch-all).

## Testing

```bash
context/scripts/run.sh -m pytest backend/tests -v
context/scripts/run.sh -m pytest backend/tests/test_foo.py::TestClass::test_method -v
```

pytest reads `backend/pyproject.toml`. Mock external dependencies with `unittest.mock`. About 85
test modules cover the pool, managers, store, orchestrator, voice, search and routes.

Tests must never write into the real `context/` (it is the private, synced repo). `backend/tests/conftest.py`
enforces it: every `OrchestratorSession` built during the run persists into a pytest sandbox, and at
the end of the run any new entry in `context/`, `context/chats/` or `context/trash/` whose name is
not a UUID or a Gemini `session-*` file fails the run. Build stores and sessions on `tmp_path`.
(Before 2026-10-08 the suite left `context/orch-1.jsonl` and an `indexed-session.*` /
`qwen-sess-1.*` pair in `context/trash/` on every run.)

## Pitfalls

- **Unknown paths return 200 + `index.html`**, never 404 (SPA fallback). To check whether a
  visualization exists, look at the content type or body, not the status.
- **No `HEAD`**: the static routes are `GET`-only; `HEAD` returns 405.
- **`index.html` must never be cached** — a cached bootstrap traps a device on old code after a
  deploy. Hashed assets are safe to cache.
- **Server→client WebSocket frames are binary** (UTF-8 JSON via `send_bytes`); client→server frames
  must be text (`receive_text`). A binary client frame drops the socket.
- **Config is re-read per session**, not cached: `build_session_config()` reloads `.manager.json`
  and `assistant_config.json` on every call, so a config edit applies to the next session without a
  restart. Before this existed the orchestrator used a startup snapshot and opened sessions with the
  wrong working directory.
- **`GET /api/config/openai-key` hands out the raw key** to any LAN client (needed by the Android
  wake-word confirm). Same trust model as the rest of the API.
- **Restart the Jetson backend with `sudo systemctl restart agentic-backend.service`**, not by
  killing the PID; don't `pkill -f uvicorn` from an agent's Bash tool (the pattern matches its own
  shell).

## History

- 2026-10-05: the Python packages moved from the repo root into `backend/`; module names unchanged.
  The rebuilt web app took over `/`, the old ones moved to `/legacy/` and `/legacy_compat/`, and the
  `/next/` preview URLs became redirects.
- 2026-10-06: `context/memory/archie` became a symlink to `docs/`; `paths.py` gained
  `get_docs_dir()` / `get_memory_link_targets()` / `is_within_memory()` so the memory routes and
  search follow it.

Related: [repo layout](../overview/repo-layout.md), [SSH remote execution](../infrastructure/ssh-remote-execution.md) (working-directory entries with `ssh_host`).
