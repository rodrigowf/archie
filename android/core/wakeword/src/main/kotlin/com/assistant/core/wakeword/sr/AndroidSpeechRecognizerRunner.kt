package com.assistant.core.wakeword.sr

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.assistant.core.audio.ports.AudioStream
import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.wakeword.WakeTuning
import com.assistant.core.wakeword.policy.PhraseMatcher
import com.assistant.core.wakeword.policy.SrAudioModeOwner
import com.assistant.core.wakeword.policy.SrHealthBook
import com.assistant.core.wakeword.policy.SrPlatformPolicy
import com.assistant.core.wakeword.ports.SrCycleRunner
import com.assistant.core.wakeword.ports.SrOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The SpeechRecognizer fallback (old `SpeechRecognizerEngine`, ported verbatim; kept until Vosk is
 * validated on every device — V6 deferred). Opens its own mic, so the wake loop releases its
 * AudioRecord first. The decisions live in pure policies: [SrHealthBook] (warm refresh, NO_SPEECH
 * health), [SrAudioModeOwner] (single-owner MODE_IN_COMMUNICATION revert, `d36d31b`) and
 * [SrPlatformPolicy] (intent extras, beep streams, mute method).
 *
 * `SpeechRecognizer` is main-thread-only on Lollipop: every platform call hops to
 * `Dispatchers.Main`; the hang watchdog runs on a main-thread scope owned here.
 */
class AndroidSpeechRecognizerRunner(
    private val context: Context,
    private val log: VoiceLog,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : SrCycleRunner {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val health = SrHealthBook()
    private val modeOwner = SrAudioModeOwner()
    private val intent: Intent by lazy { buildIntent() }

    // Main-thread state.
    private var recognizer: SpeechRecognizer? = null
    private var cycleFinished = false
    private var watchdog: Job? = null
    private var pending: CompletableDeferred<SrOutcome>? = null

    override fun markNeedsRefresh() {
        main.launch { health.markRefresh() }
    }

    override suspend fun warm() = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            log.w(TAG, "Speech recognition not available — cannot warm")
            return@withContext
        }
        if (health.onWarm() && recognizer != null) {
            log.d(TAG, "Recognizer refresh")
            destroyRecognizer()
        }
        if (recognizer == null) {
            createRecognizer()
            log.d(TAG, "Recognizer warmed and ready")
        }
    }

    override suspend fun recognize(talkVariants: List<String>, wakeVariants: List<String>): SrOutcome {
        val deferred = CompletableDeferred<SrOutcome>()
        withContext(Dispatchers.Main) {
            pending = deferred
            listener.talk = talkVariants
            listener.wake = wakeVariants
            muteBeep()
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                modeOwner.onCycleStartSetInCommunication()
            } catch (_: Exception) { /* best effort */ }
            cycleFinished = false
            if (recognizer == null) {
                log.w(TAG, "Recognizer not warm at recognize() — cold-start fallback")
                createRecognizer()
            }
            recognizer?.startListening(intent)
        }
        val result = deferred.await()
        withContext(Dispatchers.Main) {
            watchdog?.cancel()
            watchdog = null
            try {
                recognizer?.cancel()
            } catch (e: Exception) {
                log.w(TAG, "Error cancelling recognizer after cycle: ${e.message}")
            }
            health.onCycleCompleted()
            if (modeOwner.onCycleEnd(matched = result is SrOutcome.Matched)) setModeNormal()
            unmuteBeep()
        }
        return result
    }

    override suspend fun tearDown() = withContext(Dispatchers.Main) {
        watchdog?.cancel()
        watchdog = null
        destroyRecognizer()
        pending?.let { if (!it.isCompleted) it.complete(SrOutcome.Cancelled) }
        pending = null
        if (modeOwner.revertIfOurs()) setModeNormal()
        unmuteBeep()
    }

    private fun createRecognizer() {
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
        health.onRecognizerCreated()
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
            log.w(TAG, "Error destroying recognizer: ${e.message}")
        }
        recognizer = null
    }

    private fun setModeNormal() {
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) { /* best effort */ }
    }

    @SuppressLint("InlinedApi") // ADJUST_MUTE only on the API ≥ 23 branch (SrPlatformPolicy).
    @Suppress("DEPRECATION")
    private fun muteBeep() = try {
        for (s in SrPlatformPolicy.beepStreams.map(::streamType)) {
            if (SrPlatformPolicy.muteUsesAdjustStreamVolume(sdkInt)) audioManager.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
            else audioManager.setStreamMute(s, true)
        }
    } catch (e: Exception) {
        log.w(TAG, "Failed to mute beep streams: ${e.message}")
    }

    @SuppressLint("InlinedApi")
    @Suppress("DEPRECATION")
    private fun unmuteBeep() = try {
        for (s in SrPlatformPolicy.beepStreams.map(::streamType)) {
            if (SrPlatformPolicy.muteUsesAdjustStreamVolume(sdkInt)) audioManager.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
            else audioManager.setStreamMute(s, false)
        }
    } catch (e: Exception) {
        log.w(TAG, "Failed to unmute beep streams: ${e.message}")
    }

    private fun streamType(stream: AudioStream): Int = when (stream) {
        AudioStream.VOICE_CALL -> AudioManager.STREAM_VOICE_CALL
        AudioStream.MUSIC -> AudioManager.STREAM_MUSIC
        AudioStream.RING -> AudioManager.STREAM_RING
        AudioStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        AudioStream.SYSTEM -> AudioManager.STREAM_SYSTEM
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        SrPlatformPolicy.recognizerExtras(context.packageName).forEach { (key, value) ->
            when (value) {
                is String -> putExtra(key, value)
                is Int -> putExtra(key, value)
                is Long -> putExtra(key, value)
                is Boolean -> putExtra(key, value)
                else -> error("Unsupported RecognizerIntent extra type for $key: ${value.javaClass}")
            }
        }
    }

    /** Resolve the current cycle exactly once; late callbacks racing the watchdog cannot re-resolve. */
    private fun completeOnce(outcome: SrOutcome) {
        pending?.let { if (!it.isCompleted) it.complete(outcome) }
    }

    private val listener = object : RecognitionListener {
        var talk: List<String> = emptyList()
        var wake: List<String> = emptyList()

        private fun match(results: List<String>): SrOutcome.Matched? {
            for (raw in results) {
                val m = PhraseMatcher.findMatch(raw, talk, wake) ?: continue
                log.d(TAG, if (m.isRealtime) "Wake word (realtime) detected in: \"$raw\"" else "Talk word (turn-based) detected in: \"$raw\"")
                return SrOutcome.Matched(m.variant, m.isRealtime)
            }
            return null
        }

        override fun onReadyForSpeech(params: Bundle?) = log.d(TAG, "Recognizer ready")

        override fun onBeginningOfSpeech() {
            log.d(TAG, "Speech begun")
            watchdog?.cancel()
            watchdog = main.launch {
                delay(WakeTuning.SR_HANG_WATCHDOG_MS)
                if (!cycleFinished) {
                    log.w(TAG, "Recognizer watchdog fired — forcing finish after ${WakeTuning.SR_HANG_WATCHDOG_MS}ms silence")
                    cycleFinished = true
                    health.onWatchdogFired()
                    completeOnce(SrOutcome.Error(WATCHDOG_CODE, flatDelay = false))
                }
            }
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (cycleFinished) return
            cycleFinished = true
            watchdog?.cancel()
            log.d(TAG, "Recognizer error: $error")
            if (error == WakeTuning.SR_ERROR_NO_SPEECH) {
                val action = health.onNoSpeech()
                if (action.markRefresh) log.d(TAG, "Marking warm recognizer for refresh — ${health.consecutiveNoSpeech} NO_SPEECH in a row")
                if (action.broadcastUnhealthy) {
                    log.w(TAG, "Recognizer unhealthy — ${health.consecutiveNoSpeech} NO_SPEECH errors; broadcasting rebuild")
                }
                completeOnce(SrOutcome.NoSpeech(unhealthy = action.broadcastUnhealthy))
            } else {
                completeOnce(SrOutcome.Error(error, flatDelay = health.onError(error)))
            }
        }

        override fun onResults(results: Bundle?) {
            if (cycleFinished) return
            cycleFinished = true
            watchdog?.cancel()
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            log.d(TAG, "Results: $matches")
            health.onResults()
            completeOnce(matches?.let { match(it) } ?: SrOutcome.NoMatch)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (cycleFinished) return
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (partial.isNullOrEmpty()) return
            log.d(TAG, "Partial: $partial")
            val matched = match(partial) ?: return
            cycleFinished = true // early match on a partial — stop immediately
            watchdog?.cancel()
            health.onResults()
            completeOnce(matched)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val TAG = "SpeechRecogEngine"

        /** Not an Android error code: the hang watchdog's own failure (backs off, no flat delay). */
        const val WATCHDOG_CODE = -1
    }
}
