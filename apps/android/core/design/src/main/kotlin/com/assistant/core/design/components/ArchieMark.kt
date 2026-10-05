package com.assistant.core.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assistant.core.design.theme.ArchieTheme

/**
 * The Archie mark (mockup `#mark`, 48-unit viewBox): an arch on a rounded tile with a tertiary
 * dot. [bare] drops the tile and draws the arch in primary.
 */
@Composable
fun ArchieMark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    bare: Boolean = false,
    contentDescription: String? = null,
) {
    val tile = ArchieTheme.colors.primaryContainer
    val arch = if (bare) ArchieTheme.colors.primary else ArchieTheme.colors.onPrimaryContainer
    val dot = ArchieTheme.colors.tertiary
    val semantics = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Canvas(modifier.size(size).then(semantics)) {
        val k = this.size.minDimension / 48f
        scale(k, k, pivot = Offset.Zero) {
            if (!bare) drawRoundRect(tile, size = Size(48f, 48f), cornerRadius = CornerRadius(15f, 15f))
            val path = Path().apply {
                moveTo(14.5f, 35.5f)
                lineTo(14.5f, 23.5f)
                arcTo(Rect(14.5f, 14f, 33.5f, 33f), 180f, 180f, false)
                lineTo(33.5f, 35.5f)
            }
            drawPath(path, arch, style = Stroke(width = 4.6f, cap = StrokeCap.Round))
            drawCircle(dot, radius = 3.4f, center = Offset(24f, 27f))
        }
    }
}
