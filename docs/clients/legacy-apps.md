---
name: legacy-apps
category: archie/clients
tags: [legacy, frontend, frontend-compat, android, peripheral, viewmodel, shims, safari-12, history]
created: 2026-04-14
modified: 2026-10-06
summary: The frozen pre-2026-10 apps in legacy/ — old web, old Safari 12 compat app, old Android peripheral app — and their lessons.
source: curated (consolidated from memory notes assistant/devices/frontend-compat.md, assistant/android/android_peripheral_project.md, assistant/architecture/android_viewmodel.md, assistant/infrastructure/repo_layout_cutover_2026_10.md, assistant/infrastructure/features_and_integrations_summary.md §2–4, auto-memory project_compat_remark_gfm_shim.md, project_compat_gap_shim_text_node_trap.md; verified against code 2026-10-06)
references:
  - web.md
  - android.md
  - android-device.md
  - ../voice/wake-word.md
  - ../voice/architecture.md
  - ../voice/lifecycle.md
  - ../operations/refactor-methodology.md
  - ../devices/devices.md
  - ../specs/14-android-architecture.md
---

# Legacy apps (`legacy/`)

The clients that ran before the 2026-10-05 cutover. They are **frozen**: they keep working
against the same backend API and stay deployed/installed, but receive fixes only. New work goes
to [apps/web](web.md) and [apps/android](android.md).

| Path | What | Where it runs |
|---|---|---|
| `legacy/frontend/` | Old React 19 + Vite 7 web app (Vite base `/legacy/`) | Served at **`/legacy/`** from `legacy/frontend/dist` (no service worker there — `/sw.js` belongs to the new app) |
| `legacy/frontend-compat/` | Old React 18 + Vite 5 Safari 12 app (base `/legacy_compat/`) | Served at **`/legacy_compat/`** from `legacy/frontend-compat/dist` |
| `legacy/android/` | Old single-module Kotlin/Compose app `com.assistant.peripheral` **v1.0.9** (versionCode 10, minSdk 21) | Still installed on the POCO phone alongside the new main app; replaced in place on the A300M |
| `legacy/notes/` | Old root markdown notes (OAuth setup, orchestrator fix summary, visualization system, voice integration plan, tests) | Reference only |

Build a legacy web app only when its code changed: `cd legacy/frontend && npx vite build`
(likewise in `legacy/frontend-compat`), then rsync that dist to the Jetson. Dev ports were 5432
(main) and 5433 (compat).

## Old compat app (`legacy/frontend-compat/`)

Why it existed: React 19 needs Safari 16.4+, so the old main build was a blank screen on the
iPad mini 2. The compat app was a second Vite project that **reused the main app's components**
through the alias `@` → `../frontend/src` and replaced what Safari 12 could not run:

| Shim (`src/shims/`) | Replaces | Why |
|---|---|---|
| `react-syntax-highlighter.tsx`, `react-syntax-highlighter-style.ts` | `react-syntax-highlighter` and its Prism styles (aliases for the esm/cjs style paths too) | Prism grammars use named capture groups |
| `remark-gfm.ts` | `remark-gfm` | `mdast-util-gfm-autolink-literal` uses lookbehind. The shim reimplements **GFM tables only** with Safari-safe regexes; strikethrough and autolinks stay off |
| `MessageList.tsx` | `@/components/MessageList` | iOS momentum-scroll handling |
| `src/gap-compat.css` | flex `gap` declarations | Safari 12 has no flex `gap`; rewritten as `> * + *` margins |

`src/App.tsx` had no `React.lazy` and no voice. Output was a legacy-only ES5 bundle
(`renderModernChunks: false`). The build script skipped `tsc -b` (TypeScript 5.6 lacked the
generic `Uint8Array` used in voice files).

**Lessons kept from it** (the new app avoids both by construction — see [web.md](web.md)):

- **Rebuilding inline markdown in the table shim.** Without remark-gfm, a table arrives as a
  *paragraph* whose inline phase already ran, so `| **X** | y |` is
  `[text "| ", strong{X}, text " | y |"]`. Rebuilding the row from `child.value` silently drops
  every non-text node (`strong`, `emphasis`, `inlineCode`, `link`, `image`, `delete`, `break`
  have children or a url, not a value) and the cell renders blank. The shim's
  `reconstructInline` / `reconstructChildren` re-emit markdown for each node type; any new inline
  type must be added there.
- **The gap shim's `> * + *` never matches text nodes.** `<button><svg/> Configuration</button>`
  has one element child, so the icon touches the label. Fix in that app: `gap: 0` plus an
  explicit `> svg { margin-right }` (and a `margin-left` on a trailing element).
- **Blank iPad screen debugging:** read `logs/remote_console.log` for `[compat]`
  `SyntaxError: Invalid regular expression`, find the offending package
  (`grep -r '(?<' node_modules/<pkg>`), shim and alias it, rebuild.
- iPad Safari pins old bundles unless `index.html` is served no-cache (the backend does this).

Both old builds also had: an inline remote-console script posting to `POST /api/debug/log`,
low-end detection (`hardwareConcurrency <= 2 || deviceMemory <= 1` → `html.low-end`, no
animations), and bottom-to-top message pagination via
`GET /api/sessions/{id}/messages?limit=50&before=<index>`. All three carried over to the new
app.

## Old Android peripheral app (`legacy/android/`)

`com.assistant.peripheral` v1.0.9 — Kotlin, Jetpack Compose + Material 3, OkHttp 4.12,
stream-webrtc-android 1.1.1, DataStore — sources under
`legacy/android/app/src/main/java/com/assistant/peripheral/` (`audio`, `chat`, `connection`,
`data`, `network`, `service`, `settings`, `system`, `ui`, `viewmodel`, `voice`). One app on both
phones, with wake word enabled only on the A300M. It had:

- chat over WebSocket (orchestrator + agent endpoints), session list, resume/rename/delete,
  reverse-paginated history with an LRU cache;
- WebRTC realtime voice (OpenAI) and the WS relay for Qwen/Gemini, software-only AEC (hardware
  AEC/NS disabled), mic gain 0–150 % in 10 % steps, echo-ducking gain;
- the two-layer wake word (RMS gate → Vosk → Whisper confirm; SpeechRecognizer as fallback) and
  the talk word with same-mic command capture — now described in
  [voice/wake-word.md](../voice/wake-word.md), constants ported verbatim to the new apps;
- backend network scanner, reconnection with `voice_start` re-arm, the custom Compose markdown
  renderer (whose streaming RenderThread crash is described in [android.md](android.md)).

**Status:** still installed on the POCO (its wake word must stay off there so it does not
compete with the main app for the mic). On the A300M it was replaced by the lite app on
2026-10-05; the archived v1.0.9 APK allows a rollback with `adb install -r -d` (see
[android.md](android.md)). Installing a `legacy/android` build on the A300M would replace the
lite app, since the package is the same.

### History: the `AssistantViewModel` controller architecture

On 2026-06-10 the old app's 2308-line `AssistantViewModel` (23 `MutableStateFlow` fields, state
mutated across concerns) was split into a 432-line coordinator over five controllers, each
owning one slice of state (final shape `3d621fc` on branch `android-viewmodel-refactor`):

```
AssistantViewModel (coordinator; no MutableStateFlow/MutableSharedFlow fields)
 ├─ SettingsRepository              settings: StateFlow<AppSettings?>  (null until DataStore emits)
 ├─ OrchestratorConnectionController connection state, recovery state machine, pool probe; sole producer of ConnectionEvent
 ├─ ChatController                  per-endpoint buckets (ORCHESTRATOR + AGENT), session cache, WS event router, conflict mediation
 ├─ VoiceController                 VoiceManager + voice state flows; re-arms voice_start on Reconnected
 └─ SystemConfigController          server config snapshot, MCP toggles (independent)
```

- Constructor order fixed by types: Settings → Connection → Chat → Voice; controllers only
  `collect` peer flows in `init`, never read peer state synchronously.
- Cross-controller bus: `ConnectionEvent` = `OrchestratorAdopted`, `Reconnected`,
  `NoOrchestratorFound`, `NewSessionAdopted`, `OrchestratorActiveCapHit`. Adoption and
  reconnection are separate events so voice continuity never has to filter out cold-start
  adoption.
- **`settings` is `null` until loaded** — the structural fix for a cold-start race where
  `onResume()` connected to the default (Jetson) URL before DataStore had emitted the saved one.
- Orchestrator conflict mediation: tapping a non-live Archie conversation (or New) while another
  is live offers *Open the running one / Close it and switch / Cancel*.
- Fourteen invariants (endpoint isolation, pool-probe 400 ms retry, recovery 0/500/2000 ms
  single-flight, voice-stop dedupe, persisted-local-id restore, …) were pinned by parity tests.

The new apps keep the same ideas in different modules: `SettingsStore` (`null` until loaded),
`OrchestratorChannel` (adoption, recovery, `Adopted`/`Reconnected` events), and the main app's
`OrchestratorConflictDialog` (see [spec 14](../specs/14-android-architecture.md)).
The method behind the refactor is in [refactor-methodology.md](../operations/refactor-methodology.md).

### Reverted experiments (don't reintroduce without new evidence)

While stabilising voice on the old app (branch `voice-lifecycle-refactor`, 2026-06-04) these were
tried and reverted because they were speculative and broke other clients: a server-side 45 s
silence timeout, a server-side `{"type":"ping"}` heartbeat task, disabling OkHttp's
`pingInterval`, a bidirectional app-level heartbeat, and disabling uvicorn's protocol PING.
