# 20 — Consolidated decisions & backend changes

Collected from specs 11–14 on 2026-10-03. "Default" = adopted by the coordinator unless Rodrigo objects;
"ASK" = needs Rodrigo.

## A. Backend changes (all small; none removes or renames a field)

| ID | Change | Why | Size | Status |
|---|---|---|---|---|
| BF-1 | Live Claude tool results built from `UserMessage.content` `ToolResultBlock`s instead of `tool_use_result` metadata (`manager/claude/session.py:1116-1133`) | **Root cause of R7** (tool cards without output while live): live results carry `tool_use_id: ""` and empty output; MCP results never sent. Verified with a CLI stream-json probe | ~25 lines + fix tests | **APPROVED** 2026-10-03 |
| BF-2 | Broadcast `status: interrupted` to all subscribers (`api/routes/chat.py:205-210`) | Other devices stay "busy" forever after an interrupt | ~5 lines | **APPROVED** 2026-10-03 |
| BF-3 | nginx `client_max_body_size 200m` on the Jetson | Uploads > 1 MiB rejected today (seen in Jetson logs) | 2 config lines | **APPROVED** 2026-10-03 |
| BX-1 | Serve `frontend-next/dist` at `/next/` and `/next-compat/` | Try the new web app on real devices before cutover | ~15 lines, additive | **APPROVED** 2026-10-03 |
| BX-2 | `POST /api/visualizations/cast {path}` (+ capability probe) wrapping the existing TV display script | "Show on TV" (IA §9.4) | small, additive | **APPROVED** 2026-10-03 |
| O-1/O-2 | Orchestrator history keeps tool calls; thinking blocks survive history | Reloaded conversations lose tool cards / "Thought" sections | medium | **APPROVED** 2026-10-03 |
| O-3..O-6 | Orchestrator `jsonl_id` + `user_message` echo; relay fatal → `voice_ended`; stall seq; queued echo | Removes client workarounds | small each | **APPROVED** 2026-10-03 |
| O-7 | Honor `assistant_config.default_model` | Setting exists but is ignored | small | **APPROVED** 2026-10-03 |
| BX-3 | Compat output path at cutover (`frontend-compat/dist` → new path) | Cutover | 1 line | Default: keep an output dir named `frontend-compat/dist` → no backend change |

## B. Product decisions

| # | Question | Default / status |
|---|---|---|
| P-1 | Closing sessions | **DECIDED (Rodrigo)**: an *explicit* close of a session/tab inside the app closes it for everybody (server-side close). Closing the browser/app, losing the connection, or backgrounding **never** closes anything: sessions keep running in the backend even with zero frontends attached. Clients must never send `close`/`stop` from unload, teardown, or lifecycle paths (cf. web bug 10: voice start sending `stop`). |
| P-2 | Voice drop on network blip | **DECIDED (Rodrigo)**: auto-restart, **not quietly** — from the moment the link is lost, give call-app-style cues (WhatsApp-like): an audible "reconnecting" tone/pattern, a visual "Reconnecting…" state in the voice dock/orb (and lite face), then a distinct "reconnected" cue on success, or a clear failure state + cue + manual Reconnect after the retry budget is exhausted. **Retry budget: 30 s** (Rodrigo, 2026-10-04; `LINK_RETRY_BUDGET_MS`). |
| P-9 | Archie text model (O-7 precedence) | **DECIDED (Rodrigo)**: Settings value wins (`SETTINGS_DEFAULT_MODEL_FIRST = True`); model **gpt-audio-mini** (already the Jetson value; laptop `assistant_config.json` changed from the dead `gpt-4o-audio-preview` on 2026-10-03). |
| P-3 | Unprompted Archie replies (background notifications) get a subtle "Background update" divider | Default: yes |
| P-4 | Rename Archie conversations | Default: allowed (same rename flow as agents) |
| P-5 | Voice-message button visible only when the **current** Archie model accepts audio | Default: yes (fixes web inconsistency) |
| P-6 | Sessions started on another device / by Archie: open as **background tabs** (badge, no focus steal) | Default: yes |
| P-7 | Keyboard shortcuts on web: Ctrl+Alt+→/← switch tab, Ctrl+Alt+W close, Ctrl+Alt+1..9 (browsers reserve Ctrl+Tab/W/1-9) | Default: yes |
| P-8 | iPad (compat) voice: OpenAI WebRTC voice yes; Qwen/Gemini (need AudioWorklet) unavailable on iOS 12 | Default: accept platform limit |

## C. Engineering decisions (coordinator)

| # | Decision |
|---|---|
| E-1 | Web: React 18.3 on both builds (one runtime, one test run); Zustand; highlight.js curated; GFM with a lookbehind-free autolink module; compat bundle-scanner gate |
| E-2 | Android: `android-next/` multi-module, manual DI, Navigation 3, custom incremental markdown on commonmark-java, voice in a process-scoped host kept alive by a foreground service |
| E-3 | **All toolchain/library versions named in specs 13/14 are verified against published artifacts in W-01/A-01 before use** |
| E-4 | Signing: reuse the laptop debug key for both Android apps (lite must, to upgrade in place; versionCode ≥ 11) |
| E-5 | Lite app ships debuggable until field-tested, then minified release with verified keep rules |
| E-6 | TLS/transport: keep today's behavior for v1 (parity); revisit Tailscale certs later |
| E-7 | Lite app keeps the `/dev/input` recents monitor and VoiceInteractionService for parity; field test decides whether to drop them |
| E-8 | Token generator gains an Android XML output (colors/dimens) for the Views-based lite app |
| E-9 | 16 KB page-size native-lib upgrade is scheduled after the voice-parity gate (G-01) |

## Backend changes applied

Applied 2026-10-03 on `frontend-refactory` (working tree, not committed). Review fixes applied the same day. Full suite: 1165 passed, 2 skipped.

| ID | Files | Tests | Wire-protocol impact |
|---|---|---|---|
| BF-1 | `manager/claude/session.py` (UserMessage branch reads `ToolResultBlock`s; `tool_use_result` only as fallback, list/MCP fallback now emitted), `manager/protocol.py` (shared `tool_result_text()`) | `tests/test_session.py`: real CLI shape, parallel + `is_error`, MCP list content, `None` content, list and dict fallbacks | Live Claude `tool_result` now carries the real `tool_use_id` and output; MCP results arrive. Same frame, same fields. Qwen already reads `message.content` blocks; Gemini uses its own `tool_result` event with `tool_id`, so neither needs the fix |
| BF-2 | `api/routes/chat.py`, `api/pool.py` (public `broadcast_session(..., exclude=)`) | `tests/test_api_chat.py`: two-socket broadcast + exactly-once; no broadcast when no turn ran; `tests/test_pool_turn.py`: exclude | `status: interrupted` now reaches every subscriber when a turn was cancelled; the interrupter still gets exactly one (direct) frame |
| BX-1 | `api/app.py` (`_next_preview_dirs`, `_register_preview_spa`) | `tests/test_next_preview_routes.py`: no-cache index, assets, SPA fallback, traversal 404, ordering, absent when unbuilt | New routes `/next/` → `frontend-next/dist-preview/main`, `/next-compat/` → `dist-preview/compat` (spec 13 §1.6), registered only if `index.html` exists at startup (restart after the first rsync). Remove at cutover |
| BX-2 | `api/routes/visualizations.py` | `tests/test_api_visualizations_cast.py` (all adb calls mocked): probe, Amazon-device pick, pinned serial, URL encoding, 7 rejected paths, no TV, timeout, `am` error, kill on timeout | New `GET /api/visualizations/cast` → `{available, reason}`; `POST /api/visualizations/cast {path}` → `{ok, message}` (404 for a path not in the list). Runs `adb -s <tv> shell am start -n com.example.tvserverhub/.WebPageViewActivity -e url <url>` with a 10 s timeout. URL base `https://<LAN IP>`; override with `VIZ_CAST_BASE_URL` (needed on the laptop); `FIRE_TV_ADB_SERIAL` pins the device |
| O-1 | `orchestrator/session.py` (assistant line per `TextComplete`), `orchestrator/persistence.py` (text + following `tool_use` merge into one model message), `manager/claude/adapter.py` (`fold_orchestrator_lines`, `visible_line_indices`), `manager/protocol.py` + `manager/store.py` (truncate uses the adapter's visible indices) | `tests/test_orchestrator_persistence.py`, `tests/test_orchestrator_history_rest.py` (new + old layouts, tool-only turn, trailing tools, voice, wake reply, truncate parity, reviewer's text→tool→text case) | REST history of an orchestrator file has ONE assistant message per turn (everything between two visible user lines, wake replies included): blocks in file order (text, `tool_use`, `tool_result`, text…), top-level fields from the group's first line. This matches the live clients' one bubble per turn, so `drop_last_n` from REST or from the web/Android lists lands on the right message. In `truncate` the group counts once, anchored on its **last** line so a kept turn is kept whole (a first-line anchor would cut off its later text and tool results). Old JSONL still loads |
| O-2 | `manager/protocol.py`, `manager/types.py`, `api/models.py`; current web app (approved exception): `frontend/src/types.ts` (`ContentBlock.type` + `"thinking"`), `frontend/src/hooks/useChatInstance.ts` (`convertPreviews` renders it) | `tests/test_orchestrator_history_rest.py`, `tests/test_qwen_adapter.py` (updated); `npx tsc -b` clean | History blocks gain `type: "thinking"` (Claude `thinking` field, Qwen/Gemini `text`). Before: Claude thinking was dropped, Qwen/Gemini thinking came as `text`. The current web shows it as a Thought block after reload; Android already did. Dists not rebuilt |
| O-3 | `api/routes/orchestrator.py` (5 `session_started` sites, `send`), `api/pool.py` (`broadcast_orchestrator(..., exclude=)`) | `tests/test_orchestrator_ws_echo.py` | `session_started.jsonl_id` added; typed `send` broadcasts `user_message{text}` to the other orchestrator sockets before the turn. Not done: the `background_notification` frame before wake turns (Appendix A O-3, not in the approved list) |
| O-4 | `orchestrator/voice_relay.py` (`on_fatal`), `orchestrator/session.py` (`_on_voice_relay_fatal`, `_end_voice_after_relay_fatal`, `_pending_voice_relay`) | `tests/test_voice_relay_fatal_end.py` (incl. relay rebuilt before the task runs, drain dying before `_voice_relay` is assigned, superseded pending relay, voice already ending) | After the existing `voice_error` + `error{voice_relay_failed}`, the session runs `end_voice("error")`: `voice_ending`, `voice_ended{reason:"error"}`, legacy `voice_stopped`. The task re-checks right before ending that the relay is still the live one (or the one whose `start()` is in flight) and that voice is not already ending. Relay start failures are unchanged |
| O-5 | `manager/claude/session.py` | `tests/test_session.py` stall test | `session_stalled` no longer carries the previous event's `seq`/`stream_id` |
| O-6 | `api/pool.py` (`_PendingPrompt.announced`, `send(announce=)`) | `tests/test_pool_turn.py::test_queued_prompt_is_echoed_once` | A queued prompt is echoed once (`user_message{queued:true}`); no second `user_message` at dispatch. Dispatch is still visible as `status{processing}`. **Spec 12 §4.4.1 / I-12 observer rule must change:** move the oldest remote tray item into the timeline on `status{processing}` instead of on the dispatch echo |
| O-7 | `orchestrator/config.py` (`configured_default_model`, `env_default_model`, `resolve_default_model`, `RETIRED_MODEL_IDS`, switch `SETTINGS_DEFAULT_MODEL_FIRST`), `api/routes/voice.py` | `tests/test_orchestrator.py` (config isolated from the real file; Settings beats env; invalid and retired ids skipped; switch flipped) | New orchestrator sessions start on the first usable of Settings `default_model` / env `ORCHESTRATOR_MODEL` (order = `SETTINGS_DEFAULT_MODEL_FIRST`, now `True`; one-line flip pending Rodrigo), then `gpt-audio`. `gpt-4o-audio-preview` / `gpt-4o-mini-audio-preview` are skipped with a warning. `GET /api/orchestrator/models` reports the resolved value. With the current switch: Jetson → `gpt-audio-mini`; laptop (Settings holds a retired id) → env `gpt-4o` |

## D. Coordinator rulings

Implementation-time rulings CR-1…CR-23 (checkpoint in memory, T-13 backoff, no-drop voice queue, tray rules, dark default, vocabulary, parity-test fix policy, accepted voice fixes, …) are listed in `plan/23-coordinator-handoff.md` §6.
Optional backend additions surfaced during implementation (version endpoint, memory mtimes, voice device ids, per-session skills, cache headers, agent approvals without a view) are in `plan/23` §4 — **not approved yet**.
