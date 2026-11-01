package com.assistant.archie.tv.voice

import com.assistant.core.audio.ports.MicSourceType

/**
 * Archie TV's voice values (spec 15 §2, §4). New TV constants: the shared `AudioTuning` /
 * `HostTuning` / `VoiceTuning` values are frozen and are not changed for the TV.
 */
object TvVoiceTuning {
    /** The only source the Fire TV remote's microphone reaches (spec 15 §2, rounds 1–3). */
    val REMOTE_MIC_SOURCE = MicSourceType.VOICE_RECOGNITION

    /** Remote capture: 16 kHz mono PCM16 (the probe's format; Whisper and `send_audio` take it as is). */
    const val SAMPLE_RATE_HZ = 16_000

    /** One read = 20 ms. Short reads keep live voice latency low; the gap logic does not depend on it. */
    const val READ_SAMPLES = 320

    /** End of a burst = no frame for this long (frames arrive every ≤ 100 ms while the button is held). */
    const val BURST_END_GAP_MS = 350L

    /** Bursts shorter than this (audio time) are accidental taps and are dropped. */
    const val MIN_BURST_MS = 250L

    /** Hard cap of one burst (spec 15 §4.1: 60 s; `HostTuning.PTT_MAX_MS` is 120 s, so this is the TV's own). */
    const val MAX_BURST_MS = 60_000L

    /** Watchdog tick that measures the gap (`read()` blocks while the button is up). */
    const val WATCHDOG_TICK_MS = 50L

    /**
     * Continuous-stream probe (devices where `read()` never blocks): a burst that starts this soon
     * after arming is held back until it pauses (a real press, delivered then) or until
     * [PROBE_MS] after arming without a [BURST_END_GAP_MS] gap (the mic streams continuously: the
     * source is unsupported and disarms). On the AFTKM nothing arrives until the button is held.
     */
    const val PROBE_START_MS = 400L

    /** See [PROBE_START_MS]. */
    const val PROBE_MS = 2_500L

    /** The recorder is re-opened this long after a failure while the app is in front. */
    const val REARM_RETRY_MS = 3_000L

    /** Live voice: remote audio is buffered this long before it is played into the call (BLE jitter). */
    const val LIVE_PREROLL_MS = 200L

    /** Live voice: the most remote audio held for the call; older audio is dropped (never lag behind). */
    const val LIVE_MAX_BUFFER_MS = 2_000L

    /** Live voice ends after this much silence on both sides (setting, spec 15 §4.2). */
    const val DEFAULT_LIVE_IDLE_SECONDS = 120

    /** KEEP_AWAKE ping to the launcher while live voice runs (spec 15 §7.3). */
    const val LIVE_KEEP_AWAKE_MS = 60_000L

    /** Wait for a new Archie conversation to be subscribed before a voice message goes out. */
    const val NEW_CONVERSATION_WAIT_MS = 10_000L
}
