---
name: glossary
category: archie/overview
tags: [glossary, terms, definitions]
created: 2026-10-07
modified: 2026-10-07
summary: One-line definitions of Archie's terms — sessions, orchestrator, voice, wake word, clients, infrastructure, memory.
source: curated (terms collected while consolidating the memory notes into docs/, 2026-10-07)
references:
  - archie.md
  - ../architecture/agent-sessions.md
  - ../architecture/orchestrator.md
  - ../architecture/memory-and-search.md
  - ../harnesses/registry.md
  - ../integrations/skills.md
  - ../integrations/visualizations-and-sharing.md
  - ../voice/architecture.md
  - ../voice/lifecycle.md
  - ../voice/wake-word.md
  - ../clients/web.md
  - ../clients/android.md
  - ../clients/browser-extension.md
  - ../devices/fire-tv.md
  - ../infrastructure/context-sync.md
  - ../infrastructure/ssh-remote-execution.md
  - ../infrastructure/jetson-server.md
  - ../operations/refactor-methodology.md
---

# Glossary

Alphabetical within each group. Follow the link for the full story.

## Core

| Term | Meaning |
|---|---|
| **Agent session** | A coding-agent CLI subprocess (Claude Code, Qwen Code, Gemini CLI) behind a chat tab. [agent-sessions](../architecture/agent-sessions.md) |
| **Archie** | The system's name; the repo and install path still say `assistant`. [archie](archie.md) |
| **context / context repo** | `context/`, the private git repo with conversations, memory, secrets and personal skills; gitignored by the framework repo. [repo-layout](repo-layout.md) |
| **Harness** | One supported agent CLI, described by a `HarnessSpec` in the harness registry. [harnesses](../harnesses/registry.md) |
| **jsonl_id** | The orchestrator's JSONL filename stem (the resumed id, else its `local_id`). |
| **local_id** | Client-minted UUID for a tab/session; the pool key everywhere; never changes. |
| **Orchestrator** | The single hand-written agent (Anthropic/OpenAI APIs or a realtime voice provider) that drives agent sessions through tools; the "Archie" tab and voice persona. [orchestrator](../architecture/orchestrator.md) |
| **sdk_session_id** | The harness CLI's own conversation id; the JSONL filename stem; used for history and resume. |
| **SessionPool** | `backend/api/pool.py`: registry of live agent sessions plus the one orchestrator slot, with subscribers and broadcast. |
| **Session-owned turn** | A turn driven by a pool task, independent of the socket that sent it — disconnecting never kills work. |
| **shared vs personal** | General-purpose skills/scripts/agents live in `shared/` (public); personal ones directly in `context/`, which also symlinks to the shared ones. |
| **stream_id / seq** | Resume cursor on agent-session events; a reconnecting client replays what it missed. |
| **Watcher** | A socket that receives `agent_session_opened` / `agent_session_closed`; how agent tabs auto-appear when the orchestrator opens a session. |

## Harnesses and skills

| Term | Meaning |
|---|---|
| **comm_prefix** | The `/proc/<pid>/comm` prefix the orphan reaper checks before killing a PID. |
| **HarnessSpec / HarnessRegistry** | The frozen per-harness descriptor and the registry every dispatch site consults (`backend/manager/registry.py`). [registry](../harnesses/registry.md) |
| **Markdown reader** | `context/public/markdown_reader.html?file=…`: renders any markdown (incl. memory notes via `memory/…`) on any device. [sharing](../integrations/visualizations-and-sharing.md) |
| **ProviderAdapter** | Per-harness JSONL parser that normalizes to Claude's content-block shape. |
| **Review artifact** | A file Rodrigo must look at (render, transcript, draft); lives where every device can open it. |
| **session_discoverer / jsonl_path_resolver** | HarnessSpec hooks that map a session id to its JSONL when the filename isn't the id (Gemini). |
| **SessionStore** | Lists and reads sessions of every harness from `context/*.jsonl` and `context/chats/`. |
| **Skill** | A `SKILL.md` instruction set invoked as `/<name>`; scripts do the work. [skills](../integrations/skills.md) |
| **Spawn-per-turn** | One CLI subprocess per turn, chained with `--resume` (Qwen, Gemini). |

## Agent sessions

| Term | Meaning |
|---|---|
| **Chat-as-deny** | Typing a message while a permission is pending denies it, with your text as the reason. |
| **Gated tool** | A tool that needs approval through the SDK `can_use_tool` callback (currently `ExitPlanMode`). |
| **Loop watchdog** | `backend/manager/loop_watchdog.py`: a thread that exits the backend if the asyncio loop stops servicing callbacks (systemd restarts it). |
| **Orphan reaper / dead-session reaper** | Pool background tasks that kill leaked CLI processes and close sessions whose receive loop died. |
| **SessionStalled** | Advisory event after 120 s of silence mid-turn, repeated every 60 s; does not abort. |
| **TurnAbandoned** | A turn that got zero SDK messages for 240 s; retried once. |

## Orchestrator

| Term | Meaning |
|---|---|
| **BackgroundAgentRunner** | Runs `send_to_agent_session` turns in the background and pushes one `Notification` per turn. |
| **Notification / background event** | A `[SESSION xxx event: …]` line prepended to the orchestrator's next prompt when a background turn ends. |
| **run_script allowlist** | `context/memory/ORCHESTRATOR_SCRIPTS.md`; its `path:` lines are the only scripts the orchestrator may run. |
| **Wake callback** | Starts a synthetic orchestrator turn when a notification arrives while it is idle (text mode only). |

## Memory and search

| Term | Meaning |
|---|---|
| **Strong / weak relevance** | Calibrated confidence labels on search results; weak hits are returned separately. |
| **Two-level index** | `MEMORY.md` (rules, ontology, key files) → each folder's `INDEX.md` (full list). |
| **Warm search server** | `shared/scripts/search-server.py`: keeps the embedding model loaded so searches take seconds, not ~100 s. |

## Voice

| Term | Meaning |
|---|---|
| **Drain-then-restore** | Echo ducking: the mic is restored only after the speaker buffer has drained plus a tail. |
| **goAway** | Gemini Live's warning before it closes the socket; triggers a reconnect with the resumption handle. |
| **Manual VAD** | Turn boundaries decided by the backend's Silero VAD instead of the provider's server VAD. |
| **Resumption handle** | Gemini's opaque session token; valid only for the upstream socket that issued it — clear it on rebuild. |
| **response.create gate** | Deferring a reply request while another response is in flight; every parked frame needs a drain trigger that doesn't depend on the frame itself. |
| **Safety commit** | Qwen-only: commit the audio buffer without a reply after 50 s of continuous speech. |
| **STALE-REUSE** | Reusing a stale history summary and moving newer messages back into the verbatim window. |
| **Talk word** | Phrase (default "my friend"; "hello my friend" on the A300M) that captures one spoken command on the same mic and sends it as a one-shot audio message. [wake-word](../voice/wake-word.md) |
| **V6** | The deferred removal of the SpeechRecognizer wake-word fallback. |
| **Voice owner / initiator** | The one socket that started voice; the only one sent provider commands. Other devices are read-only ("Active elsewhere"). [lifecycle](../voice/lifecycle.md) |
| **Wake word** | Phrase ("wake up") that opens a realtime voice conversation. |
| **WS relay** | `backend/orchestrator/voice_relay.py` `VoiceRelay`: the backend-held upstream socket for Qwen-Omni and Gemini Live. |

## Clients and devices

| Term | Meaning |
|---|---|
| **BrowserHub / browser daemon** | The single-client WebSocket hub for the Chrome extension (`backend/api/routes/browser.py`), run as a local daemon (`browser_daemon.py`, 127.0.0.1:8766) on the Chrome host. [browser-extension](../clients/browser-extension.md) |
| **Companion app** | `com.assistant.device` on the A300M: watchdog, boot launch, WiFi ADB, DND. |
| **Compat build** | `apps/web/dist-compat`, the Safari 12 / iOS 12 bundle served at `/compat/` for the iPad mini 2. |
| **Compat bundle scanner** | Build gate (`scan-compat-bundle.mjs`) rejecting syntax and CSS Safari 12 can't run. |
| **Dependency tiers** | Android build rule: minSdk-21 modules use legacy-tier libraries, minSdk-26 modules modern ones. |
| **LegacyMigration** | One-time first start of the lite app that keeps the old app's settings and Vosk model. |
| **Lite app** | `:app-lite`, `com.assistant.peripheral`: Views-only, voice-first face app on the A300M. |
| **Look-first** | Browser-control rule: run `look` before acting and target by its `ref`/`selector`. |
| **Low-end mode** | `html.low-end` class (≤ 2 cores, ≤ 1 GB, or the compat build) that turns off motion. |
| **Main app** | `:app-main`, `com.assistant.archie`: the full Compose client on the phone. |
| **Remote console** | Inline script that beacons browser console output to `/api/debug/log` → `logs/remote_console.log`. |
| **ScreensaverMonitorService** | TvServerHub's foreground service that returns to the launcher after 80 s idle. |
| **Show on TV** | `GET/POST /api/visualizations/cast`: open a visualization on the Fire TV over adb. |
| **stale_viewport / unknown_ref** | Browser-control errors for coordinates after a scroll, and refs from an old snapshot. |
| **TvServerHub** | The custom Fire TV launcher / WebView app. [fire-tv](../devices/fire-tv.md) |

## Infrastructure and process

| Term | Meaning |
|---|---|
| **context-sync** | The inotifywait + rsync user service that mirrors `context/` between laptop and Jetson both ways. [context-sync](../infrastructure/context-sync.md) |
| **ControlMaster** | SSH connection multiplexing used by remote sessions; one socket per provider and host. |
| **D1** | 2026-10-03 decision: rewrite the Android voice stack with every tuned constant ported verbatim and covered by parity tests. |
| **Detour** | A commit between planned refactor steps, prompted by a real finding. |
| **Direct WS probe** | A throwaway Python WebSocket client that sends `start` and prints the real `session_started` payload. |
| **fb0 blanking** | Three-layer fix that keeps the headless Jetson's framebuffer blank despite HDMI hotplug noise. |
| **Ghost voice** | The backend believes voice is active while no client is connected. |
| **Gold restore point** | The 2026-05-15 Jetson SD-card image. |
| **Mangled cwd / project key** | The absolute working directory with `/` replaced by `-`; keys Claude/Qwen session storage — hence the same install path on both machines. |
| **MAXN-SAFE** | The Jetson's custom power mode (nvpmodel 2: 4 cores at 1.2 GHz). |
| **Metadata-only reset** | `git fetch && git reset --mixed origin/main` — used **only** on the Jetson's rsynced `context/` repo. |
| **Observed-paths delete gating** | context-sync propagates a delete only for paths this machine saw deleted and that are really gone; never `rsync --delete`. |
| **Parity test** | A test that captures current behaviour of tuned code before a refactor. |
| **SSH wrapper script** | A temp `/tmp/claude-ssh-*.sh` handed to the SDK as `cli_path`; quotes args locally and runs the CLI over SSH. |
