# 15 — Archie TV (Android TV / Fire TV app) + TV control channel + launcher rework

Status: **DRAFT v1** · 2026-10-16 · branch `feat/tv` · coordinator: Claude · owner: Rodrigo

This spec covers three deliverables that ship together:

1. **Archie TV** — a native 10-foot client (`apps/android/app-tv`, `com.assistant.archie.tv`) with the
   same functionality as the other apps (conversations, agents, visuals, memory, voice, settings),
   navigable with the plain Fire TV remote, with **voice through the remote's microphone**.
2. **TV control channel** — agents and the orchestrator open any visualization, memory note,
   conversation or web page **in the TV app**, over the app's own connection to the backend
   (`/api/tv/ws`). No adb, no pairing prompts.
3. **TvServerHub rework** (the existing launcher/screensaver, `~/AndroidStudioProjects/TvServerHub`):
   two cards (Archie, YouTube), a settings screen, and **blank (black) screen after 15 min idle**.

It does not restate what other specs pin down: the wire protocol and reducer (spec 12), the tokens
(`apps/design-tokens`), the shared Android core (spec 14). It says how the TV app uses them and what
it adds.

---

## 0. Decisions

| # | Decision | Why |
|---|---|---|
| TV-1 | **Native Android TV app** in the existing Gradle build (`:app-tv`), Kotlin + Compose + `androidx.tv:tv-material` 1.1.0. Target: Fire TV (Fire OS 7/8) and Android TV / Google TV 8+. **minSdk 26**, ABIs `armeabi-v7a` + `arm64-v8a`. | Reuses the whole shared core (protocol, reducer, repositories, voice, markdown, design tokens). The remote mic and launcher integration need native APIs. "Any modern TV" = the Android TV family (Fire TV, Google TV, Sony/TCL/Hisense/Philips/Xiaomi, Shield). Samsung Tizen / LG webOS are out of scope for v1 (they could later get a `/tv/` web build; nothing here blocks that). |
| TV-2 | **Remote mic = hold-to-talk** via a plain `AudioRecord(VOICE_RECOGNITION)` that the app keeps armed while it is in the foreground. Audio only flows while the mic button is held, so frames arriving = button down, a gap = released. | Probe (§2): verified on the AFTKM. |
| TV-3 | **Alexa's voice UI (`com.amazon.vizzini`) is disabled once with `pm disable-user`** by the setup script, so the mic button belongs to Archie. Reversible (`tv_setup.sh --restore-alexa`). The app detects the state and explains it. | With vizzini enabled Alexa reacts to the same audio (overlay, answers, navigates home). Disabled: Archie still gets the audio, Alexa stays quiet (§2, round 3). Rodrigo approved the test 2026-10-16. |
| TV-4 | **Dedicated control socket `/api/tv/ws`** modelled on the browser-extension hub (`backend/api/routes/browser.py`), with `POST /api/tv/command` loopback-only and `GET /api/tv/status`. Script `tv_cmd.py`. "Show on TV" prefers the app, falls back to adb. | Orchestrator WS has no client identity and is a broadcast channel; the browser hub is a proven, tested pattern (correlation ids, timeouts, replace-on-reconnect). |
| TV-5 | A TV control command addressed to this device **is a user action** — exempt from spec 12 FOCUS-1 ("no server event may navigate the UI"). Only `/api/tv/ws` commands get this exemption. | It is the whole point of the channel. |
| TV-6 | **Left navigation drawer** (tv-material `NavigationDrawer`, collapsed to icons) with 5 destinations; content uses horizontal rows + two-pane lists. A **key-hint bar** at the bottom of every screen. | Google + Amazon 10-ft guidance; few destinations; hints make the hidden keys (mic, Menu, Play/Pause) discoverable. |
| TV-7 | **Menu key (≡) = contextual options**, everywhere. Back = up one level; at Home, Back leaves the app. | Amazon guideline; the remote has no other spare key that reaches apps. |
| TV-8 | No wake word, no Vosk on TV: the 68 MB Vosk model asset is excluded from the APK. | No always-on mic on a TV stick; 1.7 GB RAM. |
| TV-9 | **Shared modules change only additively** (new opt-in parameters with today's defaults). `:app-main` and `:app-lite` behaviour must not change; their `check` stays green. TV glue that `:app-main` keeps in `app-main/system/*` is re-implemented in `:app-tv` (small), not moved. | Refactor-methodology rule; parallel sessions depend on the phone apps. |
| TV-10 | TvServerHub keeps its package `com.example.tvserverhub`, its repo, and its role (launcher + screensaver). The Archie TV package is **exempt** from "return to launcher"; blanking applies everywhere (except while media plays). The Archie app pings the launcher (`KEEP_AWAKE`) when content is pushed or voice is active. | Pushed pages have no remote input by nature; a movie must never go black. |
| TV-11 | Idle detection in TvServerHub uses an **AccessibilityService key watcher** (never consumes keys). | The current input check never fires (it counts devices, not presses). Probe verified that an a11y service sees every remote key in every app (incl. while the Amazon home is foreground). |

Open questions (Rodrigo): Q1 "Sleep instead of black" mode (§7.3) is implemented but untested on his monitor setup, because a sleeping stick may not wake. Q2 background listening (hold mic while YouTube plays → Archie hears it) ships behind a setting, default **off** until field-tested (§4.5).

---

## 1. Platform facts (verified 2026-10-16 on the living-room stick)

| Fact | Value | How verified |
|---|---|---|
| Model | AFTKM (Fire TV Stick 4K), Fire OS 8.1.8.5, Android 11 / API 30 | `getprop` |
| CPU / ABI | 32-bit userspace: `armeabi-v7a,armeabi` | `ro.product.cpu.abilist` |
| RAM | 1.7 GB total, ~340 MB available with YouTube running; heap growth limit 192 MB | `/proc/meminfo`, `dalvik.vm.heapgrowthlimit` |
| Display | 1920×1080 @ 320 dpi → **960×540 dp** | `wm size/density` |
| WebView | Amazon WebView chromium 150 | `dumpsys package` |
| Features | `leanback`, `leanback_only`, `faketouch`; **no touchscreen** → Chromium WebView turns on spatial navigation by default | `pm list features` |
| Speech | No `RecognitionService`, no `RECOGNIZE_SPEECH` activity → `SpeechRecognizer` unavailable | `cmd package query-*` |
| IME | `com.amazon.tv.ime/.FireTVIME` | `settings get secure default_input_method` |
| Remote keys reaching apps | DPAD ×4 + CENTER, BACK, MENU (82), PLAY_PAUSE (85), REWIND (89), FAST_FORWARD (90), volume/mute are system, APP_1..4 (shortcut buttons, system) | `.kl` file + probe |
| Keys never reaching apps | HOME, the mic/ALEXA key (scancode 217) — not even an AccessibilityService sees it | probe (§2) |
| Remote mic | `BTLE-In` (`AUDIO_DEVICE_IN_BLUETOOTH_BLE`, "Amazon Fire TV Remote"); not listed by `AudioManager.getDevices()` (only HDMI-ARC and REMOTE_SUBMIX are) | `dumpsys media.audio_policy` + probe |
| Address | DHCP, currently `192.168.0.15:5555` (docs said `.16`); `discover-firetv.sh` finds it by MAC | discovery |

Overscan-safe area: 48 dp left/right, 27 dp top/bottom (Google: 58/28 recommended) → content box
**≈ 844×484 dp**. Nothing actionable outside it.

## 2. Remote-mic probe (`:app-tvprobe`, committed `a6fe015e`)

The probe logs keys (activity + accessibility watcher), lists inputs, and records 16 kHz mono PCM16
bursts to WAV. Rodrigo held the remote and spoke; results:

| Round | Setup | Result |
|---|---|---|
| 1 | `AudioRecord(VOICE_RECOGNITION)` armed, mic button held | `read()` blocks while idle; frames at −9…−20 dBFS arrive only while held. Alexa overlay (`com.amazon.vizzini`) shows and acts on the speech; afterwards the Amazon home came to front. No key event in the app or the a11y service. |
| 2A | same, saved to WAV | Whisper: **"Archie, what time is it in Rio?"** (3.0 s, verbatim). Alexa answered too. |
| 2B | `VOICE_COMMUNICATION` + `setPrivacySensitive(true)` | Continuous **zero** frames (−120 dBFS); does not block Alexa. Unusable. |
| 3 | `pm disable-user com.amazon.vizzini`, VOICE_RECOGNITION | Whisper: **"Hey Archie, third test with the Lexa off."** Screen stayed quiet: no overlay, no navigation. |
| — | IME dictation (keyboard open + hold mic) | No text arrived (Fire OS 8.1.8.5). Not a usable path. |

Consequences for the design:

- **Every audio source must be `VOICE_RECOGNITION`** on Fire TV. The shared audio policy picks
  `VOICE_COMMUNICATION` on API ≥ the switch level (`core/audio/.../AudioTuning.kt:23`,
  `policy/Policies.kt:29`), which gets silence from the remote. The TV app passes an override (TV-9:
  additive parameter, default unchanged). This applies to WebRTC too (`AndroidRtcPlatform.kt:64`
  `setAudioSource(audio.micSource.androidValue)`).
- **End of utterance = no frame for 350 ms** (frames come every 100 ms of audio while held; the
  probe saw clean starts and ends). `read()` blocks, so the gap is measured by a watchdog, not by
  the reader.
- The remote stops streaming on release, so live voice is **walkie-talkie** (hold to speak).

## 3. Product design

### 3.1 Principles

1. **Readable at 3 m.** Body text ≥ 20 sp equivalent on a 1080p screen (TV type scale below), ≤ 70
   characters per line, generous line height, dark theme only (D3 dark-first; light theme is not
   offered on TV).
2. **Everything reachable with five keys** (D-pad + OK + Back). Menu, Play/Pause, Rewind/FF and the
   mic are accelerators, always shown in the key-hint bar, never the only way to do something —
   except speaking, which needs the mic.
3. **Focus is always visible and never lost.** One focused element on screen at all times;
   `focusRestorer()` on every row/list so coming back lands where you left; initial focus requested
   after first layout.
4. **Voice first, typing last.** Hold the mic button anywhere in the app to talk to Archie; typing
   (the Fire IME) is available but secondary.
5. **Same mental model as the phone/web apps.** Same nouns (Archie, agents, Visuals, Memory,
   Settings), same statuses, same conversation rendering — re-laid-out for a TV, not reinvented.

### 3.2 TV design tokens (derived, not forked)

Colors, shapes and motion come from `apps/design-tokens` through `:core:design`'s `ArchieTheme`
(dark). The TV app adds a **TV type scale** and focus tokens on top, in `:app-tv` (`TvTheme`):

| Role | Size / line (sp) | Use |
|---|---|---|
| displaySmall | 36 / 44 | Screen titles ("Conversations") |
| headlineSmall | 26 / 34 | Hero card title |
| titleLarge | 22 / 28 | Card titles, conversation title |
| titleMedium | 18 / 24 | Row headers |
| bodyLarge | **20 / 30** | Chat and memory text (reading) |
| bodyMedium | 16 / 24 | Previews, secondary text |
| labelLarge | 16 / 20 | Buttons, chips, hints |
| labelMedium | 14 / 18 | Metadata (time, provider) — the minimum |
| code | JetBrains Mono 16 / 24 | Code blocks |

User setting **Text size**: Normal / Large (×1.15) / Extra large (×1.3), scaling the whole scale.

Focus: focused cards scale to 1.05 with a 3 dp `primary` (#b1c7f5) border and a raised surface
(`surfaceContainerHighest`); unfocused cards sit on `surfaceContainer`. Buttons use tv-material
defaults themed with the tokens. No glows or blur (low-end GPU).

### 3.3 Key map

| Key | Everywhere | Conversation | Viewer (web page) | Lists/rows |
|---|---|---|---|---|
| D-pad / OK | move / activate | Up/Down walks blocks; OK expands a tool group / activates a card button | spatial navigation between links; OK = click | move / open |
| **Back** | up one level; closes overlays first; at Home leaves the app | — | browser history back, then close viewer | — |
| **Menu ≡** | options for the screen / focused item | Stop, Compact, Live voice, Type, Rename, New, Close, Delete, Agents | Reload, Pointer mode, Zoom ±, Find in Visuals, Hard reset, Close | Open, Rename, Close, Delete, Duplicate |
| **Hold mic** | talk to Archie (§4) | voice message to this conversation (Archie) / dictation (agent) | voice message to Archie (page stays) | voice message to Archie |
| **Play/Pause** | start/end live voice | start/end live voice | play/pause page media | start/end live voice |
| **Rewind / FF** | — | page up / down (0.8 × viewport) | scroll page up / down | jump to previous / next row |
| long-press OK | — | message actions (rewind, fork, copy-to-composer) | — | same as Menu |

### 3.4 Navigation shell

```
┌──────┬───────────────────────────────────────────────────────────────┐
│ (A)  │  Screen title                          ● Connected  🎙 Idle   │  ← status (top-right)
│  ⌂   │                                                               │
│  💬  │                     content (rows / two-pane)                 │
│  ▦   │                                                               │
│  📖  │                                                               │
│  ⚙   │                                                               │
│      │ 🎤 Hold to talk   ⏯ Live voice   ≡ Options   ◀ Back          │  ← key-hint bar
└──────┴───────────────────────────────────────────────────────────────┘
```

- **Drawer** (left, collapsed 72 dp → expanded 240 dp with labels when focused): Archie mark,
  **Home, Conversations, Visuals, Memory, Settings**. Left from any content's left edge enters the
  drawer; Right/OK leaves it.
- **Status cluster** (top-right, not focusable): connection dot + label (Connected / Reconnecting /
  Offline), voice state chip (Idle · Listening · Thinking · Speaking · Live · On phone).
- **Key-hint bar** (bottom, not focusable): 3–4 hints for the current context.
- **Voice overlay** (bottom-center, above the hint bar): appears while the mic is held (live level
  meter + "Release to send"), while sending, and for the reply in live voice (orb + transcript
  lines). Never steals focus.
- **Toasts** (top-center): "Opened by Archie: <title>", errors.

Navigation 3 back stack keys (mirroring `:app-main`'s `Routes.kt`): `Home`, `Conversations`,
`Conversation(key)`, `Visuals`, `Viewer(target)`, `Memory`, `MemoryDoc(path)`, `Settings`,
`SettingsPage(id)`. Drawer destinations replace the stack root; pushed content (`Viewer`,
`Conversation`, `MemoryDoc`) pushes.

### 3.5 Screens

**Home** — vertical list of rows, each a `LazyRow` with `focusRestorer()`:

1. **Hero: Archie** — the current Archie (orchestrator) conversation: title, state, last two lines
   of the latest reply. Buttons: **Open** (default focus), **Live voice**, **New conversation**.
   Hint: "Hold 🎤 anywhere to talk to Archie".
2. **Open now** — agent sessions in the pool (`GET /api/sessions/pool/live`): card = provider icon,
   title, status (working spinner / idle / needs approval badge), last line. OK opens.
3. **Visuals** — most recently updated visualizations: typographic card (kind color band, title,
   path, "updated 5 min ago"; a live-reload dot if changed since last open).
4. **Memory** — recently modified notes (title, folder, summary line).
5. **Recent conversations** — history (`GET /api/sessions`), newest first.

**Conversations** — two panes. Left (360 dp): search field (IME) + sections **Open now**, **Today**,
**Yesterday**, **Previous 7 days**, **Earlier** (same grouping as the apps), Archie conversations and
agents marked by icon. Right: preview of the focused item (title, provider, working dir, last 6
messages, rendered small). OK opens; Menu → Open, Rename, Duplicate, Close (open ones), Delete,
New agent (provider picker), New Archie conversation.

**Conversation** — full screen (drawer collapsed and hidden):

```
┌──────────────────────────────────────────────────────────────────────┐
│ ◀ Archie · "Plan the TV app"          ◔ 42%   ● Working…   Agents 2 │
│                                                                      │
│        You  ─ Can you open the solar dashboard on the TV?            │
│                                                                      │
│        Archie                                                        │
│        Sure — opening it now.                                        │
│        ▸ 3 steps (open_visualization, read_file, …)                  │
│        Done. It's on screen.                                         │
│                                                                      │
│   ┌ Permission ───────────────────────────────────────────┐          │
│   │ Claude wants to exit plan mode.   [Approve] [Reject]  │          │
│   └───────────────────────────────────────────────────────┘          │
│                                                                      │
│  [🎤 Hold to talk]  [⌨ Type]  [⏯ Live voice]  [■ Stop]  [≡ More]     │
└──────────────────────────────────────────────────────────────────────┘
```

- Transcript column max 760 dp wide, centered; user turns right-aligned in a tinted bubble (as in
  the apps), assistant turns full width.
- **Focus model for reading**: each rendered block (paragraph, list, code block, table, tool group,
  card) is a focusable item in a `LazyColumn`; Up/Down moves block by block; blocks taller than the
  viewport scroll internally in 0.8-viewport steps before focus moves on (so nothing is
  unreachable). Rewind/FF page. Focus on the last block (or the composer) = **follow the live tail**.
- Tool calls: grouped "N steps" (same grouping rule as the apps); OK expands into tool cards
  (`:feature:toolcards` renderers, read-only); OK on a card expands its output.
- Inline cards (permission, agent approval, stall with Interrupt, error, termination) have real
  buttons; on arrival focus does **not** jump (FOCUS-1), but the hint bar shows "▼ Approval
  needed" and the drawer badge lights.
- Composer row: **Hold to talk** (focus target that explains itself; the mic works from anywhere),
  **Type** (opens a full-width text field + Fire IME; OK on IME "Go" sends; queued while a turn
  runs, as in the apps), **Live voice** (Archie only), **Stop** (while working), **More** (= Menu).
- Subagents: "Agents N" chip in the header → side sheet with the turn's subagents (status, last
  line, Stop); OK opens the subagent's transcript read-only.
- Load older: reaching the top block loads the previous page (`before=`).

**Visuals** — grid (4 columns) of visualization cards with search; OK opens the **Viewer**. Menu →
Open, Rename, Copy link (shows a QR code of the URL so a phone can open it).

**Viewer** — full-screen WebView (no chrome). A 3 s top toast shows the title and source ("Opened
by Archie" when pushed). D-pad moves through links/controls (Chromium spatial navigation, on
because the device has no touchscreen); when the focused element can't move further, Up/Down
scroll the page. **Pointer mode** (Menu → Pointer mode, or automatically when a page has no
focusable elements): a virtual cursor moved by the D-pad, OK clicks (`MotionEvent` injection into
the WebView, like TV Bro), edges scroll. Live reload (spec 12 VZ-6) keeps the scroll position.
Back = history back, then close. Media keys go to the page (`HTMLMediaElement.play/pause` via
`evaluateJavascript`; no JS bridge — ArchieWebView's "no `addJavascriptInterface`" rule stands).
Trust: the server's pinned certificate only (`WebTrust`); other https sites via system trust.

**Memory** — two panes: left = tree (folders expand/collapse with OK, search field), right =
document preview; OK opens **MemoryDoc** full screen (same reading/focus model as a conversation;
internal links are focusable and resolve with the shared LNK resolver to Memory/Viewer). "Updated"
cue on live change (`memory_changed`).

**Settings** — list → detail (tv-material `ListItem`s, switches, choice rows; no free text except
server URL):

- **Connection**: server (discovered list + manual URL via IME), status, certificate trust
  (`ServerTrustDialog` flow), reconnect.
- **Remote microphone**: status ("Ready — mic button talks to Archie" / "Alexa also listens —
  run tv_setup.sh to give the mic button to Archie" / "No audio permission"), test meter (hold mic
  → level + playback), "Listen while other apps are open" (Q2, default off).
- **Voice**: live-voice provider/model/voice (server settings, same as the apps' Voice page),
  speaker volume hint, end live voice after N s of silence.
- **Display**: text size, reduce motion, show key hints.
- **TV control**: device name (shown to agents), allow agents to open pages (on), show "Opened by
  Archie" toasts (on), connection state of `/api/tv/ws`.
- **Server** (parity, phase 3): conversation model, agent harness defaults, working dirs, MCP,
  accounts — rendered from `:feature:settings`'s page models as TV rows where they are plain
  choices/toggles; anything needing complex editing shows "Edit on phone or web".
- **About**: versions, server version, licenses.

### 3.6 First run

1. Splash (Archie mark) → **Connect**: auto-discovery (`ServerDiscovery`, existing LAN scan) shows
   found servers as cards; OK connects; "Enter address" opens the IME. TOFU certificate trust
   dialog for the Jetson's self-signed cert (existing flow).
2. **Remote mic** check: asks for RECORD_AUDIO (system dialog works with the D-pad); explains
   "Hold the mic button and say hello" with a live meter; detects Alexa state.
3. Home.

## 4. Voice design

### 4.1 Components (in `:app-tv`, package `voice`)

- `RemoteMicSource` — owns one `AudioRecord(VOICE_RECOGNITION, 16 kHz, mono, PCM16)`, armed while
  the app is started (and, with Q2, while the foreground service runs). Reader thread + watchdog;
  emits `MicBurst.Start`, `Frame(pcm)`, `End(durationMs)`; end = 350 ms without a frame; bursts
  < 250 ms are dropped (accidental taps). Releases the recorder when stopped; re-arms on resume.
  If another client owns the mic (live voice transport), the source hands frames to it instead of
  buffering (single recorder; never two `AudioRecord`s).
- `TvVoiceRouter` — decides where a burst goes:

| State when the burst starts | Destination |
|---|---|
| Live voice active (this device owns it) | the live session (walkie-talkie turn) |
| Screen = agent conversation | **dictation**: transcribe → composer chip "“…” · Send / Edit / Cancel", Send focused |
| Anything else | **voice message to Archie** (current Archie conversation; creates one if none) |

- Voice message = `ClientFrame.SendAudio(base64Wav, "wav")` on the orchestrator WS — the same
  frame the phone's push-to-talk sends (`ProtocolCodec.kt:342`); other devices render it as
  `user_message{source:"voice_message"}`. Max 60 s (`HostTuning.PTT_MAX_MS` if equal; else the TV
  cap is a TV constant).
- Dictation = new endpoint `POST /api/transcribe` (wav → `{text}`; server-side Whisper with the
  server's OpenAI key; 25 s timeout; loopback/LAN like other REST). The TV never holds API keys.

### 4.2 Live voice (realtime conversation through the remote)

- Started with Play/Pause, the hero "Live voice" button, or Menu. Uses `:core:voice` /
  `:core:voice-host` (`AndroidVoiceHost.create(...)`) with a TV `HostConfig`: no wake word, no
  talk word, mic source override **VOICE_RECOGNITION**, speaker = media stream over HDMI.
- The remote only streams while held, so the provider gets **walkie-talkie turns**: while held,
  frames flow; on release the TV sends ~300 ms of silence and then an explicit end-of-turn
  (`input_audio_buffer.commit` + `response.create` for providers that need it, via the existing
  command path), so server VAD never waits on a stalled stream. Transport per provider as in the
  apps (OpenAI WebRTC, Qwen/Gemini WS relay). If WebRTC's audio device module cannot take a
  silence-padded custom source, the TV uses the WS relay for every provider — **decided in WP-T4's
  first spike** and recorded here.
- Ownership: the TV is a normal voice owner (spec 12 §7). "Voice active on another device" shows
  in the status chip and hero card with **Take over**.
- Live voice ends on Play/Pause, Menu → End, Back from the voice overlay, or after N s idle
  (setting, default 120 s).

### 4.3 Alexa coexistence

- With vizzini disabled (TV-3) the mic button is Archie's only. With vizzini enabled the app still
  works, but Alexa answers too; Settings → Remote microphone says so, with the command to fix it.
- `tv_setup.sh --restore-alexa` re-enables it.

### 4.4 Feedback

Mic held → bottom overlay with a live level bar and "Release to send" within 150 ms of the first
frame; release → "Sending…" → the message appears in the conversation; errors (no connection,
transcription failed, too short) are toasts. A soft start/stop cue (existing `VoiceCues` tones)
plays on burst start/end — setting, default on.

### 4.5 Background listening (Q2, behind a setting, default off)

A `microphone`-type foreground service started while the app is foreground keeps `RemoteMicSource`
armed after the user leaves (YouTube etc.). A burst then becomes a voice message to Archie, and an
overlay (`SYSTEM_ALERT_WINDOW`, granted by the setup script) shows "Archie heard: …" and the reply's
first lines for 8 s. Must survive Fire OS's low-memory killer gracefully (it re-arms when the app
next starts). Field test before turning on by default.

## 5. TV control channel

### 5.1 Wire (text JSON frames, like `/api/browser/ws`)

```
tv → {"type":"hello","device_id":"<uuid>","device_name":"Living room TV","client":"archie-tv",
      "version":"1.0.0","capabilities":["open_url","open_visualization",...],"token":"…"?}
be → {"type":"ready"}            |  {"type":"error","error":"…"} then close 4401
be → {"id":"<12hex>","type":"command","command":"open_visualization","params":{...}}
tv → {"id":"…","type":"result","ok":true,"result":{...}} | {"id":"…","type":"result","ok":false,"error":"…"}
tv → {"type":"state","screen":"viewer","url":"…","title":"…","conversation":"<local_id>"?,"voice":"idle"}
tv ↔ {"type":"ping"} / {"type":"pong"}
```

- Hello within 10 s. Optional `TV_CONTROL_TOKEN` (env / `context/.env`): when set, hello must carry
  it (`compare_digest`); when unset, any LAN client may register (the risk is an impostor
  receiving URLs; same-`device_id` replacement caps it).
- `TvHub`: `dict[device_id → conn]`, per-connection pending futures; same `device_id` reconnecting
  replaces the old socket (close 4409); disconnect fails that device's pending commands; late or
  unknown result ids ignored.
- The app connects the control socket whenever it is connected to the server (alongside its one
  orchestrator WS, spec 12 T-6), reconnect backoff as T-13.

### 5.2 Commands (v1)

| Command | Params | TV behaviour | Result |
|---|---|---|---|
| `open_url` | `url` | Internal URLs (LNK-1..7 resolver) open in their in-app view; others in the Viewer | `{screen, url}` |
| `open_visualization` | `path` (root-relative, e.g. `visualizations/x.html`) | Viewer on `<server>/<path>` | `{screen, url, title}` |
| `open_memory` | `path` | MemoryDoc | `{screen, path}` |
| `open_conversation` | `local_id` \| `sdk_session_id` | Conversation (opens/resumes like History) | `{screen, local_id}` |
| `home`, `back`, `reload` | — | as named | `{screen}` |
| `scroll` | `direction: up\|down\|top\|bottom` | Viewer/Conversation/MemoryDoc | `{screen}` |
| `media` | `action: play\|pause\|toggle` | page media in the Viewer | `{playing}` |
| `notify` | `title`, `text`, `seconds?` | toast | `{}` |
| `get_state` | — | — | current state frame |
| `screenshot` (P2) | — | `PixelCopy` of the window → `POST /api/uploads` | `{url}` |

Every command brings the app to the front (it starts its own activity: the app is foreground or
was recently; Fire OS allows it with the `SYSTEM_ALERT_WINDOW` app-op the setup script grants) and
sends `KEEP_AWAKE` to TvServerHub. If the user is in live voice, the voice overlay stays.

### 5.3 Backend endpoints (`backend/api/routes/tv.py`)

- `WS /api/tv/ws` — above.
- `GET /api/tv/status` → `{devices:[{device_id, device_name, version, connected_at, state}]}`.
- `POST /api/tv/command {command, params, device?, timeout?}` — **loopback only** (reuse
  `_is_proxied_from_lan`, moved to a shared helper `backend/api/net.py`); `device` = id or name;
  omitted + exactly one TV → it; none → 503; several → 409 listing names; timeout → 504; TV error →
  422. Returns `{ok, device, command, result}`.
- `POST /api/visualizations/cast` — tries the app first (`open_visualization`), falls back to adb on
  503/504; response gains `via: "app"|"adb"`; `GET …/cast` reports `available` if either route
  works, with `reason` naming it.
- `POST /api/transcribe` — multipart `audio` (wav ≤ 2 MB) → `{text}` (Whisper `whisper-1`, server
  key). For TV dictation; usable by any client.

### 5.4 Scripts, skills, orchestrator

- `shared/scripts/tv_cmd.py` (symlinked into `context/scripts/`): `open <url|/path>`, `viz <path>`,
  `memory <path>`, `conversation <id>`, `home`, `back`, `reload`, `scroll <dir>`, `media <action>`,
  `notify <text>`, `state`, `status`; `--device <name>`; prints JSON; exit codes 0/1/2/3 (ok / TV
  error / no TV / usage). Targets `http://127.0.0.1:8765`.
- `context/memory/ORCHESTRATOR_SCRIPTS.md`: `tv_cmd` entry so the orchestrator can run it.
- `/tv-remote`: "Open a page/visualization" section uses `tv_cmd.py` first; adb stays for launching
  other apps, key events and screenshots. `/create-viz`: show via `tv_cmd.py viz` / the cast
  endpoint; verify with `tv_cmd.py state`. `create_visualization.py --display-tv` → cast endpoint.
  `/tv-dev`: add the Archie TV app (build, deploy, logcat tags, setup script). `/connect-tv`:
  "needed only for adb fallback and development".
- Docs: `docs/devices/fire-tv.md`, `docs/clients/tv.md` (new), `docs/specs/12-client-protocol.md`
  §9.5 "Remote display commands" + the FOCUS-1 exemption, `docs/integrations/visualizations-and-sharing.md`.

## 6. Architecture

### 6.1 Module

```
apps/android/app-tv/                 :app-tv  (com.assistant.archie.tv, minSdk 26, Compose + tv-material)
  src/main/kotlin/com/assistant/archie/tv/
    TvApplication.kt, graph/TvAppGraph.kt      manual DI, like MainAppGraph
    shell/  MainActivity, TvShell (drawer, status, hint bar, overlays), Routes, KeyRouter
    theme/  TvTheme (type scale, focus tokens), TvComponents (cards, rows, focus border)
    home/ conversations/ conversation/ visuals/ viewer/ memory/ settings/ firstrun/
    voice/  RemoteMicSource, TvVoiceRouter, TvVoiceHost glue, VoiceOverlay
    control/ TvControlClient (WS /api/tv/ws), CommandHandler, StateReporter, LauncherPing
  src/main/res/ drawable/banner (320×180 @xhdpi + 640×360 @xxhdpi), icon in drawable/ (Fire OS
    mipmap bug), network_security_config (cleartext for LAN like app-main)
```

- **Dependencies:** `:core:model, protocol, conversation, network, settings, session, audio, voice,
  voice-host, data, design, markdown`, plus `:feature:toolcards` (renderers), `:feature:visuals`
  (`ArchieWebView`, `WebTrust`, `VisualUrls`, `WebViewPool` size 1), `:feature:memory`
  (`MemoryDeps`, link resolver), `:feature:chat` (`ConversationViewModel`, `ChatBackend`,
  `ChatListFlattener`), `:feature:sessions` (`SessionsController`, history sections),
  `:feature:settings` (page models, `ServerTrustDialog` logic). TV UI is its own; phone composables
  are not used.
- **Convention plugin** `archie.android.app.tv` (build-logic): `configureArchieApp(minSdk 26,
  deviceAbis = armeabi-v7a + arm64-v8a, emulatorAbis = x86 + x86_64, signingPrefix = "tv")` +
  `androidResources.ignoreAssetsPatterns` excluding the Vosk model + register in
  `build-logic/convention/build.gradle.kts`; `:app-tv` added to `ArchieTiers.kt` (modern).
- **Catalog:** `androidx.tv:tv-material:1.1.0` (+ `tv-foundation` 1.0.0 only if needed) via
  `gradle/catalog-requests.md`.
- **Additive shared changes** (each with a unit test proving the default is unchanged):
  `core:audio` mic-source override (`MicSourceType` forced by host config);
  `core:voice-host` `HostConfig.tv(...)` (no wake word / talk word); anything else found necessary
  is listed in the PR and reviewed.

### 6.2 Performance budget (AFTKM)

- Release build (R8) for every on-device check; baseline profile for startup + conversation
  scroll; compile with `cmd package compile -m speed-profile` after install.
- Cold start to interactive Home ≤ 2.5 s; D-pad step ≤ 1 frame drop on Home rows; streaming text
  coalesced to ≤ 10 Hz recompositions; markdown parsed incrementally (`MarkdownCache`), never the
  whole message per delta (A300M RenderThread lesson).
- PSS ≤ 220 MB with a conversation open and the Viewer closed; one WebView max (pool size 1), freed
  when the Viewer closes.

## 7. TvServerHub rework

### 7.1 Launcher

- Two cards, centered: **Archie** (launches `com.assistant.archie.tv`; toast "Archie TV is not
  installed" if missing) and **YouTube** (`com.amazon.firetv.youtube/dev.cobalt.app.MainActivity`).
  Animated gradient background stays. `<queries>` for both packages (Android 11 visibility).
- Small **gear icon** top-right (focusable; Up from the right card reaches it). **Menu** (82) and
  **Settings** (176, on remotes that have it) open Settings.
- Drop the in-launcher WebView screen; keep `WebPageViewActivity` as the adb fallback target.

### 7.2 Settings screen (D-pad only, no text fields)

| Setting | Values | Default |
|---|---|---|
| When idle | **Black screen** / Turn display off (sleep) / Do nothing | Black screen |
| Idle time before blanking | 5, 10, **15**, 20, 30, 45, 60 min | 15 min |
| Never blank while media plays | on/off | on |
| Return to launcher when idle | Off, **80 s**, 2, 5, 10 min | 80 s (today's behaviour) |
| Test: blank now | action | — |
| Status rows | key watcher (a11y) on/off, usage access, overlay permission, service running | — |

Values step with Left/Right or OK; Back saves and closes. `SharedPreferences`; the service reads
them every tick (10 s).

### 7.3 Idle engine

```
lastInput     ← AccessibilityService.onKeyEvent (any key, any app; never consumed) + own activities
lastActivity  ← max(lastInput, foreground-change time, media-active time, KEEP_AWAKE pings)
idle = now − lastActivity
return:  enabled && fg ∉ {tvserverhub, archie.tv} && idle ≥ returnAfter  → MainActivity to front
blank:   mode != none && idle ≥ blankAfter && !(mediaActive && neverWhileMedia) && !blanked
         → Black: BlankActivity (black, FLAG_KEEP_SCREEN_ON, immersive; any key → MainActivity; onStop → finish)
         → Sleep: AccessibilityService.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)  [Q1: not field-tested]
```

- `KEEP_AWAKE` = exported broadcast `com.example.tvserverhub.action.KEEP_AWAKE` (optional
  `seconds`), sent by Archie TV on pushed content and every 60 s while voice is live.
- Without the a11y service the engine falls back to foreground-change + media only, and Settings
  shows a warning.
- Fixes on the way: `BootReceiver` component name bug, `initialized` flag never reset, service
  restarted from `onResume` and boot.

## 8. Setup and deploy (`shared/scripts/tv_setup.sh`)

Idempotent, run from the laptop: discovers the TV (MAC), `adb connect`, installs Archie TV +
TvServerHub (+ optional probe), grants `RECORD_AUDIO` / `POST_NOTIFICATIONS`, app-ops
(`SYSTEM_ALERT_WINDOW`, `GET_USAGE_STATS`) for both, enables the TvServerHub key watcher
(`enabled_accessibility_services`, appending, never clobbering), disables `com.amazon.vizzini`
(`--keep-alexa` to skip, `--restore-alexa` to undo), compiles both apps (`speed-profile`), and
prints a verification table. Removes the probe's a11y entry if present.

## 9. Testing and acceptance

**Automated (gates):**
- Backend: `test_api_tv.py` (handshake ± token, ping/junk, status, replace 4409, id correlation,
  timeout, late result, per-device disconnect, targeting/ambiguity/none, LAN → 403),
  extended `test_api_visualizations_cast.py` (app first, no adb call; fallback; probe), transcribe
  endpoint (mocked OpenAI).
- `:app-tv`: JVM tests for `RemoteMicSource` burst detection (fake recorder), `TvVoiceRouter`,
  `CommandHandler` (each command → navigation/state), key routing, link resolution reuse; Roborazzi
  goldens of every screen at 960×540 dp (TV device qualifier) incl. focus states; lint (NewApi,
  TV lint checks: banner, leanback, touchscreen not required).
- `./gradlew check` for the whole project stays green (phone apps untouched).

**On-device acceptance (AFTKM, release build, driven with `adb shell input keyevent` + screenshots,
only when Rodrigo is not watching — check `mCurrentFocus` first):**

| # | Scenario | Pass |
|---|---|---|
| A1 | Launcher shows exactly Archie + YouTube + gear; Menu and gear open Settings | ✓ on screen |
| A2 | Idle 15 min (test with 1 min) → black; any key → launcher; video playing → never black | ✓ |
| A3 | Archie TV cold start → Home with real data from the Jetson in ≤ 2.5 s | timed |
| A4 | Every destination, every row, every list reachable and back with D-pad/OK/Back only | walkthrough |
| A5 | Open a long conversation, read it end to end with D-pad + Rewind/FF; no unreachable text | ✓ |
| A6 | Type a message to an agent with the IME; it streams; approve a permission card | ✓ |
| A7 | `tv_cmd.py viz <x>` from the laptop and the Jetson → Viewer opens; `state` reports it; works while blanked (wakes) | ✓ |
| A8 | Orchestrator voice: "show the X visualization on the TV" → opens via `run_script tv_cmd` | ✓ |
| A9 | Hold mic → voice message reaches Archie; reply appears; Alexa silent | Rodrigo |
| A10 | Live voice: three walkie-talkie turns with the remote | Rodrigo |
| A11 | Memory: open a note, follow an internal link to another note and to a visualization | ✓ |
| A12 | Show on TV from the phone app → opens in Archie TV (`via: app`) | ✓ |

## 10. Work packages and sequencing

| WP | Scope | Depends | Owner |
|---|---|---|---|
| **B1** | `tv.py` hub + endpoints, `net.py` helper, cast preference, `/api/transcribe`, tests, spec 12 §9.5 | — | agent (worktree) |
| **B2** | `tv_cmd.py`, ORCHESTRATOR_SCRIPTS entry, skills (`tv-remote`, `create-viz`, `tv-dev`, `connect-tv`), `create_visualization.py`, `avatar_on_tv.py`, docs | B1 wire | same agent |
| **L1** | TvServerHub: two cards, gear + Menu → Settings, settings screen, idle engine + a11y watcher, BlankActivity, KEEP_AWAKE, fixes | — | agent (TvServerHub repo) |
| **M** | High-fidelity HTML mockups of every TV screen (1920×1080) for Rodrigo's review | spec | agent |
| **T0** | `:app-tv` foundations: plugin, catalog, manifest, banner/icon, TvTheme, graph, shell (drawer/status/hints/toasts), Routes, KeyRouter, first run + connection + trust, goldens | — | agent (worktree) |
| **T1** | Home + Conversations (list, history, preview, actions) | T0 | agent |
| **T2** | Conversation screen (reading focus model, cards, composer + IME, permissions, subagents, menu actions, load older) | T0 | agent |
| **T3** | Visuals grid + Viewer (spatial nav, pointer mode, media, live reload) + Memory + `TvControlClient`/`CommandHandler` | T0, B1 | agent |
| **T4** | Voice: `RemoteMicSource`, router, voice messages, dictation, live voice walkie-talkie, mic-source override, overlay, cues, settings page, (Q2 FGS behind flag) | T0, B1 (transcribe) | agent |
| **T5** | Settings (device + TV control + server subset), About, release build, baseline profile, perf pass | T1–T4 | agent |
| **S** | `tv_setup.sh`, `docs/clients/tv.md`, fire-tv.md, final on-device acceptance | all | coordinator |

Waves (≤ 4 agents at once; one Gradle build at a time through `shared/scripts/heavy.sh`):
**W1** B1+B2, L1, M, T0 → **W2** T1+T2 (one agent), T3, T4 → **W3** T5 + review agents + S.
Each agent works in its own worktree on its own branch off `feat/tv`; the coordinator reviews,
merges into `feat/tv`, runs the gates, and only then deploys. Subagents never run git write
commands in the shared tree.

## 11. Risks

| Risk | Mitigation |
|---|---|
| Fire OS update re-enables vizzini or changes mic routing | Settings shows the live state; setup script is idempotent; probe app kept for re-checks |
| WebRTC ADM can't be fed silence-padded remote audio | WS relay for live voice on TV (decided in T4 spike) |
| Low RAM kills the app/FGS while YouTube runs | No Vosk, one WebView, release build, baseline profile; control socket reconnects on next start; adb cast fallback stays |
| Compose on 32-bit A55 janks | Perf budget §6.2 measured in T5; simplest focus effects; no blur |
| Agents push pages while Rodrigo watches a film | Commands come from his own agents; `notify` before disruptive opens is the skill's guidance; (future: "Do not disturb" toggle) |
| Sleep mode leaves the stick unreachable (Q1) | Not field-tested; Black is the default; Settings warns |
