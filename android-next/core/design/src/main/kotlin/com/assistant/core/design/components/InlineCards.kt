package com.assistant.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

/** The four inline cards above the composer (IA §6). */
enum class InlineCardKind { Permission, Stall, Error, Ended }

private val LocalInlineCardKind = staticCompositionLocalOf<InlineCardKind?> { null }

private fun defaultIcon(kind: InlineCardKind): ImageVector = when (kind) {
    InlineCardKind.Permission -> ArchieIcons.Shield
    InlineCardKind.Stall -> ArchieIcons.HourglassTop
    InlineCardKind.Error -> ArchieIcons.Error
    InlineCardKind.Ended -> ArchieIcons.Logout
}

/**
 * Inline card (mockup `.icard`): r20 container in the kind's tone, never pinned to an edge.
 * Permission = primary-container, Stall = warning-container, Error = error-container,
 * Ended = surface-container-highest. Layout: icon · title · optional dismiss, the body under the
 * title, actions at the end with an optional left [hint] ("Or type below to give feedback").
 * Use [InlineCardAction] for buttons so they take the kind's colors.
 */
@Composable
fun InlineCard(
    kind: InlineCardKind,
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = defaultIcon(kind),
    onDismiss: (() -> Unit)? = null,
    hint: String? = null,
    body: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = ArchieTheme.colors
    val x = ArchieTheme.extended
    val (bg, fg) = when (kind) {
        InlineCardKind.Permission -> c.primaryContainer to c.onPrimaryContainer
        InlineCardKind.Stall -> x.warning.colorContainer to x.warning.onColorContainer
        InlineCardKind.Error -> c.errorContainer to c.onErrorContainer
        InlineCardKind.Ended -> c.surfaceContainerHighest to c.onSurface
    }
    CompositionLocalProvider(LocalContentColor provides fg, LocalInlineCardKind provides kind) {
        Column(
            modifier
                .fillMaxWidth()
                .background(bg, RoundedCornerShape(20.dp))
                .semantics { if (kind != InlineCardKind.Ended) liveRegion = LiveRegionMode.Polite }
                .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        ) {
            // Mockup grid: [icon | title | dismiss] in a 25 dp row, then the body under the title.
            Row(Modifier.heightIn(min = 25.dp), verticalAlignment = Alignment.Top) {
                ArchieIcon(icon, null, Modifier.padding(top = 1.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    Modifier.weight(1f),
                    style = ArchieTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600),
                )
                if (onDismiss != null) {
                    Box(Modifier.offset(x = 4.dp, y = (-6).dp)) {
                        ArchieIconButton(ArchieIcons.Close, "Dismiss", onDismiss, size = 32.dp, iconSize = 20.dp, tint = fg)
                    }
                }
            }
            if (body != null) {
                val muted = if (kind == InlineCardKind.Ended) c.onSurfaceVariant else fg
                Box(Modifier.padding(start = 36.dp, top = 2.dp)) {
                    CompositionLocalProvider(LocalContentColor provides muted) {
                        ProvideTextStyle(ArchieTheme.typography.bodyMedium.copy(letterSpacing = 0.2.sp)) { body() }
                    }
                }
            }
            if (actions != null || hint != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hint != null) {
                        Text(
                            hint,
                            Modifier.weight(1f).alpha(0.85f),
                            style = ArchieTheme.typography.bodySmall.copy(letterSpacing = 0.sp),
                        )
                    }
                    actions?.invoke(this)
                }
            }
        }
    }
}

/**
 * A button inside an [InlineCard]. [primary] is the main action (Approve, Interrupt, Retry,
 * Continue in new session); the secondary one is outlined (permission) or text (others).
 */
@Composable
fun InlineCardAction(text: String, onClick: () -> Unit, primary: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = ArchieTheme.colors
    val fg = LocalContentColor.current
    when (LocalInlineCardKind.current ?: InlineCardKind.Ended) {
        InlineCardKind.Permission ->
            if (primary) ArchieButton(text, onClick, modifier, ButtonStyle.Filled, icon = icon)
            else ArchieButton(text, onClick, modifier, ButtonStyle.Outlined, icon = icon, colors = ArchieButtonColors(Color.Transparent, fg, c.outline))
        InlineCardKind.Stall ->
            if (primary) ArchieButton(text, onClick, modifier, ButtonStyle.Warning, icon = icon)
            else ArchieButton(text, onClick, modifier, ButtonStyle.Text, icon = icon, colors = ArchieButtonColors(Color.Transparent, fg))
        InlineCardKind.Error ->
            if (primary) ArchieButton(text, onClick, modifier, ButtonStyle.Danger, icon = icon)
            else ArchieButton(text, onClick, modifier, ButtonStyle.Text, icon = icon, colors = ArchieButtonColors(Color.Transparent, fg))
        InlineCardKind.Ended ->
            if (primary) ArchieButton(text, onClick, modifier, ButtonStyle.Tonal, icon = icon)
            else ArchieButton(text, onClick, modifier, ButtonStyle.Text, icon = icon)
    }
}

/** Status pill in the message list ("Connection lost at 21:14:32"; mockup `.sysline`). */
@Composable
fun SystemLine(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = ArchieTheme.colors
    Row(
        modifier
            .background(c.surfaceContainer, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) ArchieIcon(icon, null, size = 16.dp, tint = c.onSurfaceVariant)
        Text(
            text,
            style = ArchieTheme.typography.labelMedium.copy(letterSpacing = 0.3.sp, fontFeatureSettings = "tnum"),
            color = c.onSurfaceVariant,
        )
    }
}
