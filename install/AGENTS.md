# AGENTS.md

This file is the canonical project-instructions document for coding agents (Claude Code, Qwen Code). It lives in `context/AGENTS.md` (the private gitignored data repo); `CLAUDE.md` and `QWEN.md` at the project root are symlinks pointing here, so both CLIs read the same content from the location they each natively expect.

# Personal Assistant

**A transparent, hackable AI assistant that evolves with you.**

## Philosophy

This project prioritizes **transparency over polish**. The entire system is ~1000 lines of Python and a simple React frontend—no magic, no hidden complexity. You can read every line of code that touches files, executes commands, or stores data.

You're not just an AI running inside this codebase. You *are* the assistant, and you can modify yourself: fix bugs, add features, improve skills, even rewrite the wrapper application you're running inside. This is a tool that grows with its user, not one that forces adaptation to someone else's vision.

**Core principles:**
- **Developer-native**: A proper development environment, not a chatbot bolted onto a messaging app
- **Self-improving**: Teach it something once, turn it into a reusable automation
- **Local-first**: Conversations, memory, and credentials stay on the user's machine

## What You Can Do

- **Chat** through a web interface with real-time streaming
- **Talk** using realtime voice mode — speak through the orchestrator via WebRTC
- **Execute** code, manage files, run shell commands—full agent capabilities
- **Remember** context across sessions with searchable conversation history
- **Automate** workflows through custom skills (slash commands)
- **Evolve** by creating new skills and modifying your own behavior

---

## Project Structure

This project separates **public framework** from **private data** for easy sharing and environment migration:

```
assistant/                    # PUBLIC - shareable framework
├── backend/                  # Python backend (on PYTHONPATH via run.sh; module names unchanged)
│   ├── api/                  # FastAPI server (REST + WebSocket)
│   ├── manager/              # Session managers (Claude / Qwen / Gemini)
│   ├── orchestrator/         # Orchestrator agent — controls chat sessions
│   │   └── providers/        # Model providers (Anthropic, OpenAI, voice)
│   ├── utils/                # Shared Python utilities (paths.py — PROJECT_ROOT = repo root)
│   ├── tests/                # Backend pytest suite
│   ├── vendor/               # Silero VAD model
│   └── requirements*.txt, pyproject.toml
├── apps/                     # Client apps
│   ├── web/                  # Web app (React + Vite): one project, two builds — dist/ (served at /) + dist-compat/ (Safari 12 / iOS 12, served at /compat/)
│   ├── android/              # Android project (Gradle multi-module): :app-main (main phone app) + :app-lite (voice-first app for old devices, API 21)
│   ├── android-device/       # Companion device app
│   ├── browser-extension/    # Chrome MV3 extension (loaded unpacked) for driving a logged-in browser
│   ├── design-tokens/        # Shared design-token source → CSS variables (web) + Kotlin theme (Android)
│   └── protocol-fixtures/    # Client-protocol conformance fixtures shared by web + Android tests
├── shared/                   # General-purpose, shareable agent tooling (actual files)
│   ├── skills/               # General-purpose skills
│   ├── scripts/              # General-purpose scripts (run.sh, setup-context.sh, ...)
│   └── agents/               # General-purpose agents
├── legacy/                   # Previous web, compat and Android apps (web served at /legacy/ and /legacy_compat/)
├── docs/                     # Design docs, history, assets
├── install/                  # Installers and templates for a fresh installation (install.sh / install.ps1 at the root are the entry points)
├── infra/sync/               # Optional context-sync service for two-machine setups
├── start.sh                  # Starts the backend (logs to logs/)
├── .claude_config/           # Claude Code SDK config (when --with-claude)
│   └── skills → ../context/skills  # SDK skill discovery
├── index/                    # Vector search index (gitignored)
├── logs/                     # Backend logs + remote_console.log (gitignored)
└── .venv/                    # Python virtual environment

context/                      # PRIVATE - Standalone git repo, gitignored here
├── AGENTS.md                 # This file (symlinked from project root as CLAUDE.md / QWEN.md)
├── *.jsonl                   # Conversation JSONL files
├── <uuid>/                   # SDK state directories (subagents, tool-results)
├── memory/                   # Memory markdown files
├── public/                   # Static files served at URL root (downloads, visualizations, etc.)
├── skills/                   # Symlinks to shared/skills + personalized skills
├── scripts/                  # Symlinks to shared/scripts + personalized scripts
├── agents/                   # Symlinks to shared/agents + personalized agents
├── secrets/                  # OAuth credentials and tokens
├── certs/                    # SSL certificates
└── .env                      # Environment variables
```

**Public/Private separation:**
- `shared/skills/`, `shared/scripts/`, and `shared/agents/` contain general-purpose tools (shareable)
- `context/` is a standalone git repo, gitignored by the parent
- `context/skills/` has symlinks to `shared/skills/*` plus personalized skill folders
- `context/scripts/` has symlinks to `shared/scripts/*` plus personalized scripts
- `context/agents/` has symlinks to `shared/agents/*` plus personalized agents
- `shared/scripts/setup-context.sh` creates/refreshes those symlinks
- Swap the `context/` directory (clone a different context repo in its place) to migrate to a new environment

`CLAUDE_CONFIG_DIR` is set to `.claude_config/` by `context/scripts/run.sh` (a symlink to `shared/scripts/run.sh`), which also exports `PYTHONPATH=<repo>/backend` so the backend's packages (`api`, `manager`, `orchestrator`, `utils`) import by their plain names from the repo root. All code references `context/` and other repo paths via `backend/utils/paths.py` (`PROJECT_ROOT` = the repo root).

---

## Reference

### The Wrapper Application

The wrapper (api + manager + orchestrator + frontend) provides a multi-tab web interface for interacting with the agent CLI you chose at install time (Claude Code, Qwen Code, or both). The orchestrator agent can control multiple chat instances simultaneously and supports both text and realtime voice modes. When running inside the wrapper, you can edit its own code—the manager, API routes, frontend components—and those changes affect the very application you're running in.

**Session IDs:** Each session has a stable `local_id` (UUID, generated by frontend, never changes) and a `provider_session_id` (from the underlying CLI/SDK, used for resume/JSONL). The `local_id` is the primary key for the session pool, tabs, and orchestrator.

Start the backend (from the repo root): `context/scripts/run.sh -m uvicorn api.app:create_app --factory --host 0.0.0.0 --port 8765`, or `./start.sh`. Without run.sh: `.venv/bin/python -m uvicorn api.app:create_app --factory --app-dir backend --port 8765` (on Windows `.venv\Scripts\python.exe` in place of `.venv/bin/python`).

Start the frontend: `cd apps/web && npm run dev` (main build, port 5450) or `npm run dev:compat` (Safari 12 build, port 5451). `npm run mock` starts a mock backend; `npm run verify` runs lint, typecheck, tests and both builds.

Or use `/debug-app` which handles both and provides browser automation.

**Provider selection:** The "Session provider" selector in the Configuration panel picks which CLI new chats use (only installed providers appear). The default for new chats is read from `assistant_config.json` (`provider` field).

**SSH Remote Execution:** The backend can spawn agent sessions on a remote machine over SSH. When a working directory in `assistant_config.json` has `ssh_host`/`ssh_user` fields, the session manager generates a shell wrapper script that SSHes into the remote machine, sets the appropriate config dir, and runs the agent CLI there. SSH multiplexing (`ControlMaster`) reuses connections. All SDK flags are single-quoted and expanded locally to avoid the SSH quoting bug (SSH space-joins arguments).

### Peripheral Frontends

The assistant supports multiple frontend surfaces beyond the main web interface:

**Compat build** — `apps/web/` also builds `apps/web/dist-compat` (base `/compat/`, served at `/compat/`) for legacy browsers (Safari 12, iOS 12), from the same source tree; `npm run build` produces both builds. The previous standalone compat app is in `legacy/frontend-compat/` (served at `/legacy_compat/`), the previous main web app in `legacy/frontend/` (served at `/legacy/`).

**Android apps** (`apps/android/`) — Gradle multi-module project (Kotlin, Compose + Navigation 3, shared `core/*` and `feature/*` modules). Both apps connect to the same backend WebSocket/REST API as the web frontend:

- `:app-main` (`com.assistant.archie`, minSdk 26) — the main phone app: chat, sessions, orchestrator, WebRTC voice, memory, visualizations. APK: `apps/android/app-main/build/outputs/apk/debug/app-main-debug.apk`
- `:app-lite` (`com.assistant.peripheral`, minSdk 21, plain Views) — voice-first app for old devices (wake word + talk). It keeps the old peripheral app's package and installs over it when signed with the same key (`apps/android/keystore.properties`, gitignored). APK: `apps/android/app-lite/build/outputs/apk/debug/app-lite-debug.apk`
- Build: `cd apps/android && ./gradlew :app-main:assembleDebug` (or `:app-lite:assembleDebug`); full gate `./gradlew check`
- The previous single-module app is in `legacy/android/`
- Use `/android-dev` for building, deploying, and debugging

### Memory System

The `context/` folder is a standalone git repo (gitignored by the parent) containing all private data, including the memory system:

```
context/
├── *.jsonl            # Conversation history (JSONL files)
├── .titles.json       # Custom session titles
├── <uuid>/            # SDK state dirs (subagents, tool-results)
├── memory/            # Memory files (Markdown)
│   ├── MEMORY.md      # Authoritative index (keep under 200 lines)
│   └── *.md           # Detailed topic files
├── public/            # Static files served at URL root
├── skills/            # Symlinks to shared/skills/* + personalized folders
├── scripts/           # Symlinks to shared/scripts/* + personalized files
├── secrets/           # OAuth credentials and tokens
├── certs/             # SSL certificates
└── .env               # Environment variables
```

**The Shared Memory Index (`context/memory/MEMORY.md`)**

This file is the **single source of truth** for all skills, memory files, and project references. Both the orchestrator (which loads it dynamically into its prompt) and chat agents rely on this index.

**Structure:**
- Keep `MEMORY.md` under 200 lines with one-line references only
- Store detailed content in separate `<topic>.md` files
- Reference format: `- filename.md — Brief description`

**Your maintenance responsibilities:**

When you make changes that affect the memory index, update `MEMORY.md` directly:
1. **Skills**: Update the Skills Reference table when skills are added, removed, or modified
2. **Memory files**: Add a reference line when creating new `<topic>.md` files
3. **Projects**: Update entries when project status changes (started, completed, abandoned)

You have full editing capabilities via your tools — use them to keep the index accurate. The orchestrator loads this file dynamically, so your updates are immediately visible to the entire system.

**Semantic search:**

Both memory and conversation history are indexed for search via `/recall <query>`:
- Memory files: Indexed immediately when changed (file watcher)
- History: Indexed every 2 minutes (if changed)

**Memory navigation:**

Navigate the memory hierarchy recursively:
- `MEMORY.md` → references detailed topic files
- Project memory file → may contain nested references to sub-topics

When the user asks to remember something or retrieve memory, prefer **direct file lookup** over automated search. Read `MEMORY.md` to identify relevant files, then read those files directly. The `search_memory` / `/recall` tool is useful for broad searches but may not capture the full structured context. Use direct file reading as the primary approach and search as a supplement.

### Voice Mode (Realtime)

The orchestrator supports a realtime voice mode powered by the OpenAI Realtime API via WebRTC. Audio flows directly between the browser and OpenAI for low latency; the backend only handles signaling, tool execution, and persistence.

**Architecture:**
- Frontend establishes a WebRTC connection to OpenAI using an ephemeral token from the backend (`POST /api/orchestrator/voice/session`)
- Audio streams directly between browser ↔ OpenAI (sub-100ms latency)
- The frontend mirrors all OpenAI data channel events to the backend via the orchestrator WebSocket (`voice_event` messages)
- The backend processes tool calls and sends commands back (`voice_command` messages) for the frontend to forward to OpenAI
- Server-side VAD (voice activity detection) — no push-to-talk needed

**Key files:**
- `backend/api/routes/voice.py` — Ephemeral token endpoint (exchanges `OPENAI_API_KEY` for a short-lived token)
- `backend/orchestrator/providers/openai_voice.py` — `OpenAIVoiceProvider` that translates OpenAI Realtime events into `OrchestratorEvent`s
- `backend/orchestrator/session.py` — Voice session lifecycle, tool execution, JSONL persistence
- `apps/web/src/voice/core/VoiceController.ts` — Voice signaling state machine (bridges the transport and the orchestrator WebSocket)
- `apps/web/src/voice/transports/` — `webrtc.ts` (SDP exchange, mic, data channel) and `wsRelay.ts` (audio over the backend WebSocket relay)
- `apps/web/src/features/voice/` — Voice UI (`VoiceDock.tsx`, `VoiceSlot.tsx`)
- `apps/web/src/services/http/endpoints/voice.ts` — API client for ephemeral token and SDP exchange
- Android: `apps/android/core/voice`, `apps/android/core/voice-host`, `apps/android/core/wakeword`, `apps/android/core/audio`

**Environment:** Requires `OPENAI_API_KEY` set in the environment. Default model: `gpt-realtime`.

**Tool sharing:** Both text and voice modes use the same `ToolRegistry`. `get_definitions()` returns Anthropic format; `get_openai_definitions()` returns OpenAI function-calling format.

**JSONL persistence:** Voice turns are saved with `"source": "voice_transcription"` / `"voice_response"` fields. User transcripts are prefixed with `[voice]`. Interruptions are logged as `voice_interrupted` entries.

### Self-Modification

You can extend and modify your own capabilities:

- **Skills** (`context/skills/`): Create with `/scaffold-skill`, modify existing ones directly
- **Agents** (`context/agents/`): Create with `/scaffold-agent` for specialized subagents
- **Scripts** (`context/scripts/`): Shared tools any skill can reference
- **Wrapper** (`backend/api/`, `backend/manager/`, `backend/orchestrator/`, `apps/web/`): The application code itself
- **Android apps** (`apps/android/`): Native mobile clients — use `/android-dev` to build, deploy, and debug

Run Python scripts through the venv: `context/scripts/run.sh context/scripts/<script>.py [args]`

**General vs Personalized:**
- General-purpose items live in `shared/skills/`, `shared/scripts/`, and `shared/agents/`
- Personalized ones live directly in `context/skills/`, `context/scripts/`, and `context/agents/`
- The context folders have symlinks to the general-purpose items, so all are accessible from one place

### Skill and Script Maintenance

You have an active role in maintaining and improving skills and scripts. When you identify any problems, gaps, or issues with a skill or script, you should:

1. Think about how to address and fix that issue.
2. Present options to the user for addressing and fixing the problem.
3. Offer to spin up a nested agent to work on that specific skill or script improvement.

This ensures that the assistant is always evolving and that skills/scripts remain up-to-date and effective.

### Writing Skills

- Format: YAML frontmatter + markdown instructions
- Never use literal backtick command syntax in SKILL.md (triggers permission prompts)
- Variable substitution: `$ARGUMENTS`, `$0`/`$1`/`$2`, `${CLAUDE_SESSION_ID}`

### Two Distinct Agent Systems

This project contains **two separate agent systems** that should not be confused:

1. **`backend/manager/` → `BaseSessionManager` (with `ClaudeSessionManager` / `QwenSessionManager` subclasses)** — Wraps the underlying agent CLI. Each instance spawns a subprocess (Claude Code or Qwen Code, depending on the session's `provider`) and streams events (`TextDelta`, `ToolUse`, `TurnComplete`, etc.). Managed by `backend/api/pool.py` (`SessionPool`). Used for the main chat tabs.

2. **`backend/orchestrator/session.py` → `OrchestratorSession`** — A hand-written agent loop that calls Anthropic or OpenAI APIs directly. Has its own tool registry (`backend/orchestrator/tools/`), system prompt, and JSONL persistence. Used for the orchestrator tab (higher-level coordination, voice mode). Does NOT use either agent CLI.

**Event flow for regular chat sessions:**
```
Frontend WebSocket → backend/api/routes/chat.py → SessionPool.send()
  → ClaudeSessionManager/QwenSessionManager.send() → agent CLI subprocess
  → Events broadcast to all WebSocket subscribers
```

**Event flow for orchestrator:**
```
Frontend WebSocket → backend/api/routes/orchestrator.py → OrchestratorSession.send()
  → OrchestratorAgent.run() → ModelProvider → Anthropic/OpenAI API
  → ToolRegistry.execute() (non-blocking, concurrent)
  → Events broadcast via SessionPool.broadcast_orchestrator()
```

### Fire-and-Forget Agent Turns

The orchestrator delegates work to chat sessions through `backend/orchestrator/runner.py` (`BackgroundAgentRunner`) instead of awaiting `pool.send()` inline. The runner owns one `asyncio.Task` per in-flight agent turn, buffers events in a per-turn ring for `read_agent_session`'s live tail, and pushes one terminal `Notification` per turn (succeeded / failed / cancelled / timeout) onto a `NotificationQueue`. The orchestrator drains the queue at the top of each turn and prepends the notifications as `[SESSION xxx event: ...]` lines to the prompt the LLM sees, so the model can react to background completions even though it is not blocking on them.

- `send_to_agent_session` returns immediately with `{turn_id, session_id, status: "running", started_at}`. Concurrent fan-out to multiple sessions is parallel; two calls to the same session serialize naturally inside `pool.send()`'s per-session lock.
- `read_agent_session` returns persisted messages from JSONL plus a `live` block with `status` (`running`/`idle`) and, while a turn is in flight, the recent tail of live events (text deltas, tool_use, tool_result, permission events) from the runner's ring buffer. One tool, whether you want canonical history or progress on an in-flight turn.
- `interrupt_agent_session` cancels a running turn. `respond_to_agent_permission` answers a pending permission on a delegated session.
- `OrchestratorSession._busy_lock` (exposed via the `is_busy` property) wraps every `send()` / `send_audio()` body. The wake callback installed by `backend/api/routes/orchestrator.py` consults `is_busy` and schedules a synthetic empty-prompt turn only when the orchestrator is idle — so a notification arriving mid-turn just queues for the next drain. If you add a new wake source, gate it the same way. Voice mode skips the synthetic wake (notifications still drain on the next text turn).
- Each drained notification is persisted as a `background_notification` JSONL entry tied to the originating `tool_use_id`, so a fire-and-forget run is replayable.

### Permission Gating

The bundled Claude Code CLI fires a permission gate for tools like `ExitPlanMode`. `ClaudeSessionManager` wires the SDK `can_use_tool` callback: tools in `_DEFAULT_GATED_TOOLS` (currently `{"ExitPlanMode"}`) emit a `PermissionRequest` event into the active `send()` stream and await an `asyncio.Future`; anything else auto-allows. `permission_mode` is `"default"` so the SDK actually invokes the callback.

**Conversational checkpoint design** — the popup is a backstop, not the primary mechanism. The session manager appends a permission-gating prompt to the bundled system prompt instructing the agent to announce its intent in chat *before* calling a gated tool. The user (or orchestrator) guides with prose; the popup remains as the formal yes/no path.

- Frontend (`useChatInstance` + `PermissionModal`): per-tab `pendingPermission` state. The user can Approve, Reject, or just type a chat message — typing resolves all pending permissions on that session as `deny` with the prose as the rejection reason (`backend/api/routes/chat.py`), then sends the message normally. The agent receives both signals and refines.
- Orchestrator: permission events are mirrored to `broadcast_orchestrator` as `nested_session_event`, and the orchestrator can answer via the `respond_to_agent_permission` tool. First write wins between user and orchestrator; the loser's modal closes via the broadcast.
- The plan text from `ExitPlanMode` is pushed into the conversation as a normal assistant text block. `permission_request` / `permission_resolved` are broadcast over the WebSocket (driving modal lifecycle) but are not written as dedicated JSONL entries — the persisted record is the orchestrator's `background_notification` line plus the agent's own JSONL events.

### Stall Watchdog

When the bundled `claude` subprocess goes silent mid-tool (most often `WebFetch` waiting on an unresponsive endpoint), the SDK never emits a `ResultMessage`. The session manager's `send()` drains the SDK receiver via a worker task and pulls from a queue with `asyncio.wait_for`; on timeout it yields a `SessionStalled` event naming the in-flight tool, then keeps waiting. First notice at 120s of silence, repeats every 60s. While a stall is reported and the session is still streaming, the web frontend marks the named tool card as waiting. `SessionStalled` is advisory — it does NOT abort the stream.

The send loop also recovers from a related SDK bug: some `claude-cli` versions deliver bundled-tool output as a plain string instead of the documented dict. The old code dropped the `ToolResult` entirely and left the UI's tool block stuck on "running"; the loop now treats the string as the tool output and recovers `tool_use_id` from `parent_tool_use_id` on the `UserMessage`.

### Warm Search Server

Loading PyTorch + sentence-transformers + the embedding model takes ~100s on low-power hardware, so `shared/scripts/search-server.py` runs as a persistent subprocess that loads the model once and serves queries over stdin/stdout (JSON-line protocol). `backend/orchestrator/tools/search.py` manages the singleton with auto-recovery and a cold fallback. The server is pre-warmed during API startup (`backend/api/app.py`) so the first query is fast too. `shared/scripts/run.sh` sets `LD_PRELOAD=libgomp.so.1` on aarch64 to fix the "cannot allocate memory in static TLS block" ImportError. Searches went from ~68–103s to ~1–3s.

**Two indexes, both SQLite** (no chroma since 2026-10-06): `index/history.sqlite3` (`backend/utils/history_index.py`) and `index/memory.sqlite3` (`backend/utils/memory_index.py`) — chunks with an FTS5 keyword index and embeddings (`paraphrase-multilingual-MiniLM-L12-v2`, recorded per index in `meta.model`) searched in numpy, so the files are portable between machines (build on the laptop with `index-memory.py --local-model`, copy to the Jetson). Requests are handled by `backend/utils/search_service.py` — the same code in the warm server, the `search.py` fallback and the eval harness. History covers every harness's JSONL; noise and the orchestrator's narration of its own searches are skipped; each session also gets an LLM summary (`backend/utils/session_summaries.py`, gpt-4.1-mini, `index/session_summaries.sqlite3`) indexed as an extra chunk; results are re-ranked by gpt-4.1-mini (`backend/utils/rerank.py`). Memory chunks follow headings with line ranges and carry each note's frontmatter `source` links. Indexing is incremental and one failing file never blocks the rest. Design, evaluation method and measured results: `docs/history-search/` (eval harness `shared/scripts/history_eval/`, private eval data `context/evals/history_search/`).

### Testing

Run the full suite (from the repo root): `context/scripts/run.sh -m pytest backend/tests -v` (pytest reads `backend/pyproject.toml`)

Run a single test: `context/scripts/run.sh -m pytest backend/tests/test_foo.py::TestClass::test_method -v`

Mock external dependencies with `unittest.mock`.

### Browser Automation

Chrome DevTools MCP provides full browser control. Use `/debug-app` for integrated frontend testing.

### Skills & Integrations

The default skill set provides a broad range of integrations. Run `/help` inside the assistant to list them, or browse `shared/skills/` directly.

Categories include:
- **Code/dev tooling** — `/debug-app`, `/scaffold-skill`, `/scaffold-agent`, `/android-dev`, `/tv-dev`
- **Media** — `/generate-image`, `/create-viz`, `/music-video-editor`
- **Device control** — `/tv-remote`, `/connect-tv`, `/iphone-photos`
- **APIs** — `/youtube` (YouTube Data API), Google integrations

Personalized skills live next to the defaults in `context/skills/` and are discovered automatically.

### Orchestration Behavior

When delegating tasks to agent sessions, ensure the related skills and context are loaded. Match the skill to the task type (TV → `/tv-remote`, video → `/youtube` + `/create-viz`, image → `/generate-image`, etc.).

When handling repeating-context requests: reuse an open session if relevant, search history for a past similar session to resume, or create a new one only if needed.

### User Context

This is a fresh installation. Personalize this section as you learn about the user: who they are, what they work on, how they prefer to collaborate. Keep detailed personal context in `context/memory/user_context.md` (or similar) and link to it from `MEMORY.md`.

### Tracked Projects

This section lists active projects with dedicated memory files. A fresh installation starts empty — add entries as projects are scoped.

See `context/memory/projects/personal_projects_index.md` (create this when you have multiple tracked projects) for full details.

### Compact Instructions

When compacting, preserve:
- Current task context and progress
- Key decisions made during this session
- Key learnings from this session
- Any unresolved questions or blockers
