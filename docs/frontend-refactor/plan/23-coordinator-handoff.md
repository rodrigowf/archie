# 23 — Coordinator handoff (state for resuming after context compaction)

Written 2026-10-04 by the coordinator (Claude) at the end of the implementation phase.
Read this first when resuming; then `README.md` (charter), `plan/20` (decisions), `plan/21`
(operating model), `plan/22` (progress log + open issues).

## 1. Snapshot

| Item | State |
|---|---|
| Branch | `frontend-refactory` (never pushed; Rodrigo approved commits per WP, **no push without asking**) |
| HEAD | `c0fdc7a` (all implementation WPs committed; working tree clean) |
| Backend on Jetson | `local` @ `b77b0f7` (= cherry-pick of `66249f4`), pushed to origin, deployed + restarted 2026-10-04 08:07 |
| New web on Jetson | `/next/` and `/next-compat/` serve `frontend-next/dist-preview/{main,compat}` (rsynced from laptop; Jetson has no node) |
| Current apps on Jetson | `/` and `/compat/` rebuilt from this branch's `frontend/` + `frontend-compat/` (thinking-in-history fix) |
| nginx on Jetson | `~/nginx-server.conf` (+`client_max_body_size 200m;` in both server blocks), backup `~/nginx-server.conf.bak-2026-10-04`; nginx runs as `nginx -c /home/rodrigo/nginx-server.conf` |
| Jetson pool | empty after every test (verify with `GET /api/sessions/pool/live` → `[]`) |
| Laptop `assistant_config.json` | restored after the stash incident (see §8) — `default_model: gpt-audio-mini`; **Rodrigo must verify working-directory paths** |

## 2. Work-package status (all accepted by the coordinator)

| WP | What | Commit(s) |
|---|---|---|
| docs | charter, inventory 01–04, specs 11–14, tokens, fixtures, mockups | a65eafd (+ many doc commits) |
| backend | BF-1/2, BX-1/2, O-1..O-7 (+ review fixes) | 66249f4 (deployed as b77b0f7 on `local`) |
| W-01 | web scaffold, dual builds, compat gates | 75b7c6f |
| W-02 | styles, fonts, icons, primitives, gallery | ed4bffd, 282e4d9 |
| W-03 | UI kit I | 8a04a63 |
| W-04 | UI kit II (overlays, nav, a11y) | 794264b |
| W-05 | protocol client + conformance + mock server (38/38) | b347d9f, 51f6996 |
| W-06 | services + stores | 9dfd468, eea37f1 |
| W-07 | app shell | e9192d4, 9646815 |
| W-08 | markdown | 1f62515 |
| W-09 | conversation view | 45cd270, 7da2d86 |
| W-10 | tool cards | 26f61a6 |
| W-11 | composer + session actions | ca26b76, 9646815 |
| W-12 | voice engine + UI | 887c887 (slot wiring in 78d7970) |
| W-13 | settings + auth | 8a573b4 (slot wiring in 78d7970) |
| W-14 | history, memory, visuals | 78d7970 |
| A-01 | gradle skeleton (22 modules) | af16837 |
| A-02 | model/protocol/reducer (38/38) | 0d23fe5, 9c64f0e |
| A-03 | network/settings/session | 9bc23b7 |
| A-04 | voice parity harness (413 tests first) | 077c7cb |
| A-05..A-08 | voice rewrite: audio, session, wake word, host | cfc532e, 076ad1a, 6117005, 34e57a2, 19ae74e (OI-4) |
| B-01 | design system (102 goldens) | 922fa23 |
| B-02 | streaming markdown | 3d54293, c0eed21 |
| B-03 | data layer + shell | 23c302b |
| B-04 | conversation screen | 29e576d, d549cbf |
| B-05 | tool cards (web-oracle parity) | 584157a |
| B-06 + B-08 | sessions/history + settings/permissions | 0053357 |
| B-07 + B-09 | memory/visuals + system integration | 78e2312 |
| C-01 | A300M lite app (Views) | 1638f18 |

Gates: **G-P met** on both platforms (web + Android reducers pass all 38 shared fixtures).
Web full gate (`npm run verify`): 1581 tests, compat scanner, budgets, token gate — green.
Android full `./gradlew check` — green (after c0eed21).

## 3. Remaining work (in order)

1. **Rodrigo items (blocking):** verify laptop `assistant_config.json` paths; try `/next/` on desktop/phone/iPad and report issues; connect POCO X7 + A300M via ADB (USB or Wi-Fi).
2. **iPad compat spike (W-01, still pending):** load `/next-compat/` on the real iPad mini 2 (iOS 12); confirm render + remote-console beacon (`/api/debug/log`, `[compat]` lines). Steps also in `frontend-next/README.md`.
3. **G-01 voice-parity gate** (spec 14 §6.2) — remaining items need hardware: real "wake up"/talk-word recordings on the A300M (API 21 AVD never receives injected host audio), release smoke on devices, `check_elf_alignment` for native libs (WebRTC 1.1.1 / Vosk 0.3.47 are 4 KB-aligned — E-9: 16 KB upgrade after G-01), scripted lite run (`android-next/app-lite/tools/g01/`). Also confirm A300M API level (`getprop ro.build.version.sdk`; AVD assumes 21) and the `/dev/input` recents question (Q8: `android-next/core/voice-host/docs/q8-dev-input-recents.md` — one `adb logcat` grep on the A300M).
4. **B-07 instrumented tests** on POCO_X7: `:feature:visuals:connectedDebugAndroidTest` (WebView security with self-signed HTTPS, pool leak) + live memory/visual screenshots — skipped for lack of disk (< 6 GB).
5. **D-01 field tests** (spec 14 §6.6: F1–F22, M-F1–7, L-F1–5), 48 h A300M soak with the lite app, release builds (lite: debuggable until field-tested, E-5).
6. **W-15 hardening + cutover:** replace `frontend/`, `frontend-compat/`, `android/` with the new code (BX-3: keep an output dir named `frontend-compat/dist` to avoid a backend change, or a 1-line backend path change); move `frontend-next/dist-preview` routes to `/` and `/compat/`; update CLAUDE.md (it is stale: mentions PermissionModal, useVoiceSession, plugin-legacy in main), the `android-dev` skill paths, deploy notes, archive old APKs; remove the `/next/` preview route or keep it as an opt-in.
7. **Open issues** (plan/22 table): OI-2 agent approvals only answerable with that agent's view open (Android; no temp-socket path), OI-3 Jetson history shows nothing newer than Aug 28 (both clients — backend listing?), OI-5 disk, OI-6 attention notifications for agent permissions + lock-screen full-screen intent (Android).

## 4. Optional backend additions surfaced during implementation (not approved yet — ask Rodrigo)

| Need | Found by | Current client behaviour |
|---|---|---|
| Backend/app version endpoint | W-13 | About shows "Not reported by the server" |
| `modified` time per memory file in the tree API | W-14 | file ages from the mockup not shown |
| `owner_device_id`/device name in `voice_owner_active` (inv01 fix 11, part 2) | W-12 | dock says "Voice active on another device" |
| Per-session skills/agents selection | W-13 | session sheet shows them read-only |
| `Cache-Control` headers on memory/visual GETs | B-07 | client forces revalidation itself (c0eed21) |
| Agent approvals without an open view (temp socket or REST) | B-04/B-06 | OI-2 |

## 5. Runbooks

### 5.1 Deploy backend / web to the Jetson (proven 2026-10-04)

1. Backend code reaches the Jetson via the `local` branch: cherry-pick reviewed backend commits onto `local` in a **separate worktree** (`git worktree add <scratch>/wt-local local`), `git push origin local`, then `ssh rodrigo@192.168.0.200 'cd ~/assistant && git pull -q origin local'` (normal git on the main repo — never the `reset --mixed` rule, that is for `context/` only).
2. Web builds are done on the laptop and rsynced (Jetson has no node): `frontend-next: npm run build:preview` → `rsync -az --delete frontend-next/dist-preview/ rodrigo@192.168.0.200:assistant/frontend-next/dist-preview/`; current apps: `frontend: npm run build`, `frontend-compat: npm run build` → rsync both `dist/`. **Always rebuild/rsync both current dists together.**
3. `/next/` routes register only if `dist-preview` exists **when the backend starts** → rsync before restarting.
4. sudo on the Jetson: password is `SERVER_PASSWORD` in `context/.env`; pipe it (`printf '%s\n' "$PW" | ssh … 'sudo -S -p "" …'`), never print it. Restart: `systemctl restart agentic-backend.service`. nginx: `/usr/sbin/nginx -t -c /home/rodrigo/nginx-server.conf` then `-s reload -c …`.
5. Verify: `/`, `/compat/`, `/next/`, `/next-compat/`, `/api/sessions/pool/live` (200), backend journal free of errors, pool unchanged.

### 5.2 Testing environment

- **One test environment at a time** (Chrome DevTools browser *or* one emulator), enforced by `flock /tmp/archie-locks/testenv.lock`; Gradle via `flock /tmp/archie-locks/gradle.lock`; npm via `flock /tmp/archie-locks/npm.lock`. MCP-browser users hold the lock with a background `flock -n … -c 'sleep N' &` and kill only their own holder.
- The chrome-devtools MCP browser **rejects the Jetson's self-signed cert** (`ERR_CERT_AUTHORITY_INVALID`): view the live backend through a local Vite dev server proxying `/api` to `https://192.168.0.200` with `secure:false, changeOrigin:true, ws:true` (the config must live inside the package dir; a config outside it fails to resolve `vite/internal`). The temporary `frontend/vite.audit.config.ts` was deleted; recreate if needed.
- `frontend-next` mock server: `mock-server/server.mjs` (default port 8799, `--fast`, `--scenario`, `--list`); agents should use random ports.
- AVDs (hand-made, no avdmanager originally; cmdline-tools were later installed by A-01): `POCO_X7` (1220×2712, 440 dpi, API 36 google_apis_playstore x86_64) and `A300M_API21` (540×960, hdpi, API 21). Both had their userdata overlays deleted on 2026-10-04 to free disk → they cold-boot factory-fresh. The dangling `Medium_Phone_API_36.0.ini` (pointed to a missing dir) was removed.
- Live Jetson etiquette: read-only by default. **Opening a session from history `start`s it in the pool** → close exactly that session afterwards (`POST /api/sessions/{local_id}/close`) and confirm `pool/live` is `[]`. Never start voice, never POST the cast endpoint (it would put things on Rodrigo's TV), never PUT config on the Jetson.
- Check `df -h /` before emulator/Gradle-heavy work; agents stop if < 6 GB free (emulator) / < 3 GB (builds). Free space by `./gradlew :<module>:clean` of finished modules; never delete Rodrigo's `~/.cache` without asking.
- Debug-key signing: both apps use `~/.android/debug.keystore` (SHA-256 `F4:D6:91:…:1E:3D`), copy in `context/secrets/android/peripheral-signing.keystore`; lite `versionCode` ≥ 100 so it installs over the old app (v10).

### 5.3 Agent briefs (template that worked)

Every brief: WP id + goal; what is already committed (with paths/commits) and who works concurrently where; "Rodrigo APPROVED the design: mockups + IA"; read-first list (plan/21, the WP row in spec 13/14, relevant spec 12 sections, inventory sections, decisions P-x); **exact boundary**; rules block (locks, no emulator/browser unless locked, live Jetson read-only, **no git writes**, do not commit, `./gradlew --stop`, disk check, modest resources); DoD incl. tests + screenshots saved to `docs/frontend-refactor/audit/screenshots/<wp>-*.png` (gitignored) compared with the mockups; "Report ≤200 words". Parity WPs: point at the already-committed sibling implementation (web ↔ Android) as the oracle.

### 5.4 Review → commit procedure

`git status` limited to the boundary; read report deviations; spot-check screenshots; re-run the gate for correctness-critical WPs (protocol conformance, full `npm run verify`, `./gradlew check`); commit only the WP's paths with a descriptive message + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Shared files edited by several WPs (e.g. `frontend-next/src/app/slots/index.tsx`, `android-next/app-main/**/shell/**`) are committed **together with the last WP that touches them**, so no commit imports uncommitted code.

## 6. Coordinator rulings (not elsewhere as decisions)

| # | Ruling | Why |
|---|---|---|
| CR-1 | Resume checkpoint kept **in memory only** (spec 12 T-10 wins over spec 13's sessionStorage) | persisted checkpoint caused duplicate-on-reload (W-7); in-page reconnect still replays |
| CR-2 | Reconnect = spec 12 T-13 exponential backoff (1 s ×2ⁿ, cap 15 s, ±20 %, reset on `session_started`) on **both** platforms; old fixed 3 s superseded | the LB label in inv04 belonged to the 30 s ping (kept exactly) — inv04 errata #9 |
| CR-3 | Voice command queue never drops, no cap (spec 12 V-4 amended) | RS-04/B2: dropping can lose `session.update` |
| CR-4 | Retry after a failed start = close + reopen the socket (spec 12 §6.13) | avoids re-sending `start` on a failed socket; no reducer change |
| CR-5 | Queued prompts render **only** in the composer tray until dispatched (I-12); observers pop the tray head on `status{processing}`; `dispatchedFromTray` swallows pre-O-6 re-echo; checked **before** `endTurn` | fixes double rendering + backend O-6 |
| CR-6 | BG-1 "Background update" divider only when the entry before the new run is not a user entry | O-3 echoes make other-device prompts visible |
| CR-7 | SEQ-5/SEQ-8: any `error` bypasses the pre-start hold; only a start error releases held frames; H-6, WATCH-1, ID-4/PM-5 codified from the web implementation | web/Android parity |
| CR-8 | Theme default **dark** on both platforms (D3); old Android installs keep their migrated choice | D3 dark-first |
| CR-9 | UI never shows the word "Orchestrator": Archie tabs/rows show the conversation title (fallback "New conversation") | IA §1 vocabulary |
| CR-10 | Drawer/list pane never autofocuses search on touch (keyboard pop-up); Ctrl+K / `/` focus search on desktop | UX |
| CR-11 | One status indicator per tab; "unseen" = badge on the icon, only for activity that arrived in background (not restored tabs) | clarity |
| CR-12 | Dev gallery ships only in dev or with `VITE_GALLERY=1` (`npm run build:gallery`); budgets apply to production builds | budget |
| CR-13 | Conversation heavy parts (markdown, tool cards, permission cards) are prefetched lazy chunks behind "Loading conversation…"; main initial JS ≈155–182 kB / 190 | budget |
| CR-14 | No AudioWorklet copy for `/compat/` (Safari 12 can't use it; modern Safari uses the main build) | P-8 |
| CR-15 | Voice parity: harness errors may be fixed only with evidence from the old code (approved: A-06 WsPcm +40 ms virtual time ×3; A-07 two timing/count fixes); behaviour changes in tuned areas are reverted (A-07 mic release moved back to re-arm) | D1 + tuning memory notes |
| CR-16 | Accepted voice fixes vs old behaviour: second start ignored; new start cancels pending wake re-arm; B10 ends the session; stopVoice with no live session is a no-op (OI-4); single-writer PcmSink (B3); negative mic read reopens; SR backoff overflow guard; FGS start idempotent + stop waits for in-flight start | bugs |
| CR-17 | Whisper confirm gate keeps the old quirk: blank serverUrl lets matches through (inv04 errata #5) — flagged in KDoc for later review | parity |
| CR-18 | MCP: empty `enabled_mcps` = all enabled; the last enabled server cannot be switched off | backend semantics |
| CR-19 | HTTP: GET responses without freshness headers are treated `no-cache` (WebSocket upgrades untouched) | stale memory/visual reloads |
| CR-20 | Vosk `noCompress` declared by each app; in practice assets stay compressed (same as old app) and load fine | A-07/C-01/B-09 finding |
| CR-21 | `.gitignore`: `!android-next/**/src/debug/` (root `debug/` rule would hide Android debug source sets) | C-01 finding |
| CR-22 | Bash tool-card summary = the tool's `description` input, fallback the command (both platforms; Android parity enforced by a web-oracle test over 164 calls) | live-check finding |
| CR-23 | P-1 enforcement: lifecycle/teardown/unload paths never send `close`/`stop`; explicit close closes for everyone (backend already only unsubscribes on disconnect) | Rodrigo P-1 |

## 7. Accepted deviations worth remembering

- W-04: tab × is mouse-only (a button inside a tab is invalid); keyboard uses Delete.
- W-07: rename/close in ⋮ handled by shell; past Archie from history always resumes (no read-only view for Archie).
- W-10/B-05: category mapping follows the mockups (WebFetch=navigate; Grep/Glob/WebSearch=search; chrome input=interact; run_script=script); cards have no chevron; durations are client-timed (history shows none).
- W-11: voice messages via MediaRecorder, WAV fallback under `?caps=compat`.
- W-12: passive viewers keep a usable composer with "Take over".
- B-03: rail/list pane hand-built (not NavigationSuiteScaffold); layout class from measured width.
- B-06: new agent session uses global config (backend `start` takes no provider/workdir); choose them in Session settings.
- B-08: speaker level = live system call volume; echo ducking stays a slider; text size/reduce motion in SharedPreferences; recents trigger left to the lite app.
- B-09: QS tile updates while the panel is open (ACTIVE_TILE unreliable on API 36).
- C-01: goldens rendered at SDK 28 (closest cached Robolectric image); idle PSS 128.9 MB with Vosk (budget 140).

## 8. Learnings and incidents

- **Usage limit (2026-10-03 ~15:40):** 6 parallel agents exhausted the API session limit; all were cut off mid-work. Resuming via SendMessage kept their context and partial files. Cap: **≤ 4 implementation agents**; follow-ups are cheap.
- **Git stash incident (2026-10-04):** an agent's `git stash push -- <path> -q` failed (flag parsed as path) and `git stash pop` applied an unrelated April stash, overwriting the gitignored `assistant_config.json` (no backup exists). Rule: **git writes are coordinator-only** (plan/21 §3). Recovery: `git reset -- assistant_config.json`, stash kept, known edit re-applied.
- **Disk:** laptop at 98–100% twice; emulator userdata overlays (≈7.6 GB) and finished-module build dirs are the safe reclaimables.
- **`pkill -f`/`pgrep -f` self-match:** killing processes by pattern from the Bash tool can kill its own shell (exit 144) — use specific PIDs.
- **Parallel agents on shared files** (slots/shell) worked when each kept its edits minimal and the coordinator committed them together.
- **Spec drift is normal:** inventory values were wrong in places (inv04 errata §12); the old code + `old_constants.json` are authoritative for voice parity.
- **Flaky tests under full-check load:** fix with subscribe-before-act and longer waits, not retries.
- **Opening sessions has side effects:** clicking a history row in the old web app `start`s that session in the Jetson pool (the audit left "twitter browser test" open until closed with Rodrigo's OK).
- **Jetson `git branch --show-current` unsupported** (old git) — use `git rev-parse --abbrev-ref HEAD`.

## 9. Rodrigo's preferences observed in this project

Decides quickly when given clear options with a recommendation; approved every backend change; wants call-app-style audible+visual feedback for voice problems; dark-first design; consistent web/Android visuals; organized settings; commit per package, never push without asking; wants to be consulted on product decisions but not on routine engineering; reviews artifacts locally (asked for the local file link of the mockups).
