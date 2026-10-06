package com.assistant.core.wakeword.ports

import com.assistant.core.audio.ports.AudioStream
import com.assistant.core.audio.ports.VoiceLog
import java.io.File
import java.io.InputStream
import okhttp3.OkHttpClient

/*
 * Parity entry point for :core:wakeword (A-04). Interface-only.
 *
 * A-07 registers one implementation with java.util.ServiceLoader:
 *   core/wakeword/src/main/resources/META-INF/services/com.assistant.core.wakeword.ports.WakewordCore
 * Tuning values live in `com.assistant.core.wakeword.WakeTuning` (names pinned by WakeTuningTest,
 * VoskTuningTest, SrTuningTest, WhisperTuningTest).
 */

data class VariantMatch(val variant: String, val isRealtime: Boolean, val rawText: String)

/** Phrase handling shared by Vosk, SR and Whisper (inv04 §4.1 "variant parse", §4.2 precedence). */
interface VariantMatcher {
    /** Comma-split, trim, drop empties, lowercase, distinct. No phonetic substitutions (`e31d4fc`). */
    fun parseVariants(csv: String): List<String>

    /** Wake (realtime) variants first, then talk; substring `contains` on the lowercased text. */
    fun findMatch(text: String, talk: List<String>, wake: List<String>): VariantMatch?

    /**
     * Talk-only prefix trigger: the text's leading words equal a multi-word talk variant's leading
     * words, at least `min(MIN_PREFIX_WORDS, variantWords)` of them. Never for single-word variants,
     * never for wake variants.
     */
    fun findTalkPrefixMatch(text: String, talk: List<String>): VariantMatch?

    /** JSON array of the distinct phrases (talk then wake) followed by "[unk]". */
    fun keywordGrammar(talk: List<String>, wake: List<String>): String
}

/** Stage-1 activity gate: rms ≥ RMS_THRESHOLD / gain held ≥ ACTIVITY_HOLD_MS (from the first loud frame). */
interface RmsGate {
    val effectiveThreshold: Double

    /** True when activity has been held long enough to start recognition. */
    fun onFrame(rms: Double, nowMs: Long): Boolean
    fun reset()
}

/** Asymmetric floor tracker: attack 0.30 toward lower readings, release 0.02 toward higher; seed < 0 = unseeded. */
interface NoiseFloorTracker {
    val floor: Double
    fun update(rms: Double): Double
}

enum class TalkVadStep { CONTINUE, ONSET, END_SILENCE, END_MAX, ABORT_NO_ONSET }

/**
 * Talk-command end-of-utterance VAD (old `captureTalkCommand`, frame-for-frame):
 *  1. `now - started ≥ COMMAND_MAX_MS` → END_MAX (frame not consumed);
 *  2. no onset and `now - started ≥ COMMAND_SPEECH_ONSET_TIMEOUT_MS` → ABORT_NO_ONSET (no UI);
 *  3. floor = tracker.update(rms); voice iff rms ≥ max(floor × K, 30);
 *     voice → sustained += now - previousFrameTime, lastVoice = now; else sustained = 0;
 *  4. no onset yet and sustained ≥ ONSET_SUSTAIN_MS → ONSET (lastVoice = now);
 *  5. onset and `now - lastVoice ≥ COMMAND_SILENCE_MS` → END_SILENCE.
 * `previousFrameTime` and `lastVoice` start at `startedAtMs`.
 */
interface TalkVad {
    val onsetFired: Boolean
    fun onFrame(rms: Double, nowMs: Long): TalkVadStep
}

enum class CycleOutcome { MATCHED, VOSK_NO_MATCH, SR_NO_MATCH, NO_SPEECH, ERROR_FLAT, ERROR, CANCELLED }

/**
 * Re-arm delays (inv04 §3.1 COOLDOWN rows): MATCHED 3000 (resets misses); VOSK_NO_MATCH 500 (resets
 * misses, `043340a`); NO_SPEECH / ERROR_FLAT 1000 flat; SR_NO_MATCH / ERROR backoff
 * `min(1000 shl misses, 30000)` then misses++; CANCELLED → null (no re-arm).
 */
interface RearmPolicy {
    val consecutiveMisses: Int
    fun delayAfter(outcome: CycleOutcome): Long?
    fun reset()
}

data class WhisperVerdict(val confirmed: Boolean, val isRealtime: Boolean)

/** Pure Whisper decision (old `WhisperConfirmer.decide/normalize`). */
interface WhisperDecision {
    val hallucinationBoilerplate: Set<String>

    /** Lowercase, non-`[a-z0-9\s]` → space, collapse whitespace, trim. */
    fun normalize(text: String): String

    /** Exact-boilerplate reject, then wake-first then talk substring on normalized text. */
    fun decide(transcript: String, talk: List<String>, wake: List<String>): WhisperVerdict
}

/** Clip helpers (inv04 §4.1/§4.2). */
interface ClipPolicy {
    /** `max(minBuf, 3200)` bytes for the monitor AudioRecord (≥100 ms reads). */
    fun monitorBufferBytes(minBufferBytes: Int): Int

    /** `max(1, (16000 * 500 / 1000) / readSamples)` reads (integer division, as the old code). */
    fun preBufferCapacity(readSamples: Int): Int

    /** Keep the most recent frames whose total reaches [windowMs]; oldest dropped first. */
    fun trimToTrailingWindow(frames: List<ShortArray>, windowMs: Long, sampleRateHz: Int): List<ShortArray>

    /** Peak per-frame RMS ≥ SPEECH_FLOOR_RMS (30). Empty → false. */
    fun hasSpeech(frames: List<ShortArray>): Boolean

    /** Min non-zero per-frame RMS of the pre-roll, or -1 (unseeded) when none. */
    fun seedFloor(preRoll: List<ShortArray>): Double
}

data class SrHealthAction(val markRefresh: Boolean, val broadcastUnhealthy: Boolean)

/** SpeechRecognizer health book-keeping (inv04 §4.3, RS-35). */
interface SrHealth {
    val consecutiveNoSpeech: Int

    /** At warm: true when the recognizer must be rebuilt (refresh pending or ≥ 20 cycles). */
    fun onWarm(): Boolean

    /** After each completed cycle. */
    fun onCycleCompleted()

    /** ERROR_NO_SPEECH (literal 6): refresh at 2 in a row, unhealthy at ≥ 8. */
    fun onNoSpeech(): SrHealthAction

    /** Any other error code: resets the NO_SPEECH run; returns flatDelay (true for ERROR_CLIENT 7). */
    fun onError(code: Int): Boolean

    /** onResults / partial match: resets the NO_SPEECH run. */
    fun onResults()

    /** Hang watchdog (10 s after onBeginningOfSpeech) fired: refresh next warm. */
    fun onWatchdogFired()
}

/**
 * Single-owner audio-mode flip for the SR beep suppression (`d36d31b`): only revert
 * MODE_IN_COMMUNICATION → NORMAL if this engine set it; a match hands ownership to the voice session.
 */
interface AudioModeOwnership {
    fun onCycleStartSetInCommunication()

    /** Returns true when the caller must set MODE_NORMAL now. */
    fun onCycleEnd(matched: Boolean): Boolean

    /** Teardown path; true when the caller must set MODE_NORMAL now. */
    fun revertIfOurs(): Boolean
}

/** Vosk model extraction (same dir and stamp as the old app so the lite upgrade does not re-extract). */
interface VoskModelStore {
    val assetRoot: String
    val extractDirName: String
    val stamp: String
    val stampFileName: String
    fun shouldExtract(target: File, expectedStamp: String): Boolean
    fun extractTree(sources: Map<String, () -> InputStream>, target: File, stamp: String)

    /** Flattened file list under [assetRoot]; [list] mimics `AssetManager.list(path)`. */
    fun assetFileList(list: (String) -> List<String>): List<String>
}

interface WakewordCore {
    val variants: VariantMatcher
    val whisperDecision: WhisperDecision
    val clips: ClipPolicy
    val modelStore: VoskModelStore

    fun rmsGate(wakeGain: Float): RmsGate
    fun noiseFloorTracker(seed: Double): NoiseFloorTracker
    fun adaptiveVoiceThreshold(noiseFloor: Double, sensitivity: Double): Double
    fun talkVad(startedAtMs: Long, seedFloor: Double, sensitivity: Float): TalkVad
    fun rearmPolicy(): RearmPolicy
    fun srHealth(): SrHealth
    fun srAudioModeOwnership(): AudioModeOwnership

    /**
     * `RecognizerIntent` extras keyed by their Android string values, with the old Java value types
     * (Int MAX_RESULTS, Boolean flags, Long millis) so `putExtra` overload resolution is unchanged.
     */
    fun srRecognizerExtras(packageName: String): Map<String, Any>

    /** Streams muted around `startListening` to hide the SR beep. */
    val srBeepStreams: List<AudioStream>

    /** `adjustStreamVolume(ADJUST_MUTE)` on M+ (23), `setStreamMute` before. */
    fun srBeepMuteUsesAdjustStreamVolume(sdkInt: Int): Boolean

    /** Text of a Vosk result JSON (`{"text":…}` or `{"partial":…}`), trimmed; "" when absent/malformed. */
    fun voskResultText(json: String): String

    /**
     * whisper-1 over [http] (multipart `file=wake.wav`, `model=whisper-1`, `response_format=json`,
     * `language=en`, `temperature=0`, `Authorization: Bearer <key>`). The key is cached; a 401
     * clears the cache.
     */
    fun whisperClient(http: OkHttpClient, endpointUrl: String, keys: OpenAiKeyProvider, log: VoiceLog): WhisperTranscriber

    fun engine(deps: WakeEngineDeps, config: WakeConfig): WakeWordEngine
}
