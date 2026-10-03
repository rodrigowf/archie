package com.assistant.core.voice.parity

import org.junit.Ignore
import org.junit.Test

/**
 * inv04 §10.1 `SessionTuningTest` (inv04 §4.6, §2.1 wire defaults). Gains / clamps / the 75 % call
 * volume live in `AudioTuning` (`:core:audio`); the pool probe, recovery backoff and socket timings
 * in `:core:session` / `:core:network` (`ChannelTuningTest` in `:core:voice-host`).
 */
@Ignore("A-06")
class SessionTuningTest {
    private val pins = voicePins()

    /** `67a7958`: wait for `voice_ended`. */
    @Test fun endingAckTimeoutIs5s() = pins.long("session.ending_ack_timeout_ms", "ENDING_ACK_TIMEOUT_MS", 5_000L)

    /** `0bc612f`: WebRTC holds the AudioRecord after stop(); 20+ retries otherwise. */
    @Test fun micReleaseDelayIs1500ms() = pins.long("session.mic_release_delay_ms", "MIC_RELEASE_DELAY_MS", 1_500L)

    @Test fun wakeWordAckTimeoutIs2s() = pins.long("session.wake_word_ack_timeout_ms", "WAKE_WORD_ACK_TIMEOUT_MS", 2_000L)

    /**
     * `d36d31b`. SEQUENTIAL delays (old `for (d in longArrayOf(1000, 3000, 5000)) delay(d)`), i.e.
     * re-applies at +1 s, +4 s, +9 s — see `VoiceSessionControllerParityTest.rs27_…`.
     */
    @Test fun routeReapplyDelays() = pins.longList("session.route_reapply_delays_ms", "ROUTE_REAPPLY_DELAYS_MS", listOf(1_000L, 3_000L, 5_000L))

    @Test fun defaultProviderIsOpenAi() = pins.string("voice.default_provider", "DEFAULT_PROVIDER", "openai")

    @Test fun defaultModelIsGptRealtime() = pins.string("voice.default_model", "DEFAULT_MODEL", "gpt-realtime")

    @Test fun defaultVoiceIsCedar() = pins.string("voice.default_voice", "DEFAULT_VOICE", "cedar")

    @Test fun defaultSampleRateIs24k() = pins.int("voice.default_sample_rate_hz", "DEFAULT_SAMPLE_RATE_HZ", 24_000)

    @Test fun wsProviderDefaultSampleRateIs24k() = pins.int("voice.ws_default_sample_rate_hz", "DEFAULT_SAMPLE_RATE_HZ", 24_000)

    @Test
    fun defaultSdpEndpoint() =
        pins.string("voice.default_sdp_endpoint", "DEFAULT_SDP_ENDPOINT", "https://api.openai.com/v1/realtime/calls?model=gpt-realtime")
}
