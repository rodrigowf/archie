package com.assistant.core.audio.ports

import kotlin.coroutines.CoroutineContext

/*
 * Parity entry point for :core:audio (A-04). Interface-only.
 *
 * A-05 registers exactly one implementation with `java.util.ServiceLoader`:
 *   core/audio/src/main/resources/META-INF/services/com.assistant.core.audio.ports.AudioCore
 * (public class, public no-arg constructor). The parity tests load it through
 * `com.assistant.core.testing.Parity.load<AudioCore>()`; the app graph may construct the same
 * pieces directly. Tuning values live in `com.assistant.core.audio.AudioTuning` (const vals,
 * names pinned by `AudioTuningTest` / `EchoDuckTuningTest`).
 */

/** PCM utilities (mono PCM16). */
interface PcmMath {
    fun rms(samples: ShortArray, count: Int = samples.size): Double

    /** (rms, peak) of a little-endian PCM16 byte slice. */
    fun rmsAndPeakPcm16Le(bytes: ByteArray, offset: Int, length: Int): Pair<Double, Int>

    /** In place, clamped to the Short range. */
    fun applyGainPcm16Le(bytes: ByteArray, offset: Int, length: Int, gain: Float)

    /** Standard 44-byte RIFF/WAVE header, mono, 16-bit. */
    fun wav(frames: List<ShortArray>, sampleRateHz: Int): ByteArray

    /** Base64 without line wraps (`Base64.NO_WRAP`). Must not use java.util.Base64 (API 26+). */
    fun base64(bytes: ByteArray): String
}

interface AudioCore {
    val pcm: PcmMath
    val routeDecider: RouteDecider
    val micSourcePolicy: MicSourcePolicy
    val playbackPolicy: PlaybackPolicy
    val audioFocusPolicy: AudioFocusPolicy
    val bufferSizing: BufferSizing
    val callVolumePolicy: CallVolumePolicy

    fun echoDucker(clock: MonotonicClock, playback: PlaybackClock, log: VoiceLog): EchoDucker

    fun routeApplier(audioManager: AudioManagerPort, sdkInt: Int, log: VoiceLog): RouteApplier

    /** [writerContext] hosts the single writer coroutine (tests pass a test dispatcher). */
    fun pcmPlayer(
        tracks: AudioTrackFactory,
        sdkInt: Int,
        sampleRateHz: Int,
        writerContext: CoroutineContext,
        log: VoiceLog,
    ): PcmPlayer
}
