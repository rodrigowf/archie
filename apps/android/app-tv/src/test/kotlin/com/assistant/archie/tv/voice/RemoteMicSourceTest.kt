package com.assistant.archie.tv.voice

import com.assistant.core.audio.ports.MonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Burst detection of the remote's microphone (spec 15 §4.1) with a fake recorder and a fake clock:
 * frames arrive only while the button is "held" (as on the AFTKM, `read()` blocks otherwise), the
 * watchdog [RemoteMicSource.tick] measures the gap.
 */
class RemoteMicSourceTest {
    private class Clock(var now: Long = 1_000) : MonotonicClock {
        override fun nowMs() = now
    }

    private class FakeRecorder : RemoteRecorder {
        var stopped = false
        var released = false
        override fun read(buffer: ShortArray) = error("the test drives step()")
        override fun stop() { stopped = true }
        override fun release() { released = true }
    }

    private class Rig(permission: Boolean = true, opens: Boolean = true) {
        val clock = Clock()
        val events = mutableListOf<MicBurst>()
        val recorder = FakeRecorder()
        var opened = 0
        val source = RemoteMicSource(
            recorders = { opened++; if (opens) recorder else null },
            clock = clock,
            hasPermission = { permission },
            sink = { events += it },
            log = {},
            timers = null,
            startThread = {},
        )
        private val frame = ShortArray(TvVoiceTuning.READ_SAMPLES) { 3_000 }

        /** The button is held for [ms]: one 20 ms frame per 20 ms. */
        fun hold(ms: Long) {
            var t = 0L
            while (t < ms) {
                assertTrue(source.step(1, frame, frame.size))
                clock.now += 20
                t += 20
                source.tick()
            }
        }

        /** The button is up for [ms]: no frames, the watchdog ticks every 50 ms. */
        fun idle(ms: Long) {
            var t = 0L
            while (t < ms) {
                clock.now += 50
                t += 50
                source.tick()
            }
        }

        fun starts() = events.filterIsInstance<MicBurst.Start>()
        fun ends() = events.filterIsInstance<MicBurst.End>()
        fun frames() = events.filterIsInstance<MicBurst.Frame>()
    }

    @Test
    fun armsOnlyWithPermission() {
        val denied = Rig(permission = false)
        denied.source.start()
        assertEquals(RemoteMicState.NO_PERMISSION, denied.source.state.value)
        assertEquals(0, denied.opened)

        val r = Rig()
        r.source.start()
        r.source.start() // idempotent: one recorder
        assertEquals(RemoteMicState.READY, r.source.state.value)
        assertEquals(1, r.opened)
    }

    @Test
    fun unavailableRecorderIsReported() {
        val r = Rig(opens = false)
        r.source.start()
        assertEquals(RemoteMicState.UNAVAILABLE, r.source.state.value)
    }

    @Test
    fun burstStartsOnTheFirstFrameAndEndsAfter350msWithoutFrames() {
        val r = Rig()
        r.source.start()
        val t0 = r.clock.now
        r.hold(1_000)
        assertEquals(1, r.starts().size)
        assertEquals(t0, r.starts().single().atMs)
        assertTrue("no End while frames flow", r.ends().isEmpty())

        val released = r.clock.now
        r.idle(300)
        assertTrue("still open 300 ms after the last frame", r.ends().isEmpty())
        r.idle(100)
        val end = r.ends().single()
        assertTrue("ended ≥ 350 ms after the last frame", r.clock.now - released >= TvVoiceTuning.BURST_END_GAP_MS)
        assertEquals(1_000, end.durationMs)
        assertFalse(end.tooShort)
        assertEquals(50, r.frames().size)
    }

    @Test
    fun burstsShorterThan250msAreMarkedTooShort() {
        val r = Rig()
        r.source.start()
        r.hold(200)
        r.idle(400)
        val end = r.ends().single()
        assertEquals(200, end.durationMs)
        assertTrue(end.tooShort)

        r.hold(260)
        r.idle(400)
        assertFalse("260 ms is a real burst", r.ends().last().tooShort)
    }

    @Test
    fun burstIsCappedAt60sAndTheRestOfTheHoldIsIgnored() {
        val r = Rig()
        r.source.start()
        r.hold(61_000)
        val end = r.ends().single()
        assertTrue(end.capped)
        assertEquals(TvVoiceTuning.MAX_BURST_MS, end.durationMs)
        val samples = r.frames().sumOf { it.pcm.size }
        assertEquals((TvVoiceTuning.MAX_BURST_MS * TvVoiceTuning.SAMPLE_RATE_HZ / 1000).toInt(), samples)
        r.hold(2_000) // still held: no new burst
        assertEquals(1, r.starts().size)
        r.idle(400)
        r.hold(500) // released and pressed again: a new burst
        assertEquals(2, r.starts().size)
    }

    @Test
    fun aLateFrameAfterTheGapStartsANewBurstEvenWithoutATick() {
        val r = Rig()
        r.source.start()
        r.hold(400)
        r.clock.now += 600 // the watchdog never ran
        r.hold(400)
        assertEquals(2, r.starts().size)
        assertEquals(1, r.ends().size)
        assertEquals(400, r.ends().single().durationMs)
    }

    @Test
    fun stopEndsTheBurstAndUnblocksTheReader() {
        val r = Rig()
        r.source.start()
        r.hold(600)
        r.source.stop()
        assertEquals(1, r.ends().size)
        assertTrue(r.recorder.stopped)
        assertFalse("a stale reader exits", r.source.step(1, ShortArray(1), 1))
        assertEquals(RemoteMicState.OFF, r.source.state.value)
    }

    @Test
    fun readErrorDropsTheRecorderAndEndsTheBurst() {
        val r = Rig()
        r.source.start()
        r.hold(600)
        assertFalse(r.source.step(1, ShortArray(1), -3))
        assertEquals(RemoteMicState.UNAVAILABLE, r.source.state.value)
        assertEquals(1, r.ends().size)
    }

    @Test
    fun levelsFollowTheSignal() {
        assertEquals(0f, BurstDetector.level(ShortArray(320)), 0f)
        val loud = BurstDetector.level(ShortArray(320) { if (it % 2 == 0) 16_000 else -16_000 })
        val quiet = BurstDetector.level(ShortArray(320) { if (it % 2 == 0) 300 else -300 })
        assertTrue("loud ($loud) > quiet ($quiet)", loud > quiet)
        assertTrue(loud in 0.8f..1f)
    }
}
