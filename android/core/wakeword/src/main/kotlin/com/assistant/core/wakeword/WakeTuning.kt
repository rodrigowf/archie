package com.assistant.core.wakeword

/**
 * Every tuned wake-word value (inv04 §4.1–§4.4), ported verbatim from the old app at `e871d05`
 * (`tools/parity/old_constants.json`, pinned by `WakeTuningTest`, `VoskTuningTest`, `SrTuningTest`,
 * `WhisperTuningTest` and `OldConstantsCrossCheckTest`).
 *
 * FROZEN EQUILIBRIUM (memory `feedback_dont_touch_wake_word_tuning.md`): `2acf57c` reverted a
 * "logical" 200→100 RMS retune that made detection "very very difficult". Do not change any value
 * here without Rodrigo's explicit, per-constant approval.
 */
object WakeTuning {
    // ── Detector loop (old WakeWordDetector.kt) ──────────────────────────────────────────────

    /** 16 kHz mono PCM16: the Vosk model rate and the Whisper WAV rate. */
    const val SAMPLE_RATE_HZ = 16_000

    /** Stage-1 RMS gate, divided by the wake gain (`c60cd08` Detour 5: A300M "wake up" peaks 35–82). */
    const val RMS_THRESHOLD = 70.0

    /** Activity must hold this long (from the first loud read) before recognition starts (clicks/pops). */
    const val ACTIVITY_HOLD_MS = 30L

    /** Rolling pre-buffer replayed into Vosk first, so the leading "wake" of "wake up" is kept (V5). */
    const val PRE_BUFFER_MS = 500

    /** Monitor AudioRecord buffer floor: `max(minBuf, 3200 B)` = at least 100 ms per read. */
    const val MONITOR_BUFFER_MIN_BYTES = 3200

    /** Mic acquisition retry interval (`95201b5`/`5bde0d1`: mic held by WebRTC / push-to-talk). */
    const val MIC_RETRY_MS = 500L

    /** Consecutive open failures before `MicUnavailable` (`cff6afd` Inc 9). */
    const val MIC_RETRY_WARN_THRESHOLD = 8

    /** Cooldown after any match (confirmed or rejected) so a consumed utterance cannot re-fire. */
    const val POST_WAKEWORD_DELAY_MS = 3000L

    /** SpeechRecognizer-path miss backoff: 1, 2, 4, 8, 16, 30 s. */
    const val POST_RECOGNITION_BASE_MS = 1000L
    const val POST_RECOGNITION_MAX_MS = 30_000L

    /** Vosk NoMatch re-arm, never backed off (`043340a`: "worked once then stopped"). */
    const val POST_VOSK_NOMATCH_MS = 500L

    /** Pre-Whisper gate: a clip whose loudest frame is below this is a phantom (`70283e3`). */
    const val SPEECH_FLOOR_RMS = 30.0

    /** Gain-independent speech floor of the talk-command VAD (`ae1d958`); equals [SPEECH_FLOOR_RMS]. */
    const val COMMAND_ABS_SPEECH_FLOOR = 30.0

    /** Lower clamp of the adaptive voice threshold `max(floor × K, 30)` (`4f689a4`). */
    const val ADAPTIVE_VOICE_FLOOR_MIN = COMMAND_ABS_SPEECH_FLOOR

    /** Noise-floor tracker: weight toward a lower reading (fast down) / a higher one (slow up). */
    const val SILENCE_FLOOR_ATTACK = 0.30
    const val SILENCE_FLOOR_RELEASE = 0.02

    /** End-of-utterance K when the user setting is ≤ 0. */
    const val DEFAULT_TALK_SILENCE_SENSITIVITY = 2.0

    /** Sustained voice needed before the talk recording UI appears (`ae1d958`). */
    const val ONSET_SUSTAIN_MS = 300L

    /** Silence after the last voice frame that ends a talk command (`3efbe55` → `f934d09`). */
    const val COMMAND_SILENCE_MS = 1000L

    /** Hard cap on a talk command (`ae1d958`, was 30 s). */
    const val COMMAND_MAX_MS = 12_000L

    /** No sustained onset within this window after the trigger → phantom, abort with no UI. */
    const val COMMAND_SPEECH_ONSET_TIMEOUT_MS = 4_000L

    /** Talk capture read size: 200 ms at 16 kHz (the VAD granularity). */
    const val CAPTURE_FRAME_SAMPLES = 3200

    // ── Vosk (old VoskRecognitionEngine.kt / VoskWakeWordEngine.kt / VoskModelLoader.kt) ─────

    const val VOSK_RECOGNITION_TIMEOUT_MS = 5_000L

    /** First frame at/above this RMS stamps "speech started" in the recognition window. */
    const val VOSK_RMS_STARTED_THRESHOLD = 30.0

    /** Recognition read size: 400 ms at 16 kHz. */
    const val VOSK_READ_SAMPLES = 6400

    /** Audio kept after a (possibly mid-word) Vosk match so Whisper hears the whole phrase (`dd5567f`). */
    const val MATCH_TAIL_MS = 400L

    /** Wake confirm uploads at most the trailing 2 s (`54463e1`). */
    const val MAX_CONFIRM_WINDOW_MS = 2_000L

    /** Talk prefix trigger needs at least this many leading words (`ae1d958`). */
    const val MIN_PREFIX_WORDS = 2

    /** Tail-read size after a match (200 ms). */
    const val MATCH_TAIL_READ_SAMPLES = 3200

    const val VOSK_MODEL_ASSET_ROOT = "vosk-model-small-en-us-0.15"

    /** `filesDir/vosk-model` — the SAME dir as the old app, so the lite upgrade does not re-extract 68 MB. */
    const val VOSK_MODEL_DIR = "vosk-model"
    const val VOSK_MODEL_STAMP = "vosk-model-small-en-us-0.15"
    const val VOSK_STAMP_FILE = ".stamp"

    /** Below this API level the stderr shim is loaded before Vosk (Lollipop Bionic, inv04 §5.1). */
    const val VOSK_SHIM_MAX_SDK_EXCLUSIVE = 23

    // ── SpeechRecognizer fallback (old SpeechRecognizerEngine.kt, V6 deferred) ───────────────

    const val SR_HANG_WATCHDOG_MS = 10_000L
    const val SR_REFRESH_AFTER_N = 20
    const val SR_REFRESH_NO_SPEECH_SPIKE = 2
    const val SR_NO_SPEECH_HEALTH_THRESHOLD = 8
    const val SR_CLIENT_ERROR_DELAY_MS = 1000L

    /** `SpeechRecognizer.ERROR_NO_SPEECH`, as a literal (not a named constant before API 23). */
    const val SR_ERROR_NO_SPEECH = 6

    /** `SpeechRecognizer.ERROR_CLIENT`: flat 1 s re-arm (`d93f7d7`). */
    const val SR_ERROR_CLIENT = 7

    /** `adjustStreamVolume(ADJUST_MUTE)` from API 23; `setStreamMute` before. */
    const val SR_ADJUST_MUTE_MIN_SDK = 23

    // ── Whisper gate (old WhisperConfirmer.kt) ───────────────────────────────────────────────

    /** Whole-call budget (`2f5ecd7`: A300M round trip ≈3.7 s; 2.5 s failed ~15/17). */
    const val WHISPER_TIMEOUT_MS = 10_000L
    const val WHISPER_MODEL = "whisper-1"

    /** `temperature=0` kills whisper-1's silence hallucinations (`70283e3`). */
    const val WHISPER_TEMPERATURE = "0"
    const val WHISPER_LANGUAGE = "en"
    const val WHISPER_RESPONSE_FORMAT = "json"
    const val WHISPER_UPLOAD_FILENAME = "wake.wav"
    const val WHISPER_TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"

    /** whisper-1's canned silence outputs; rejected only when the WHOLE normalized transcript equals one. */
    @JvmField
    val WHISPER_HALLUCINATION_BOILERPLATE: Set<String> = setOf(
        "you",
        "thank you",
        "thank you very much",
        "thanks for watching",
        "thanks for watching the video",
        "please subscribe",
        "bye",
        "bye bye",
        "so",
        "the",
        "okay",
        "i m sorry",
    )
}
