package com.assistant.archie.feature.sessions.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.assistant.archie.feature.sessions.ActionState
import com.assistant.archie.feature.sessions.SessionMenuModel
import com.assistant.archie.feature.sessions.SessionTarget
import com.assistant.archie.feature.sessions.SessionsIntent
import com.assistant.core.data.WorkspaceItem
import com.assistant.core.design.components.ArchieDropdownMenu
import com.assistant.core.design.components.ArchieIconButton
import com.assistant.core.design.components.ArchieMenuSeparator
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

/**
 * The session ⋮ menu (IA §3, mockups "Menu and dialog"; web `SessionMenu`): Rename, Session
 * settings, Compact context, Fork, — Close, Delete. Unavailable items stay visible and say why
 * (ID-3, fixes "rename / fork / session config before the first turn silently do nothing").
 * Documents (memory, visual) only offer Close.
 */
@Composable
fun SessionMenuButton(
    item: WorkspaceItem?,
    onIntent: (SessionsIntent) -> Unit,
    onSessionSettings: (String) -> Unit,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Box(modifier) {
        ArchieIconButton(ArchieIcons.MoreVert, "Session menu", { open = true }, enabled = item != null)
        ArchieDropdownMenu(open && item != null, onDismissRequest = { open = false }) {
            if (item != null) SessionMenuItems(item, onIntent, onSessionSettings) { open = false }
        }
    }
}

/** The menu's items (also drawn statically for goldens). */
@Composable
fun ColumnScope.SessionMenuItems(
    item: WorkspaceItem,
    onIntent: (SessionsIntent) -> Unit,
    onSessionSettings: (String) -> Unit,
    onDone: () -> Unit,
) {
    val m = SessionMenuModel.of(item)
    val target = SessionTarget.of(item)
    val run: (() -> Unit) -> () -> Unit = { f -> { onDone(); f() } }
    if (m.conversation) {
        ReasonMenuItem("Rename", ArchieIcons.Edit, m.rename, run { onIntent(SessionsIntent.RequestRename(target)) }, shortcut = "F2")
        ReasonMenuItem("Session settings", ArchieIcons.Tune, m.settings, run { item.localId?.let(onSessionSettings) })
        ReasonMenuItem("Compact context", ArchieIcons.Compress, m.compact, run { onIntent(SessionsIntent.Compact(item.key)) })
        ReasonMenuItem("Fork", ArchieIcons.CallSplit, m.fork, run { onIntent(SessionsIntent.RequestFork(target)) })
        ArchieMenuSeparator()
    }
    ReasonMenuItem("Close", ArchieIcons.Close, ActionState.OK, run { onIntent(SessionsIntent.RequestClose(item)) })
    if (m.conversation) {
        ReasonMenuItem("Delete", ArchieIcons.Delete, m.delete, run { onIntent(SessionsIntent.RequestDelete(target)) }, destructive = true)
    }
}

/**
 * Menu item (mockup `.mi`) with a second line saying why it is unavailable. A disabled item is
 * dimmed and announced as unavailable with its reason.
 */
@Composable
internal fun ReasonMenuItem(
    text: String,
    icon: ImageVector,
    state: ActionState,
    onClick: () -> Unit,
    shortcut: String? = null,
    destructive: Boolean = false,
) {
    val c = ArchieTheme.colors
    val fg = if (destructive) c.error else c.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = state.enabled, role = Role.Button, onClick = onClick)
            .semantics {
                if (!state.enabled) {
                    disabled()
                    state.reason?.let { stateDescription = it }
                }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .alpha(if (state.enabled) 1f else 0.38f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArchieIcon(icon, null, size = 20.dp, tint = if (destructive) c.error else c.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(text, style = ArchieTheme.typography.bodyMedium.copy(letterSpacing = ArchieTheme.typography.labelLarge.letterSpacing), color = fg, maxLines = 1)
            if (!state.enabled && state.reason != null) {
                Text(state.reason, style = ArchieTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
            }
        }
        if (shortcut != null && state.enabled) {
            Spacer(Modifier.width(16.dp))
            Text(shortcut, style = ArchieTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

/** A static menu panel (goldens / gallery). */
@Composable
fun SessionMenuPanel(item: WorkspaceItem, modifier: Modifier = Modifier) {
    com.assistant.core.design.components.ArchieMenuPanel(modifier) {
        SessionMenuItems(item, {}, {}, {})
    }
}
