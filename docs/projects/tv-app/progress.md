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

## WP → commit

| WP | Branch | Commit | State |
|----|--------|--------|-------|
| probe | feat/tv | a6fe015e | done |
| spec | feat/tv | 93b2cdfb | draft v1 |
| B1+B2 | feat/tv-backend | — | running |
| L1 | TvServerHub repo | — | running |
| M | feat/tv | — | running |
| T0 | feat/tv-app | — | running |

## Open issues

- Q1 Sleep mode untested on Rodrigo's monitor setup (by design).
- Q2 background listening behind a setting, default off.
- Keep vizzini disabled long-term? Confirm with Rodrigo at handoff.
