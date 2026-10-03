package com.assistant.archie.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.core.data.HistoryGroup
import com.assistant.core.data.ItemKind
import com.assistant.core.data.OpenSessionsRepository
import com.assistant.core.data.WorkspaceItem
import com.assistant.core.design.components.ArchieButton
import com.assistant.core.design.components.ArchieIconButton
import com.assistant.core.design.components.ArchieListItem
import com.assistant.core.design.components.ArchieMark
import com.assistant.core.design.components.ButtonStyle
import com.assistant.core.design.components.ListItemSize
import com.assistant.core.design.components.ListLeadingIcon
import com.assistant.core.design.components.ListLeadingTile
import com.assistant.core.design.components.ListMeta
import com.assistant.core.design.components.ListSectionHeader
import com.assistant.core.design.components.ProviderChip
import com.assistant.core.design.components.StatusIndicator
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.model.PoolSession

/*
 * Navigation chrome shared by the drawer (Compact), the list pane (Medium/Expanded) and the session
 * switcher sheet (Compact). Data comes from OpenSessionsRepository + the session directory through
 * ShellUiState; the B-06 feature screens may replace the switcher / history parts later.
 */

/** Unread dot for a background tab opened by another device or by Archie (P-6). */
@Composable
private fun UnreadDot() {
    Box(Modifier.size(8.dp).background(ArchieTheme.colors.primary, CircleShape))
}

@Composable
private fun OpenItemTrailing(item: WorkspaceItem) {
    if (item.unread) UnreadDot()
    item.provider?.let { ProviderChip(it.label) }
    item.status.toLive()?.let { StatusIndicator(it) }
}

/** "Open now" one-line rows (drawer, mockup (b)). */
internal fun LazyListScope.openNowRows(
    state: ShellUiState,
    onAction: (ShellAction) -> Unit,
    twoLine: Boolean,
    after: () -> Unit,
) {
    val count = state.items.size + state.liveElsewhere.size
    if (count == 0) return
    item(key = "open-h") {
        ListSectionHeader("Open now", trailing = if (twoLine) ({ ListMeta("$count") }) else null)
    }
    items(state.items, key = { "open-" + it.key.toString() }) { item ->
        ArchieListItem(
            item.title,
            onClick = { onAction(ShellAction.Select(item.key)); after() },
            modifier = Modifier.testTag("open-row"),
            size = if (twoLine) ListItemSize.TwoLine else ListItemSize.OneLine,
            selected = item.key == state.active,
            leading = { ItemLeading(item.kind) },
            supporting = if (twoLine) ({
                item.provider?.let { ProviderChip(it.label) }
                Text(item.detail.removePrefix("Archie · ").replaceFirstChar { c -> c.uppercase() }.let { if (item.kind == ItemKind.ARCHIE) "Archie · $it" else it }, maxLines = 1)
            }) else null,
            trailing = {
                if (twoLine) {
                    if (item.unread) UnreadDot()
                    item.status.toLive()?.let { StatusIndicator(it) }
                } else {
                    OpenItemTrailing(item)
                }
            },
        )
    }
    items(state.liveElsewhere, key = { "live-" + it.localId }) { p ->
        ArchieListItem(
            p.title?.takeIf { it.isNotBlank() } ?: OpenSessionsRepository.AGENT_PLACEHOLDER,
            onClick = { onAction(ShellAction.OpenLive(p)); after() },
            size = if (twoLine) ListItemSize.TwoLine else ListItemSize.OneLine,
            leading = { ListLeadingIcon(ArchieIcons.Terminal) },
            supporting = if (twoLine) ({ Text("Open on another device", maxLines = 1) }) else null,
            trailing = { OpenSessionsRepository.statusOf(p.status).toLive()?.let { StatusIndicator(it) } },
        )
    }
}

/** History grouped by local day (Today / Yesterday / Previous 7 days / Earlier). */
internal fun LazyListScope.historyRows(groups: List<HistoryGroup>, onAction: (ShellAction) -> Unit, after: () -> Unit) {
    for (g in groups) {
        item(key = "h-" + g.bucket.name) { ListSectionHeader(g.bucket.label) }
        items(g.rows, key = { "row-" + it.summary.sdkId }) { row ->
            ArchieListItem(
                row.summary.title.ifBlank { if (row.summary.isOrchestrator) "Archie conversation" else "Agent session" },
                onClick = { onAction(ShellAction.OpenHistory(row.summary)); after() },
                modifier = Modifier.testTag("history-row"),
                leading = { HistoryLeading(row) },
                trailing = { if (row.meta.isNotEmpty()) ListMeta(row.meta) },
            )
        }
    }
}

internal fun LazyListScope.historyStatus(state: ShellUiState) {
    if (state.history.isEmpty()) {
        item(key = "h-empty") {
            val text = when {
                state.historyLoading -> "Loading conversations…"
                state.historyError != null -> "Couldn't load conversations: ${state.historyError}"
                state.search.isNotEmpty() -> "No conversation matches \"${state.search}\""
                else -> "No conversations yet"
            }
            Text(
                text,
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = ArchieTheme.typography.bodyMedium,
                color = ArchieTheme.colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * The navigation drawer body (IA §5, mockup (b)): mark + connection, search, Open now, history by
 * date, footer Memory / Visuals / Settings.
 */
@Composable
fun DrawerContent(
    state: ShellUiState,
    onAction: (ShellAction) -> Unit,
    onNavigate: (ShellNav) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ArchieTheme.colors
    Column(modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 20.dp).testTag("drawer")) {
        Row(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArchieMark(size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text("Archie", style = ArchieTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp), color = c.onSurface)
                ConnectionLine(state.connection)
            }
        }
        SearchField(state.search, { onAction(ShellAction.Search(it)) }, "Search conversations")
        LazyColumn(Modifier.weight(1f).padding(top = 2.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            openNowRows(state, onAction, twoLine = false, after = onClose)
            historyRows(state.history, onAction, after = onClose)
            historyStatus(state)
        }
        DrawerFooter(onNavigate, onClose)
    }
}

@Composable
private fun ColumnScope.DrawerFooter(onNavigate: (ShellNav) -> Unit, onClose: () -> Unit) {
    ArchieListItem("Memory", { onNavigate(ShellNav.Memory); onClose() }, leading = { ListLeadingIcon(ArchieIcons.Book2) })
    ArchieListItem("Visuals", { onNavigate(ShellNav.Visuals); onClose() }, leading = { ListLeadingIcon(ArchieIcons.BarChart) })
    ArchieListItem("Settings", { onNavigate(ShellNav.Settings); onClose() }, leading = { ListLeadingIcon(ArchieIcons.Settings) })
}

/** Where drawer / rail entries go (resolved per size class by the shell). */
enum class ShellNav { Chats, Memory, Visuals, Settings, History }

/** Expanded/Medium list pane for the Chats destination (IA §3, desktop mockup `.lp`). */
@Composable
fun ChatsPane(
    state: ShellUiState,
    onAction: (ShellAction) -> Unit,
    onCollapse: (() -> Unit)?,
    after: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("list-pane")) {
        PaneHeader("Chats", onCollapse)
        SearchField(state.search, { onAction(ShellAction.Search(it)) }, "Search conversations")
        LazyColumn(Modifier.weight(1f).padding(top = 2.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            openNowRows(state, onAction, twoLine = true, after = after)
            historyRows(state.history, onAction, after = after)
            historyStatus(state)
        }
    }
}

@Composable
fun PaneHeader(title: String, onCollapse: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = ArchieTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp), color = ArchieTheme.colors.onSurface)
        if (onCollapse != null) ArchieIconButton(ArchieIcons.LeftPanelClose, "Collapse list", onCollapse)
    }
}

/**
 * Session switcher sheet body (IA §5, mockup (c)): open sessions with live status, each closable,
 * plus the two "new" actions. Swipe-to-close on rows is left to B-06.
 */
@Composable
fun SessionSwitcherContent(
    state: ShellUiState,
    onAction: (ShellAction) -> Unit,
    onRequestClose: (WorkspaceItem) -> Unit,
    onHistory: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(start = 12.dp, end = 12.dp, bottom = 30.dp).testTag("switcher")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sessions", Modifier.weight(1f), style = ArchieTheme.typography.titleLarge, color = ArchieTheme.colors.onSurface)
            ArchieButton("History", { onHistory(); onDismiss() }, style = ButtonStyle.Text)
        }
        ListSectionHeader("Open now", Modifier.padding(top = 0.dp))
        if (state.items.isEmpty() && state.liveElsewhere.isEmpty()) {
            Text(
                "Nothing open on this device.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = ArchieTheme.typography.bodyMedium,
                color = ArchieTheme.colors.onSurfaceVariant,
            )
        }
        for (item in state.items) SwitcherRow(item, item.key == state.active, onAction, onRequestClose, onDismiss)
        for (p in state.liveElsewhere) LiveElsewhereRow(p, onAction, onDismiss)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArchieButton("New Archie chat", { onAction(ShellAction.NewArchie); onDismiss() }, Modifier.weight(1f), ButtonStyle.Tonal, icon = ArchieIcons.AddComment)
            ArchieButton("New agent session", { onAction(ShellAction.NewAgent); onDismiss() }, Modifier.weight(1f), ButtonStyle.Outlined, icon = ArchieIcons.Terminal)
        }
    }
}

@Composable
private fun SwitcherRow(
    item: WorkspaceItem,
    selected: Boolean,
    onAction: (ShellAction) -> Unit,
    onRequestClose: (WorkspaceItem) -> Unit,
    onDismiss: () -> Unit,
) {
    ArchieListItem(
        item.title,
        onClick = { onAction(ShellAction.Select(item.key)); onDismiss() },
        modifier = Modifier.testTag("switcher-row"),
        size = ListItemSize.Large,
        selected = selected,
        leading = {
            if (item.kind == ItemKind.ARCHIE) ListLeadingTile { ArchieMark(size = 40.dp) } else ListLeadingTile(item.kind.icon())
        },
        supporting = {
            item.provider?.let { ProviderChip(it.label) }
            item.status.toLive()?.let { StatusIndicator(it) }
            Text(item.detail, maxLines = 1)
        },
        trailing = {
            if (item.unread) UnreadDot()
            ArchieIconButton(ArchieIcons.Close, "Close ${item.title}", { onRequestClose(item) })
        },
    )
}

@Composable
private fun LiveElsewhereRow(p: PoolSession, onAction: (ShellAction) -> Unit, onDismiss: () -> Unit) {
    ArchieListItem(
        p.title?.takeIf { it.isNotBlank() } ?: OpenSessionsRepository.AGENT_PLACEHOLDER,
        onClick = { onAction(ShellAction.OpenLive(p)); onDismiss() },
        size = ListItemSize.Large,
        leading = { ListLeadingTile(ArchieIcons.Terminal) },
        supporting = {
            OpenSessionsRepository.statusOf(p.status).toLive()?.let { StatusIndicator(it) }
            Text("Open on another device", maxLines = 1)
        },
    )
}

/** True when a close needs a confirmation first (§6.7: Archie always, an agent while busy). */
internal fun needsCloseConfirm(item: WorkspaceItem): Boolean =
    item.kind == ItemKind.ARCHIE || (item.kind == ItemKind.AGENT && item.status == com.assistant.core.data.TabStatus.WORKING)

internal fun closeConfirmText(item: WorkspaceItem): Pair<String, String> = when (item.kind) {
    ItemKind.ARCHIE -> "Stop Archie on all devices?" to "This Archie conversation ends everywhere, voice included. It stays in History."
    else -> "Close ${item.title}?" to "Closing stops the current reply. The session stays in History."
}
