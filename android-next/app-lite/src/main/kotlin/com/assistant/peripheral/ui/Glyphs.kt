package com.assistant.peripheral.ui

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import androidx.core.graphics.PathParser

/**
 * Vector glyphs drawn on a Canvas (no icon font, no images, spec 14 §5.6 "no images"). Path data:
 * Material Icons (Apache 2.0), 24-unit viewport; the Archie mark from the mockup (48-unit).
 */
object Glyphs {
    const val SETTINGS = "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"
    const val CLOUD_OFF = "M19.35,10.04C18.67,6.59 15.64,4 12,4c-1.48,0 -2.85,0.43 -4.01,1.17l1.46,1.46C10.21,6.23 11.08,6 12,6c3.04,0 5.5,2.46 5.5,5.5v0.5H19c1.66,0 3,1.34 3,3c0,1.13 -0.64,2.11 -1.56,2.62l1.45,1.45C23.16,18.16 24,16.68 24,15c0,-2.64 -2.05,-4.78 -4.65,-4.96zM3,5.27l2.75,2.74C2.56,8.15 0,10.77 0,14c0,3.31 2.69,6 6,6h11.73l2,2L21,20.73 4.27,4 3,5.27zM7.73,10l8,8H6c-2.21,0 -4,-1.79 -4,-4s1.79,-4 4,-4h1.73z"
    const val DEVICES = "M4,6h18V4H4c-1.1,0 -2,0.9 -2,2v11H0v3h14v-3H4V6zM23,8h-6c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1V9c0,-0.55 -0.45,-1 -1,-1zM22,17h-4v-7h4v7z"
    const val MIC = "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"
    const val MIC_OFF = "M19,11h-1.7c0,0.74 -0.16,1.43 -0.43,2.05l1.23,1.23c0.56,-0.98 0.9,-2.09 0.9,-3.28zM14.98,11.17c0,-0.06 0.02,-0.11 0.02,-0.17V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v0.18l5.98,5.99zM4.27,3L3,4.27l6.01,6.01V11c0,1.66 1.33,3 2.99,3c0.22,0 0.44,-0.03 0.65,-0.08l1.66,1.66c-0.71,0.33 -1.5,0.52 -2.31,0.52c-2.76,0 -5.3,-2.1 -5.3,-5.1H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c0.91,-0.13 1.77,-0.45 2.54,-0.9L19.73,21 21,19.73 4.27,3z"
    const val ALERT = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-2h2v2zM13,13h-2V7h2v6z"
    const val PAUSE = "M6,19h4V5H6v14zM14,5v14h4V5h-4z"
    const val BACK = "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"

    /** Archie mark (mockup `<symbol id="mark">`, 48-unit): rounded square, arch, dot. */
    const val MARK_ARCH = "M14.5,35.5V23.5a9.5,9.5 0,0 1,19 0v12"

    private val cache = HashMap<String, Path>()

    fun path(data: String): Path = synchronized(cache) { cache.getOrPut(data) { PathParser.createPathFromPathData(data) } }

    /** Draws a 24-unit glyph filled with [paint] into [bounds]. */
    fun draw(canvas: Canvas, data: String, bounds: RectF, paint: Paint, viewport: Float = 24f) {
        val m = Matrix()
        m.setRectToRect(RectF(0f, 0f, viewport, viewport), bounds, Matrix.ScaleToFit.CENTER)
        val p = Path(path(data))
        p.transform(m)
        canvas.drawPath(p, paint)
    }

    /** Draws the Archie mark into a square [bounds]. */
    fun drawMark(canvas: Canvas, bounds: RectF, bg: Int, fg: Int, dot: Int) {
        val s = bounds.width() / 48f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = bg }
        canvas.drawRoundRect(bounds, 15f * s, 15f * s, fill)
        val m = Matrix().apply { setScale(s, s); postTranslate(bounds.left, bounds.top) }
        val arch = Path(path(MARK_ARCH)).apply { transform(m) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = fg; strokeWidth = 4.6f * s; strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(arch, stroke)
        fill.color = dot
        canvas.drawCircle(bounds.left + 24f * s, bounds.top + 27f * s, 3.4f * s, fill)
    }
}

/** A glyph as a Drawable (buttons). */
class GlyphDrawable(private val data: String, color: Int, private val insetFraction: Float = 0.25f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    fun setColor(color: Int) {
        paint.color = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = b.width() * insetFraction
        Glyphs.draw(canvas, data, RectF(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset), paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
