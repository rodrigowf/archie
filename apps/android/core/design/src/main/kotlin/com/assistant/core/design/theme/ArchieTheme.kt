package com.assistant.core.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.assistant.core.design.ExtendedColors
import com.assistant.core.design.LocalExtendedColors
import com.assistant.core.design.R
import com.assistant.core.design.TokenAdapter

/** Appearance → Theme (IA §7). Dark is the default; there is no dynamic color (D3). */
enum class ThemeMode { System, Dark, Light }

/** Roboto Flex (static 400/500/600 instances of the variable font, wght axis only, as on the web). */
val RobotoFlex: FontFamily = FontFamily(
    Font(R.font.roboto_flex_regular, FontWeight.W400),
    Font(R.font.roboto_flex_medium, FontWeight.W500),
    Font(R.font.roboto_flex_semibold, FontWeight.W600),
)

/** JetBrains Mono for code, tool output and summaries. */
val JetBrainsMono: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.W400),
    Font(R.font.jetbrains_mono_medium, FontWeight.W500),
)

/** Text styles the M3 type scale has no slot for. All derive from the tokens' code style. */
data class ArchieTextStyles(
    /** tokens `code` (13/20): code blocks. */
    val code: TextStyle,
    /** 12.5/19: tool summaries and tool output (mockup `.tc-sum`, `.tc-o`). */
    val codeSmall: TextStyle,
)

val LocalArchieTextStyles = staticCompositionLocalOf {
    ArchieTextStyles(code = TextStyle.Default, codeSmall = TextStyle.Default)
}

/** True while the dark scheme is active (some drawings, like the voice orb, read it). */
val LocalArchieDark = staticCompositionLocalOf { true }

/**
 * Appearance → Reduce motion (IA §7). Components with infinite animations (spinner, voice orb,
 * caret) draw a still frame when true. Screenshot tests set it so goldens are deterministic.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * The Archie theme: M3 color scheme, extended colors, type scale and shapes, all from
 * apps/design-tokens through [TokenAdapter]. Main app only (the lite app uses plain Views).
 */
@Composable
fun ArchieTheme(
    mode: ThemeMode = ThemeMode.Dark,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val textStyles = remember {
        val code = TokenAdapter.codeStyle(JetBrainsMono)
        ArchieTextStyles(code = code, codeSmall = code.copy(fontSize = 12.5.sp, lineHeight = 19.sp))
    }
    val typography = remember { TokenAdapter.typography(RobotoFlex) }
    CompositionLocalProvider(
        LocalExtendedColors provides TokenAdapter.extendedColors(dark),
        LocalArchieTextStyles provides textStyles,
        LocalArchieDark provides dark,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = TokenAdapter.colorScheme(dark),
            typography = typography,
            shapes = TokenAdapter.shapes,
            content = content,
        )
    }
}

/** Shorthand accessors: `ArchieTheme.colors.primary`, `ArchieTheme.extended.warning.color`. */
object ArchieTheme {
    val colors: ColorScheme
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme

    val extended: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current

    val typography: Typography
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography

    val text: ArchieTextStyles
        @Composable @ReadOnlyComposable get() = LocalArchieTextStyles.current

    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalArchieDark.current

    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current

    /** M3 shadow role. */
    val shadow: Color
        get() = TokenAdapter.shadow
}
