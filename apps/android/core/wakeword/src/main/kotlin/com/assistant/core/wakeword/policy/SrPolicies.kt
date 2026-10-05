package com.assistant.core.wakeword.policy

import com.assistant.core.audio.ports.AudioStream
import com.assistant.core.wakeword.WakeTuning
import com.assistant.core.wakeword.ports.AudioModeOwnership
import com.assistant.core.wakeword.ports.SrHealth
import com.assistant.core.wakeword.ports.SrHealthAction

/*
 * SpeechRecognizer-fallback book-keeping (old SpeechRecognizerEngine.kt, inv04 §4.3). The fallback
 * stays until Vosk is validated on every device (V6 deferred); these are its pure decisions.
 */

/**
 * Warm-recognizer refresh and NO_SPEECH health (Detour 6 `b753ac5`, Inc 8 `39b1cec`, watchdog
 * `187b419`). Not thread-safe: the adapter touches it on the main thread only.
 */
class SrHealthBook : SrHealth {
    override var consecutiveNoSpeech: Int = 0
        private set
    private var refreshPending = false
    private var cyclesSinceWarm = 0

    override fun onWarm(): Boolean {
        val rebuild = refreshPending || cyclesSinceWarm >= WakeTuning.SR_REFRESH_AFTER_N
        if (rebuild) onRecognizerCreated()
        return rebuild
    }

    /** A recognizer was (re)built: the refresh is satisfied and the cycle count restarts. */
    fun onRecognizerCreated() {
        refreshPending = false
        cyclesSinceWarm = 0
    }

    /** Pause / resume / external request: rebuild at the next warm (Detour 6 safeguard C). */
    fun markRefresh() {
        refreshPending = true
    }

    override fun onCycleCompleted() {
        cyclesSinceWarm++
    }

    override fun onNoSpeech(): SrHealthAction {
        consecutiveNoSpeech++
        val refresh = consecutiveNoSpeech >= WakeTuning.SR_REFRESH_NO_SPEECH_SPIKE
        if (refresh) refreshPending = true
        return SrHealthAction(
            markRefresh = refresh,
            broadcastUnhealthy = consecutiveNoSpeech >= WakeTuning.SR_NO_SPEECH_HEALTH_THRESHOLD,
        )
    }

    override fun onError(code: Int): Boolean {
        consecutiveNoSpeech = 0
        return code == WakeTuning.SR_ERROR_CLIENT
    }

    override fun onResults() {
        consecutiveNoSpeech = 0
    }

    override fun onWatchdogFired() {
        refreshPending = true
    }
}

/**
 * Single-owner MODE_IN_COMMUNICATION flip (`d36d31b`): revert to NORMAL only if this engine set it;
 * a match hands ownership to the voice session, so a late teardown never flips a live call's mode.
 */
class SrAudioModeOwner : AudioModeOwnership {
    private var weChangedAudioMode = false

    override fun onCycleStartSetInCommunication() {
        weChangedAudioMode = true
    }

    override fun onCycleEnd(matched: Boolean): Boolean {
        if (matched) {
            weChangedAudioMode = false
            return false
        }
        return revertIfOurs()
    }

    override fun revertIfOurs(): Boolean {
        if (!weChangedAudioMode) return false
        weChangedAudioMode = false
        return true
    }
}

/** The tuned `RecognizerIntent` extras (inv04 §4.3, errata #6), keyed by their Android string values. */
object SrPlatformPolicy {
    fun recognizerExtras(packageName: String): Map<String, Any> = linkedMapOf(
        "android.speech.extra.LANGUAGE_MODEL" to "free_form",
        "calling_package" to packageName,
        "android.speech.extra.MAX_RESULTS" to 5,
        "android.speech.extra.PARTIAL_RESULTS" to true,
        "android.speech.extra.LANGUAGE" to "en-US",
        "android.speech.extra.LANGUAGE_PREFERENCE" to "en-US",
        "android.speech.extra.PREFER_OFFLINE" to true,
        "android.speech.extra.DICTATION_MODE" to true,
        "android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS" to 200L,
        "android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS" to 1500L,
        "android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS" to 1000L,
    )

    /** Muted around `startListening` to hide the SR beep. */
    val beepStreams: List<AudioStream> = listOf(AudioStream.RING, AudioStream.NOTIFICATION, AudioStream.SYSTEM, AudioStream.MUSIC)

    fun muteUsesAdjustStreamVolume(sdkInt: Int): Boolean = sdkInt >= WakeTuning.SR_ADJUST_MUTE_MIN_SDK
}
