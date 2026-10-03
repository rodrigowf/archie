package com.assistant.core.audio.parity

import com.assistant.core.testing.PinsConstant
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

/**
 * inv04 §10.1 `AudioTuningTest`: one assertion per LB/wire audio I/O constant (inv04 §4.6,
 * §4.7). Values are frozen (memory `feedback_dont_touch_wake_word_tuning.md`); changing one needs
 * Rodrigo's per-constant approval.
 */
@Ignore("A-05")
class AudioTuningTest {
    private val pins = audioPins()

    @Test fun micChunkFramesIs480() = pins.int("audio.mic_chunk_frames", "MIC_CHUNK_FRAMES", 480)

    @Test fun agentSpeechStaleIs800ms() = pins.long("audio.agent_speech_stale_ms", "AGENT_SPEECH_STALE_MS", 800L)

    @Test fun playbackFullBufferRetryIs10ms() = pins.long("audio.full_buffer_retry_ms", "PLAYBACK_FULL_RETRY_MS", 10L)

    @Test fun halSettleBeforeMicIs200ms() = pins.long("audio.hal_settle_ms", "HAL_SETTLE_MS", 200L)

    @Test fun micSourceSwitchesAtApi24() = pins.int("audio.mic_source_switch_sdk", "MIC_SOURCE_SWITCH_SDK", 24)

    @Test fun defaultMicGainIs1() = pins.float("session.default_mic_gain", "DEFAULT_MIC_GAIN", 1.0f)

    @Test fun micGainClampMaxIs2() = pins.float("session.mic_gain_max", "MIC_GAIN_MAX", 2.0f)

    @Test fun defaultEchoDuckingGainIs5Percent() = pins.float("session.default_echo_ducking_gain", "DEFAULT_ECHO_DUCKING_GAIN", 0.05f)

    @Test fun echoDuckingGainClampMaxIs1() = pins.float("session.echo_ducking_gain_max", "ECHO_DUCKING_GAIN_MAX", 1.0f)

    @Test fun callVolumeRaiseFractionIs75Percent() = pins.double("session.call_volume_raise_fraction", "CALL_VOLUME_RAISE_FRACTION", 0.75)

    /** `max(minBuf * 4, rate * 2 / 5)`. */
    @Test
    @PinsConstant("audio.mic_buffer_formula")
    fun micBufferFormula() {
        val s = audioCore.bufferSizing
        assertEquals(9600, s.micBufferBytes(minBufferBytes = 1000, sampleRateHz = 24000))
        assertEquals(6400, s.micBufferBytes(minBufferBytes = 1000, sampleRateHz = 16000))
        assertEquals(16000, s.micBufferBytes(minBufferBytes = 4000, sampleRateHz = 24000))
    }

    /** `max(minBuf * 4, bytesPerSec * 1.5)` = 72000 at 24 kHz. */
    @Test
    @PinsConstant("audio.speaker_buffer_formula")
    fun speakerBufferFormulaIs72000At24k() {
        val s = audioCore.bufferSizing
        assertEquals(72000, s.speakerBufferBytes(minBufferBytes = 4000, sampleRateHz = 24000))
        assertEquals(80000, s.speakerBufferBytes(minBufferBytes = 20000, sampleRateHz = 24000))
        assertEquals(48000, s.speakerBufferBytes(minBufferBytes = 1000, sampleRateHz = 16000))
    }

    @Test
    fun callVolumeRaisedTo75PercentOnlyWhenMuted() {
        val p = audioCore.callVolumePolicy
        assertEquals(5, p.raisedVolume(current = 0, max = 7))
        assertEquals(1, p.raisedVolume(current = 0, max = 1))
        assertEquals(null, p.raisedVolume(current = 3, max = 7))
    }
}
