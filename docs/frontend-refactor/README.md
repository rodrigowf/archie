# Frontend Refactor — Project Charter

Branch: `frontend-refactory` · Started: 2026-10-03 · Owner: Rodrigo · Coordinator: Claude

Rebuild the web app (`frontend/` + `frontend-compat/`) and the Android app (`android/`) from
scratch on a shared Material Design 3 design language. The backend API is the fixed
contract (only small, justified fixes allowed).

## Goals

1. Modern, clean, consistent look on web and Android (M3, integrated top bars, less color
   separation between bars, a proper tab/session switcher, organized settings).
2. **Feature parity with today, minus the bugs** — every feature in `inventory/` is preserved.
3. Visual consistency between web and Android: one token source, same components and naming.
4. Android: modern-first main app (minSdk 26) + a separate voice-first **lite app** for the
   Samsung A300M (API 21). The `android-device` companion stays as it is.
5. New on Android: **Visualizations** and **Memory** (currently web-only).
6. Known bugs fixed (e.g. Android tool-call ordering; see `audit/` and `inventory/`).

## Issues reported by Rodrigo (must be fixed; each becomes an acceptance test)

| # | Issue | Covered by |
|---|-------|-----------|
| R1 | Android looks dated: separated colored bars, old controls | design system, IA |
| R2 | Web and Android look different | D3, D6 |
| R3 | Web tab bar small / not practical / not integrated | IA |
| R4 | Android tool calls out of order vs interleaved text (orchestrator) | protocol spec reducer invariants + fixtures |
| R5 | Settings panels disorganized | IA (settings hierarchy) |
| R6 | Visualizations and Memory missing on Android | IA, Android spec |
| R7 | Tool-call cards sometimes show no output, especially live | protocol spec "tool result never lost" invariant + fixtures |

## Decisions log

| # | Date | Decision | By |
|---|------|----------|----|
| D1 | 2026-10-03 | Android voice/audio/wake-word stack is **rewritten from scratch** too, with every tuned constant and timing ported verbatim and covered by parity tests (see Quality gates) | Rodrigo |
| D2 | 2026-10-03 | A300M gets a separate voice-first lite app; `android-device` companion stays separate and unchanged | Rodrigo |
| D3 | 2026-10-03 | Theme: dark-first, seeded from the current web palette (neutral near-black + soft blue); light theme also provided; **no dynamic color** so web and Android match | Rodrigo |
| D4 | 2026-10-03 | Main Android app minSdk 26; lite app minSdk 21 | Rodrigo |
| D5 | 2026-10-03 | New code is built side by side (`frontend-next/`, `android-next/`) so the current apps keep working; cutover replaces the old directories once acceptance passes | Claude |
| D6 | 2026-10-03 | One design-token source (`design/tokens`) generates CSS variables (web) and Kotlin theme (Android) | Claude |
| D7 | 2026-10-03 | Lite app keeps applicationId `com.assistant.peripheral` (the unchanged `android-device` companion hard-codes it in `BootReceiver.kt:43` / `WatchdogService.kt:183`); the new main app gets a new id, so both can coexist on one device | Claude (forced by D2) |
| D8 | 2026-10-03 | Lite app UI uses plain Android Views, not Compose (A300M: 888 MB RAM, past RenderThread crash) | Claude (from inventory 04 §9) |

## Document map

| Path | Content | Status |
|------|---------|--------|
| `audit/00-visual-audit.md` | Screenshot audit of current apps (shots gitignored) | done |
| `inventory/01-backend-api.md` | API contract: REST, WebSocket protocols, streaming/ordering, voice | done |
| `inventory/02-web-frontend.md` | Web + compat feature inventory and message model | done |
| `inventory/03-android-app.md` | Android UI/chat/network inventory, ordering bug root cause | done |
| `inventory/04-android-voice-and-device.md` | Voice stack rewrite spec: constants, state machines, 46 regression scenarios, module design, lite-app needs | done |
| `spec/12-client-protocol.md` + `shared/protocol-fixtures/` | Normative client data-layer spec + conformance fixtures | done |
| `design/tokens/` | (approved 2026-10-03) Shared M3 token source + generators (CSS, Kotlin, TS); TonalSpot from seed, 196/196 contrast pairs pass | done |
| `spec/11-information-architecture.md` | Screens, navigation, settings hierarchy (web + Android) | approved |
| `mockups/archie-mockups.html` | High-fidelity mockups of every key screen (web, phone, tablet, lite); published privately: https://claude.ai/artifact/MDViCDBwzHDfnyqrcUuBLR | **approved by Rodrigo 2026-10-03** |
| `spec/13-web-architecture.md` | Web architecture + work packages W-01..15 | done |
| `spec/14-android-architecture.md` | Android architecture (main + lite + core) + work packages A/B/C/D | done |
| `plan/20-decisions-and-backend-changes.md` | All pending/approved decisions + backend changes | live |
| `plan/21-work-plan.md` | Milestones, merged waves, operating model, locks, gates | live |
| `plan/22-progress-log.md` | Progress log | live |

## Phases

| Phase | Output | Exit criteria |
|-------|--------|---------------|
| P0 Discovery | audit + inventory | All 4 inventory chapters reviewed; open questions answered by Rodrigo |
| P1 Specification | design system, IA, per-feature acceptance criteria, architecture | Rodrigo approves the design direction (mockups) |
| P2 Foundations | token pipeline, web app shell, Android module skeleton, protocol clients + tests | Clients pass contract tests against recorded/live backend |
| P3 Features | chat, sessions, orchestrator, voice, settings, memory, visualizations | Every inventory item checked off |
| P4 Lite app | A300M voice-first app | Field test on A300M |
| P5 Hardening & cutover | QA matrix, perf, a11y, replace old dirs, deploy | Acceptance matrix green; Rodrigo sign-off |

## Working rules

- **One test environment at a time** (Chrome *or* one emulator), shut down when idle;
  watch CPU/RAM. Emulator profiles: `POCO_X7` (1220×2712, 440 dpi, API 36); an A300M
  profile (540×960, API 21) is added when needed.
- Test against the live Jetson backend (`https://192.168.0.200`), read-only where possible;
  never open orchestrator sessions or voice sessions on it without telling Rodrigo
  (that can change which conversation the real devices are attached to).
- Agents: each work package has a written brief, a single owner, explicit file
  boundaries, and a definition of done. Agents never edit files outside their boundary.
- Every non-obvious behavior ported from old code cites its source (`file:line`) in
  the spec, so we can tell "load-bearing" from "accidental".

## Quality gates

- Protocol clients (web TS + Android Kotlin) are covered by contract tests built from
  real recorded WebSocket transcripts, including interleaved text/tool streams.
- Voice rewrite (D1): a parity-test suite pins every tuned constant, state transition
  and timing of the old stack before it is retired; field test on POCO X7, Xiaomi, A300M.
- Visual QA: screenshot sets for web desktop, web mobile, compat (Safari 12 constraints),
  Android phone, Android lite — reviewed side by side.
