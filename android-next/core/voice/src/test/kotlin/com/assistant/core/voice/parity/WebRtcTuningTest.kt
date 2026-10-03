package com.assistant.core.voice.parity

import org.junit.Ignore
import org.junit.Test

/**
 * inv04 §10.1 `WebRtcTuningTest` (inv04 §4.9). The audio-processing switches (HW AEC/NS off, SW
 * AEC/NS/AGC on, goog* constraints) are asserted on what the transport hands the RTC platform, in
 * `WebRtcTransportParityTest`.
 */
@Ignore("A-06")
class WebRtcTuningTest {
    private val pins = voicePins()

    @Test fun connectionTimeoutIs15s() = pins.long("webrtc.connection_timeout_ms", "CONNECTION_TIMEOUT_MS", 15_000L)

    /** `687442e`: 2 s after `output_audio_buffer.stopped` AND `.cleared` (the commit said 1 s; the code wins). */
    @Test fun restoreDelayIs2s() = pins.long("webrtc.restore_delay_ms", "OPENAI_RESTORE_DELAY_MS", 2_000L)

    @Test fun dataChannelLabel() = pins.string("webrtc.data_channel_label", "DATA_CHANNEL_LABEL", "oai-events")
}
