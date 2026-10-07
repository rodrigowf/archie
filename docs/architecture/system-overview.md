---
name: system-overview
category: archie/architecture
tags: [architecture, components, data-flow, websocket, orchestrator, voice, state, context]
created: 2026-02-23
modified: 2026-10-06
summary: Archie's components and end-to-end data flows (agent chat, orchestrator text, orchestrator voice) and where state lives.
source: curated (consolidated from memory notes assistant/architecture/project-overview.md, assistant/architecture/orchestrator-vision.md, assistant/infrastructure/features_and_integrations_summary.md, assistant/infrastructure/repo_layout_cutover_2026_10.md; verified against code 2026-10-06)
references:
  - backend.md
  - agent-sessions.md
  - orchestrator.md
  - memory-and-search.md
  - ../overview/archie.md
  - ../overview/repo-layout.md
  - ../voice/architecture.md
  - ../voice/lifecycle.md
  - ../harnesses/registry.md
  - ../clients/web.md
  - ../clients/android.md
  - ../infrastructure/topology.md
  - ../infrastructure/context-sync.md
---

# System overview

Archie is one Python backend that hosts two kinds of agents and serves several clients. This page
names the components, follows a message through each of the three main paths, and says where every
piece of state is kept. Start with [overview/archie.md](../overview/archie.md) for what Archie is;
go to the linked docs for each component's internals.

## Components

```
 Clients ─────────────────────────────────────────────────────────────────────────────────────
  apps/web (/ and /compat/)   apps/android (:app-main, :app-lite)   browser extension   scripts
      │ HTTPS + WSS (nginx on the Jetson → 127.0.0.1:8765)                    │
      ▼                                                                       ▼
 Backend (FastAPI, backend/api/app.py) ──────────────────────────────────────────────────────
  REST routes  ·  WS /api/sessions/chat  ·  WS /api/orchestrator/chat  ·  WS /api/browser/ws
  static: apps/web/dist, context/public, /memory, /uploads
      │                                   │
      ▼                                   ▼
  SessionPool (backend/api/pool.py) ─── one orchestrator slot ───► OrchestratorSession
      │ keyed by local_id                                         (backend/orchestrator/session.py)
      ▼                                                             │ OrchestratorAgent loop
  BaseSessionManager subclasses (backend/manager/)                  │ ToolRegistry (24 tools) ──┐
   Claude: claude_agent_sdk → bundled `claude` CLI subprocess       │ model providers           │
   Qwen / Gemini: their CLIs (local, or over SSH)                   ▼                           │
      │                                              Anthropic / OpenAI APIs (text)             │
      │                                              OpenAI Realtime / Qwen-Omni / Gemini Live  │
      ▼                                                    (voice)                              │
  JSONL in context/ ◄──────────────── orchestrator tools drive agent sessions ◄─────────────────┘
      │                                       │
      ▼                                       ▼
  index/history.sqlite3, memory.sqlite3 ◄── search service + warm search server (shared/scripts)
      ▲
  context/memory/ (markdown wiki, incl. archie → docs/)
```

| Component | Where | Doc |
|---|---|---|
| Backend app, routes, static serving | `backend/api/` | [backend.md](backend.md) |
| Agent sessions (pool, managers, harnesses) | `backend/api/pool.py`, `backend/manager/` | [agent-sessions.md](agent-sessions.md), [harnesses/registry.md](../harnesses/registry.md) |
| Orchestrator (agent loop, tools, providers) | `backend/orchestrator/` | [orchestrator.md](orchestrator.md) |
| Voice (realtime providers, relay, VAD) | `backend/orchestrator/providers/*voice*`, `voice_*.py` | [voice/architecture.md](../voice/architecture.md) |
| Memory wiki and search | `context/memory/`, `backend/utils/*_index.py`, `shared/scripts/search-server.py` | [memory-and-search.md](memory-and-search.md) |
| Web client (main + Safari 12 build) | `apps/web/` | [clients/web.md](../clients/web.md) |
| Android clients | `apps/android/` | [clients/android.md](../clients/android.md) |
| Private data repo | `context/` (gitignored, synced Laptop ↔ Jetson) | [context-sync](../infrastructure/context-sync.md) |

**Two agent systems, not to be confused:**

1. **Agent sessions** — Claude Code (via `claude_agent_sdk`), Qwen Code or Gemini CLI subprocesses,
   each a full coding agent with files and shell. Used by chat tabs.
2. **The orchestrator** — a hand-written loop on the Anthropic/OpenAI APIs with its own tools,
   prompt and JSONL; it controls agent sessions but has no shell itself. Used by the orchestrator
   tab and voice. At most one is active.

## Flow 1 — agent chat

1. The client mints a `local_id` for the tab and opens `WS /api/sessions/chat`, sending
   `{"type":"start", "local_id", "resume_sdk_id"?, "resume_from"?}`.
2. `backend/api/routes/chat.py` `_handle_start` re-subscribes to an existing pool entry, or builds a
   `ManagerConfig` with `build_session_config()` (global `assistant_config.json` + per-session
   config: working dir or SSH target, harness, model, MCPs) and calls `SessionPool.create()`.
3. The pool asks the harness registry for the manager class; `ClaudeSessionManager.start()` connects
   a `ClaudeSDKClient`, which spawns the bundled `claude` CLI. The CLI writes
   `context/<sdk_session_id>.jsonl` itself. Watchers get `agent_session_opened`.
4. `session_started` goes back; the client sends `{"type":"send","text":...}`.
5. `pool.send_or_queue()` starts a pool-owned turn task (or queues behind the running one).
   `pool.send()` broadcasts `user_message` and `status: processing`, then iterates
   `sm.send()`: `client.query()` → the persistent receive loop turns SDK messages into typed events
   (`TextDelta`, `ToolUse`, `ToolResult`, `PermissionRequest`, `TurnComplete`, ...).
6. Each event is serialized, stamped with `seq`/`stream_id`, and sent as a binary frame to every
   subscriber of that `local_id` (phone, laptop and iPad can watch the same tab). A reload just
   re-subscribes and replays from its checkpoint.

## Flow 2 — orchestrator, text

1. The client opens `WS /api/orchestrator/chat` and sends `start` (a second orchestrator with another
   `local_id` gets `orchestrator_active`). `OrchestratorSession.start()` loads history from its
   JSONL and registers the tools; `pool.set_orchestrator()` takes the single slot.
2. `send` → `OrchestratorSession.send()` under `_busy_lock`: drain background notifications
   (persisted as `background_notification`, prepended to the prompt), persist the `user` line, run
   `OrchestratorAgent.run()`.
3. The agent builds the system prompt (memory index, private memory, scripts allowlist, live
   sessions), calls the model provider, streams text, and runs requested tools concurrently, up to
   20 rounds. Events go to every orchestrator subscriber.
4. Tools reach the rest of the system through the `context` dict: `open_agent_session` calls
   `pool.create()` (agent tabs auto-appear on clients via the watcher event);
   `send_to_agent_session` hands the turn to `BackgroundAgentRunner`, which drives `pool.send()` in
   the background and later pushes a `Notification`. If the orchestrator is idle, the wake
   callback starts a synthetic turn so it can react.

## Flow 3 — orchestrator, voice

Voice reuses the same `OrchestratorSession`, history, tools and JSONL; only transport and provider
change. The client sends `voice_start`; the backend resolves provider/model/voice
(`voice_registry.resolve_voice_target()`), builds the voice instructions with a token-budgeted
history, and replies with connection info. Two transports:

- **WebRTC direct (OpenAI Realtime)** — the client gets an ephemeral token
  (`POST /api/orchestrator/voice/session`) and connects audio straight to OpenAI. It mirrors every
  data-channel event to the backend as `voice_event`; `OpenAIVoiceProvider` turns them into
  orchestrator events, the backend executes tool calls and returns `voice_command` frames the client
  forwards into the data channel. Audio never touches the backend.
- **WebSocket relay (Qwen-Omni, Gemini Live)** — `backend/orchestrator/voice_relay.py` `VoiceRelay`
  owns the upstream provider socket. The client streams mic PCM as `voice_audio_in` on the
  orchestrator WS; the backend runs VAD (`voice_vad.py`, Silero), forwards audio upstream, and
  broadcasts `voice_audio_out` back.

Transcripts are persisted with `source: "voice_transcription"` (user, prefixed `[voice]`) and
`"voice_response"`. One device owns a voice call; others see it read-only. Details:
[voice/architecture.md](../voice/architecture.md), [voice/lifecycle.md](../voice/lifecycle.md).

## Where state lives

| State | Location | Written by | Notes |
|---|---|---|---|
| Claude Code and orchestrator conversations | `context/<id>.jsonl` | The `claude` CLI; `OrchestratorSession` writer | Filename = `sdk_session_id` / orchestrator `jsonl_id`. SDK side state in `context/<uuid>/` (subagents, tool results) |
| Other harnesses' conversations | `context/chats/*.jsonl` (Gemini also under `~/.gemini/tmp/...`) | Qwen / Gemini CLIs | [harnesses/registry.md](../harnesses/registry.md) |
| Session titles | `context/.titles.json` | `SessionStore.rename_session` / `set_title` | Also visualization titles (`viz:<path>` keys) |
| Per-session config | `context/<sdk_session_id>.config.json` | `PUT /api/sessions/{id}/config`, provider pinning | [agent-sessions.md](agent-sessions.md#per-session-config) |
| Deleted sessions | `context/trash/` | `SessionStore.delete_session` | Soft delete |
| Global settings | `assistant_config.json` (repo root, gitignored, per machine) | `PUT /api/config`, `update_assistant_config` | [backend.md](backend.md#configuration-files) |
| Manager defaults | `.manager.json` (repo root, gitignored) | by hand | Env vars override |
| Secrets | `context/.env`, `context/secrets/`, `context/certs/` | by hand | Never in `docs/` |
| Claude CLI config and credentials | `.claude_config/` (gitignored, **not** synced) | The CLI | `CLAUDE_CONFIG_DIR`; `projects/<mangled>` symlinks to `context/` |
| Memory wiki | `context/memory/` (+ `archie` → `docs/`) | Agents, orchestrator `write_file` | [memory-and-search.md](memory-and-search.md) |
| Search indexes | `index/history.sqlite3`, `index/memory.sqlite3`, `index/session_summaries.sqlite3` (gitignored, per machine) | `index-memory.py` via the backend indexers | Rebuildable from the sources |
| Live sessions | memory of the backend process (`SessionPool`) | — | Lost on restart; clients re-`start` and resume from JSONL |
| Served files | `context/public/`, `context/uploads/` | Skills, `POST /api/uploads` | Served at `/` and `/uploads/` |
| Logs | `logs/` (incl. `remote_console.log`); journald on the Jetson | backend | |

`context/` is a separate private git repo, synced in near real time between the laptop and the
Jetson ([context-sync](../infrastructure/context-sync.md)); `assistant_config.json`, `.claude_config/`
and `index/` are per machine ([topology](../infrastructure/topology.md)).

## Design decisions

- **Stable `local_id` as the key everywhere** — tabs, pool and orchestrator use the client-minted
  id; the harness id is only for history and resume.
- **Turns are owned by the pool, not by a socket** — any number of devices can watch one session,
  and disconnecting never kills work.
- **Event mirroring for WebRTC voice** — audio goes browser ↔ provider directly for latency; the
  backend still sees every event, so tool execution and persistence stay server-side.
- **One orchestrator** — avoids conflicting commands; can be relaxed later.
- **Everything on disk is plain files** — JSONL, markdown, JSON, SQLite — readable and portable
  between machines with the same install path.

Related: [repo layout](../overview/repo-layout.md) for the full directory tree and old → new paths.
