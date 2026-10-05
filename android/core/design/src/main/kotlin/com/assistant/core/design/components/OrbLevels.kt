package com.assistant.core.design.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.exp
import kotlin.math.min
import kotlin.random.Random

/**
 * The level orb's live mapping (web `LevelOrb`): the bars are a small equalizer on a visual level
 * (0..1), each bar answering a little differently, and the two halos grow with the same level
 * instead of pulsing. Pure, so the mapping is unit-tested without Compose.
 */
internal object OrbLevels {
    /** Level sampling period (~15 Hz, the rate the voice host publishes at). */
    const val TICK_MS = 66L

    /** Per-bar response: the tall middle bar answers most, the short outer ones least. */
    private val BarResponse = floatArrayOf(0.7f, 1f, 0.85f, 0.6f)

    /** Halo reach at full level: outer 0.84 → 1.08, inner 0.84 → 1.0 of their radius. */
    private const val OUTER_REACH = 0.24f
    private const val INNER_REACH = 0.16f

    /** The resting pulse's low point; a silent live orb sits here. */
    const val HALO_REST = 0.84f

    /** Bar height scale at silence. */
    const val BAR_REST = 0.3f

    /** Display easing between ticks (ms time constant), so 15 Hz steps glide at the frame rate. */
    private const val EASE_MS = 45f

    /** Fast attack, slow release (bars). */
    fun smoothBars(prev: Float, v: Float): Float = if (v > prev) v else prev * 0.7f + v * 0.3f

    /** Slower both ways (halos): they breathe rather than flicker. */
    fun smoothHalo(prev: Float, v: Float): Float = if (v > prev) prev * 0.5f + v * 0.5f else prev * 0.88f + v * 0.12f

    /** Bar [i]'s height scale for a smoothed level; [shimmer] (0..1) adds a little life per tick. */
    fun barScale(level: Float, i: Int, shimmer: Float): Float {
        val v = min(1f, level.coerceAtLeast(0f) * (0.6f + BarResponse[i] * 0.6f) * (0.85f + 0.3f * shimmer))
        return BAR_REST + (1f - BAR_REST) * v
    }

    /** Halo radius scale for a smoothed level. */
    fun haloScale(level: Float, outer: Boolean): Float =
        HALO_REST + (if (outer) OUTER_REACH else INNER_REACH) * min(1f, level.coerceAtLeast(0f))

    /** Moves [current] toward [target] over [dtMs] (frame-rate independent). */
    fun ease(current: Float, target: Float, dtMs: Long): Float {
        if (dtMs <= 0) return current
        val k = 1f - exp(-dtMs / EASE_MS)
        return current + (target - current) * k
    }
}

/**
 * Live orb state: [tick] samples the level (~15 Hz), [frame] eases the drawn values toward the
 * targets. The drawn values are snapshot state, read only in the draw phase (no recomposition).
 */
internal class OrbMeter(private val shimmer: () -> Float = { Random.nextFloat() }) {
    /** False while the level is null (thinking, reconnecting…): the orb falls back to its pulse. */
    var live by mutableStateOf(false)
        private set

    private var smooth = 0f
    private var ring = 0f
    private val barTargets = FloatArray(4) { OrbLevels.BAR_REST }
    private var haloTarget = 0f
    private val barStates = Array(4) { mutableFloatStateOf(OrbLevels.BAR_REST) }
    private val ringState = mutableFloatStateOf(0f)

    fun bar(i: Int): Float = barStates[i].floatValue
    val outerHalo: Float get() = OrbLevels.haloScale(ringState.floatValue, outer = true)
    val innerHalo: Float get() = OrbLevels.haloScale(ringState.floatValue, outer = false)

    fun tick(level: Float?) {
        if (level == null) {
            if (live) reset()
            return
        }
        val v = level.coerceIn(0f, 1f)
        smooth = OrbLevels.smoothBars(smooth, v)
        ring = OrbLevels.smoothHalo(ring, v)
        for (i in 0 until 4) barTargets[i] = OrbLevels.barScale(smooth, i, shimmer())
        haloTarget = ring
        live = true
    }

    fun frame(dtMs: Long) {
        if (!live) return
        for (i in 0 until 4) barStates[i].floatValue = OrbLevels.ease(barStates[i].floatValue, barTargets[i], dtMs)
        ringState.floatValue = OrbLevels.ease(ringState.floatValue, haloTarget, dtMs)
    }

    private fun reset() {
        live = false
        smooth = 0f
        ring = 0f
        haloTarget = 0f
        for (i in 0 until 4) {
            barTargets[i] = OrbLevels.BAR_REST
            barStates[i].floatValue = OrbLevels.BAR_REST
        }
        ringState.floatValue = 0f
    }
}
