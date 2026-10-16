package com.assistant.archie.feature.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assistant.archie.feature.chat.ChatAction
import com.assistant.archie.feature.chat.model.AgentTabUi
import com.assistant.archie.feature.chat.model.AgentUi
import kotlinx.collections.immutable.ImmutableList
import com.assistant.archie.feature.chat.model.AgentViewUi
import com.assistant.archie.feature.chat.model.AgentsUi
import com.assistant.archie.feature.toolcards.AgentRunStatus
import com.assistant.core.conversation.SubagentStatus
import com.assistant.core.design.ToolCategory
import com.assistant.core.design.components.ArchieButton
import com.assistant.core.design.components.ArchieDropdownMenu
import com.assistant.core.design.components.ArchieIconButton
import com.assistant.core.design.components.ArchieMenuItem
import com.assistant.core.design.components.ArchieMenuSeparator
import com.assistant.core.design.components.ButtonStyle
import com.assistant.core.design.components.IconButtonStyle
import com.assistant.core.design.components.ListSectionHeader
import com.assistant.core.design.components.LiveAnnouncement
import com.assistant.core.design.components.SecondaryScrollableTabRow
import com.assistant.core.design.components.SecondaryTab
import com.assistant.core.design.components.Spinner
import com.assistant.core.design.components.SystemLine
import com.assistant.core.design.components.ToolIconTile
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.model.SessionKind

/*
 * The subagent view (spec 12 §4.8, plan §4.4): the nested agent tab row above the conversation, an
 * agent tab's body with its read-only bar, the Agents button by the composer and the Agents sheet.
 * Every agent gets a tab automatically, as a placeholder (decision 1); a tab's transcript is fetched
 * only when the tab is entered (SA-5). A new tab never takes focus (FOCUS-1).
 */

/** Side sheet from this width (IA §7, the session-settings pattern); bottom sheet below. */
val AgentsSideSheetWidth = 600.dp

/** Status glyph of an agent (tab, row, bar): spinner, check, error, stop. */
@Composable
fun AgentStatusGlyph(status: SubagentStatus, size: androidx.compose.ui.unit.Dp = 16.dp) {
    val c = ArchieTheme.colors
    val x = ArchieTheme.extended
    when (status) {
        SubagentStatus.RUNNING -> Spinner(size = size)
        SubagentStatus.DONE -> ArchieIcon(ArchieIcons.CheckCircle, null, size = size, tint = x.success.color)
        SubagentStatus.FAILED -> ArchieIcon(ArchieIcons.Error, null, size = size, tint = c.error)
        SubagentStatus.STOPPED -> ArchieIcon(ArchieIcons.StopFilled, null, size = size, tint = c.onSurfaceVariant)
    }
}

/**
 * The nested agent tabs (M3 secondary, scrollable): "Main" then one tab per subagent in spawn order,
 * `↳` for nested ones. The trailing "N ⌄" menu lists every agent (closed ones included) and offers
 * "Close finished". The screen composes it only while some agent has a tab (`AgentsUi.showsTabs`).
 */
@Composable
fun AgentTabRow(
    tabs: ImmutableList<AgentTabUi>,
    menuItems: ImmutableList<AgentTabUi>,
    selected: String?,
    closableFinished: Int,
    onAction: (ChatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ArchieTheme.colors
    val index = if (selected == null) 0 else tabs.indexOfFirst { it.id == selected } + 1
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().background(c.surface).testTag("agent-tabs"), verticalAlignment = Alignment.CenterVertically) {
        SecondaryScrollableTabRow(selectedTabIndex = index.coerceAtLeast(0), modifier = Modifier.weight(1f)) {
            SecondaryTab(
                selected = selected == null,
                onClick = { onAction(ChatAction.SelectAgent(null)) },
                label = "Main",
                modifier = Modifier.testTag("agent-tab:main"),
                contentDescription = "Main conversation",
            )
            tabs.forEach { a ->
                SecondaryTab(
                    selected = a.id == selected,
                    onClick = { onAction(ChatAction.SelectAgent(a.id)) },
                    label = (if (a.depth > 0) "↳ " else "") + a.label,
                    modifier = Modifier.testTag("agent-tab:${a.id}"),
                    leading = { AgentStatusGlyph(a.status, 14.dp) },
                    contentDescription = a.spoken,
                )
            }
        }
        Box {
            Row(
                Modifier
                    .padding(horizontal = 4.dp)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { menu = true }
                    .semantics { contentDescription = "All agents, ${menuItems.size}" }
                    .padding(horizontal = 10.dp)
                    .testTag("agent-tabs-menu"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${menuItems.size}", style = ArchieTheme.typography.labelLarge, color = c.onSurfaceVariant)
                ArchieIcon(ArchieIcons.KeyboardArrowDown, null, size = 18.dp, tint = c.onSurfaceVariant)
            }
            ArchieDropdownMenu(menu, { menu = false }) {
                menuItems.forEach { a ->
                    ArchieMenuItem(
                        (if (a.depth > 0) "↳ " else "") + a.title,
                        { menu = false; onAction(ChatAction.SelectAgent(a.id)) },
                        icon = when (a.status) {
                            SubagentStatus.RUNNING -> ArchieIcons.SmartToy
                            SubagentStatus.DONE -> ArchieIcons.CheckCircle
                            SubagentStatus.FAILED -> ArchieIcons.Error
                            SubagentStatus.STOPPED -> ArchieIcons.StopFilled
                        },
                    )
                }
                ArchieMenuSeparator()
                ArchieMenuItem(
                    "Close finished",
                    { menu = false; onAction(ChatAction.CloseFinishedAgents) },
                    icon = ArchieIcons.Close,
                    enabled = closableFinished > 0,
                )
            }
        }
    }
}

/** Said when the tab row hides because its last agent tab was closed (the web says the same). */
const val AgentTabsClosedText = "Agent tabs closed. The Agents button reopens them."

/**
 * A polite announcement when the tab row goes away ("Close finished" with every agent done): the
 * row's menu that held the action is gone, so tell where the agents are. Only on that transition,
 * never when a session opens with every tab already closed.
 */
@Composable
fun AgentTabsClosedAnnouncement(any: Boolean, showsTabs: Boolean) {
    var shownBefore by remember { mutableStateOf(showsTabs) }
    var closed by remember { mutableStateOf(false) }
    LaunchedEffect(showsTabs) {
        closed = shownBefore && !showsTabs && any
        shownBefore = showsTabs
    }
    if (closed && !showsTabs) LiveAnnouncement(AgentTabsClosedText, Modifier.testTag("agent-tabs-closed"))
}

/**
 * An agent tab's body: the breadcrumb of a nested agent, then the subagent's own transcript in the
 * normal list (same cards); a spinner while its first page loads; the history error when that
 * failed (the live tail still shows). Long-press actions (rewind, fork) belong to Main only.
 */
@Composable
fun AgentTabBody(
    view: AgentViewUi,
    kind: SessionKind,
    toolCards: ToolCardRenderer,
    onAction: (ChatAction) -> Unit,
    onLink: (String) -> Unit,
    linkOrigin: String?,
    modifier: Modifier = Modifier,
    /** Kept per agent by the caller, so a tab returns where it was left. */
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
) {
    val c = ArchieTheme.colors
    // The list asks for older pages near its end; an agent tab shows its first page only (SA-5) and
    // must never page Main's history.
    val tabAction = remember(onAction) { { a: ChatAction -> if (a !is ChatAction.LoadOlder) onAction(a) } }
    Column(modifier.fillMaxSize().testTag("agent-view:${view.agent.id}")) {
        if (view.path.isNotEmpty()) {
            Text(
                view.path.joinToString(" › "),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("agent-breadcrumb"),
                style = ArchieTheme.typography.labelMedium,
                color = c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        view.error?.let { e ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("agent-history-error"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArchieIcon(ArchieIcons.Warning, null, size = 16.dp, tint = ArchieTheme.extended.warning.color)
                Text("Earlier steps couldn't load: $e", style = ArchieTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        if (view.truncated) SystemLine("Showing this agent's latest steps", icon = ArchieIcons.History)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !view.loaded && view.items.isEmpty() -> Box(Modifier.fillMaxSize().testTag("agent-loading"), contentAlignment = Alignment.Center) { Spinner(size = 24.dp) }
                view.items.isEmpty() -> Text(
                    if (view.agent.status == SubagentStatus.RUNNING) "Waiting for this agent's first step…" else "No steps were recorded for this agent.",
                    Modifier.align(Alignment.Center).padding(24.dp),
                    style = ArchieTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                else -> key(view.agent.id) {
                    ChatList(
                        items = view.items,
                        kind = kind,
                        toolCards = toolCards,
                        listState = listState,
                        onAction = tabAction,
                        onLongPress = {},
                        onLink = onLink,
                        linkOrigin = linkOrigin,
                    )
                }
            }
        }
    }
}

/** Replaces the composer in an agent tab: "Viewing … · Stop · Back to Main" (messaging a subagent is out of scope). */
@Composable
fun AgentReadOnlyBar(agent: AgentUi, onAction: (ChatAction) -> Unit, modifier: Modifier = Modifier) {
    val c = ArchieTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(c.surfaceContainerHigh, RoundedCornerShape(28.dp))
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
            .testTag("agent-bar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentStatusGlyph(agent.status, 18.dp)
        Column(Modifier.weight(1f)) {
            Text("Viewing ${agent.title}", style = ArchieTheme.typography.bodyMedium, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(agent.run.line(), style = ArchieTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LiveAnnouncement("${agent.title} ${agent.run.spoken}", assertive = agent.run.status == AgentRunStatus.Failed)
        if (agent.status == SubagentStatus.RUNNING) {
            ArchieIconButton(ArchieIcons.StopFilled, "Stop ${agent.title}", { onAction(ChatAction.StopAgent(agent.id)) }, style = IconButtonStyle.Error, size = 40.dp, modifier = Modifier.testTag("agent-stop"))
        }
        ArchieButton("Back to Main", { onAction(ChatAction.SelectAgent(null)) }, style = ButtonStyle.Text, modifier = Modifier.testTag("agent-back"))
    }
}

/** The Agents button in the composer row (next to the context ring): badge = running agents. */
@Composable
fun AgentsButton(total: Int, running: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = if (running > 0) "Agents, $running running of $total" else "Agents, $total"
    BadgedBox(
        badge = { if (running > 0) Badge(containerColor = ArchieTheme.colors.primary, contentColor = ArchieTheme.colors.onPrimary) { Text("$running") } },
        modifier = modifier.testTag("agents-button"),
    ) {
        ArchieIconButton(ArchieIcons.SmartToy, label, onClick, size = 40.dp)
    }
}

/**
 * The Agents sheet (plan §4.4 item 2): side sheet when [wide] (≥ [AgentsSideSheetWidth]), modal bottom sheet below; agents grouped
 * Running · Done · Failed/Stopped in spawn order, nested ones indented, Stop on running ones; a row
 * opens its tab. More than three finished agents fold into "N more".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsSheet(agents: AgentsUi, onAction: (ChatAction) -> Unit, wide: Boolean, onDismiss: () -> Unit) {
    val c = ArchieTheme.colors
    if (!wide) {
        ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surfaceContainerLow) {
            AgentsSheetContent(agents, onAction, onDismiss, Modifier.padding(bottom = 16.dp))
        }
        return
    }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(c.scrim.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .testTag("agents-sheet-scrim"),
    ) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(400.dp)
                .background(c.surfaceContainerLow, RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(top = 8.dp),
        ) {
            AgentsSheetContent(agents, onAction, onDismiss, Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun AgentsSheetContent(agents: AgentsUi, onAction: (ChatAction) -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    val c = ArchieTheme.colors
    var showAllDone by rememberSaveable { mutableStateOf(false) }
    Column(modifier.testTag("agents-sheet")) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Agents", style = ArchieTheme.typography.titleLarge, color = c.onSurface)
                Text(
                    if (agents.running > 0) "${agents.running} running · ${agents.all.size} in this session" else "${agents.all.size} in this session",
                    style = ArchieTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                )
            }
            ArchieIconButton(ArchieIcons.Close, "Close", onDismiss)
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            val running = agents.all.filter { it.status == SubagentStatus.RUNNING }
            val done = agents.all.filter { it.status == SubagentStatus.DONE }
            val ended = agents.all.filter { it.status == SubagentStatus.FAILED || it.status == SubagentStatus.STOPPED }
            val pick: (AgentUi) -> Unit = { a -> onAction(ChatAction.SelectAgent(a.id)); onDismiss() }
            if (running.isNotEmpty()) {
                ListSectionHeader("Running")
                running.forEach { AgentRow(it, pick, onAction) }
            }
            if (done.isNotEmpty()) {
                ListSectionHeader("Done")
                val shown = if (showAllDone || done.size <= 3) done else done.take(3)
                shown.forEach { AgentRow(it, pick, onAction) }
                if (shown.size < done.size) {
                    ArchieButton("${done.size - shown.size} more", { showAllDone = true }, style = ButtonStyle.Text, modifier = Modifier.padding(start = 4.dp).testTag("agents-more"))
                }
            }
            if (ended.isNotEmpty()) {
                ListSectionHeader("Failed or stopped")
                ended.forEach { AgentRow(it, pick, onAction) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            ArchieButton(
                "Close finished",
                { onAction(ChatAction.CloseFinishedAgents) },
                style = ButtonStyle.Text,
                enabled = agents.closableFinished > 0,
                modifier = Modifier.testTag("agents-close-finished"),
            )
        }
    }
}

@Composable
private fun AgentRow(a: AgentUi, onPick: (AgentUi) -> Unit, onAction: (ChatAction) -> Unit) {
    val c = ArchieTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = "Open tab") { onPick(a) }
            .semantics { contentDescription = a.spoken }
            .padding(start = 12.dp + 16.dp * a.depth.coerceAtMost(4), end = 4.dp, top = 8.dp, bottom = 8.dp)
            .testTag("agent-row:${a.id}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ToolIconTile(ToolCategory.Task, ArchieIcons.Assignment, size = 36.dp, iconSize = 20.dp, corner = 12.dp)
            Box(Modifier.align(Alignment.BottomEnd).size(18.dp).background(c.surfaceContainerLow, CircleShape), contentAlignment = Alignment.Center) {
                AgentStatusGlyph(a.status, 14.dp)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text((if (a.depth > 0) "↳ " else "") + a.title, style = ArchieTheme.typography.bodyLarge, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.run.line(), style = ArchieTheme.typography.bodyMedium, color = if (a.status == SubagentStatus.FAILED) c.error else c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            com.assistant.archie.feature.toolcards.ui.AgentStatsText(a.run)
        }
        if (a.status == SubagentStatus.RUNNING) {
            ArchieIconButton(ArchieIcons.StopFilled, "Stop ${a.title}", { onAction(ChatAction.StopAgent(a.id)) }, size = 40.dp, modifier = Modifier.testTag("agent-row-stop:${a.id}"))
        }
    }
}
