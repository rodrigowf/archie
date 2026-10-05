package com.assistant.core.design.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.design.theme.ThemeMode

/**
 * One board of the component sheet (mockup §5 `.board`): surface, 20 dp padding, title and caption.
 * The board fills its window, so each size class shows how the components lay out at that width.
 */
@Composable
fun BoardFrame(title: String, caption: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = ArchieTheme.colors
    Column(
        modifier.fillMaxWidth().background(c.surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                title,
                Modifier.weight(1f),
                style = ArchieTheme.typography.titleMedium.copy(letterSpacing = 0.sp),
                color = c.onSurface,
            )
            Text(caption, style = ArchieTheme.typography.bodySmall.copy(letterSpacing = 0.sp), color = c.onSurfaceVariant)
        }
        CompositionLocalProvider(LocalContentColor provides c.onSurface) { content() }
    }
}

/** Wrapping row with the sheet's 12 dp spacing (mockup `.spec`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpecRow(modifier: Modifier = Modifier, gap: Dp = 12.dp, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
        itemVerticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Grid of equal cells at least [minCell] wide (CSS `repeat(auto-fill, minmax(min(100%, X), 1fr))`).
 * Conversation-width components are capped at [maxWidth] (the 840 dp message column).
 */
@Composable
fun AdaptiveGrid(
    items: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    minCell: Dp = 360.dp,
    gap: Dp = 10.dp,
    maxWidth: Dp = Dp.Infinity,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = minOf(maxWidth, this.maxWidth)
        val columns = ((width + gap) / (minCell + gap)).toInt().coerceAtLeast(1)
        Column(Modifier.widthIn(max = width), verticalArrangement = Arrangement.spacedBy(gap)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
                    row.forEach { item -> Column(Modifier.weight(1f)) { item() } }
                    repeat(columns - row.size) { Column(Modifier.weight(1f)) {} }
                }
            }
        }
    }
}

/** Dark and light, for `@PreviewParameter`. */
class ThemeModePreviews : PreviewParameterProvider<ThemeMode> {
    override val values: Sequence<ThemeMode> = sequenceOf(ThemeMode.Dark, ThemeMode.Light)
}

/** Compact (the POCO, 443 dp), Medium (700 dp) and Expanded (1280 dp) previews of one board. */
@androidx.compose.ui.tooling.preview.Preview(name = "Compact", widthDp = 443)
@androidx.compose.ui.tooling.preview.Preview(name = "Medium", widthDp = 700)
@androidx.compose.ui.tooling.preview.Preview(name = "Expanded", widthDp = 1280)
annotation class BoardPreviews
