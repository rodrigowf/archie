---
name: tv
category: archie/clients
tags: [android-tv, fire-tv, tv-material, compose, navigation3, app-tv, d-pad, leanback, gradle]
created: 2026-10-16
modified: 2026-10-27
summary: apps/android/app-tv — Archie TV (com.assistant.archie.tv), the 10-foot client for Fire TV / Android TV; module, build/install, shell architecture and the extension points the screen work packages fill.
source: curated (spec 15 WP T0, 2026-10-16)
references:
  - ../specs/15-tv-app.md
  - ../specs/14-android-architecture.md
  - ../specs/12-client-protocol.md
  - android.md
  - ../devices/devices.md
  - ../operations/troubleshooting.md
---

# Archie TV (`apps/android/app-tv`)

The Android TV / Fire TV client: the same conversations, agents, visuals, memory, voice and
settings as the other apps, laid out for a TV and driven with the plain remote (D-pad, OK, Back,
Menu, Play/Pause, Rewind/FF, and the mic button). The design is
[spec 15](../specs/15-tv-app.md); the shared Android core it reuses is
[spec 14](../specs/14-android-architecture.md) and [android.md](android.md).

| | `:app-tv` |
|---|---|
| Package | `com.assistant.archie.tv` (namespace and applicationId) |
| Devices | Fire TV (Fire OS 7/8; the living-room AFTKM is 32-bit, 960×540 dp) and Android TV / Google TV 8+ |
| SDK | minSdk 26, targetSdk 36, compileSdk 37 |
| ABIs | `armeabi-v7a` + `arm64-v8a` (`-Parchie.emulatorAbis=true` adds `x86` + `x86_64`) |
| UI | Compose + `androidx.tv:tv-material` 1.1.0 + Navigation 3; dark theme only |
| Launch activity | `com.assistant.archie.tv/.shell.MainActivity` (`LEANBACK_LAUNCHER` + `LAUNCHER`) |
| Debug APK | `apps/android/app-tv/build/outputs/apk/debug/app-tv-debug.apk` |
| Release APK | `apps/android/app-tv/build/outputs/apk/release/app-tv-release.apk` (R8, baseline profile; use it for every on-device check) |
| Version file | `apps/android/app-tv/version.properties` (starts at 1 / 0.1.0) |
| Signing | `tv.*` entries of the gitignored `apps/android/keystore.properties` (same key as `archie.*`); debug builds fall back to the SDK debug key without them |

## Build and install

All Gradle runs go through the shared heavy-job wrapper (one build at a time, memory-capped):

```bash
# build, unit tests + goldens, lint
shared/scripts/heavy.sh --gradle apps/android :app-tv:assembleDebug :app-tv:testDebugUnitTest :app-tv:lintDebug
# re-record the Roborazzi goldens (src/test/screenshots/)
shared/scripts/heavy.sh --gradle apps/android :app-tv:recordRoborazziDebug
# the module's full gate
shared/scripts/heavy.sh --gradle apps/android :app-tv:check

# install on the Fire TV (address from discover-firetv.sh; check what is on screen first)
adb -s 192.168.0.15:5555 install -r apps/android/app-tv/build/outputs/apk/debug/app-tv-debug.apk
adb -s 192.168.0.15:5555 shell am start -n com.assistant.archie.tv/.shell.MainActivity
```

Before launching anything on the living-room TV, check that nobody is watching:
`dumpsys window | grep mCurrentFocus` and `dumpsys media_session | grep "state=3"`. `adb install -r`
alone does not change the screen.

**What is not in the APK.** The TV has no wake word (spec 15 TV-8), so the convention plugin
`archie.android.app.tv` keeps the 68 MB Vosk model asset (`androidResources.ignoreAssetsPatterns`)
and the Vosk native libraries (`packaging.jniLibs.excludes`) out, although `:core:voice-host` still
brings `:core:wakeword` onto the classpath. Nothing may construct a wake-word engine in this app.

## Module layout

```
app-tv/src/main/kotlin/com/assistant/archie/tv/
  TvApplication.kt            builds TvAppGraph; VoiceHostOwner (T4 supplies the voice host)
  graph/   TvAppGraph         manual DI: settings, HttpStack/TrustStore, ArchieApi, OrchestratorChannel,
                              History/Conversations/OpenSessions/Connection/Memory/Visuals/ContentChanges
                              repositories wired exactly as MainAppGraph; ConnectionModel; toasts; voice seams
           TvPrefs            TV-only prefs (setup done, text size, key hints, reduce motion)
  shell/   MainActivity       single Activity; dispatchKeyEvent → KeyRouter
           TvShell            overscan box, drawer, header + StatusCluster, NavDisplay, KeyHintBar,
                              toasts, OptionsSheet
           TvShellController  back stack + TvNavigator, ScreenChromeRegistry, options sheet, KeyRouter, Back
           KeyRouter          Menu / Play/Pause / Rewind / FF / long-press OK routing (pure, JVM-tested)
           TvNavigator        push / pop / selectRoot; TvDestination (the 5 drawer entries); BackPolicy
           Routes             Navigation 3 keys
           TvRoutes           key → route dispatcher + TvRouteContext
           TvScreen           the per-screen contract (ScreenChrome, KeyHints, TvOption, …)
           TvToasts           the toast queue
  theme/   TvTheme            ArchieTheme (dark) + TV type scale (Text size ×1 / ×1.15 / ×1.3) + TvDimens
           TvComponents       FocusCard, TvButton, RowHeader, KeyHintBar, KeyCap, StatusCluster,
                              TvToastHost, TvEmptyState, TvBadge, rememberInitialFocus
           TvIcons, TvFormat
  home/ conversations/ conversation/ visuals/ viewer/ memory/ settings/ firstrun/   one route each
  voice/   TvVoice            process-scoped wiring (TvVoiceOwner = the Application); TvVoice.hostFactory
           RemoteMicSource    the ONE recorder on the remote (VOICE_RECOGNITION 16 kHz) + BurstDetector
           TvVoiceRouter      burst → Live / Dictation / VoiceMessage / Test (pure)
           TvVoiceController  overlay + mic-test state; voice messages (VoiceIo) and dictation (/api/transcribe)
           LiveMicBridge      the live call's continuous mic (WS relay MicSource, WebRTC RtcMicFeed)
           TvLiveVoice        Play/Pause toggle over the voice host, idle end, KEEP_AWAKE pings
           VoiceOverlayHost   hold pill, Sending…, live panel, dictation chip; the RECORD_AUDIO request
           TvVoiceSeams       LiveVoiceToggle, VoiceChip (status chip), TvVoicePresence
  control/ TvControl          T3's part of the graph (`graph.tvControl`): control socket, command handler,
                              remote screens, the Viewer's WebView pool (size 1) + WebTrust, Visuals dots
           TvControlClient    /api/tv/ws: hello, ping, commands → results, state frames, backoff, 4409/4401
           CommandHandler     the spec 15 §5.2 commands (TvCommandEnv seam, JVM-tested)
           TvLinks            open_url resolution on top of :core:markdown InternalLinks
           TvRemoteScreens    RemoteScreen registry (back / reload / scroll / media) + state info
           StateReporter      `state` frames; TvScreens names; LauncherPing (KEEP_AWAKE)
  viewer/  ViewerScreen, TvWebView (ViewerController, KeyFrame, TvWebViewClient), PointerModel, PageScripts
  visuals/ VisualsScreen, VisualKind, VisualsSeen, VisualDialogs (Rename, Copy link + QrCode), T3Components
  memory/  MemoryScreen (tree + preview), MemoryDocScreen (reading model), TvMarkdown (blocks with links)
```

`app-tv/build.gradle.kts` already depends on everything spec 15 §6.1 lists (`:core:*`,
`:feature:chat|toolcards|sessions|memory|visuals|settings`, tv-material, Navigation 3), so the
screen work packages do not need to edit it.

## The shell

- **Layout.** Everything sits inside the overscan-safe box (48 dp left/right, 27 dp top/bottom).
  Left: the tv-material `NavigationDrawer` (Archie mark + Home, Conversations, Visuals, Memory,
  Settings), collapsed to icons, expanded while it has focus; Left from the content's left edge
  enters it, Right/OK leaves it. Top: the screen title and the status cluster (connection dot +
  label, voice chip). Bottom: the key-hint bar. Toasts top-center. The options sheet slides in on
  the right.
- **Navigation.** Navigation 3 back stack of `@Serializable` keys (`Home`, `Conversations`,
  `Conversation(localId, sdkId, archie)`, `Visuals`, `Viewer(target, title, pushed)`, `Memory`,
  `MemoryDoc(path)`, `Settings`, `SettingsPage(id)`, `Connect(firstRun)`). A drawer destination
  replaces the stack root (`TvNavigator.selectRoot`); content is pushed (`push`). The entry
  decorators are the saveable-state holder and the per-entry `ViewModelStore` (T2's
  `ConversationViewModel` per key).
- **Back** (`BackPolicy`): options sheet open → close it; focus in the drawer → back to the content;
  stack deeper than one → pop; a drawer root other than Home → Home; Home (or the first-run Connect)
  → finish the Activity. Finishing never closes a session: everything lives in the process-scoped
  graph. A screen with its own Back (viewer history, an open text field) registers a `BackHandler`,
  which wins over the shell's.
- **Accelerator keys** (`KeyRouter`, fed by `MainActivity.dispatchKeyEvent`): Menu, Play/Pause
  (also Play / Pause), Rewind, Fast-forward and long-press OK go to the options sheet or the top
  screen overlay (`TvOverlay` layer) when one shows — then never to the screen behind it — else to
  the top screen's handler, then to the global default — Menu and long-press OK open
  the options sheet, Play/Pause calls `TvAppGraph.liveVoice` (T4). A handled key also swallows its
  key-up; an unhandled one reaches Compose unchanged (Rewind/FF can reach a WebView). Long-press OK
  on a `FocusCard` / `TvButton` is delivered by tv-material (`onLongClick`), which routes it through
  the same chain and skips the click.
- **First run** (spec 15 §3.6): until `TvPrefs.setupDone`, the stack root is `Connect(firstRun =
  true)`: LAN discovery (`ConnectionRepository.scan` → `ServerDiscovery`), manual address with the
  IME (Go connects), the TOFU certificate trust dialog for self-signed servers, then Continue →
  Home. Settings → Connection → Server pushes the same screen. The logic is `:feature:settings`'
  `ConnectionModel` (the phone's Settings → Connection), one per process in the graph.

## Extension points (T1–T5)

Each screen lives in its own package with one route file; a work package replaces the body of its
route and screen, never the shell files.

| WP | Files to own | Entry point (signature fixed by `TvRoutes.Entry`) |
|---|---|---|
| T1 Home + Conversations | `home/HomeScreen.kt`, `conversations/ConversationsScreen.kt` | `HomeRoute(ctx)`, `ConversationsRoute(ctx)` |
| T2 Conversation | `conversation/ConversationScreen.kt` (+ more files in `conversation/`) | `ConversationRoute(key: Conversation, ctx)` |
| T3 Visuals, Viewer, Memory, control channel | `visuals/`, `viewer/`, `memory/`, `control/` | `VisualsRoute`, `ViewerRoute(key: Viewer, …)`, `MemoryRoute`, `MemoryDocRoute(key: MemoryDoc, …)` |
| T4 Voice (done) | `voice/`, `settings/RemoteMicPage.kt`, `firstrun/MicCheckStep.kt` | `TvVoice.install` sets `TvAppGraph.voice` / `.liveVoice`; `TvVoice.hostFactory` |
| T5 Settings, perf (done) | `settings/`, release build, `baseline-prof.txt` | `SettingsRoute`, `SettingsPageRoute(key: SettingsPage, …)` |

What a screen gets and gives:

- **`TvRouteContext`**: `graph` (all repositories, `connectionModel`, `prefs`, `toasts`, voice seams),
  `nav` (`push`, `pop`, `selectRoot`, `replaceRoot`, `popToRoot`), `toasts`.
- **`ScreenChromeEffect(contract)`** — call once per composition with a `TvScreenContract`
  (usually `ScreenChrome(...)`): `title`, `hints` (`KeyHints.Default`, `KeyHints.Ok("Open")`,
  `KeyHints.Page()`, …), `options` (`TvOption(id, label, icon, detail, enabled) { … }` — the options
  sheet adds "Go to Home" / "Settings"), `fullScreen` (hides the drawer), `showHeader` (hides the
  shell header), and `keys: TvKeyHandler` for `TvKey.MENU / PLAY_PAUSE / REWIND / FAST_FORWARD /
  LONG_OK` (return true when handled). In the viewer, Play/Pause belongs to page media: handle
  `PLAY_PAUSE` there.
- **Components** (`theme/TvComponents.kt`): `FocusCard` (focus = 1.05 scale + 3 dp primary border +
  raised surface), `TvButton` (Primary / Secondary), `RowHeader`, `TvEmptyState`, `TvBadge`,
  `CardMeta`, `rememberInitialFocus()` (request first focus after layout — every screen must leave
  exactly one element focused). Rows and lists use `Modifier.dpadFocusRestorer()` (theme/TvFocus.kt:
  Compose's `focusRestorer` for D-pad entries only — Compose's own one also redirected a screen's
  explicit focus restore to its saved child). Text and icons inside
  tv-material surfaces use `androidx.tv.material3.Text` / `Icon`; colors come from
  `TvTheme.colors` / `ArchieTheme.extended`, never literals; sizes from `TvTheme.type`.
- **`LocalTvKeyActions`**: `openOptions()` for an on-screen "More" button, `longPress()`.
- **State-driven screens.** Keep every screen a `XScreen(state, actions)` composable fed by its
  `XRoute`, so Roborazzi goldens (`src/test/.../TvShellGoldens.kt`, 960×540 dp television
  qualifier) render it without a graph.

## TV control channel, Viewer, Visuals, Memory (T3)

**Control socket** (`control/`, spec 15 §5, spec 12 §9.5). `TvControl.start()` (from the
Application) connects `ws(s)://<server>/api/tv/ws` once the app has connected to its server, then
keeps it up for the life of the process — also while another app is in front and the orchestrator
socket is parked, because that is when agents push pages. Backoff 1 s × 2ⁿ capped at 15 s (reset on
`ready`); the orchestrator reconnecting or the network coming back retries at once; a new server URL
moves it. Only a close **4401** whose reason (the `{"type":"error","error":…}` frame the server sends
first) is `unauthorized` stops for good and logs `TV control rejected: unauthorized`
(`TvControlClient.state` = `Rejected`, until Settings → TV control → Connect again or a new token);
any other 4401 (`hello_timeout`, `invalid_json`, `expected_hello`, no reason) retries with the backoff.
A **4409** (replaced by a newer socket with this device id) retries too, with a backoff that keeps
growing across consecutive replacements (two clients with one id never ping-pong faster than every
15 s); the orchestrator reconnecting starts it fresh. Command results name URLs without the default
port (`http://h/x`, as Chromium reports them in `state`). It uses the app's
`HttpStack` (same OkHttp client, TOFU pins, ping interval). Logcat tag: `ArchieTv/control`.

- Hello: `device_id` = a random UUID kept in `TvPrefs.controlDeviceId`; `device_name` =
  `TvPrefs.controlDeviceName`, else `Settings.Global.DEVICE_NAME`, else "Fire TV" / the model;
  `token` = `TvPrefs.controlToken` when set; capabilities = every command below.
- `TvPrefs` (Settings → TV control): `allowAgentsToOpen` (default on; off refuses
  every screen-changing command, `get_state` / `notify` still work), `showOpenedToasts` (default on).
- Commands (`CommandHandler`): parameters are checked before anything moves; then the app comes to
  the front (`navHolder.bringToFront`, waits ≤ 6 s for the shell), navigates, answers once the screen
  is on top (not when the page has loaded) and pings the launcher (KEEP_AWAKE). A Viewer replaces a
  Viewer on top, a note replaces a note. `open_url` resolves with `TvLinks` (the shared
  `InternalLinks` resolver + the web app's `/#/memory/…`, `/#/visuals/…` routes; other `http(s)` URLs
  and server paths open in the Viewer; other schemes are refused). `open_visualization` /
  `open_memory` answer `ok:false` for a page / note the server does not have. `back` uses the
  screen's own history first and never leaves the app. `notify` and `get_state` never bring the app
  forward (`notify` is a system toast while another app is in front).
- `state` frames: `screen` ∈ `home conversations conversation visuals viewer memory memory_doc
  settings connect none`, plus `url`, `title`, `path`, `conversation`, `voice` (chip label, lower
  case), `foreground`.
- **Remote screens.** A screen that can do more than navigation registers a `RemoteScreen` with
  `RemoteScreenEffect(graph.tvControl.remote, key, RemoteInfo(...), screen)`: `back()`, `reload()`,
  `scroll(direction)`, `media(action)`. The Viewer and MemoryDoc do; T2's conversation screen can
  (then `scroll` works there too).

**Viewer** (`viewer/`, mockups 11/11b/12). One WebView (`WebViewPool` size 1, destroyed when the
Viewer leaves), `:feature:visuals`' ArchieWebView config (no JS bridge, server-pinned TLS) wrapped by
`TvWebViewClient`: a D-pad key the page did not use stays in the Viewer and Up/Down scroll the page.
Links to other sites load in place (no browser on a TV). Pointer mode: a `KeyFrame` parent takes the
D-pad before Chromium (`WebView.dispatchKeyEvent` bypasses `OnKeyListener`); `PointerModel` moves
the cursor with acceleration, pushing an edge sends mouse-wheel events at the cursor, OK sends a
touch tap, every move a hover. It switches on by itself when the page has nothing focusable. Menu:
pointer mode on → turns it off; else the Viewer's own options sheet (Reload, Pointer mode, Zoom ±
with Left/Right as CSS zoom, Find in Visuals, Hard reset = origin storage + cookies + HTTP cache,
Close). Play/Pause → page media (`PageScripts.media`, autoplay allowed: the remote is the gesture);
Rewind/FF scroll 0.8 of the page; Back = page history, then close. The Viewer is edge to edge
(`ScreenChrome.edgeToEdge`: no overscan padding, header or key-hint bar; pages are made for
1920×1080); its toast, chips and the options sheet's rows keep to the overscan-safe box. The options
sheet traps focus and closes on Back (`trapFocus` + `dismissOnBack`). Pushed pages show "Opened by
Archie · title" and ping KEEP_AWAKE every 60 s while the Viewer is RESUMED (never from the
background: `keepAwakeWhileForeground`). A dead renderer (`onRenderProcessGone`) rebuilds the pooled
WebView (the `AndroidView` is keyed by the pool entry) and shows the error panel; the new WebView
stays blank until Retry, which loads the page and gives it focus. Live reload (VZ-6) keeps the scroll
position.

**Visuals** (`visuals/`, mockup 10): own header (count, search, status), 4-column cards with a kind
band (`VisualKind`, read from path/title: the backend has no kind), a dot when the page changed since
it was last opened (`VisualsSeen`, per process), Menu → Open / Rename (IME; shell overlay layers that
close on Back, focus back on the card) / Copy link (address +
QR code from the dependency-free `QrCode` encoder, also on the clipboard) / Refresh.

**Memory** (`memory/`, mockups 13/13b): tree (MEMORY.md first, folder counts, OK expands; folders
start collapsed on the TV, a search opens every match) + preview (a folder previews its INDEX.md),
"Updated" chip on `memory_changed`. MemoryDoc: every top-level block is a focus stop (3 dp border,
no scale); a block taller than the screen scrolls inside by 0.8 viewport first; Rewind/FF page; the
focused block's links are picked with Left/Right and opened with OK (`MemoryLinkResolver` → MemoryDoc
/ Viewer); refetches in place on `memory_changed` / resync.

## Voice (T4)

Spec 15 §4. Everything starts from the remote's mic button, which never reaches the app as a key:

- **`RemoteMicSource`** keeps one `AudioRecord(VOICE_RECOGNITION, 16 kHz)` armed while the app is
  started (process lifecycle). `read()` blocks while the button is up, so a watchdog (50 ms) ends a
  burst after 350 ms without frames; bursts under 250 ms are dropped ("Too short"), 60 s is the cap.
  **Continuous-stream probe:** on devices where `read()` never blocks (phones, most Android TV
  boxes) a burst that starts within 400 ms of arming is held back; if it pauses (a real press at arm
  time) it is delivered then, if frames run on to 2.5 s after arming the source turns `UNSUPPORTED`
  ("This device's microphone streams continuously; hold-to-talk needs a remote that streams only
  while its button is held"), drops that audio and disarms — never a capped burst of room audio to
  Archie. Probed again the next time the app comes to the front. A queued re-arm after a failure
  never fires after `stop()` (`startIfWanted`).
  Fire OS shows its microphone indicator top-right while the app is open (the recorder is armed).
- **Routing** (`TvVoiceRouter`, decided when the burst starts): live voice owned here → the call;
  an agent conversation on screen → dictation (`POST /api/transcribe` → chip with Send focused /
  Edit with the IME / Cancel; Send = `ConversationRepository.send`); a mic test page → the test
  meter; anything else → a voice message to Archie (`send_audio` WAV on the orchestrator socket, as
  the phone's push-to-talk; with no Archie conversation open it starts a new one first).
- **Take over** (spec 12 §7): while another device owns live voice, Play/Pause does not take it
  over: it shows "Live voice is on another device — Take over? Press ⏯ again" and only a second
  Play/Pause within 6 s, or the explicit **Take over** button (hero, composer, Menu → "Take over live
  voice") starts it here (`LiveVoiceToggle.takeOver`).
- **Live voice** (Play/Pause, hero button): `AndroidVoiceHost.create` with `HostConfig.tv` — no wake
  word, the call on the media stream. The TV never opens a second recorder on the remote:
  `LiveMicBridge` turns its bursts into a continuous stream (voice while held, digital silence
  otherwise), fed to the WS relay (Qwen/Gemini) as its `MicSource` and to WebRTC (OpenAI) through
  `RtcMicFeed` with the ADM recording VOICE_COMMUNICATION only as a clock (spec 15 §4.2 has the
  spike). The provider's VAD ends each turn on the silence. Ends on Play/Pause, Back while the
  panel shows, or 120 s with nobody talking (`TvVoicePrefs.liveIdleSeconds`).
- **Status chip**: Idle · Listening (button held) · Thinking (sending / transcribing, live thinking)
  · Speaking · Live · On phone.
- **Settings → Remote microphone** (`settings/RemoteMicPage.kt`): status (Ready / streams
  continuously (unsupported) / Alexa also listens
  / no permission / busy), the test meter with Play back, "Listen while other apps are open" (Q2,
  not built: disabled), start / stop sounds. First run step 2 (`firstrun/MicCheckStep.kt`) is the
  same check after Connect.

Shared-module additions (each default keeps the phones unchanged, each with a test):
`:core:audio` `FixedMicSourcePolicy`, `DefaultAudioCore(micSource)`; `:core:voice`
`WebRtcDeps.micSourcePolicy / micFeed / mediaPlayback`, `RtcAudioOptions.mediaPlayback`,
`RtcMicFeed`; `:core:voice-host` `HostConfig.wakeWord / audio`, `HostConfig.tv`, `HostAudio`,
`NotificationSpec.tv`, `MicPushToTalkRecorder(audioCore)`; the runtime never builds a wake engine
when `wakeWord = false`.

**The `RECORD_AUDIO` grant is provisioned, and the app recovers when it is lost.** First run and
`TvVoice.requestPermission` do launch the system dialog, but on Fire TV the request comes back
denied *immediately, without any dialog being shown*, leaving `shouldShowRequestPermissionRationale`
false — the same signal the platform gives for "Don't ask again". `MicPermission.of` reads that as
`BLOCKED`, and `TvVoice.openAppSettings` opens Fire OS's app page, which lands on Permissions →
Microphone and is grantable with the D-pad alone. The explanation is a *platform* toast, because the
in-app `TvToasts` overlay would be hidden behind Settings. Until 2026-10-27 that branch only showed
the red "Archie needs the microphone to hear the remote" toast, so Live voice and hold-to-talk
stayed dead until someone ran `adb … pm grant`.

The grant itself still comes from `shared/scripts/tv_setup.sh` (`pm grant … RECORD_AUDIO`), which
also sets the `AUTO_REVOKE_PERMISSIONS_IF_UNUSED` app-op to `ignore`: without it Fire OS resets the
permissions of an app it considers unused and silently takes the mic away (seen 2026-10-27, 10 days
after provisioning — moving the stick to another TV re-runs enough of setup to do the same, and it
re-enables Alexa's voice UI too). `tv_setup.sh --no-install` re-applies the whole set and
`--check` reports every row; symptom → fix in [troubleshooting.md](../operations/troubleshooting.md).

**Testing without a human** (debug builds only, `src/debug`): `DebugMicReceiver`, guarded by the DUMP
permission so only `adb shell` can call it:

```bash
R="adb -s 192.168.0.15:5555 shell am broadcast -n com.assistant.archie.tv/.voice.DebugMicReceiver -a com.assistant.archie.tv"
$R.DEBUG_MIC_BURST --es wav hello.wav     # a WAV in the app's files dir, played as a held mic button
$R.DEBUG_SET_SERVER --es url http://127.0.0.1:8777   # with adb reverse tcp:8777 tcp:8777 to a lean backend
$R.DEBUG_OPEN_AGENT                       # a new agent session on screen (dictation target)
$R.DEBUG_CLOSE_AGENT --es local_id <id>
$R.DEBUG_NEW_ARCHIE                       # a new Archie conversation
$R.DEBUG_LIVE_VOICE                       # the Play/Pause toggle
$R.DEBUG_MIC_SPIKE                        # logs which mic sources stream next to the armed remote
```

Push a WAV with `adb push x.wav /data/local/tmp/` then `run-as com.assistant.archie.tv cp
/data/local/tmp/x.wav files/`. Logs: `ArchieTv/Voice`, `ArchieTv/Debug`.

## Status (T0, 2026-10-16)

Shell, theme, graph, first run and placeholders are in. The placeholder screens already show real
data: Home rows (Archie, open agents, visuals, memory, recent conversations), the Conversations list
grouped by day, the Visuals grid, the Memory tree with a text preview, memory notes as focusable
paragraphs, Settings → Connection / Display / About. Home, Conversations and Conversation are T1/T2's (below);
T3 (Viewer, Visuals, Memory, control channel) and T4 (voice) are described in their own sections.

## Home, Conversations, Conversation (T1 + T2)

- **Shell additions.** The drawer is an overlay (collapsed 72 dp, 272 dp open over a scrim; the
  content never moves) with an amber dot on Conversations while an open session waits for an
  approval (`TvShellState.needsApproval`). Back or Right from the drawer returns focus to the
  element the screen had focused: a screen registers `FocusRestoreEffect { memory.restore() }`
  (else the content's saved child). `TvOverlay { … }` (`shell/TvOverlayHost.kt`) draws a
  screen's sheet or dialog full screen above the shell; `TvSideSheet` / `TvDialog` use it.
  Overlays close on Back through `Modifier.dismissOnBack` and trap D-pad focus with
  `Modifier.trapFocus()` — a focus group whose `onExit` cancels *every* exit swallows Back,
  because Compose turns Back into a focus exit. `KeyHint(emphasis = true)` (`KeyHints.ApprovalNeeded`)
  is drawn last, right-aligned, in the warning colors.
- **Opening conversations** (`conversations/TvSessions.kt`, one per process): every open from Home or
  Conversations is a user action and goes through `:feature:sessions`' `SessionsController` and
  `OpenSessionsRepository` exactly as in `:app-main` (history agent → `openSession`, may resume;
  pool row → `select`, reattaches; past Archie conversation → `requestResumeArchie`, with the §6.11
  conflict dialog; New conversation → `requestNewArchie`; New agent → harness picker from
  `GET /api/config/harnesses`, then `newAgentSession(provider)`), then pushes the
  `Conversation(localId, sdkId, archie)` route of the view it focused. The route itself only
  resolves (`ConversationRouting.resolve`): the Archie view, a view open here, a pool session to
  **reattach** to, or "not open" with an Open button (user action). A view that closes leaves the
  screen. Rename / Duplicate / Close / Delete use the controller's dialogs (rename with the Fire TV
  keyboard).
- **Home** (`home/`): hero = the Archie view (title, state, the end of the latest reply), Open now
  (pool agents; status, last reply line from the view or `GET …/preview`), Visuals (newest first;
  dot = changed this session), Memory (notes changed this session via `memory_changed`, then
  `MEMORY.md`, the folders' `INDEX.md` and the other root files — the tree has no dates), Recent
  conversations. Lists are sampled at 2 Hz. `FocusMemory` brings focus back to the card you left.
- **Conversations** (`conversations/`): the apps' grouping (`HistoryList`), search with the IME
  (OK on the field opens the keyboard; D-pad focus alone never does), preview of the focused row
  (`GET …/preview?max=12` + session config's working directory; `PreviewCache` keys an entry by the
  row's message count + state, an open row's for 30 s at most), Menu with the session
  actions and New agent / New Archie conversation.
- **Conversation** (`conversation/`): `:feature:chat`'s `ConversationViewModel` per view key
  (state sampled at ≤ 10 Hz; markdown stays incremental in the ViewModel's `MarkdownStore`). The
  transcript (`Transcript.kt`, `reverseLayout` like the apps) makes every item one tv-material
  surface — the phone renderers (markdown blocks, `:feature:toolcards` cards) draw inside,
  read-only, so nothing inside is a focus stop. `TranscriptModel` holds the pure rules: a block
  taller than the viewport scrolls 0.8 viewport per Up/Down before focus moves; Rewind/FF page and
  focus the block in the middle; focus on the newest block follows new blocks, never onto an
  inline card (FOCUS-1); inline cards (permission, agent approval, stall, error, gap) sit at the
  end with real buttons. Composer: Hold to talk (explains the mic, T4), Type (single-line field +
  Fire IME; its action key — "Avançar"/"Go" — sends; while a turn runs the message goes into it
  or waits, as in the apps), Live voice (Archie), Stop, More. Agents chip → side sheet (Stop; OK
  opens the subagent's transcript read-only). Long-press OK on a message: copy to composer, fork,
  rewind (confirmed).

## Focus and keys after the fix pass (review 2026-10-16)

- **Overlays own the keys** (B6). `TvOverlay(keys)` layers register with the `KeyRouter`: while the
  options sheet or a screen's dialog / sheet shows, Menu / Rewind / FF / long-press OK go to it (a
  side sheet closes on Menu, a dialog ignores them) and never to the screen behind; what it does not
  handle goes to the global defaults, so Play/Pause stays live voice over every overlay.
- **Focus comes back after an overlay** (B3). When the focused element of an overlay goes away,
  Compose parks focus on the nearest element of the screen behind (or nowhere, or the drawer). The
  shell sets `FocusSettling.active` from the moment an overlay shows until it has put focus back, so
  screens' trackers (`FocusMemory`, the transcript's last block, Visuals / Memory selection, the
  Settings panes) ignore those moves; 2 frames after the last overlay closed it calls the screen's
  `FocusRestoreEffect` (unless a text field is editing — Menu → Type). Restorers fall back to a
  sensible element: Home → hero Open, Conversations → first row / search, Visuals and Memory → the
  first item in view / search, Conversation → the block or composer button it left, else the newest
  block, Settings → the pane row it left (`LocalPaneFocus`: every pane row reports to a per-page
  `FocusMemory`) or the list row. Overlays keep focus inside (`rememberRefocusInside`): a Stop button
  that disappears or the Fire TV keyboard closing over a field hands focus to the overlay's first
  element (Rename: to Save), never to the drawer. A screen-root fallback refocuses Home,
  Conversations, Conversation, Visuals, Memory and Settings when focus is lost with no overlay open.
- **Approval cards** keep their buttons (disabled, still focusable) after an answer until the card
  leaves; **history arriving** places focus on the newest block only while the user has not pressed
  a D-pad / OK key, is not typing and has no overlay open (B5); a conversation that closes removes its
  own back-stack entry once (`TvNavigator.remove`, B7). A close / delete this TV confirmed is quiet
  only for that session's notice, once, within 8 s (`OwnCloseFilter`, B11).

## Settings (T5)

Spec 15 §3.5, mockup 14. One screen, two panes (`settings/SettingsScreen.kt`): the list on the left
(Connection, Remote microphone, Voice, Display, TV control, Server, About), the selected page's pane
on the right. Moving through the list shows each page; OK or Right enters the pane; Back in the pane
returns to the list row (a picker or a text field takes Back first); Back on the list leaves
Settings. `Settings` opens on the list, `SettingsPage(id)` with that page's pane focused. Focus
comes back where it was after a pushed screen (Connect) or the drawer (`FocusMemory` on the list +
"pane had focus", saveable). The key-hint bar follows the pane: "OK Change", and "Hold to test" on
Remote microphone.

Every row is one focus stop (`settings/SettingsParts.kt`): a **switch** (OK flips it), a **choice**
(OK opens a picker side sheet, current value checked and focused; a pick saves, Back closes, focus
returns to the row), an **action**, or an **info line** (OK shows "Edit this in Settings on the phone
or web app"). Status boxes and notes are focus stops without an action, so the D-pad can scroll a
pane to its end. Text is typed only for the server address (Connect) and the TV control device name
and token.

| Page | What it shows / edits |
|---|---|
| Connection | status, server (OK → the Connect screen: discovery, address, certificate trust), Reconnect, certificate state (pinned / system / not encrypted) |
| Remote microphone | T4's mic status, test meter + Play back, "Listen while other apps are open" (Q2, disabled), start/stop sounds |
| Voice | the server's live-voice provider / Google backend / model / voice / transcription language (same partial PUTs as the apps' Voice page, CFG-6 notice), and the TV's "End live voice after" (`TvVoicePrefs.liveIdleSeconds`, default 2 min, Never…10 min) |
| Display | text size (Normal / Large / Extra large), key hints, reduce motion (`TvPrefs`) |
| TV control | control-socket state (`graph.tvControl.client.state`: Ready, connecting, waiting, rejected with the server's reason, replaced) + Connect again, device name (IME; empty = the system name), server token for `TV_CONTROL_TOKEN` (IME, never shown, Remove), "Allow agents to open pages", "Show 'Opened by Archie' toasts". A new name or token re-sends the hello (socket closed and reopened). |
| Server | parity subset: text model, audio model, default harness + its model (no custom ids), Claude in Chrome, active working directory, MCP server switches (the all-enabled rule, CFG-4), accounts' sign-in state (read-only). Harness options, custom models, directories, sign-ins and keys: "Phone or web app" rows. |
| About | app version, build type, package, server + state (OK checks again), server version ("Not reported by the server": the backend has no version endpoint), licenses |

The pure part is `settings/TvSettingsLogic.kt` (choices and patches built from `:feature:settings`'
`VoiceLogic`, `ModelLogic`, `HarnessLogic`, `McpLogic`, `WorkingDirectoryLogic`; JVM-tested in
`TvSettingsLogicTest`). Server settings go through `graph.serverSettings` (`ServerSettingsModel`)
and `graph.accounts` (`AccountsModel`), reset on a server change; each open refetches (CFG-3). Panes
are state-driven (`settings/SettingsPanes.kt`); `settings/SettingsRoutes.kt` wires them to the graph.
Goldens: `SettingsGoldens` → `src/test/screenshots/settings-*.png`.

**Shell fixes that came with T5.** The options sheet and the certificate dialog use `trapFocus()` +
`dismissOnBack` (a trap that cancels every focus exit swallowed Back); the certificate dialog is a
`TvOverlay`, no longer a window `Dialog`. When the options sheet closes, focus goes back to the
element the screen had (its `FocusRestoreEffect`, else the content's focus restorer) unless the option
opened a dialog or another screen. The status cluster keeps 64 dp clear of the right edge
(`SystemMicIndicatorClearance`): Fire OS draws its mic-in-use disc at x ≈ 878–910 dp, y ≈ 37–70 dp
while the app records, which is always while it is open. Home's Visuals cards use T3's `VisualKind`
band and label, like the Visuals grid.

## Release build and performance (T5)

```bash
shared/scripts/heavy.sh --gradle apps/android :app-tv:assembleRelease
adb -s 192.168.0.15:5555 install -r apps/android/app-tv/build/outputs/apk/release/app-tv-release.apk
adb -s 192.168.0.15:5555 shell cmd package compile -f -m speed-profile com.assistant.archie.tv   # tv_setup.sh does this
```

- **R8** is on for release (`configureArchieApp`), signed with the `tv.*` key (the same key as debug
  builds, so a release installs over a debug build in place). No app-level keep rules were needed:
  the consumer rules of `:core:voice` (`org.webrtc.**`, JNI natives, `DefaultVoiceCore`) and
  `:core:wakeword` (JNA, Vosk) and kotlinx-serialization's bundled rules cover the reflection; the
  release build was driven on the TV (Home, a long conversation, Settings) without a crash.
- **Baseline profile**: `app-tv/src/main/baseline-prof.txt`, hand-written wildcard rules (no
  emulator or Macrobenchmark device on this toolchain): Archie's own packages
  (`com/assistant/archie/tv`, `com/assistant/core`, `com/assistant/archie/feature`) plus coroutines,
  kotlinx-serialization, collections-immutable, OkHttp/Okio, commonmark, Navigation 3 and
  tv-material. AGP expands the wildcards, R8 rewrites them, the APK carries
  `assets/dexopt/baseline.prof`; androidx.profileinstaller hands it to ART on first launch (or
  `am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE com.assistant.archie.tv/androidx.profileinstaller.ProfileInstallReceiver`),
  then `compile -m speed-profile` compiles it.
- The window background is dropped after the first frame (the shell's Surface is opaque): one
  full-screen fill less per frame (no measurable change on Home's GPU time, kept as harmless).

**Measured on the AFTKM, 2026-10-16** (release, server = Jetson; `am start -W` after `force-stop`,
`dumpsys meminfo`, `dumpsys gfxinfo` around scripted `input keyevent` runs at 4 keys/s):

| | release, `verify` only | release, `speed-profile` |
|---|---|---|
| Cold start to Home (`TotalTime`, 3 runs) | 1.33–1.53 s | 0.67–0.74 s |
| PSS, Archie conversation open (Home after it) | 112 MB (135) | 121–126 MB (140) |
| Reading a long conversation (35 Up/Down + RW/FF) | p90 17 ms, p99 48 ms | p90 13 ms, p99 21–27 ms |
| Home rows (26 D-pad steps) | p90 23 ms, p99 44 ms | p90 20 ms, p99 31 ms; ≈ 1 frame past its deadline per step |

(A debug build cold-starts in ≈ 7.5 s.) `gfxinfo`'s "Janky frames" percentage counts every frame
after an `adb shell input` key as "high input latency" (the injected event's timestamp), so use
"Frame deadline missed" and the percentiles, or `framestats`. On Home the GPU needs ≈ 12 ms a frame
(p90 15.5 ms) against 5 ms in a conversation: Home's remaining cost is drawing, not composition.


## Fixes after launch (2026-10-17)

- **History of a session opened mid first turn** (shared `:core:conversation` / `:core:data`, so the
  phone app gets it too): a pool view opened before its sdk id was known showed only the live tail.
  The cold open now asks `pool/live` for the id, and an attached view reloads once it learns it
  (spec 12 H-2a); SEQ-6 overflow reloads look the id up inside the reload (web parity) and a
  successful reload clears the gap flag. Tests: `ConnectionManagerTest` (`attachedView…`, `seq6_…`,
  `aSuccessfulReloadClearsTheGap`).
- **Rewind paging looped**: on TV devices Compose's default bring-into-view pins the focused item to a
  pivot, so focusing the middle block after a page scrolled the list back. The transcript and the
  memory reader use `theme/TvReadingScroll.kt` (`ReadingScroll`): no scroll when the focused block is
  visible, else the shortest move. Tests: `ReadingScrollTest`. Verified on the AFTKM: Rewind pages
  from the newest message to the first prompt of a 98-message session.
