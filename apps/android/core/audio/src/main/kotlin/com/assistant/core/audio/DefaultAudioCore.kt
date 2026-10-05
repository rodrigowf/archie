package com.assistant.core.audio

import com.assistant.core.audio.duck.DrainEchoDucker
import com.assistant.core.audio.pcm.Pcm
import com.assistant.core.audio.playback.PcmSink
import com.assistant.core.audio.policy.DefaultAudioFocusPolicy
import com.assistant.core.audio.policy.DefaultBufferSizing
import com.assistant.core.audio.policy.DefaultCallVolumePolicy
import com.assistant.core.audio.policy.DefaultMicSourcePolicy
import com.assistant.core.audio.policy.DefaultPlaybackPolicy
import com.assistant.core.audio.ports.AudioCore
import com.assistant.core.audio.ports.AudioFocusPolicy
import com.assistant.core.audio.ports.AudioManagerPort
import com.assistant.core.audio.ports.AudioTrackFactory
import com.assistant.core.audio.ports.BufferSizing
import com.assistant.core.audio.ports.CallVolumePolicy
import com.assistant.core.audio.ports.EchoDucker
import com.assistant.core.audio.ports.MicSourcePolicy
import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.audio.ports.PcmMath
import com.assistant.core.audio.ports.PcmPlayer
import com.assistant.core.audio.ports.PlaybackClock
import com.assistant.core.audio.ports.PlaybackPolicy
import com.assistant.core.audio.ports.RouteApplier
import com.assistant.core.audio.ports.RouteDecider
import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.audio.routing.DefaultRouteDecider
import com.assistant.core.audio.routing.PolicyRouteApplier
import kotlin.coroutines.CoroutineContext

/**
 * The `:core:audio` entry point (registered in `META-INF/services` for the parity harness; the app
 * graph may construct it directly). Pure: no Android type is touched here, the Android adapters
 * live in `com.assistant.core.audio.platform`.
 */
class DefaultAudioCore : AudioCore {
    override val pcm: PcmMath get() = Pcm
    override val routeDecider: RouteDecider get() = DefaultRouteDecider
    override val micSourcePolicy: MicSourcePolicy get() = DefaultMicSourcePolicy
    override val playbackPolicy: PlaybackPolicy get() = DefaultPlaybackPolicy
    override val audioFocusPolicy: AudioFocusPolicy get() = DefaultAudioFocusPolicy
    override val bufferSizing: BufferSizing get() = DefaultBufferSizing
    override val callVolumePolicy: CallVolumePolicy get() = DefaultCallVolumePolicy

    override fun echoDucker(clock: MonotonicClock, playback: PlaybackClock, log: VoiceLog): EchoDucker =
        DrainEchoDucker(clock, playback, log)

    override fun routeApplier(audioManager: AudioManagerPort, sdkInt: Int, log: VoiceLog): RouteApplier =
        PolicyRouteApplier(audioManager, sdkInt, log)

    override fun pcmPlayer(
        tracks: AudioTrackFactory,
        sdkInt: Int,
        sampleRateHz: Int,
        writerContext: CoroutineContext,
        log: VoiceLog,
    ): PcmPlayer = PcmSink(tracks, sdkInt, sampleRateHz, writerContext, log)
}
