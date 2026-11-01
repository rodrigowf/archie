package com.assistant.archie.tv.voice

import com.assistant.core.audio.ports.MicOpenResult
import com.assistant.core.audio.ports.MicSourceType
import com.assistant.core.audio.ports.MicSpec
import com.assistant.core.audio.ports.MonotonicClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live call's continuous stream (spec 15 §4.2): remote audio while held, silence otherwise. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveMicBridgeTest {
    private val fixed = MonotonicClock { 0 }

    @Test
    fun silenceUntilThePrerollIsBufferedThenTheVoiceThenSilenceAgain() {
        val b = LiveMicBridge(fixed)
        val out = ShortArray(160)
        b.pull(out, 160, 16_000)
        assertTrue("idle = digital silence", out.all { it == 0.toShort() })
        b.push(ShortArray(1_600) { 1_000 }) // 100 ms < 200 ms pre-roll
        b.pull(out, 160, 16_000)
        assertTrue("still pre-rolling", out.all { it == 0.toShort() })
        b.push(ShortArray(1_600) { 1_000 }) // 200 ms buffered
        b.pull(out, 160, 16_000)
        assertTrue("voice flows", out.drop(2).all { it == 1_000.toShort() })
        b.endOfBurst()
        val rest = ShortArray(3_200)
        b.pull(rest, 3_200, 16_000)
        assertTrue("the tail plays out", rest.take(2_800).all { it == 1_000.toShort() || it == 0.toShort() })
        b.pull(out, 160, 16_000)
        assertTrue("then silence", out.all { it == 0.toShort() })
        assertEquals(0, b.buffered)
    }

    @Test
    fun aShortBurstPlaysOutOnRelease() {
        val b = LiveMicBridge(fixed)
        b.push(ShortArray(1_600) { 500 })
        b.endOfBurst()
        val out = ShortArray(1_600)
        b.pull(out, 1_600, 16_000)
        assertTrue(out.count { it == 500.toShort() } > 1_500)
    }

    @Test
    fun neverLagsMoreThanTheBufferCap() {
        val b = LiveMicBridge(fixed)
        repeat(50) { b.push(ShortArray(1_600) { 1 }) } // 5 s pushed, nothing pulled
        assertEquals((TvVoiceTuning.SAMPLE_RATE_HZ * TvVoiceTuning.LIVE_MAX_BUFFER_MS / 1000).toInt(), b.buffered)
    }

    @Test
    fun resamplesToTheTransportsRate() {
        val b = LiveMicBridge(fixed)
        b.push(ShortArray(3_200) { 2_000 })
        b.endOfBurst()
        val out = ShortArray(4_800)
        b.pull(out, 4_800, 24_000) // 200 ms at 24 kHz
        val voiced = out.count { it in 1_990..2_000 }
        assertTrue("≈ 4 800 voiced samples at 24 kHz, got $voiced", voiced > 4_700)
    }

    @Test
    fun rtcFeedReplacesTheAdmBufferAtItsRate() {
        val b = LiveMicBridge(fixed)
        val buf = ByteBuffer.allocateDirect(960).order(ByteOrder.LITTLE_ENDIAN) // 10 ms at 48 kHz
        repeat(480) { buf.putShort(1_234) }
        buf.flip()
        b.rtcFeed.fill(buf)
        assertEquals("between bursts the ADM's capture is replaced by silence", 0, buf.getShort(100).toInt())
        b.push(ShortArray(3_200) { 900 })
        b.rtcFeed.fill(buf)
        assertEquals(900, buf.getShort(400).toInt())
        assertEquals("position untouched", 0, buf.position())
    }

    @Test
    fun wsMicIsSelfPacedAndAlwaysReturnsAFullChunk() = runTest {
        val clock = MonotonicClock { testScheduler.currentTime }
        val b = LiveMicBridge(clock)
        val mic = (b.mics.open(MicSpec(24_000, MicSourceType.VOICE_RECOGNITION, 0)) as MicOpenResult.Opened).mic
        val chunk = ShortArray(480) // 20 ms at 24 kHz, as WsPcmTransport reads
        var reads = 0
        val job = launch { while (true) { assertEquals(480, mic.read(chunk, 0, 480)); reads++ } }
        testScheduler.advanceTimeBy(1_000)
        job.cancel()
        assertTrue("≈ 50 chunks per second of clock time, got $reads", reads in 49..51)
        mic.release()
        assertEquals(-3, mic.read(chunk, 0, 480))
    }
}
