package com.assistant.peripheral.face

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.assistant.peripheral.ui.GlyphDrawable
import com.assistant.peripheral.ui.Glyphs
import com.assistant.peripheral.ui.LitePalette
import com.assistant.peripheral.ui.dpi

/**
 * The full-screen state face (spec 14 §5.2, approved mockup §4), plain Views. Sizes are the
 * mockup's px at 540 wide ÷ 1.5 (hdpi): top row 59 dp, shape 200 dp, word 48 sp ("Reconnecting"
 * 40 sp), sub 16 sp, caption label 13 sp, caption 23 sp; side padding 19 dp.
 */
class FaceScreen(context: Context, private val palette: LitePalette) : LinearLayout(context) {
    var onShapeTap: (FaceAction) -> Unit = {}
    var onSettings: () -> Unit = {}
    var onMute: () -> Unit = {}

    private val dot = View(context)
    private val connLabel = text(15f, palette.onSurfaceVariant)
    private val banner = text(14f, palette.warning).apply { visibility = GONE; maxLines = 2 }
    private val muteButton = roundButton(Glyphs.MIC, "Mute").apply { visibility = GONE }
    private val gearButton = roundButton(Glyphs.SETTINGS, "Settings")
    val shape = StateFaceView(context, palette)
    private val word = text(48f, palette.onSurface).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        maxLines = 1
        letterSpacing = -0.014f
    }
    private val sub = text(16f, palette.onSurfaceVariant).apply {
        gravity = Gravity.CENTER
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.25f)
    }
    private val captions = LinearLayout(context).apply { orientation = VERTICAL }
    private var current: FaceModel? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.background)
        val side = context.dpi(19f)
        setPadding(side, 0, side, side)

        // Status row: connection dot + server name … mute, settings.
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dotSize = context.dpi(11f)
        top.addView(dot, LayoutParams(dotSize, dotSize).apply { rightMargin = context.dpi(8f) })
        top.addView(connLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val btn = context.dpi(48f)
        top.addView(muteButton, LayoutParams(btn, btn).apply { rightMargin = context.dpi(10f) })
        top.addView(gearButton, LayoutParams(btn, btn))
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(59f)))
        addView(banner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Middle: shape, word, hint.
        val mid = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        mid.addView(shape, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(16f) })
        mid.addView(word, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(10f) })
        mid.addView(sub, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(2f) })
        addView(mid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Captions pinned to the bottom.
        addView(View(context), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(captions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        shape.isClickable = true
        shape.setOnClickListener { current?.let { m -> if (m.action != FaceAction.NONE) onShapeTap(m.action) } }
        gearButton.setOnClickListener { onSettings() }
        muteButton.setOnClickListener { onMute() }
    }

    /** The shown model (smoke tests, accessibility). */
    val model: FaceModel? get() = current

    fun bind(m: FaceModel) {
        val prev = current
        current = m
        shape.bind(m)
        if (prev?.word != m.word) {
            word.text = m.word
            word.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (m.word.length > 10) 40f else 48f)
        }
        if (prev?.sub != m.sub) {
            sub.text = m.sub.orEmpty()
            sub.visibility = if (m.sub.isNullOrEmpty()) INVISIBLE else VISIBLE
        }
        (dot.background as? GradientDrawable)?.setColor(palette.dot(m.dot)) ?: run {
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(palette.dot(m.dot)) }
        }
        if (prev?.connLabel != m.connLabel) connLabel.text = m.connLabel
        banner.text = m.reconnectBanner.orEmpty()
        banner.visibility = if (m.reconnectBanner.isNullOrEmpty()) GONE else VISIBLE
        muteButton.visibility = if (m.showMute) VISIBLE else GONE
        muteButton.setImageDrawable(GlyphDrawable(if (m.muted) Glyphs.MIC_OFF else Glyphs.MIC, if (m.muted) palette.error else palette.onSurfaceVariant))
        muteButton.contentDescription = if (m.muted) "Unmute" else "Mute"
        shape.contentDescription = when (m.action) {
            FaceAction.START -> "${m.word}. Tap to talk"
            FaceAction.STOP -> "${m.word}. Tap to end"
            FaceAction.RECONNECT_VOICE -> "${m.word}. Tap to reconnect"
            FaceAction.CONNECT -> "${m.word}. Tap to connect"
            FaceAction.NONE -> m.word
        }
        if (prev?.captions != m.captions) bindCaptions(m.captions)
    }

    private fun bindCaptions(list: List<Caption>) {
        captions.removeAllViews()
        list.forEachIndexed { i, c ->
            val label = text(13f, palette.onSurfaceVariant).apply {
                text = c.label.uppercase()
                letterSpacing = 0.077f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val body = text(23f, if (c.dim) palette.onSurfaceVariant else palette.onSurface).apply {
                text = c.text
                // The user line is short; the assistant answer gets more room (spec 14 §5.2: 3 / 5 lines).
                maxLines = if (c.label == "You") 3 else 5
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.25f)
            }
            captions.addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = context.dpi(12f)
            })
            captions.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(2f) })
        }
    }

    private fun text(sp: Float, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        includeFontPadding = true
    }

    private fun roundButton(glyph: String, description: String) = ImageButton(context).apply {
        contentDescription = description
        setImageDrawable(GlyphDrawable(glyph, palette.onSurfaceVariant))
        scaleType = android.widget.ImageView.ScaleType.FIT_XY
        setPadding(0, 0, 0, 0)
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(palette.container) }
        background = RippleDrawable(ColorStateList.valueOf(LitePalette.withAlpha(palette.onSurface, 0.12f)), bg, null)
    }
}
