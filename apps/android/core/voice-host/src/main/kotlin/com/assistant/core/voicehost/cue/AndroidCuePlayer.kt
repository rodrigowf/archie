package com.assistant.core.voicehost.cue

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.voicehost.HostTuning
import java.util.EnumMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Plays cues on STREAM_MUSIC with a static `AudioTrack`, exactly like the old
 * `AssistantViewModel.playTones` (legacy stream-type constructor, MODE_STATIC, stop + release
 * [HostTuning.CUE_RELEASE_PAD_MS] after the last frame). Playback is serialized on one daemon
 * thread, so overlapping cues queue instead of fighting over the stream; [play] returns at once
 * (RS-45: the caller's state flip is not delayed by audio).
 *
 * PCM comes from the shipped WAV assets (`assets/voicehost/cues/`), cached after the first load;
 * a missing or malformed asset falls back to [ToneSynth].
 */
class AndroidCuePlayer(context: Context, private val log: VoiceLog) : CuePlayer {
    private val assets = context.applicationContext.assets
    private val cache = EnumMap<CueKind, ShortArray>(CueKind::class.java)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "voicehost-cues").apply { isDaemon = true } }

    override fun play(kind: CueKind) {
        executor.execute {
            try {
                playBlocking(kind)
            } catch (e: Exception) {
                log.w(TAG, "cue ${kind.name} failed: ${e.message}")
            }
        }
    }

    private fun pcmFor(kind: CueKind): ShortArray = synchronized(cache) {
        cache.getOrPut(kind) {
            val fromAsset = try {
                assets.open(kind.assetPath).use { ToneSynth.parseWav(it.readBytes()) }
                    ?.takeIf { it.second == HostTuning.CUE_SAMPLE_RATE_HZ }?.first
            } catch (_: Exception) {
                null
            }
            fromAsset ?: ToneSynth.pcm(kind.tone).also { log.w(TAG, "cue asset ${kind.assetPath} missing — synthesizing") }
        }
    }

    private fun playBlocking(kind: CueKind) {
        val pcm = pcmFor(kind)
        val sr = HostTuning.CUE_SAMPLE_RATE_HZ
        val bufSize = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(pcm.size * 2)
        @Suppress("DEPRECATION")
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC, // HostTuning.CUE_STREAM (`b586e4b`)
            sr,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
            AudioTrack.MODE_STATIC,
        )
        // A MODE_STATIC track reports STATE_NO_STATIC_DATA until write(); only UNINITIALIZED is a failure.
        // (The old app checked != STATE_INITIALIZED, so its cues never played.)
        if (track.state == AudioTrack.STATE_UNINITIALIZED) {
            log.w(TAG, "cue ${kind.name}: AudioTrack init failed state=${track.state}")
            track.release()
            return
        }
        try {
            val written = track.write(pcm, 0, pcm.size)
            if (written < 0) log.w(TAG, "cue ${kind.name}: AudioTrack write failed code=$written")
            track.play()
            Thread.sleep(pcm.size * 1000L / sr + HostTuning.CUE_RELEASE_PAD_MS)
            try { track.stop() } catch (_: Exception) {}
        } finally {
            track.release()
        }
    }

    fun shutdown() = executor.shutdownNow()

    private companion object {
        const val TAG = "VoiceCues"
    }
}
