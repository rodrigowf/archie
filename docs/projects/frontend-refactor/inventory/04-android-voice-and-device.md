# 04 — Android voice stack, wake word, service, and `android-device` (behavioural spec)

> **Status / scope (updated per Rodrigo's 2026-10-03 scope change).**
> - The voice/audio/wake-word stack will be **rewritten from scratch**, not moved. This chapter is the **behavioural spec the rewrite is verified against**: every tuned value, every state machine, every device/API branch, and every field-found bug written as a regression scenario.
> - The `android-device` companion (`com.assistant.device`) stays a **separate, unchanged** app.
> - The A300M gets a **separate voice-first lite app (minSdk 21)** that shares the new voice modules.
> - The main app becomes **minSdk 26**.
>
> Written against branch `frontend-refactory` (HEAD `e871d05`). Every claim cites `file:line`. I read the code directly. Where the code and the architecture docs disagree, **the code wins** and the drift is listed in §11.4.

**Path abbreviations**

| Prefix | Expands to |
|---|---|
| `P/` | `android/app/src/main/java/com/assistant/peripheral/` |
| `T/` | `android/app/src/test/java/com/assistant/peripheral/` |
| `D/` | `android-device/app/src/main/java/com/assistant/device/` |
| `A/` | `android/app/` |

**Labels used in the constant tables (§4):**

- **LB** — load-bearing, field-tuned. The rewrite must reproduce it exactly; changing it needs Rodrigo's explicit per-constant approval.
- *wire* — protocol or compatibility value.
- *plumb* — internal plumbing. Keep the value; the implementation is free.
- *UX* — user-facing cue.

**Non-negotiables carried from memory into the rewrite**

1. **Wake-word tuning is a fragile equilibrium.** `feedback_dont_touch_wake_word_tuning.md`: the RMS/backoff/dedupe values, SR `EXTRA_*` and audio-mode flow are frozen. A "logical" retune (200→100, `10e0c83`) made detection "very very difficult" and was reverted (`2acf57c`, `51ba2e5`).
2. **Echo ducking is drain-then-restore.** `feedback_dont_shortcut_echo_ducking.md`: never restore the mic early on raw-mic VAD.
3. **The realtime "wake up" path is the reference for perfect.** The talk path is a same-mic unified loop. `project_wakeword_turnbased_unified_loop_2026_07_21.md`, field-validated 2026-07-21.
4. **Keep the SpeechRecognizer fallback.** Don't drop it until Vosk is validated on every device. `project_wakeword_vosk_v6_deferred_2026_06_10.md`.
5. **Voice is owner-scoped across devices.** Compose gotcha: never use an early `return@Box` in a content lambda. `project_multidevice_voice_ownership_2026_07_21.md`.
6. **Diagnose from real logs.** Pull real logcat/journal before patching (`feedback_voice_debug_diagnose_before_patching.md`). The rewrite must keep equivalent log markers (§10.3).

---

## 1. Current voice stack inventory (the system being replaced)

### 1.1 Layering

```
MainActivity (LocalBroadcast receiver + Compose DisposableEffects)        P/MainActivity.kt:110-144, 322-389
   │  wake/talk/confirm callbacks                ▲ LocalBroadcasts
   ▼                                             │
AssistantViewModel  (activity-scoped, builds VoiceController)            P/viewmodel/AssistantViewModel.kt:75-92
   │
VoiceController     (session lifecycle, ownership, WS voice branches)    P/voice/VoiceController.kt
   │
VoiceManager        (audio focus, routing, provider factory, cmd queue)  P/voice/VoiceManager.kt
   │            └── AudioRouter (route decision + AudioManager mutations) P/voice/AudioRouter.kt
   ▼
VoiceProvider (interface)                                                 P/voice/VoiceProvider.kt:105
   ├── OpenAIVoiceProvider (WebRTC, owns PeerConnection + own ducking)    P/voice/OpenAIVoiceProvider.kt
   └── WebSocketPcmProvider (abstract)                                    P/voice/WebSocketPcmProvider.kt
          ├── MicCapture / PcmPlayback / EchoDuckController
          ├── QwenVoiceProvider  (OpenAI-Realtime-shaped events)
          └── GeminiVoiceProvider (Gemini Live envelopes)

AssistantService (foreground service, owns WakeWordDetector)             P/service/AssistantService.kt
   └── WakeWordDetector (RMS gate → engine → Whisper gate → talk capture) P/voice/WakeWordDetector.kt
          ├── WakeWordRecognitionEngine (interface)
          │     ├── VoskRecognitionEngine → VoskWakeWordEngine → org.vosk.Recognizer
          │     └── SpeechRecognizerEngine (fallback, "V6 deferred")
          ├── VoskModelLoader (singleton, asset extract + Lollipop polyfill)
          └── WhisperConfirmer (direct OpenAI whisper-1 call)
```

### 1.2 Per-class inventory

| Class (file:line) | Responsibility | API called by UI/VM | State exposed | Threading | Lifecycle |
|---|---|---|---|---|---|
| `VoiceController` `P/voice/VoiceController.kt:91` | Owns the `VoiceManager`, voice UI state, ownership (`amVoiceOwner`), the `voiceStopFinalized` dedupe, WS voice branches, `Reconnected` re-arm, PTT, talk send | `onSettingsChanged` :234, `handleVoiceWebSocketEvent` :303, `startVoiceSession` :699, `stopVoiceSession` :753, `toggleMute` :822, `startRecording`/`stopRecording` :626/:642, `sendCapturedVoiceMessage` :670, `markRecordingStarting` :579, `markVoiceConnecting` :593, `markWakeConfirming` :604, `clearWakeConfirming` :609, `clearRecording` :621, BT/wired availability :828-831, `release` :850 | `voiceState` :119, `voiceReconnectBanner` :122, `vadState` :125, `vadDurationMs` :128, `isMuted` :131, `isRecording` :134, `wakeConfirming` :144, `remoteVoiceActive` :155, `toastMessages` :174 | `viewModelScope`. WS events on `Dispatchers.Default` (`P/viewmodel/AssistantViewModel.kt:208-212`), UI calls on Main. `amVoiceOwner` `@Volatile` :165; `activeVoiceConfig`/`voiceStopFinalized` plain :184/:190 | With the VM. `init` subscribes to `ConnectionEvent.Reconnected` :213-221 |
| `VoiceManager` `P/voice/VoiceManager.kt:46` | Audio focus, routing, BT/wired detection, provider factory, pre-provider command queue, `session.update` cache | `start()` :234 / `start(cfg)` :245, `stop` :385, `release` :411, `handleBackendCommand` :419, `handleProviderEvent` :187, `pushSpeakerChunk` :177, `clearPreStartState` :218, `setVoiceEventCallback` :159, `setMicChunkCallback` :168, mute :446-450, gains :452-462, `setAudioOutput` :547 | `state` :141, `events` (buffer 64) :144 | Own `IO+SupervisorJob` scope :137. `starting` `@Volatile` + `synchronized` :90, :260-266. `Channel(UNLIMITED)` queue :121. `lastSessionUpdate` `@Volatile` :135 | Rebuilt when the server URL changes |
| `VoiceProvider` `P/voice/VoiceProvider.kt:105` (+ `VoiceConnectionInfo` :33, `VoiceConfig` :61, `VoiceConnectionType` :12) | Transport contract / wire DTOs | `connect` :145, `handleBackendCommand` :157, `pushSpeakerChunk` :166, `setSessionUpdateFallback` :193, `handleProviderEvent` :208, mute :213-215, gains :221/:228, `setSpeakerMode` :241, `disconnect` :249 | `state`, `events` | — | — |
| `VoiceEvent` `P/voice/VoiceManager.kt:644-699` | Provider→controller events | — | — | — | — |
| `OpenAIVoiceProvider` `P/voice/OpenAIVoiceProvider.kt:51` | WebRTC PC + `oai-events` DC. Mirrors every event. Gain in ADM callback. **Own** 2 s ducking. `session.update` self-heal | VoiceProvider surface | — | WebRTC native threads. Teardown bounced to `scope` :428, :651. `cleanup` `synchronized` :804-806 | New instance per session |
| `WebSocketPcmProvider` `P/voice/WebSocketPcmProvider.kt:42` | Mic→`voice_audio_in`, `voice_audio_out`→speaker, ducking, barge-in flush, `voice_status`/`error` handling | + `parseProviderEvent` :131, `emit` :135, `setState` :139, `flushSpeakerOutput` :153 | — | Own scope :57. `running` AtomicBoolean :64 | New per session |
| `QwenVoiceProvider` :18 / `GeminiVoiceProvider` :27 | Event parsers | — | — | — | — |
| `MicCapture` `P/voice/MicCapture.kt:44` | AudioRecord + capture loop + `[MIC_PROBE]` + gain + staleness probe | `start` :67, `cleanup` :161 | — | Blocking read on IO :99-154 | Per session |
| `PcmPlayback` `P/voice/PcmPlayback.kt:41` | AudioTrack + speaker `Channel(UNLIMITED)` + writer loop + flush + CALL/MEDIA rebuild | `enqueue` :86, `flush` :103, `start` :137, `setSpeakerMode` :251, `cleanup` :289, frame counters :73-79 | — | Single writer coroutine :170-235 | Per session |
| `EchoDuckController` `P/voice/EchoDuckController.kt:48` | Drain-then-restore ducking (WS providers) | :103-292 | `currentMicGain`, `isDucked`, `isRestorePending` :77-91 | `@Volatile` gains | Per provider |
| `AudioRouter` `P/voice/AudioRouter.kt:58` | Route decision + AudioManager mutations | `pickRoute` :153, `apply` :373, `release` :556, availability :303/:353 | — | Caller thread | Per VoiceManager |
| `WakeWordDetector` `P/voice/WakeWordDetector.kt:55` | RMS monitor, engine, Whisper gate, talk capture, re-arm | `start` :576, `pause` :597, `resume` :614, `stop` :624, `release` :634 | `WakeWordState` :27-36 (`@Volatile` :453). Output = **LocalBroadcasts** :76-111, :355, :379-383 | Scope `Main+SupervisorJob` :480. The whole mic/recognize/capture runs **inline on one IO coroutine** :661, :806-809 | Replaced per `AssistantService.startWakeWord` |
| `WakeWordRecognitionEngine`, `RecognitionResult`, `RecognitionCallbacks` `P/voice/WakeWordRecognitionEngine.kt:33,124,188` | Stage-2 contract | internal | — | — | — |
| `VoskRecognitionEngine` :37 / `VoskWakeWordEngine` :51 | Vosk feed, prefix trigger, tail + trim / grammar + matching | internal; companion `findMatch` :165, `findTalkPrefixMatch` :210, `buildKeywordGrammar` :241 | — | Recognizer not thread-safe | Per cycle |
| `SpeechRecognizerEngine` `P/voice/SpeechRecognizerEngine.kt:48` | Fallback SR | internal | — | Main-only SR | Kept (V6) |
| `VoskModelLoader` `P/voice/VoskModelLoader.kt:37` | Extract + load model, Lollipop shim | `getModel` :142 | — | IO + Mutex | Process; failure sticky :144 |
| `WhisperConfirmer` `P/voice/WhisperConfirmer.kt:38` | Whisper gate (fail-closed) | `confirm` :80, `decide` :196, `normalize` :215 | — | IO | Lazy per detector |
| `AudioRecorder` `P/audio/AudioRecorder.kt:24`, `WavUtils` `P/audio/WavUtils.kt` | Manual PTT; WAV encoding | `startRecording` :64, `stopRecording` :131 | — | IO | VM-owned |
| `AssistantService` `P/service/AssistantService.kt:39` | Mic FGS; owns detector; pause/resume ack; screen re-arm; recents monitor; Vosk preload; notification | companion :145-253 | prefs :99-105 | Main + `serviceScope` IO + recents thread | `START_STICKY` |
| `ButtonAccessibilityService`, `AssistantVoiceInteractionService`, `VoiceShortcutActivity` | Alternative triggers (§6.3) | — | — | — | — |

### 1.3 UI-facing surface today

The VM re-exports controller flows (`P/viewmodel/AssistantViewModel.kt:130-137`). The UI collects them (`P/MainActivity.kt:262-269`).

- **Pass-throughs:** `AssistantViewModel.kt:381-395`.
- **Beeps:**
  - `playWakeWordAckBeep` :493-494
  - `playTalkWordAckBeep` :502-503
  - `playReconnectBeep` :569
- **`VoiceState`** (`P/data/Models.kt:84-105`): Off, Connecting, Summarizing, Active, Speaking, Listening (**never set by any producer**), Thinking, ToolUse, Ending, Error.

---

## 2. Voice providers and transports (behaviour to reproduce)

### 2.1 Backend surface (complete)

| Kind | Endpoint / message | Current caller |
|---|---|---|
| REST | `GET /api/config` → `default_voice_provider/model/name/transcription_language/endpoint`; on any error → `VoiceConfig.DEFAULT` (openai / gpt-realtime / cedar) | `P/network/ApiClient.kt:239-262`; `VoiceProvider.kt:74-80` |
| REST | `POST /api/orchestrator/voice/session?provider&model&voice&transcription_language&endpoint` (empty body) → `connection_info{connection_type, endpoint, ephemeral_token, expires_at, model, voice, audio_in_format{sample_rate,encoding}, audio_out_format{...}}`. Sample rates default to 24000 | `ApiClient.kt:293-340`, :431-448 |
| REST | `GET /api/config/openai-key` → `{api_key}` | `ApiClient.kt:351-368` |
| External | `POST <connection_info.endpoint>` (default `https://api.openai.com/v1/realtime/calls?model=gpt-realtime`), `Content-Type: application/sdp`, `Bearer <ephemeral>` | `OpenAIVoiceProvider.kt:117`, :507-565 |
| External | `POST https://api.openai.com/v1/audio/transcriptions` multipart (`file=wake.wav`, `model=whisper-1`, `response_format=json`, `language=en`, `temperature=0`) | `WhisperConfirmer.kt:105-169` |
| WS out | `voice_start{local_id, resume_sdk_id, voice_provider, voice_model, voice_name, voice_transcription_language, voice_endpoint?}` | `P/network/WebSocketManager.kt:226-239` |
| WS out | `voice_stop` | :240-242 |
| WS out | `voice_event{event}` (WebRTC mirror) | :243-246 |
| WS out | `voice_audio_in{audio}` | :247-249 |
| WS out | `send_audio{audio, format:"wav"}` (talk/PTT) | :259-262 |
| WS out | `start{local_id, resume_sdk_id, resume_from{stream_id,seq}}` | :208-222 |
| WS in | `session_started{session_id, voice, voice_session_update, voice_initiator (default **false**)}` | :331-343 |
| WS in | `voice_command{command}` | :450-453 |
| WS in | `voice_event{event}` (drops `*.transcript.delta`, `response.text.delta`, `function_call_arguments.delta`; shallow map) | :454-481 |
| WS in | `voice_audio_out{audio}` | :482-488 |
| WS in | `voice_ending{reason}`, `voice_ended{reason}`, `voice_stopped` | :489-497 |
| WS in | `voice_owner_active{active, owner_local_id}` | :498-502 |
| WS in | `voice_vad_state{state, duration_ms, silero_prob}` | :504-515 |
| WS in | `voice_error{error{category,message,recoverable,recovery_hint,provider_doc_url,raw_close_code,raw_close_reason,provider}}` | :517-545 |
| WS in | `ping` (backend app-level heartbeat every 15 s, consumed silently) | :321-328 |
| WS transport | okhttp `pingInterval` 30 s; reconnect delay 3 s; `Disconnected(willReconnect=shouldReconnect)` | :42, :47, :99, :175-179 |

### 2.2 OpenAI Realtime over WebRTC

**Mic path:**

- `JavaAudioDeviceModule` with HW AEC/NS **disabled** (:359-364).
- WebRTC software AEC, NS and AGC **enabled** (:335-337).
- Source is `VOICE_RECOGNITION` below API 24, `VOICE_COMMUNICATION` from 24 (:354-357).
- Gain is applied in `AudioRecordDataCallback` (:283-296).
- goog* constraints (:374-387).
- One send-track plus one recv-only transceiver, unified plan, max-bundle (:394-468).
- Ordered DC `oai-events` (:470-472).

**Events:**

- Every DC event is mirrored to the backend as `voice_event` (:629).
- Event → `VoiceState`/`VoiceEvent` mapping (:655-762):

| DC event | State / VoiceEvent |
|---|---|
| `response.created` | Speaking |
| `response.done` | Active + TurnComplete |
| `output_item.added(function_call)` | ToolUse |
| `function_call_arguments.done` | Thinking + ToolUse |
| `speech_started` | Active + SpeechStarted |
| `speech_stopped` | Thinking + SpeechStopped |
| `input_audio_transcription.completed` | UserTranscript |
| `response.output_audio_transcript.*` / legacy `response.audio_transcript.*` | TextDelta / TextComplete (both names accepted, `304aa80`) |
| `output_audio_buffer.started/stopped/cleared` | Ducking (§3.3) |
| `error` | Error + async teardown |

- **Backend → OpenAI:** `voice_command.command` goes over the DC if it is open, otherwise into `pendingCommands` (:769-785).

**Teardown:** never from a WebRTC callback thread (:405-429, :641-652). Order is DC close → track dispose → PC close → PC dispose → factory dispose (:806-853).

### 2.3 WebSocket PCM providers (Qwen, Gemini; unknown WS → Qwen parser `VoiceManager.kt:471-480`)

**Connect** (`WebSocketPcmProvider.kt:174-266`):

1. Guard: already running.
2. Check RECORD_AUDIO.
3. In/out sample rates come from `connection_info`.
4. State → Connecting.
5. Reset ducker.
6. Build playback and mic.
7. `running=true`.
8. **delay 200 ms.**
9. **Mic start, then speaker start.**
10. State Active + SessionCreated.

**`voice_status`** (:287-326):

| Status | Effect |
|---|---|
| `preparing` | Connecting |
| `summarizing` | Summarizing, **only if** state ∈ {Off, Error, Connecting, Summarizing} (`40ce856`) |
| `ready` | Active |
| `reconnect_warning` | ReconnectWarning(parsed Go duration) |
| `reconnecting` | Reconnecting |

**Backend `error`** (:334-346): Error state + VoiceEvent.Error → `cleanup()` → SessionEnded (`94e3e4a`).

**Qwen** (`QwenVoiceProvider.kt:23-81`): same table as OpenAI. `speech_started` → `flushSpeakerOutput()` (barge-in).

**Gemini** (`GeminiVoiceProvider.kt:39-121`):

- `inputTranscription` deltas are buffered into one user transcript. It flushes on the first `outputTranscription`/`modelTurn`, or on `turnComplete` (`ea96ce2`).
- `interrupted` → flush the speaker and drop the staged assistant text.
- `turnComplete` → TextComplete(staged) + TurnComplete.
- `toolCall.functionCalls[]` → ToolUse.
- **But see bug B1:** the `parts` and `functionCalls` arrays are never parsed today.

### 2.4 Ownership

**Ownership flags:**

- `voice_initiator` missing → **false** (`WebSocketManager.kt:335-342`, `9b24d1a`). It sets `amVoiceOwner` on `session_started` when `voice=true` (`VoiceController.kt:310-316`).
- `startVoiceSession` sets owner=true (:716). `finalizeVoiceStop` clears it (:780).

**Owner-only handling:**

- `voice_command`, `voice_ending`, `voice_ended`/`voice_stopped` (:350, :377, :393).
- The `voice_session_update` forward happens only when the initiator flag is set (:317-321).

**Non-owner:** `voice_owner_active` → `remoteVoiceActive`, which renders a disabled button "Active elsewhere" (:358-365; `P/ui/components/VoiceButton.kt:140-160`).

---

## 3. State machines (explicit, for the rewrite)

Notation: **state** · *event* → next state / [side effects] {timers}.

### 3.1 Wake word → Whisper confirm → turn-based capture (detector loop)

The implementation's `WakeWordState` only has Stopped / Idle / SilenceMonitor / Recognizing / Paused (`WakeWordDetector.kt:27-36`). The confirm, capture and cooldown phases run while `state == Idle` (it is set back to Idle at :1003-1005 *before* confirm/capture). The rewrite should model those as **explicit states**; risk R3 comes from that ambiguity. The full FSM:

| State | Event | Next | Side effects / timers |
|---|---|---|---|
| STOPPED | `start()` and `SpeechRecognizer.isRecognitionAvailable()` (:577-580) and not already active (:581) | ACQUIRING_MIC | — |
| ACQUIRING_MIC | AudioRecord(16 kHz mono PCM16, source per §5) init fails or not INITIALIZED | ACQUIRING_MIC | {retry 500 ms} :688/:702. failures==8 → broadcast `MIC_UNAVAILABLE` once :682-687 |
| ACQUIRING_MIC | init OK but `startRecording` → not RECORDING | ACQUIRING_MIC | release; {500 ms} re-enter :719-727 |
| ACQUIRING_MIC | recording OK | MONITORING | if previously unavailable → `MIC_AVAILABLE` :710-714. `ensureEngine()`: Vosk if the model loads, else SR (sticky) :548-570. `engine.warm()` on Main :739-742 |
| MONITORING | each read | MONITORING | push into the 500 ms pre-buffer ring :766-768. compute RMS |
| MONITORING | RMS ≥ `70/micGain` continuously for ≥30 ms | RECOGNIZING (Vosk) or SR_CYCLE (SR) | Vosk: keep the same AudioRecord and pass the pre-buffer :806-809. SR: release the mic, hop to Main :790-797 |
| MONITORING | RMS drops below threshold | MONITORING | reset hold timer :811-812 |
| RECOGNIZING (Vosk) | feed pre-buffer then 400 ms reads; `findMatch` hit (realtime-first) or `findTalkPrefixMatch` (≥2 leading words) | MATCH_TAIL | log partials. `onRecognitionStarted` on first frame RMS ≥30 |
| RECOGNIZING | 5000 ms elapsed with no match | COOLDOWN(500 ms) | `NoMatch`, `consecutiveMisses=0` :1054-1063 |
| RECOGNIZING | `pause/stop` (`cancelled`) | — | `Cancelled` → no re-arm :1053 |
| MATCH_TAIL | read +400 ms | (wake) CONFIRMING_WAKE / (talk) TALK_PRE_ONSET | trim to the trailing 2000 ms :248-280 |
| CONFIRMING_WAKE | peak frame RMS < 30 | COOLDOWN(3000) | broadcast `WAKE_CONFIRM_FAILED(isRealtime)`; Whisper not called :1110-1121 |
| CONFIRMING_WAKE | else | CONFIRMING_WAKE | broadcast `WAKE_CONFIRMING` :1123-1125. Whisper {≤10 s} |
| CONFIRMING_WAKE | Whisper confirmed | COOLDOWN(3000) | `bringToForeground` (3 s wake lock + startActivity) → broadcast `WAKE_WORD_DETECTED` :1141-1147 |
| CONFIRMING_WAKE | rejected / timeout / error / no key / 401 | COOLDOWN(3000) | broadcast `WAKE_CONFIRM_FAILED`. 401 also clears the cached key |
| TALK_PRE_ONSET | each 200 ms frame; update noise floor; voice iff RMS ≥ max(floor×K, 30) | TALK_PRE_ONSET | sustained voice resets on a non-voice frame |
| TALK_PRE_ONSET | sustained voice ≥300 ms | TALK_CAPTURING | **onset:** `bringToForeground` + broadcast `TALK_WORD_DETECTED` (beep + recording UI) :1262-1267 |
| TALK_PRE_ONSET | 4000 ms since trigger with no onset | COOLDOWN(3000) | phantom: return null, **no UI, no broadcast** :1228-1231 |
| TALK_CAPTURING | silence (no voice frame) ≥1000 ms after the last voice | CONFIRMING_TALK | — |
| TALK_CAPTURING | 12000 ms since trigger | CONFIRMING_TALK | send what was captured :1222-1225 |
| CONFIRMING_TALK | peak frame RMS < 30, or Whisper reject/fail | COOLDOWN(3000) | broadcast `WAKE_CONFIRM_FAILED(false)` (clears recording UI) :1034-1039, :1303-1309 |
| CONFIRMING_TALK | confirmed | COOLDOWN(3000) | WAV(16 kHz) → b64 → broadcast `TALK_MESSAGE_CAPTURED{talk_audio_b64}` :1329-1336. **No** `WAKE_CONFIRMING` on the talk path (`3bee23a`) |
| SR_CYCLE | Matched (no PCM → confirm fail-open) | COOLDOWN(3000) | broadcast. NoSpeech/flat Error → COOLDOWN(1000). Other miss → COOLDOWN(1 s·2ⁿ, cap 30 s) :931-964 |
| COOLDOWN(d) | {d} elapsed and still active and not paused | ACQUIRING_MIC | the AudioRecord is held through cooldown; `startSilenceMonitor` releases it first :645 |
| any active | `pause()` (voice session) | PAUSED | cancel the job; stop+release AudioRecord; engine `tearDown` + `markNeedsRefresh` :597-609 |
| PAUSED | `resume()` | ACQUIRING_MIC | `consecutiveMisses=0`, refresh engine :614-622. (The service actually does a full rebuild instead, §3.5) |
| any | `stop()` | STOPPED | cancel all children :624-632 |

**Broadcast contract consumed by the UI** (`P/MainActivity.kt:110-129`, :322-389):

| Broadcast | UI reaction |
|---|---|
| `WAKE_WORD_DETECTED` | wake beep, `markVoiceConnecting`, navigate to Chat, `startVoiceSession` |
| `TALK_WORD_DETECTED` | talk beep, `markRecordingStarting`, navigate |
| `TALK_MESSAGE_CAPTURED` | `sendCapturedVoiceMessage` (clears recording + confirming; appends a "[Voice message]" bubble; WS `send_audio` to the orchestrator or agent endpoint) |
| `WAKE_CONFIRMING` | `markWakeConfirming` + navigate |
| `WAKE_CONFIRM_FAILED` | `clearRecording` (clears both indicators) |

### 3.2 Realtime voice session lifecycle (client)

| State (UI `VoiceState`) | Event | Next | Side effects / timers |
|---|---|---|---|
| OFF | wake broadcast / button / assist / recents | CONNECTING (UI) | `markVoiceConnecting` sets Connecting synchronously (`3c4dbba`). Then `startVoiceSession` |
| CONNECTING | not an orchestrator session | ERROR | `Error("Voice only available for orchestrator sessions")`; no pause sent :700-703 |
| CONNECTING | VoiceManager null | ERROR | :706-709 |
| CONNECTING | otherwise | PAUSING_WAKE | `pauseWakeWord()` intent (service sets `voiceSessionActive=true`, pauses the detector, completes the ack). `voiceStopFinalized=false`, `amVoiceOwner=true`, `remoteVoiceActive=false` :712-717. {await ack ≤2000 ms, then proceed anyway} :720 |
| PAUSING_WAKE | ack / timeout | STARTING | `GET /api/config` → cfg. `activeVoiceConfig=cfg`. WS `voice_start` :722-742. `VoiceManager.start(cfg)` |
| STARTING | (backend) `voice_event voice_status summarizing/preparing` before the provider exists | SUMMARIZING / CONNECTING | `VoiceManager.kt:195-204`. The provider's initial Off does not overwrite it :308-313 |
| STARTING | `POST voice/session` → null | ERROR | `VoiceEvent.Error` → controller **finalize** (`c0cad2c`) |
| STARTING | `connection_info` OK | PROVIDER_CONNECTING | provider chosen; gains, fallback getter, drain of queued commands; audio focus + route; {route re-apply +1/+4/+9 s (sequential delays 1000/3000/5000; errata §12)}; `provider.connect` |
| any pre-active | `session_started{voice, voice_initiator=true, voice_session_update}` | (same) | owner=true. The update is queued (no provider) or forwarded; cached in `lastSessionUpdate` |
| PROVIDER_CONNECTING (WebRTC) | DC OPEN | ACTIVE | drain `pendingCommands`. If no `session.update` was sent → re-send the cached one. Else if nothing is cached → log error (`d4bc698`) |
| PROVIDER_CONNECTING (WebRTC) | 15 s without success / SDP failure / exception | ERROR | cleanup → controller finalize |
| PROVIDER_CONNECTING (WS) | mic + speaker up | ACTIVE | — |
| ACTIVE ↔ SPEAKING ↔ THINKING ↔ TOOLUSE | provider events (§2.2–2.3) | … | ducking (§3.3). `TurnComplete` → orchestrator status "idle" (`VoiceController.kt:504-506`). Transcripts → chat bubbles `[voice] …` :483-500 |
| ACTIVE (WebRTC) | `session.updated` echo while `sessionUpdateSent==false` | ACTIVE | re-send cached `session.update` once :656-673 |
| any active | `voice_vad_state` | (same) | `vadState`/`vadDurationMs`. VoiceButton shows "listening Ns" after 3000 ms |
| any active | `VoiceEvent.ReconnectWarning(t)` | (same) | banner "Pausing in ~t s…" + reconnect beep. `Reconnecting` → banner. Back to Active clears it :532-543, :271-273 |
| any active | user stop | ENDING | WS `voice_stop`. {5000 ms → FINALIZING} :753-765 |
| any active | `voice_ending` (owner) | ENDING | {5000 ms → FINALIZING} :372-390 |
| ENDING / any | `voice_ended`/`voice_stopped` (owner) | FINALIZING | `finalizeStreamingForVoiceEnd()` :391-402 |
| any | `VoiceEvent.Error` | FINALIZING | system message + toast :507-527 |
| any | WS `Disconnected(willReconnect=true)` | (unchanged) | keep everything |
| any | WS `Disconnected(willReconnect=false)` and (config set or owner) | FINALIZING | clear `activeVoiceConfig` (`9db8f37`) |
| any (config set) | `ConnectionEvent.Reconnected` (genuine reconnects only, `initialConnectionDone` gate `P/connection/OrchestratorConnectionController.kt:140, 213-216`) | (same) | re-send `voice_start` with `activeVoiceConfig` :444-459. Without a config → send `start` with the resume checkpoint :460-470 |
| FINALIZING | (idempotent: `voiceStopFinalized`) | OFF | owner=false, remote=false, cancel timers, config=null, VAD idle; `vm.stop()` → state Off, unmute. **{1500 ms}** → `resumeWakeWord()` (await ack ≤2000) :774-800 |
| OFF | `session_started voice=false` while UI is Summarizing/Connecting | OFF | `clearPreStartState` (ghost-voice, `f5b339a`) :333-339 |

**Ownership sub-machine:**

- NOT_OWNER --start / `session_started(voice, initiator)`--> OWNER --finalize--> NOT_OWNER.
- NOT_OWNER --`voice_owner_active(active)`--> remote flag = active.
- `session_started(voice, !initiator)` → NOT_OWNER and remote=true.

**`session.update` delivery sub-machine (WebRTC):**

1. RECEIVED: cache it.
2. Provider null → QUEUED (VoiceManager channel).
3. `start()` drains it into the provider → PROVIDER_PENDING (provider list; **never cleared on connect**, `9515576`).
4. DC open → SENT (`sessionUpdateSent=true`), or MISSING → SELF_HEAL (send the cached copy), or no cache → DEFAULTS (log error).
5. `session.updated` echo while not sent → SELF_HEAL once.
6. `cleanup` resets the flag.

### 3.3 Echo ducking

**WS providers** (`EchoDuckController` + `WebSocketPcmProvider` + `MicCapture`):

| State | Event | Next | Side effects |
|---|---|---|---|
| UNDUCKED (gain = user gain) | speaker chunk enqueued | DUCKED | `saved=gain; gain=duckGain(0.05)`; log `[MIC_STATE] DUCK` :144-151. Each chunk updates `lastSpeakerChunkAtMs` and cancels any pending restore (`WebSocketPcmProvider.kt:374-379`) |
| DUCKED | capture loop: agent speaking and no chunk for >800 ms and no restore pending | DRAINING | `agentSpeaking=false`; `scheduleRestore("stale")` (`MicCapture.kt:123-128`) |
| DRAINING | poll every 80 ms. Restore when written frames stayed constant ≥400 ms **and** (head ≥ written **or** head unchanged ≥400 ms) | TAIL_WAIT | log drained / head-stuck. **No timeout** (`2ccee40`) |
| DRAINING | AudioTrack gone (head null) | UNDUCKED | restore |
| DRAINING / TAIL_WAIT | new speaker chunk | DUCKED | cancel restore |
| TAIL_WAIT | {1000 ms} | UNDUCKED | `RESTORE_IMMEDIATE(drained:stale)` |
| any ducked | barge-in (`speech_started` Qwen / `interrupted` Gemini) | UNDUCKED | flush queue + AudioTrack pause/flush/play, reset frame counter, `restoreImmediately("flush")` |
| any | user `setMicGain` while ducked | (same) | updates `saved` only. While unducked, applies now :103-112 |
| any | `setEchoDuckingGain` while ducked | (same) | applies immediately :119-128 |
| any | session cleanup | UNDUCKED | restore the saved gain :285-292 |

Muted (`userMuted`): mic chunks are dropped, not sent (`MicCapture.kt:119`).

**WebRTC** (`OpenAIVoiceProvider.kt:238-278`, :674-758):

| Event | Effect |
|---|---|
| `response.created` / `output_audio_buffer.started` | cancel restore; DUCK (`started` also sets `agentPlaying=true`) |
| `output_audio_buffer.stopped` | `agentPlaying=false`; RESTORE after 2000 ms |
| `output_audio_buffer.cleared` | DUCK, then RESTORE after 2000 ms |
| `speech_started` with `!agentPlaying` | RESTORE now |
| `speech_started` with `agentPlaying` | suppressed (echo) |
| `response.done` | does **not** restore (audio can play 6 s+ after it; `687442e`) |
| cleanup | restore the saved gain |

### 3.4 Audio routing

**Decision** (`AudioRouter.pickRoute` :153-186):

| `AudioOutput` | Route |
|---|---|
| AUTO (default) | SystemDefault |
| EARPIECE | Earpiece |
| LOUDSPEAKER | Loudspeaker |
| WIRED | WiredHeadphone(device or null before M) |
| BLUETOOTH | HFP connected → BluetoothCallAudio. Else A2DP connected → BluetoothMedia on WS providers / BluetoothUnsupported(A2DP_REQUIRES_WS_PROVIDER) on WebRTC. Else BluetoothUnsupported(BT_NOT_AVAILABLE) |

**Apply** (:373-417):

1. `mode` = NORMAL for SystemDefault and BluetoothMedia, otherwise IN_COMMUNICATION.
2. Per route:
   - SystemDefault: clear comm device (S+), speakerphone off, stop SCO.
   - Earpiece / Loudspeaker: `setCommunicationDevice(type)` on S+, else (or if no such device) clear + `isSpeakerphoneOn = (loudspeaker)` + stop SCO.
   - BluetoothCallAudio: S+ `setCommunicationDevice(bt)`, else speakerphone off + `startBluetoothSco` + `scoOn=true`.
   - BluetoothMedia: clear + speakerphone off + stop SCO.
   - Unsupported → Loudspeaker.
   - Wired: S+ pin if listed, else clear + speakerphone off + stop SCO.
3. Return `SpeakerMode`: MEDIA only for BluetoothMedia, **CALL for everything else including SystemDefault** (`c14c837`).
4. The VoiceManager forwards the mode and preferred device to the provider. Only WS providers rebuild the AudioTrack (`PcmPlayback.setSpeakerMode` :251-265).
5. BluetoothUnsupported → toast via `VoiceEvent.RoutingFallback` (`VoiceManager.kt:590-600`).

**When routing is (re)applied:**

- session start (`requestAudioFocus`)
- +1 / +3 / +5 s (`d36d31b`)
- `AudioDeviceCallback` added/removed (M+ only, `VoiceManager.kt:611-626`)
- `setAudioOutput` while a session is active (:547-551)

**Release on stop:** clear comm device (S+), stop SCO, mode → NORMAL (`AudioRouter.kt:556-572`), abandon focus, unregister the callback.

### 3.5 Foreground service lifecycle (`AssistantService`)

| State | Event | Next | Side effects |
|---|---|---|---|
| NOT_RUNNING | `start` / `updateWakeWord` / `pause` / `resume` intent (`startForegroundService` on O+) | CREATED | `onCreate`: prefs; notification channel (O+, IMPORTANCE_LOW); register SCREEN_ON+USER_PRESENT; start the recents `/dev/input/event2` thread; register LocalBroadcast receivers for UNHEALTHY and MIC_(UN)AVAILABLE; launch the Vosk preload :410-452 |
| CREATED/RUNNING | every `onStartCommand` | RUNNING | `startForeground(1001, notification)` :456 |
| RUNNING | UPDATE{enable, talk, wake, gain, sensitivity, url} | RUNNING | persist all 6 to prefs; enable → `startWakeWord` (dedupe), disable → `stopWakeWord` (clears the dedupe key) :499-527, :600-609 |
| RUNNING | PAUSE{ack} | RUNNING (`voiceSessionActive=true`) | `detector.pause()`; complete the ack :464-468 |
| RUNNING | RESUME{ack} and `voiceSessionActive` | RUNNING (`false`) | re-read enabled from prefs; enabled → **full** `startWakeWord` (not `resume`) :484-498; complete the ack |
| RUNNING | RESUME and not `voiceSessionActive` | RUNNING | ignore (duplicate-resume fix `495b5d9`); complete the ack |
| RUNNING | null intent (sticky restart) | RUNNING | restore from prefs; start if enabled :528-547 |
| RUNNING | SCREEN_ON / USER_PRESENT | RUNNING | {debounce 300 ms} → `rearmWakeWord`: skip if `voiceSessionActive`; reload prefs; null → start; paused → resume; not active → start; **active → restart** :367-408 |
| RUNNING | `RECOGNIZER_UNHEALTHY` | RUNNING | if not voice-active and enabled → `startWakeWord` (dedupe applies) :282-288 |
| RUNNING | `MIC_UNAVAILABLE` / `MIC_AVAILABLE` | RUNNING | notification text ↔ "Wake word stalled — mic held by another app" :306-325 |
| RUNNING | recents key held ≥600 ms (`/dev/input`) | RUNNING | `bringToForeground` + `WAKE_WORD_DETECTED` :647-664 |
| RUNNING | `onDestroy` | NOT_RUNNING | unregister; `detector.release`; stop the recents thread; cancel the scope :554-569 |

**`startWakeWord` dedupe:**

- Key: (talk, wake, gain). Window: <3000 ms on `elapsedRealtime` :571-598.
- **Not part of the key:** `serverUrl` and `talkSilenceSensitivity` (see B4).

---

## 4. Tuned constants — complete table (value · file:line · why · class)

### 4.1 Wake-word detector (`P/voice/WakeWordDetector.kt`)

| Constant | Value | Line | Why / origin | Class |
|---|---|---|---|---|
| `SAMPLE_RATE`, mono, PCM16 | 16000 | :211-213 | Vosk model rate; Whisper WAV | *wire* |
| `RMS_THRESHOLD` (divided by wake gain) | 70.0 | :239 | `c60cd08` Detour 5. A300M "wake up" peaks RMS 35–82; 200 was unreachable. 200→100 was reverted (`2acf57c`) | **LB** |
| `ACTIVITY_HOLD_MS` | 30 | :243 | Ignore clicks/pops before engaging the engine | **LB** |
| `PRE_BUFFER_MS` | 500 | :397 | Vosk V5. Replays the leading edge ("wake" of "wake up"). 5/5 first-try on A300M | **LB** |
| Monitor buffer | `max(minBuf, 3200 B)` | :648 | ≥100 ms reads | **LB** |
| Mic retry interval | 500 ms | :688, :702, :723 | `95201b5`/`5bde0d1` mic held by WebRTC/PTT | **LB** |
| `MIC_RETRY_WARN_THRESHOLD` | 8 (~4 s) | :377 | `cff6afd` Inc 9 notification swap | **LB** |
| `POST_WAKEWORD_DELAY_MS` | 3000 | :261 | Cooldown so a consumed/rejected utterance can't re-fire | **LB** |
| `POST_RECOGNITION_BASE_MS` / `_MAX_MS` | 1000 / 30000 (1,2,4,8,16,30 s) | :269-270 | SR path only: battery + SR beep | **LB** |
| `POST_VOSK_NOMATCH_MS` | 500 | :296 | `043340a`: NoMatch fed into backoff saturated at 30 s → "worked once then stopped" | **LB** |
| `SPEECH_FLOOR_RMS` (pre-Whisper gate) | 30.0 | :258 | `70283e3`: phantom matches on silence + whisper-1 hallucination. A300M floor 4–11 | **LB** |
| `COMMAND_ABS_SPEECH_FLOOR` = `ADAPTIVE_VOICE_FLOOR_MIN` | 30.0 | :137, :182 | `ae1d958` loud-room runaway; adaptive VAD lower clamp | **LB** |
| `SILENCE_FLOOR_ATTACK` / `_RELEASE` | 0.30 / 0.02 | :159-160 | `4f689a4` adaptive floor: fast down / slow up so a loud word can't swallow the silence | **LB** |
| `DEFAULT_TALK_SILENCE_SENSITIVITY` K | 2.0 (slider 1–4, `Models.kt:430`) | :174 | `4f689a4`: speech ≥2× room floor | **LB** |
| Seed floor | min RMS of the pre-roll frames | :1204-1209 | Avoid ~1 s convergence that clips the command's first words | **LB** |
| `ONSET_SUSTAIN_MS` | 300 | :192 | `ae1d958`: no recording UI on a single music spike | **LB** |
| `COMMAND_SILENCE_MS` | 1000 | :123 | `3efbe55` 1500→1200, `f934d09` →1000, field-tuned | **LB** |
| `COMMAND_MAX_MS` | 12000 | :200 | `ae1d958` (was 30000) | **LB** |
| `COMMAND_SPEECH_ONSET_TIMEOUT_MS` | 4000 | :209 | `ae1d958`: phantom prefix trigger aborts silently | **LB** |
| Capture frame | 3200 samples (200 ms) | :1211 | VAD granularity | **LB** |
| Mic source | `VOICE_RECOGNITION` <N; `VOICE_COMMUNICATION` ≥N | :656-659 | `55037c2`: HAL AGC continuity with the call (mic amplitude halved otherwise) | **LB** |
| Talk/wake variant parse | comma-split, trim, lowercase, distinct; no phonetic subs | :306-307, :468-474 | `e31d4fc` Inc 5 | **LB** |

### 4.2 Vosk (`P/voice/VoskRecognitionEngine.kt`, `P/voice/VoskWakeWordEngine.kt`, `P/voice/VoskModelLoader.kt`)

| Constant | Value | Line | Why | Class |
|---|---|---|---|---|
| `VOSK_RECOGNITION_TIMEOUT_MS` | 5000 | RE :54 | Covers a slow 1–2-word phrase | **LB** |
| `RMS_STARTED_THRESHOLD` | 30.0 | RE :62 | `beganSpeech` stamp; equals `SPEECH_FLOOR_RMS` | **LB** |
| Recognition read buffer | 6400 samples (400 ms) | RE :171 | Progress vs latency | **LB** |
| `MATCH_TAIL_MS` | 400 | RE :70 | `dd5567f`: Vosk fires mid-word; Whisper needs the full phrase | **LB** |
| `MAX_CONFIRM_WINDOW_MS` | 2000 | RE :78 | `54463e1`: 6 s+ uploads were slow and buried the phrase | **LB** |
| `MIN_PREFIX_WORDS` | 2 | WE :132 | `ae1d958`: a lone "hello" from noise must not fire | **LB** |
| Grammar | configured phrases + `"[unk]"` | WE :241-247 | Constrained decode. Without `[unk]` the recognizer hangs (memory) | **LB** |
| Match precedence | wake (realtime) before talk; substring `contains` | WE :165-180 | Detour 3 precedence | **LB** |
| Reset recognizer after a match | — | WE :91-93 | Prevents re-trigger storms | **LB** |
| Fresh `Recognizer` per cycle | — | RE :130-135 | No stale partials | **LB** |
| Model `vosk-model-small-en-us-0.15`, stamp-based extract to `filesDir/vosk-model` | — | ML :42-52, :152-160 | Re-extract on stamp change | *wire* |
| `noCompress` model dir | — | `A/build.gradle.kts:41-43` | `Model(path)` needs raw files | **LB** |
| Load failure sticky per process | — | ML :144, :177 | Don't burn 0.5–2 s retries | *plumb* |
| `vosk-android` | 0.3.47 | `A/build.gradle.kts:126` | Patched `libvosk.so` must match | **LB** |

### 4.3 SpeechRecognizer fallback (`P/voice/SpeechRecognizerEngine.kt`)

| Constant | Value | Line | Why | Class |
|---|---|---|---|---|
| `RECOGNIZER_HANG_WATCHDOG_MS` | 10000 | :63 | `187b419`: SR hangs after `onBeginningOfSpeech` | **LB** |
| `RECOGNIZER_REFRESH_AFTER_N` | 20 | :66 | `b753ac5` Detour 6 | **LB** |
| `RECOGNIZER_REFRESH_NO_SPEECH_SPIKE` | 2 | :69 | `b753ac5` | **LB** |
| `NO_SPEECH_HEALTH_THRESHOLD` | 8 | :73 | `39b1cec`: replaced the 2 h rebuild watchdog (`820241d` binder death) | **LB** |
| `CLIENT_ERROR_DELAY_MS` (ERROR_CLIENT 7 / NO_SPEECH 6 flat) | 1000 | :76 | `d93f7d7`: Google recognition service restarts in ~1 s | **LB** |
| `EXTRA_*` | free-form, `MAX_RESULTS` 5, `PARTIAL_RESULTS`, en-US, `PREFER_OFFLINE`, min length 200, complete silence 1500, possibly complete 1000 | ≈:320-330 | Tuned | **LB** |
| Beep-stream mute (RING, NOTIFICATION, SYSTEM, MUSIC) around `startListening`; `ADJUST_MUTE` on M+ / `setStreamMute` before | — | :90-95, ≈:289-312 | Suppress the SR beep | **LB** |
| `weChangedAudioMode` single-owner revert of `MODE_IN_COMMUNICATION` | — | :147, ≈:282-285 | `d36d31b`: a late callback flipped the mode under a live voice session | **LB** |

### 4.4 Whisper gate (`P/voice/WhisperConfirmer.kt`)

| Constant | Value | Line | Why | Class |
|---|---|---|---|---|
| `timeoutMs` | 10000 | :54 | `2f5ecd7`: A300M round trip ≈3.7 s (http ≈3.4 s); 2.5 s failed ~15/17 | **LB** |
| Model / params | `whisper-1`, `temperature=0`, `language=en`, json | :122-133, :222 | `70283e3`: `temperature=0` kills silence hallucination | **LB** |
| `normalize` | lowercase, non-`[a-z0-9\s]`→space, collapse | :215-216 | "Hello, my friend." must match | **LB** |
| `HALLUCINATION_BOILERPLATE` (exact match after normalize) | you, thank you, thank you very much, thanks for watching, thanks for watching the video, please subscribe, bye, bye bye, so, the, okay, i m sorry | :232-245 | `70283e3` | **LB** |
| Decision | wake-first substring on normalized text | :196-212 | Mirrors Vosk precedence | **LB** |
| Fail-closed on timeout/error/no key; 401 clears the cached key | — | :81-93, :141-147 | Design decision (2026-07-21) | **LB** |
| okhttp connect / read | 10 s / 30 s (shared `ApiClient.httpClient`) | `ApiClient.kt:35-37` | Hard ceiling | *plumb* |

### 4.5 Service / triggers

| Constant | Value | Line | Why | Class |
|---|---|---|---|---|
| `WAKE_START_DEDUPE_WINDOW_MS` (strict `<`, key talk/wake/gain, `elapsedRealtime`) | 3000 | `AssistantService.kt:122`, :135-143 | `0b2cbb5`: redelivery gaps of 20 ms and 1.3 s | **LB** |
| Screen re-arm debounce | 300 ms | :360-361 | SCREEN_ON + USER_PRESENT fire ms apart | **LB** |
| `bringToForeground` wake lock (`SCREEN_BRIGHT \| ACQUIRE_CAUSES_WAKEUP`) | 3000 ms | :208-212 | Lollipop screen-on when the activity is backgrounded | **LB** |
| Recents long press (both paths) | 600 ms | :625; `ButtonAccessibilityService` | UX | *UX* |
| Notification | id 1001, channel `assistant_service_channel`, LOW | :43-44, :683-697 | — | *plumb* |
| Pref keys `assistant_service_prefs`: `wake_word_enabled`, `turn_talk_word`, `realtime_wake_word`, `wake_word_mic_gain`, `talk_silence_sensitivity`, `server_url`, `button_trigger_enabled` | — | :99-105; `ButtonAccessibilityService` | Survive process death | *wire* (internal) |
| Defaults talk / wake / enabled | "my friend" / "wake up" / true | `Models.kt:421, 425-426` | `687442e` | *UX* |

### 4.6 Voice session

| Constant | Value | Line | Why | Class |
|---|---|---|---|---|
| `ENDING_ACK_TIMEOUT_MS` | 5000 | `VoiceController.kt:107` | `67a7958`: wait for `voice_ended` | **LB** |
| `MIC_RELEASE_DELAY_MS` | 1500 | :109 | `0bc612f`: WebRTC holds AudioRecord after `stop()`; 20+ retries otherwise | **LB** |
| `WAKE_WORD_ACK_TIMEOUT_MS` | 2000 | :111 | Inc 7 hand-off; falls through on timeout | *plumb* |
| Route re-apply | +1000 / +3000 / +5000 ms | `VoiceManager.kt:354-362` | `d36d31b`: WebRTC ADM / Samsung HAL re-pin the earpiece | **LB** |
| Audio focus | `GAIN_TRANSIENT_EXCLUSIVE`, `USAGE_VOICE_COMMUNICATION`/`SPEECH` (O+), `STREAM_VOICE_CALL` (pre-O) | :487-512 | — | **LB** |
| Raise `STREAM_VOICE_CALL` if 0 → 75% of max (≥1) | — | :519-528 | Ships muted on some devices | **LB** |
| Default mic gain / clamp | 1.0 / [0, 2] | :74, :453 | — | **LB** |
| Default echo-duck gain / clamp | 0.05 / [0, 1] | :75, :460; `Models.kt:432` | `b9e6352`: duck to 5%, not mute, so barge-in still works | **LB** |
| Controller event buffers | 64 | :143; providers | — | *plumb* |
| `POOL_PROBE_RETRY_MS` (orchestrator adoption) | 400 | `OrchestratorConnectionController.kt:85` | Cold-start empty pool | **LB** |
| Recovery backoff | 0 / 500 / 2000 ms | :267 | — | **LB** |
| WS ping / reconnect | 30000 / 3000 ms | `WebSocketManager.kt:42, 47` | `f77cd62` (backend pings every 15 s to keep the A300M radio awake) | **LB** |

### 4.7 Audio I/O

| Constant | Value | Line | Why | Class |
|---|---|---|---|---|
| `MIC_CHUNK_FRAMES` | 480 (20 ms @ 24 kHz) | `MicCapture.kt:217` | Matches the web cadence | **LB** / *wire* |
| `AGENT_SPEECH_STALE_MS` | 800 | :224 | Gemini bursty chunk gaps | **LB** |
| Mic buffer | `max(minBuf*4, rate*2/5)` | :74 | Jitter headroom | **LB** |
| `[MIC_PROBE]` window | every 50 chunks (~1 s): avg RMS, peak, gain, ducking | :131-141 | `1589cfb` diagnostics | *plumb* (keep the log) |
| `AudioRecord.read` error handling | `ERROR_INVALID_OPERATION`/`ERROR_BAD_VALUE` end the loop; 0 → continue | :110-118 | — | *plumb* |
| Speaker buffer | `max(minBuf*4, bytesPerSec*1.5)` (=72000 @ 24 kHz) | `PcmPlayback.kt:148-149` | Jitter for bursty upstream | **LB** |
| Write | `WRITE_NON_BLOCKING` on M+; blocking 3-arg write on API 21/22; catch `Throwable` | :194-208 | `20217b1`: `NoSuchMethodError` killed the process on Lollipop | **LB** |
| Full-buffer park + retry | 10 ms | :214-220 | — | *plumb* |
| Barge-in flush | pause → flush → play, reset `totalFramesWritten` | :103-126 | Head resets on flush | **LB** |
| HAL settle before mic open | 200 ms | `WebSocketPcmProvider.kt:251` | `55037c2` | **LB** |
| Start order | mic before speaker | :255-256 | First chunk can't echo into a cold mic | **LB** |
| Default in/out rates if absent | 24000 / 24000 | `WebSocketPcmProvider.kt:118-119`; `ApiClient.kt:443-446` | — | *wire* |

### 4.8 Echo duck (`P/voice/EchoDuckController.kt`)

| Constant | Value | Line | Why | Class |
|---|---|---|---|---|
| `MIC_RESTORE_TAIL_MS` | **1000** | :301 | `cffec38` (600→1000): the mic re-armed into the room tail | **LB** |
| `MIC_RESTORE_DRAIN_POLL_MS` | 80 | :305 | — | **LB** |
| `MIC_RESTORE_WRITES_QUIET_MS` | 400 | :310 | `2ccee40`: head≥written fired while chunks were still being fed | **LB** |
| No drain timeout | — | :180-185 | `2ccee40`: 4 s then 20 s caps both cut long answers | **LB** |
| Head-stuck fallback | — | :256-261 | Lollipop post-underrun head freeze | **LB** |
| Head read as unsigned 32-bit (`and 0xFFFFFFFF`) | — | :206, :225 | — | **LB** |

### 4.9 OpenAI WebRTC (`P/voice/OpenAIVoiceProvider.kt`)

| Constant | Value | Line | Why | Class |
|---|---|---|---|---|
| `CONNECTION_TIMEOUT_MS` | 15000 | :58 | — | *plumb* |
| Restore delay after `stopped`/`cleared` | 2000 ms | :253, :750, :757 | `687442e` (the commit said 1 s for cleared; the code is 2 s for both — code wins) | **LB** |
| SW AEC/NS/AGC on, HW AEC/NS off | — | :335-337, :360-361 | Field choice (CLAUDE.md "software-only AEC") | **LB** |
| goog* constraints | — | :374-387 | — | **LB** |
| `PeerConnectionFactory.initialize` once per process | — | :61, :340-346 | `2a33e8d` double init | **LB** |
| `RMS_LOG_INTERVAL` | 50 callbacks | :59 | diagnostics | *plumb* |

### 4.10 Routing and cues

| Item | Value | Line | Why | Class |
|---|---|---|---|---|
| SystemDefault → MODE_NORMAL + AudioTrack tagged CALL | — | `AudioRouter.kt:115-127`, :380-383 | `01d30ec` AUTO + `c14c837` tfa9895 amp gate | **LB** |
| Wake ack beep | 660→880 Hz, 90 ms each, 30 ms gap, amplitude 0.45, STREAM_MUSIC | `AssistantViewModel.kt:493-494` | `3c4dbba` | *UX* |
| Talk ack beep | 440 Hz, 150 ms | :502-503 | `3c4dbba` | *UX* |
| Reconnect beep on STREAM_MUSIC | — | :569-627 | STREAM_NOTIFICATION is muted during call audio on the A300M (`b586e4b`) | **LB** (stream) |
| VoiceButton "listening Ns" reveal | 3000 ms | `P/ui/components/VoiceButton.kt:38` | Increment B | *UX* |

---

## 5. Device- and API-level branches

### 5.1 API-level branches (all must exist in the minSdk-21 shared modules)

| Threshold | Branch | Where | Main app (minSdk 26) | Lite (A300M API 22) |
|---|---|---|---|---|
| API < 23 (M) | Vosk stderr shim: `System.loadLibrary("vosk-stderr-shim")` + `RTLD_GLOBAL` republish + preload `libvosk.so`; patched weak `stderr/stdin/stdout` | `VoskModelLoader.kt:78-111`; `A/src/main/cpp/`; `A/scripts/patch_vosk_weaken.py` | not needed | **required** |
| API < 23 | Blocking `AudioTrack.write(…)` (no `WRITE_NON_BLOCKING`); catch `Throwable` | `PcmPlayback.kt:194-208` | dead | **required** |
| API < 23 | Legacy `AudioTrack(streamType,…)` constructor (CALL→`STREAM_VOICE_CALL`, MEDIA→`STREAM_MUSIC`) | `PcmPlayback.kt:336-351` | dead | **required** |
| API < 23 | No `AudioManager.getDevices`: BT via `BluetoothAdapter.getProfileConnectionState`, wired via sticky `ACTION_HEADSET_PLUG`; no `setPreferredDevice`; no `AudioDeviceCallback` | `AudioRouter.kt:199-258, 312-347`; `PcmPlayback.kt:154`; `VoiceManager.kt:612` | dead | **required** (`20217b1` Settings crash) |
| API < 23 | SR beep mute via `setStreamMute` vs `adjustStreamVolume(ADJUST_MUTE)` | `SpeechRecognizerEngine.kt` ≈:289-312 | M+ path | required |
| API < 23 | literal error code `6` for NO_SPEECH | `d93f7d7` | — | required |
| API < 24 (N) | Mic source `VOICE_RECOGNITION` (Samsung HAL silences VOICE_COMMUNICATION) vs `VOICE_COMMUNICATION` | `MicCapture.kt:79-82`; `WakeWordDetector.kt:656-659`; `OpenAIVoiceProvider.kt:354-357` | `VOICE_COMMUNICATION` | `VOICE_RECOGNITION` |
| API ≥ 26 (O) | `startForegroundService`; `AudioFocusRequest`; notification channel | `AssistantService.kt:147-151, 174, 196, 248, 684`; `VoiceManager.kt:490-507, 531-536` | always | legacy `startService` / `requestAudioFocus(null, STREAM_VOICE_CALL, …)` |
| API ≥ 27 (O_MR1) | `setTurnScreenOn/ShowWhenLocked` vs window flags (+ `FLAG_DISMISS_KEYGUARD`) | `MainActivity.kt:175-185`; `VoiceShortcutActivity.kt:28-37` | API 26 still needs the flags | flags |
| API ≥ 31 (S) | `setCommunicationDevice`/`clearCommunicationDevice`/`availableCommunicationDevices`; BLE device types; `BLUETOOTH_CONNECT` runtime + `SecurityException` catch | `AudioRouter.kt:200-231, 433-473, 480-487, 505-506, 531-541, 557`; `MainActivity.kt:228-234` | required | legacy speakerphone/SCO |
| API 23..30 | BT call audio = SCO device from `getDevices` when the HEADSET profile is connected | `AudioRouter.kt:208-213` | API 26–30 | — |
| API ≥ 33 | `POST_NOTIFICATIONS`; typed `getParcelableExtra` | `MainActivity.kt:98, 213-219` | required | — |
| API ≥ 34 target | FGS type microphone, while-in-use restriction (risk R5) | `AssistantService.kt:456` | **must handle** | n/a |

### 5.2 Device-specific behaviour

| Device | Behaviour | Where / origin |
|---|---|---|
| **A300M** (Samsung SM-A300M, MSM8916, Lollipop 5.0.2 / API 22, 888 MB real RAM, 32-bit Cortex-A53) | RMS threshold/floors calibrated on its mic (floor 4–11, "wake up" 35–82) | `WakeWordDetector.kt:215-239, 245-258` |
| | tfa9895 amp silences STREAM_MUSIC in MODE_NORMAL → AUTO route must tag CALL | `c14c837`; `AudioRouter.kt:118-126` |
| | HAL re-pins the earpiece during native audio init → +1/3/5 s re-apply | `d36d31b` |
| | AGC state continuity between wake and call → same mic source + 200 ms settle | `55037c2` |
| | Head-stuck after underrun → drain fallback | `EchoDuckController.kt:256-261` |
| | STREAM_NOTIFICATION muted during call audio → beeps on STREAM_MUSIC | §4.10 |
| | WiFi power-save drops PONGs → backend 15 s ping | `f77cd62` |
| | SR binder death after hours; IPC bind 0.8–3 s clips the leading edge → Vosk | `820241d`, `wakeword_subsystem.md` |
| | `sec_touchkey` `/dev/input/event2` KEY_APPSWITCH monitor | `AssistantService.kt:613-675` |
| | Whisper RTT ≈3.7 s → 10 s timeout | `2f5ecd7` |
| | Compose streaming-markdown RenderThread stack overflow → avoid heavy Compose on the lite app | `31c2fbf` |
| | LMK killed the FGS after 5.5 min voice; 125 packages debloated | memory `android_peripheral_project.md` |
| | ABI `armeabi-v7a` | `A/build.gradle.kts:19-21` |
| **Xiaomi** (2412DPC0AG, modern Android) | ICE `DISCONNECTED` teardown from the signalling thread → SIGABRT after 2 h20 m → only react to `FAILED`, teardown off-thread | `0196e2a` |
| | `BLUETOOTH_CONNECT` SecurityException crash in Settings | `ef2aaae` |
| | `session.update` loss logs were first captured here | `8424f0f`, `9515576` |
| | Passive peer in the multi-device ownership test | `afe77a4` Compose crash |
| **POCO X7** (new main target) | No field data yet. Expect HyperOS autostart/battery kill, Android 14+ FGS/BAL rules | R5 |
| Any device without Google speech services | `WakeWordDetector.start()` refuses to run even though Vosk would work | R6 |

---

## 6. Foreground service, notifications, boot, permissions, companion app

### 6.1 Manifest (`A/src/main/AndroidManifest.xml`)

**Permissions:**

| Permission | Line | Note |
|---|---|---|
| INTERNET, ACCESS_WIFI_STATE, RECORD_AUDIO | :6-8 | |
| MODIFY_AUDIO_SETTINGS | :13 | `6fb1e54` |
| BLUETOOTH | :16-17 | `maxSdk` 30 |
| BLUETOOTH_CONNECT | :18 | |
| FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE | :19-20 | |
| POST_NOTIFICATIONS | :21 | |
| WAKE_LOCK | :22 | |

There is no `RECEIVE_BOOT_COMPLETED`; the companion app handles boot.

**Components:**

| Component | Line | Notes |
|---|---|---|
| `MainActivity` | :36-60 | `showWhenLocked`, `turnScreenOn`, share targets |
| `VoiceShortcutActivity` | :63-76 | `ACTION_ASSIST` |
| `AssistantService` | :78-82 | `foregroundServiceType="microphone"` |
| `ButtonAccessibilityService` | :85-96 | key filtering, `typeAllMask` |
| `AssistantVoiceInteractionService` | :99-110 | misconfigured, B6 |

Cleartext is allowed globally (`network_security_config.xml`).

**Runtime permissions:** `MainActivity.kt:202-239` requests RECORD_AUDIO, POST_NOTIFICATIONS (33+) and BLUETOOTH_CONNECT (31+). The results are ignored (:51-59).

### 6.2 Service

See §3.5. The service starts from `LaunchedEffect(Unit)` (`MainActivity.kt:403`). Config arrives from a `LaunchedEffect` keyed on 6 settings (:415-432). That is the single ingress per `9200d50`; the gain is always explicit per `d6181b1`.

### 6.3 Trigger entry points (all → `ACTION_WAKE_WORD_DETECTED` LocalBroadcast → MainActivity only)

| Source | Where | Gate |
|---|---|---|
| Vosk/SR match (Whisper-confirmed) | `WakeWordDetector.kt:1045-1046, 928` | `enableWakeWord` |
| Recents via `/dev/input/event2` | `AssistantService.kt:647-664` | **none** (B5) |
| Recents via accessibility | `ButtonAccessibilityService` | `button_trigger_enabled` (mirrored by the VM, `AssistantViewModel.kt:188-189`) |
| Assist gesture | `VoiceShortcutActivity` | — |

The receiver exists only while MainActivity lives (`MainActivity.kt:136-144, 197-200`). That makes voice Activity-bound (R2).

### 6.4 `android-device` (`com.assistant.device`) — stays separate and unchanged

**What it does:**

- Build: minSdk 21, `androidx.core` only.
- Manifest permissions: WRITE_SECURE_SETTINGS (adb grant), RECEIVE_BOOT_COMPLETED, ACCESS_NOTIFICATION_POLICY, ACCESS_WIFI_STATE, FOREGROUND_SERVICE, WAKE_LOCK, GET_TASKS.
- `BootReceiver` (`D/receiver/BootReceiver.kt:54-107`), in order:
  1. 15 s wake lock.
  2. WiFi-ADB settings.
  3. CPU governor (root-only).
  4. **DND total silence** (`D/util/DndUtil.kt:18-44`).
  5. `startService(WatchdogService)`.
  6. Launch the assistant 3 s later.
- `WatchdogService` (`D/service/WatchdogService.kt`): every 30 s, checks `getRunningServices(100)` for **any service whose `packageName == "com.assistant.peripheral"`** (:252-266). If none is found, it starts `getLaunchIntentForPackage("com.assistant.peripheral")`, falling back to the explicit `com.assistant.peripheral.MainActivity` (:268-277).

**Hard contract the lite app must satisfy (because `android-device` is unchanged):**

1. **`applicationId` must be `com.assistant.peripheral`.** It is hard-coded in `BootReceiver.kt:43-44` and `WatchdogService.kt:183-184`. Corollary: the main app (POCO) should take a **different applicationId**, or the two can never coexist on one device. That is acceptable if they never share a device, but it must be decided explicitly.
2. **A LAUNCHER activity must exist** (`getLaunchIntentForPackage`). If none resolves, the fallback FQCN `com.assistant.peripheral.MainActivity` must exist.
3. **A long-running service must exist in that package whenever the app is healthy.** Otherwise the watchdog relaunches the activity every 30 s. Conversely, a dead Activity with a live service is *not* detected (R2), so the lite app must not depend on its Activity for voice.
4. **Audio cues must use STREAM_MUSIC.** DND total silence is on, and (on Lollipop) zen mode can suppress notification/ring streams.
5. The lite app must **not** need WRITE_SECURE_SETTINGS or boot permission itself; the companion provides those.

---

## 7. Coupling analysis (what the UI needs from the voice core)

### 7.1 Today's dependencies of voice code on the app

| Voice-side file | App-side dependency | Nature |
|---|---|---|
| providers, `VoiceProvider.kt` | `data.VoiceState` | model |
| `VoiceManager`, `AudioRouter` | `data.AudioOutput` | model |
| `VoiceManager`, `OpenAIVoiceProvider`, `WhisperConfirmer`, `WakeWordDetector:529` | `ApiClient`: `getVoiceConfig`, `startVoiceSession`, `fetchOpenAiKey`, `httpClient` only | narrow REST |
| `WakeWordDetector:1143` | `AssistantService.bringToForeground` → `MainActivity` | voice↔service↔activity cycle |
| `AssistantService` | `MainActivity` class literal (:214, :703), `R` | host coupling |
| `VoiceController` | `ChatController` (`appendOrchestratorMessage`, `setOrchestratorSessionStatus`, `finalizeStreamingForVoiceEnd`, `buildStartMessage`, `orchestratorCurrentLocalId`, `orchestratorJsonlSessionId`, `orchestratorCurrentSessionId`, `isOrchestratorSession` — `P/chat/ChatController.kt:139, 281, 1555-1625`), `OrchestratorConnectionController.events`, `WebSocketManager`, WS DTOs, `AppSettings`, `ChatMessage`, `AudioRecorder` | **the real seam** |
| `AssistantViewModel` | builds the controller, beeps, `button_trigger_enabled` mirror, WS fan-out | glue |
| `MainActivity` | broadcast receivers + service intents | glue |

### 7.2 What either UI needs from the voice core (derived contract)

**State (read):**

- connection state + `noActiveOrchestrator`
- `voiceState`, `wakeConfirming`, `isRecording` (talk capture)
- `remoteVoiceActive`, `isMuted`
- `vadState`/`vadDurationMs`, `voiceReconnectBanner`
- wake-word health (armed / paused / mic-stalled / Whisper-offline)
- last user/assistant transcript
- BT/wired availability

**Commands:**

- connect/reconnect
- startVoice / stopVoice, toggleMute
- start/stop PTT (main app only)
- settings updates (§4 user-tunable: wake enable/phrases/gain/talk sensitivity, mic gain, echo-duck gain, audio output, button trigger, server URL)

**One-shot events:** toasts (routing fallback, voice error), transcript/message appends for the chat bucket, cues (beeps).

**Chat coupling** (main app only): transcript appends to the orchestrator bucket, `[Voice message]` bubbles, "idle" status on turn complete, finalize streaming on voice end. The lite app only needs the last-exchange text.

---

## 8. Proposed module design for the NEW code

Design rules:

1. **Pure policy vs platform adapters.** Every constant, FSM and decision lives in pure-Kotlin classes with injected `Clock`/dispatchers, unit-testable on the JVM. Android classes (AudioRecord, AudioTrack, AudioManager, WebRTC, Vosk JNI, Service) are thin adapters behind ports.
2. **Constants in one `Tuning` object per module.** Use `const val` with a KDoc `why` + origin commit. A parity test pins each value (§10.1). No runtime-configurable tuning except the existing user sliders.
3. **The voice session is owned by the service, not the Activity** (fixes R2/R3 structurally). The UI binds and observes.
4. **minSdk 21 for everything shared.** Avoid AndroidX artifacts that have raised minSdk (verify each), and avoid Compose in shared modules.

| Module | minSdk / type | Contents (new code) | Main ports / APIs |
|---|---|---|---|
| `:core:model` | pure Kotlin JVM | `VoiceUiState`, `VoiceEvent`, `VoiceConfig`, `ConnectionInfo`, `AudioOutput`, `WakeSignal`, settings data classes, protocol DTOs | — |
| `:core:protocol` | pure Kotlin JVM | JSON codec for every WS message in §2.1. Encodes defaults (`voice_initiator` default false), delta drop list, **recursive** (not shallow) conversion of `voice_event` payloads (fixes B1) | `ProtocolCodec` |
| `:core:network` | Android lib, 21 | okhttp WS client (ping 30 s, reconnect 3 s, `willReconnect`, audio-frame log suppression), `VoiceApi` REST (3 endpoints), shared OkHttpClient | `OrchestratorSocket`, `VoiceApi` |
| `:core:session` | Android lib, 21 (logic pure) | Orchestrator adoption (pool probe + 400 ms retry), genuine-reconnect gating (`initialConnectionDone`), recovery 0/500/1000 (errata §12), resume checkpoint, `start` builder | `OrchestratorSession` (localId, sdkId, isOrchestrator, events Adopted/Reconnected/NoOrchestrator) |
| `:core:audio` | Android lib, 21 | **Pure:** `EchoDucker` FSM (§3.3), `RouteDecider` (§3.4), PCM utils (RMS, gain, WAV, b64). **Adapters:** `MicSource` (AudioRecord, source policy §5), `PcmSink` (AudioTrack, write policy, flush, CALL/MEDIA rebuild **with single writer**, fixes B3), `RouteApplier` (AudioManager), `AudioFocus`, `DeviceWatcher` | `MicSource`, `PcmSink`, `PlaybackClock` (head/written) |
| `:core:voice` | Android lib, 21; depends on `stream-webrtc-android` | **Pure:** `VoiceSessionMachine` (§3.2), ownership, `SessionUpdateDelivery` (§3.2 sub-FSM), provider event parsers (OpenAI/Qwen/Gemini → `VoiceEvent`), OpenAI duck policy (§3.3). **Adapters:** `WebRtcTransport` (threading rule: no dispose on callback threads; thread-safe pending queue, fixes B2), `WsPcmTransport` | `VoiceSessionController` (commands + `StateFlow<VoiceUiState>`), `TranscriptSink`, `VoiceCues` |
| `:core:wakeword` | Android lib, 21; NDK 26.1, CMake shim, patched `libvosk.so` (v7a+arm64), model asset (`noCompress`), vosk 0.3.47 | **Pure:** `WakeLoopMachine` (§3.1, explicit states incl. CONFIRMING/CAPTURING/COOLDOWN), `RmsGate`, `PreBuffer`, `NoiseFloorTracker`, `TalkVad`, `VariantMatcher` (findMatch/prefix), `WhisperDecision`, backoff policy. **Adapters:** `VoskEngine` (single-thread confinement incl. close — fixes R4), `SpeechRecognizerEngine` (port verbatim; V6 deferred), `VoskModelStore`, `WhisperClient` | `WakeWordEngine` (start/pause/resume/stop, `Flow<WakeSignal>`, health) |
| `:core:voice-host` | Android lib, 21 | `VoiceHostService` (FGS microphone): owns socket + session + voice + wake; pause/resume hand-off with ack; screen re-arm that **never restarts a detector in CONFIRMING/CAPTURING** (R3); mic-stalled notification; trigger ingress (accessibility, assist); boot-agnostic. Exposes a bound `VoiceHost` (StateFlows + commands) | `VoiceHost` interface; `HostConfig` (launch activity class, notification strings/icons) |
| `:app:main` | **minSdk 26**, Compose M3 | Chat, sessions, settings, a voice bar bound to `VoiceHost` | — |
| `:app:lite` | **minSdk 21**, applicationId `com.assistant.peripheral` (§6.4); plain Views or minimal pinned Compose | Glanceable voice face (§9) | — |

**Interfaces the host needs from the app** (from §7):

```kotlin
interface TranscriptSink {
    fun userTranscript(t: String)
    fun assistantTranscript(t: String)
    fun system(t: String)
    fun voiceMessageSent()
    fun turnComplete()
    fun voiceEnded()
}
interface VoiceCues {
    fun wakeAck()
    fun talkAck()
    fun reconnect()
}   // default impl = §4.10 tones on STREAM_MUSIC
interface HostConfig {
    val launchActivity: Class<out Activity>
    val notification: NotificationSpec
}
interface VoiceHost {                                  // what UIs bind to
    val state: StateFlow<VoiceUiState>                 // aggregate of §7.2 state
    val events: Flow<VoiceUiEvent>                     // toasts, transcripts
    fun startVoice()
    fun stopVoice()
    fun toggleMute()
    fun updateSettings(s: VoiceSettings)
    fun connect()
}
```

**Risks of the rewrite:**

1. **Silent loss of tuned behaviour.** Mitigate with §10 parity tests, written *before* the new code against the old implementation where possible.
2. **Native packaging** (shim, patched lib precedence over the AAR inside a library module). Verify the merged APK's `lib/` contains the patched `libvosk.so`.
3. **Ownership move to the service changes timing** (Activity no longer in the loop). Re-validate the realtime handoff on the A300M with logcat markers.
4. **Release minify:** add consumer ProGuard rules (JNA `com.sun.jna.**`, `org.vosk.**`, `org.webrtc.**`). Today `A/proguard-rules.pro` has 10 lines and release is untested.
5. **Pinned dependency versions at minSdk 21:** WebRTC 1.1.1, okhttp 4.12, coroutines 1.7.3. The main app can't bump them independently.
6. **`android-device` applicationId lock-in** (§6.4).
7. **Android 14+ FGS microphone restrictions on POCO** (R5): start the host only from a foreground context, use `ServiceCompat.startForeground(…, MICROPHONE)`, and never re-`startForeground` from background intents.

---

## 9. Lite app (A300M) requirements

### 9.1 What the A300M exercises daily

- Wake word, both phrases (defaults `Models.kt:421, 425-426`; all field validation on serial 06e4f224).
- Realtime voice with the backend-default provider.
- Talk same-mic messages.
- Screen-off wake → foreground + `showWhenLocked`.
- Ack beeps.
- Recents long-press (accessibility and/or `/dev/input`).
- Settings sliders: wake sensitivity, talk auto-stop, mic gain, echo duck, audio output.
- Server URL / scan / auto-connect.
- Ownership "Active elsewhere" (a second device on the same orchestrator).
- All Lollipop branches of §5.1 and the A300M items of §5.2.
- RAM: 888 MB. Vosk is ~80–100 MB resident. Today's app idles at ~85 MB without Vosk.

### 9.2 Minimal feature set

1. **One full-screen state face** (big colour + icon + one word): Wake armed · Confirming · Recording · Sending · Connecting/Preparing · Listening · Speaking · Thinking/Tool · Ending · Active elsewhere · Error · Offline / Mic stalled / Whisper unreachable (R10).
2. Wake word + talk + Whisper gate.
3. Realtime voice with ownership.
4. Ack cues (the primary channel when nobody is looking).
5. A single big start/stop target + mute; recents/assist triggers.
6. Connection status + server URL + scan + auto-connect; orchestrator auto-adopt (no conflict dialog).
7. One settings screen with the §7.2 tunables.
8. Last-exchange text (last user + assistant transcript, large type, no markdown).
9. Toasts + reconnect banner.
10. Foreground notification with the mic-stalled text.

**Out of scope:** history, agent tab, markdown, uploads, system config (do it from web), PTT button.

**UI technology:** plain Views recommended (A300M RenderThread history, RAM).

**Companion:** `android-device` stays separate; satisfy the §6.4 contract.

---

## 10. Proposed parity-test list

### 10.1 Unit tests — constants (JVM, one assertion per constant)

Pin **every** row of §4 marked **LB** or *wire*. The table below groups them; a reviewer can diff it against §4.

| Test class (new) | Pins |
|---|---|
| `WakeTuningTest` | 70.0, 30 ms, 500 ms pre-buffer, 3200 B / 3200-sample frames, 500 ms mic retry, 8, 3000, 1000/30000, 500 (Vosk NoMatch), 30.0 ×3, 0.30/0.02, K=2.0, 300, 1000, 12000, 4000, 16 kHz |
| `VoskTuningTest` | 5000, 30.0, 6400, 400, 2000, `MIN_PREFIX_WORDS=2`, grammar ends with `[unk]` |
| `SrTuningTest` | 10000, 20, 2, 8, 1000, the full `EXTRA_*` map |
| `WhisperTuningTest` | 10000 ms, `whisper-1`, temperature "0", language "en", the boilerplate set (exact) |
| `ServiceTuningTest` | 3000 (strict `<`), 300 debounce, 3000 wake lock, 600 long press, pref keys |
| `SessionTuningTest` | 5000, 1500, 2000, route re-apply delays [1000,3000,5000] run sequentially (= +1/+4/+9 s), 75%, gains 1.0/0.05 + clamps, 400 probe, 0/500/1000, 30000/3000 |
| `AudioTuningTest` | 480, 800, `max(minBuf*4, rate*2/5)`, `max(minBuf*4, bps*1.5)`=72000 @24k, 200 settle, 10 ms retry |
| `EchoDuckTuningTest` | 1000, 80, 400, no timeout |
| `WebRtcTuningTest` | 15000, 2000 restore, HW AEC/NS off, SW on |

### 10.2 Unit tests — transitions (JVM with fake clock, mic, track, transport)

Port the existing `T/voice/parity/*` intent (15 files: `AdaptiveVad`, `BuildVariants`, `EchoDuckController`, `FinishRecognition`, `MicUnavailable`, `NoSpeechHealth`, `PauseResumeAck`, `PendingBackendCommands`, `StartWakeWord`, `VoiceController`, `VoskEngine`, `VoskModelLoader`, `WakeWordFSM`, `WakeWordStart`, `WhisperConfirmerDecide`) and add the following.

**Wake loop:**

- RMS gate needs ≥30 ms sustained; the threshold scales by 1/gain; gain 0 → base threshold.
- Pre-buffer keeps the last ⌈8000/readSize⌉ reads and is fed first.
- Realtime-first precedence; substring match; the prefix needs ≥2 leading words, never on a single-word variant, never for wake variants.
- Post-match tail of 400 ms, trimmed to the trailing 2000 ms.
- Speech-floor gate rejects peak < 30 *without* calling Whisper and emits CONFIRM_FAILED.
- Whisper: confirmed → WAKE_DETECTED. Timeout/err/no-key → CONFIRM_FAILED. 401 clears the cached key.
- Talk: no onset within 4000 → null, no broadcast. Onset after 300 ms sustained → TALK_DETECTED exactly once. End 1000 ms after the last voice frame. Cap 12000. Adaptive floor attack/release math. Seed = min pre-roll RMS.
- Talk confirm emits no CONFIRMING. Confirmed → TALK_MESSAGE_CAPTURED with a valid 16 kHz WAV. Rejected → CONFIRM_FAILED.
- Re-arm delays: match 3000, Vosk NoMatch 500 (no backoff accumulation over 10 consecutive NoMatches), SR miss 1→30 s, NoSpeech/flat 1000, Cancelled none.
- `pause()` during RECOGNIZING/CAPTURING: no broadcast, mic released, no re-arm.

**Service:**

- Dedupe (same key <3000 deduped; =3000 allowed; a disable clears the key).
- RESUME while not voice-active is ignored but acked.
- RESUME re-reads enabled.
- Screen-on during voice is skipped.
- **Screen-on during CONFIRMING/CAPTURING does not restart** (new requirement, R3).
- Sticky restart restores from prefs.
- Mic-stalled toggles the notification text once per stretch.

**Voice session:**

- Every row of §3.2, specifically:
  - pause ack awaited ≤2000 then proceed
  - `voice_start` payload fields
  - ownership gating of command/ending/ended/stopped
  - `voice_owner_active` only affects non-owners
  - `session_started(voice, !initiator)` → remote=true
  - missing `voice_initiator` → not owner
  - Ending 5000 timeout → finalize
  - finalize idempotent; 1500 ms then resume
  - Error → finalize + toast
  - Disconnected(true) keeps; Disconnected(false) finalizes and clears config
  - Reconnected re-sends `voice_start` only when a config is set and never on the first connect
  - ghost-voice `clearPreStartState`
- `session.update` delivery: queued pre-provider → drained → not cleared at connect → DC-open self-heal → `session.updated` self-heal → reset on cleanup. Plus a 100k-`trySend` stress test.
- WS provider: `summarizing` ignored when mid-call; `error` → Error + cleanup + SessionEnded; Go-duration parser ("50s", "30m0s", "1h30m0s", "500ms", junk→null).
- Parsers: OpenAI GA and legacy transcript names; Qwen barge-in flush; Gemini user-delta coalescing; `interrupted` drops staged text; **`modelTurn.parts` and `toolCall.functionCalls` parsed from both JSONArray and List** (B1).

**Echo duck:** the §3.3 table rows. 1:1 DUCK↔RESTORE invariant. A chunk during drain or tail cancels. Barge-in restores immediately. `setMicGain` while ducked defers; `setEchoDuckingGain` while ducked applies. Cleanup restores. Head-stuck fallback. Unsigned head.

**OpenAI duck:** created/started duck; stopped/cleared → restore at +2000; `speech_started` with `!agentPlaying` restores now; `response.done` does not restore.

**Routing:** decision table §3.4 for every (output × provider kind × BT class), at both API levels (21 and 31+) via a fake AudioManager. SystemDefault → CALL + MODE_NORMAL. Release returns to NORMAL.

**Playback:** single writer after a mode rebuild (B3); flush resets the counter; Lollipop write path never calls the 4-arg write.

### 10.3 Device field-test scenarios (run on the A300M for lite, POCO for main; capture logcat)

Keep greppable log markers equivalent to today's:

- `[MIC_STATE] DUCK`, `RESTORE_DRAIN(...)`, `RESTORE_IMMEDIATE(...)`
- `[MIC_PROBE]`, `Speaker started: rate=24000Hz bufSize=72000 mode=…`, `Mic started: rate=…Hz source=6 bufSize=6400`
- `[VM] ===== SESSION START ===== … pendingCommands=N`, `start: draining N pre-provider backend command(s)`
- `Vosk match`, `Vosk talk-prefix trigger`, `Whisper CONFIRMED`/`REJECTED`, `Whisper timing: …`, `Pre-Whisper gate`
- `Talk command speech onset confirmed`, `Command ended on 1000ms silence`

| ID | Scenario | Pass criteria |
|---|---|---|
| F1 | "wake up" at arm's length, quiet room, 10 tries | ≥9 confirmed first try; voice Active ≤ (Whisper RTT + 3 s); OpenAI `session.updated` shows voice=cedar (not alloy) |
| F2 | 30 min of silence + 30 min of TV/music, wake enabled | 0 realtime sessions opened; 0 recording UIs shown |
| F3 | "hello my friend, what time is it" in one breath | Recording UI after onset; auto-stop ~1 s after speech ends; message sent once; no stuck "Listening…"/record button |
| F4 | Talk phrase then silence (phantom) | No UI; silent re-arm |
| F5 | 5 consecutive talk turns, each followed by the assistant's audible reply | Wake still responsive within 1 s after each reply (no 30 s backoff) |
| F6 | Screen off → talk phrase + command | Screen wakes; command still captured and sent (R3) |
| F7 | Screen off → "wake up" | Screen on, conversation starts; after stop + screen dim, "wake up" works again (≥3 cycles) |
| F8 | Long agent answer (>25 s) on Gemini and on OpenAI | Never self-interrupts; mic restore logs appear only after playback ends + 1000 ms tail (WS) / 2000 ms (WebRTC) |
| F9 | Barge-in mid-answer (Qwen/Gemini) | Agent audio stops promptly; user heard |
| F10 | Back-to-back calls via wake word | Second-call mic `[MIC_PROBE]` amplitude ≈ cold-start |
| F11 | Each AudioOutput: AUTO, speaker, earpiece, wired, BT HFP, BT A2DP (WS) and A2DP (OpenAI → toast + loudspeaker) | Audible on the expected device; AUTO audible on the A300M loudspeaker for Gemini |
| F12 | Gemini goAway during a call | Beep + "Pausing in ~Ns" banner; seamless resume; banner clears |
| F13 | Toggle airplane mode 5 s mid-call | Session survives the transient drop (re-armed via `voice_start`); no auto-start on cold app launch |
| F14 | Kill the WS terminally mid-call (close conversation) | Local teardown; wake word re-armed |
| F15 | Two devices on one orchestrator; start voice on A | B shows "Active elsewhere", no crash, no wedge; ending on A clears B |
| F16 | Backend rejects the session (e.g. oversize instructions) | Toast + Off; wake word resumes |
| F17 | Wake word disabled during a call | Not re-enabled after the call ends |
| F18 | Mic held by another app | Notification shows "Wake word stalled…" and clears after release |
| F19 | 2 h+ continuous voice session (Xiaomi/POCO) with WiFi blip | No SIGABRT |
| F20 | Process killed by LMK (adb `am kill`) on A300M | Companion relaunches within 30 s; wake re-armed from persisted config |
| F21 | Fresh install, before settings load | Detector runs with the persisted (not default) server URL and gain (B4) |
| F22 | Wake slider at 150% vs 100% | Effective threshold logs 47 vs 70 |

---

## 11. Bugs, regression scenarios, dead code, drift

### 11.1 Field-found bugs already fixed → regression scenarios the rewrite must pass

| RS | Origin | Scenario (Given / When / Then) | Covered by |
|---|---|---|---|
| RS-01 | `8424f0f` | `session_started` with `voice_session_update` arrives before the provider exists → the update is queued and applied at DC open (OpenAI never runs on defaults) | 10.2 delivery; F1 |
| RS-02 | `9515576` | Commands drained into the provider before `connect()` are not cleared by `connect()` | 10.2 |
| RS-03 | `d4bc698` | Voice restart (WS drop or end→re-arm) with an empty drain → the cached `session.update` is re-sent at DC open, or at the `session.updated` echo | 10.2; F13 |
| RS-04 | `d8fbedf` | Concurrent `handleBackendCommand` from the WS thread while `start()` drains → no lost or corrupted commands | stress test |
| RS-05 | `304aa80` | GA event names `response.output_audio_transcript.*` produce transcripts | parser test |
| RS-06 | `0196e2a` | ICE DISCONNECTED → no teardown. FAILED → teardown off the signalling thread | WebRTC adapter test; F19 |
| RS-07 | `91a5df5` | OpenAI `error` event → teardown off the DC thread; close→dispose PC→dispose factory order; reentrant cleanup safe | F16 |
| RS-08 | `2a33e8d` | `PeerConnectionFactory.initialize` only once per process across sessions | adapter test |
| RS-09 | `c0cad2c` | Fatal VoiceEvent.Error → finalize + wake word resumed | 10.2; F16 |
| RS-10 | `9db8f37` | Terminal WS disconnect mid-voice → finalize + resume; transient → keep | 10.2; F14 |
| RS-11 | `9b24d1a` | Cold start / first connect never auto-starts voice; missing `voice_initiator` = non-owner | 10.2; F13 |
| RS-12 | `b6d184f`/`afe77a4` | Non-owner ignores command/ending/ended; shows read-only "Active elsewhere"; no Compose crash on toggle | 10.2; F15 |
| RS-13 | `71f19ce` | Non-initiator does not apply `voice_session_update` | 10.2 |
| RS-14 | `f5b339a` | `session_started voice=false` while the UI is stuck Summarizing/Connecting → Off | 10.2 |
| RS-15 | `40ce856` | Mid-call `voice_status:summarizing` does not regress the UI | 10.2 |
| RS-16 | `67a7958` | Stop shows Ending until `voice_ended`, or the 5 s timeout | 10.2 |
| RS-17 | `aab5f71`/`90d6913` | AI-initiated `voice_stopped`/`voice_ended` finalizes streaming text + local teardown | 10.2 |
| RS-18 | `94e3e4a` | Backend relay `error` → Error state, mic/speaker torn down (no silent "connected") | 10.2 |
| RS-19 | `b586e4b`/`ff517c2` | goAway `time_left` Go durations parsed; banner + audible beep (STREAM_MUSIC) | 10.2; F12 |
| RS-20 | `ea96ce2` | Gemini user-transcript deltas → one bubble per turn | parser test |
| RS-21 | `2ccee40`/`cffec38` | Long answers: no restore before writes-quiet + head drained + 1000 ms tail; no timeout | 10.2; F8 |
| RS-22 | `20217b1` | WS echo ducking actually applied to captured chunks (duck gain 0 = silence) | 10.2 |
| RS-23 | `b9e6352`/`687442e` | OpenAI: restore timer cancelled on `response.created`; restore waits for `output_audio_buffer.stopped` (+2 s), not `response.done` | 10.2; F8 |
| RS-24 | `55037c2` | Wake and call share the mic source; 200 ms settle → no amplitude drop on back-to-back calls | F10 |
| RS-25 | `20217b1` | API 21/22: no `getDevices` crash; no 4-arg `AudioTrack.write` | API-21 Robolectric/device |
| RS-26 | `c14c837` | AUTO on the A300M loudspeaker with a WS provider is audible (CALL tag) | F11 |
| RS-27 | `d36d31b` | Loudspeaker selection survives WebRTC ADM init (re-apply +1/3/5 s); SR fallback never reverts an audio mode it didn't set | F11 |
| RS-28 | `6fb1e54`/`ef2aaae` | API 31+: earpiece/speaker via `setCommunicationDevice`; missing BLUETOOTH_CONNECT never crashes | F11 |
| RS-29 | `b974756` | BT A2DP-only + WS → media plane; + WebRTC → loudspeaker + toast | F11 |
| RS-30 | `0bc612f` | Wake word resumes only after 1500 ms post-stop; no AudioRecord retry storm | F7 |
| RS-31 | `d93f7d7` | Wake disabled during a call is not re-enabled on resume; SR NO_SPEECH/CLIENT → flat 1 s | 10.2; F17 |
| RS-32 | `5bde0d1`/`95201b5` | Mic busy at monitor start → retry until free; config persisted across process death; re-arm on SCREEN_ON without a lock screen | 10.2; F18/F20 |
| RS-33 | `d6181b1`/`9200d50` | Config changes never reset the wake gain to 1.0; one ingress per change | 10.2 |
| RS-34 | `0b2cbb5`/`495b5d9` | Duplicate start within 3 s deduped; duplicate resume ignored; pause/resume acks complete | 10.2 |
| RS-35 | `187b419`/`39b1cec`/`b753ac5` | SR hang watchdog 10 s; NO_SPEECH ×8 → rebuild; SR refresh after 20 cycles / 2 NO_SPEECH | 10.2 (SR engine) |
| RS-36 | `c60cd08` vs `2acf57c` | Threshold 70 detects normal speech; do not "optimise" | F1/F22 |
| RS-37 | `70283e3` | Silence/ambient never opens a conversation (speech floor + temp 0 + boilerplate) | 10.2; F2 |
| RS-38 | `ae1d958` | Stray "hello" doesn't trigger; phantom shows no UI; noisy room still ends capture | 10.2; F2/F4 |
| RS-39 | `ecd3a43` | One-breath "hello my friend <command>" captured on the same mic without losing "my friend" | F3 |
| RS-40 | `2f5ecd7` | Whisper RTT up to ~4 s still confirms | F1 |
| RS-41 | `54463e1` | Whisper upload ≤2 s of audio | 10.2 |
| RS-42 | `b710c8b`/`3bee23a` | Rejected talk clears the record button; confirmed talk clears "Listening…" | 10.2; F3 |
| RS-43 | `043340a` | NoMatch windows don't accrue backoff ("worked once then stopped") | 10.2; F5 |
| RS-44 | `4f689a4` | Talk capture auto-stops in a quiet room whose idle RMS is high (AGC-boosted floor ~150–200) | 10.2; F3 |
| RS-45 | `3c4dbba` | Beep + state flip happen synchronously on detection, before network work | F1/F3 |
| RS-46 | `f77cd62` | Orchestrator WS survives idle periods on the A300M (backend ping consumed) | F13 |

### 11.2 Open bugs found now (must not be reproduced)

| # | Sev | Finding | Evidence |
|---|---|---|---|
| B1 | Med | **Gemini half-cascade text and tool calls are dropped.** The shallow map leaves `JSONArray`, which fails `is List<*>` | `WebSocketManager.kt:479, 597-608`; `JsonUtils.kt:48-52`; `GeminiVoiceProvider.kt:70-81, 102-104` |
| B2 | Med | **Unsynchronised `pendingCommands`** in `OpenAIVoiceProvider`: written from the WS thread (`Default`), drained on the DC thread | `OpenAIVoiceProvider.kt:85, 582-587, 783`; `AssistantViewModel.kt:208` |
| B3 | Med | **Two writers after a CALL↔MEDIA rebuild.** `PcmPlayback.setSpeakerMode` launches a second writer without cancelling the first; frame counter races feed the duck drain | `PcmPlayback.kt:170, 251-265, 273-291` |
| B4 | Med | **Startup default-config race.** `settings` `stateIn(AppSettings())` makes `updateWakeWord` fire with defaults. The dedupe key excludes URL and sensitivity, so a detector can keep the default `serverUrl` | `AssistantViewModel.kt:156-158`; `MainActivity.kt:415-432`; `AssistantService.kt:584` |
| B5 | Low | The `/dev/input` recents monitor ignores `button_trigger_enabled` (probably inert: no input-group access) | `AssistantService.kt:647-664` |
| B6 | Low | `AssistantVoiceInteractionService` is misconfigured (`sessionService` = itself; `showSession` isn't the system callback) | `res/xml/voice_interaction_service.xml`; `AssistantVoiceInteractionService.kt:34-40` |
| B7 | Low | The Stop button during talk capture doesn't stop the detector capture | `VoiceController.kt:642-661`; `AudioRecorder.kt:131-135` |
| B8 | Low | Manual PTT opens a second AudioRecord without pausing wake | `AudioRecorder.kt:82-95`; `VoiceController.kt:626-640` |
| B9 | Low | The `speakerVolumeLevel` slider is a no-op | `Models.kt:431`; `SettingsRepository.kt:254, 304`; `SettingsScreen.kt:~330` |
| B10 | Low | `getVoiceConfig()==null` after pause would strand the wake word (unreachable today) | `VoiceController.kt:712-726` |
| B11 | Low | Companion uses background `startService` / activity start (fine on API 21, broken on 26+). Out of scope: the companion is unchanged | `BootReceiver.kt:92`; `WatchdogService.kt:268-277` |

### 11.3 Risks / suspected issues (verify on device)

| # | Risk | Evidence |
|---|---|---|
| R1 | **Raw OpenAI key is served to LAN clients** over cleartext and cached on the device. Prefer a backend proxy or ephemeral token, weighed against latency | `ApiClient.kt:343-368`; `WhisperConfirmer.kt:171-176` |
| R2 | **Voice is Activity-bound.** The cold-started Activity misses the broadcast; the watchdog only checks the service | `WakeWordDetector.kt:1141-1147`; `MainActivity.kt:136-144, 197-200`; `WatchdogService.kt:258` |
| R3 | **Screen-on re-arm restarts a "healthy" detector**, killing an in-flight talk capture/confirm when the onset itself turned the screen on. Confirm/capture run while the FSM says `Idle` | `AssistantService.kt:355-362, 392-407`; `WakeWordDetector.kt:1003-1005, 1024-1028` |
| R4 | **Cross-thread native teardown:** `pause/stop` on Main releases AudioRecord / closes the Vosk Recognizer while the IO loop is inside `read`/`acceptWaveForm` | `WakeWordDetector.kt:597-609`; `VoskRecognitionEngine.kt:299-307` |
| R5 | **Android 14+ FGS microphone / BAL rules on POCO:** `startForeground` runs on every intent including background ones; HyperOS kills | `AssistantService.kt:454-456, 166-221` |
| R6 | **Wake start requires `SpeechRecognizer.isRecognitionAvailable()`** even for Vosk | `WakeWordDetector.kt:577-580` |
| R7 | **Release minify untested** (no JNA/WebRTC keep rules) | `A/proguard-rules.pro` |
| R8 | **Controller fields mutated from Main and Default** without synchronisation | `VoiceController.kt:184, 190` |
| R9 | **Leaks:** ADM not released; provider scopes never cancelled | `OpenAIVoiceProvider.kt:359-370, 73, 231`; `WebSocketPcmProvider.kt:57, 427` |
| R10 | **Wake depends on WiFi + OpenAI reachability** (fail-closed). The UI must show it | `WhisperConfirmer.kt:31-34` |

### 11.4 Dead code and doc / comment drift

**Dead code:**

| Item | Where |
|---|---|
| `VoiceState.Listening` (UI-only) | `Models.kt:95` |
| `OpenAIVoiceProvider.handleConnectionClosed` | :787 |
| AEC/NS effect fields | :80-81, :845-852 |
| Both providers' `release()` | — |
| `WebSocketEvent.VoiceTranscript` | `ChatController.kt:795` no-op |
| `AssistantVoiceInteractionService` | B6 |
| `android/migrate_wake_word_rename.sh` | — |
| `speakerVolumeLevel` | B9 |
| `WakeWordDetector.runRecognitionCycle` + `SpeechRecognizerEngine` | **intentionally kept** (V6) |

**Drift:**

- `wakeword_subsystem.md`:
  - It describes a fixed single-threshold VAD. The code uses an **adaptive floor + user K** (`4f689a4`).
  - It omits `POST_VOSK_NOMATCH_MS` (`043340a`).
  - It says re-arm leaves healthy detectors alone; the code restarts them (`AssistantService.kt:401-406`).
- `voice_subsystem.md` says `MIC_RESTORE_TAIL_MS = 600`. The code has **1000** (`cffec38`).
- `project_voice_initiator_flag_2026_06_06.md` says `voiceInitiator` defaults true. The parser defaults false (`9b24d1a`).
- **Code comment inconsistency (verify before porting the calibration):**
  - The adaptive-VAD comment attributes a quiet-room RMS floor of 150–200 to "the A300M's VOICE_COMMUNICATION source" (`WakeWordDetector.kt:141-147`, :1186-1188).
  - But the A300M (API 22) uses `VOICE_RECOGNITION` per :656-659.
  - So either that trace came from another device, or the comment mislabels the source.
- `wakeword_subsystem.md` "~600 MB free" vs `android_peripheral_project.md` "888 MB real, MemFree 60–79 MB": use the latter.
- `687442e` describes a 1 s restore after `cleared`. The code uses 2 s for both (`OpenAIVoiceProvider.kt:750, 757`); the code wins.

## 12. Errata (found by A-04 against the old code at `e871d05`, 2026-10-03)

The parity tests pin the **old code's** behaviour; where this chapter disagrees, the code wins.
`android-next/tools/parity/old_constants.json` (generated from `e871d05`) is the authoritative
constant list.

1. Route re-apply delays 1000/3000/5000 ms run **sequentially**, so re-apply happens at +1/+4/+9 s (not +1/+3/+5 s). Fixed in §3/§10 above.
2. Orchestrator recovery backoff is **0/500/1000 ms** (not 0/500/2000). Fixed above and in spec 14.
3. The pre-buffer capacity computation rounds **down**, not up.
4. The Qwen parser only accepts the legacy `response.audio_transcript.*` event names.
5. A blank `serverUrl` makes the Whisper confirm gate **let matches through** (not fail-closed) — preserve for parity, flagged for review.
6. The SpeechRecognizer extras map also contains `DICTATION_MODE`, `CALLING_PACKAGE` and `LANGUAGE_PREFERENCE`.
7. Line citations drifted: `noCompress` is at build.gradle `:46`, vosk at `:122`.
8. The screen-on re-arm restart is subject to the 3 s dedupe.
9. The **LB** label on the "WS ping / reconnect 30000 / 3000 ms" row (commit `f77cd62`) applies to the **30 s ping** (keeps the A300M radio awake). The fixed 3000 ms reconnect is *not* load-bearing; it is replaced on purpose by spec 12 T-13 exponential backoff (spec 12 bug A-3.3). Decided by the coordinator, 2026-10-03.
