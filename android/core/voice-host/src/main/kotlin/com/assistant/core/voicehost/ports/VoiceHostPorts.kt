package com.assistant.core.voicehost.ports

import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.voice.ports.VoiceCues
import com.assistant.core.voice.ports.VoiceSessionController
import com.assistant.core.wakeword.ports.WakeLoopEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/*
 * Voice host: the wake-word service logic (old `AssistantService`, inv04 §3.5) and the trigger
 * router (wake signals / external triggers → cues + voice + talk UI, spec 14 §2.5). Interface-only (A-04).
 *
 * A-08 registers one implementation with java.util.ServiceLoader:
 *   core/voice-host/src/main/resources/META-INF/services/com.assistant.core.voicehost.ports.VoiceHostCore
 * Tuning values live in `com.assistant.core.voicehost.HostTuning` (names pinned by ServiceTuningTest).
 */

/** The wake configuration persisted in `assistant_service_prefs` (survives process death). */
data class WakeServiceConfig(
    val enabled: Boolean,
    val talkWord: String,
    val wakeWord: String,
    val wakeGain: Float,
    val talkSilenceSensitivity: Float,
    val serverUrl: String,
)

/**
 * Persistence of [WakeServiceConfig] (adapter over `:core:settings` `ServicePrefs`, same file and
 * keys as the old app). [load] applies the old defaults for absent keys: enabled=false,
 * "my friend", "wake up", gain 1.0, sensitivity 2.0, url "".
 */
interface WakeConfigStore {
    fun load(): WakeServiceConfig
    fun save(config: WakeServiceConfig)
}

/** What the service drives (wraps a `WakeWordEngine`). */
interface WakeEngineHandle {
    val isActive: Boolean
    val isPaused: Boolean

    /** CONFIRMING_WAKE / MATCH_TAIL / TALK_PRE_ONSET / TALK_CAPTURING / CONFIRMING_TALK (R3). */
    val isBusy: Boolean
    fun start()
    fun pause()
    fun resume()
    fun stop()
}

fun interface WakeEngineProvider {
    fun create(config: WakeServiceConfig): WakeEngineHandle
}

enum class WakeNotice { NORMAL, MIC_STALLED }

data class WakeStartKey(val talkWord: String, val wakeWord: String, val wakeGain: Float)

/**
 * Start dedupe (inv04 §3.5, `0b2cbb5`): key (talk, wake, gain) — NOT url, NOT sensitivity; strict
 * `<` 3000 ms on the monotonic clock. A call that is not deduped records itself; [clear] (on
 * disable) forgets the key.
 */
interface WakeStartDeduper {
    fun shouldDedupe(key: WakeStartKey, nowMs: Long): Boolean
    fun clear()
}

/**
 * Old `AssistantService` wake logic, minus Android:
 *  - [onConfigUpdate]: persist all fields; enabled → start (deduped), disabled → stop + clear dedupe.
 *  - [onPauseForVoice]: voiceSessionActive = true, pause the engine, complete [ack].
 *  - [onResumeAfterVoice]: if !voiceSessionActive → ignore but complete [ack] (`495b5d9`); else
 *    voiceSessionActive = false, RE-READ `enabled` from the store, enabled → full start (deduped),
 *    complete [ack].
 *  - [onStickyRestart]: load from the store; start when enabled.
 *  - [onScreenOnOrUserPresent]: debounce 300 ms, then: skip during voice; reload; disabled → nothing;
 *    no engine → start; paused → resume; not active → start; BUSY (confirm/capture) → leave alone
 *    (new, R3); otherwise restart (deduped).
 *  - [onRecognizerUnhealthy]: not voice-active and enabled → start (deduped).
 *  - mic stalled: [notice] flips once per stretch.
 */
interface WakeServiceController {
    val voiceSessionActive: Boolean
    val notice: StateFlow<WakeNotice>
    val currentConfig: WakeServiceConfig
    fun onConfigUpdate(config: WakeServiceConfig)
    fun onPauseForVoice(ack: CompletableDeferred<Unit>)
    fun onResumeAfterVoice(ack: CompletableDeferred<Unit>)
    fun onStickyRestart()
    fun onScreenOnOrUserPresent()
    fun onRecognizerUnhealthy()
    fun onMicUnavailable()
    fun onMicAvailable()
    fun destroy()
}

class WakeServiceDeps(
    val scope: CoroutineScope,
    val clock: MonotonicClock,
    val log: VoiceLog,
    val store: WakeConfigStore,
    val engines: WakeEngineProvider,
)

enum class Trigger { BUTTON, WAKE_WORD, ASSIST, TILE, NOTIFICATION, RECENTS }

/** Transient talk/wake indicators the UI shows (old `wakeConfirming` / `isRecording`). */
data class TalkUiState(val wakeConfirming: Boolean = false, val isRecording: Boolean = false)

/** Ships a confirmed talk capture as `send_audio{format:"wav"}` + a "[Voice message]" bubble. */
fun interface TalkMessageSender {
    fun send(wav: ByteArray)
}

/**
 * Single trigger ingress (spec 14 §2.5). On `WakeDetected` (or a voice trigger) it plays the wake
 * cue, flips the voice UI to CONNECTING and starts voice SYNCHRONOUSLY inside the call, before any
 * network work (RS-45). Talk: `TalkDetected` → talk cue + recording on; `Confirming` → confirming on;
 * `ConfirmFailed` → both off; `TalkMessageCaptured` → both off + send (RS-42).
 * RECENTS is ignored unless the button trigger is enabled (fixes B5).
 */
interface TriggerRouter {
    val talkUi: StateFlow<TalkUiState>
    fun onWakeEvent(event: WakeLoopEvent)
    fun onTrigger(trigger: Trigger)
}

class TriggerDeps(
    val voice: VoiceSessionController,
    val cues: VoiceCues,
    val talkSender: TalkMessageSender,
    val buttonTriggerEnabled: () -> Boolean,
    val log: VoiceLog,
)

interface VoiceHostCore {
    fun wakeStartDeduper(): WakeStartDeduper
    fun wakeService(deps: WakeServiceDeps): WakeServiceController
    fun triggerRouter(deps: TriggerDeps): TriggerRouter
}
