package com.assistant.core.wakeword.parity

import com.assistant.core.testing.PinsConstant
import com.assistant.core.wakeword.ports.TalkVad
import com.assistant.core.wakeword.ports.TalkVadStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Talk-command end-of-utterance VAD (inv04 §3.1 TALK_* rows, §4.1; `4f689a4`, `ae1d958`). Ports the
 * old `AdaptiveVadParityTest` and pins the frame semantics of `captureTalkCommand` exactly
 * (frames every 200 ms, timestamps as the old loop took them).
 */
class TalkVadParityTest {
    private val s = 100_000L

    private fun vad(seed: Double, k: Float = 2.0f): TalkVad = wakeCore.talkVad(s, seed, k)

    /** Feeds `rms(i)` at s + 200·i until a terminal step; returns (frame time, step) of every non-CONTINUE step. */
    private fun run(v: TalkVad, frames: Int, rms: (Int) -> Double): List<Pair<Long, TalkVadStep>> {
        val out = mutableListOf<Pair<Long, TalkVadStep>>()
        for (i in 0 until frames) {
            val now = s + 200L * i
            val step = v.onFrame(rms(i), now)
            if (step != TalkVadStep.CONTINUE) out += (now - s) to step
            if (step == TalkVadStep.END_SILENCE || step == TalkVadStep.END_MAX || step == TalkVadStep.ABORT_NO_ONSET) break
        }
        return out
    }

    // ── adaptive threshold + floor tracker (old AdaptiveVadParityTest) ─────────────────────────

    @Test
    fun thresholdScalesWithFloorAndSensitivity() = assertEquals(340.0, wakeCore.adaptiveVoiceThreshold(170.0, 2.0), 1e-9)

    @Test
    fun thresholdIsClampedToTheAbsoluteMinimumInASilentRoom() = assertEquals(30.0, wakeCore.adaptiveVoiceThreshold(5.0, 2.0), 1e-9)

    @Test
    fun realSpeechIsVoiceAndTheRoomTailIsNotAtMeasuredLevels() {
        val bar = wakeCore.adaptiveVoiceThreshold(170.0, 2.0)
        assertTrue(2600.0 >= bar)
        assertTrue(170.0 < bar)
    }

    @Test
    fun trackerSeedsOnTheFirstReadingWhenUnseeded() = assertEquals(170.0, wakeCore.noiseFloorTracker(-1.0).update(170.0), 1e-9)

    @Test
    @PinsConstant("wake.silence_floor_attack", "wake.silence_floor_release")
    fun trackerAttackAndReleaseMath() {
        val t = wakeCore.noiseFloorTracker(100.0)
        assertEquals(0.30 * 40 + 0.70 * 100, t.update(40.0), 1e-9) // down: attack 0.30
        val f = t.floor
        assertEquals(0.02 * 2600 + 0.98 * f, t.update(2600.0), 1e-9) // up: release 0.02
    }

    @Test
    fun trackerAdaptsDownFast() {
        val t = wakeCore.noiseFloorTracker(2600.0)
        repeat(6) { t.update(170.0) }
        assertTrue("floor ${t.floor}", t.floor < 600.0)
    }

    @Test
    fun trackerAdaptsUpSlowlySoALoudWordDoesNotInflateTheFloor() {
        val t = wakeCore.noiseFloorTracker(170.0)
        t.update(2600.0)
        assertTrue(t.floor < 300.0)
        assertTrue(170.0 < wakeCore.adaptiveVoiceThreshold(t.floor, 2.0))
    }

    // ── frame semantics ───────────────────────────────────────────────────────────────────────

    /** Onset after ≥300 ms sustained voice; end 1000 ms after the last voice frame. */
    @Test
    @PinsConstant("wake.seed_floor_min_preroll")
    fun onsetAfterSustainedVoiceThenEndOnOneSecondOfSilence() {
        val v = vad(seed = 10.0)
        val steps = run(v, 100) { i -> if (i <= 2) 1000.0 else 10.0 }
        assertEquals(listOf(400L to TalkVadStep.ONSET, 1400L to TalkVadStep.END_SILENCE), steps)
        assertTrue(v.onsetFired)
    }

    /** RS-38 (`ae1d958`): no sustained speech within 4000 ms → phantom, no UI. */
    @Test
    fun rs38_phantomTriggerAbortsAtTheOnsetTimeoutWithoutOnset() {
        val v = vad(seed = 10.0)
        assertEquals(listOf(4000L to TalkVadStep.ABORT_NO_ONSET), run(v, 100) { 10.0 })
        assertFalse(v.onsetFired)
    }

    /** A single loud frame (music beat) never sustains, so no recording UI appears. */
    @Test
    fun aSingleSpikeNeverFiresOnset() {
        val v = vad(seed = 10.0)
        assertEquals(listOf(4000L to TalkVadStep.ABORT_NO_ONSET), run(v, 100) { i -> if (i == 3 || i == 9) 3000.0 else 10.0 })
    }

    /** Hard cap: a never-ending utterance is sent at 12000 ms. */
    @Test
    fun captureIsCappedAt12Seconds() {
        val v = vad(seed = 10.0)
        val steps = run(v, 200) { i -> if (i % 4 == 3) 10.0 else 2000.0 }
        assertEquals(listOf(400L to TalkVadStep.ONSET, 12_000L to TalkVadStep.END_MAX), steps)
    }

    /** RS-44 (`4f689a4`): a quiet room whose idle RMS is high (AGC floor ~170) still ends ~1 s after speech. */
    @Test
    fun rs44_talkCaptureEndsInAQuietRoomWithHighIdleRms() {
        val v = vad(seed = 160.0)
        val steps = run(v, 100) { i -> if (i <= 5) 2600.0 else 170.0 }
        assertEquals(listOf(400L to TalkVadStep.ONSET, 2000L to TalkVadStep.END_SILENCE), steps)
    }

    /** RS-38 (`ae1d958`): ambient music (40–106) must not pin the silence timer. */
    @Test
    fun rs38_noisyRoomStillEndsCapture() {
        val v = vad(seed = 80.0)
        val steps = run(v, 100) { i -> if (i <= 5) 2000.0 else listOf(40.0, 106.0, 75.0, 98.0)[i % 4] }
        assertEquals(TalkVadStep.ONSET, steps.first().second)
        assertEquals(TalkVadStep.END_SILENCE, steps.last().second)
        assertTrue("ended at ${steps.last().first}", steps.last().first <= 2_200L)
    }

    /**
     * K is the user's "talk silence sensitivity" (voice iff rms ≥ max(floor × K, 30)); K ≤ 0 falls back
     * to 2.0. With K = 1 a live room (120/400 alternating) keeps counting as voice until the 12 s cap;
     * K = 2 ends 1 s after the speech.
     */
    @Test
    fun sensitivityDecidesWhetherALiveRoomCountsAsSilence() {
        val live = { i: Int -> if (i <= 5) 2000.0 else if (i % 2 == 1) 400.0 else 120.0 }
        assertEquals(listOf(400L to TalkVadStep.ONSET, 12_000L to TalkVadStep.END_MAX), run(vad(100.0, 1.0f), 200, live))
        assertEquals(listOf(400L to TalkVadStep.ONSET, 2_000L to TalkVadStep.END_SILENCE), run(vad(100.0, 2.0f), 200, live))
        assertEquals(listOf(400L to TalkVadStep.ONSET, 2_000L to TalkVadStep.END_SILENCE), run(vad(100.0, 0.0f), 200, live))
    }
}
