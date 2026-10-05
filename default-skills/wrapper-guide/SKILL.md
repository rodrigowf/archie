---
name: wrapper-guide
description: Understand, navigate, and debug the wrapper application (claude manager, API, frontend). Use when investigating issues, understanding data flow, or making changes to the wrapper.
argument-hint: "[topic]"
allowed-tools: Read, Glob, Grep
---

# Wrapper Application Guide

This skill provides comprehensive knowledge for understanding, navigating, and debugging the wrapper application that powers this assistant.

Topic requested: **$ARGUMENTS**

---

## Architecture Overview

The wrapper consists of three interconnected components:

```
Frontend (React) ──WebSocket/REST──> API (FastAPI) ──SDK──> Manager (Python)
     │                                    │                       │
     │                                    │                       ▼
     │                                    │              Claude Code SDK
     │                                    │                       │
     └────────────────────────────────────┴───────────────────────┘
                          User interaction
```

**Data flows from user input through:**
1. Frontend captures input, sends via WebSocket
2. API routes message to SessionManager
3. Manager wraps Claude SDK, yields typed events
4. API serializes events back to WebSocket
5. Frontend renders streaming updates

---

## 1. Manager Package (`manager/`)

The manager wraps the `claude-agent-sdk` to provide a clean async interface.

### Key Files

| File | Purpose |
|------|---------|
| `session.py` | `SessionManager` - core session lifecycle and message handling |
| `types.py` | Event dataclasses (`TextDelta`, `ToolUse`, etc.) and data types |
| `store.py` | `SessionStore` - reads Claude Code's JSONL session files from disk |
| `config.py` | `ManagerConfig` - loads from JSON file, env vars, or defaults |
| `auth.py` | `AuthManager` - OAuth status checks and login flow |

### SessionManager (`session.py`)

The heart of the manager. Wraps a single Claude Code conversation.

**Dual ID System:**
Each session has two IDs:
- `local_id` — Stable UUID generated at creation time. Used as the primary key throughout the system (pool, tabs, orchestrator). Never changes.
- `sdk_session_id` — The Claude Code SDK's session ID. Only available after the first message is sent. Used for resuming sessions and looking up JSONL files on disk.

**Lifecycle:**
```python
sm = SessionManager(session_id="sdk-id-to-resume", local_id="stable-uuid", config=config)
await sm.start()           # Connect to SDK, returns local_id (stable)
async for event in sm.send("Hello"):  # Stream events
    ...                    # sdk_session_id becomes available after first response
await sm.stop()            # Disconnect
```

**Key methods:**
- `start()` → Connect to SDK, return `local_id` (stable, never changes)
- `send(prompt)` → Yields `Event` objects as response streams
- `command(slash_cmd)` → Send `/compact`, `/help`, etc.
- `interrupt()` → Stop current response
- `stop()` → Disconnect

**Properties:**
- `local_id` — Stable local UUID (primary identifier)
- `sdk_session_id` — Claude SDK session ID (available after first message)
- `session_id` — Alias for `local_id`
- `status`, `cost`, `turns`, `is_active`

**Internal flow in `_process_message()`:**
- `StreamEvent` with `content_block_delta` → `TextDelta` / `ThinkingDelta`
- `SystemMessage` with `subtype="compact"` → `CompactComplete`
- `AssistantMessage` → Iterates blocks: `TextBlock`→`TextComplete`, `ToolUseBlock`→`ToolUse`, `ToolResultBlock`→`ToolResult`
- `UserMessage` with `tool_use_result` → `ToolResult`
- `ResultMessage` → `TurnComplete` (updates cost/turns)

### Event Types (`types.py`)

All events inherit from `Event` base class:

| Event | When | Key Fields |
|-------|------|------------|
| `TextDelta` | Streaming text token | `text` |
| `TextComplete` | Full text block done | `text` |
| `ThinkingDelta` | Streaming thinking | `text` |
| `ThinkingComplete` | Thinking block done | `text` |
| `ToolUse` | Tool invoked | `tool_use_id`, `tool_name`, `tool_input` |
| `ToolResult` | Tool finished | `tool_use_id`, `output`, `is_error` |
| `TurnComplete` | Turn finished | `cost`, `usage`, `num_turns`, `session_id` |
| `CompactComplete` | Compaction done | `trigger` ("manual"/"auto") |

### SessionStore (`store.py`)

Reads Claude Code's session files from disk (JSONL format).

**Session location:**
```
$CLAUDE_CONFIG_DIR/projects/<mangled-path>/<session-id>.jsonl
```
Where `<mangled-path>` = `/home/rodrigo/assistant` → `-home-rodrigo-assistant`

**Key methods:**
- `list_sessions()` → All sessions sorted by recency
- `get_session(id)` → Full `SessionDetail` with messages
- `get_preview(id, max)` → Last N messages
- `delete_session(id)` → Remove JSONL file

**JSONL line types:** `user`, `assistant`, `system`, `progress`, `file-history-snapshot`, `queue-operation`

### ManagerConfig (`config.py`)

Configuration loading order: JSON file → env vars → defaults

| Field | Env Var | Default |
|-------|---------|---------|
| `project_dir` | `MANAGER_PROJECT_DIR` | Parent of manager/ |
| `model` | `MANAGER_MODEL` | None (SDK default) |
| `permission_mode` | `MANAGER_PERMISSION_MODE` | "default" |
| `max_budget_usd` | `MANAGER_MAX_BUDGET_USD` | None |
| `max_turns` | `MANAGER_MAX_TURNS` | None |

### AuthManager (`auth.py`)

OAuth authentication helper.

- `is_authenticated()` → Runs `claude auth status`, parses JSON
- `login()` → Runs `claude setup-token` (opens browser)

**Important:** Unsets `CLAUDECODE` env var to run auth commands inside a session.

---

## 2. API Package (`api/`)

FastAPI server providing REST + WebSocket interfaces.

### Key Files

| File | Purpose |
|------|---------|
| `app.py` | Application factory with lifespan (startup/shutdown) |
| `routes/chat.py` | WebSocket endpoint for real-time streaming |
| `routes/sessions.py` | REST endpoints for session CRUD |
| `routes/auth.py` | Auth status and login endpoints |
| `pool.py` | `SessionPool` - manages all active sessions, keyed by local_id |
| `connections.py` | `ConnectionManager` - tracks active WebSocket sessions |
| `serializers.py` | Converts manager Events to JSON dicts |
| `models.py` | Pydantic response models |
| `deps.py` | FastAPI dependency injection |
| `indexer.py` | Background tasks for memory/history indexing |

### Application Startup (`app.py`)

The `lifespan` context manager initializes:
1. `ManagerConfig.load()` → `app.state.config`
2. `SessionStore(project_dir)` → `app.state.store`
3. `AuthManager()` → `app.state.auth`
4. `ConnectionManager()` → `app.state.connections`
5. `MemoryWatcher` → Watches memory folder, indexes on change
6. `HistoryIndexer` → Periodic (120s) history indexing

**CORS:** `allow_origins=["*"]` (Android apps and local dev servers)

**Static SPAs** (`_spa_dirs()`): `/` → `frontend/dist`, `/compat/` → `frontend/dist-compat`, `/legacy/` → `_old/frontend/dist`, `/legacy_compat/` → `_old/frontend-compat/dist`; the retired `/next/` and `/next-compat/` 307-redirect to `/` and `/compat/` (tests: `tests/test_spa_routes.py`).

### SessionPool (`pool.py`)

Manages all active sessions, keyed by stable `local_id`.

**Key methods:**
- `create(config, local_id=None, resume_sdk_id=None, fork=False)` → Creates session, returns `local_id`
- `send(local_id, prompt)` → Yields events from session
- `has(local_id)` → Check if session exists
- `list_sessions()` → Returns list with both `session_id` (local) and `sdk_session_id`

Sessions are announced to watchers immediately on creation (no waiting for SDK ID).

### WebSocket Protocol (`routes/chat.py`)

Endpoint: `WS /api/sessions/chat`

**Client → Server messages:**
```json
{"type": "start", "local_id": "..."}                              // New session (frontend-generated UUID)
{"type": "start", "local_id": "...", "resume_sdk_id": "..."}     // Resume session
{"type": "start", "local_id": "...", "fork": true}               // Fork session
{"type": "send", "text": "..."}                                   // Send message
{"type": "command", "text": "/compact"}                            // Slash command
{"type": "interrupt"}                                              // Stop response
{"type": "stop"}                                                   // End session
```

**Server → Client messages:**
```json
{"type": "session_started", "session_id": "..."}
{"type": "status", "status": "connecting|interrupted|disconnected"}
{"type": "text_delta", "text": "..."}
{"type": "text_complete", "text": "..."}
{"type": "thinking_delta", "text": "..."}
{"type": "thinking_complete", "text": "..."}
{"type": "tool_use", "tool_use_id": "...", "tool_name": "...", "tool_input": {...}}
{"type": "tool_result", "tool_use_id": "...", "output": "...", "is_error": false}
{"type": "turn_complete", "cost": 0.01, "num_turns": 1, "session_id": "..."}
{"type": "compact_complete", "trigger": "manual"}
{"type": "error", "error": "...", "detail": "..."}
{"type": "session_stopped"}
```

**Error types:** `invalid_json`, `not_started`, `start_timeout`, `start_failed`, `send_failed`, `command_failed`, `unknown_type`

**Timeout:** 30s for `session.start()`

### REST Endpoints (`routes/sessions.py`, `routes/auth.py`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/sessions` | List all sessions |
| GET | `/api/sessions/{id}` | Get session detail with messages |
| GET | `/api/sessions/{id}/preview?max=5` | Get last N messages |
| DELETE | `/api/sessions/{id}` | Delete session |
| GET | `/api/auth/status` | Check if authenticated |
| POST | `/api/auth/login` | Trigger OAuth login |

### ConnectionManager (`connections.py`)

Tracks active (local_id → WebSocket) pairs for WebSocket routing.

- `connect(local_id, ws)` → Register connection
- `disconnect(local_id)` → Cleanup
- `is_active(local_id)` → Check if connected
- `active_count` → Number of active connections

### Event Serialization (`serializers.py`)

`serialize_event(event)` converts manager Event → dict for JSON:
- `TextDelta` → `{"type": "text_delta", "text": "..."}`
- `ToolUse` → `{"type": "tool_use", "tool_use_id": "...", ...}`
- etc.

### Background Indexers (`indexer.py`)

**MemoryWatcher:**
- Uses `watchfiles` to monitor memory directory
- Triggers `context/scripts/index-memory.py --memory-only` on .md changes
- Debounced 1s

**HistoryIndexer:**
- Runs every 120s
- Hashes session file names/sizes/mtimes
- Only re-indexes if changed
- Triggers `context/scripts/index-memory.py --history-only`

---

## 3. Frontend (`frontend/src/`)

React 18 + Vite + zustand. One source tree, two builds: main (`vite.config.main.ts` → `dist/`, base `/`) and Safari 12 / iOS 12 compat (`vite.config.compat.ts` → `dist-compat/`, base `/compat/`). Dev: `npm run dev` (port 5450) / `npm run dev:compat` (5451); `npm run mock` for a mock backend; `npm run verify` is the full gate. Architecture spec: `docs/frontend-refactor/spec/13-web-architecture.md`; the client protocol it implements: `docs/frontend-refactor/spec/12-client-protocol.md` (conformance fixtures in `shared/protocol-fixtures/`, shared with the Android apps).

The pre-cutover app (the `hooks/useChatInstance.ts` / `context/TabsContext.tsx` / `components/*` design) lives in `_old/frontend/src/` and is served at `/legacy/`.

### Layers

| Folder | Purpose |
|--------|---------|
| `protocol/` | Pure client data layer: `wire/` (server/client frame types with the backend's field names, tolerant `decode.ts`), `reducer/machine.ts` (connection manager + conversation reducer), `types.ts` (domain model), `selectors/`, `history/`, `resume/` (checkpoint) |
| `services/ws/` | `socket.ts` (one WebSocket; binary UTF-8 JSON frames in, always text frames out) and `reconnect.ts` (backoff policy) |
| `services/http/` | `client.ts` (fetch wrapper, typed errors) and `endpoints/` (auth, config, sessions, memory, visuals, voice, debug) |
| `services/sessions/` | `manager.ts` (session directory/lifecycle: open, close, pool sync, watcher), `ConversationRuntime.ts` (feeds every input through the reducer, publishes to stores), `SessionRuntime.ts` (one agent session: `start` handshake, send, interrupt, permissions, rewind), `ArchieRuntime.ts` + `orchestratorChannel.ts` (the orchestrator conversation and its voice bridge) |
| `stores/` | zustand stores: `tabs.ts` (ordered tabs, active tab), `sessionStore.ts` (per-session state), `sessionRegistry.ts` (`local_id → {store, runtime}`), `connection.ts`, `prefs.ts`, … |
| `voice/` | Voice engine: `core/VoiceController.ts`, `transports/` (WebRTC, WS relay), `audio/` |
| `app/` | `App.tsx` (AuthGate → AppShell, starts services), `AppShell.tsx`, `shell/` (rail, drawer, list pane), `workspace/` (tab strip, session switcher, panels) |
| `features/` | Screens: `conversation/` (message list, entries, inline permission card), `tools/` (tool cards), `composer/`, `history/`, `memory/`, `visuals/`, `settings/`, `session-settings/`, `auth/`, `voice/` |
| `ui/`, `styles/`, `platform/` | Primitives and controls, CSS (tokens from `design/tokens`), platform shims (storage, remote log, low-end detection, polyfills) |

### Key rules

- **Stable `local_id`.** Each conversation is keyed by a frontend-generated `local_id` (UUID); the tab, the session store and the backend pool all use it. Resuming a history session opens a new `local_id` with `resume_sdk_id` set.
- **One ordered queue per conversation.** Every frame goes through `stepConversation` in the runtime; the reducer is pure and covered by the protocol fixtures (tool results are never lost; tool calls stay ordered with interleaved text).
- **Runtimes live outside React** (`sessionRegistry`), so sockets survive re-renders and hidden panels. Only an explicit close sends `POST /api/sessions/{local_id}/close`; page unload never stops a session.
- **Tabs persist** in `localStorage` (`tabs:v1`); chat tabs are re-derived from `GET /api/sessions/pool/live` on load.

### Session flow

1. New session → `services/sessions/manager.ts` `openSession()` creates a `local_id`, a store and a `SessionRuntime`.
2. On socket open the runtime sends `{"type": "start", "local_id": ..., "resume_sdk_id"?: ..., "resume_from"?: checkpoint}`.
3. The backend answers `session_started`; frames stream in and are reduced in order.
4. Sending: the composer calls the runtime's send; the user entry is added optimistically and confirmed by the stream.

## Common Debugging Scenarios

### WebSocket Connection Issues

**Symptoms:** UI shows disconnected, messages not flowing

**Debug steps:**
1. Check browser console for WS errors
2. Verify backend is running on port 8765
3. Check backend logs for connection events
4. In dev, check the Vite proxy (`/api` → `http://localhost:8765`, override with `ARCHIE_BACKEND`)

**Key breakpoints:**
- `api/routes/chat.py:20` → `chat_ws()` function
- `frontend/src/services/ws/socket.ts` → socket open/close/error events
- `frontend/src/services/ws/reconnect.ts` → reconnect policy

### Session Start Timeout

**Symptoms:** Error "Session start timed out" after 30s

**Debug steps:**
1. Check if Claude Code is authenticated: `GET /api/auth/status`
2. Check backend logs for SDK connection issues
3. Verify `CLAUDE_CONFIG_DIR` is set correctly
4. Try `claude auth status` in terminal

**Key locations:**
- `api/routes/chat.py:103` → 30s timeout
- `manager/session.py:90` → `start()` method

### Message Not Appearing

**Symptoms:** Sent message but no response

**Debug steps:**
1. Check WS connection state in UI status bar
2. Look for errors in browser console
3. Check if `send_failed` error received
4. Verify backend logs show message received

**Key locations:**
- `frontend/src/services/sessions/SessionRuntime.ts` → send / queue
- `api/routes/chat.py:126` → `_handle_send()`

### Tool Results Not Attaching

**Symptoms:** Tool shows "pending" forever

**Debug steps:**
1. Check if `tool_result` event received (browser Network tab, WS frames)
2. Verify `tool_use_id` matches between `tool_use` and `tool_result`
3. Look at the reducer's `tool_result` handling

**Key location:**
- `frontend/src/protocol/reducer/machine.ts` → `tool_result` case (live frames and history)
- `shared/protocol-fixtures/` → add a fixture reproducing the bug; web and Android both run it

### History Not Loading

**Symptoms:** Resume session but no messages

**Debug steps:**
1. Check REST response: `GET /api/sessions/{id}`
2. Verify session file exists in `$CLAUDE_CONFIG_DIR/projects/<mangled>/`
3. Check the runtime's cold open / history load (REST reload)

**Key locations:**
- `frontend/src/protocol/history/` and `frontend/src/services/sessions/SessionRuntime.ts` (cold open, pagination)
- `manager/store.py:149` → `get_session()`

---

## Quick Reference: File Locations

**To understand message flow:**
1. `frontend/src/protocol/reducer/machine.ts` - State machine (+ `services/sessions/SessionRuntime.ts`)
2. `api/routes/chat.py` - WebSocket handler
3. `manager/session.py` - SDK wrapper

**To understand data types:**
1. `manager/types.py` - Python event types
2. `api/serializers.py` - Event → JSON
3. `frontend/src/protocol/wire/server.ts` (frames) and `frontend/src/protocol/types.ts` (domain model)

**To understand session storage:**
1. `manager/store.py` - Read JSONL files
2. `api/routes/sessions.py` - REST endpoints

**To understand authentication:**
1. `manager/auth.py` - OAuth helpers
2. `api/routes/auth.py` - Auth endpoints
3. `frontend/src/features/auth/AuthGate.tsx` - Auth UI

---

## Making Changes

When modifying the wrapper:

1. **Adding new event types:**
   - Add to `manager/types.py`
   - Handle in `manager/session.py:_process_message()`
   - Add to `api/serializers.py`
   - Add the frame to `frontend/src/protocol/wire/server.ts` and `wire/decode.ts`
   - Handle in `frontend/src/protocol/reducer/machine.ts` (+ a fixture in `shared/protocol-fixtures/`)
   - Mirror on Android (`android/core/protocol`, `android/core/conversation`)

2. **Adding new REST endpoints:**
   - Add route in `api/routes/`
   - Add Pydantic models in `api/models.py`
   - Add a client function in `frontend/src/services/http/endpoints/`

3. **Adding new WebSocket messages:**
   - Handle in `api/routes/chat.py` message loop
   - Add to `frontend/src/protocol/wire/` (client.ts / server.ts)
   - Handle in the reducer / runtime

4. **Modifying session storage:**
   - Update `manager/store.py`
   - Update `api/models.py` if response shape changes
   - Update `frontend/src/services/http/types.ts` / `protocol/types.ts` if needed
