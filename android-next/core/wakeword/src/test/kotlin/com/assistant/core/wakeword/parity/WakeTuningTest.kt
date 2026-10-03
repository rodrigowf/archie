package com.assistant.core.wakeword.parity

import org.junit.Ignore
import org.junit.Test

/**
 * inv04 §10.1 `WakeTuningTest` (inv04 §4.1). Frozen equilibrium — memory
 * `feedback_dont_touch_wake_word_tuning.md`: `2acf57c` reverted a "logical" 200→100 retune that
 * made detection "very very difficult". Any change needs Rodrigo's per-constant approval.
 */
@Ignore("A-07")
class WakeTuningTest {
    private val pins = wakePins()

    @Test fun sampleRateIs16k() = pins.int("wake.sample_rate_hz", "SAMPLE_RATE_HZ", 16000)

    /** `c60cd08` Detour 5: A300M "wake up" peaks RMS 35–82; divided by the wake gain. */
    @Test fun rmsThresholdIs70() = pins.double("wake.rms_threshold", "RMS_THRESHOLD", 70.0)

    @Test fun activityHoldIs30ms() = pins.long("wake.activity_hold_ms", "ACTIVITY_HOLD_MS", 30L)

    @Test fun preBufferIs500ms() = pins.int("wake.pre_buffer_ms", "PRE_BUFFER_MS", 500)

    @Test fun monitorBufferIsAtLeast3200Bytes() = pins.int("wake.monitor_buffer_min_bytes", "MONITOR_BUFFER_MIN_BYTES", 3200)

    @Test fun micRetryIs500ms() = pins.long("wake.mic_retry_ms", "MIC_RETRY_MS", 500L)

    @Test fun micUnavailableAfter8Failures() = pins.int("wake.mic_retry_warn_threshold", "MIC_RETRY_WARN_THRESHOLD", 8)

    @Test fun postWakewordCooldownIs3000ms() = pins.long("wake.post_wakeword_delay_ms", "POST_WAKEWORD_DELAY_MS", 3000L)

    @Test fun srBackoffBaseIs1000ms() = pins.long("wake.post_recognition_base_ms", "POST_RECOGNITION_BASE_MS", 1000L)

    @Test fun srBackoffCapIs30s() = pins.long("wake.post_recognition_max_ms", "POST_RECOGNITION_MAX_MS", 30_000L)

    /** `043340a`: NoMatch must not feed the backoff. */
    @Test fun voskNoMatchRearmIs500ms() = pins.long("wake.post_vosk_nomatch_ms", "POST_VOSK_NOMATCH_MS", 500L)

    /** `70283e3`: phantom matches on silence never reach Whisper. */
    @Test fun speechFloorIs30() = pins.double("wake.speech_floor_rms", "SPEECH_FLOOR_RMS", 30.0)

    @Test fun commandAbsoluteSpeechFloorIs30() = pins.double("wake.command_abs_speech_floor", "COMMAND_ABS_SPEECH_FLOOR", 30.0)

    @Test fun adaptiveVoiceFloorMinIs30() = pins.double("wake.adaptive_voice_floor_min", "ADAPTIVE_VOICE_FLOOR_MIN", 30.0)

    @Test fun silenceFloorAttackIs030() = pins.double("wake.silence_floor_attack", "SILENCE_FLOOR_ATTACK", 0.30)

    @Test fun silenceFloorReleaseIs002() = pins.double("wake.silence_floor_release", "SILENCE_FLOOR_RELEASE", 0.02)

    @Test fun defaultTalkSilenceSensitivityIs2() =
        pins.double("wake.default_talk_silence_sensitivity", "DEFAULT_TALK_SILENCE_SENSITIVITY", 2.0)

    @Test fun onsetSustainIs300ms() = pins.long("wake.onset_sustain_ms", "ONSET_SUSTAIN_MS", 300L)

    /** `3efbe55` 1500→1200, `f934d09` →1000, field-tuned. */
    @Test fun commandSilenceIs1000ms() = pins.long("wake.command_silence_ms", "COMMAND_SILENCE_MS", 1000L)

    @Test fun commandMaxIs12s() = pins.long("wake.command_max_ms", "COMMAND_MAX_MS", 12_000L)

    @Test fun commandOnsetTimeoutIs4s() = pins.long("wake.command_speech_onset_timeout_ms", "COMMAND_SPEECH_ONSET_TIMEOUT_MS", 4_000L)

    @Test fun captureFrameIs3200Samples() = pins.int("wake.capture_frame_samples", "CAPTURE_FRAME_SAMPLES", 3200)
}
