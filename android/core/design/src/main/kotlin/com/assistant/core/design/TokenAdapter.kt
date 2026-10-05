package com.assistant.core.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.assistant.design.AppShapes
import com.assistant.design.DarkColorScheme
import com.assistant.design.DarkExtendedColors
import com.assistant.design.LightColorScheme
import com.assistant.design.LightExtendedColors
import com.assistant.design.ShadowColor
import com.assistant.design.appTypography
import com.assistant.design.codeTextStyle

/*
 * The single seam between the generated tokens (design/tokens/dist/Tokens.kt, package
 * com.assistant.design) and the rest of the app (spec 14 §7 B-01, risk X14). If the generator
 * renames a symbol, only this file changes. Every other module imports com.assistant.core.design.
 *
 * This is also the only file allowed to contain color literals (NoHexColorLiteralsTest); today it
 * needs none, because every color comes from the tokens.
 */

/** One extended color: color, on-color, container, on-container, translucent tint. */
typealias ColorFamily = com.assistant.design.ColorFamily

/** Semantic (success, warning, info) and tool-category colors. */
typealias ExtendedColors = com.assistant.design.ExtendedColors

/** The 12 tool categories of the tool catalog (spec 14 §3.4). */
typealias ToolCategory = com.assistant.design.ToolCategory

/** 4 dp grid: `Spacing.Space4` = 16 dp. */
typealias Spacing = com.assistant.design.Spacing

/** M3 corner radii. */
typealias Corner = com.assistant.design.Corner

/** M3 elevation levels (shadow dp). */
typealias Elevation = com.assistant.design.Elevation

/** M3 durations (ms) and easing curves. */
typealias Motion = com.assistant.design.Motion

/** M3 state-layer and disabled opacities. */
typealias StateLayer = com.assistant.design.StateLayer

/** Fully rounded corners (M3 `corner-full`). */
val ShapeFull: RoundedCornerShape = com.assistant.design.ShapeFull

/** The extended colors of the current theme. Provided by ArchieTheme. */
val LocalExtendedColors: ProvidableCompositionLocal<ExtendedColors> = com.assistant.design.LocalExtendedColors

internal object TokenAdapter {
    fun colorScheme(dark: Boolean): ColorScheme = if (dark) DarkColorScheme else LightColorScheme

    fun extendedColors(dark: Boolean): ExtendedColors = if (dark) DarkExtendedColors else LightExtendedColors

    fun typography(uiFont: FontFamily): Typography = appTypography(uiFont)

    fun codeStyle(codeFont: FontFamily): TextStyle = codeTextStyle(codeFont)

    val shapes: Shapes = AppShapes

    val shadow: Color = ShadowColor
}
