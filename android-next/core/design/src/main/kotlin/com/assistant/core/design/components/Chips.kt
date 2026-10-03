package com.assistant.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.core.design.Corner
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

/**
 * Assist / filter / input chip (mockup `.chip`): 32 dp, r8, 1 dp outline-variant. [selected]
 * fills it with secondary-container (filter chip). [onRemove] adds the trailing ✕ (input chip).
 */
@Composable
fun ArchieChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    selected: Boolean = false,
    onRemove: (() -> Unit)? = null,
) {
    val c = ArchieTheme.colors
    val shape = RoundedCornerShape(Corner.Small)
    val fg = if (selected) c.onSecondaryContainer else c.onSurfaceVariant
    Row(
        modifier
            .height(32.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(c.secondaryContainer) else Modifier.border(1.dp, c.outlineVariant, shape))
            .clickable(role = if (onRemove == null) Role.Button else null, onClick = onClick, indication = ripple(color = fg), interactionSource = null)
            .semantics { this.selected = selected }
            .padding(PaddingValues(start = if (leadingIcon != null) 8.dp else 16.dp, end = if (onRemove != null) 8.dp else 16.dp)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            if (leadingIcon != null) ArchieIcon(leadingIcon, null, size = 18.dp)
            Text(label, style = ArchieTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onRemove != null) {
                ArchieIcon(
                    ArchieIcons.Close,
                    "Remove $label",
                    Modifier.clip(RoundedCornerShape(Corner.ExtraSmall)).clickable(onClick = onRemove),
                    size = 18.dp,
                )
            }
        }
    }
}

/**
 * Suggestion chip on the empty conversation (mockup `.chip.lg`): 40 dp, r12, on-surface label,
 * primary icon.
 */
@Composable
fun SuggestionChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = ArchieTheme.colors
    val shape = RoundedCornerShape(Corner.Medium)
    Row(
        modifier
            .height(40.dp)
            .clip(shape)
            .border(1.dp, c.outlineVariant, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (icon != null) 12.dp else 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) ArchieIcon(icon, null, size = 18.dp, tint = c.primary)
        Text(label, style = ArchieTheme.typography.labelLarge, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Agent provider label (IA §1: "Claude", "Qwen", "Gemini"; mockup `.prov`): 20 dp, r6, 11 sp. */
@Composable
fun ProviderChip(provider: String, modifier: Modifier = Modifier) {
    val c = ArchieTheme.colors
    Box(
        modifier
            .height(20.dp)
            .background(c.secondaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            provider,
            color = c.onSecondaryContainer,
            style = ArchieTheme.typography.labelSmall.copy(letterSpacing = 0.3.sp),
            maxLines = 1,
        )
    }
}

/**
 * Settings scope label (mockup `.scope`): "This device · Pixel 8" or "Archie (server)". Device
 * vs server scope is always visible (IA §7).
 */
@Composable
fun ScopeChip(label: String, icon: ImageVector, modifier: Modifier = Modifier) {
    val c = ArchieTheme.colors
    Row(
        modifier
            .height(28.dp)
            .background(c.surfaceContainerHigh, RoundedCornerShape(14.dp))
            .padding(start = 8.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArchieIcon(icon, null, size = 16.dp, tint = c.onSurfaceVariant)
        Text(label, style = ArchieTheme.typography.labelMedium.copy(letterSpacing = 0.sp), color = c.onSurfaceVariant, maxLines = 1)
    }
}

