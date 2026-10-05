package com.assistant.core.voice.duck

import com.assistant.core.audio.AudioTuning
import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.voice.VoiceLogMarkers.MIC_STATE
import com.assistant.core.voice.VoiceTuning
import com.assistant.core.voice.ports.OpenAiDuckPolicy

/**
 * OpenAI WebRTC mic ducking (old `OpenAIVoiceProvider` duck/restore, inv04 §3.3 WebRTC table;
 * RS-23) as a pure, tick-driven FSM: the owner waits [nextTickDelayMs] and calls [tick] (the old
 * `delay(2000)` coroutine). Thread-safe: data-channel events, the ADM record hook and the timer
 * run on different threads.
 *
 * Kept verbatim from the old provider: [setMicGain] writes the live gain even while ducked (the
 * restore then returns to the gain saved at the duck), [setEchoDuckingGain] applies from the next
 * duck, and `response.done` never restores (audio plays 6 s+ after it, `687442e`).
 */
class OpenAiDucker(
    private val clock: MonotonicClock,
    private val log: VoiceLog,
    private val tag: String = TAG,
) : OpenAiDuckPolicy {
    private val lock = Any()
    private var micGainLevel = AudioTuning.DEFAULT_MIC_GAIN
    private var echoDuckingGain = AudioTuning.DEFAULT_ECHO_DUCKING_GAIN
    private var gainBeforeSpeaking: Float? = null
    private var playing = false
    private var restoreAtMs: Long? = null

    override val currentMicGain: Float get() = synchronized(lock) { micGainLevel }
    override val isDucked: Boolean get() = synchronized(lock) { gainBeforeSpeaking != null }
    override val agentPlaying: Boolean get() = synchronized(lock) { playing }

    override fun setMicGain(level: Float): Unit = synchronized(lock) {
        micGainLevel = level.coerceIn(AudioTuning.MIC_GAIN_MIN, AudioTuning.MIC_GAIN_MAX)
        log.d(tag, "Mic gain set to: $micGainLevel")
    }

    override fun setEchoDuckingGain(gain: Float): Unit = synchronized(lock) {
        echoDuckingGain = gain.coerceIn(AudioTuning.ECHO_DUCKING_GAIN_MIN, AudioTuning.ECHO_DUCKING_GAIN_MAX)
        log.d(tag, "Echo ducking gain set to: $echoDuckingGain")
    }

    override fun onDcEvent(type: String): Boolean = synchronized(lock) {
        when (type) {
            "response.created" -> {
                restoreAtMs = null
                log.i(tag, "$MIC_STATE RESPONSE CREATED → ducking mic (restore timer cancelled)")
                duckLocked()
            }
            "output_audio_buffer.started" -> {
                restoreAtMs = null
                playing = true
                duckLocked()
            }
            "output_audio_buffer.stopped" -> {
                playing = false
                scheduleRestoreLocked()
            }
            "output_audio_buffer.cleared" -> {
                playing = false
                duckLocked()
                scheduleRestoreLocked()
            }
            "input_audio_buffer.speech_started" -> {
                if (playing) {
                    log.w(tag, "$MIC_STATE SPEECH_STARTED suppressed (echo while agent playing) gain=$micGainLevel")
                    return true
                }
                restoreImmediatelyLocked()
            }
        }
        false
    }

    override val nextTickDelayMs: Long?
        get() = synchronized(lock) { restoreAtMs?.let { (it - clock.nowMs()).coerceAtLeast(0L) } }

    override fun tick(): Unit = synchronized(lock) {
        val due = restoreAtMs ?: return
        if (clock.nowMs() < due) return
        restoreAtMs = null
        val saved = gainBeforeSpeaking ?: return
        micGainLevel = saved
        gainBeforeSpeaking = null
        log.i(tag, "$MIC_STATE RESTORE_DONE → gain: $echoDuckingGain→$micGainLevel (after ${VoiceTuning.OPENAI_RESTORE_DELAY_MS}ms delay)")
    }

    override fun cleanup(): Unit = synchronized(lock) {
        restoreAtMs = null
        gainBeforeSpeaking?.let { micGainLevel = it }
        gainBeforeSpeaking = null
        playing = false
    }

    private fun duckLocked() {
        val saved = gainBeforeSpeaking
        if (saved != null) {
            log.d(tag, "$MIC_STATE DUCK (already ducked, no-op) | gain=$micGainLevel gainSaved=$saved")
            return
        }
        gainBeforeSpeaking = micGainLevel
        micGainLevel = echoDuckingGain
        log.i(tag, "$MIC_STATE DUCK → gain: ${gainBeforeSpeaking}→$echoDuckingGain agentPlaying=$playing")
    }

    private fun scheduleRestoreLocked() {
        if (gainBeforeSpeaking == null) {
            log.d(tag, "$MIC_STATE RESTORE_DELAYED (no-op, not ducked)")
            return
        }
        restoreAtMs = clock.nowMs() + VoiceTuning.OPENAI_RESTORE_DELAY_MS
        log.i(tag, "$MIC_STATE RESTORE_DELAYED scheduled in ${VoiceTuning.OPENAI_RESTORE_DELAY_MS}ms | savedGain=$gainBeforeSpeaking")
    }

    private fun restoreImmediatelyLocked() {
        restoreAtMs = null
        val saved = gainBeforeSpeaking
        if (saved == null) {
            log.d(tag, "$MIC_STATE RESTORE_IMMEDIATE (no-op, not ducked)")
            return
        }
        micGainLevel = saved
        gainBeforeSpeaking = null
        log.i(tag, "$MIC_STATE RESTORE_IMMEDIATE → gain: $echoDuckingGain→$micGainLevel")
    }

    private companion object {
        const val TAG = "OpenAIVoiceProvider"
    }
}
