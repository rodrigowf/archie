package com.assistant.core.audio.playback

import com.assistant.core.audio.AudioTuning
import com.assistant.core.audio.policy.DefaultBufferSizing
import com.assistant.core.audio.policy.DefaultPlaybackPolicy
import com.assistant.core.audio.ports.AudioDeviceRef
import com.assistant.core.audio.ports.AudioTrackFactory
import com.assistant.core.audio.ports.AudioTrackPort
import com.assistant.core.audio.ports.BufferSizing
import com.assistant.core.audio.ports.PcmPlayer
import com.assistant.core.audio.ports.PlaybackPolicy
import com.assistant.core.audio.ports.SpeakerMode
import com.assistant.core.audio.ports.TrackConstructor
import com.assistant.core.audio.ports.TrackSpec
import com.assistant.core.audio.ports.TrackWriteMode
import com.assistant.core.audio.ports.VoiceLog
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Speaker playback (old `PcmPlayback`, inv04 §4.7) with exactly ONE writer (fixes inv04 B3).
 *
 * The old `setSpeakerMode` released the track and called `start()`, which launched a second
 * writer while the first one kept writing to the captured (released) track and racing the frame
 * counter the duck drain reads. Here:
 *  - one writer coroutine per player, launched by [start] only when none is running, and it
 *    survives CALL↔MEDIA rebuilds;
 *  - every track operation (write, rebuild, flush, release) and the frame counter sit behind one
 *    lock, so a rebuild never releases a track mid-write, and a writer only ever writes to the
 *    current track;
 *  - a generation number retires a cancelled writer, so it can never write to a track built
 *    after [cleanup].
 *
 * Kept verbatim: the unbounded queue, the 1.5 s buffer formula, the write policy per API level
 * (`20217b1`), "a Throwable from write drops the rest of the chunk", the 10 ms full-buffer park,
 * flush = drop queue + pause → flush → play + counter reset (the parked remainder, if any, still
 * plays, as before), and the log lines.
 */
class PcmSink(
    private val tracks: AudioTrackFactory,
    sdkInt: Int,
    private val sampleRateHz: Int,
    writerContext: CoroutineContext,
    private val log: VoiceLog,
    private val tag: String = TAG,
    private val playbackPolicy: PlaybackPolicy = DefaultPlaybackPolicy,
    private val bufferSizing: BufferSizing = DefaultBufferSizing,
) : PcmPlayer {
    private val api = playbackPolicy.forSdk(sdkInt)
    private val scope = CoroutineScope(writerContext + SupervisorJob(writerContext[Job]))

    /** Decouples the WS receive thread from AudioTrack.write (old 4,500-frame UI freezes). Never closed. */
    private val queue = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    private val lock = Any()

    // Guarded by [lock].
    private var track: AudioTrackPort? = null
    private var totalFramesWritten = 0L
    private var pending: ByteArray? = null
    private var writerGeneration = 0
    private var writerRunning = false
    private var writerJob: Job? = null

    override fun headPositionFrames(): Long? = synchronized(lock) {
        val t = track ?: return null
        if (!t.isInitialized) return null
        t.playbackHeadPosition().toLong()
    }

    override fun totalFramesWritten(): Long = synchronized(lock) { totalFramesWritten }

    override fun enqueue(pcm16le: ByteArray): Boolean {
        val result = queue.trySend(pcm16le)
        if (result.isFailure) {
            log.w(tag, "speakerQueue full or closed; dropping chunk (${pcm16le.size}B)")
            return false
        }
        return true
    }

    override fun flush(): Int {
        var dropped = 0
        while (queue.tryReceive().isSuccess) dropped++
        synchronized(lock) {
            try {
                val t = track
                if (t != null && t.isInitialized) {
                    t.pause()
                    t.flush()
                    t.play()
                    // flush() resets playbackHeadPosition to 0; keep the drain comparison valid.
                    totalFramesWritten = 0L
                }
            } catch (e: Exception) {
                log.w(tag, "AudioTrack flush failed: ${e.message}")
            }
        }
        return dropped
    }

    /** Builds the track for [mode] and starts the writer if none is running. Throws if the track fails to initialise. */
    override fun start(mode: SpeakerMode, preferredDevice: AudioDeviceRef?) {
        synchronized(lock) {
            // A second start() without cleanup() used to leak the previous track; release it.
            track?.let { stopAndRelease(it, "stopSpeakerOnly failed") }
            track = null
            track = buildStartedTrack(mode, preferredDevice)
            if (!writerRunning) {
                writerRunning = true
                val gen = ++writerGeneration
                writerJob = scope.launch { writerLoop(gen) }
            }
        }
    }

    /** Rebuilds the track for a CALL↔MEDIA change; no-op before [start] / after [cleanup]. Keeps the writer. */
    override fun setSpeakerMode(mode: SpeakerMode, preferredDevice: AudioDeviceRef?) {
        synchronized(lock) {
            val old = track ?: return
            log.i(tag, "setSpeakerMode → $mode (rebuilding AudioTrack)")
            track = null
            stopAndRelease(old, "stopSpeakerOnly failed")
            try {
                track = buildStartedTrack(mode, preferredDevice)
            } catch (e: Exception) {
                log.e(tag, "AudioTrack rebuild failed: ${e.message}", e)
            }
        }
    }

    override fun cleanup() {
        val job: Job?
        synchronized(lock) {
            writerGeneration++ // retires the running writer, even mid-chunk
            writerRunning = false
            job = writerJob
            writerJob = null
            totalFramesWritten = 0L
            pending = null
            while (queue.tryReceive().isSuccess) Unit
            track?.let { stopAndRelease(it, "Error stopping AudioTrack") }
            track = null
        }
        job?.cancel()
    }

    // Must hold [lock].
    private fun buildStartedTrack(mode: SpeakerMode, preferredDevice: AudioDeviceRef?): AudioTrackPort {
        val bufSize = bufferSizing.speakerBufferBytes(tracks.minBufferBytes(sampleRateHz), sampleRateHz)
        val spec = TrackSpec(
            sampleRateHz = sampleRateHz,
            bufferBytes = bufSize,
            speakerMode = mode,
            constructor = api.constructor,
            legacyStream = if (api.constructor == TrackConstructor.LEGACY_STREAM_TYPE) playbackPolicy.legacyStreamFor(mode) else null,
        )
        val t = tracks.create(spec)
        if (!t.isInitialized) {
            t.release()
            throw IllegalStateException("AudioTrack failed to initialize")
        }
        if (preferredDevice != null && api.canSetPreferredDevice) {
            try {
                val ok = t.setPreferredDevice(preferredDevice)
                log.d(tag, "AudioTrack pinned to ${preferredDevice.name} (type=${preferredDevice.type}) → $ok")
            } catch (e: Exception) {
                log.w(tag, "AudioTrack.setPreferredDevice failed: ${e.message}")
            }
        }
        t.play()
        log.d(tag, "Speaker started: rate=${sampleRateHz}Hz bufSize=$bufSize mode=$mode")
        return t
    }

    private fun stopAndRelease(t: AudioTrackPort, failure: String) {
        try {
            t.stop()
            t.release()
        } catch (e: Exception) {
            log.w(tag, "$failure: ${e.message}")
            try { t.release() } catch (_: Exception) {}
        }
    }

    /** Outcome of one locked write attempt. */
    private sealed interface Step {
        data class Wrote(val bytes: Int) : Step
        data object Full : Step
        data object Failed : Step
        data object Retired : Step
    }

    private suspend fun writerLoop(gen: Int) {
        try {
            while (true) {
                val parked = synchronized(lock) {
                    if (gen != writerGeneration) return
                    pending.also { pending = null }
                }
                val data = parked ?: run {
                    val r = queue.receiveCatching()
                    if (r.isClosed) return
                    r.getOrNull()
                } ?: continue

                var offset = 0
                while (offset < data.size) {
                    when (val step = writeOnce(gen, data, offset)) {
                        is Step.Wrote -> offset += step.bytes
                        Step.Failed -> break // permanent error: drop the rest of this chunk
                        Step.Retired -> return
                        Step.Full -> {
                            // Hardware buffer full: park the rest and let the buffer drain.
                            synchronized(lock) { if (gen == writerGeneration) pending = data.copyOfRange(offset, data.size) }
                            delay(AudioTuning.PLAYBACK_FULL_RETRY_MS)
                            break
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(tag, "Playback loop ended with: ${e.message}")
        } finally {
            synchronized(lock) { if (gen == writerGeneration) writerRunning = false }
            log.d(tag, "Playback loop exited")
        }
    }

    private fun writeOnce(gen: Int, data: ByteArray, offset: Int): Step = synchronized(lock) {
        if (gen != writerGeneration) return Step.Retired
        val t = track
        if (t == null || !t.isInitialized) {
            // Old behaviour: the writer ends when the track is gone; the next start() relaunches one.
            writerRunning = false
            writerGeneration++
            return Step.Retired
        }
        val size = data.size - offset
        // NoSuchMethodError on Lollipop is a Throwable, not an Exception (`20217b1`).
        val written = try {
            when (api.writeMode) {
                TrackWriteMode.NON_BLOCKING_4ARG -> t.writeNonBlocking(data, offset, size)
                TrackWriteMode.BLOCKING_3ARG -> t.writeBlocking(data, offset, size)
            }
        } catch (e: Throwable) {
            log.w(tag, "AudioTrack.write failed: ${e.message}")
            -1
        }
        when {
            written < 0 -> Step.Failed
            written == 0 -> Step.Full
            else -> {
                totalFramesWritten += (written / 2).toLong() // PCM16 mono: 2 bytes per frame
                Step.Wrote(written)
            }
        }
    }

    private companion object {
        const val TAG = "PcmSink"
    }
}
