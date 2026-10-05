package com.assistant.core.design.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

/** Dot colors (mockup `.dot`, `.dot.off|err|warn`). */
enum class DotTone { Success, Off, Error, Warning }

/** An 8 dp status dot. Success = open and idle / connected. */
@Composable
fun StatusDot(modifier: Modifier = Modifier, tone: DotTone = DotTone.Success, size: Dp = 8.dp) {
    val color = when (tone) {
        DotTone.Success -> ArchieTheme.extended.success.color
        DotTone.Off -> ArchieTheme.colors.outline
        DotTone.Error -> ArchieTheme.colors.error
        DotTone.Warning -> ArchieTheme.extended.warning.color
    }
    Box(modifier.size(size).background(color, CircleShape))
}

/**
 * The indeterminate spinner (mockup `.spin`): a 2 dp ring with one quarter open, one turn per
 * 0.9 s. Still under reduce motion.
 */
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    color: Color = ArchieTheme.colors.primary,
    strokeWidth: Dp = 2.dp,
) {
    val angle = if (ArchieTheme.reduceMotion) {
        0f
    } else {
        val t = rememberInfiniteTransition(label = "spinner")
        val a by t.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "spinner-angle",
        )
        a
    }
    Canvas(modifier.size(size).semantics { contentDescription = "Working" }) {
        val w = strokeWidth.toPx()
        rotate(angle) {
            // A CSS border ring with a transparent right side: the visible arc runs from the
            // bottom-right diagonal round to the top-right diagonal.
            drawArc(
                color = color,
                startAngle = 45f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w),
                style = Stroke(width = w),
            )
        }
    }
}

/** Session/tab live state (IA §3): idle dot, working spinner, needs-you hand, disconnected warning. */
enum class LiveStatus { Idle, Working, NeedsYou, Disconnected, Off }

/** The glyph for a [LiveStatus], sized to sit inline with 12–14 sp text. */
@Composable
fun StatusIndicator(status: LiveStatus, modifier: Modifier = Modifier) {
    when (status) {
        LiveStatus.Idle -> StatusDot(modifier)
        LiveStatus.Off -> StatusDot(modifier, DotTone.Off)
        LiveStatus.Working -> Spinner(modifier)
        LiveStatus.NeedsYou -> ArchieIcon(
            ArchieIcons.FrontHand, "Needs you", modifier, size = 18.dp, tint = ArchieTheme.extended.warning.color,
        )
        LiveStatus.Disconnected -> ArchieIcon(
            ArchieIcons.Warning, "Disconnected", modifier, size = 18.dp, tint = ArchieTheme.extended.warning.color,
        )
    }
}

/** A status glyph with its text alternative shown (mockup chips board, bottom row). */
@Composable
fun StatusLabel(status: LiveStatus, label: String, modifier: Modifier = Modifier, color: Color = ArchieTheme.colors.onSurfaceVariant) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusIndicator(status)
        Text(label, style = ArchieTheme.typography.bodyMedium, color = color)
    }
}
