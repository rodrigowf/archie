package com.assistant.archie.tv.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.assistant.core.design.components.Spinner
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

/*
 * Small non-focusable pieces shared by Home, Conversations and Conversation (spec 15 §3.5, mockups
 * 3/5/6): the provider pill, session status (working spinner / needs-approval badge / idle dot), and
 * the context ring. Colors from the tokens only.
 */

/** What a session row/card shows as its live state (ST-3 of the apps, reduced to what a TV shows). */
enum class SessionState { WORKING, NEEDS_APPROVAL, IDLE, CONNECTING, OFFLINE, NONE }

/** The harness name on a tonal pill (mockup `.pv`): "Claude", "Codex". */
@Composable
fun ProviderPill(label: String, modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    Box(modifier.background(c.secondaryContainer, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 1.dp)) {
        Text(label, style = TvTheme.type.labelMedium, color = c.onSecondaryContainer, maxLines = 1)
    }
}

/** The amber "Needs approval" badge (mockup `.bdg`). */
@Composable
fun ApprovalBadge(modifier: Modifier = Modifier, label: String = "Needs approval") {
    val w = ArchieTheme.extended.warning
    Row(
        modifier.background(w.color, RoundedCornerShape(50)).padding(start = 6.dp, end = 9.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ArchieIcons.FrontHand, contentDescription = null, tint = w.onColor, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = TvTheme.type.labelMedium, color = w.onColor, maxLines = 1)
    }
}

/** An idle (hollow) / live (filled) status dot (mockup `.dot`). */
@Composable
fun StatusDot(modifier: Modifier = Modifier, filled: Boolean = false, size: Dp = 9.dp) {
    val c = TvTheme.colors
    if (filled) Box(modifier.size(size).background(ArchieTheme.extended.success.color, CircleShape))
    else Box(modifier.size(size).border(2.dp, c.outline, CircleShape))
}

/** The status of a session as one line: spinner + "Working", badge, or dot + "Idle". */
@Composable
fun SessionStatusLine(state: SessionState, modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (state) {
            SessionState.WORKING -> {
                Spinner(size = 15.dp, strokeWidth = 2.5.dp)
                Text("Working", style = TvTheme.type.labelMedium, color = c.primary, maxLines = 1)
            }
            SessionState.NEEDS_APPROVAL -> ApprovalBadge()
            SessionState.CONNECTING -> {
                Spinner(size = 15.dp, strokeWidth = 2.5.dp, color = c.onSurfaceVariant)
                Text("Connecting", style = TvTheme.type.labelMedium, color = c.onSurfaceVariant, maxLines = 1)
            }
            SessionState.OFFLINE -> {
                Box(Modifier.size(9.dp).background(c.error, CircleShape))
                Text("Disconnected", style = TvTheme.type.labelMedium, color = c.onSurfaceVariant, maxLines = 1)
            }
            SessionState.IDLE, SessionState.NONE -> {
                StatusDot()
                Text("Idle", style = TvTheme.type.labelMedium, color = c.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** Context use as a ring + percent (mockup `.ring`). [fraction] null = unknown ("?"). */
@Composable
fun ContextRing(fraction: Float?, modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val track = c.outlineVariant
        val value = c.primary
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.5.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            val f = (fraction ?: 0f).coerceIn(0f, 1f)
            if (f > 0f) drawArc(value, -90f, 360f * f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            fraction?.let { "${(it * 100).toInt().coerceIn(0, 999)}%" } ?: "?",
            style = TvTheme.type.labelMedium,
            color = c.onSurfaceVariant,
        )
    }
}

/** One small static line of key/value metadata, used in preview panes. */
@Immutable
data class MetaChip(val text: String, val mono: Boolean = false)

@Composable
fun MetaChipView(chip: MetaChip, modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    Box(modifier.background(c.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 2.dp)) {
        Text(chip.text, style = if (chip.mono) TvTheme.type.code.copy(fontSize = TvTheme.type.labelMedium.fontSize) else TvTheme.type.labelMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
