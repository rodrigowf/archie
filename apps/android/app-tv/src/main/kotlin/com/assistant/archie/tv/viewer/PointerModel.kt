package com.assistant.archie.tv.viewer

/** A D-pad direction. */
enum class PointerDir { LEFT, RIGHT, UP, DOWN }

/**
 * The Viewer's virtual cursor (spec 15 §3.5 Pointer mode; mockup 11b). Pure math, JVM-tested:
 *
 * - the cursor starts at the centre of the [width]×[height] px page;
 * - each D-pad press moves it by [stepDp] (× [density]); a held key accelerates: the step grows by
 *   [ACCEL_DP] per repeat up to [MAX_STEP_DP] (about a fifth of the screen per event);
 * - the cursor never leaves the page: a press that pushes against an edge it already sits on
 *   scrolls instead — [Move.scrollX] / [Move.scrollY] wheel notches in that direction (more with
 *   acceleration), which the host turns into a mouse-wheel event at the cursor, so the box under
 *   the cursor scrolls (inner panels too, not only the page).
 */
class PointerModel(width: Float, height: Float, private val density: Float) {
    var width: Float = width
        private set
    var height: Float = height
        private set
    var x: Float = width / 2f
        private set
    var y: Float = height / 2f
        private set

    /** One step's outcome: the new position and the wheel notches to send (+ = right / down). */
    data class Move(val x: Float, val y: Float, val scrollX: Int = 0, val scrollY: Int = 0)

    /** The page was laid out again (zoom, window size): keep the cursor inside, proportionally. */
    fun resize(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        if (width > 0f && height > 0f) {
            x = (x / width * w).coerceIn(0f, w - 1f)
            y = (y / height * h).coerceIn(0f, h - 1f)
        } else {
            x = w / 2f
            y = h / 2f
        }
        width = w
        height = h
    }

    fun center() {
        x = width / 2f
        y = height / 2f
    }

    fun move(dir: PointerDir, repeat: Int): Move {
        val step = stepDp(repeat) * density
        val maxX = (width - 1f).coerceAtLeast(0f)
        val maxY = (height - 1f).coerceAtLeast(0f)
        var sx = 0
        var sy = 0
        when (dir) {
            PointerDir.LEFT -> if (x <= 0f) sx = -notches(repeat) else x = (x - step).coerceAtLeast(0f)
            PointerDir.RIGHT -> if (x >= maxX) sx = notches(repeat) else x = (x + step).coerceAtMost(maxX)
            PointerDir.UP -> if (y <= 0f) sy = -notches(repeat) else y = (y - step).coerceAtLeast(0f)
            PointerDir.DOWN -> if (y >= maxY) sy = notches(repeat) else y = (y + step).coerceAtMost(maxY)
        }
        return Move(x, y, sx, sy)
    }

    companion object {
        const val BASE_STEP_DP = 12f
        const val ACCEL_DP = 6f
        const val MAX_STEP_DP = 96f

        /** dp per event for the [repeat]-th auto-repeat of a held key (0 = the press itself). */
        fun stepDp(repeat: Int): Float = (BASE_STEP_DP + ACCEL_DP * repeat.coerceAtLeast(0)).coerceAtMost(MAX_STEP_DP)

        /** Wheel notches per event when pushing an edge: 1, then up to 3 while held. */
        fun notches(repeat: Int): Int = (1 + repeat.coerceAtLeast(0) / 4).coerceAtMost(3)
    }
}
