package com.assistant.core.audio.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.assistant.core.audio.ports.AudioDeviceRef
import com.assistant.core.audio.ports.AudioStream
import com.assistant.core.audio.ports.AudioTrackFactory
import com.assistant.core.audio.ports.AudioTrackPort
import com.assistant.core.audio.ports.SpeakerMode
import com.assistant.core.audio.ports.TrackConstructor
import com.assistant.core.audio.ports.TrackSpec

/**
 * Builds MODE_STREAM mono PCM16 AudioTracks (old `PcmPlayback.buildAudioTrack`, :316-357).
 * Which constructor and which write call is decided by
 * [com.assistant.core.audio.policy.DefaultPlaybackPolicy]; this adapter only executes it.
 *
 *  - CALL  → `USAGE_VOICE_COMMUNICATION` / `CONTENT_TYPE_SPEECH` (legacy `STREAM_VOICE_CALL`);
 *  - MEDIA → `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC` (legacy `STREAM_MUSIC`).
 */
class AndroidAudioTrackFactory(context: Context) : AudioTrackFactory {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun minBufferBytes(sampleRateHz: Int): Int =
        AudioTrack.getMinBufferSize(sampleRateHz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)

    override fun create(spec: TrackSpec): AudioTrackPort {
        val track = if (spec.constructor == TrackConstructor.BUILDER_WITH_ATTRIBUTES && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val attrs = when (spec.speakerMode) {
                SpeakerMode.CALL -> AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                SpeakerMode.MEDIA -> AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            }
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(spec.sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(spec.bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            val stream = AndroidAudioTypes.streamType(
                spec.legacyStream ?: if (spec.speakerMode == SpeakerMode.MEDIA) AudioStream.MUSIC else AudioStream.VOICE_CALL,
            )
            @Suppress("DEPRECATION")
            AudioTrack(stream, spec.sampleRateHz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, spec.bufferBytes, AudioTrack.MODE_STREAM)
        }
        return AndroidAudioTrack(track, audioManager)
    }
}

private class AndroidAudioTrack(
    private val track: AudioTrack,
    private val audioManager: AudioManager,
) : AudioTrackPort {
    override val isInitialized: Boolean get() = track.state == AudioTrack.STATE_INITIALIZED
    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun flush() = track.flush()

    override fun stop() {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
    }

    override fun release() = track.release()
    override fun playbackHeadPosition(): Int = track.playbackHeadPosition

    override fun writeNonBlocking(data: ByteArray, offset: Int, size: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) throw NoSuchMethodError("AudioTrack.write(byte[],int,int,int) needs API 23")
        return track.write(data, offset, size, AudioTrack.WRITE_NON_BLOCKING)
    }

    override fun writeBlocking(data: ByteArray, offset: Int, size: Int): Int = track.write(data, offset, size)

    override fun setPreferredDevice(device: AudioDeviceRef): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val info = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == device.id } ?: return false
        return track.setPreferredDevice(info)
    }
}
