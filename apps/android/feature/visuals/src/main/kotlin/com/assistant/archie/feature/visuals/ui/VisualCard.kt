package com.assistant.archie.feature.visuals.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assistant.archie.feature.visuals.CastCapability
import com.assistant.archie.feature.visuals.VisualsDeps
import com.assistant.archie.feature.visuals.relativeTime
import kotlinx.coroutines.launch
import com.assistant.core.design.components.ArchieButton
import com.assistant.core.design.components.ButtonSize
import com.assistant.core.design.components.ButtonStyle
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

/**
 * Inline visual card (mockups tablet, "Visuals inline"; web `VisualCard`): a visual Archie made shows
 * as a card with **Open** and **Show on TV** — the same action as the viewer's top bar. For the
 * conversation (B-04/B-09) to place under a turn that produced a visualization. [onShowOnTv] null =
 * the capability is not Available, so the button is hidden (not disabled).
 */
@Composable
fun VisualCard(
    title: String,
    modified: String?,
    onOpen: () -> Unit,
    onShowOnTv: (() -> Unit)?,
    modifier: Modifier = Modifier,
    status: String? = null,
    casting: Boolean = false,
    now: java.time.Instant? = null,
) {
    val c = ArchieTheme.colors
    val line = status ?: relativeTime(modified, now ?: java.time.Instant.now())?.let { "Visual · updated $it" } ?: "Visual"
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surfaceContainerLow)
            .border(1.dp, c.outlineVariant, RoundedCornerShape(16.dp))
            .padding(12.dp)
            .testTag("visual-card"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChartGlyph()
        Column(Modifier.weight(1f)) {
            Text(title, style = ArchieTheme.typography.bodyLarge.copy(fontWeight = FontWeight.W500), color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = ArchieTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ArchieButton("Open", onOpen, style = ButtonStyle.Outlined, size = ButtonSize.Small)
        if (onShowOnTv != null) {
            ArchieButton("Show on TV", onShowOnTv, Modifier.testTag("show-on-tv"), style = ButtonStyle.Tonal, size = ButtonSize.Small, icon = ArchieIcons.Cast, enabled = !casting)
        }
    }
}

/** The small bar-chart glyph of the mockup (decorative): six bars, the last one in tertiary. */
@Composable
private fun ChartGlyph() {
    val c = ArchieTheme.colors
    val bars = remember { listOf(25f to 22f, 28f to 19f, 22f to 25f, 26f to 21f, 19f to 28f, 14f to 33f) }
    Canvas(
        Modifier
            .size(width = 72.dp, height = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.surfaceContainerLowest),
    ) {
        val sx = size.width / 72f
        val sy = size.height / 56f
        bars.forEachIndexed { i, (y, h) ->
            drawRoundRect(
                color = if (i == bars.lastIndex) c.tertiary else c.primary,
                topLeft = Offset((9f + i * 9f) * sx, y * sy),
                size = Size(6f * sx, h * sy),
                cornerRadius = CornerRadius(2f * sx),
            )
        }
    }
}

/** [VisualCard] wired to the capability and the cast; [onMessage] shows the outcome (snackbar). */
@Composable
fun VisualCard(
    deps: VisualsDeps,
    path: String,
    title: String,
    modified: String?,
    onOpen: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val capability by deps.cast.capability.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var casting by remember { mutableStateOf(false) }
    LaunchedEffect(deps) { deps.cast.ensure() }
    VisualCard(
        title = title,
        modified = modified,
        onOpen = onOpen,
        onShowOnTv = if (capability == CastCapability.Available) {
            {
                casting = true
                scope.launch {
                    val r = deps.cast.cast(path, title)
                    casting = false
                    onMessage(r.message)
                }
            }
        } else {
            null
        },
        modifier = modifier,
        casting = casting,
    )
}
