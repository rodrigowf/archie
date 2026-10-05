package com.assistant.core.design.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The level orb's live mapping: level → bar scales (equalizer) and halo scales. */
class OrbLevelsTest {

    private fun near(expected: Float, actual: Float, msg: String = "") = assertEquals(msg, expected, actual, 1e-4f)

    @Test
    fun silenceRestsTheBarsAndHalos() {
        for (i in 0 until 4) near(OrbLevels.BAR_REST, OrbLevels.barScale(0f, i, shimmer = 1f))
        near(0.84f, OrbLevels.haloScale(0f, outer = true))
        near(0.84f, OrbLevels.haloScale(0f, outer = false))
    }

    @Test
    fun loudFillsTheBarsAndGrowsTheHalosPastTheirPulse() {
        for (i in 0 until 4) near(1f, OrbLevels.barScale(1f, i, shimmer = 1f))
        near(1.08f, OrbLevels.haloScale(1f, outer = true))
        near(1f, OrbLevels.haloScale(1f, outer = false))
        near(1.08f, OrbLevels.haloScale(5f, outer = true), "clamped")
    }

    @Test
    fun barsAnswerDifferentlyLikeAnEqualizer() {
        val mid = (0 until 4).map { OrbLevels.barScale(0.5f, it, shimmer = 0.5f) }
        assertEquals("all four differ", 4, mid.toSet().size)
        assertTrue("the tall middle bar answers most", mid[1] == mid.max())
        assertTrue("the short outer bar answers least", mid[3] == mid.min())
        assertTrue("monotonic in level", OrbLevels.barScale(0.3f, 1, 0.5f) < OrbLevels.barScale(0.6f, 1, 0.5f))
    }

    @Test
    fun barsAttackFastAndReleaseSlowly() {
        near(0.8f, OrbLevels.smoothBars(0.1f, 0.8f), "attack is immediate")
        near(0.8f * 0.7f, OrbLevels.smoothBars(0.8f, 0f), "release keeps 70 % per tick")
    }

    @Test
    fun halosBreatheSlowerThanTheBars() {
        assertTrue(OrbLevels.smoothHalo(0f, 1f) < OrbLevels.smoothBars(0f, 1f))
        near(0.5f, OrbLevels.smoothHalo(0f, 1f))
        near(0.88f, OrbLevels.smoothHalo(1f, 0f))
    }

    @Test
    fun easingIsFrameRateIndependent() {
        val oneStep = OrbLevels.ease(0f, 1f, 32)
        val twoSteps = OrbLevels.ease(OrbLevels.ease(0f, 1f, 16), 1f, 16)
        near(oneStep, twoSteps)
        near(0.5f, OrbLevels.ease(0.5f, 1f, 0))
        assertTrue(OrbLevels.ease(0f, 1f, 1_000) > 0.99f)
    }

    @Test
    fun meterGoesLiveOnALevelAndFallsBackToThePulseOnNull() {
        val m = OrbMeter(shimmer = { 0.5f })
        assertFalse(m.live)
        m.tick(null)
        assertFalse("null keeps the pulse", m.live)

        m.tick(1f)
        assertTrue(m.live)
        repeat(30) { m.frame(16) }
        near(1f, m.bar(1))
        near(OrbLevels.haloScale(0.5f, outer = true), m.outerHalo, "halo eased to its first smoothed step")

        m.tick(null)
        assertFalse(m.live)
        near(OrbLevels.BAR_REST, m.bar(1))
        near(0.84f, m.outerHalo)
    }

    @Test
    fun meterReleasesOverTicksWhenTheLevelDrops() {
        val m = OrbMeter(shimmer = { 0.5f })
        m.tick(1f)
        repeat(30) { m.frame(16) }
        val loud = m.bar(1)
        m.tick(0f)
        repeat(30) { m.frame(16) }
        val after1 = m.bar(1)
        m.tick(0f)
        repeat(30) { m.frame(16) }
        val after2 = m.bar(1)
        assertTrue("decays, not drops: $loud > $after1 > $after2", loud > after1 && after1 > after2 && after2 > OrbLevels.BAR_REST)
    }
}
