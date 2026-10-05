package com.assistant.core.audio.ports

/*
 * Platform adapter ports for the mic (AudioRecord) and the speaker (AudioTrack). Interface-only (A-04).
 * The fakes in :core:testing implement these with virtual time.
 */

data class MicSpec(
    val sampleRateHz: Int,
    val source: MicSourceType,
    val bufferBytes: Int,
)

enum class MicFailure { CONSTRUCTOR_THREW, NOT_INITIALIZED, START_RECORDING_FAILED, PERMISSION_DENIED }

sealed interface MicOpenResult {
    data class Opened(val mic: MicSource) : MicOpenResult
    data class Failed(val reason: MicFailure) : MicOpenResult
}

/** Opens AudioRecord instances (construct + `startRecording`, mono PCM16). */
interface MicSourceFactory {
    /** `AudioRecord.getMinBufferSize(rate, MONO, PCM16)`. */
    fun minBufferBytes(sampleRateHz: Int): Int
    fun open(spec: MicSpec): MicOpenResult
}

/**
 * One recording AudioRecord. [read] suspends for as long as the audio takes to arrive (the
 * production adapter runs the blocking `AudioRecord.read` on its confined IO thread), so virtual
 * time in tests matches real pacing: reading 1600 samples at 16 kHz takes 100 ms.
 *
 * Return value: samples read (> 0), 0 (nothing yet — retry), or a negative AudioRecord error code
 * ([ERROR_INVALID_OPERATION] / [ERROR_BAD_VALUE] end a capture loop, inv04 §4.7).
 */
interface MicSource {
    val spec: MicSpec
    suspend fun read(buffer: ShortArray, offset: Int, length: Int): Int

    /** stop + release. Must be called on the thread that reads (Vosk/AudioRecord confinement, R4). */
    fun release()

    companion object {
        const val ERROR_INVALID_OPERATION = -3
        const val ERROR_BAD_VALUE = -2
    }
}

data class TrackSpec(
    val sampleRateHz: Int,
    val bufferBytes: Int,
    val speakerMode: SpeakerMode,
    val constructor: TrackConstructor,
    /** Only for [TrackConstructor.LEGACY_STREAM_TYPE]. */
    val legacyStream: AudioStream?,
)

/** One AudioTrack (MODE_STREAM, mono PCM16). */
interface AudioTrackPort {
    val isInitialized: Boolean
    fun play()
    fun pause()
    fun flush()
    fun stop()
    fun release()

    /** Raw `getPlaybackHeadPosition()` (signed 32-bit; wraps). */
    fun playbackHeadPosition(): Int

    /** API 23+ only: `write(data, off, size, WRITE_NON_BLOCKING)`. */
    fun writeNonBlocking(data: ByteArray, offset: Int, size: Int): Int

    /** API 21/22 path: `write(data, off, size)` (blocking). */
    fun writeBlocking(data: ByteArray, offset: Int, size: Int): Int

    /** API 23+ only. */
    fun setPreferredDevice(device: AudioDeviceRef): Boolean
}

interface AudioTrackFactory {
    /** `AudioTrack.getMinBufferSize(rate, MONO, PCM16)`. */
    fun minBufferBytes(sampleRateHz: Int): Int
    fun create(spec: TrackSpec): AudioTrackPort
}

/**
 * Speaker playback with a queue and exactly ONE writer at any time (fixes inv04 B3: a CALL↔MEDIA
 * rebuild must stop the old writer before the new one starts). Write policy per
 * [PlaybackPolicy.forSdk]; a full buffer parks the remainder and retries after 10 ms; any
 * `Throwable` from write ends the chunk (never the process). [flush] = drop the queue, then
 * pause → flush → play, and reset [totalFramesWritten] to 0.
 *
 * Log marker: `Speaker started: rate=<rate>Hz bufSize=<bytes> mode=<mode>`.
 */
interface PcmPlayer : PlaybackClock {
    fun start(mode: SpeakerMode, preferredDevice: AudioDeviceRef?)
    fun enqueue(pcm16le: ByteArray): Boolean
    fun flush(): Int
    fun setSpeakerMode(mode: SpeakerMode, preferredDevice: AudioDeviceRef?)
    fun cleanup()
}
