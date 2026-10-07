---
name: wakeword_vosk_migration_plan_2026_06_09
category: archie/history
tags: [wake-word, android, vosk, on-device-stt, refactor, plan, leading-edge-clipping]
created: 2026-06-09
modified: 2026-10-05
summary: Plan to replace Android's SpeechRecognizer with on-device Vosk STT in the wake-word path, eliminating the SpeechRecognizer-bind-latency clipping that left the first attempt of every wake-word session failing on Lollipop. Companion plan to wakeword_subsystem_refactor_plan_2026_06_09.md (which is complete).
source: curated (post-Detour-6 field-testing session on A300M, 2026-06-09)
references:
  - ../../overview/repo-layout.md
---

# Wake-word Vosk migration plan

> **Repo layout note (2026-10-05):** this is a dated record of work on the **old** Android app; its `android/app/...` paths now live under `legacy/android/app/...`. The current Android code is the multi-module `apps/android/` project (Vosk/Whisper wake word in `apps/android/core/wakeword`, tuned constants ported verbatim). See [repo_layout_cutover_2026_10.md](../../overview/repo-layout.md).

> **⚠️ SUPERSEDED IN PART (2026-07-21).** Vosk shipped and is now only the *first layer* of a **two-layer detector** — a fast permissive Vosk pass followed by an **OpenAI Whisper (`whisper-1`) confirmation gate called directly from Android** (fail-closed). This reverses this plan's §2.6/§11 rejection of cloud/Whisper (the "on-device, no API key, offline" criteria were relaxed: Whisper is now the accuracy layer, Vosk the cheap trigger). The talk-command path was also reworked (unified same-mic loop, deferred-onset single-threshold VAD, `captureTalkCommand` auto-send). Current architecture: [wakeword_subsystem.md](../../voice/wake-word.md) → "Two-layer detector" section. This plan is kept for the Vosk on-device design + Lollipop polyfill, which are still accurate.

## Status board

| # | Title | Status | Notes |
|---|---|---|---|
| V1 | Vosk dependency + bundled model + ABI filters | ✅ `482a311` | dependency `com.alphacephei:vosk-android:0.3.47`, model `vosk-model-small-en-us-0.15` (~68 MB extracted, +46 MB APK), ABI filters `armeabi-v7a + arm64-v8a`, `noCompress` for the model directory. |
| V2 | `VoskModelLoader` singleton + Lollipop polyfill | ✅ `ddfb53f` | extract from assets, cache `Model`, mutex-guarded; ABI-specific patched `libvosk.so` (WEAK stderr/stdin/stdout) + `libvosk-stderr-shim.so` polyfill, gated `SDK_INT < 23`. |
| V3a | `WakeWordRecognitionEngine` interface + `SpeechRecognizerEngine` extract | 🟡 local | Pure refactor — SR machinery (Inc 4 watchdog, Inc 8 NO_SPEECH, Detour 6 warm/refresh, beep mute, audio mode) moves into a dedicated engine class behind a small interface. `WakeWordDetector` orchestrates only. 69/69 parity tests green. |
| V3b | `VoskRecognitionEngine` + engine selection + leading-edge pre-buffer | 🟡 local | Vosk engine reads from the shared `AudioRecord`. Selection at first `warm()` — Vosk if `VoskModelLoader.getModel()` succeeds, else SR fallback (escape hatch). Silence monitor maintains a 500 ms rolling pre-buffer, replayed into Vosk first so the user's leading edge isn't lost. **A300M field-tested 5/5 first-attempt success at ~500 ms latency.** |
| V4 | V4a (keep RMS gate) vs V4b (continuous) | 🟡 local | **Picked V4a** — RMS gate stays. Vosk only runs after activity detection. Battery-friendly; field-tested working perfectly with pre-buffer. |
| V5 | Repurpose Inc 8 health check | 🟡 local | NO_SPEECH watchdog now lives inside `SpeechRecognizerEngine` (V3a extraction). No Vosk-side analog added — Vosk doesn't have SR's Lollipop binder-death failure mode that motivated the watchdog. If a Vosk health signal becomes needed, surface during V6 follow-up. |
| V6 | Cleanup + delete `SpeechRecognizerEngine` | ⏸ deferred | **Parked 2026-06-10**: SR engine stays as fallback until Vosk is confirmed on all Rodrigo's Android devices (only A300M tested so far). Spec captured in [`wakeword_vosk_v6_deferred_2026_06_10.md`](../../voice/wake-word.md). |

See [`wakeword_vosk_migration_log_2026_06_09.md`](log.md) for the per-commit narrative.

---

**Why this plan exists**: The 10-increment wake-word refactor plan (`wakeword_subsystem_refactor_plan_2026_06_09.md`) shipped successfully (Inc 1–9 + Detours 3, 5, 6 on branch `voice-wakeword-refactor`). Field testing post-Detour-6 revealed that the **first-attempt-fails / second-attempt-works pattern persists** because Android's `SpeechRecognizer` takes 800ms–3s between `startListening()` and `onReadyForSpeech` on Lollipop — the user's "wake up" finishes before Google's STT IPC is even ready to receive audio.

The pre-warming approach (Detour 6) construction-time only ~100ms savings. The bottleneck is **Google's STT IPC bind**, not construction. Pre-warming doesn't help because we can't keep `SpeechRecognizer` in a "listening" state ahead of activity detection — it always opens its own mic when `startListening` is called.

**The user explicitly rejected** the only workaround that would fix this within `SpeechRecognizer`: reintroducing the `wordSubs` phonetic-variant table to match partial captures ("don't want hacky"). And the user picked the right architectural answer: **buffer the audio from the trigger moment and feed it to STT directly**.

`SpeechRecognizer` has no `feed(bytes)` API. So we must switch to an STT that accepts PCM directly. The constraints chosen by the user are:

1. **Open source.** Apache 2.0 or similar.
2. **Runs on-device.** No server roundtrip, no API keys, no accounts.
3. **Works offline.** A300M sometimes has shaky wifi.
4. **No account / no cloud signup.** Explicit user requirement.
5. **Performance on A300M (Cortex-A53 @ 1.2 GHz, 1 GB RAM, Lollipop API 22).** Must process audio in real-time on this exact hardware.

**Vosk** is the only mainstream option that satisfies all five. This plan migrates the wake-word recognition stage from `SpeechRecognizer` to Vosk while preserving everything Inc 1–9 + Detours 3/5/6 built.

---

## 0. Working agreement (mandatory)

All of plan §0 from `wakeword_subsystem_refactor_plan_2026_06_09.md` applies here verbatim. Specifically:

- **§0.1 Source-fidelity rule**: read the production source at HEAD before writing any new code; cite line refs with a HEAD sha; document tuned behavior to preserve.
- **§0.2 TDD rule (test-first)**: every shippable increment writes the parity test FIRST, watches it fail (RED), implements, watches it pass (GREEN). Pattern matches Inc 1–9: companion-object pure helpers tested in plain JUnit, instance behavior tested on-device.
- **§0.3 Parity-test policy**: when refactoring a behavior, capture today's behavior in a JUnit test that pins it; refactor; test still passes. The only exception is the audio-source replacement itself — Vosk is a different recognizer, so its output is naturally different. But the surrounding scaffolding (state machine, dedupe, pause/resume contract, watchdog) keeps parity.
- **§0.4 Real logcat after every increment**: per `feedback_voice_debug_diagnose_before_patching.md`. No speculative diagnoses.
- **§0.5 Naming convention (Detour 3)**: `talkWord` = turn-based single message trigger; `wakeWord` = realtime conversation trigger. On-the-wire keys `turn_talk_word` / `realtime_wake_word`. Broadcast actions `ACTION_TALK_WORD_DETECTED` / `ACTION_WAKE_WORD_DETECTED`. UI labels "Single voice message / turn based" / "Realtime voice conversation". All new code must use this vocabulary.

---

## 1. Current state of the wake-word system

**Branch**: `voice-wakeword-refactor` at `b753ac5`. Pushed to `origin/voice-wakeword-refactor`.

**Shipped commits** (read in order to understand the architecture):

| # | Commit | What |
|---|---|---|
| Inc 1 | `88ab435` | `start()` idempotency guard (companion predicate) |
| Detour 1 | `d6181b1` | gain corruption fix (LaunchedEffect was clobbering gain to 1.0) |
| Detour 2 | `9200d50` | single-ingress to AssistantService — removed redundant inline updates |
| Inc 2 | `318ce66` | `finishRecognition` idempotency guard |
| Inc 3 | `0b2cbb5` | `AssistantService` double-start dedupe (3s window, `(talkWord, wakeWord, micGain)` key) |
| Inc 4 | `187b419` | recognizer hang watchdog (10s post-onBeginningOfSpeech) |
| Detour 3 | `d226027` | naming swap-rename per real semantic (`wakeWord` ↔ `voiceWord` → `talkWord` / `wakeWord`) |
| Inc 5 | `e31d4fc` | drop `wordSubs`; `buildVariants(p) = listOf(p.lowercase().trim())` |
| Inc 6 | `738f5aa` | `WakeWordState` sealed-class FSM (Stopped / Idle / SilenceMonitor / Recognizing / Paused) |
| Inc 7 | `495b5d9` | `pauseWakeWord` / `resumeWakeWord` return `CompletableDeferred<Unit>` + duplicate-resume-intent fix |
| Inc 8 | `39b1cec` | NO_SPEECH-driven health check (replaces 2h rebuild watchdog) |
| Inc 9 | `cff6afd` | mic-unavailable broadcast + notification swap |
| Detour 5 | `c60cd08` | empirical RMS retune 200 → 70 (lifts plan §7 RMS ban) |
| Detour 6 | `b753ac5` | warm SpeechRecognizer + 5 lifecycle safeguards |

Read `wakeword_subsystem_refactor_log_2026_06_09.md` for the full per-commit narrative. Each entry includes source-fidelity HEAD sha, tuned-behavior preservation list, TDD outcome, on-device verification.

**File anatomy (post-Detour-6)** — read this before editing:

- `android/app/src/main/java/com/assistant/peripheral/voice/WakeWordDetector.kt` (~1k lines)
  - Top-level `sealed class WakeWordState` with `Stopped`, `Idle`, `SilenceMonitor`, `Recognizing(startedAtMs, beganSpeechAtMs?)`, `Paused`.
  - Companion: constants (`RMS_THRESHOLD=70`, `ACTIVITY_HOLD_MS=30`, `POST_WAKEWORD_DELAY_MS=3000`, exponential backoff schedule, `RECOGNIZER_HANG_WATCHDOG_MS=10000`, `RECOGNIZER_REFRESH_AFTER_N=20`, `RECOGNIZER_REFRESH_NO_SPEECH_SPIKE=2`), pure predicates (`shouldShortCircuitStart`, `shouldShortCircuitFinishRecognition`, `shouldBroadcastRecognizerUnhealthy`, `shouldBroadcastMicUnavailable`, `buildVariants`, `derivedIs*`), action strings (`ACTION_TALK_WORD_DETECTED`, `ACTION_WAKE_WORD_DETECTED`, `ACTION_RECOGNIZER_UNHEALTHY`, `ACTION_MIC_UNAVAILABLE`, `ACTION_MIC_AVAILABLE`).
  - Instance fields: `state` (FSM), `audioRecord`, `silenceMonitorJob`, `speechRecognizer` (warm), `recognizerWatchdogJob`, `weChangedAudioMode`, `consecutiveMisses`, `consecutiveNoSpeechErrors`, `recognitionsSinceWarmedUp`, `recognizerNeedsRefresh`, `listenerCycleFinished`.
  - Public API: `start()`, `pause()`, `resume()`, `stop()`, `release()`, derived `isActive`/`isPaused` getters.
  - Internal: `startSilenceMonitor()` (IO coroutine, RMS loop + mic-acquisition retry), `startRecognizer()` (calls `startListening` on the warm instance), `finishRecognition(wakeWordDetected, delay)`, `ensureRecognizerWarm()`, `buildRecognizerIntent()`, `destroyRecognizer()`, `revertAudioModeIfOurs()`, `muteBeep` / `unmuteBeep`, `computeRms`, `checkForWakeWord`.

- `android/app/src/main/java/com/assistant/peripheral/service/AssistantService.kt`
  - Companion: `EXTRA_TALK_WORD = "turn_talk_word"`, `EXTRA_WAKE_WORD = "realtime_wake_word"`, `EXTRA_ACK_TOKEN`, `pendingAcks` registry, `nextAckToken/stashAck/takeAck`, `pauseWakeWord(context): CompletableDeferred<Unit>`, `resumeWakeWord(context): CompletableDeferred<Unit>`, `shouldDedupeWakeStart` (Inc 3).
  - Instance: `recognizerUnhealthyReceiver` (Inc 8 listens for ACTION_RECOGNIZER_UNHEALTHY → rebuild via startWakeWord through dedupe), `micAvailabilityReceiver` (Inc 9 listens for ACTION_MIC_(UN)AVAILABLE → swap notification text), `voiceSessionActive` flag.

- `android/app/src/main/java/com/assistant/peripheral/viewmodel/AssistantViewModel.kt`
  - `startVoiceSession()`: captures `pauseAck`, resets `voiceStopFinalized`, awaits with 2s timeout.
  - `finalizeVoiceStop()`: idempotency guard (`voiceStopFinalized`), 1500ms HAL settle delay, captures `resumeAck`, awaits with 2s timeout.

- `android/app/src/main/java/com/assistant/peripheral/MainActivity.kt`
  - `onTalkWordDetected` callback runs `viewModel.startRecording()` (turn-based path).
  - `onWakeWordDetected` callback runs `viewModel.startVoiceSession()` (realtime path).
  - Broadcast listener filters on `ACTION_TALK_WORD_DETECTED` and `ACTION_WAKE_WORD_DETECTED`.

- `android/app/src/test/java/com/assistant/peripheral/voice/parity/*` — **45 parity tests, all green**:
  - `WakeWordStartParityTest` (4), `FinishRecognitionParityTest` (4), `StartWakeWordParityTest` (8), `BuildVariantsParityTest` (6), `WakeWordFSMParityTest` (7), `PauseResumeAckParityTest` (6), `NoSpeechHealthParityTest` (5), `MicUnavailableParityTest` (5).

**The fundamental problem**:

Field-confirmed pattern on A300M (Samsung Galaxy A3, Lollipop API 22, serial `06e4f224`):

```
20:47:15.501  Audio activity detected (rms=187) — starting recognizer
20:47:17.421  Recognizer ready                       ← +1.92 s
20:47:21.201  Speech begun                            ← +3.78 s
20:47:31.471  Recognizer watchdog fired               ← never heard the wake-word
```

The user said "hey buddy, wake up" at t=0. By the time Google's STT was ready to listen (~+1.92s), the speech was nearly over. Even after `Speech begun` fired at +3.78s, Google never produced partial results — the audio quality / phrasing wasn't enough for the recognizer to lock on.

The Detour 6 pre-warmed SpeechRecognizer instance helped construction time (~100ms saved), but the dominant latency is Google's STT IPC bind during `startListening`. We cannot pre-warm that — Google's API doesn't allow it.

**Vosk fixes this by being a single-process library that accepts PCM samples directly via `Recognizer.acceptWaveform()`**. No IPC, no bind latency, no separate "ready" signal. We feed it the same audio bytes the silence monitor is already reading, from t=0.

---

## 2. Vosk — facts to know before starting

### 2.1 Library

- **Name**: Vosk Speech Recognition Toolkit (https://alphacephei.com/vosk/).
- **License**: Apache 2.0.
- **Android artifact**: `com.alphacephei:vosk-android:<version>` on Maven Central. At time of writing the latest is `0.3.47` but **the implementing agent must verify the latest stable release** before adding the dependency.
- **Native libs**: Vosk ships JNI binaries for `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`. A300M is `armeabi-v7a` (32-bit Cortex-A53). The artifact bundles all four; gradle's `abiFilters` can drop the unused ones to reduce APK size.
- **Min SDK**: Vosk works on API 21+. Compatible with our minSdk=21.

### 2.2 Model

- **Recommended for A300M**: `vosk-model-small-en-us-0.15` (~40 MB extracted, ~15 MB zipped).
- **Why small**: the A300M has 1 GB RAM total; large models (~1.5 GB extracted) won't fit. Small is also fast enough for real-time on Cortex-A53.
- **Accuracy on small model**: real-world transcription is mediocre, but **we're not transcribing arbitrary speech — we're spotting two short configured phrases**. Small model is plenty accurate for this constrained vocabulary. (Vosk supports custom KWS grammars too — see §5.3.)
- **Download URL**: `https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip` (Vosk-hosted; mirror to `context/public/` if we don't want runtime dependency on alphacephei.com).
- **Where to put it on-device**: `context.filesDir / "vosk-model"` after first-run extraction. The Vosk `Model` constructor takes a path to the extracted model directory.

### 2.3 API surface (what we'll use)

```kotlin
// 1. Load model once on first run (heavy: ~500ms-2s on A300M):
val model = Model("/data/data/com.assistant.peripheral/files/vosk-model")

// 2. Create a recognizer for a specific sample rate:
val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())   // SAMPLE_RATE = 16000

// 3. Optional: constrain the vocabulary to just the configured phrases
//    (drastically improves accuracy + speed):
val recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), grammarJson)
//    where grammarJson is e.g. """["wake up", "my friend", "hey wake up", "[unk]"]"""

// 4. Feed PCM 16-bit mono samples; receive partial + final results:
val finalResult: Boolean = recognizer.acceptWaveform(buffer, length)
val partialJson: String = recognizer.partialResult           // {"partial": "wake up"}
val finalJson:   String = recognizer.result                  // {"text": "wake up"}

// 5. Close when done:
recognizer.close()
model.close()
```

**Critical**: `acceptWaveform` accepts `ShortArray` or `ByteArray` of PCM 16-bit mono samples at the rate the recognizer was constructed with. Our existing silence monitor already reads `ShortArray` at 16 kHz mono 16-bit — perfectly compatible.

### 2.4 Threading

- `Model` is thread-safe; can be shared between recognizers and across threads.
- `Recognizer` is NOT thread-safe; each instance must be accessed from a single thread (or with external synchronization).
- The recommended pattern: one `Recognizer` per audio stream, all `acceptWaveform`/`partialResult`/`result` calls from the same coroutine/thread.
- This matches our existing IO coroutine in `startSilenceMonitor` perfectly.

### 2.5 Memory & CPU

- `Model` loads ~80–100 MB into RAM on Android (the small model).
- `Recognizer` instance: ~5 MB.
- CPU: real-time factor ~0.3 on Cortex-A53 @ 1.2 GHz with the small model. That means processing 1s of audio takes ~300ms — fits well within a streaming pipeline.
- Our app currently sits at ~85 MB at idle. With Vosk loaded: ~170–180 MB. A300M has ~600 MB free; fits comfortably.

### 2.6 Where Vosk does NOT fit

- Multi-language: small models are language-specific. We'll bundle English. If the user wants a different language later, we either bundle multiple models or download on demand.
- Continuous speech for the VOICE session itself: voice mode still uses WebRTC → OpenAI Realtime; Vosk is only the wake-word stage.
- Accuracy for long-form transcription: Vosk small model is much weaker than Google or Whisper. Not a problem for wake-word.

---

## 3. Architecture — target shape

### 3.1 Current shape (post-Detour-6)

```
AudioRecord (16 kHz, 16-bit, mono)
    │
    │ silenceMonitorJob (IO coroutine) reads PCM buffers
    │
    ▼
RMS computation → threshold check → ACTIVITY_HOLD_MS debounce
    │
    │ if RMS >= effective threshold sustained:
    ▼
stopAudioRecord()                                    ← KEY PROBLEM POINT
    │
    │ Hand off to SpeechRecognizer
    ▼
startRecognizer() → speechRecognizer.startListening(intent)
    │
    │ Google STT IPC binds (800ms–3s)              ← LEADING-EDGE LOST HERE
    ▼
onReadyForSpeech → onBeginningOfSpeech → onPartialResults → checkForWakeWord
```

The handoff at `stopAudioRecord()` destroys the audio stream the user's leading edge was already in. SpeechRecognizer opens its OWN AudioRecord and the user's speech has already finished by the time it's listening.

### 3.2 Target shape (Vosk)

```
AudioRecord (16 kHz, 16-bit, mono)                    ← OPENED ONCE, NEVER STOPPED MID-CYCLE
    │
    │ ONE IO coroutine reads PCM buffers
    │
    ▼
For each buffer:
    │
    ├─► RMS computation → silence monitor's threshold tracking
    │      (kept for power-saving: optionally skip Vosk inference when silent)
    │
    └─► voskRecognizer.acceptWaveform(buffer)         ← NO HANDOFF, SAME STREAM
            │
            │ partialResult = JSON {"partial": "..."}
            ▼
        checkForWakeWord(partial) → if match, fire broadcast, reset Vosk for next phrase
```

**Key change**: there is no `stopAudioRecord()` and no handoff. The same audio stream the silence monitor is reading is also fed directly into Vosk. The user's "wake up" reaches Vosk from sample zero — no clipping.

**Optional power-saving**: when RMS is below threshold for a sustained period, we can skip `acceptWaveform` calls (Vosk still consumes RAM but no CPU). This preserves the original two-stage pipeline's battery efficiency while removing the latency penalty.

### 3.3 FSM updates

The Inc 6 `WakeWordState` sealed class needs a small update:

```kotlin
sealed class WakeWordState {
    object Stopped : WakeWordState()
    object Idle : WakeWordState()                     // active, mic not acquired yet
    object Listening : WakeWordState()                // mic acquired, Vosk feeding (NEW unified state)
    data class WakeWordDetected(                      // brief transitional state on match
        val matchedAt: Long,
        val matchedPhrase: String,
    ) : WakeWordState()
    object Paused : WakeWordState()
}
```

Why `Listening` replaces both `SilenceMonitor` and `Recognizing`: with Vosk, those two stages MERGE. There's no separate "now we're recognizing" phase — Vosk is always recognizing while the mic is open.

Detail: keep the legacy-property accessors (`isActive`, `isPaused`) byte-compatible per Inc 6's design.

### 3.4 What gets removed

Once Vosk is in:
- `SpeechRecognizer`, `RecognitionListener`, `RecognizerIntent` — gone from `WakeWordDetector`.
- `recognizerWatchdogJob` and `RECOGNIZER_HANG_WATCHDOG_MS` (Inc 4) — gone (Vosk doesn't have IPC binders to hang on).
- `recognizerNeedsRefresh`, `recognitionsSinceWarmedUp`, `RECOGNIZER_REFRESH_AFTER_N`, `RECOGNIZER_REFRESH_NO_SPEECH_SPIKE` (Detour 6) — gone (no warm/refresh cycle needed).
- `listenerCycleFinished` (Detour 6) — gone.
- `warmRecognizerIntent`, `buildRecognizerIntent()`, `recognitionListener`, `ensureRecognizerWarm()`, `destroyRecognizer()` — gone.
- `consecutiveNoSpeechErrors` and `ACTION_RECOGNIZER_UNHEALTHY` (Inc 8) — gone (Vosk doesn't emit NO_SPEECH the same way; replace with a different health signal — see §5.4).
- `muteBeep` / `unmuteBeep` and the `BEEP_STREAMS` audio-stream wrangling — gone (Vosk doesn't emit start/stop beeps; this was a SpeechRecognizer mitigation).
- `weChangedAudioMode` toggling in `startRecognizer` — gone. We don't need to set `MODE_IN_COMMUNICATION` for Vosk (it was a SpeechRecognizer beep-suppression hack).
- `revertAudioModeIfOurs` becomes mostly a no-op but kept for the voice-session interleave path.

### 3.5 What stays unchanged

- Public API: `start()`, `pause()`, `resume()`, `stop()`, `release()`, `isActive`/`isPaused` getters.
- `talkVariants` / `wakeVariants` split-by-comma derivation.
- `checkForWakeWord(matches: List<String>)` — same `lower.contains(it)` semantics.
- AudioRecord mic-acquisition retry loop (preserved with all tuned constants — `MediaRecorder.AudioSource.VOICE_RECOGNITION` on pre-N, `VOICE_COMMUNICATION` on post-N, 500ms retry delay, mic-busy notification at threshold 8).
- Inc 9 `ACTION_MIC_UNAVAILABLE` / `ACTION_MIC_AVAILABLE` broadcasts — unchanged.
- All `AssistantService` integration: Inc 3 dedupe, Inc 7 deferred-ack contract, Inc 7's idempotency fix in `AssistantViewModel.finalizeVoiceStop`, Inc 7's service-side `!voiceSessionActive` guard, Detour 3 naming (`talkWord`/`wakeWord`).
- All ViewModel call sites and broadcast wiring.
- Detour 5's `RMS_THRESHOLD = 70` — kept as the gating threshold for the power-saving Vosk-skip-when-silent path.

---

## 4. Shippable increments

Following the same one-commit-per-shippable-increment pattern from the original plan. Each increment must have a parity test (where applicable), survive `./gradlew :app:testDebugUnitTest`, deploy + verify on the A300M (serial `06e4f224`), then update plan + log before moving on.

### Increment V1 — Add Vosk dependency + bundle model

**Files**:
- `android/app/build.gradle.kts`: add `implementation("com.alphacephei:vosk-android:0.3.47")` (verify latest version at execution time).
- `android/app/src/main/assets/vosk-model-small-en-us-0.15/`: bundle the extracted model directory (~40 MB).
  - Download: `https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip`
  - Extract; verify directory structure looks like `am/`, `conf/`, `graph/`, `ivector/`, `README`.
  - Decision: **bundled, not downloaded on first launch**. User reasoning: A300M is a permanent-assistant device, should work out-of-box, no first-run network requirement. APK growth ~+40 MB is acceptable on this device class.
- `android/app/proguard-rules.pro`: add Vosk keep rules:
  ```
  -keep class org.vosk.** { *; }
  -keep class org.kaldi.** { *; }
  ```

**Change**: dependency + asset bundle only. No code changes yet.

**Line delta**: build.gradle.kts +1, proguard-rules.pro +2, assets +40MB.

**Test plan**: `./gradlew assembleDebug` succeeds; APK size increases by ~40 MB; nothing else breaks. No parity test needed (no code logic changes).

**Rollback**: revert.

**Risk**: low. Maven Central artifact is well-known; model is static data.

**On-device verification**:
- Install APK; app launches without crash.
- `adb shell run-as com.assistant.peripheral ls files/` (or equivalent on Lollipop where run-as is broken — use `find /data/app -name "vosk-model*"` from logcat or extract APK and inspect).
- Log a one-shot test: load the model in `Application.onCreate` or `AssistantService.onCreate` and log "Vosk model loaded". Verify the log line appears within ~2s of service start.

### Increment V2 — Vosk model loader (lazy, scoped to service)

**Files**: `android/app/src/main/java/com/assistant/peripheral/voice/VoskModelLoader.kt` (new).

**Change**: a `VoskModelLoader` singleton (object) that:
- Extracts the bundled model from `assets/vosk-model-small-en-us-0.15/` to `context.filesDir/vosk-model/` on first call (skip if already extracted).
- Loads the `org.vosk.Model` instance once and caches it.
- Exposes `suspend fun getModel(): Model?` — null if loading failed.
- Survives the lifecycle of the process; not tied to `WakeWordDetector` instances.
- Uses a `Mutex` to prevent races during init.

**Tested via JUnit (no Android runtime)**:
- Mock the file extraction; verify the model-load helper handles missing files gracefully.
- New parity test `VoskModelLoaderTest.kt` (3-4 tests):
  - `getModelReturnsNullWhenAssetExtractionFails`
  - `getModelCachesAcrossCalls`
  - `getModelHandlesConcurrentInitialization`

**Tuned behaviors preserved**: N/A (new code).

**On-device verification**: log "Vosk model loaded from filesDir/vosk-model in NNNms" on first cold-start; second cold-start should log faster (model already extracted).

### Increment V3 — `VoskWakeWordEngine` replacing `SpeechRecognizer`

**Files**:
- `android/app/src/main/java/com/assistant/peripheral/voice/VoskWakeWordEngine.kt` (new).
- `WakeWordDetector.kt` (heavy refactor).
- `android/app/src/test/java/com/assistant/peripheral/voice/parity/VoskEngineParityTest.kt` (new).

**Change**: extract the recognizer-stage logic into a `VoskWakeWordEngine` class that owns the `Recognizer` instance, takes PCM `ShortArray` buffers, and emits `VoskMatch` events when a configured phrase is matched in `partialResult` or `result`.

```kotlin
class VoskWakeWordEngine(
    private val model: Model,
    private val sampleRate: Float = 16000f,
    private val talkVariants: List<String>,
    private val wakeVariants: List<String>,
    private val onMatch: (matchedPhrase: String, isRealtime: Boolean) -> Unit,
) {
    private val recognizer = Recognizer(model, sampleRate, buildKeywordGrammar())

    fun feed(buffer: ShortArray, length: Int) {
        val final = recognizer.acceptWaveform(buffer, length)
        val text = if (final) extractText(recognizer.result) else extractPartial(recognizer.partialResult)
        if (text.isNotBlank() && checkForWakeWord(listOf(text))) {
            // Same precedence as pre-Detour-3: realtime wakeWord checked FIRST.
            onMatch(text, isRealtime = matchedRealtimeVariant)
            recognizer.reset()                                // ready for next phrase
        }
    }

    private fun buildKeywordGrammar(): String {
        // Constrain Vosk's vocabulary to just the configured phrases + "[unk]".
        // This dramatically improves accuracy + speed for our 2-phrase case.
        val phrases = (talkVariants + wakeVariants).distinct() + "[unk]"
        return JSONArray(phrases).toString()
    }

    fun close() {
        recognizer.close()
    }
}
```

**`WakeWordDetector.kt` changes**:
- Remove SpeechRecognizer/RecognitionListener/RecognizerIntent imports + fields + methods (per §3.4 list).
- New `private var voskEngine: VoskWakeWordEngine? = null` field.
- `startSilenceMonitor`'s IO loop now feeds the buffer to BOTH the RMS computation AND `voskEngine.feed(buffer, read)`.
- Match callback fires the LocalBroadcast (same `ACTION_TALK_WORD_DETECTED` / `ACTION_WAKE_WORD_DETECTED`) and transitions state to `WakeWordDetected → Stopped` for the existing pause/voice/resume flow.
- `pause()` / `resume()` / `stop()` close the Vosk engine cleanly.
- FSM update per §3.3: `Listening` replaces `SilenceMonitor` + `Recognizing`. Keep `Idle` as the brief interstitial during mic acquisition.

**Parity tests**:
- `VoskEngineParityTest`: pure JUnit, tests the phrase-matching predicate.
  - `feedDoesNotMatchOnBlankPartial`
  - `feedMatchesOnExactPhrase` ("wake up")
  - `feedMatchesOnContainsSubstring` ("hey wake up" → contains "wake up")
  - `feedDoesNotMatchUnrelatedPhrase` ("good morning")
  - `realtimeWakeWordChecksFirst` (precedence)
  - `feedResetsRecognizerAfterMatch` (mocked)
- Existing `BuildVariantsParityTest`, `FinishRecognitionParityTest`, `WakeWordStartParityTest`, `StartWakeWordParityTest`, `WakeWordFSMParityTest`, `PauseResumeAckParityTest`, `NoSpeechHealthParityTest`, `MicUnavailableParityTest` — most should still pass with minor adjustments. The Inc 8 `NoSpeechHealthParityTest` needs to be either dropped (since Vosk doesn't emit NO_SPEECH) or repurposed for the new health signal (see §5.4).

**Tuned behaviors to preserve**:
- All AudioRecord retry-loop constants (mic source selection, 500ms retry, mic-busy notification at threshold 8).
- Inc 9 `ACTION_MIC_UNAVAILABLE` / `ACTION_MIC_AVAILABLE` broadcasts.
- `lower.contains(it)` matching semantics in `checkForWakeWord`.
- Realtime-wakeWord-checked-first precedence from Detour 3.
- `POST_WAKEWORD_DELAY_MS = 3000` (gap after a match before re-arming).
- Inc 3 service-level dedupe.
- Inc 7 pause/resume CompletableDeferred contract.
- All Detour 3 naming.

**Line delta**: −300 / +250 in `WakeWordDetector.kt` (significant removal of SpeechRecognizer machinery, plus a new ~150-line `VoskWakeWordEngine.kt`).

**Rollback**: revert this commit; previous behavior (SpeechRecognizer) restored.

**Risk**: medium-high. Largest single commit in the plan. Mitigations:
- Keep the AudioRecord lifecycle and silence-monitor loop structurally identical.
- Add a feature-flag-style escape hatch: if `VoskModelLoader.getModel()` returns null, fall back to SpeechRecognizer-based path (leaves the old code in place during the transition). Remove the fallback in a later increment once Vosk is stable.

**On-device verification**:
- Cold-start: log "Vosk engine armed (model=small-en-us-0.15, sample-rate=16000)".
- Say "wake up" within 2s of cold-start → should detect on FIRST attempt (no second-attempt retry needed). This is THE acceptance criterion.
- End voice session → silence monitor re-arms with Vosk → second "wake up" within 5s detects on first attempt.
- Sustained 30s of silence → no Vosk match (no false positives).
- Speak unrelated phrase ("good morning, world") → no Vosk match.
- Speak with TV/music in background → measure false-positive rate over a 10-minute soak.

### Increment V4 — Drop power-saving silence-monitor gating (optional)

**Files**: `WakeWordDetector.kt`.

**Change**: with Vosk being cheap enough to run continuously, the RMS-threshold pre-gate becomes optional. Two sub-options:

- **V4a (keep gating, conservative)**: leave the RMS gate in place; only feed Vosk when RMS > threshold. Saves battery; introduces a small risk that the leading edge is still missed if the gate triggers slightly late. The point is moot because the user's gripe was Google STT bind time, not silence-monitor gating time.

- **V4b (drop gating, aggressive)**: feed every PCM buffer to Vosk unconditionally. Highest accuracy. Battery cost: ~5–10% extra on the A300M (Vosk small model on Cortex-A53 is light).

**Decision**: ship as V4a first (lowest risk). After the V3 increment's on-device verification confirms first-attempt success, optionally explore V4b if user reports residual misses.

### Increment V5 — Inc 8 health-check repurposing

**Files**: `WakeWordDetector.kt`, `AssistantService.kt`, `NoSpeechHealthParityTest.kt`.

**Change**: Vosk doesn't emit NO_SPEECH the way SpeechRecognizer does. But we still want a "recognizer is wedged, rebuild" health check.

New signal: **no successful Vosk `acceptWaveform` call in the last N seconds while AudioRecord is reading non-silent data**. If the loop is running, RMS is sometimes above threshold, but Vosk's `partialResult` is consistently empty for >2 min — something is wrong (model load corrupted, recognizer state stuck, etc).

Repurpose `consecutiveNoSpeechErrors` → `secondsWithoutAnyVoskOutput`. Repurpose `ACTION_RECOGNIZER_UNHEALTHY` → fires on the new signal. `AssistantService` rebuilds the detector the same way (through Inc 3 dedupe).

**Parity test**: rewrite `NoSpeechHealthParityTest` → `VoskHealthParityTest` with the new predicate. Old 5 tests become ~5 new ones.

### Increment V6 — Cleanup pass + final on-device soak

**Files**: `WakeWordDetector.kt`, `WakeWordStartParityTest.kt`, `FinishRecognitionParityTest.kt`, `StartWakeWordParityTest.kt`, `WakeWordFSMParityTest.kt`, `PauseResumeAckParityTest.kt`.

**Change**:
- Remove the V3 fallback path (SpeechRecognizer escape hatch). Vosk is now the only recognizer.
- Remove the Detour 6 leftover constants and the warm-recognizer code that's no longer needed.
- Update parity tests that referenced removed fields/predicates (`shouldShortCircuitFinishRecognition` may still apply with adapted semantics; the others mostly stay).
- Final 60-minute on-device soak: a mix of cold-start, voice sessions, post-voice resumes, ambient noise, and explicit wake-word attempts. Log everything.

**Acceptance criterion (end-to-end)**:
- ≥95% first-attempt success rate on conversational "wake up" at arm's length.
- 0 false positives across a 30-minute background-noise soak.
- No regression in any of Inc 1–9's tuned behaviors (mic acquisition, dedupe, FSM transitions, pause/resume contract, post-voice handoff).

---

## 5. Open questions & decisions to make at execution time

### 5.1 Bundled vs downloaded model

**Decision**: bundled, per user discussion this session. Reconfirm at execution time if the user prefers a smaller APK + first-run download.

### 5.2 Continuous Vosk vs gated Vosk

**Decision**: gated (V4a) first, evaluate V4b based on field measurements.

### 5.3 Constrained grammar vs full free-form

Vosk supports passing a JSON-array of allowed phrases to the `Recognizer` constructor. This drastically improves accuracy and speed for keyword spotting:

```kotlin
val grammar = """["wake up", "my friend", "[unk]"]"""
val recognizer = Recognizer(model, sampleRate, grammar)
```

**Trade-off**: free-form lets the user reconfigure phrases in Settings without re-instantiating the engine; constrained grammar requires recreating the recognizer on every phrase change.

**Decision**: use constrained grammar — phrase changes are rare (UI Save button events), and the accuracy/speed gains are worth the extra teardown-and-rebuild. Wire the recreation into the existing settings-change → `AssistantService.startWakeWord` flow.

### 5.4 Inc 8 health-check replacement signal

See V5. Detail to settle: what's the right window for "no Vosk output" before declaring unhealthy? Plan §9 decision 6 picked 8 NO_SPEECH errors = ~4–5 min of saturated backoff. For Vosk: 120s of audio with `partialResult == ""` AND mean RMS > threshold/2 for that window.

### 5.5 Model loader location

`AssistantService.onCreate` vs first-`startWakeWord` call. Trade-off: eager load delays service start by ~500ms-2s; lazy load delays first wake-word arm by the same amount. Probably eager — the service starts in background and the user can tolerate a couple seconds of "warming up" once at boot.

### 5.6 ABI filters

Add to `android/app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }
    }
}
```

Drops `x86` and `x86_64` natives — saves ~20 MB APK size. The A300M is `armeabi-v7a`; modern phones are `arm64-v8a`. We don't run on emulators in production.

### 5.7 What about the orchestrator-storm bug?

Out of scope. The user fixed it in a parallel session. If it comes back during testing, document and escalate — don't try to fix it as part of this plan.

---

## 6. Operational constants & device facts

- **Device under test**: Samsung Galaxy A3 (A300M), serial `06e4f224`, Android 5.0.2 (API 22) Lollipop, Cortex-A53 quad-core @ 1.2 GHz, 1 GB RAM, `armeabi-v7a`.
- **Backend**: 192.168.0.28:8765 (laptop) and 192.168.0.200:8765 (Jetson). Either should serve.
- **Logcat ring buffer**: ~1 MB main + 512 KB system on the A300M. Can be drowned by orchestrator-storm errors or other chatter; use filtered streaming (`adb logcat WakeWordDetector:* AssistantService:* *:S`) to a file when you need clean capture.
- **adb input** for UI tests (per `feedback_use_adb_input_for_device_tests.md`):
  - swipe duration ≤200ms for fling-scroll on Lollipop.
  - re-dump UI after every scroll (`uiautomator dump`).
  - mark logcat phases with `adb shell "log -t MARKER 'phase X'"` for clean post-hoc slicing.
- **App data wipe**: `adb -s 06e4f224 shell pm clear com.assistant.peripheral`. Required after V1 if the model bundle ships and we want a clean test.

---

## 7. Constraints (do not violate)

Same as the wake-word refactor plan §7, minus the RMS_THRESHOLD ban (lifted by Detour 5).

- DO NOT change `ACTIVITY_HOLD_MS`, `POST_WAKEWORD_DELAY_MS`, `POST_RECOGNITION_BASE_MS`, `POST_RECOGNITION_MAX_MS`, `CLIENT_ERROR_DELAY_MS` — these are the tuned constants that survived. (`POST_RECOGNITION_*` may become unused once Vosk lands; remove only after verifying no regression.)
- DO NOT bring back `wordSubs` phonetic substitution. The user explicitly rejected it as hacky.
- DO NOT touch the AudioRecord retry loop's mechanics. Tuned, working.
- DO NOT change `voice_initiator` behavior (out of scope).
- Preserve `revertAudioModeIfOurs` semantics where they apply.
- Preserve mic-source choice (`VOICE_COMMUNICATION` post-N, `VOICE_RECOGNITION` pre-N).
- Preserve recents-button long-press monitor.
- Preserve SharedPreferences persistence keys and sticky-restart restore path.
- Preserve the Inc 7 CompletableDeferred contract — voice plan's seam.
- Preserve Detour 3 naming and on-the-wire keys (`turn_talk_word`, `realtime_wake_word`).

---

## 8. Build & deploy commands

```bash
# 1. Sanity-check tree
cd /home/rodrigo/assistant && git status && git log --oneline -5

# 2. Run parity tests
cd /home/rodrigo/assistant/android && ./gradlew :app:testDebugUnitTest --tests "com.assistant.peripheral.voice.parity.*"

# 3. Build
cd /home/rodrigo/assistant/android && ./gradlew assembleDebug

# 4. Install + restart
adb -s 06e4f224 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 06e4f224 shell am force-stop com.assistant.peripheral
adb -s 06e4f224 shell am start -n com.assistant.peripheral/.MainActivity

# 5. Filtered logcat capture
adb -s 06e4f224 logcat -c
# (user triggers wake words, runs voice sessions, etc — 60s window)
adb -s 06e4f224 logcat -d -v threadtime 2>&1 | \
  grep -E "WakeWordDetector|AssistantService|VoskWakeWordEngine|VoskModelLoader|FATAL|AndroidRuntime" \
  > /tmp/vosk_inc<N>_$(date +%s).log
```

---

## 9. Per-Increment commit message structure

Each commit must include:
- Plan citation (this file, the relevant section).
- Source-fidelity HEAD sha and line refs for any preserved behavior.
- TDD outcome (RED → GREEN counts).
- Tuned-behavior preservation list.
- On-device verification log.
- "Implication for downstream increments" paragraph.

Same form as Inc 1–9. Consistency matters for future reviewers.

---

## 10. Memory pointers

- **This plan**: `context/memory/archie/history/wakeword-vosk-migration-2026-06/plan.md`
- **Original wake-word refactor plan (now ✅ complete)**: `context/memory/assistant/plans/wakeword_subsystem_refactor_plan_2026_06_09.md`
- **Original wake-word execution log** (one entry per shipped increment): `context/memory/assistant/plans/wakeword_subsystem_refactor_log_2026_06_09.md`
- **Vosk migration execution log** (to be created at first commit): `context/memory/archie/history/wakeword-vosk-migration-2026-06/log.md`
- **Structural analysis** (pre-refactor): `context/memory/assistant/operational/voice_wakeword_structural_analysis_2026_06_09.md`
- **Bug inventory** (pre-refactor): `context/memory/assistant/operational/voice_system_refactor_handoff_2026_06_08.md`
- **Detour 1+2 detail memo**: `context/memory/assistant/operational/wakeword_increment1_field_observation_2026_06_09.md`
- **Per-Claude feedback memos**:
  - `agent memory: feedback_dont_touch_wake_word_tuning.md`
  - `agent memory: feedback_use_adb_input_for_device_tests.md`
  - `agent memory: feedback_voice_debug_diagnose_before_patching.md`
- **Voice plan** (parallel, Increment H remaining): `context/memory/assistant/plans/voice_subsystem_refactor_plan_2026_06_09.md`

---

## 11. Why Vosk over the alternatives

User criteria from the session (verbatim):
1. Open source — Apache 2.0 ✓
2. Runs locally on-device — yes ✓
3. No account — yes ✓
4. Performance on A300M — RT factor ~0.3 ✓
5. (Implicit) Offline — yes ✓

Rejected:
- **Google Cloud Speech API**: needs GCP account + billing; costs money; requires network. ✗ on criteria 1, 3, partially 5.
- **Porcupine** (Picovoice): mostly closed source; needs AccessKey for free tier; custom wake-word training requires their cloud. ✗ on criteria 1 and 3.
- **Snowboy** (Kitt.AI): officially dead (project abandoned 2020). ✗ as a maintained option.
- **TensorFlow Lite + custom keyword model**: viable but requires training a model — significant project. Deferred.
- **MediaRecorder.AudioSource.REMOTE_SUBMIX hack to feed buffered audio to SpeechRecognizer**: requires CAPTURE_AUDIO_OUTPUT signature-protected permission. ✗ not possible without rooting.
- **Re-introducing wordSubs phonetic variants**: user explicitly rejected ("don't want hacky").

Vosk is the only mainstream option that satisfies every criterion. The migration cost (one large commit, ~150 lines of new code, ~300 lines of old code removed, +40 MB APK) is the unique price for moving off SpeechRecognizer's broken-on-Lollipop IPC bind model.
