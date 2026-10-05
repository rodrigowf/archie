# 03 — Android app (`android/`, `com.assistant.peripheral`)

Chapter 3 of the frontend rebuild spec. It covers everything in the native Android app **except** the low-level voice, audio, service and wake-word engines (chapter 4 covers those). Where the UI touches those engines (VoiceController/VoiceManager surface, button states, settings), the touch points are documented here.

**Citation convention.** Paths are relative to `android/app/src/main/java/com/assistant/peripheral/` unless they start with `android/`, `api/`, `frontend/`, `orchestrator/` or `manager/` (repo root). `file:line` refers to the `frontend-refactory` branch at commit `e871d05`.

**Build facts** (`android/app/build.gradle.kts`): `minSdk 21`, `targetSdk 34`, `compileSdk 34`, versionName `1.0.9` / versionCode 10, Kotlin 1.9.20, AGP 8.2.0, Compose BOM `2023.10.01` (Material3 1.1.x), compose-compiler 1.5.4, Java 8 target. Dependencies that matter to the UI: navigation-compose 2.7.5, lifecycle-viewmodel-compose, OkHttp 4.12 (REST + WS), `org.json`, DataStore-preferences 1.0, LocalBroadcastManager, `stream-webrtc-android` 1.1.1, `vosk-android` 0.3.47, plus an NDK/CMake build for a Lollipop-only Vosk shim (`build.gradle.kts:24-41`). Release builds use R8 with `-keep class com.assistant.peripheral.data.** { *; }` (`android/app/proguard-rules.pro`).

**Architecture in one paragraph.** There is one Activity (`MainActivity`) hosting a Compose `NavHost` with three routes. All state lives in an **Activity-scoped** `AssistantViewModel` (`viewmodel/AssistantViewModel.kt`), which is a thin coordinator over five controllers: `SettingsRepository`, `OrchestratorConnectionController`, `ChatController`, `VoiceController` and `SystemConfigController` (see `context/memory/assistant/architecture/android_viewmodel.md`). A separate foreground `AssistantService` hosts only the wake-word detector. It talks to the Activity through `LocalBroadcastManager` intents. **The chat and voice sessions die with the Activity's ViewModel; the service does not own them.** The rebuild should revisit this split (§6, §7).

---

## 1. Screens and navigation

### 1.1 App shell (`MainActivity.kt`)

| Element | Location | Behaviour |
|---|---|---|
| Routes | `MainActivity.kt:242-246` | `chat` (icon Chat, start destination), `sessions` (icon History, title "History"), `settings` (icon Settings). |
| Bottom navigation | `MainActivity.kt:721-765` | Hand-rolled row (80dp tall) of icon-only 64×48dp rounded "pills". It is not an M3 `NavigationBar` and has no labels; `contentDescription` is the only text. Each tap does `navigate(route){popUpTo(start){saveState}; launchSingleTop; restoreState}`. |
| Bottom stack above the nav | `MainActivity.kt:606-719` | Only on the Chat route **and** while voice is not active: TerminationBanner → StatusBar → "Listening…" banner → ChatInputBar. While voice is active (any route): an optional reconnect banner, then VoiceControls. The input bar disappears entirely during a voice session. |
| Theme | `MainActivity.kt:154-161` | `AssistantTheme(themeMode = settings.themeMode)`. |
| Toasts | `MainActivity.kt:281-292` | Two `SharedFlow`s: `toastMessage` (chat and voice controllers) and `shareToast` (upload/share), both shown as `Toast.LENGTH_LONG`. |
| File picker | `MainActivity.kt:296-300` | `GetContent("*/*")` → `viewModel.shareFile(uri)`. |
| Share intents | `MainActivity.kt:86-108, 146-148, 165-169, 304-314` | `ACTION_SEND` text goes to `shareText`; `EXTRA_STREAM` goes to `shareFile`. The payload is a `StateFlow` so a cold-launch share isn't lost. |
| Wake-word callbacks | `MainActivity.kt:110-129, 322-389` | The receiver for `ACTION_TALK_WORD_DETECTED`, `ACTION_WAKE_WORD_DETECTED`, `ACTION_WAKE_CONFIRMING`, `ACTION_WAKE_CONFIRM_FAILED` and `ACTION_TALK_MESSAGE_CAPTURED` (registered only while the Activity exists, `:137-144`). Talk word: beep, `markRecordingStarting`, then navigate to Chat. Wake word: beep, `markVoiceConnecting`, navigate to Chat, then `startVoiceSession`. Confirming: `markWakeConfirming` and navigate. Confirm failed: `clearRecording`. Captured: `sendCapturedVoiceMessage(b64)`. |
| Launch effects | `MainActivity.kt:392-404` | If `autoConnect` is on, `connect()`; otherwise, when the URL is the default, `scanForServers()`. In both cases it then calls `AssistantService.start`. |
| Wake-config push | `MainActivity.kt:415-432` | Re-sends `AssistantService.updateWakeWord(enabled, talkWord, wakeWord, wakeWordMicGain, talkSilenceSensitivity, serverUrl)` whenever any of those keys change. The gain **must** be in the key list. Commit `d6181b1` fixed the gain being silently clobbered to 1.0. |
| Extra scan | `MainActivity.kt:436-442` | When `autoConnect` is on and the URL is the default, it **also** scans, so a cold start runs both connect and scan. |
| Auto-navigation | `MainActivity.kt:445-476` | Calls `refreshSessions()` on Connected. `noActiveOrchestrator` navigates to History. `orchestratorOpenedToChat` navigates to Chat. |
| History entry refresh | `MainActivity.kt:523` | `forceRefreshSessions()` each time History is entered. |
| `onResume` | `MainActivity.kt:189-195` → `AssistantViewModel.kt:226-240` | `reconnectIfNeeded` (only if Disconnected/Error), then `resyncOnResume` (re-sends `start` on every connected bucket so the backend replays missed events), then `forceRefreshSessions`. |
| `onNewIntent` | `MainActivity.kt:165-187` | Re-parses shares. On a wake trigger, turns the screen on (`setTurnScreenOn`/`setShowWhenLocked` on O_MR1+, window flags on older versions). |
| Permissions | `MainActivity.kt:51-59, 202-239` | `RECORD_AUDIO`, plus `POST_NOTIFICATIONS` (33+) and `BLUETOOTH_CONNECT` (31+). The result callback is a no-op (`:54-58`), so a denial is never surfaced to the user. |

### 1.2 Chat screen (`ui/screens/ChatScreen.kt`)

There is **no top app bar**. The Chat screen shows no session title and nothing that says whether you are in the orchestrator or an agent (Claude Code) session. The only hint is that the voice, upload and record buttons appear only for the orchestrator (`ChatScreen.kt:1267, 1320`).

| Feature | Location | Notes |
|---|---|---|
| Message list | `ChatScreen.kt:165-213` | `LazyColumn`, 8dp padding, 14dp spacing, keyed by `ChatMessage.id`. |
| Load-older indicator | `:172-204` | Shows a spinner while loading, otherwise the text "↑ Scroll up for older messages". |
| Auto-load older | `:154-158` | Fires when `firstVisibleItemIndex <= 1`, but only after the initial scroll. A guard keyed on the first message id (`:84-102`) prevents the infinite load-more loop (commit `5c029d6`). |
| Stick-to-bottom | `:104-149` | Keyed on `messages.size` and a cheap "tail signal" (block count × 1,000,003 + tail text length). Uses `scrollToItem(last, Int.MAX_VALUE)`, **not** the animated version, because of the A300M main-thread starvation fix (commits `06fe06f`, `31c2fbf`). |
| Scroll-to-bottom FAB | `:216-233` | `SmallFloatingActionButton` shown when not at the bottom; its scroll is animated. |
| Per-message ⋮ menu | `:244-288, 330-418` | "Rewind conversation to here" and "Fork conversation from here". It appears on **every** message, including SYSTEM error bubbles. The confirmation dialog lives in MainActivity (`:800-831`). |
| User bubble | `:293-295, 338-383` | Hard-coded dark colours (`0xFF1B2338` fill, `0xFF303852` border, `0xFFEEEEF2` text) regardless of theme. Max width 340dp. |
| Fold long user text | `:298-299, 457-514` | More than 25 lines collapses to 150dp with a fade and a "Show all (N lines)" / "Show less" toggle. |
| System message bubble | `:338-383` | `errorContainer` at alpha 0.6. Used for every error and info system message. |
| Assistant message | `:384-414` | Full-width prose with no bubble. Blocks are drawn in order with 4dp gaps. A 2dp `LinearProgressIndicator` sits under the message while `isStreaming`. |
| Text block | `:424-434` | User text is plain. Assistant text uses `MarkdownText`. |
| Thinking block | `:517-583` | Left border, "Thinking"/"Thought" header, collapsible, italic. |
| Generic tool block | `:603-708, 717-791` | Left border coloured by tool category, icon, one-line summary (`formatToolSummary`, `:1512`). Status is a spinner while executing, then a done/error pill. Tap ▶ to expand Input (key: value, values truncated at 400 chars) and Output (`(running...)`, `(no output)` or `(empty)`). |
| Specialized tools | `:586-600` | TodoWrite (`:959`, always expanded as a checklist), Task (`:1016`, always expanded: agent, prompt, output) and Bash (`:1087`, description header, 5-line command preview, output when expanded). |
| Tool name normalization | `:1405-1425` | Maps Qwen tool names to Claude names. Categories (`:1426-1465`) hard-code orchestrator tool names. Newer orchestrator tools fall into the SYSTEM category with their raw name: `respond_to_agent_permission`, `run_script`, `end_voice_session`, `get/update_assistant_config` and `listen_recording` (the registered list is in `orchestrator/tools/*.py`). |
| Compact divider | `:1184-1235` | "⟳ Context compacted" between dividers, expandable when there is a summary. Orchestrator compactions carry no summary (see §3.4). |

### 1.3 Chat input bar (`ChatScreen.kt:1238-1385`)

| Control | Visible when | Enabled when | Action |
|---|---|---|---|
| VoiceButton (48dp) | orchestrator session | not `remoteActive` | Starts the realtime session, or stops it if active (`VoiceButton.kt:139-146`). |
| Attach (📎) | orchestrator session | orchestrator WS connected | Opens the file picker; the file is uploaded and its link injected (§3.3). |
| Text field | always | connected and not recording | `OutlinedTextField`, max 4 lines, `ImeAction.Send`. The IME Send key is **ignored while streaming** (`:1313`). |
| Record (mic/stop) | orchestrator session | connected and not streaming | Push-to-talk WAV via `AudioRecorder`. Stopping sends `send_audio` (`VoiceController.kt:626-661`). |
| Send / Stop | always | Send: connected, text not blank, not recording | Stop replaces Send while the status is streaming, tool_use, thinking or processing, and sends `interrupt`. |

"Connected" always means the **orchestrator** socket (`MainActivity.kt:678`, `connectionState` = orchestrator only, `OrchestratorConnectionController.kt:93`). The agent socket's state is never shown, even when an agent session is on screen (§8).

### 1.4 Status strips and voice controls

- **StatusBar** (`ui/components/StatusBar.kt:24-75`): an 8dp dot plus a label. Labels are Error: <msg>, Disconnected, Connecting..., Generating..., Using tools..., Thinking..., Processing..., or Ready. Other status values the backend sends, such as `interrupted`, `retrying` (`api/pool.py:1122`), `error` and `connecting`, all display as "Ready". It shows no cost, turns or context use (the web shows these).
- **"Listening…" banner** (`MainActivity.kt:639-664`): `secondaryContainer` with a spinner, shown while the Whisper confirmation is in flight.
- **TerminationBanner** (`ui/screens/TerminationBanner.kt:29-88`): error container plus a headline that mirrors the web wording (`:80-87`) and a "Continue" button. Continue calls `loadSession(sdkSessionId, false, null)` (`MainActivity.kt:616-631`). **This always reopens on the AGENT endpoint**, even for an orchestrator termination.
- **Voice reconnect banner** (`MainActivity.kt:690-709`): `tertiaryContainer` with text from `VoiceController.voiceReconnectBanner`: "Pausing in ~Ns to reconnect…", "Reconnecting shortly…" or "Pausing for a second to reconnect…".
- **VoiceControls** (`ui/components/VoiceButton.kt:211-297`): a status dot (red/yellow/green semaphore, pulsing, `:300-350`) with a label, a mute toggle and a red end-call button. Labels are Connecting..., Preparing conversation... (Summarizing), Connected or Listening..., Speaking..., Thinking..., Using tools... and Ending.... After 3s of continuous listening (`LISTENING_REVEAL_MS`, `:38`) the label becomes "Listening Ns".
- **VoiceButton states** (`VoiceButton.kt:45-198`):

| VoiceState | Background / tint | Icon | Animation |
|---|---|---|---|
| Off | primaryContainer | Mic | none |
| Connecting / Summarizing / Ending | tertiaryContainer | spinner | none |
| Active | green @ 0.2 | Mic | none |
| Listening | green @ 0.3, green border | Mic | 1.0→1.15 scale pulse |
| Speaking | blue `0xFF5888CC`, blue border | VolumeUp | pulse |
| Thinking | amber `0xFFC4923A` | Psychology | none |
| ToolUse | purple `0xFF8B7ACC` | Build | none |
| Error(msg) | errorContainer | MicOff | none. **The message is never shown.** |
| `remoteActive` | alpha 0.45, click disabled | PhonelinkRing ("Voice active on another device") | none |

  The D-pad focus border (`:115-138`) is a Fire TV leftover. `if/else` is used instead of an early `return@Box`, which is load-bearing (commit `afe77a4`: Compose `Stack.pop` crash).

### 1.5 History (`ui/screens/SessionsScreen.kt`)

- **Top bar** titled "Conversations" (the nav label says "History") with a Refresh action (`:49-58`). **FAB "+"** calls `requestNewOrchestratorSession()`, which goes through the conflict mediator. **There is no way to create a new agent (Claude Code) session on Android** (`:59-66`, `MainActivity.kt:547-552`).
- **States:** a spinner while loading with no data (`:73-77`); an empty state with "No conversations yet" and a "New Conversation" button (`:78-111`); and the list (`:114-130`) with a top `LinearProgressIndicator` during a refresh (`:134-140`).
- **SessionItem** (`:146-469`):
  - A round type icon: SmartToy for an orchestrator, Chat for an agent.
  - A pulsing green dot when the session is live in the pool.
  - Badges: "open", "orch", and a one-letter provider circle (C or Q).
  - Message count and relative time.
  - The card background distinguishes selected, open and idle sessions.
  - The ⋮ menu offers Rename (inline edit field with ✓ and ✗), Duplicate, Close (only when open) and Delete (which opens a confirmation dialog, `MainActivity.kt:772-796`).
- **Tap:**
  - Orchestrator: `requestLoadOrchestratorSession(sessionId, localId)` (conflict-mediated). Navigation happens only via the `orchestratorOpenedToChat` emission.
  - Agent: `loadSession(id, false, localId)`, then navigate immediately (`MainActivity.kt:529-546`).
- **Relative time** (`:474-499`) parses the first 19 characters of the ISO string as **device-local** time. The backend sends UTC with an offset (verified: `"last_activity":"2026-08-28T21:49:03.550000+00:00"` from `GET /api/sessions` on the Jetson), so every age is skewed by the UTC offset (+3h in Rio). Same bug in `ApiClient.parseTimestamp` (`network/ApiClient.kt:780-788`).
- **Performance:** every row creates its own `rememberInfiniteTransition` even when the session isn't open (`:161-170`), which matters on the A300M.

### 1.6 Settings (`ui/screens/SettingsScreen.kt`, `ui/screens/SystemSettingsTab.kt`)

A `TopAppBar("Settings")` with a `TabRow`: **App** and **System** (`SettingsScreen.kt:69-136`). The System tab loads its config lazily, on the first tap and only if `config == null` (`:84-89`).

**App tab** (`SettingsScreen.kt:141-690`). Four cards: Server Connection, Audio, Appearance, About.

- **Server Connection** (`:183-284`):
  - A unified list of saved and discovered servers (`ServersSection`, `:793-889`). Tapping a saved row selects its URL. Tapping a discovered row adopts its URL. Saved rows have Edit and Delete. Discovered rows have Save (bookmark).
  - "Scan" and "Add" buttons. `ServerEditorDialog` (`:986-1030`) takes a label and a WebSocket URL.
  - `ConnectionStatusPill` and a Connect/Disconnect button (`:224-257`).
  - An Auto-connect switch.
  - **Selecting a server only changes the URL.** The settings observer then *disconnects* (`AssistantViewModel.kt:197-201`) and **nothing reconnects**. The user must tap Connect, or background and resume the app (`onResume` → `reconnectIfNeeded`). The `onUpdateServerUrl` callback is passed in (`SettingsScreen.kt:38`) but never used: there is no free-form URL field outside the dialog.
- **Audio** (`:287-566`):
  - Sliders built on `LevelSlider` (`:718-790`, discrete steps, a reset icon when off-default, commits on release): Mic, Speaker, Echo Ducking (0–10% in 0.5% steps).
  - An Audio Output selector with five `FilledIconToggleButton`s: Auto, Speaker, Earpiece, BT and Wired. BT and Wired are disabled when unavailable. Availability is computed per recomposition, not live (`MainActivity.kt:580-585`).
  - A Wake Word Detection switch. When it is on: a talk-phrase field and a wake-phrase field, each with a separate Save button that appears only when the text is edited (`:450-493`); a Wake Word Sensitivity slider; a Talk Auto-Stop Sensitivity slider; and an explanatory caption.
  - A Recents Button Trigger switch.
- **Appearance** (`:569-642`): theme radio group (System default, Light, Dark).
- **About** (`:645-688`): the hard-coded text **"Version 1.0.0"** (`:673`), while the actual versionName is 1.0.9.

**System tab** (`SystemSettingsTab.kt:42-155`) mirrors the web `ConfigPage`.

- **Gating:** a "Connect to a server…" card when not connected, a spinner while loading, and "Couldn't load configuration" with a Reload button on failure.
- **Banners:** Saving…, Saved (2s flash), an error with Retry (`:60-101`), and a Gemini model auto-correct notice with Dismiss (`:103-128`).
- **Orchestrator card** (`:191-400`):
  - Text mode: provider and model dropdowns. Models are flagged 🎤 (audio) and 👁 (vision).
  - Voice mode: provider, a Google backend selector (Vertex AI or AI Studio, `:278-289`), model, voice, and transcription language.
  - A "Voice recording" switch.
  - "Voice tuning" sliders: VAD threshold 0.15–0.50, min silence 800–5000 ms, mic gain 0.5–2.0×. The UI itself labels mic gain "reserved — wiring lands in a later increment" (`:397`).
- **Session provider card** (`:447-505`): a provider dropdown. When the provider is Qwen, a harness-model dropdown with a "CLI default" option and capability badges.
- **Working directory card** (`:512-557`): a selector only. The card says "Manage the full list … from the web frontend". Shows the path and SSH user@host.
- **Session flags card** (`:564-586`): a Chrome extension switch (the `--chrome` flag).
- **MCP servers card** (`:593-635`): one switch per server from `.claude.json`. See the toggle bug in §8.
- **`DropdownField`** (`:675-712`): `ExposedDropdownMenuBox` with a read-only `OutlinedTextField`.

### 1.7 Dialogs and transient UI (all in `MainActivity.kt`)

| Dialog | Lines | Buttons |
|---|---|---|
| Delete conversation | `:772-796` | Delete / Cancel. Text: "moved to trash … recover manually from context/trash/". |
| Rewind or fork | `:800-831` | Rewind (closes the session; reopen from History) or Fork / Cancel. |
| Orchestrator conflict | `:840-892` | "Open the running one" / "Close and switch" or "Close and start fresh" / Cancel. Three buttons are crammed into the AlertDialog's confirm and dismiss slots. |
| Server editor | `SettingsScreen.kt:986-1030` | Add or Save / Cancel. |
| Toast strings | `chat/ChatController.kt:1370, 1441, 1465-1468, 1489, 1505, 1517-1520`; `voice/VoiceController.kt:525, 530`; `AssistantViewModel.kt:280, 292, 297, 313` | "Couldn't close the active session.", "Delete failed.", "Conversation duplicated./forked./rewound.", "Rewind failed — session may still be open.", "Voice error: …", router fallbacks, "Not connected — can't upload yet.", "Couldn't read the shared file.", "Upload failed.", "Shared <file>." |

### 1.8 Entry points outside the main UI

- **`VoiceShortcutActivity`** (`VoiceShortcutActivity.kt:22-57`): a transparent trampoline for `ACTION_ASSIST` (long-press home). It turns the screen on, brings MainActivity to the front with `EXTRA_WAKE_WORD_TRIGGERED`, broadcasts `ACTION_WAKE_WORD_DETECTED` and finishes. The manifest is at `android/app/src/main/AndroidManifest.xml:63-76`.
- **`AssistantVoiceInteractionService`** (`service/AssistantVoiceInteractionService.kt:34-40`): a stub VoiceInteractionService. Its `showSession` launches the trampoline, because on Lollipop the assist gesture goes to the registered VIS.
- **`ButtonAccessibilityService`** (`service/ButtonAccessibilityService.kt:32-60`): a long press of 600ms or more on `KEYCODE_APP_SWITCH` with the pref `button_trigger_enabled` set calls `bringToForeground` and the wake broadcast, then consumes the event. The user must enable it in system Accessibility settings.
- **Raw recents monitor** (`service/AssistantService.kt:617-675`, started unconditionally in `onCreate` at `:424`): reads `/dev/input/event2` (the A300M `sec_touchkey`) for `KEY_APPSWITCH`. It **does not check the `enableButtonTrigger` toggle**. On a non-root device it most likely fails with EACCES and logs a warning (the catch is at `:668-670`). Added in `0d01543` and never revisited.
- **Foreground notification** (`service/AssistantService.kt:683-727`): channel "Assistant Service" at IMPORTANCE_LOW; title "Assistant Active", text "Listening for commands", or "Wake word stalled — mic held by another app" when the mic is unavailable. Small icon `android.R.drawable.ic_btn_speak_now`. Tapping it opens MainActivity. It has no actions (no stop, no mute).
- **Share target** (`AndroidManifest.xml:47-59`): `SEND` with `text/plain` and `*/*`. Single item only; there is no `SEND_MULTIPLE`.

---

## 2. Settings inventory

### 2.1 App settings: `AppSettings` (`data/Models.kt:417-435`), persisted by `settings/SettingsRepository.kt`

All are stored in the Preferences DataStore file `settings` (`SettingsRepository.kt:352`). The key names are wire format and renaming them requires a migration (`:325-343`). **None of the app settings are sent to the backend**, except that `serverUrl` *is* the backend address.

| Setting | DataStore key | Type, default (range) | UI | Consumed by |
|---|---|---|---|---|
| `serverUrl` | `server_url` | String `"ws://192.168.0.200:80"` (a hard-coded Jetson LAN IP) | server list, editor | WS base (`WebSocketManager.kt:154-170`), REST base (`ApiClient.kt:41-53`), `AssistantService` (`server_url` extra, for the wake-word Whisper key fetch), "is default?" checks for auto-scan (`MainActivity.kt:393-401, 437-441`; `OrchestratorConnectionController.kt:326-331`) |
| `savedServers` | `saved_servers` | List encoded as `label\turl\|…` (`SettingsRepository.kt:313-323`). Breaks if a label contains a tab or pipe. | ServersSection | Settings only |
| `autoConnect` | `auto_connect` | Bool `true` | switch | `MainActivity.kt:395-396, 439` |
| `enableWakeWord` | `enable_wake_word` | Bool `true` | switch | `AssistantService.updateWakeWord` |
| `talkWord` | `turn_talk_word` | String `"my friend"`, comma-separated | text field with explicit Save | service (turn-based capture) |
| `wakeWord` | `realtime_wake_word` | String `"wake up"`, comma-separated | text field with explicit Save | service (realtime trigger) |
| `themeMode` | `theme_mode` | enum SYSTEM / LIGHT / DARK, default SYSTEM | radio | `AssistantTheme` |
| `micGainLevel` | `mic_gain_level` | Float 1.0 (0–1.5, 10% steps) | "Mic" slider | `VoiceManager.setMicGain` (`VoiceController.kt:246, 253`) |
| `wakeWordMicGainLevel` | `wake_word_mic_gain_level` | Float 1.0 (0–1.5) | "Wake Word Sensitivity" | service. Scales the RMS gate. |
| `talkSilenceSensitivity` | `talk_silence_sensitivity` | Float 2.0 (1.0–4.0, 0.5 steps) | "Talk Auto-Stop Sensitivity" | service. End-of-utterance VAD multiplier. |
| `speakerVolumeLevel` | `speaker_volume_level` | Float 1.0 (0–1.5) | "Speaker" slider | **Side effect only:** sets the *system* `STREAM_MUSIC` volume (`SettingsRepository.kt:252-259`). The stored value is never read back, so the slider drifts from the real volume after a hardware-key change. Values above 100% clamp to the maximum. |
| `echoDuckingGain` | `echo_ducking_gain` | Float 0.05 (0–1.0, UI limited to 0–10%) | "Echo Ducking" | `VoiceManager.setEchoDuckingGain` |
| `audioOutput` | `audio_output` | enum AUTO / LOUDSPEAKER / EARPIECE / BLUETOOTH / WIRED, default AUTO | five toggle buttons | `VoiceManager.setAudioOutput` |
| `enableButtonTrigger` | `enable_button_trigger` | Bool `false` | "Recents Button Trigger" | Mirrored to SharedPreferences `assistant_service_prefs/button_trigger_enabled` (`SettingsRepository.kt:267-271`, and again on every emission at `AssistantViewModel.kt:188-189`). Read by `ButtonAccessibilityService.kt:45-47`. |

### 2.2 Non-user persisted state (same DataStore)

| Key | Content | Lifecycle |
|---|---|---|
| `orchestrator_local_id` | the local_id of the orchestrator pool entry | Restored on the first settings emission (`AssistantViewModel.kt:181-186`). Rewritten by the connect probe, recovery, `newSession` and `reconcileOrchestrator`. Cleared on a URL change or on close. |
| `ws_resume_checkpoint:<localId>` | `"<streamId>\|<seq>"` | Written on **every** seq-stamped event (`ChatController.kt:496-511`). Each write is a read plus a full-file DataStore edit (`SettingsRepository.kt:158-171`). One key per local_id. Agent sessions mint a **new random local_id on every open** (`ChatController.kt:1106`), so keys accumulate forever and are only removed on overflow or termination. The preferences file grows without bound, and the whole file is rewritten on every streamed token of an agent turn. |
| SharedPreferences `assistant_service_prefs` | `button_trigger_enabled` (plus the service's own `PREFS_NAME`) | legacy glue to the services |

### 2.3 System settings (backend `assistant_config.json`, via `GET/PUT /api/config`)

Everything in the System tab is **sent to the backend** as a `ConfigPatch` (`data/SystemConfig.kt:131-150`, serialized at `network/ApiClient.kt:816-863`). These settings apply to all clients.

| Field (`ConfigPatch`) | JSON key | UI control | Notes |
|---|---|---|---|
| `defaultModel` | `default_model` | Text-mode Provider and Model | Changing the provider picks that provider's first model (`SystemSettingsTab.kt:223-226`). |
| `defaultVoiceProvider` | `default_voice_provider` | Voice Provider | openai / qwen / google, labels at `:18-22` |
| `defaultVoiceEndpoint` | `default_voice_endpoint` | Backend (Google only) | Refetches the Google catalog when it changes (`system/SystemConfigController.kt:139-188`) |
| `defaultVoiceModel` | `default_voice_model` | Voice Model | Auto-corrected when Google deprecates an id (`SystemConfigController.kt:203-229`), which writes a second PUT |
| `defaultVoiceName` | `default_voice_name` | Voice | |
| `defaultVoiceTranscriptionLanguage` | `default_voice_transcription_language` | Transcription language | Shown only if the model lists languages |
| `voiceRecordingEnabled` | `voice_recording_enabled` | Voice recording switch | |
| `voiceVadThreshold` | `voice_vad_threshold` | slider 0.15–0.5 | Parse default 0.28 (`ApiClient.kt:1049`) |
| `voiceVadMinSilenceMs` | `voice_vad_min_silence_ms` | slider 800–5000 | default 2500 |
| `voiceMicGain` | `voice_mic_gain` | slider 0.5–2.0 | Labelled "reserved" in the UI |
| `provider` | `provider` | Session provider | |
| `harnessModel` | `harness_model` | Qwen model (the `"qwen"` key only) | |
| `workingDirectory` | `working_directory` | Active directory | select only; no add, edit or delete |
| `chromeExtension` | `chrome_extension` | switch | |
| `enabledMcps` | `enabled_mcps` | per-server switch | see the bug in §8 |

Voice defaults are also read at the start of every voice session (`ApiClient.getVoiceConfig`, `ApiClient.kt:239-262`) and forwarded in `voice_start` (§3.5).

---

## 3. Networking

### 3.1 URL handling

- **WS:** `http→ws`, `https→wss`, then strip any `/api/orchestrator/chat` or `/api/sessions/chat` and append the endpoint path (`network/WebSocketManager.kt:154-170`). The two endpoints are `ORCHESTRATOR` → `/api/orchestrator/chat` and `AGENT` → `/api/sessions/chat` (`:12-15`).
- **REST:** the reverse mapping, with `/api/orchestrator` stripped as well (`network/ApiClient.kt:41-53`).
- **The `ApiClient` is rebuilt whenever `serverUrl` changes** (`AssistantViewModel.kt:192-194`). Every controller takes function-typed dependencies that read the *current* client.

### 3.2 Backend discovery (`network/NetworkScanner.kt`)

- Finds the /24 subnet from `WifiManager.connectionInfo.ipAddress` (`:48-59`). This API is deprecated, returns 0 off-WiFi, and is restricted on newer Android.
- Probes `.2–.254` in parallel (`:33`) with a **plain TCP connect** on ports 80 and 8765 and a 400ms timeout (`:21-22, 62-71`). Any host with port 80 open (router admin page, printer, NAS) counts as a "backend", because there is no `/api/health` check. Results are sorted by last octet.
- `scanForServers` (`connection/OrchestratorConnectionController.kt:318-338`) auto-adopts the first result only when the saved URL is still the default and the WS isn't connected. "Adopt" means "write the URL"; as noted in §1.6, nothing then connects.

### 3.3 Connection lifecycle

- **Two independent sockets** (`WebSocketManager.kt:50-62`), one per endpoint. A new `OkHttpClient` is built per `connect` (`:98-101`): `pingInterval 30s`, `readTimeout 0`, default connect timeout. The public `connectionState` is the orchestrator socket's only (`:65-66`).
- **Connect:** `connect()` → `OrchestratorConnectionController.connect(localId)` → `awaitLoaded().serverUrl` (the structural fix for the cold-start URL race, `28d982d`). The agent socket opens lazily in `ChatController.openSessionOnEndpoint` (`chat/ChatController.kt:1151-1179`). Opening an agent session must not tear down the orchestrator socket, because voice may be live on it.
- **Orchestrator handshake** (`OrchestratorConnectionController.onWsConnected`, `:186-223`):
  1. If `armNewSessionStart` was set, emit `NewSessionAdopted`.
  2. Otherwise probe `GET /api/sessions/pool/live` for `is_orchestrator`, retrying once after 400ms.
  3. If one is found: persist its local_id, then emit `OrchestratorAdopted`, plus `Reconnected` on every connect after the first.
  4. If none is found: emit `NoOrchestratorFound`, which routes the UI to History.
- **Chat reaction** (`ChatController.handleConnectionEvent`, `:336-389`):
  - On a cold start (`userPickedBucket == false`), select the orchestrator bucket and send `start{local_id, resume_sdk_id, resume_from?}` (commit `797cf1e`).
  - Otherwise only record `pendingResumeSessionId`.
  - **Voice:** `Reconnected` with `activeVoiceConfig` sends `voice_start`; without it, a plain `start` (`voice/VoiceController.kt:444-471`). On a normal reconnect **two `start` frames are sent**: one from Chat (via `resyncOnResume` or a cold start) and one from Voice.
- **The `orchestrator_active` error** (`ChatController.kt:764-782`, `OrchestratorConnectionController.kt:251-306`):
  - If the user has an intent in flight, the error goes to the conflict dialog.
  - Otherwise a bounded, single-flight recovery runs: attempts at 0, 500 and 2000 ms, giving up after 3 (`MAX_RECOVERY_RETRIES`, `:82`) and routing to History. It reconnects if the WS is down, and otherwise resends `start` with the adopted ids (loop fix `5b1b1f8`).
- **Reconnect:** `onClosed` or `onFailure` → `handleDisconnect` → a **fixed 3000ms delay with no backoff, retried forever** while `shouldReconnect` is set (`WebSocketManager.kt:172-183`). There is no `ConnectivityManager` callback. `disconnect()` clears `shouldReconnect` and shuts the dispatcher down (`:186-197`).
- **On every failed attempt `onFailure` emits `WebSocketEvent.Error(msg)`** (`:145-150`). ChatController appends it as a SYSTEM chat bubble (`ChatController.kt:764-770`). **While the server is down, the chat gains a new "Error: Failed to connect to /192.168.0.200:80" bubble every 3 seconds.**
- **Agent reconnect:** on `Connected` for AGENT, either flush `pendingAgentResume` or resend `start` from `lastResumeSdkId` so the socket re-subscribes (`ChatController.kt:408-437`, commit `b9646ee`).
- **Foreground:** `onResume` → `reconnectIfNeeded` + `resyncOnResume` (resends `start` on every connected bucket; also `reconcileOrchestrator`, which follows the pool if another device replaced the orchestrator, `:979-1011`) + a forced session refresh.
- **Keepalive:**
  - OkHttp protocol ping every 30s (`WebSocketManager.kt:43-47`).
  - The backend sends app-level `{"type":"ping"}`, which is consumed silently (`:321-328`). The comment there says it keeps the A300M's radio awake.
  - A transient drop is delivered as `Disconnected(willReconnect=true)`; voice is kept, and the `Reconnected` path re-arms it (`VoiceController.kt:403-429`; feedback memo `feedback_android_ws_keepalive_silent_drop`).
  - **Don't add an extra app heartbeat or disable OkHttp pings.** Both were tried and reverted (`context/memory/assistant/android/android_peripheral_project.md`, "What was tried … and reverted").
- **Resume protocol:**
  - A `start` carries `resume_from{stream_id, seq}` from the persisted checkpoint (`ChatController.kt:281-313`).
  - Every inbound event that has `stream_id`+`seq` produces a `ResumeCheckpoint` (`WebSocketManager.kt:306-318`).
  - `session_started.resume_state` seeds `next_seq-1`. `replay_overflow` clears the checkpoint (`ChatController.kt:496-541`).
  - **Only agent-session broadcasts are seq-stamped** (`api/pool.py:1237-1264`). `broadcast_orchestrator` (`api/pool.py:550-567`) stamps nothing, so the orchestrator relies on a REST refetch in `SessionStarted` (`ChatController.kt:474-492`).
- **Event bus:** a `MutableSharedFlow(extraBufferCapacity = 64)` with `tryEmit` (`WebSocketManager.kt:77`). **When the collector falls behind, events are silently dropped** (`tryEmit` returns false). The collector runs on `Dispatchers.Default` and fans out to Chat and then Voice (`AssistantViewModel.kt:208-213`). With voice audio at about 50 frames/s on the same flow, a slow UI can lose text deltas.
- **Outbound sends** are fire-and-forget: `webSocket?.send(json)` with no outbox (`WebSocketManager.kt:290`). A send on a socket that isn't connected is silently dropped. The exception is shared-text inject, which has a one-slot queue (`ChatController.kt:901-918, 460-466`).

### 3.4 REST calls (`network/ApiClient.kt`)

There is one OkHttp client with a 10s connect timeout and a 30s read timeout (`:35-39`). Requests carry no auth header. Every method swallows exceptions and returns an empty value or null.

| Method | Endpoint | Fields used | Caller |
|---|---|---|---|
| `listSessions` | `GET /api/sessions` | session_id, local_id, title, started_at, last_activity, message_count, is_orchestrator, provider | History (`:59-95`) |
| `getSession` | `GET /api/sessions/{id}` | — | **unused** (`:101-139`) |
| `getMessagesPaginated` | `GET /api/sessions/{id}/messages?limit=50[&before=N]` | messages[role, text, blocks[type, text, tool_use_id, tool_name, tool_input, output, is_error, summary], timestamp], total_count, has_more, start_index | load, refetch, load-more (`:150-190`) |
| `getLivePool` | `GET /api/sessions/pool/live` | local_id, sdk_session_id, status, is_orchestrator, title | probe, recovery, conflicts, refresh (`:196-229`) |
| `getVoiceConfig` | `GET /api/config` | default_voice_{provider, model, name, transcription_language, endpoint} | voice start (`:239-262`) |
| `startVoiceSession` | `POST /api/orchestrator/voice/session?provider&model&voice&transcription_language&endpoint` | `connection_info{connection_type, endpoint, ephemeral_token, expires_at, model, voice, audio_in_format, audio_out_format}` | VoiceManager (`:293-340`) |
| `fetchOpenAiKey` | `GET /api/config/openai-key` | api_key | wake-word Whisper confirmation (`:351-368`) |
| `uploadFile` | `POST /api/uploads` (multipart `file`) | filename, path, url, size, content_type | share and upload (`:381-418`). The ViewModel reads the **whole file into memory** first (`AssistantViewModel.kt:285-287`), which is an OOM risk on the 888 MB A300M. |
| `renameSession` | `PATCH /api/sessions/{id}/rename {title}` | — | `:454-477` |
| `closePoolSession` | `POST /api/sessions/{local_id}/close` | 204, or 404 treated as OK | `:486-503` |
| `duplicateSession` | `POST /api/sessions/{id}/duplicate` | session_id | `:511-532` |
| `truncateSession` | `POST /api/sessions/{id}/truncate {drop_last_n}` | — | rewind (`:543-563`) |
| `forkSession` | `POST /api/sessions/{id}/fork {drop_last_n}` | session_id | `:572-597` |
| `deleteSession` | `DELETE /api/sessions/{id}` | — | `:603-619` |
| `getAssistantConfig` | `GET /api/config` | full config (`:1014-1057`) | System tab |
| `updateAssistantConfig` | `PUT /api/config` (`ConfigPatch`) | the 400 `detail` is surfaced as the error | System tab (`:816-863`) |
| `listMcpServers` | `GET /api/mcp/servers` | servers{name: {type, command, args, env}} | `:866-895` |
| `listOrchestratorModels` | `GET /api/orchestrator/models` | models[provider, model_id, display_name, supports_*] | `:898-922` |
| `listVoiceModels` | `GET /api/orchestrator/voice/models` | providers{id: [model entries]} | `:925-943` |
| `listGoogleVoiceModels` | `GET /api/config/voice/google/models[?endpoint=]` | models | `:946-961` |
| `listQwenHarnessModels` | `GET /api/config/harness/qwen/models` | models | `:964-989` |
| `listSessionProviders` | `GET /api/config/providers` | providers[id, label, description] | `:992-1012` |
| `getVoiceToken` | (alias of startVoiceSession) | — | **unused** (`:421-429`) |

### 3.5 WebSocket client→server (`WebSocketManager.send`, `:203-291`)

| type | Fields | Sent by |
|---|---|---|
| `start` | `local_id?`, `resume_sdk_id?`, `resume_from{stream_id, seq}?` | Chat (open, reconnect, resume), Connection recovery, Voice (`Reconnected` without voice) |
| `stop` | — | `newSession` when already connected (`ChatController.kt:1250`) |
| `send` | `text` | `sendMessage` (the current endpoint) |
| `inject_text` | `text` | share or upload (always ORCHESTRATOR) |
| `send_audio` | `audio` (b64 WAV), `format`, `text?` | push-to-talk and talk-word capture. **Routed to AGENT when an agent session is visible** (`VoiceController.kt:654-658, 687-691`), but the agent route doesn't handle `send_audio` (`api/routes/chat.py:148-242` accepts only start, send, command, interrupt, compact, permission_response and stop), so the audio is lost. Meanwhile the "[Voice message]" bubble is written to the *orchestrator* bucket. |
| `interrupt` | — | Stop button |
| `compact` | — | `ChatController.compact()`. **There is no UI for it.** |
| `voice_start` | `local_id`, `resume_sdk_id`, `voice_provider`, `voice_model`, `voice_name`, `voice_transcription_language`, `voice_endpoint?` | VoiceController (`:730-742`, `:448-459`) |
| `voice_stop` | — | `stopVoiceSession` |
| `voice_event` | `event{…}` | WebRTC data-channel mirror (VoiceManager callback) |
| `voice_audio_in` | `audio` (b64 PCM16) | WS-path voice providers |
| `set_model` / `get_model` / `get_models` | `model` | **Never sent (dead code)**, `data/Models.kt:392-394` |

**Not implemented by Android but used by the web or backend:** `command` (`frontend/src/hooks/useChatInstance.ts:939-944`), `permission_response` (`:1084`), `start.mcp_servers`, and `voice_recording_chunk` / `voice_recording_end` (`api/routes/orchestrator.py:289-306`).

### 3.6 WebSocket server→client (`WebSocketManager.parseMessage`, `:294-572`)

**Handled:**
- **Connection:** `ping`/`pong` (ignored).
- **Session lifecycle:** `session_started` (`session_id`, `voice`, `voice_session_update`, `voice_initiator` (default **false**, `:335-342`), `resume_state{stream_id, next_seq}`, `replay_overflow`); `session_stopped`; `session_terminated{reason, detail, sdk_session_id}`; `user_message{text}` (the `queued` flag is ignored).
- **Pool watcher:** `agent_session_opened{session_id, sdk_session_id, is_orchestrator}`, `agent_session_closed` (they trigger a forced session-list refresh, `ChatController.kt:596-604`).
- **Turn status:** `status{status}`.
- **Streaming:** `text_delta{text, message_id}`, `text_complete{text}`, `thinking_delta`, `thinking_complete`.
- **Tools:** `tool_use{tool_use_id, tool_name, tool_input}`, `tool_executing`, `tool_result{tool_use_id, output, is_error}`. `tool_progress` is parsed but **ignored**.
- **Turn end:** `turn_complete` (input/output tokens are parsed and **ignored**; cost, usage and num_turns are dropped).
- **Voice:** `voice_command{command}`, `voice_event{event}` (four high-frequency delta types are dropped at parse time, `:465-472`), `voice_audio_out{audio}`, `voice_ending`, `voice_ended`, `voice_stopped`, `voice_owner_active{active, owner_local_id}`, `voice_vad_state{state, duration_ms, silero_prob}`, `voice_error{error{category, message, recoverable, recovery_hint, provider_doc_url, raw_close_code, raw_close_reason, provider}}`.
- **Other:** `compact_complete{summary}`, `error{error, detail}`.
- **Legacy:** `content_block_delta`, `message_start`, `message_end`/`message_stop`. The current backend doesn't emit the `message_*` events (`api/serializers.py`).

**Silently ignored (no `when` branch):**
- `session_stalled` (stall banner on web)
- `permission_request` / `permission_resolved` (PermissionBar on web)
- `nested_session_event`
- `model_changed` / `model_info` / `models_list`
- `voice_connection_error`. A background relay-start failure (`api/routes/orchestrator.py:1006-1013`) is invisible to Android; the web shows it (`frontend/src/hooks/useVoiceOrchestrator.ts:565-566`).
- the orchestrator `compact_complete` with `tokens_before/after` and no `summary`

The backend sends orchestrator frames as **binary** (`send_bytes`); Android handles both text and binary frames (`WebSocketManager.kt:118-133`).

### 3.7 TLS and auth

- `network_security_config.xml` allows cleartext globally and trusts **system CAs only**. `wss://` to a self-signed backend (`context/certs/`) fails. There is no user-CA trust, no pinning, and no client auth.
- The REST and WS calls carry no token. Anyone on the LAN can drive the backend, including `GET /api/config/openai-key`, which returns the raw OpenAI key. The web `AuthGate` is about the backend's *Claude* credentials in headless mode (`frontend/src/components/AuthGate.tsx`), not app auth. Android has no equivalent screen.

---

## 4. Chat message model and rendering

### 4.1 Model

- `ChatMessage{id (random UUID), role USER|ASSISTANT|SYSTEM, content, blocks, timestamp, isStreaming}` (`data/Models.kt:35-53`).
- `MessageBlock = Text{text, isStreaming} | Thinking{…} | ToolUse{toolUseId, toolName, toolInput, result, isError, isExecuting, isComplete} | Compact{summary}` (`:8-30`). `timestamp` is never rendered and `displayText` is unused.
- **Per-endpoint buckets** (`chat/ChatStateBucket.kt:32-78`, `ChatController.kt:111-121`): ORCHESTRATOR and AGENT. Each has messages, pagination, `streamingMessageId`, `sessionStatus`, `currentLocalId`, `pendingResumeSessionId`, `lastResumeSdkId` and `termination`. The UI mirrors whichever bucket `_isOrchestratorSession` selects (`:159-183`).
- **Session cache:** LRU of 5 sessions × 100 messages (`:100-102, 1181-1203`). Page size 50.

### 4.2 Event-to-state reducer (`ChatController.handleWebSocketEvent`, `:405-806`)

| Event | Effect |
|---|---|
| `message_start` | Append a new assistant message and set `streamingMessageId` (`:606-617`). Not emitted by the current backend. |
| `text_delta` / `thinking_delta` | `ensureStreamingMessage` (`:808-821`: **only creates a message when `streamingMessageId == null`**), then `mutateStreamingBlocks` (`:832-842`, which **targets the message whose id == `streamingMessageId`, wherever it sits in the list**). The delta extends a trailing streaming block of the same type or appends a new one. |
| `text_complete` / `thinking_complete` | Replaces a trailing streaming block, or **appends a new finalized block** (`:631-665`). If the text block was already finalized by a `tool_use`, a late `text_complete` would duplicate it. Current backends emit complete before the tool, so this is latent. |
| `tool_use` | `ensureStreamingMessage`, finalize the trailing text or thinking block, append a ToolUse block, set status `tool_use` (`:667-686`). |
| `tool_executing` / `tool_result` | Merge into the ToolUse block with the same id, **inside the streaming message only** (`:688-711`). A result whose tool block lives in another message, or after `streamingMessageId` was cleared, is dropped. |
| `turn_complete` / `message_end` | Finalize the message, clear `streamingMessageId`, set status idle, save to the cache (`:713-737`). |
| `user_message` | Append a user message (`:580-594`). It does **not** close the in-flight streaming message. |
| `compact_complete`, `voice_error`, `error` | Append a SYSTEM message (`:739-782`). The streaming message is not closed. |
| `Disconnected` | `streamingMessageId = null`, status "disconnected" (`:575-578`). A reconnect mid-turn therefore splits the turn into two messages. |
| `session_started` | Set the session id and status idle, clear the termination. **When `pendingResumeSessionId` is set**, asynchronously refetch page 1 over REST and **replace** `b.messages` (`:474-491`). |

**Local, out-of-band appends** (not driven by WS events):
- `sendMessage` (`:848-865`) and shared-text inject (`:880-915`) append a user message.
- `VoiceController` → `ChatController.appendOrchestratorMessage` (`:1555-1557`) appends voice user transcripts `"[voice] …"`, voice assistant transcripts, "[Voice message]" placeholders and voice errors (`voice/VoiceController.kt:483-514, 632-651, 680-686`).

**History conversion** (`network/ApiClient.kt:636-747`) works in two passes, like the web's `convertPreviews`:
1. Collect `tool_result` blocks by `tool_use_id`.
2. Fold them into `tool_use` blocks, then drop user messages that contain only tool results.

Tool blocks from history are always `isComplete = true`, so a tool that is still running at reload time shows "done". The backend `MessagePreviewResponse` has **no `id`** (`api/models.py:62-66`), so each fetch mints new UUIDs (`ApiClient.kt:698`). Every refetch re-keys the whole `LazyColumn` and can retrigger the initial-scroll logic.

### 4.3 KNOWN BUG — tool calls bunch after the first one in orchestrator conversations

**Symptom:** within the current orchestrator turn, the first tool call renders in place. Every later tool call is drawn directly under the first one, and the text that arrived between tool calls piles up underneath all the tool blocks.

**Root cause:** Android tracks the in-flight assistant message **by id** (`streamingMessageId`), not by "the last message in the list". In orchestrator *voice* sessions the backend never sends a message boundary. Text then arrives as separate messages appended *after* that tracked message, so the id ends up pointing at a message that is no longer last.

1. In voice mode the backend broadcasts **only** `tool_use` / `tool_result` for tools (OpenAI WebRTC: `api/routes/orchestrator.py:1547-1572`; Gemini: `:1606-1633`). There is **no `message_start`, `text_delta` or `turn_complete`**, because voice text arrives through the provider path instead.
2. On the first `tool_use`, `ensureStreamingMessage` (`chat/ChatController.kt:808-821`) appends a new assistant message M at the tail and stores `streamingMessageId = M.id`. This is why the first tool lands in the right place (`:667-686`).
3. Voice text does **not** go through the bucket's streaming message. `VoiceController.handleVoiceEvent` appends each user transcript and each completed assistant transcript as a **new standalone `ChatMessage`** via `appendOrchestratorMessage` (`voice/VoiceController.kt:483-500` → `ChatController.kt:1555-1557`). These land after M, and nothing clears `streamingMessageId`.
4. The next `tool_use` finds `streamingMessageId != null`, so `ensureStreamingMessage` does nothing. `mutateStreamingBlocks` (`ChatController.kt:832-842`) maps over the list and appends the new ToolUse block **into M, which is now in the middle of the list**. Every later tool joins M; every later transcript goes to the tail.
5. M is closed only by `turn_complete`/`message_end` (`:713-737`), which voice never sends, or by `finalizeStreamingForVoiceEnd` (`:1564-1586`). That is called **only** on the owner's `voice_ended`/`voice_stopped` (`VoiceController.kt:391-402`). So M also stays open **across voice turns**: every tool call in the whole voice call collects into the first tool's message. M also keeps `isStreaming=true`, so its progress bar spins for the entire call (`ChatScreen.kt:406-412`).

**Compounding paths (same root):**
- **Timeout and error stops never finalize M.** These all call `finalizeVoiceStop()` *without* `finalizeStreamingForVoiceEnd()`:
  - the `voice_ending` 5s ack timeout (`VoiceController.kt:384-388`)
  - the user-stop timeout (`:760-764`)
  - the terminal WS disconnect (`:424-428`)
  - a `VoiceEvent.Error` (`:526`)

  The stale `streamingMessageId` survives into the next **typed** turn. The orchestrator text path has no `message_start` either, so `text_delta` → `ensureStreamingMessage` is a no-op, and the reply is written **into the old voice message above the user's new prompt**.
- **Non-owner devices** ignore `voice_ended` entirely (`VoiceController.kt:393-396`), so they never finalize. Every tool call from another device's voice session piles into one message, and their next typed turn shows the same misplacement. They also receive no transcripts for the WebRTC (OpenAI) provider, because transcripts arrive on the owner's data channel.
- **Any append during a text turn has the same effect.** This covers `user_message` from another client, a shared-file inject, a talk-word "[Voice message]", and SYSTEM error or compact bubbles. Later deltas and tools keep going into the message above the new bubble.

**Why the web doesn't have it:** the web reducer is **tail-based**. `ensureAssistantMessage` / `updateLastAssistantBlock` (`frontend/src/hooks/useChatInstance.ts:140-151`) append to the last message only if it is an assistant message, and otherwise start a new one. `TOOL_USE` (`:308-323`) therefore starts a fresh assistant message after any user or voice transcript. Voice assistant text also streams into that same tail message as blocks (`VOICE_ASSISTANT_DELTA`/`COMPLETE`, `:457-490`), instead of becoming a separate message.

**Fix directions for the rebuild:**
- Adopt the tail-based reducer, or invalidate `streamingMessageId` on *any* append to the bucket.
- Render voice transcripts as blocks of the in-flight assistant message, and stream voice deltas (Android currently drops `VoiceEvent.TextDelta`, `VoiceController.kt:558-560`).
- Finalize on every voice-stop path, including non-owners.
- Ideally have the backend emit explicit turn boundaries in voice mode.

**Regular (agent) chat sessions:** **not affected by this mechanism** in normal use.
- Nothing calls `appendOrchestratorMessage` on the AGENT bucket.
- The Claude/Qwen/Gemini harnesses always end a turn with `turn_complete`.
- Text and tool events arrive in order and are appended at the tail of the streaming message (fixed in `e8917d7`, "preserve arrival order of streaming text and tool blocks").

Agent sessions do share the weaker variants:
- (a) An `error` or `compact_complete` bubble mid-turn: later blocks still land above it.
- (b) A WS drop mid-turn nulls the id (`ChatController.kt:576`), so the turn is split into two assistant messages after resume replay.
- (c) A `tool_result` that arrives after the split is dropped, because it only searches the current streaming message (`:698-711`).

### 4.4 Other ordering, merge and dedupe issues

1. **REST refetch races live streaming (orchestrator reconnect).**
   - After a reconnect, `OrchestratorAdopted` always sets `pendingResumeSessionId` (`ChatController.kt:341`), so the following `session_started` replaces `b.messages` from REST in a coroutine (`:478-491`).
   - Live events arriving meanwhile create a new streaming message (the id was nulled on Disconnect). The REST replace then deletes that message while `streamingMessageId` still points at it.
   - From then on `mutateStreamingBlocks` silently no-ops (no id matches), and `ensureStreamingMessage` won't recreate it. **The rest of that turn is invisible until `turn_complete`.**
   - The REST replace also discards local-only bubbles: "[Voice message]", `[voice]` transcripts not yet persisted, and system errors.
   - This comes from reading the code; it has not been reproduced on a device.
2. **No stable ids in history** (§4.2) means no way to reconcile REST pages with live state, and new keys on every refetch.
3. **Rewind/fork index drift.** `dropLastN = total - 1 - uiIndex` is computed from the UI list (`ChatController.kt:1525-1547`). That list contains local-only SYSTEM bubbles (including the reconnect "Error:" spam from §3.3) and voice placeholders that the backend's "visible messages" count may not include. Rewind can therefore drop the wrong number of messages. The web keeps errors out of the message list (`state.error`).
4. **Optimistic user bubble and server echo.** The backend excludes the source socket from `user_message` (`api/pool.py:905-908`), so normal sends don't duplicate. Shared text is optimistically rendered only when the orchestrator is visible (`ChatController.kt:891-899`). In voice mode the backend *also* broadcasts `user_message` for the inject (`api/routes/orchestrator.py:1057-1062`), which **would duplicate the shared bubble on the sending device**. This was not verified on a device.
5. **History `tool_result` folding is global per page** (`ApiClient.kt:646-661`). A result that sits on a different page from its tool_use shows no output. The web has the same limitation.
6. **Load-more uses the cached `paginationStartIndex`.** If messages were appended locally, or the server truncated the session in between, the indices drift.
7. **Concurrency:** the WS collector mutates bucket fields such as `streamingMessageId` (a plain `var`) on `Dispatchers.Default`, while `VoiceController` appends on Main. `MutableStateFlow.update` is atomic, but the read-modify of `streamingMessageId` across threads is unsynchronized.

---

## 5. Feature parity vs the web (`frontend/src/components/`)

The web Visualizations (`VizPanel`, `VizItem`) and Memory (`MemoryPanel`, `MemoryTree`) panels are already known to be missing and are planned. Everything else is listed below.

**The web has it; Android lacks it or does it differently**

| Web feature | Web source | Android |
|---|---|---|
| Multiple open sessions as tabs, with per-tab status icons, rename, and close-with-confirm | `TabBar.tsx`, `ConfirmCloseModal.tsx`, `context/TabsContext` | Exactly one orchestrator view plus one agent view (two buckets). No tabs, and no per-session status besides the "open" dot. |
| Create a new **agent (Claude Code) session** | `Sidebar.tsx` "+" (`onNew`) | Missing. The FAB creates orchestrator sessions only. |
| Permission gating: approve/reject bar, and typing a message rejects with that text as the reason | `PermissionBar.tsx`, `useChatInstance.ts` PERMISSION_* and `permission_response` | Missing entirely. Events are ignored, so an agent waiting on `ExitPlanMode` hangs from Android's point of view. |
| Stall banner ("tool X silent for Ns" + Interrupt) | `ChatPanel.tsx:165-…` (`session_stalled`) | Missing |
| Context-usage % and Compact button | `ChatInput.tsx` (`onCompact`, `contextUsage`) | `compact()` exists in the ViewModel with no UI. Tokens from `turn_complete` are dropped. |
| Cost and turn counters | `StatusBar.tsx` | Missing |
| Per-session config page (working dir, MCPs, skills/agents; gear in the input) | `SessionConfigPage.tsx`, `AgentSettings.tsx`, `McpSelectionModal.tsx` | Missing |
| Working-directory CRUD (add, edit, SSH host/user, delete) | `WorkingDirectoryList.tsx` | Selector only |
| Claude auth gate for a headless backend | `AuthGate.tsx` | Missing |
| Busy overlay for duplicate/rewind/fork | `BusyOverlay.tsx` | Toasts only, with no progress indication |
| Queue a message while streaming (Enter sends; backend queues via `user_message{queued}`) | `ChatInput.tsx` handleKeyDown, `api/pool.py:1055-1066` | Blocked: Send is hidden while streaming and IME Send is ignored (`ChatScreen.kt:1313`). |
| Slash-command passthrough (`command` frame) | `useChatInstance.ts:939-944` | Missing |
| Rich tool input views: Write (syntax-highlighted content), **Edit diff**, Read, Grep, FileRead, Search, `send_to_agent_session` block, `evaluate_script` view | `ToolUseBlock.tsx:456-760, 1091` | Generic key:value dump. Only TodoWrite, Task and Bash are specialized. No Qwen tool-*input* normalization (`normalizeToolInput`, `:82`). |
| Streaming voice assistant text into the chat | `VOICE_ASSISTANT_DELTA` (`useChatInstance.ts:457`) | Only the final transcript, as a separate message |
| Passive voice viewer (renders transcripts from another device's voice session) | `registerVoiceEventHandler` (`useChatInstance.ts:625-627, 847`) | Non-owner shows only "active elsewhere" and tool blocks |
| Voice connection error surfaced, and voice error text shown under the input | `useVoiceOrchestrator.ts:565`, `ChatPanel.tsx:215-217` | Ignored or never displayed (§1.4) |
| Voice bar with mic **and** assistant (speaker) mute plus live mic and speaker level meters | `ChatPanel.tsx:222-235`, `VoiceControls.tsx` | Mic mute and end only; no level meters or speaker mute |
| Markdown via `react-markdown` + GFM + Prism | `Markdown.tsx` | Custom parser (see §8). No images, strikethrough, task lists, nested lists or h4–h6. Table cells are plain text and columns don't align. |
| Session title visible in the chat header | `ChatPanel`/`TabBar` | No header at all |

**Android has it; the web lacks it**

- On-device two-layer wake and talk words (Vosk with Whisper confirmation), talk-word same-mic capture with auto-send, and audible ack beeps (`AssistantViewModel.kt:493-561`).
- System share target and an in-conversation **file upload** into the orchestrator (`POST /api/uploads` + `inject_text`). `frontend/src` contains no reference to `/api/uploads`.
- Audio output routing (Auto, Speaker, Earpiece, BT, Wired), mic gain, echo ducking, speaker volume.
- Saved servers and LAN scanning (a web page doesn't need them).
- A light theme. The web is dark-only: no `prefers-color-scheme` or `data-theme` in `index.css`/`App.css`.
- A three-way orchestrator conflict dialog (Open running / Close and switch / Cancel). The web `OrchestratorModal.tsx` is two-way: "Stop & start new" / Cancel.
- Hardware triggers: Assist long-press home, recents long-press, a transparent shortcut activity.
- A voice reconnect heads-up banner plus an audible beep (`VoiceController.kt:532-543`, `AssistantViewModel.kt:569-628`). No equivalent was found in `frontend/src`.

---

## 6. Android-specific features to preserve

| Feature | Where | Notes for the rebuild |
|---|---|---|
| Foreground service (type `microphone`, START_STICKY) hosting the wake-word detector | `AndroidManifest.xml:78-82`; `service/AssistantService.kt:410-549` | Started from the UI (`MainActivity.kt:403`). Pause and resume use ack-token intents (`:166-198`) so voice and wake word don't fight over the mic. The 1.5s mic-release delay before re-arming is load-bearing (`VoiceController.kt:787-799`). **Consider moving the voice session and the WS into the service**, so wake triggers work when the Activity is gone. Today the detection broadcast (`voice/WakeWordDetector.kt:1143-1146`) is sent immediately after `startActivity`, and **is lost if MainActivity isn't alive yet** (the receiver registers in `onCreate`). |
| Screen-on and keyguard handling | `AssistantService.kt:201-221` (`SCREEN_BRIGHT_WAKE_LOCK \| ACQUIRE_CAUSES_WAKEUP`, 3s), `MainActivity.kt:165-187`, manifest `showWhenLocked`/`turnScreenOn` | The wake lock is "the reliable path on Lollipop". Modern Android also restricts background activity starts (API 29+); verify on the POCO. |
| Notification with mic-unavailable state | `AssistantService.kt:291-322, 700-727` | Add actions in the new app: stop listening, end voice. |
| Permissions: RECORD_AUDIO, POST_NOTIFICATIONS (33+), BLUETOOTH_CONNECT (31+), MODIFY_AUDIO_SETTINGS, FOREGROUND_SERVICE(_MICROPHONE), WAKE_LOCK, INTERNET, ACCESS_WIFI_STATE | `AndroidManifest.xml:6-22`, `MainActivity.kt:202-239` | Add rationale and denied-state UI; today denials are silent. |
| Boot behaviour | **None in this app.** There is no `RECEIVE_BOOT_COMPLETED`. On the A300M, boot launch and the watchdog come from the separate companion app `com.assistant.device` (`android-device/`, `context/memory/assistant/android/android_device_project.md`). | Keep that separation. Decide whether the modern-phone app needs a boot receiver. |
| Multi-device voice ownership ("active elsewhere") | `VoiceController.kt:146-166, 305-316, 358-365`; `VoiceButton.kt:50-53, 139-161`; shipped in `b6d184f` + `afe77a4` | The owner acts on lifecycle and command events; a non-owner shows a read-only disabled button. `voice_initiator` defaults to false (`WebSocketManager.kt:335-342`). Preserve these semantics, but add a visible label and transcript mirroring. |
| Mic-gain and wake-gain sliders, talk auto-stop sensitivity, echo ducking, audio output | §2.1 | The wake-word tuning constants are a tuned equilibrium; don't change them as part of UI work (feedback memo `feedback_dont_touch_wake_word_tuning`). |
| Wake/talk UX: instant beep, nav to Chat, "Listening…" confirm banner, recording indicator auto-clear on reject | `MainActivity.kt:322-389`, `VoiceController.kt:579-624` | Commits `3c4dbba` (instant ack) and `b710c8b` (stuck record button). |
| Voice state machine UI including Summarizing and Ending | `data/Models.kt:84-105`, `VoiceController.kt:372-402, 753-800` | Ending flips to Off after `voice_ended` or a 5s timeout (`ENDING_ACK_TIMEOUT_MS`). |
| Deep links and intents | `ACTION_ASSIST` → `VoiceShortcutActivity`; `VoiceInteractionService`; AccessibilityService; `ACTION_SEND` share; the internal extra `wake_word_triggered` | There are **no URI deep links**. A launcher shortcut or quick-settings tile ("Talk") would be natural additions. |
| Orchestrator conflict mediation | `ChatController.kt:1274-1427`, `chat/OrchestratorConflict.kt` | Covered by `OrchestratorConflictTest`. |
| Termination banner with resume | §1.4 | Fix the orchestrator-recovery endpoint bug. |
| Parity test suite | `android/app/src/test/java/com/assistant/peripheral/**/parity/` (connection, chat, voice, settings, system, wiring) | These pin the invariants listed in `android_viewmodel.md`. Port or keep them when replacing controllers. |

**VoiceController/VoiceManager surface the UI depends on** (keep this contract when rebuilding the UI):
- **Flows:** `voiceState`, `voiceReconnectBanner`, `vadState`, `vadDurationMs`, `isMuted`, `isRecording`, `wakeConfirming`, `remoteVoiceActive`, `toastMessages` (`VoiceController.kt:118-174`).
- **Operations:** `startVoiceSession`, `stopVoiceSession`, `toggleMute`, `startRecording`/`stopRecording`, `sendCapturedVoiceMessage`, `markRecordingStarting`/`markVoiceConnecting`/`markWakeConfirming`/`clearWakeConfirming`/`clearRecording`, `isBluetoothAudioAvailable`/`isWiredHeadphoneAvailable` (`:579-831`), and `onSettingsChanged(AppSettings)` (`:234-258`, which pushes micGain, echoDuckingGain and audioOutput to `VoiceManager`).
- **`VoiceManager`** (`voice/VoiceManager.kt:141-555`) exposes `state`, `events` (`VoiceEvent`: SessionCreated, SessionEnded, SpeechStarted/Stopped, TurnComplete, TextDelta, TextComplete, UserTranscript, ToolUse, Error, RoutingFallback, ReconnectWarning, Reconnecting; `:644-698`), `start(cfg)`, `stop`, `handleBackendCommand`, `handleProviderEvent`, `pushSpeakerChunk`, `setMicGain`, `setEchoDuckingGain`, `setAudioOutput`, and `clearPreStartState`.
- **Restriction:** `startVoiceSession` refuses when the orchestrator bucket isn't selected, showing `Error("Voice only available for orchestrator sessions")` (`:700-703`). That message is never displayed.

---

## 7. Device targeting

| | Samsung Galaxy A300M (dedicated terminal) | Xiaomi POCO X7 (personal phone) |
|---|---|---|
| OS / API | Android 5.0.2 / API 21, not rooted | modern Android (API 34-class) |
| Screen | 540×960 (about 360×640 dp at hdpi) | large, high-density, edge-to-edge |
| RAM / SoC | 888 MB usable, Snapdragon 410, old GPU (`android_peripheral_project.md`, "A300M memory budget") | ample |
| Management | companion app `com.assistant.device` (boot, watchdog, WiFi ADB at static 192.168.0.225), 125 bloat packages removed | none |

**Code that exists only for the A300M or Lollipop** (this chapter's scope; the voice/audio ones are listed for completeness and detailed in chapter 4):

- **UI and app layer:**
  - `VoiceShortcutActivity` + `AssistantVoiceInteractionService`: the Lollipop assist gesture (§1.8).
  - `ButtonAccessibilityService` and the raw `/dev/input/event2` recents monitor: the A300M's capacitive recents key (`AssistantService.kt:617-675`).
  - Pre-O_MR1 window-flag branches (`MainActivity.kt:175-185`, `VoiceShortcutActivity.kt:28-38`) and the bright-screen wake lock (`AssistantService.kt:201-221`).
  - Markdown performance hardening, motivated by a native `libhwui` RenderThread stack overflow on the A300M (commit `31c2fbf`): incremental parse of the stable prefix (`ui/components/markdown/MarkdownText.kt:60-96`), code blocks that wrap instead of scrolling horizontally, an inline-depth cap of 8, and non-animated stick-to-bottom. Keep these as general good practice.
  - Syntax highlighting is gated on API 24+ **and** ≥2 GB RAM (`ui/components/markdown/MarkdownStyles.kt:55-67`), so it is off on the A300M.
  - Audio-frame log suppression (`WebSocketManager.kt:34-38, 284-289`).
- **Voice/audio (chapter 4):**
  - The Vosk `stderr` shim (CMake + `VoskModelLoader` for API < 23).
  - `VOICE_RECOGNITION` audio source on API < 24 (`voice/MicCapture.kt:76-80`, `voice/OpenAIVoiceProvider.kt:352-355`).
  - AudioRouter and PcmPlayback branches for API < M (`AudioDeviceInfo` unavailable).
  - The Samsung HAL quirks (MODE_NORMAL, STREAM_MUSIC beeps).

**Modern-only code:** the `BLUETOOTH_CONNECT` and `POST_NOTIFICATIONS` runtime requests, typed `getParcelableExtra` (33+), `setCommunicationDevice` (31+), dynamic color support (`ui/theme/Theme.kt:114-118`, present but disabled).

**Constraints minSdk 21 imposes on the main app today:**
- Java 8 target.
- No `AudioDeviceInfo`/`setCommunicationDevice` without fallbacks.
- No `enableEdgeToEdge` assumptions.
- The Compose BOM is pinned at `2023.10.01`. Any upgrade must be checked against each AndroidX artifact's minSdk; recent AndroidX releases are moving off API 21. Verify before bumping.
- No predictive back or per-app language.
- WebRTC and Vosk native ABIs limited to armeabi-v7a and arm64.

Raising the main app to minSdk 26–29 removes most branches. **targetSdk 35 forces edge-to-edge**, which the current non-inset-aware shell (manual bottom bar, `MainActivity.kt:721-765`) would break.

**Proposed split and shared modules:**
- **Shared (`:core`):**
  - `data/` models and protocol types.
  - `network/` (`WebSocketManager`, `ApiClient`, discovery).
  - `settings/SettingsRepository` (after fixing checkpoint growth).
  - `connection/OrchestratorConnectionController`.
  - A rewritten chat reducer (pure Kotlin, tail-based).
  - `voice/` (`VoiceManager`, providers, EchoDuck, PcmPlayback, AudioRouter).
  - `audio/`, the wake-word stack (`WakeWordDetector`, Vosk, Whisper), and `service/AssistantService`.
- **Main app (modern):** the new M3 UI, tabs/multi-session, Memory and Viz panels, permissions, tool renderers, the System tab.
- **Lite voice-first app (A300M):**
  - Service-owned voice, wake word and the beeps.
  - A minimal transcript view (no heavy markdown, or the hardened renderer).
  - Server selection, plus the assist, recents and accessibility triggers.
  - It keeps minSdk 21, the Lollipop branches and the Vosk shim.
  - The companion `com.assistant.device` app stays separate.

---

## 8. UI/UX issues, bugs and dead code

**Bugs (beyond §4)**
1. **The MCP toggle inverts semantics.** An empty `enabled_mcps` is rendered as "all on" (`SystemSettingsTab.kt:606-611`). Toggling one server off then calls `toggleMcp`, which *adds* that name (`system/SystemConfigController.kt:232-237`), producing `["that-one"]`: **only the server you tried to disable ends up enabled.** The web renders `enabledMcps.includes(name)` only (`frontend/src/components/AgentSettings.tsx:158`).
2. **Reconnect "Error:" bubbles** are appended to the chat every 3s while the backend is down (§3.3).
3. **No reconnect after a server switch or scan adoption**; the user must tap Connect (§1.6, §3.2).
4. **Relative times are off by the UTC offset** in History (§1.5).
5. **Termination "Continue" always reopens on the agent endpoint** (`MainActivity.kt:627`), even for an orchestrator termination.
6. **`send_audio` is routed to the agent socket** when an agent session is visible, so the audio is lost and the bubble lands in the orchestrator bucket (§3.5).
7. **`VoiceState.Error(message)` is never displayed** (§1.4).
8. **The recents `/dev/input` monitor runs on every device and ignores the toggle** (§1.8).
9. **The Settings → About version is hard-coded to "1.0.0"** while the build is 1.0.9.
10. **Light theme is broken.** The markdown palette `MdColors` (`MarkdownStyles.kt:12-49`), the user bubble colours (`ChatScreen.kt:293-295`), the tool palette and the thinking colours are hard-coded dark values. In Light mode, body text `0xFFC0C0C8` sits on a `0xFFFAFAFC` background. The XML theme is `android:Theme.Material.Light` with a purple status bar (`res/values/themes.xml`, `colors.xml` primary `#4B0856`), so every launch flashes purple and white before Compose paints.
11. **The System tab can show "Couldn't load configuration" spuriously.** The config loads only on the tab's onClick, so if `selectedTab` is restored as SYSTEM by `rememberSaveable` after process death, nothing loads.
12. **BT/Wired availability isn't live**, as the TODO in `MainActivity.kt:580-583` admits.
13. **Speaker slider vs system volume drift** (§2.1).
14. **DataStore checkpoint growth and a write per token** (§2.2): a performance and storage problem, worst on the A300M.
15. **The scanner accepts any open port 80** (§3.2).

**Dated or inconsistent UI**
- **The chat screen has no app bar or title.** There is no indication of which session is open or its type, and no quick switch back to the orchestrator.
- **The bottom nav is a custom icon-only row** of 64×48 pills in an 80dp-tall bar with no labels. It is not an M3 `NavigationBar`. Labels disagree: the nav says "History", the screen says "Conversations", and the empty state says "New Conversation".
- **Settings is a long scroll of four cards.**
  - The Audio card mixes voice-session audio, wake-word phrases, wake sensitivity, talk VAD and the recents trigger.
  - Wake phrases need a separate Save button while every slider auto-commits.
  - Long caption paragraphs ("Turn-based capture stops after you pause. Lower = …").
  - The System tab exposes internal tuning knobs, one of them marked "reserved".
- **Spacing is ad hoc.** `Spacer` calls with 4, 6, 8, 12 and 16dp are mixed throughout (for example `SettingsScreen.kt:204-421`, `SystemSettingsTab.kt:200-399`). There are no shared dimension tokens, `Typography()` is the M3 default (`ui/theme/Theme.kt:139`), and colours mix `MaterialTheme` with literal hex values (status dots, the "open" green `0xFF4AAA7A` in `SessionsScreen.kt:243, 302-307`, voice palette).
- **The conflict dialog** puts three actions into M3 AlertDialog slots (`MainActivity.kt:856-891`); it wraps poorly at 360dp.
- **Every message, including system errors, gets a ⋮ menu**, which is noisy. There is no long-press, copy, or select-text for messages; only code blocks have Copy.
- **The tool status pill uses 9sp text and a 10dp icon** (`ChatScreen.kt:682-690`), below comfortable touch and readability sizes.
- **The scroll-up hint is plain text** ("↑ Scroll up for older messages") instead of a proper load affordance.
- **Toasts are used for all feedback.** There are no snackbars with Undo for delete, rewind or fork.
- **The Chat input hides entirely during voice**, the same as the web (`frontend/src/components/ChatPanel.tsx:195-196`). Neither client can type a correction mid-call.
- **No empty state on Chat** for "connected but no session". The app relies on auto-navigation to History.

**Dead code and leftovers**
- `ApiClient.getSession` (`:101`) and `getVoiceToken` (`:421`).
- `WebSocketMessage.SetModel/GetModel/GetModels` (`data/Models.kt:392-394`) and their serializers (`WebSocketManager.kt:271-280`).
- `WebSocketEvent.SessionList`, `HistoryLoaded` (never emitted) and `ToolProgress` (parsed, then ignored) (`Models.kt:192-195, 156`; `ChatController.kt:800-804`).
- Legacy `content_block_delta`/`message_start`/`message_end` parsing (`WebSocketManager.kt:558-567`).
- `OrchestratorConnectionController.teardownForServerUrlChange` (`:355-361`) is **never called**, so `initialConnectionDone` is never reset on a server change. The first connect to a new server therefore emits `Reconnected`, which sends an extra `start`.
- `AssistantViewModel.newSession`, `compact` and `clearWakeConfirming` have no UI callers (`:343, 351, 389`).
- `SettingsScreen`'s `onUpdateServerUrl` parameter is unused.
- `ChatMessage.displayText` and `timestamp` are never read.
- The VoiceButton D-pad focus code is a Fire TV leftover.
- Unused `strings.xml` entries (`nav_tabs`, `new_tab`, `rename_tab`, `close_tab`, `server_url`, `connect`, …) and template colours (`purple_*`, `teal_*`).
- `android/migrate_wake_word_rename.sh`, a one-off migration script.
- The chapter-4 inventory should confirm whether the `SpeechRecognizerEngine` fallback is still live; it's retained only as a fallback when Vosk fails to load (`project_wakeword_vosk_v6_deferred`).
- The memory doc `context/memory/assistant/android/android_peripheral_project.md` (§"Primary Target Devices") wrongly says the peripheral app uses "native Activity, no AppCompat or Jetpack Compose". That describes the companion app, not this one.

**Load-bearing workarounds to keep (with their reasons)**
- `if/else` instead of early `return@Box` in Compose content lambdas (`afe77a4`).
- The gain must be in the `updateWakeWord` key list (`d6181b1`).
- `awaitLoaded()` before reading `serverUrl` (`28d982d`).
- The pool-probe retry after 400ms and the 0/500/2000 recovery cap (`5b1b1f8`).
- Re-send `start` on every agent and orchestrator reconnect and on resume (`b9646ee`, `13beaa8`).
- `voice_initiator` defaulting to false (`b6d184f`).
- The 1.5s mic-release delay before re-arming the wake word.
- Clear both `isRecording` and `wakeConfirming` after a talk send (`VoiceController.kt:672-679`).
- The cold-start orchestrator `start` so its history loads (`797cf1e`).
- The incremental markdown parse and non-animated scroll (`31c2fbf`, `06fe06f`).
- The load-more guard (`5c029d6`).
