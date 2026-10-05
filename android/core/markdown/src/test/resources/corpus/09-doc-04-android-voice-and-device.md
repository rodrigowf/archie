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

