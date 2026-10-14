package com.assistant.mictest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * VU meter (top bar: RMS fill + peak marker + 3 s peak hold) over a scrolling level history
 * (bottom: one column per audio block, ~10 s). Scale is dBFS from [FLOOR_DB] to 0.
 * Dropouts (blocks of digital silence) are drawn as red columns in the history, clipped blocks
 * as a red top cap, so a flaky contact shows up as gaps/spikes over time.
 */
class MeterView(context: Context) : View(context) {

    private val rmsHistory = FloatArray(HISTORY)
    private val peakHistory = FloatArray(HISTORY)
    private val flags = IntArray(HISTORY)
    private var head = 0
    private var count = 0

    private var rmsDb = FLOOR_DB
    private var peakDb = FLOOR_DB
    private var holdDb = FLOOR_DB
    private var holdAtMs = 0L

    private val bg = Paint().apply { color = Color.rgb(24, 24, 28) }
    private val grid = Paint().apply { color = Color.rgb(60, 60, 68); strokeWidth = 1f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 150, 160) }
    private val fill = Paint()
    private val peak = Paint().apply { color = Color.WHITE; strokeWidth = 4f }
    private val hold = Paint().apply { color = Color.rgb(255, 193, 7); strokeWidth = 4f }
    private val histRms = Paint().apply { color = Color.rgb(76, 175, 80) }
    private val histPeak = Paint().apply { color = Color.rgb(46, 100, 50) }
    private val red = Paint().apply { color = Color.rgb(229, 57, 53) }

    fun reset() {
        head = 0; count = 0
        rmsDb = FLOOR_DB; peakDb = FLOOR_DB; holdDb = FLOOR_DB
        invalidate()
    }

    /** Called on the UI thread once per audio block. */
    fun push(rmsDb: Float, peakDb: Float, dropout: Boolean, clipped: Boolean) {
        this.rmsDb = rmsDb
        this.peakDb = peakDb
        val now = System.currentTimeMillis()
        if (peakDb >= holdDb || now - holdAtMs > HOLD_MS) {
            holdDb = peakDb
            holdAtMs = now
        }
        rmsHistory[head] = rmsDb
        peakHistory[head] = peakDb
        flags[head] = (if (dropout) FLAG_DROPOUT else 0) or (if (clipped) FLAG_CLIP else 0)
        head = (head + 1) % HISTORY
        if (count < HISTORY) count++
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (resources.displayMetrics.density * 220).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density
        label.textSize = 11 * d
        canvas.drawRect(0f, 0f, w, h, bg)

        // --- top bar
        val barTop = 6 * d
        val barH = 44 * d
        val labelsY = barTop + barH + 13 * d
        fill.color = colorFor(rmsDb)
        canvas.drawRect(0f, barTop, x(rmsDb, w), barTop + barH, fill)
        val px = x(peakDb, w)
        canvas.drawLine(px, barTop, px, barTop + barH, peak)
        val hx = x(holdDb, w)
        canvas.drawLine(hx, barTop, hx, barTop + barH, hold)
        for (db in TICKS) {
            val tx = x(db.toFloat(), w)
            canvas.drawLine(tx, barTop + barH, tx, barTop + barH + 4 * d, grid)
            val text = db.toString()
            val tw = label.measureText(text)
            canvas.drawText(text, (tx - tw / 2).coerceIn(0f, w - tw), labelsY, label)
        }

        // --- history (oldest left, newest right)
        val histTop = labelsY + 8 * d
        val histH = h - histTop - 2 * d
        for (db in TICKS) {
            val gy = histTop + histH * (1 - norm(db.toFloat()))
            canvas.drawLine(0f, gy, w, gy, grid)
        }
        val col = w / HISTORY
        for (i in 0 until count) {
            val idx = (head - count + i + HISTORY) % HISTORY
            val left = w - (count - i) * col
            val right = left + col.coerceAtLeast(1f)
            val f = flags[idx]
            if (f and FLAG_DROPOUT != 0) {
                canvas.drawRect(left, histTop, right, histTop + histH, red)
                continue
            }
            val pTop = histTop + histH * (1 - norm(peakHistory[idx]))
            val rTop = histTop + histH * (1 - norm(rmsHistory[idx]))
            canvas.drawRect(left, pTop, right, histTop + histH, histPeak)
            canvas.drawRect(left, rTop, right, histTop + histH, histRms)
            if (f and FLAG_CLIP != 0) canvas.drawRect(left, histTop, right, histTop + 4 * d, red)
        }
        canvas.drawText("last ~10 s  (red column = digital silence, red cap = clipping)", 4 * d, histTop + 12 * d, label)
    }

    private fun norm(db: Float) = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
    private fun x(db: Float, w: Float) = w * norm(db)

    private fun colorFor(db: Float) = when {
        db > -6f -> Color.rgb(229, 57, 53)
        db > -20f -> Color.rgb(255, 193, 7)
        else -> Color.rgb(76, 175, 80)
    }

    companion object {
        const val FLOOR_DB = -90f
        private const val HISTORY = 320 // 320 x 32 ms blocks ≈ 10 s
        private const val HOLD_MS = 3000L
        private const val FLAG_DROPOUT = 1
        private const val FLAG_CLIP = 2
        private val TICKS = intArrayOf(-90, -80, -70, -60, -50, -40, -30, -20, -10, 0)
    }
}
