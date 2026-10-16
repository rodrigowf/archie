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
  voice/   TvVoiceSeams       LiveVoiceToggle, VoiceChip (T4 adds RemoteMicSource, router, overlay)
  control/                    (T3: TvControlClient, CommandHandler, StateReporter, LauncherPing)
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
| T4 Voice | `voice/`, `settings/` Remote mic + Voice pages, first-run mic step | `TvAppGraph.voiceHostFactory`, `.voice`, `.liveVoice` |
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

## Status (T0, 2026-10-16)

Shell, theme, graph, first run and placeholders are in. The placeholder screens already show real
data: Home rows (Archie, open agents, visuals, memory, recent conversations), the Conversations list
grouped by day, the Visuals grid, the Memory tree with a text preview, memory notes as focusable
paragraphs, Settings → Connection / Display / About. Conversation and Viewer are stubs (T2, T3).
Opening a conversation never starts or resumes a session yet (spec 12 OPEN-*; T1/T2 decide).
