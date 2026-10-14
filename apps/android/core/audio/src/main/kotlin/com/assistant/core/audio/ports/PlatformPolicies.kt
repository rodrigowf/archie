package com.assistant.core.audio.ports

/*
 * API-level branches as pure policies taking `sdkInt` (spec 14 §6.1: Robolectric cannot run
 * API 21/22, so these are tested on the JVM at 21, 22, 23, 26, 30, 31, 34, 36). Interface-only (A-04).
 */

/** `MediaRecorder.AudioSource` values the voice stack uses. */
enum class MicSourceType(val androidValue: Int) { VOICE_RECOGNITION(6), VOICE_COMMUNICATION(7) }

/**
 * VOICE_RECOGNITION below API 24 (N), VOICE_COMMUNICATION from 24 (inv04 §5.1, `55037c2`).
 * The wake-word mic, the WS mic and the WebRTC ADM all use the SAME source on a given device,
 * so the HAL AGC state is continuous between wake and call (RS-24).
 */
interface MicSourcePolicy {
    fun sourceFor(sdkInt: Int): MicSourceType
}

enum class TrackWriteMode {
    /** `write(data, off, size, WRITE_NON_BLOCKING)` — API 23+. */
    NON_BLOCKING_4ARG,

    /** `write(data, off, size)` — the only variant on API 21/22 (`20217b1` NoSuchMethodError). */
    BLOCKING_3ARG,
}

enum class TrackConstructor {
    /** `AudioTrack.Builder` + `AudioAttributes` — API 23+. */
    BUILDER_WITH_ATTRIBUTES,

    /** `AudioTrack(streamType, …)` — API 21/22. */
    LEGACY_STREAM_TYPE,
}

data class PlaybackApi(
    val writeMode: TrackWriteMode,
    val constructor: TrackConstructor,
    /** `AudioTrack.setPreferredDevice` — API 23+. */
    val canSetPreferredDevice: Boolean,
    /** `AudioManager.registerAudioDeviceCallback` — API 23+. */
    val hasDeviceCallback: Boolean,
)

interface PlaybackPolicy {
    fun forSdk(sdkInt: Int): PlaybackApi

    /** Legacy constructor stream: CALL → VOICE_CALL, MEDIA → MUSIC. */
    fun legacyStreamFor(mode: SpeakerMode): AudioStream
}

enum class FocusGain { GAIN_TRANSIENT_EXCLUSIVE }

enum class FocusUsage { VOICE_COMMUNICATION }

enum class FocusContentType { SPEECH }

data class FocusRequestSpec(
    val gain: FocusGain,
    val usage: FocusUsage,
    val contentType: FocusContentType,
    /** `AudioFocusRequest` (API 26+) vs `requestAudioFocus(null, STREAM_VOICE_CALL, …)`. */
    val useAudioFocusRequest: Boolean,
    val legacyStream: AudioStream,
)

/** inv04 §4.6 "Audio focus" (**LB**). */
interface AudioFocusPolicy {
    fun forSdk(sdkInt: Int): FocusRequestSpec
}

/** Buffer formulas (inv04 §4.7, **LB**). */
interface BufferSizing {
    /** `max(minBuf * 4, rate * 2 / 5)` bytes (≥200 ms of mono PCM16). */
    fun micBufferBytes(minBufferBytes: Int, sampleRateHz: Int): Int

    /** `max(minBuf * 4, bytesPerSecond * 1.5)` bytes (= 72000 at 24 kHz). */
    fun speakerBufferBytes(minBufferBytes: Int, sampleRateHz: Int): Int
}

/**
 * STREAM_VOICE_CALL ships muted on some devices (inv04 §4.6, **LB**): when the current volume is 0,
 * raise it to `max(1, (max * 0.75).toInt())`; otherwise leave it (null).
 */
interface CallVolumePolicy {
    fun raisedVolume(current: Int, max: Int): Int?
}
