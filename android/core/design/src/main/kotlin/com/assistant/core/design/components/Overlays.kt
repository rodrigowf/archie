package com.assistant.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.assistant.core.design.Corner
import com.assistant.core.design.Elevation
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

// ---------------------------------------------------------------- snackbar

/**
 * Snackbar (mockup `.snack`): inverse surface, r8, level-3 shadow, optional action (inverse
 * primary) and dismiss. Every save confirms with one (IA §7): "Saved", or the server's error
 * verbatim + Retry.
 */
@Composable
fun ArchieSnackbar(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
) {
    val c = ArchieTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(Corner.Small),
        color = c.inverseSurface,
        contentColor = c.inverseOnSurface,
        shadowElevation = Elevation.Level3,
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, Modifier.weight(1f).padding(vertical = 10.dp), style = ArchieTheme.typography.bodyMedium)
            if (actionLabel != null) {
                ArchieButton(
                    actionLabel,
                    onAction,
                    style = ButtonStyle.Text,
                    colors = ArchieButtonColors(Color.Transparent, c.inversePrimary),
                )
            }
            if (onDismiss != null) {
                ArchieIconButton(
                    ArchieIcons.Close, "Dismiss", onDismiss, size = 40.dp, iconSize = 20.dp, tint = c.inverseOnSurface,
                )
            }
        }
    }
}

/** M3 SnackbarHost drawing [ArchieSnackbar]s; long messages get a dismiss button. */
@Composable
fun ArchieSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { data: SnackbarData ->
        ArchieSnackbar(
            message = data.visuals.message,
            actionLabel = data.visuals.actionLabel,
            onAction = { data.performAction() },
            onDismiss = if (data.visuals.withDismissAction || data.visuals.duration == SnackbarDuration.Indefinite) {
                { data.dismiss() }
            } else {
                null
            },
        )
    }
}

// ---------------------------------------------------------------- menu

/**
 * Menu panel (mockup `.menu`): surface-container, r16, 6 dp padding, level-2 shadow. Use
 * [ArchieDropdownMenu] for an anchored popup; the panel alone is the static surface.
 */
@Composable
fun ArchieMenuPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.widthIn(min = 220.dp),
        shape = RoundedCornerShape(Corner.Large),
        color = ArchieTheme.colors.surfaceContainer,
        shadowElevation = Elevation.Level2,
    ) {
        Column(Modifier.width(IntrinsicSize.Max).padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

/** Anchored popup menu with the [ArchieMenuPanel] look. */
@Composable
fun ArchieDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 220.dp).padding(horizontal = 6.dp),
        offset = offset,
        shape = RoundedCornerShape(Corner.Large),
        containerColor = ArchieTheme.colors.surfaceContainer,
        tonalElevation = 0.dp,
        shadowElevation = Elevation.Level2,
        content = content,
    )
}

/**
 * Menu item (mockup `.mi`): 44 dp, r10, 20 dp leading icon, optional shortcut hint. [destructive]
 * paints it in error; [current] marks the current choice.
 */
@Composable
fun ArchieMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    shortcut: String? = null,
    destructive: Boolean = false,
    current: Boolean = false,
    enabled: Boolean = true,
) {
    val c = ArchieTheme.colors
    val fg = when {
        destructive -> c.error
        current -> c.onSecondaryContainer
        else -> c.onSurface
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(if (current) Modifier.background(c.secondaryContainer) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) ArchieIcon(icon, null, size = 20.dp, tint = if (destructive) c.error else c.onSurfaceVariant)
        Text(text, Modifier.weight(1f), style = ArchieTheme.typography.bodyMedium.copy(letterSpacing = ArchieTheme.typography.labelLarge.letterSpacing), color = fg, maxLines = 1)
        if (shortcut != null) {
            Spacer(Modifier.width(16.dp))
            Text(shortcut, style = ArchieTheme.typography.bodySmall.copy(letterSpacing = ArchieTheme.typography.labelLarge.letterSpacing), color = c.onSurfaceVariant)
        }
    }
}

/** 1 dp outline-variant separator with 4 dp / 12 dp margins. */
@Composable
fun ArchieMenuSeparator() {
    Box(
        Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(ArchieTheme.colors.outlineVariant),
    )
}

// ---------------------------------------------------------------- dialog

/**
 * The basic dialog surface (mockup `.dlg`): surface-container-high, r28, 24 dp padding, headline
 * small title, body in on-surface-variant, text-button actions at the end.
 */
@Composable
fun ArchieDialogSurface(
    title: String,
    modifier: Modifier = Modifier,
    body: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    val c = ArchieTheme.colors
    Surface(
        modifier = modifier.widthIn(max = 340.dp),
        shape = RoundedCornerShape(Corner.ExtraLarge),
        color = c.surfaceContainerHigh,
        shadowElevation = Elevation.Level3,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = ArchieTheme.typography.headlineSmall, color = c.onSurface)
            if (body != null) {
                CompositionLocalProvider(LocalContentColor provides c.onSurfaceVariant) {
                    androidx.compose.material3.ProvideTextStyle(ArchieTheme.typography.bodyMedium) { body() }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { actions() }
        }
    }
}

/** Confirmation dialog (e.g. "Delete this session?"). [destructive] paints the confirm action in error. */
@Composable
fun ArchieConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        ArchieDialogSurface(title, body = { Text(text) }) {
            ArchieButton(dismissLabel, onDismissRequest, style = ButtonStyle.Text)
            val c = ArchieTheme.colors
            ArchieButton(
                confirmLabel,
                onConfirm,
                style = ButtonStyle.Text,
                colors = if (destructive) ArchieButtonColors(Color.Transparent, c.error) else ArchieButtonDefaults.colors(ButtonStyle.Text),
            )
        }
    }
}
