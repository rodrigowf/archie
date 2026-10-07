package com.assistant.core.voice

/**
 * Every tuned value of the realtime voice session and the provider transports (inv04 §4.6, §4.9,
 * §2.1 wire defaults), ported verbatim from the old app at `e871d05`
 * (`tools/parity/old_constants.json` is authoritative; `OldConstantsCrossCheckTest`,
 * `SessionTuningTest` and `WebRtcTuningTest` pin them by name).
 *
 * Gains, clamps, buffer sizes, the HAL settle and the echo-duck drain live in
 * `com.assistant.core.audio.AudioTuning` (`:core:audio`).
 */
object VoiceTuning {
    // ---- Session (old `VoiceController.kt` / `VoiceManager.kt`) ----

    /** `67a7958`: after `voice_stop` / `voice_ending`, wait this long for `voice_ended`, then finalize locally. */
    const val ENDING_ACK_TIMEOUT_MS = 5_000L

    /** `0bc612f`: WebRTC holds the AudioRecord after `stop()`; re-arming the wake word earlier retries 20+ times. */
    const val MIC_RELEASE_DELAY_MS = 1_500L

    /** Wake pause/resume ack hand-off; start proceeds (and finalize returns) after this even without an ack. */
    const val WAKE_WORD_ACK_TIMEOUT_MS = 2_000L

    /**
     * NEW (spec 12 §6.11a SW-3): after `voice_ended{reason:"switch"}` the call continues in the resumed
     * conversation, whose `voice_start` cancels the pending wake re-arm. The re-arm waits this long
     * instead of [MIC_RELEASE_DELAY_MS], so the wake word does not start and stop again in between; it
     * still re-arms if the switch never arrives. *UX*.
     */
    const val SWITCH_WAKE_REARM_DELAY_MS = 10_000L

    /**
     * `d36d31b`: the WebRTC ADM / Samsung HAL re-pin the earpiece during native audio init, so the
     * route is re-applied after these SEQUENTIAL delays, i.e. at +1 s, +4 s and +9 s (inv04 §12 errata 1).
     */
    @JvmField
    val ROUTE_REAPPLY_DELAYS_MS: List<Long> = listOf(1_000L, 3_000L, 5_000L)

    // ---- Wire defaults (old `VoiceConfig.DEFAULT`, `ApiClient`, `OpenAIVoiceProvider`) ----

    const val DEFAULT_PROVIDER = "openai"
    const val DEFAULT_MODEL = "gpt-realtime"
    const val DEFAULT_VOICE = "cedar"

    /** `connection_info.audio_*_format.sample_rate` when absent (both directions, both transports). */
    const val DEFAULT_SAMPLE_RATE_HZ = 24_000

    /** SDP endpoint when `connection_info.endpoint` is empty. */
    const val DEFAULT_SDP_ENDPOINT = "https://api.openai.com/v1/realtime/calls?model=gpt-realtime"

    // ---- OpenAI WebRTC (old `OpenAIVoiceProvider.kt`) ----

    /** From `connect` until the `oai-events` data channel is open. */
    const val CONNECTION_TIMEOUT_MS = 15_000L

    /** `687442e`: mic restore after `output_audio_buffer.stopped` AND `.cleared` (the commit said 1 s for cleared; the code wins). */
    const val OPENAI_RESTORE_DELAY_MS = 2_000L

    const val DATA_CHANNEL_LABEL = "oai-events"

    /** `[AUDIO_RMS]` diagnostic every this many ADM record callbacks (*plumb*). */
    const val RMS_LOG_INTERVAL = 50

    /** The goog* audio-source constraints, all "true" (inv04 §4.9, **LB**). */
    @JvmField
    val GOOG_CONSTRAINTS: List<String> = listOf(
        "echoCancellation", "noiseSuppression", "autoGainControl", "googEchoCancellation", "googEchoCancellation2",
        "googDAEchoCancellation", "googAutoGainControl", "googAutoGainControl2", "googNoiseSuppression",
        "googNoiseSuppression2", "googHighpassFilter", "googTypingNoiseDetection",
    )

    // ---- Link loss during a live voice session (decision P-2; NEW, not from the old app) ----

    /**
     * P-2 retry budget: how long a live voice session waits for the orchestrator link to come back
     * and the backend to re-confirm voice (`session_started{voice, voice_initiator}` after the
     * re-arm) before it gives up, ends locally and shows the failure state with a manual Reconnect.
     * *UX* value chosen by A-06 (call apps give up after a similar window); tune with Rodrigo.
     */
    const val LINK_RETRY_BUDGET_MS = 30_000L

    /** Cadence of the `Reconnecting(elapsedMs)` updates while the link is down. */
    const val LINK_TICK_MS = 1_000L
}

/** Log markers field diagnosis greps for (inv04 §10.3); asserted through the recording logger. */
object VoiceLogMarkers {
    const val MIC_STATE = "[MIC_STATE]"
    const val MIC_PROBE = "[MIC_PROBE]"
    const val AUDIO_RMS = "[AUDIO_RMS]"
    const val DRAIN_PRE_PROVIDER = "start: draining"
    const val SESSION_UPDATE_MISSING = "session.update missing at DC_OPEN"
}
