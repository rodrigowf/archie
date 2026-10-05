package com.assistant.peripheral.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.core.content.ContextCompat
import com.assistant.peripheral.R
import com.assistant.peripheral.face.DotTone
import com.assistant.peripheral.face.FaceTone

/**
 * The token colors the lite app uses, from the generated `archie_dark_*` resources
 * (apps/design-tokens → dist/android, decision E-8). Dark only: the A300M face ignores day/night (D3).
 */
class LitePalette(context: Context) {
    private val ctx = context
    private fun c(id: Int) = ContextCompat.getColor(ctx, id)

    val background = c(R.color.archie_dark_surface_container_lowest)
    val surface = c(R.color.archie_dark_surface)
    val container = c(R.color.archie_dark_surface_container)
    val containerHigh = c(R.color.archie_dark_surface_container_high)
    val containerHighest = c(R.color.archie_dark_surface_container_highest)
    val onSurface = c(R.color.archie_dark_on_surface)
    val onSurfaceVariant = c(R.color.archie_dark_on_surface_variant)
    val outline = c(R.color.archie_dark_outline)
    val outlineVariant = c(R.color.archie_dark_outline_variant)
    val primary = c(R.color.archie_dark_primary)
    val onPrimary = c(R.color.archie_dark_on_primary)
    val primaryContainer = c(R.color.archie_dark_primary_container)
    val onPrimaryContainer = c(R.color.archie_dark_on_primary_container)
    val secondary = c(R.color.archie_dark_secondary)
    val onSecondary = c(R.color.archie_dark_on_secondary)
    val secondaryContainer = c(R.color.archie_dark_secondary_container)
    val onSecondaryContainer = c(R.color.archie_dark_on_secondary_container)
    val tertiary = c(R.color.archie_dark_tertiary)
    val onTertiary = c(R.color.archie_dark_on_tertiary)
    val tertiaryContainer = c(R.color.archie_dark_tertiary_container)
    val onTertiaryContainer = c(R.color.archie_dark_on_tertiary_container)
    val error = c(R.color.archie_dark_error)
    val onError = c(R.color.archie_dark_on_error)
    val errorContainer = c(R.color.archie_dark_error_container)
    val success = c(R.color.archie_dark_success)
    val warning = c(R.color.archie_dark_warning)
    val onWarning = c(R.color.archie_dark_on_warning)

    /** Orb fill and bar color for a tone. */
    fun orb(tone: FaceTone): Int = when (tone) {
        FaceTone.PRIMARY -> primary
        FaceTone.SECONDARY -> secondary
        FaceTone.TERTIARY -> tertiary
        FaceTone.CONTAINER -> primaryContainer
        FaceTone.OUTLINE -> outline
        FaceTone.WARNING -> warning
        FaceTone.ERROR -> error
    }

    fun onOrb(tone: FaceTone): Int = when (tone) {
        FaceTone.PRIMARY -> onPrimary
        FaceTone.SECONDARY -> onSecondary
        FaceTone.TERTIARY -> onTertiary
        FaceTone.CONTAINER -> onPrimaryContainer
        FaceTone.OUTLINE -> background
        FaceTone.WARNING -> onWarning
        FaceTone.ERROR -> onError
    }

    fun dot(tone: DotTone): Int = when (tone) {
        DotTone.SUCCESS -> success
        DotTone.WARNING -> warning
        DotTone.ERROR -> error
        DotTone.OFF -> outline
    }

    companion object {
        fun withAlpha(color: Int, alpha: Float): Int =
            Color.argb((Color.alpha(color) * alpha).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }
}

fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun Context.dpi(v: Float): Int = dp(v).toInt()
