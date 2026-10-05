package com.assistant.core.audio

/**
 * Every tuned audio value of the voice stack (inv04 §4.6–§4.8, §4.10), ported verbatim from the
 * old app at `e871d05` (`tools/parity/old_constants.json` is authoritative).
 *
 * Values are frozen (memory `feedback_dont_touch_wake_word_tuning.md`, `feedback_dont_shortcut_echo_ducking.md`):
 * changing one needs Rodrigo's per-constant approval. `AudioTuningTest`, `EchoDuckTuningTest` and
 * `OldConstantsCrossCheckTest` pin them by name.
 */
object AudioTuning {
    // ---- Mic capture (old `MicCapture.kt`) ----

    /** 20 ms at 24 kHz mono PCM16 = 960 bytes; matches the web frontend's cadence (*wire*). */
    const val MIC_CHUNK_FRAMES = 480

    /** No speaker chunk for this long ⇒ the agent's turn ended (Gemini's bursty chunk gaps). */
    const val AGENT_SPEECH_STALE_MS = 800L

    /** `[MIC_PROBE]` line every this many mic chunks (~1 s at 20 ms), `1589cfb` diagnostics (*plumb*). */
    const val MIC_PROBE_WINDOW_CHUNKS = 50

    /** VOICE_RECOGNITION below this API level, VOICE_COMMUNICATION from it (`55037c2`, RS-24). */
    const val MIC_SOURCE_SWITCH_SDK = 24

    /** Both buffers are at least this many times the platform minimum (jitter headroom). */
    const val BUFFER_MIN_MULTIPLIER = 4

    /** Mic buffer floor: `rate * 2 / 5` bytes (200 ms of mono PCM16). */
    const val MIC_BUFFER_FLOOR_NUMERATOR = 2
    const val MIC_BUFFER_FLOOR_DENOMINATOR = 5

    /** HAL settle between the wake-word AudioRecord release and the call mic open (`55037c2`). */
    const val HAL_SETTLE_MS = 200L

    // ---- Speaker (old `PcmPlayback.kt`) ----

    /** Speaker buffer floor in seconds of audio: `bytesPerSecond * 1.5` (= 72000 bytes at 24 kHz). */
    const val SPEAKER_BUFFER_SECONDS = 1.5

    /** A full AudioTrack buffer parks the remainder and retries after this long (*plumb*). */
    const val PLAYBACK_FULL_RETRY_MS = 10L

    // ---- Gains (old `VoiceManager.kt` / `EchoDuckController.kt`) ----

    const val DEFAULT_MIC_GAIN = 1.0f
    const val MIC_GAIN_MIN = 0.0f
    const val MIC_GAIN_MAX = 2.0f

    /** `b9e6352`: duck to 5 %, not mute, so barge-in still works. */
    const val DEFAULT_ECHO_DUCKING_GAIN = 0.05f
    const val ECHO_DUCKING_GAIN_MIN = 0.0f
    const val ECHO_DUCKING_GAIN_MAX = 1.0f

    /** STREAM_VOICE_CALL ships muted on some devices: raise 0 → `max(1, max * 0.75)`. */
    const val CALL_VOLUME_RAISE_FRACTION = 0.75

    // ---- Echo duck drain-then-restore (old `EchoDuckController.kt`) ----

    /** `cffec38` (600 → 1000): after the drain, pad for speaker decay, room reverb and BT latency. */
    const val MIC_RESTORE_TAIL_MS = 1000L

    /** Poll interval while waiting for the speaker buffer to drain. */
    const val MIC_RESTORE_DRAIN_POLL_MS = 80L

    /** `2ccee40`: written frames must stay constant this long before the writer counts as done. */
    const val MIC_RESTORE_WRITES_QUIET_MS = 400L

    /** A drain-loop debug line every this many polls (*plumb*). */
    const val MIC_RESTORE_DRAIN_LOG_EVERY_POLLS = 10

    // ---- API levels (inv04 §5.1) ----

    /** `AudioTrack.Builder`, `WRITE_NON_BLOCKING`, `setPreferredDevice`, `getDevices`, device callback. */
    const val SDK_M = 23

    /** `AudioFocusRequest`. */
    const val SDK_O = 26

    /** `setCommunicationDevice` family, BLE device types, BLUETOOTH_CONNECT. */
    const val SDK_S = 31
}
