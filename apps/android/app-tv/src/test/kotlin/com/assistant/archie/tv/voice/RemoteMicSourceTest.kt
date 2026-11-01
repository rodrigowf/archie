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

        /**
         * The button is held for [ms]: one 20 ms frame per 20 ms. [strict] = the reader must keep
         * reading throughout; otherwise it stops when the source tells the reader to exit.
         */
        fun hold(ms: Long, strict: Boolean = true) {
            var t = 0L
            while (t < ms) {
                val reading = source.step(generation, frame, frame.size)
                if (strict) assertTrue(reading) else if (!reading) return
                clock.now += 20
                t += 20
                source.tick()
            }
        }

        /** The reader generation of the current recorder (1 after the first [arm]). */
        var generation = 1

        /** Arms, then waits out the arm-time window (on the AFTKM nothing arrives until a press). */
        fun arm() {
            source.start()
            idle(TvVoiceTuning.PROBE_START_MS)
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
        r.arm()
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
        r.arm()
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
        r.arm()
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
        r.arm()
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
        r.arm()
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
        r.arm()
        r.hold(600)
        assertFalse(r.source.step(1, ShortArray(1), -3))
        assertEquals(RemoteMicState.UNAVAILABLE, r.source.state.value)
        assertEquals(1, r.ends().size)
    }

    // ── A1: devices where read() never blocks (phones, other TV boxes) ──

    @Test
    fun continuousFramesFromArmTimeMarkTheSourceUnsupportedAndSendNothing() {
        val r = Rig()
        r.source.start()
        r.hold(TvVoiceTuning.PROBE_MS + 100, strict = false) // frames from the first read on, never a gap
        assertEquals(RemoteMicState.UNSUPPORTED, r.source.state.value)
        assertTrue("nothing of the stream reached the router", r.events.isEmpty())
        assertFalse("disarmed: the reader exits", r.source.step(1, ShortArray(320), 320))
        r.idle(1_000)
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun continuousStreamIsNeverAutoSentAsACappedBurst() {
        val r = Rig()
        r.source.start()
        // A stream that keeps coming even after the probe would have been a capped 60 s burst.
        var t = 0L
        while (t < 61_000 && r.source.step(1, ShortArray(320) { 3_000 }, 320)) {
            r.clock.now += 20
            t += 20
            r.source.tick()
        }
        assertTrue(r.ends().none { it.capped })
        assertTrue(r.starts().isEmpty())
        assertEquals(RemoteMicState.UNSUPPORTED, r.source.state.value)
    }

    @Test
    fun unsupportedIsProbedAgainWhenTheAppReturns() {
        val r = Rig()
        r.source.start()
        r.hold(TvVoiceTuning.PROBE_MS + 100, strict = false)
        assertEquals(RemoteMicState.UNSUPPORTED, r.source.state.value)
        r.source.stop()
        r.source.start()
        assertEquals(2, r.opened)
        assertEquals(RemoteMicState.READY, r.source.state.value)
    }

    @Test
    fun aPressHeldAtArmTimeIsDeliveredWhenItPauses() {
        // Fire TV: the user happened to hold the button while the app came to the front.
        val r = Rig()
        r.source.start()
        r.hold(1_000)
        assertTrue("held back while the probe runs", r.events.isEmpty())
        r.idle(400)
        assertEquals(1, r.starts().size)
        assertEquals(1_000, r.ends().single().durationMs)
        assertEquals(50, r.frames().size)
        assertEquals(RemoteMicState.READY, r.source.state.value)
        r.hold(500) // later presses go straight through
        assertEquals(2, r.starts().size)
    }

    @Test
    fun fireTvPressesAfterArmingAreUnchanged() {
        // AFTKM: nothing arrives until the button is held, so the probe never holds anything back.
        val r = Rig()
        r.source.start()
        r.idle(TvVoiceTuning.PROBE_START_MS)
        r.hold(3_000) // a long press that spans the end of the probe window
        assertEquals("delivered live, not held", 1, r.starts().size)
        assertEquals(150, r.frames().size)
        r.idle(400)
        assertEquals(3_000, r.ends().single().durationMs)
        assertEquals(RemoteMicState.READY, r.source.state.value)
        assertFalse(r.recorder.stopped)
    }

    @Test
    fun stopDuringAHeldBurstDropsIt() {
        val r = Rig()
        r.source.start()
        r.hold(600)
        r.source.stop()
        assertTrue("nothing was delivered, so no End either", r.events.isEmpty())
        assertEquals(RemoteMicState.OFF, r.source.state.value)
    }

    // ── A6: a queued re-arm after stop() ──

    @Test
    fun queuedRetryDoesNotReArmAfterStop() {
        val r = Rig(opens = false)
        r.source.start() // fails, a retry is queued
        r.source.stop() // the app went to the background before the retry fired
        r.source.startIfWanted() // the retry fires
        assertEquals(1, r.opened)
        assertEquals(RemoteMicState.OFF, r.source.state.value)

        r.source.start()
        r.source.startIfWanted() // while wanted, the retry re-opens
        assertEquals(3, r.opened)
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
