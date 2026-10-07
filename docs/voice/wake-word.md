---
name: wake-word
category: archie/voice
tags: [wake-word, talk-word, android, vosk, whisper, speech-recognizer, vad, echo-ducking, lollipop, a300m, tuned-constants]
created: 2026-06-10
modified: 2026-10-06
summary: The Android two-layer wake word (Vosk then Whisper), the same-mic talk-word capture, the frozen tuned constants, and the deferred V6 cleanup.
source: curated (consolidated from memory notes assistant/architecture/wakeword_subsystem.md, assistant/plans/wakeword_vosk_v6_deferred_2026_06_10.md, assistant/plans/wakeword_vosk_migration_plan_2026_06_09.md, assistant/plans/wakeword_vosk_migration_log_2026_06_09.md, assistant/architecture/voice_command_device_control.md, assistant/android/android_peripheral_project.md, auto-memory project_wakeword_whisper_confirm_2026_07_21, project_wakeword_turnbased_unified_loop_2026_07_21, project_wakeword_vosk_v6_deferred_2026_06_10, feedback_dont_touch_wake_word_tuning, feedback_dont_shortcut_echo_ducking; verified against code 2026-10-06)
references:
  - architecture.md
  - lifecycle.md
  - openai-realtime.md
  - ../clients/android.md
  - ../devices/devices.md
  - ../operations/debugging.md
  - ../operations/working-rules.md
  - ../projects/frontend-refactor/inventory/04-android-voice-and-device.md
  - ../specs/12-client-protocol.md
---

# Android wake word and talk word

Archie's Android apps listen for two phrases all the time, in a foreground
service. Each phrase does something different:

| Concept | Default phrase | What it does | Settings key (prefs `assistant_service_prefs`) |
|---|---|---|---|
| **talk word** | `my friend` (the A300M uses "hello my friend") | **One-shot voice message.** The command that follows the phrase is captured on the same mic, sent as a single `send_audio` turn to the orchestrator, and answered. No realtime session opens. | `turn_talk_word` |
| **wake word** | `wake up` | **Realtime conversation.** Opens a realtime voice session (see [lifecycle.md](lifecycle.md)). | `realtime_wake_word` |

Each phrase setting may hold several comma-separated variants. They are
lower-cased, trimmed and de-duplicated, with no phonetic expansion
(`PhraseMatcher.parseVariants`). Other settings: `wake_word_enabled`,
`wake_word_mic_gain` (the RMS gate is divided by it) and
`talk_silence_sensitivity` (K, default 2.0).

The code lives in the shared Android modules, so both apps can run it:

| Module | Contents |
|---|---|
| `apps/android/core/wakeword` | `WakeTuning.kt` (every tuned value), `loop/WakeLoop.kt` (the detector loop), `policy/` (`ActivityGate`, `PreBuffer`, `AdaptiveTalkVad`, `PhraseMatcher`, `WhisperGateDecision`, `WakeRearmPolicy`, SR policies), `whisper/WhisperClient.kt`, `vosk/` (model extraction), `sr/AndroidSpeechRecognizerRunner.kt` (fallback), `cpp/vosk_stderr_shim.c`, assets `vosk-model-small-en-us-0.15/` |
| `apps/android/core/voice-host` | `VoiceHostService` (microphone foreground service), `wake/WakeService.kt` (`DefaultWakeServiceController`: start dedupe, pause/resume for voice, screen re-arm, sticky restart), `runtime/VoiceHostRuntime.kt` (routes wake events to voice or `send_audio`), `HostTuning.kt`, cues |
| `apps/android/core/audio` | mic source policy, PCM utils, `DrainEchoDucker` |
| `apps/android/app-lite` | the A300M "face" app (`com.assistant.peripheral`); carries the patched `libvosk.so` in `src/main/jniLibs/armeabi-v7a/` |
| `apps/android/app-main` | `MainVoiceHost` (main phone app `com.assistant.archie`, minSdk 26, no Lollipop shim) |
| `apps/android/tools/native/` | `patch_vosk_weaken.py`, `verify_vosk_patch.py` |

The full behavioural spec this code was rewritten against, with every
constant, every state machine and the 46 regression scenarios, is
[inventory 04](../projects/frontend-refactor/inventory/04-android-voice-and-device.md).
This doc is the short version.

## The pipeline

```
AudioRecord 16 kHz mono PCM16 (VOICE_RECOGNITION below API 24, VOICE_COMMUNICATION from 24)
  │ one coroutine owns the mic and the recognizer from arm to send (WakeLoop)
  ▼
MONITORING  ── RMS ≥ 70/gain for ≥ 30 ms ──►  RECOGNIZING (Vosk, same mic)
  500 ms rolling pre-buffer                    pre-buffer replayed first, then 400 ms reads,
                                               5 s window; grammar = phrases + "[unk]"
                                                 │ full match (wake first)  or  talk prefix (≥ 2 words)
                                                 ▼
                                               MATCH_TAIL (+400 ms so Whisper hears the whole phrase)
                       ┌─────────────────────────┴─────────────────────────┐
                 wake ("wake up")                                   talk ("hello my friend …")
   CONFIRMING_WAKE: trailing ≤ 2 s clip                 TALK_PRE_ONSET → TALK_CAPTURING (same mic,
   peak RMS < 30 → reject, no Whisper call              adaptive VAD, 200 ms frames)
   else Confirming → Whisper (≤ 10 s, fail-closed)       → CONFIRMING_TALK: speech floor → Whisper
   → WakeDetected → realtime voice session                → TalkMessageCaptured(wav) → send_audio
                       └─────────────── COOLDOWN 3 s (mic held), then re-arm ──────┘
   Vosk window with no match → re-arm after 500 ms (never backed off)
```

`WakePhase` has explicit states: `STOPPED, ACQUIRING_MIC, MONITORING,
RECOGNIZING, MATCH_TAIL, CONFIRMING_WAKE, TALK_PRE_ONSET, TALK_CAPTURING,
CONFIRMING_TALK, SR_CYCLE, COOLDOWN, PAUSED`. The old detector reported `Idle`
during confirm, capture and cooldown. The new phases let the host tell a busy
loop from an idle one: a screen-on re-arm **never restarts an engine that is
confirming or capturing** (`WakeService.kt`, risk R3).

The loop reports to the host through `WakeLoopEvent`, which replaced the old
LocalBroadcasts:

| Event | When | Host reaction |
|---|---|---|
| `Confirming(isRealtime)` | wake path only, right before the Whisper call | transient "confirming…" indicator |
| `WakeDetected` | wake confirmed | wake cue, bring the app to the front, start realtime voice |
| `TalkDetected` | talk: sustained speech onset (UI acknowledgement only) | talk cue + recording UI |
| `TalkMessageCaptured(wav)` | talk confirmed; capture ended on silence | send `send_audio{audio, format:"wav"}` on the orchestrator WS |
| `ConfirmFailed(isRealtime)` | phantom, speech-floor reject, Whisper reject, timeout | clear the confirming and recording indicators |
| `MicUnavailable` / `MicAvailable` | 8 failed mic opens / first success after that | notification text "Wake word stalled — mic held by another app" |
| `RecognizerUnhealthy` | SpeechRecognizer fallback: 8 NO_SPEECH in a row | rebuild the engine (subject to the 3 s start dedupe) |

## Layer 1: Vosk (fast and permissive)

- Library `com.alphacephei:vosk-android:0.3.47` with the model
  `vosk-model-small-en-us-0.15` (~68 MB extracted). The model is bundled
  uncompressed (`noCompress`, because `Model(path)` needs raw files) and
  extracted to `filesDir/vosk-model`. This is the same directory the old app
  used, so the lite upgrade does not extract it again.
- Constrained grammar: the configured phrases plus `"[unk]"`. Without `[unk]`
  the recognizer hangs. The wake (realtime) match wins over the talk match,
  using substring `contains`. The recognizer is reset after a match so it
  can't fire again and again.
- **There is no confidence floor, on purpose.** Vosk is allowed to be noisy;
  Whisper filters out the false positives.
- **Talk-prefix trigger.** In grammar mode Vosk cannot decode a multi-word
  phrase that runs straight into out-of-grammar words: in "hello my friend,
  turn off the lamps" it stalls after "hello". So the talk path fires as soon
  as the partial result matches the first **≥ 2 words** of a talk variant
  (`MIN_PREFIX_WORDS`). Whisper then checks the whole utterance. A lone
  "hello" from noise does not fire. The wake word is never triggered by a
  prefix.
- Memory: the model takes ~80–100 MB of RAM and runs at about 0.3× real time
  on the A300M's Cortex-A53. It loads in ~3.75 s.

### Lollipop polyfill (API < 23, A300M only)

Vosk's prebuilt `libvosk.so` is linked with `BIND_NOW` and imports
`stderr/stdin/stdout` as global symbols. Bionic before API 23 does not
export these (they were macros over `__sF[]`), so `dlopen` fails with
`cannot locate symbol "stderr"`. The fix has three parts, and all three are
needed:

1. `tools/native/patch_vosk_weaken.py` rewrites those `.dynsym` entries from
   `STB_GLOBAL` to `STB_WEAK`. The patched library ships in
   `app-lite/src/main/jniLibs/armeabi-v7a/`, and Gradle prefers it over the
   copy in the AAR. **Run the script again after any Vosk version bump.**
2. `core/wakeword/src/main/cpp/vosk_stderr_shim.c` exports real
   `stderr/stdin/stdout` pointers backed by `__sF[]`, so `fprintf(stderr, …)`
   inside Vosk does not crash.
3. The shim is loaded with `RTLD_GLOBAL` before Vosk
   (`VOSK_SHIM_MAX_SDK_EXCLUSIVE = 23`). NDK is pinned to `26.1.10909125`.

## Layer 2: Whisper confirmation (accurate, fail-closed)

`WhisperClient` posts the flagged clip to
`https://api.openai.com/v1/audio/transcriptions` **directly from the device**
(no backend proxy, for latency): `model=whisper-1`, `language=en`,
`response_format=json`, **`temperature=0`**, file `wake.wav`. The device has no
OpenAI key of its own. It fetches the backend's key from
`GET /api/config/openai-key` (`backend/api/routes/config.py`, read from
`OPENAI_API_KEY` / `context/.env`) and caches it. The trust model is the same
as the ephemeral-token route, LAN only. A 401 clears the cached key.

- **Speech-floor pre-gate.** If the clip's loudest 200 ms frame is below
  `SPEECH_FLOOR_RMS = 30`, the clip is rejected without calling Whisper. On
  the A300M the background floor is RMS 4–11 and a real "wake up" peaks at
  35–82. This keeps silence away from whisper-1, which invents canned
  phrases on silent audio.
- **Decision** (`WhisperGateDecision`): normalize the text (lower-case,
  non-alphanumerics to spaces, collapse whitespace), so "Hello, my friend."
  matches "hello my friend". Then substring-match with wake taking
  precedence. If the *whole* normalized transcript is one of whisper-1's
  silence outputs ("you", "thank you", "thanks for watching", "bye", "so",
  "okay", … see `WHISPER_HALLUCINATION_BOILERPLATE`), reject it.
- **Fail-closed.** A timeout, error, missing key or 401 counts as a reject. A
  real wake is lost when the network is down; that trade was chosen for zero
  false positives. Wake-word reliability therefore also depends on Wi-Fi and on
  the backend being reachable.
- Timeout 10 s. A real A300M round trip is about 3.7 s (key 45 ms, WAV 230 ms,
  HTTP about 3.4 s). The old 2.5 s timeout failed on about 15 of 17 attempts.
- Wake path: only the trailing **≤ 2 s** is uploaded (`MAX_CONFIRM_WINDOW_MS`).
  Talk path: the whole "phrase + command" utterance is uploaded.
- Parity quirk, kept from the old code and flagged for Rodrigo's review: when
  no Whisper client is configured (no server URL), matches fire *unconfirmed*
  and skip the speech floor. The SpeechRecognizer fallback has no PCM, so it
  also fires without Whisper.

## Talk-word capture (same mic, auto-send on silence)

After a talk trigger the loop keeps reading **the same `AudioRecord`**. There
is no hand-off to another recorder, so "my friend" and the command are
captured without a gap. `AdaptiveTalkVad` (200 ms frames):

- A frame counts as voice if `rms ≥ max(noiseFloor × K, 30)`. The noise floor
  is tracked fast down and slow up (`SILENCE_FLOOR_ATTACK = 0.30`,
  `SILENCE_FLOOR_RELEASE = 0.02`), starting from the quietest pre-roll frame.
  K is `talk_silence_sensitivity` (slider 1–4, default 2.0). This single
  adaptive threshold replaced an earlier two-threshold hysteresis, which ran
  away in loud rooms because ambient noise above the low threshold kept the
  silence timer from ever expiring. Commit `4f689a4` added the adaptive
  floor, so capture also ends in quiet rooms where AGC lifts the idle RMS to
  about 150–200.
- **Deferred-onset UI**: the recording UI (`TalkDetected`) appears only after
  300 ms of sustained voice (`ONSET_SUSTAIN_MS`). A phantom trigger shows
  nothing.
- No sustained onset within 4 s (`COMMAND_SPEECH_ONSET_TIMEOUT_MS`) means a
  phantom: abort silently.
- The capture ends `COMMAND_SILENCE_MS = 1000` ms after the last voice frame.
  That is the whole end-of-utterance wait, with no hidden padding. The hard
  cap is 12 s (`COMMAND_MAX_MS`).
- After confirmation the WAV goes out as one `send_audio` on the orchestrator
  WS. The server answers it with the audio-input chat model (`gpt-audio`, see
  [openai-realtime.md](openai-realtime.md)). Other devices see it as a "Voice
  message" (spec 12 §6.16 VM-1).

## Hand-off to realtime voice, and re-arm

- On `WakeDetected` the session controller asks the host to pause the wake
  loop (`WakeHandoff.pauseWake()`). That returns a deferred which completes
  once the `AudioRecord` is really released. The session waits for it for at
  most 2 s (`WAKE_WORD_ACK_TIMEOUT_MS`), then sends `voice_start`. The HAL then
  settles for 200 ms before the call mic opens.
- When voice ends (normally, by error, or on a **terminal** WS drop with
  `willReconnect=false`), the session finalizes. It re-arms the wake word
  **1500 ms** later (`MIC_RELEASE_DELAY_MS`), because WebRTC holds the
  `AudioRecord` after `stop()` and an earlier re-arm retries 20+ times. A
  conversation switch waits 10 s instead. Resuming after voice always fully
  restarts the engine, because the mic may have been held by WebRTC. A
  transient okhttp-ping drop keeps the live session; don't finalize on every
  disconnect.
- On the old app, an unclean drop (the user closes the conversation, or the
  screen dims) never finalized. That left the detector paused forever: "wake
  up worked once then stopped", fixed in `9db8f37`.
- A Vosk window with no match is the *normal* idle outcome: a cough, music,
  or the assistant's own reply. It re-arms after a flat 500 ms
  (`POST_VOSK_NOMATCH_MS`) and resets the miss counter. When NoMatch was fed
  into the SpeechRecognizer backoff, the delay reached 30 s after about 5
  windows and real wake words fell into the dead time (`043340a`).
- Service robustness (`DefaultWakeServiceController`): a second
  `startWakeWord` with the same (talk, wake, gain) key within 3 s is dropped
  as an intent redelivery. A sticky restart restores the config from prefs.
  SCREEN_ON and USER_PRESENT re-arm with a 300 ms debounce. A recents-key long
  press (600 ms) is a trigger too.

## Voice to device control

The talk word is the cheap path to act on the physical world. "Hello my
friend, turn off the office lamps" is captured and confirmed. One audio turn
reaches the orchestrator, which calls `run_script` (for example the lamps
script, allowlisted in `ORCHESTRATOR_SCRIPTS.md`) and answers. **No realtime
session opens.** The same `run_script` tool also works inside a live realtime
conversation started by the wake word. Both paths were first proven end to
end on 2026-07-22 with the Tuya lamps on the LAN. See
[architecture.md](architecture.md) and
[orchestrator](../architecture/orchestrator.md).

## Tuned constants (frozen)

All values are in `core/wakeword/.../WakeTuning.kt`, `core/voice-host/.../HostTuning.kt`,
`core/voice/.../VoiceTuning.kt` and `core/audio/.../AudioTuning.kt`. They were
ported **verbatim** from the old app at `e871d05` and are pinned by parity
tests (`WakeTuningTest`, `VoskTuningTest`, `SrTuningTest`, `WhisperTuningTest`,
`ServiceTuningTest`, `OldConstantsCrossCheckTest` against
`tools/parity/old_constants.json`).

| Constant | Value | Why (origin) |
|---|---|---|
| `RMS_THRESHOLD` (÷ wake gain) | 70.0 | A300M "wake up" peaks at 35–82; 200 was unreachable (`c60cd08`) |
| `ACTIVITY_HOLD_MS` | 30 | ignore clicks and pops |
| `PRE_BUFFER_MS` | 500 | keeps the leading "wake" (5/5 first try on the A300M) |
| `MIC_RETRY_MS` / `MIC_RETRY_WARN_THRESHOLD` | 500 / 8 | mic held by WebRTC or push-to-talk; notification after about 4 s |
| `POST_WAKEWORD_DELAY_MS` | 3000 | cooldown after any match, so a used utterance can't fire again |
| `POST_VOSK_NOMATCH_MS` | 500 | never back off on NoMatch (`043340a`) |
| `POST_RECOGNITION_BASE_MS` / `_MAX_MS` | 1000 / 30000 | SpeechRecognizer path only: 1, 2, 4, 8, 16, 30 s |
| `SPEECH_FLOOR_RMS`, `VOSK_RMS_STARTED_THRESHOLD`, `COMMAND_ABS_SPEECH_FLOOR` | 30.0 | phantom matches on silence (`70283e3`, `ae1d958`) |
| `SILENCE_FLOOR_ATTACK` / `_RELEASE` | 0.30 / 0.02 | adaptive talk floor (`4f689a4`) |
| `DEFAULT_TALK_SILENCE_SENSITIVITY` | 2.0 | speech ≥ 2× the room floor |
| `ONSET_SUSTAIN_MS` | 300 | no recording UI on a single music spike |
| `COMMAND_SILENCE_MS` | 1000 | field-tuned: 1500 → 1200 → 1000 |
| `COMMAND_MAX_MS` | 12000 | was 30000 |
| `COMMAND_SPEECH_ONSET_TIMEOUT_MS` | 4000 | a phantom aborts silently |
| `VOSK_RECOGNITION_TIMEOUT_MS` / read size | 5000 / 400 ms | |
| `MATCH_TAIL_MS` / `MAX_CONFIRM_WINDOW_MS` | 400 / 2000 | Vosk fires mid-word; long uploads were slow |
| `MIN_PREFIX_WORDS` | 2 | |
| `WHISPER_TIMEOUT_MS` | 10000 | real round trip about 3.7 s (`2f5ecd7`) |
| `WAKE_START_DEDUPE_WINDOW_MS` | 3000 (strict `<`, monotonic clock) | intent redelivery (`0b2cbb5`) |
| `SCREEN_REARM_DEBOUNCE_MS` / `FOREGROUND_WAKE_LOCK_MS` | 300 / 3000 | |
| `MIC_RELEASE_DELAY_MS` / `WAKE_WORD_ACK_TIMEOUT_MS` | 1500 / 2000 | wake re-arm after voice; hand-off ack |
| `HAL_SETTLE_MS` | 200 | wake mic released → call mic opened (`55037c2`) |
| Mic source | `VOICE_RECOGNITION` < API 24, `VOICE_COMMUNICATION` ≥ 24 | wake and call must use the same source, or the Samsung HAL's AGC halves the call's mic level (`55037c2`) |
| SR fallback: `SR_HANG_WATCHDOG_MS`, `SR_REFRESH_AFTER_N`, `SR_REFRESH_NO_SPEECH_SPIKE`, `SR_NO_SPEECH_HEALTH_THRESHOLD`, `SR_CLIENT_ERROR_DELAY_MS` | 10000, 20, 2, 8, 1000 | old SpeechRecognizer safeguards |

## Rules

- **The tuning is a fragile equilibrium. Treat it as read-only.** On
  2026-06-06 a "logical" retune (RMS threshold 200 → 100, plus a watchdog and
  a dedupe) made detection "very very difficult". It was reverted
  (`2acf57c`, `51ba2e5`). The rest of the pipeline had been balanced around the
  old values. If a change is really needed, propose it on its own, name every
  constant you would touch, and wait for Rodrigo's go-ahead. Never bundle it
  with other voice work.
- **The realtime "wake up" path is the reference for "works perfectly".** Talk
  path changes must not regress it.
- **Echo ducking is drain-then-restore. Don't shortcut it.** While the
  assistant speaks, the mic gain is ducked to 5%. It is restored only after the
  speaker buffer has drained (written frames quiet for 400 ms, and the play head
  caught up or stuck for 400 ms), plus a 1000 ms tail (`DrainEchoDucker`,
  `AudioTuning.MIC_RESTORE_*`). There is no drain timeout. Never propose
  restoring the gain early when raw-mic VAD hears the user; the assistant
  hearing itself was a recurring problem, and this is what solved it. A
  "user can't barge in quickly" freeze is the accepted cost; look for fixes
  elsewhere (UI cues, shorter answers).
- **Keep the SpeechRecognizer fallback (V6 is deferred).** The loop uses Vosk
  when the model loads, otherwise `SpeechRecognizer` (`WakeLoop.resolveEngine`,
  `EngineKind`). Removing the fallback ("V6": delete the SR runner and its
  policies, `SrTuning*` constants and the NO_SPEECH health path) waits until
  **both** of these are true: Vosk is confirmed on all of Rodrigo's daily
  Android devices, and there has been a few weeks of stable running.
  Otherwise a device where Vosk fails to load would silently have no wake
  word. Don't delete it as part of a general "cleanup". If another engine is
  added by then, generalise the engine port instead of collapsing to one.
  Status 2026-10-06: Vosk is field-validated on the A300M and the Xiaomi/POCO.
  Check with Rodrigo before acting.
- **Diagnose from real logcat** before patching. Every "same symptom" in the
  2026-07-21 rework turned out to have a different root cause. The loop keeps
  the old log tags (`WakeWordDetector`, `VoskRecogEngine`, `WhisperConfirmer`)
  and lines (`Vosk match`, `talk-prefix trigger`, `Whisper CONFIRMED`,
  `Pre-Whisper gate`, `cmd rms=… floor=… voice≥…`) so old grep patterns still
  work. See [debugging](../operations/debugging.md).
- A300M specifics: 888 MB real RAM, and the logcat ring buffer only holds
  about 20 min, so pull logs early. `STREAM_NOTIFICATION` is muted during call
  audio, so every cue plays on `STREAM_MUSIC`. AudioFlinger "BUFFER TIMEOUT"
  warnings during drain windows are normal. See [devices](../devices/devices.md).

## History

- **Until 2026-06:** a two-stage RMS gate → Google `SpeechRecognizer`. The
  2026-06-09 refactor (Inc 1–9 plus detours) added the `WakeWordState` FSM,
  the `CompletableDeferred` pause/resume hand-off, the 3 s start dedupe, the
  NO_SPEECH health check, the mic-unavailable notification and the
  `wakeWord`/`voiceWord` → `talkWord`/`wakeWord` rename (`d226027`). Field tests
  showed SpeechRecognizer's 0.8–3 s IPC bind latency on Lollipop cut off the
  start of every phrase.
- **2026-06-10, Vosk migration** (V1 `482a311`, V2 `ddfb53f`, V3–V5): on-device
  Vosk with the 500 ms pre-buffer, about 500 ms detection, 5/5 first try. V6
  was deferred.
- **2026-07-21, two-layer rework** (`dd5567f`, `54463e1`, `70283e3`, `ae1d958`,
  `2f5ecd7`, `b710c8b`, `3bee23a`, `3efbe55`, `f934d09`, `9db8f37`; then
  `043340a` on 07-22 and the adaptive floor `4f689a4`): the Whisper confirm
  gate, removal of the confidence floor, the talk prefix trigger, same-mic
  capture with auto-send, the speech-floor gate, the re-arm after unclean
  drops, and the NoMatch backoff fix. Field-validated by Rodrigo ("all
  working as I intended").
- **2026-10 rewrite:** the stack was rewritten from scratch into
  `apps/android/core/{wakeword,voice-host,voice,audio}` against inventory 04,
  with explicit phases and one coroutine owning the mic. The lite app replaced
  the old peripheral app on the A300M in place on 2026-10-05. The old code is
  in `legacy/android/`.
