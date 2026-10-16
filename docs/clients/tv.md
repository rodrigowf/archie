---
name: tv
category: archie/clients
tags: [android-tv, fire-tv, tv-material, compose, navigation3, app-tv, d-pad, leanback, gradle]
created: 2026-10-16
modified: 2026-10-16
summary: apps/android/app-tv — Archie TV (com.assistant.archie.tv), the 10-foot client for Fire TV / Android TV; module, build/install, shell architecture and the extension points the screen work packages fill.
source: curated (spec 15 WP T0, 2026-10-16)
references:
  - ../specs/15-tv-app.md
  - ../specs/14-android-architecture.md
  - ../specs/12-client-protocol.md
  - android.md
  - ../devices/devices.md
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
  (also Play / Pause), Rewind, Fast-forward and long-press OK go to the options sheet when it is
  open, then to the top screen's handler, then to the global default — Menu and long-press OK open
  the options sheet, Play/Pause calls `TvAppGraph.liveVoice` (T4). A handled key also swallows its
  key-up; an unhandled one reaches Compose unchanged (Rewind/FF can reach a WebView). Long-press OK
  on a `FocusCard` / `TvButton` is delivered by tv-material (`onLongClick`), which routes it through
  the same chain and skips the click.
- **First run** (spec 15 §3.6): until `TvPrefs.setupDone`, the stack root is `Connect(firstRun =
  true)`: LAN discovery (`ConnectionRepository.scan` → `ServerDiscovery`), manual address with the
  IME (Go connects), the TOFU certificate trust dialog for self-signed servers, then Continue →
  Home. Settings → Connection pushes the same screen. The logic is `:feature:settings`'
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
| T5 Settings, perf | `settings/` | `SettingsRoute`, `SettingsPageRoute(key: SettingsPage, …)` |

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
  exactly one element focused). Rows and lists use `Modifier.focusRestorer()`. Text and icons inside
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
moves it. Close **4409** (replaced) never reconnects from that socket; **4401** stops and logs
`TV control rejected: <reason>` (`TvControlClient.state` = `Rejected`). It uses the app's
`HttpStack` (same OkHttp client, TOFU pins, ping interval). Logcat tag: `ArchieTv/control`.

- Hello: `device_id` = a random UUID kept in `TvPrefs.controlDeviceId`; `device_name` =
  `TvPrefs.controlDeviceName`, else `Settings.Global.DEVICE_NAME`, else "Fire TV" / the model;
  `token` = `TvPrefs.controlToken` when set; capabilities = every command below.
- `TvPrefs` (Settings → TV control UI comes with T5): `allowAgentsToOpen` (default on; off refuses
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
Rewind/FF scroll 0.8 of the page; Back = page history, then close. Pushed pages show "Opened by
Archie · title" and ping KEEP_AWAKE every 60 s. Live reload (VZ-6) keeps the scroll position.

**Visuals** (`visuals/`, mockup 10): own header (count, search, status), 4-column cards with a kind
band (`VisualKind`, read from path/title: the backend has no kind), a dot when the page changed since
it was last opened (`VisualsSeen`, per process), Menu → Open / Rename (IME) / Copy link (address +
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
  Fire OS shows its microphone indicator top-right while the app is open (the recorder is armed).
- **Routing** (`TvVoiceRouter`, decided when the burst starts): live voice owned here → the call;
  an agent conversation on screen → dictation (`POST /api/transcribe` → chip with Send focused /
  Edit with the IME / Cancel; Send = `ConversationRepository.send`); a mic test page → the test
  meter; anything else → a voice message to Archie (`send_audio` WAV on the orchestrator socket, as
  the phone's push-to-talk; with no Archie conversation open it starts a new one first).
- **Live voice** (Play/Pause, hero button): `AndroidVoiceHost.create` with `HostConfig.tv` — no wake
  word, the call on the media stream. The TV never opens a second recorder on the remote:
  `LiveMicBridge` turns its bursts into a continuous stream (voice while held, digital silence
  otherwise), fed to the WS relay (Qwen/Gemini) as its `MicSource` and to WebRTC (OpenAI) through
  `RtcMicFeed` with the ADM recording VOICE_COMMUNICATION only as a clock (spec 15 §4.2 has the
  spike). The provider's VAD ends each turn on the silence. Ends on Play/Pause, Back while the
  panel shows, or 120 s with nobody talking (`TvVoicePrefs.liveIdleSeconds`).
- **Status chip**: Idle · Listening (button held) · Thinking (sending / transcribing, live thinking)
  · Speaking · Live · On phone.
- **Settings → Remote microphone** (`settings/RemoteMicPage.kt`): status (Ready / Alexa also listens
  / no permission / busy), the test meter with Play back, "Listen while other apps are open" (Q2,
  not built: disabled), start / stop sounds. First run step 2 (`firstrun/MicCheckStep.kt`) is the
  same check after Connect.

Shared-module additions (each default keeps the phones unchanged, each with a test):
`:core:audio` `FixedMicSourcePolicy`, `DefaultAudioCore(micSource)`; `:core:voice`
`WebRtcDeps.micSourcePolicy / micFeed / mediaPlayback`, `RtcAudioOptions.mediaPlayback`,
`RtcMicFeed`; `:core:voice-host` `HostConfig.wakeWord / audio`, `HostConfig.tv`, `HostAudio`,
`NotificationSpec.tv`, `MicPushToTalkRecorder(audioCore)`; the runtime never builds a wake engine
when `wakeWord = false`.

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
paragraphs, Settings → Connection / Display / About. Conversation is a stub (T2). T3 (Viewer, Visuals, Memory, control channel) is described above.
Opening a conversation never starts or resumes a session yet (spec 12 OPEN-*; T1/T2 decide).
