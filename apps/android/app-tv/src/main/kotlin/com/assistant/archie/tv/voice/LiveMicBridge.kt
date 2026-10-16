package com.assistant.archie.tv.voice

import com.assistant.core.audio.ports.MicOpenResult
import com.assistant.core.audio.ports.MicSource
import com.assistant.core.audio.ports.MicSourceFactory
import com.assistant.core.audio.ports.MicSpec
import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.voice.ports.RtcMicFeed
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.delay

/**
 * The live call's microphone on the TV (spec 15 §4.2): a **continuous** real-time stream made of
 * the remote's audio while the button is held and digital silence otherwise, so the provider's
 * VAD always hears the end of a turn (it never waits on a stalled stream) and no second recorder
 * is ever opened on the remote.
 *
 * [RemoteMicSource] pushes the remote's 16 kHz frames in ([push] / [endOfBurst]); a transport pulls
 * at its own rate:
 * - WS relay (Qwen / Gemini): [mics] hands the transport a [MicSource] that paces itself on the clock.
 * - WebRTC (OpenAI): [rtcFeed] overwrites the ADM's 10 ms buffers; the ADM's own recorder (a source
 *   that does not block) is only the clock.
 *
 * The first [TvVoiceTuning.LIVE_PREROLL_MS] of a burst are held back so BLE jitter never puts
 * holes into speech; at most [TvVoiceTuning.LIVE_MAX_BUFFER_MS] are kept (never lag behind).
 */
class LiveMicBridge(private val clock: MonotonicClock) {
    private val lock = Any()
    private val ring = ShortArray((TvVoiceTuning.SAMPLE_RATE_HZ * TvVoiceTuning.LIVE_MAX_BUFFER_MS / 1000).toInt())
    private var head = 0 // read index
    private var size = 0
    private var primed = false
    private var bursting = false
    private val resampler = LinearResampler(TvVoiceTuning.SAMPLE_RATE_HZ)

    /** Remote frames in (16 kHz mono). */
    fun push(pcm: ShortArray) = synchronized(lock) {
        bursting = true
        for (s in pcm) {
            if (size == ring.size) { head = (head + 1) % ring.size; size-- } // drop the oldest
            ring[(head + size) % ring.size] = s
            size++
        }
        if (!primed && size >= PREROLL_SAMPLES) primed = true
    }

    /** The button went up: whatever is buffered plays out, then silence. */
    fun endOfBurst() = synchronized(lock) {
        bursting = false
        if (size > 0) primed = true
    }

    /** Drops everything (a new call starts clean). */
    fun reset() = synchronized(lock) {
        head = 0; size = 0; primed = false; bursting = false
        resampler.reset()
    }

    /** Samples buffered at 16 kHz (tests). */
    internal val buffered: Int get() = synchronized(lock) { size }

    /** Fills [out] with [count] samples at [rateHz]: remote audio when primed, zeros otherwise. */
    fun pull(out: ShortArray, count: Int, rateHz: Int) = synchronized(lock) {
        resampler.setOutputRate(rateHz)
        for (i in 0 until count) {
            out[i] = if (primed) resampler.next(::takeOne) else 0
        }
    }

    /** One 16 kHz sample for the resampler; ends the primed run when the ring runs dry. */
    private fun takeOne(): Short? {
        if (size == 0) {
            if (!bursting) primed = false
            return null
        }
        val s = ring[head]
        head = (head + 1) % ring.size
        size--
        return s
    }

    /** The WS relay's microphone: [MicSourceFactory.open] returns a self-paced view of this bridge. */
    val mics: MicSourceFactory = object : MicSourceFactory {
        override fun minBufferBytes(sampleRateHz: Int): Int = sampleRateHz / 10 // never used to size a real recorder
        override fun open(spec: MicSpec): MicOpenResult = MicOpenResult.Opened(PacedMic(spec))
    }

    /** OpenAI WebRTC: replaces each ADM buffer (10 ms of mono PCM16 LE; rate = samples × 100). */
    val rtcFeed: RtcMicFeed = RtcMicFeed { buffer -> fillRtc(buffer) }

    /** Only the ADM's record thread calls [fillRtc]. */
    private var rtcScratch = ShortArray(4_800)

    private fun fillRtc(buffer: ByteBuffer) {
        val bytes = buffer.remaining()
        val n = bytes / 2
        if (n <= 0) return
        if (rtcScratch.size < n) rtcScratch = ShortArray(n)
        val scratch = rtcScratch
        pull(scratch, n, n * 100)
        val order = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val base = buffer.position()
        for (i in 0 until n) buffer.putShort(base + 2 * i, scratch[i])
        buffer.order(order)
    }

    /** A [MicSource] that returns one chunk per chunk-duration of real time (the WS loop's clock). */
    private inner class PacedMic(override val spec: MicSpec) : MicSource {
        @Volatile private var released = false
        private var nextAt = -1L

        override suspend fun read(buffer: ShortArray, offset: Int, length: Int): Int {
            if (released) return MicSource.ERROR_INVALID_OPERATION
            val rate = spec.sampleRateHz
            val chunkMs = length * 1000L / rate
            val now = clock.nowMs()
            if (nextAt < 0 || now - nextAt > 500) nextAt = now // first read, or the loop stalled: resync
            val wait = nextAt + chunkMs - now
            if (wait > 0) delay(wait)
            nextAt += chunkMs
            if (released) return MicSource.ERROR_INVALID_OPERATION
            val tmp = if (offset == 0) buffer else ShortArray(length)
            pull(tmp, length, rate)
            if (offset != 0) tmp.copyInto(buffer, offset, 0, length)
            return length
        }

        override fun release() { released = true }
    }

    private companion object {
        val PREROLL_SAMPLES = (TvVoiceTuning.SAMPLE_RATE_HZ * TvVoiceTuning.LIVE_PREROLL_MS / 1000).toInt()
    }
}

/**
 * Streaming linear-interpolation resampler from [inRateHz] to any output rate (16 kHz voice into
 * the transports' 24 kHz / 48 kHz). Pulls input samples on demand; a missing sample (null) yields
 * the last value faded to silence.
 */
class LinearResampler(private val inRateHz: Int) {
    private var outRateHz = inRateHz
    private var pos = 1.0 // fractional position between [prev] (0) and [cur] (1)
    private var prev: Short = 0
    private var cur: Short = 0
    private var started = false

    fun setOutputRate(rateHz: Int) {
        if (rateHz != outRateHz) outRateHz = rateHz
    }

    fun reset() {
        pos = 1.0; prev = 0; cur = 0; started = false
    }

    fun next(take: () -> Short?): Short {
        val step = inRateHz.toDouble() / outRateHz
        if (!started) {
            val first = take() ?: return 0
            prev = first; cur = first; pos = 1.0; started = true
        }
        while (pos >= 1.0) {
            val s = take()
            if (s == null) {
                // Ran dry: restart cleanly on the next sample (no click from stale interpolation).
                started = false
                return 0
            }
            prev = cur
            cur = s
            pos -= 1.0
        }
        val v = prev + (cur - prev) * pos
        pos += step
        return v.toInt().toShort()
    }
}
