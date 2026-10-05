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

