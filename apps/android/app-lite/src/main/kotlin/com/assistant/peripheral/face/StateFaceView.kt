package com.assistant.peripheral.face

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import com.assistant.peripheral.ui.Glyphs
import com.assistant.peripheral.ui.LitePalette
import kotlin.math.PI
import kotlin.math.cos

/**
 * The big state shape (spec 14 §5.2, mockup §4), one custom View drawn on a Canvas: the ring
 * around the Archie mark, the orb with level bars, the dashed "elsewhere" ring, the red error
 * ring, the amber reconnecting orb.
 *
 * Animation: ONE [ValueAnimator], frames throttled to ≤ 20 fps. It runs only while the model asks
 * for it ([FaceModel.animate]) and the view is shown and attached (screen off → the window is
 * hidden → it stops). The armed idle face never animates.
 */
class StateFaceView(context: Context, private val palette: LitePalette) : View(context) {
    private var model: FaceModel? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    /** Animation clock (ms) — frozen at a fixed phase for screenshots. */
    var fixedPhaseMs: Long? = null
        set(value) {
            field = value
            invalidate()
        }

    private var lastFrameAt = 0L
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val now = SystemClock.uptimeMillis()
            if (now - lastFrameAt >= FRAME_INTERVAL_MS) {
                lastFrameAt = now
                invalidate()
            }
        }
    }

    fun bind(m: FaceModel) {
        val old = model
        model = m
        if (old == null || old.shape != m.shape || old.tone != m.tone || old.glyph != m.glyph || old.level != m.level) invalidate()
        contentDescription = m.word
        updateAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    private fun updateAnimation() {
        val run = model?.animate == true && fixedPhaseMs == null && isAttachedToWindow && isShown && windowVisibility == VISIBLE
        if (run && !animator.isStarted) animator.start() else if (!run && animator.isStarted) animator.cancel()
    }

    /** True while the throttled animator runs (tests / smoke). */
    val isAnimating: Boolean get() = animator.isStarted

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (SIZE_DP * resources.displayMetrics.density).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val m = model ?: return
        val side = minOf(width, height).toFloat()
        val u = side / 100f // the mockup's 100-unit viewBox
        val cx = width / 2f
        val cy = height / 2f
        val t = (fixedPhaseMs ?: SystemClock.uptimeMillis()).toDouble()
        when (m.shape) {
            FaceShape.MARK_RING -> {
                ring(canvas, cx, cy, 42.3f * u, 2f * u, palette.outlineVariant, 1f, dashed = false)
                val half = 23.3f * u
                rect.set(cx - half, cy - half, cx + half, cy + half)
                glyphOrMark(canvas, m, rect)
            }
            FaceShape.DASHED_RING -> {
                ring(canvas, cx, cy, 42.3f * u, 2f * u, palette.outline, 1f, dashed = true)
                val half = 18.7f * u
                rect.set(cx - half, cy - half, cx + half, cy + half)
                glyphOrMark(canvas, m, rect)
            }
            FaceShape.ERROR_RING -> {
                ring(canvas, cx, cy, 42.3f * u, 2f * u, palette.error, 0.6f, dashed = false)
                val half = 18.7f * u
                rect.set(cx - half, cy - half, cx + half, cy + half)
                glyphOrMark(canvas, m, rect)
            }
            FaceShape.ORB -> orb(canvas, m, cx, cy, u, t, reconnect = false)
            FaceShape.RECONNECT_ORB -> orb(canvas, m, cx, cy, u, t, reconnect = true)
        }
    }

    private fun glyphOrMark(canvas: Canvas, m: FaceModel, r: RectF) {
        when (m.glyph) {
            FaceGlyph.NONE -> Unit
            FaceGlyph.MARK -> Glyphs.drawMark(canvas, r, palette.primaryContainer, palette.onPrimaryContainer, palette.tertiary)
            else -> {
                fill.color = palette.onSurfaceVariant
                val data = when (m.glyph) {
                    FaceGlyph.DEVICES -> Glyphs.DEVICES
                    FaceGlyph.CLOUD_OFF -> Glyphs.CLOUD_OFF
                    FaceGlyph.ALERT -> Glyphs.ALERT
                    FaceGlyph.MIC_OFF -> Glyphs.MIC_OFF
                    else -> Glyphs.PAUSE
                }
                Glyphs.draw(canvas, data, r, fill)
            }
        }
    }

    private fun ring(canvas: Canvas, cx: Float, cy: Float, r: Float, w: Float, color: Int, alpha: Float, dashed: Boolean) {
        stroke.color = LitePalette.withAlpha(color, alpha)
        stroke.strokeWidth = w
        stroke.pathEffect = if (dashed) DashPathEffect(floatArrayOf(w * 3f, w * 2f), 0f) else null
        canvas.drawCircle(cx, cy, r, stroke)
        stroke.pathEffect = null
    }

    /** mockup `.orb`: o1 r48 @0.14, o2 r39 @0.28 (pulse 0.84↔1, 2.6 s), o3 r29 solid, 4 level bars. */
    private fun orb(canvas: Canvas, m: FaceModel, cx: Float, cy: Float, u: Float, t: Double, reconnect: Boolean) {
        val color = palette.orb(m.tone)
        val on = palette.onOrb(m.tone)
        val period = if (reconnect) 1_600.0 else 2_600.0
        val pulse1 = if (m.animate) wave(t, period, 0.0) else 1f
        val pulse2 = if (m.animate) wave(t, period, 300.0) else 1f
        val s1 = 0.84f + 0.16f * pulse1
        val s2 = 0.84f + 0.16f * pulse2
        fill.color = LitePalette.withAlpha(color, 0.14f)
        canvas.drawCircle(cx, cy, 48f * u * s1, fill)
        fill.color = LitePalette.withAlpha(color, if (reconnect) 0.26f else 0.28f)
        canvas.drawCircle(cx, cy, 39f * u * s2, fill)
        val coreAlpha = if (reconnect) (if (m.animate) 0.4f + 0.35f * wave(t, period, 0.0) else 0.6f) else 1f
        fill.color = LitePalette.withAlpha(color, coreAlpha)
        canvas.drawCircle(cx, cy, 29f * u, fill)
        // Bars: x = 34.5 / 43 / 51.5 / 60 (w 6), heights 14 / 28 / 22 / 12, centred on y = 50.
        fill.color = on
        val xs = floatArrayOf(34.5f, 43f, 51.5f, 60f)
        val hs = floatArrayOf(14f, 28f, 22f, 12f)
        for (i in 0..3) {
            val scale = when {
                reconnect -> 0.28f
                m.animate -> (0.55f + 0.45f * wave(t, 1_100.0, 150.0 * i)) * (0.45f + 0.55f * m.level)
                else -> 0.55f * (0.45f + 0.55f * m.level)
            }
            val h = hs[i] * scale
            val left = cx + (xs[i] - 50f) * u
            rect.set(left, cy - h / 2f * u, left + 6f * u, cy + h / 2f * u)
            canvas.drawRoundRect(rect, 3f * u, 3f * u, fill)
        }
    }

    /** 0..1..0 over [periodMs], eased (cosine), delayed by [delayMs]. */
    private fun wave(t: Double, periodMs: Double, delayMs: Double): Float {
        val x = ((t - delayMs) % periodMs + periodMs) % periodMs / periodMs
        return (0.5 - 0.5 * cos(2 * PI * x)).toFloat()
    }

    companion object {
        const val SIZE_DP = 200f
        /** ≤ 20 fps (spec 14 §5.2). */
        const val FRAME_INTERVAL_MS = 50L
    }
}
