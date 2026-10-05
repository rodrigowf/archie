package com.assistant.archie.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.assistant.archie.feature.settings.TextSize

/**
 * Appearance → Text size (IA §7): scales every `sp` in [content] on top of the system font scale.
 * The shell wraps the app in it; reduce motion goes to `ArchieTheme(reduceMotion = …)`.
 */
@Composable
fun ProvideTextSize(size: TextSize, content: @Composable () -> Unit) {
    if (size == TextSize.DEFAULT) return content()
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * size.scale), content = content)
}
