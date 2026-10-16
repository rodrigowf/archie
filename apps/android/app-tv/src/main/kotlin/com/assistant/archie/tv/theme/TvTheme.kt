package com.assistant.archie.tv.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.core.design.theme.ArchieTextStyles
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.design.theme.JetBrainsMono
import com.assistant.core.design.theme.LocalArchieTextStyles
import com.assistant.core.design.theme.RobotoFlex
import com.assistant.core.design.theme.ThemeMode
import androidx.compose.material3.ColorScheme as M3ColorScheme
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.Typography as M3Typography
import androidx.tv.material3.ColorScheme as TvColorScheme
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Typography as TvTypography
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme

/** Settings → Display → Text size (spec 15 §3.2): scales the whole TV type scale. */
enum class TvTextSize(val scale: Float, val label: String) {
    NORMAL(1f, "Normal"),
    LARGE(1.15f, "Large"),
    EXTRA_LARGE(1.3f, "Extra large"),
}

/** The TV type scale of spec 15 §3.2 (sizes in sp at [TvTextSize.NORMAL]). */
@Immutable
data class TvTypeScale(
    /** 36/44 — screen titles ("Conversations"). */
    val displaySmall: TextStyle,
    /** 26/34 — hero card title. */
    val headlineSmall: TextStyle,
    /** 22/28 — card titles, conversation title. */
    val titleLarge: TextStyle,
    /** 18/24 — row headers. */
    val titleMedium: TextStyle,
    /** 20/30 — chat and memory text (reading). */
    val bodyLarge: TextStyle,
    /** 16/24 — previews, secondary text. */
    val bodyMedium: TextStyle,
    /** 16/20 — buttons, chips, hints. */
    val labelLarge: TextStyle,
    /** 14/18 — metadata (time, provider); the minimum on a TV. */
    val labelMedium: TextStyle,
    /** JetBrains Mono 16/24 — code blocks. */
    val code: TextStyle,
) {
    companion object {
        fun of(scale: Float, ui: FontFamily = RobotoFlex, mono: FontFamily = JetBrainsMono): TvTypeScale {
            fun s(size: Int, line: Int, weight: FontWeight = FontWeight.W400, family: FontFamily = ui) = TextStyle(
                fontFamily = family,
                fontWeight = weight,
                fontSize = (size * scale).sp,
                lineHeight = (line * scale).sp,
            )
            return TvTypeScale(
                displaySmall = s(36, 44, FontWeight.W500),
                headlineSmall = s(26, 34, FontWeight.W500),
                titleLarge = s(22, 28, FontWeight.W500),
                titleMedium = s(18, 24, FontWeight.W500),
                bodyLarge = s(20, 30),
                bodyMedium = s(16, 24),
                labelLarge = s(16, 20, FontWeight.W500),
                labelMedium = s(14, 18, FontWeight.W500),
                code = s(16, 24, family = mono),
            )
        }
    }

    /** M3 slots for the reused `:core:*` / `:feature:*` renderers (markdown, tool cards): TV sizes, never below 14 sp. */
    fun toM3(): M3Typography {
        fun big(t: TextStyle, k: Float) = t.copy(fontSize = t.fontSize * k, lineHeight = t.lineHeight * k)
        return M3Typography(
            displayLarge = big(displaySmall, 1.33f),
            displayMedium = big(displaySmall, 1.17f),
            displaySmall = displaySmall,
            headlineLarge = big(headlineSmall, 1.23f),
            headlineMedium = big(headlineSmall, 1.08f),
            headlineSmall = headlineSmall,
            titleLarge = titleLarge,
            titleMedium = titleMedium,
            titleSmall = labelLarge,
            bodyLarge = bodyLarge,
            bodyMedium = bodyMedium,
            bodySmall = labelMedium.copy(fontWeight = FontWeight.W400),
            labelLarge = labelLarge,
            labelMedium = labelMedium,
            labelSmall = labelMedium,
        )
    }

    fun toTv(): TvTypography {
        val m = toM3()
        return TvTypography(
            displayLarge = m.displayLarge, displayMedium = m.displayMedium, displaySmall = m.displaySmall,
            headlineLarge = m.headlineLarge, headlineMedium = m.headlineMedium, headlineSmall = m.headlineSmall,
            titleLarge = m.titleLarge, titleMedium = m.titleMedium, titleSmall = m.titleSmall,
            bodyLarge = m.bodyLarge, bodyMedium = m.bodyMedium, bodySmall = m.bodySmall,
            labelLarge = m.labelLarge, labelMedium = m.labelMedium, labelSmall = m.labelSmall,
        )
    }
}

/** Focus and layout tokens (spec 15 §1, §3.2). Colors always come from the theme, never literals. */
object TvDimens {
    /** Overscan-safe padding: 48 dp left/right, 27 dp top/bottom → content box ≈ 844×484 dp. */
    val OverscanHorizontal: Dp = 48.dp
    val OverscanVertical: Dp = 27.dp

    /** Focused cards scale to 1.05 with a 3 dp `primary` border and a raised surface. */
    const val FocusedScale: Float = 1.05f
    val FocusBorder: Dp = 3.dp

    val DrawerCollapsed: Dp = 72.dp
    val DrawerExpanded: Dp = 240.dp
    val CardCorner: Dp = 16.dp
    val RowSpacing: Dp = 16.dp
    val ScreenGutter: Dp = 24.dp
}

val LocalTvType = staticCompositionLocalOf { TvTypeScale.of(1f) }

/**
 * Archie TV's theme (spec 15 §3.2): `:core:design`'s [ArchieTheme] in **dark** (colors, shapes,
 * extended colors from apps/design-tokens), with the TV type scale on top. Both the M3 theme (read
 * by the reused renderers) and the tv-material theme (read by tv-material components) carry the
 * same colors and the scaled TV type.
 */
@Composable
fun TvTheme(
    textSize: TvTextSize = TvTextSize.NORMAL,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    ArchieTheme(mode = ThemeMode.Dark, reduceMotion = reduceMotion) {
        val m3 = M3Theme.colorScheme
        val tvColors = remember(m3) { tvColorsOf(m3) }
        val type = remember(textSize) { TvTypeScale.of(textSize.scale) }
        val code = remember(type) { ArchieTextStyles(code = type.code, codeSmall = type.code.copy(fontSize = type.labelMedium.fontSize, lineHeight = scaled(type.labelMedium.lineHeight, 1.1f))) }
        M3Theme(colorScheme = m3, typography = remember(type) { type.toM3() }, shapes = M3Theme.shapes) {
            TvMaterialTheme(colorScheme = tvColors, typography = remember(type) { type.toTv() }) {
                CompositionLocalProvider(LocalTvType provides type, LocalArchieTextStyles provides code, content = content)
            }
        }
    }
}

private fun scaled(t: TextUnit, k: Float): TextUnit = t * k

/** The tokens' M3 dark scheme in tv-material's shape (`border` = outline). */
internal fun tvColorsOf(c: M3ColorScheme): TvColorScheme = tvDarkColorScheme(
    primary = c.primary,
    onPrimary = c.onPrimary,
    primaryContainer = c.primaryContainer,
    onPrimaryContainer = c.onPrimaryContainer,
    inversePrimary = c.inversePrimary,
    secondary = c.secondary,
    onSecondary = c.onSecondary,
    secondaryContainer = c.secondaryContainer,
    onSecondaryContainer = c.onSecondaryContainer,
    tertiary = c.tertiary,
    onTertiary = c.onTertiary,
    tertiaryContainer = c.tertiaryContainer,
    onTertiaryContainer = c.onTertiaryContainer,
    background = c.background,
    onBackground = c.onBackground,
    surface = c.surface,
    onSurface = c.onSurface,
    surfaceVariant = c.surfaceVariant,
    onSurfaceVariant = c.onSurfaceVariant,
    surfaceTint = c.surfaceTint,
    inverseSurface = c.inverseSurface,
    inverseOnSurface = c.inverseOnSurface,
    error = c.error,
    onError = c.onError,
    errorContainer = c.errorContainer,
    onErrorContainer = c.onErrorContainer,
    border = c.outline,
    borderVariant = c.outlineVariant,
    scrim = c.scrim,
)

/** Theme accessors for TV screens. */
object TvTheme {
    val type: TvTypeScale
        @Composable @ReadOnlyComposable get() = LocalTvType.current

    /** The M3 (token) colors, incl. `surfaceContainer*` roles tv-material's scheme does not have. */
    val colors: M3ColorScheme
        @Composable @ReadOnlyComposable get() = M3Theme.colorScheme
}
