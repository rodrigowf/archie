# Archie TV — progress log

## 2026-10-16

- Research: 4 parallel read-only agents (Android reuse, TV UX + Fire TV mic, TvServerHub, backend
  channel). Fire TV reached at `192.168.0.15:5555` (docs said `.16`).
- Probe `:app-tvprobe` (`a6fe015e`): the remote mic streams into `AudioRecord(VOICE_RECOGNITION)`
  while the Alexa button is held; Whisper transcribed both test utterances verbatim. With
  `com.amazon.vizzini` disabled, Alexa stays quiet and the audio still arrives. The Alexa key never
  reaches apps; an AccessibilityService sees every other remote key in every app.
- TV state after the probe: `com.amazon.vizzini` **disabled-user**; `enabled_accessibility_services`
  = `com.assistant.tvprobe/.KeyWatchService` (to be replaced by TvServerHub's watcher in L1);
  `com.assistant.tvprobe` installed.
- TvServerHub baseline committed (`d7a771e`, its own repo) before the rework.
- Spec 15 draft v1 (`93b2cdfb`).
- Wave 1 started: B1+B2 (backend, `feat/tv-backend`), L1 (TvServerHub), M (mockups), T0 (`feat/tv-app`).

- Wave 1 done: backend B1+B2 (`b2b55daa`, 314 targeted tests; full suite green except the
  worktree-name test), mockups (`b15e0b19`, published privately for review), T0 (`74cdf842`),
  TvServerHub v2.0 (`dca0ee1` in its repo). Coordinator seams on feat/tv: `TvNavHolder`,
  `VoiceOverlayHost` slot, `LauncherPing`.
- Launcher verified on the TV by the coordinator (A1, A2): two cards, gear reachable with Up,
  Menu → Settings with the right defaults, 60 s debug idle → pure black (`BlankActivity`), OK →
  launcher without launching a card. Key watcher enabled (`enabled_accessibility_services =
  com.example.tvserverhub/.KeyWatchService`). Cosmetic: the key-watcher status row shows its adb
  hint even when On.
- Rodrigo approved agents driving the TV for tests (2026-10-16); `~/assistant-wt/tv-free.sh`
  decides FREE/BUSY (real audio or human input in the last 10 min) and agents `--mark` their end.
- Wave 2 started: T1+T2 (`feat/tv-ui`), T3 (`feat/tv-viewer`), T4 (`feat/tv-voice`).

- TvServerHub self-heals its key watcher (`edbe3fe`): Android drops an app's accessibility
  services when the app is force-stopped (verified: `pkg:android` wrote `null`); the monitor
  service re-adds it with WRITE_SECURE_SETTINGS granted by `tv_setup.sh` (`3355bf46`).
- Wave 2 done and merged: T3 (`acca129c`), T4 (`90bd5883`, live-voice transport decision in spec
  §4.2), T1+T2 (`4e00161d` merge). Integration gate green: `:app-tv:check :app-main:assembleDebug
  :app-lite:assembleDebug`. On-device (debug injection / local backend): tv_cmd viz/memory/state
  /home/notify, voice message → new Archie conversation answered, dictation chip, live voice on
  OpenAI WebRTC and Qwen relay, Home/Conversations/Conversation against the Jetson.
- Test leftovers: two test Archie conversations in context (`8994871d…`, `a55f6b1c…`), from T4.
- Wave 3 started: T5 (`feat/tv-t5`: settings, release + perf, polish), two read-only reviews
  (shared voice/control/backend; TV UI).

## WP → commit

| WP | Branch | Commit | State |
|----|--------|--------|-------|
| probe | feat/tv | a6fe015e | done |
| spec | feat/tv | 93b2cdfb | draft v1 |
| B1+B2 | feat/tv-backend | b2b55daa | merged |
| L1 | TvServerHub repo | dca0ee1 | done, A1/A2 verified on TV |
| M | feat/tv | b15e0b19 | published (artifact) |
| T0 | feat/tv-app | 74cdf842 | merged |
| T1+T2 | feat/tv-ui | 4e00161d (merge) | merged |
| T3 | feat/tv-viewer | acca129c | merged |
| T4 | feat/tv-voice | 90bd5883 | merged |
| S (setup) | feat/tv | 3355bf46 | done |
| T5 | feat/tv-t5 | — | running |
| Reviews | — | — | running |

## Open issues

- Q1 Sleep mode untested on Rodrigo's monitor setup (by design).
- Q2 background listening behind a setting, default off.
- Keep vizzini disabled long-term? Confirm with Rodrigo at handoff.
