package com.assistant.core.voicehost.runtime

import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.voicehost.ports.WakeEngineHandle
import com.assistant.core.wakeword.ports.WakeLoopEvent
import com.assistant.core.wakeword.ports.WakePhase
import com.assistant.core.wakeword.ports.WakeWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Starts / stops the foreground service. Android: `VoiceHostService`; tests: a recorder. */
interface ServiceControl {
    /**
     * Start (or promote) the FGS from a foreground context. Returns false when the platform refused
     * (`ForegroundServiceStartNotAllowedException`, Android 12+): the host shows BACKGROUND_RESTRICTED.
     */
    fun startFromForeground(): Boolean

    /** Ask the running service to stop itself (main app, when nothing needs it). */
    fun stop()
}

/** Push-to-talk capture (main app). [stop] returns the 16 kHz mono WAV, or null when nothing was captured. */
interface PushToTalkRecorder {
    fun start(): Boolean
    suspend fun stop(): ByteArray?
}

/** Mutes what Archie says (speaker), independently of the mic mute. */
fun interface SpeakerMuter {
    fun setMuted(muted: Boolean)
}

/** Mirrors `button_trigger_enabled` into `assistant_service_prefs` (the pinned on-disk contract). */
fun interface ButtonTriggerPref {
    fun set(enabled: Boolean)
}

/** Why the wake-word mic is held closed although the wake word is enabled. */
enum class GateReason {
    /** "Pause listening". */
    USER_PAUSED,

    /** Android 14+ FGS started without the microphone type (spec 14 §2.6). */
    NEEDS_FOREGROUND,
}

/**
 * Gate in front of every wake engine: while any reason holds, engines stay paused (no AudioRecord
 * is opened), and they resume when the last reason clears. This keeps the wake-service logic (and
 * its parity-pinned dedupe) untouched: the service still believes the engine is armed.
 */
class MicGate {
    private val _reasons = MutableStateFlow<Set<GateReason>>(emptySet())
    val reasons: StateFlow<Set<GateReason>> = _reasons.asStateFlow()
    val isOpen: Boolean get() = _reasons.value.isEmpty()

    fun close(reason: GateReason) = _reasons.update { it + reason }
    fun open(reason: GateReason) = _reasons.update { it - reason }
}

/**
 * Production [WakeEngineHandle] over a [WakeWordEngine] (A-07): forwards the engine's events while
 * it is the current one, maps its explicit phases to `isBusy` (R3), and honours the [MicGate].
 */
class GatedWakeEngineHandle(
    private val engine: WakeWordEngine,
    private val gate: MicGate,
    scope: CoroutineScope,
    private val log: VoiceLog,
    onEvent: (GatedWakeEngineHandle, WakeLoopEvent) -> Unit,
) : WakeEngineHandle {
    private val lock = Any()
    private var wanted = false
    private var paused = false
    private val jobs: List<Job>

    init {
        // Subscribe before start (the engine's events are hot).
        jobs = listOf(
            scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { onEvent(this@GatedWakeEngineHandle, it) } },
            scope.launch(start = CoroutineStart.UNDISPATCHED) { gate.reasons.collect { onGate(it.isEmpty()) } },
        )
    }

    val phase: StateFlow<WakePhase> get() = engine.phase

    override val isActive: Boolean get() = synchronized(lock) { wanted }
    override val isPaused: Boolean get() = synchronized(lock) { paused }
    override val isBusy: Boolean get() = engine.phase.value in BUSY

    override fun start() {
        synchronized(lock) {
            wanted = true
            paused = false
        }
        if (gate.isOpen) engine.start() else log.d(TAG, "wake start held — mic gate closed ${gate.reasons.value}")
    }

    override fun pause() {
        synchronized(lock) { if (wanted) paused = true }
        engine.pause()
    }

    override fun resume() {
        synchronized(lock) { paused = false }
        if (gate.isOpen) resumeEngine()
    }

    override fun stop() {
        synchronized(lock) {
            wanted = false
            paused = false
        }
        jobs.forEach { it.cancel() }
        engine.stop()
        engine.release()
    }

    private fun onGate(open: Boolean) {
        val run = synchronized(lock) { wanted && !paused }
        if (!run) return
        if (open) resumeEngine() else engine.pause()
    }

    private fun resumeEngine() {
        if (engine.phase.value == WakePhase.PAUSED) engine.resume() else engine.start()
    }

    companion object {
        private const val TAG = "AssistantService"

        /** Confirm / capture phases a screen-on re-arm must never restart (R3). */
        val BUSY = setOf(
            WakePhase.CONFIRMING_WAKE,
            WakePhase.MATCH_TAIL,
            WakePhase.TALK_PRE_ONSET,
            WakePhase.TALK_CAPTURING,
            WakePhase.CONFIRMING_TALK,
        )
    }
}
