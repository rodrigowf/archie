package com.assistant.core.wakeword.parity

import com.assistant.core.testing.PinsConstant
import com.assistant.core.testing.frame
import com.assistant.core.wakeword.ports.CycleOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure wake-loop policies: RMS gate, re-arm delays, clip helpers (inv04 §3.1, §4.1, §4.2). */
class WakePoliciesParityTest {

    // ── RMS gate ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun effectiveThresholdIs70DividedByGain() {
        assertEquals(70.0, wakeCore.rmsGate(1.0f).effectiveThreshold, 1e-9)
        assertEquals(70.0 / 1.5, wakeCore.rmsGate(1.5f).effectiveThreshold, 1e-6)
        assertEquals(70.0 / 1.3, wakeCore.rmsGate(1.3f).effectiveThreshold, 1e-6)
        assertEquals("gain 0 falls back to the base threshold", 70.0, wakeCore.rmsGate(0f).effectiveThreshold, 1e-9)
        assertEquals(70.0, wakeCore.rmsGate(-1f).effectiveThreshold, 1e-9)
    }

    @Test
    fun gateNeedsActivityHeldFor30ms() {
        val g = wakeCore.rmsGate(1.0f)
        assertFalse(g.onFrame(80.0, 1_000))
        assertFalse(g.onFrame(80.0, 1_029))
        assertTrue(g.onFrame(80.0, 1_030))
    }

    @Test
    fun aQuietFrameResetsTheHold() {
        val g = wakeCore.rmsGate(1.0f)
        assertFalse(g.onFrame(80.0, 1_000))
        assertFalse(g.onFrame(60.0, 1_020))
        assertFalse(g.onFrame(80.0, 1_040))
        assertFalse(g.onFrame(80.0, 1_060))
        assertTrue(g.onFrame(80.0, 1_070))
    }

    /** RS-36 (`c60cd08` vs `2acf57c`): 70 detects normal speech; the 150 % slider gives ≈47 (F22). */
    @Test
    fun rs36_threshold70DetectsNormalSpeech() {
        val g1 = wakeCore.rmsGate(1.0f)
        assertFalse(g1.onFrame(69.9, 1_000)); assertFalse(g1.onFrame(69.9, 1_100))
        g1.reset()
        assertFalse(g1.onFrame(70.0, 2_000)); assertTrue(g1.onFrame(70.0, 2_100))
        val g15 = wakeCore.rmsGate(1.5f)
        assertEquals(46.67, g15.effectiveThreshold, 0.01)
        assertFalse(g15.onFrame(47.0, 3_000)); assertTrue(g15.onFrame(47.0, 3_100))
    }

    // ── re-arm policy ────────────────────────────────────────────────────────────────────────

    @Test
    fun matchCoolsDownThreeSecondsAndResetsMisses() {
        val p = wakeCore.rearmPolicy()
        p.delayAfter(CycleOutcome.SR_NO_MATCH)
        p.delayAfter(CycleOutcome.SR_NO_MATCH)
        assertEquals(3_000L, p.delayAfter(CycleOutcome.MATCHED))
        assertEquals(0, p.consecutiveMisses)
    }

    /** RS-43 (`043340a`): Vosk NoMatch windows re-arm after 500 ms and never accrue backoff. */
    @Test
    fun rs43_noMatchWindowsDoNotAccrueBackoff() {
        val p = wakeCore.rearmPolicy()
        repeat(10) { assertEquals("window ${it + 1}", 500L, p.delayAfter(CycleOutcome.VOSK_NO_MATCH)) }
        assertEquals(0, p.consecutiveMisses)
        p.delayAfter(CycleOutcome.SR_NO_MATCH)
        assertEquals("a Vosk NoMatch also clears SR misses", 500L, p.delayAfter(CycleOutcome.VOSK_NO_MATCH))
        assertEquals(0, p.consecutiveMisses)
    }

    @Test
    fun srMissesBackOffExponentiallyToThirtySeconds() {
        val p = wakeCore.rearmPolicy()
        val delays = (1..8).map { p.delayAfter(CycleOutcome.SR_NO_MATCH) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), delays)
        assertEquals(1_000L, wakeCore.rearmPolicy().delayAfter(CycleOutcome.ERROR))
    }

    /** RS-31 (`d93f7d7`): NO_SPEECH and ERROR_CLIENT re-arm after a flat 1 s without touching the backoff. */
    @Test
    fun rs31_srNoSpeechAndClientErrorRearmFlatOneSecond() {
        val p = wakeCore.rearmPolicy()
        p.delayAfter(CycleOutcome.SR_NO_MATCH)
        assertEquals(1_000L, p.delayAfter(CycleOutcome.NO_SPEECH))
        assertEquals(1_000L, p.delayAfter(CycleOutcome.ERROR_FLAT))
        assertEquals(1, p.consecutiveMisses)
        assertEquals(2_000L, p.delayAfter(CycleOutcome.SR_NO_MATCH))
    }

    @Test
    fun cancelledNeverRearms() = assertNull(wakeCore.rearmPolicy().delayAfter(CycleOutcome.CANCELLED))

    @Test
    fun resetClearsMisses() {
        val p = wakeCore.rearmPolicy()
        repeat(3) { p.delayAfter(CycleOutcome.SR_NO_MATCH) }
        p.reset()
        assertEquals(0, p.consecutiveMisses)
        assertEquals(1_000L, p.delayAfter(CycleOutcome.SR_NO_MATCH))
    }

    // ── clips ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun monitorBufferIsAtLeast3200Bytes() {
        assertEquals(3200, wakeCore.clips.monitorBufferBytes(1280))
        assertEquals(5000, wakeCore.clips.monitorBufferBytes(5000))
    }

    /** Integer division as the old code (`computePreBufferCapacity`), not the ceiling inv04 §10.2 states. */
    @Test
    fun preBufferCapacityIs500msOfReadsRoundedDown() {
        val c = wakeCore.clips
        assertEquals(5, c.preBufferCapacity(1600))
        assertEquals(2, c.preBufferCapacity(3200))
        assertEquals(2, c.preBufferCapacity(3000))
        assertEquals(1, c.preBufferCapacity(6400))
        assertEquals(1, c.preBufferCapacity(9000))
    }

    @Test
    fun trimKeepsTheTrailingTwoSeconds() {
        val c = wakeCore.clips
        val frames = List(10) { frame(6400, it + 1) }
        val trimmed = c.trimToTrailingWindow(frames, 2_000, 16_000)
        assertEquals(5, trimmed.size)
        assertEquals(32_000, trimmed.sumOf { it.size })
        assertEquals(6, trimmed.first()[0].toInt()) // oldest dropped first
    }

    @Test
    fun trimKeepsEverythingWhenShorterAndOvershootsByLessThanOneFrame() {
        val c = wakeCore.clips
        val short = listOf(frame(6400, 1), frame(3200, 1), frame(3200, 1))
        assertEquals(3, c.trimToTrailingWindow(short, 2_000, 16_000).size)
        val mixed = List(6) { frame(6400, 1) } + frame(3200, 1)
        val total = c.trimToTrailingWindow(mixed, 2_000, 16_000).sumOf { it.size }
        assertTrue("kept $total", total in 32_000 until 32_000 + 6400)
    }

    @Test
    @PinsConstant("wake.speech_floor_rms")
    fun speechFloorUsesThePeakFrameRms() {
        val c = wakeCore.clips
        assertFalse(c.hasSpeech(emptyList()))
        assertFalse(c.hasSpeech(listOf(frame(3200, 29), frame(3200, 10))))
        assertTrue(c.hasSpeech(listOf(frame(3200, 5), frame(3200, 30), frame(3200, 5))))
    }

    @Test
    fun seedFloorIsTheQuietestNonZeroPreRollFrame() {
        val c = wakeCore.clips
        assertEquals(40.0, c.seedFloor(listOf(frame(1600, 100), frame(1600, 0), frame(1600, 40), frame(1600, 70))), 1e-9)
        assertEquals(-1.0, c.seedFloor(listOf(frame(1600, 0))), 0.0)
        assertEquals(-1.0, c.seedFloor(emptyList()), 0.0)
    }
}
