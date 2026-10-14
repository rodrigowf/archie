package com.assistant.core.wakeword.ports

import com.assistant.core.audio.ports.MicSourceFactory
import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.audio.ports.VoiceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Wake-word facade and its collaborators (inv04 §3.1, §4.1–§4.4). Interface-only (A-04).
 *
 * The loop is: RMS gate → Vosk (pre-buffer first, 400 ms reads, 5 s window) → match tail →
 * (wake) speech-floor gate → Whisper → WakeDetected, or (talk) same-mic command capture with an
 * adaptive VAD → speech-floor gate → Whisper → TalkMessageCaptured. Confirm/capture/cooldown are
 * EXPLICIT phases (fixes R3); the Vosk recognizer, the AudioRecord and the loop share ONE thread
 * (fixes R4). Memory `feedback_dont_touch_wake_word_tuning.md`: every value is frozen.
 */

/** User settings the loop is built with. One engine instance per configuration. */
data class WakeConfig(
    /** Comma-separated talk (turn-based) phrases, e.g. "my friend". */
    val talkPhrases: String,
    /** Comma-separated realtime wake phrases, e.g. "wake up". Empty = disabled. */
    val wakePhrases: String,
    /** Scales the RMS gate: threshold = RMS_THRESHOLD / gain (gain ≤ 0 → base threshold). */
    val wakeGain: Float,
    /** End-of-utterance K: voice iff rms ≥ max(floor × K, 30). ≤ 0 → default 2.0. */
    val talkSilenceSensitivity: Float,
)

/**
 * Explicit loop phases (inv04 §3.1 FSM table). The old code ran confirm/capture/cooldown while its
 * state said `Idle`, which is why a screen-on re-arm could kill an in-flight capture (R3).
 */
enum class WakePhase {
    STOPPED,
    ACQUIRING_MIC,
    MONITORING,
    RECOGNIZING,
    MATCH_TAIL,
    CONFIRMING_WAKE,
    TALK_PRE_ONSET,
    TALK_CAPTURING,
    CONFIRMING_TALK,
    SR_CYCLE,
    COOLDOWN,
    PAUSED,
}

/**
 * What the loop tells its host (replaces the old LocalBroadcasts, inv04 §3.1 "Broadcast contract").
 * Mirrors `:core:model`'s `WakeSignal` (A-02); the coordinator may unify them later.
 */
sealed interface WakeLoopEvent {
    /** Wake path only, right before the Whisper call (never on the talk path, `3bee23a`). */
    data class Confirming(val isRealtime: Boolean) : WakeLoopEvent

    /** Rejected by the speech-floor gate or Whisper, or Whisper failed (fail-closed). */
    data class ConfirmFailed(val isRealtime: Boolean) : WakeLoopEvent

    /** Realtime wake phrase confirmed → host starts the voice session. */
    data object WakeDetected : WakeLoopEvent

    /** Talk path: sustained speech onset after the talk phrase → beep + recording UI. Exactly once. */
    data object TalkDetected : WakeLoopEvent

    /** Talk path confirmed: the whole utterance (pre-roll + command) as a 16 kHz mono WAV. */
    class TalkMessageCaptured(val wav: ByteArray) : WakeLoopEvent

    /** 8 consecutive mic-open failures (once per stretch). */
    data object MicUnavailable : WakeLoopEvent

    /** The mic opened after a [MicUnavailable] stretch. */
    data object MicAvailable : WakeLoopEvent

    /** SpeechRecognizer fallback: NO_SPEECH × 8 → the host rebuilds the engine. */
    data object RecognizerUnhealthy : WakeLoopEvent
}

enum class EngineKind { VOSK, SPEECH_RECOGNIZER }

/**
 * The wake-word engine. All methods are safe from any thread; teardown of the AudioRecord and the
 * Vosk recognizer always happens on the loop's own thread (R4).
 */
interface WakeWordEngine {
    val phase: StateFlow<WakePhase>

    /** Hot, never drops (buffered); subscribe before [start]. */
    val events: SharedFlow<WakeLoopEvent>

    /** Resolved on the first arm: Vosk when the model loads, else SpeechRecognizer (sticky). */
    val engineKind: EngineKind?

    /** STOPPED → ACQUIRING_MIC. No-op while already armed and not paused. */
    fun start()

    /** Any active phase → PAUSED: cancel the loop, release the mic, no event, no re-arm. */
    fun pause()

    /** PAUSED → ACQUIRING_MIC with the miss counter reset and a fresh recognizer. */
    fun resume()

    /** → STOPPED. */
    fun stop()

    fun release()
}

/** Text the recognizer holds after a feed. */
data class RecognizerText(val text: String, val isFinal: Boolean)

/**
 * One Vosk `Recognizer` (constrained grammar). NOT thread-safe: created, fed, reset and closed on
 * the loop thread only (R4). A fresh one is created for every recognition cycle and reset after a
 * match (re-trigger storms).
 */
interface StreamingRecognizer {
    /** Feed samples; returns the final text if the recognizer finalized, else the partial. */
    fun accept(samples: ShortArray, count: Int): RecognizerText

    /** Current partial without consuming it. */
    fun partial(): String

    fun reset()
    fun close()
}

interface StreamingRecognizerFactory {
    /** [grammarJson] is a JSON array of phrases ending with "[unk]". */
    fun create(grammarJson: String, sampleRateHz: Int): StreamingRecognizer
}

/**
 * Vosk model loading (extract to `filesDir/vosk-model`, stamp, Lollipop stderr shim, `Model(path)`).
 * Returns null when Vosk is unusable; failure is sticky per process.
 */
fun interface VoskModelSource {
    suspend fun load(): StreamingRecognizerFactory?
}

sealed interface SrOutcome {
    data class Matched(val phrase: String, val isRealtime: Boolean) : SrOutcome
    data object NoMatch : SrOutcome

    /** ERROR_NO_SPEECH; [unhealthy] when the NO_SPEECH run reached the health threshold (→ RecognizerUnhealthy). */
    data class NoSpeech(val unhealthy: Boolean = false) : SrOutcome
    data class Error(val code: Int, val flatDelay: Boolean) : SrOutcome
    data object Cancelled : SrOutcome
}

/**
 * The SpeechRecognizer fallback (kept until Vosk is validated everywhere, V6 deferred). It opens
 * its own mic, so the loop releases the AudioRecord before a cycle. An SR match carries no PCM,
 * so it fires without Whisper (fail-open, as the old code).
 */
interface SrCycleRunner {
    suspend fun warm()
    suspend fun recognize(talkVariants: List<String>, wakeVariants: List<String>): SrOutcome
    fun markNeedsRefresh()
    suspend fun tearDown()
}

sealed interface WhisperOutcome {
    data class Transcript(val text: String) : WhisperOutcome
    /** HTTP 401: the cached OpenAI key was cleared. */
    data object Unauthorized : WhisperOutcome
    data class HttpError(val code: Int) : WhisperOutcome
    data class Failure(val message: String) : WhisperOutcome
    data object NoKey : WhisperOutcome
}

/** whisper-1 transcription of a WAV clip (inv04 §2.1 external call). */
fun interface WhisperTranscriber {
    suspend fun transcribe(wav: ByteArray): WhisperOutcome
}

/** `GET /api/config/openai-key` (cached by the Whisper client; a 401 clears the cache). */
fun interface OpenAiKeyProvider {
    suspend fun fetchKey(): String?
}

/**
 * Everything the engine needs. The engine runs its whole loop (mic reads, recognizer, capture,
 * timers) in [scope] and never switches dispatcher itself (no `Dispatchers.IO`/`Main` inside): in
 * production [scope] is already confined to one IO thread (R4); in tests it runs on a
 * `StandardTestDispatcher`, so the mic fake, the clock and every timer share virtual time.
 */
class WakeEngineDeps(
    val scope: CoroutineScope,
    val clock: MonotonicClock,
    val log: VoiceLog,
    val sdkInt: Int,
    val mics: MicSourceFactory,
    val vosk: VoskModelSource,
    /** Null on devices without the fallback; the engine must still run on Vosk (fixes R6). */
    val speechRecognizer: SrCycleRunner?,
    /**
     * Null = Whisper not configured: matches fire unconfirmed (old behaviour with a blank
     * serverUrl, `WakeWordDetector.kt:523-534`). When non-null the gate is fail-closed.
     */
    val whisper: WhisperTranscriber?,
)
