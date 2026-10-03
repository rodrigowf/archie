package com.assistant.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.core.design.Corner
import com.assistant.core.design.StateLayer
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.theme.ArchieTheme

/** Icon-button styles (mockup `.ib.*`). [Selected] is a pressed toggle; [Error] is "End". */
enum class IconButtonStyle { Standard, Outlined, Tonal, Filled, Selected, Error }

/**
 * A 48 dp icon button (40 dp with [size] = 40.dp) with a 24 dp icon. [checked] makes it a toggle
 * (announced as selected). [tint] overrides the content color of Standard buttons (snackbars,
 * inline cards).
 */
@Composable
fun ArchieIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: IconButtonStyle = IconButtonStyle.Standard,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
    checked: Boolean? = null,
    tint: Color = Color.Unspecified,
) {
    val c = ArchieTheme.colors
    val (container, content) = when (style) {
        IconButtonStyle.Standard, IconButtonStyle.Outlined ->
            Color.Transparent to (if (tint != Color.Unspecified) tint else c.onSurfaceVariant)
        IconButtonStyle.Tonal -> c.secondaryContainer to c.onSecondaryContainer
        IconButtonStyle.Filled, IconButtonStyle.Selected -> c.primary to c.onPrimary
        IconButtonStyle.Error -> c.errorContainer to c.onErrorContainer
    }
    val disabledContent = c.onSurface.copy(alpha = StateLayer.DisabledContent)
    val bg = when {
        enabled -> container
        style == IconButtonStyle.Filled || style == IconButtonStyle.Selected || style == IconButtonStyle.Tonal ->
            c.onSurface.copy(alpha = StateLayer.DisabledContainer)
        else -> Color.Transparent
    }
    val fg = if (enabled) content else disabledContent
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .then(
                if (style == IconButtonStyle.Outlined) Modifier.border(1.dp, c.outlineVariant, CircleShape) else Modifier,
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = fg),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                this.contentDescription = contentDescription
                if (checked != null) selected = checked
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            ArchieIcon(icon, null, size = iconSize)
        }
    }
}

/** FAB sizes (mockup `.fab.s|m|l`): 40 dp r12, 56 dp r16, 96 dp r28. */
enum class FabSize { Small, Regular, Large }

/** M3 FAB on primary-container with the level-3 shadow. */
@Composable
fun ArchieFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: FabSize = FabSize.Regular,
) {
    val c = ArchieTheme.colors
    val content: @Composable () -> Unit = {
        ArchieIcon(icon, contentDescription, size = if (size == FabSize.Large) 36.dp else 24.dp)
    }
    when (size) {
        FabSize.Small -> SmallFloatingActionButton(
            onClick, modifier, RoundedCornerShape(Corner.Medium), c.primaryContainer, c.onPrimaryContainer, content = content,
        )
        FabSize.Regular -> FloatingActionButton(
            onClick, modifier, RoundedCornerShape(Corner.Large), c.primaryContainer, c.onPrimaryContainer, content = content,
        )
        FabSize.Large -> LargeFloatingActionButton(
            onClick, modifier, RoundedCornerShape(Corner.ExtraLarge), c.primaryContainer, c.onPrimaryContainer, content = content,
        )
    }
}

/** Extended FAB ("＋ New"): 56 dp, r16, 16 dp start / 20 dp end, 16 sp label. */
@Composable
fun ArchieExtendedFab(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ArchieTheme.colors
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(Corner.Large),
        containerColor = c.primaryContainer,
        contentColor = c.onPrimaryContainer,
        elevation = FloatingActionButtonDefaults.elevation(),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ArchieIcon(icon, null)
            Text(text, style = ArchieTheme.typography.titleMedium.copy(fontSize = 16.sp, letterSpacing = 0.1.sp))
        }
    }
}
