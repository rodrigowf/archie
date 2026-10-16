package com.assistant.archie.tv.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.assistant.archie.feature.chat.ChatAction
import com.assistant.archie.feature.chat.model.AgentUi
import com.assistant.archie.feature.chat.model.AgentViewUi
import com.assistant.archie.feature.chat.model.AgentsUi
import com.assistant.archie.feature.chat.ui.ToolCardRenderer
import com.assistant.archie.tv.theme.FocusCard
import com.assistant.archie.tv.theme.FocusOnOpen
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvButtonStyle
import com.assistant.archie.tv.theme.TvDialog
import com.assistant.archie.tv.theme.TvSheetRow
import com.assistant.archie.tv.theme.TvSideSheet
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.theme.dismissOnBack
import com.assistant.archie.tv.theme.trapFocus
import com.assistant.core.conversation.SubagentStatus
import com.assistant.core.design.components.Spinner
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.model.SessionKind

/*
 * The conversation screen's overlays (spec 15 §3.5): the Agents side sheet (the turn's subagents:
 * status, last line, Stop; OK opens a read-only transcript), the subagent transcript, and the
 * message actions of a long-pressed block (rewind / fork / copy to composer, as the apps).
 */

/** "Agents N" → the session's subagents (spec 12 §4.8). */
@Composable
fun AgentsSheet(agents: AgentsUi, onAction: (ChatAction) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    FocusOnOpen(first)
    TvSideSheet(
        title = "Agents",
        eyebrow = "This conversation",
        subtitle = if (agents.running > 0) "${agents.running} running · ${agents.all.size} in this session" else "${agents.all.size} in this session",
        onClose = onClose,
        width = 520.dp,
        modifier = Modifier.testTag("agents-sheet"),
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val ordered = agents.all.sortedBy { if (it.status == SubagentStatus.RUNNING) 0 else 1 }
            items(ordered, key = { it.id }) { a ->
                AgentRow(a, if (a === ordered.first()) Modifier.focusRequester(first) else Modifier, onAction, onClose)
            }
        }
    }
}

@Composable
private fun AgentRow(a: AgentUi, modifier: Modifier, onAction: (ChatAction) -> Unit, onClose: () -> Unit) {
    val c = TvTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        FocusCard(
            onClick = {
                onAction(ChatAction.SelectAgent(a.id))
                onClose()
            },
            onLongClick = {},
            modifier = modifier.weight(1f).testTag("agent-${a.id}"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (a.status) {
                    SubagentStatus.RUNNING -> Spinner(size = 16.dp, strokeWidth = 2.5.dp)
                    SubagentStatus.DONE -> Icon(ArchieIcons.CheckCircle, contentDescription = null, tint = ArchieTheme.extended.success.color, modifier = Modifier.size(18.dp))
                    SubagentStatus.FAILED -> Icon(ArchieIcons.Error, contentDescription = null, tint = c.error, modifier = Modifier.size(18.dp))
                    SubagentStatus.STOPPED -> Icon(ArchieIcons.StopFilled, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text((if (a.depth > 0) "↳ " else "") + a.title, style = TvTheme.type.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(a.run.line(), style = TvTheme.type.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val stats = a.run.stats(System.currentTimeMillis())
            if (stats.isNotEmpty()) Text(stats, style = TvTheme.type.labelMedium, color = LocalContentColor.current.copy(alpha = 0.72f), maxLines = 1)
        }
        if (a.status == SubagentStatus.RUNNING) {
            Spacer(Modifier.width(10.dp))
            TvButton("Stop", { onAction(ChatAction.StopAgent(a.id)) }, Modifier.testTag("agent-stop-${a.id}"), icon = ArchieIcons.StopFilled)
        }
    }
}

/** A subagent's transcript, read-only (SA-5): Back returns to the conversation. */
@Composable
fun AgentTranscript(view: AgentViewUi, kind: SessionKind, toolCards: ToolCardRenderer, onAction: (ChatAction) -> Unit, reduceMotion: Boolean) {
    val c = TvTheme.colors
    BackHandler { onAction(ChatAction.SelectAgent(null)) }
    val listState = rememberLazyListState()
    val controller = rememberTranscriptController(listState, reduceMotion)
    val items = remember(view.items) { view.items.map { TItem.Chat(it) } }
    val newest = remember(items) { TranscriptModel.newestFollowable(items) }
    val back = remember { FocusRequester() }
    LaunchedEffect(view.agent.id, newest != null) {
        withFrameNanos { }
        if (newest == null || !controller.focus(newest)) runCatching { back.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.surface)
            .dismissOnBack { onAction(ChatAction.SelectAgent(null)) }
            .trapFocus()
            .testTag("agent-transcript"),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TvButton("Back", { onAction(ChatAction.SelectAgent(null)) }, Modifier.focusRequester(back), icon = ArchieIcons.ArrowBack)
            Spacer(Modifier.width(16.dp))
            Icon(ArchieIcons.SmartToy, contentDescription = null, tint = c.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(view.agent.title, style = TvTheme.type.titleLarge, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        view.path.takeIf { it.isNotEmpty() }?.joinToString(" › "),
                        view.agent.run.line(),
                        "read-only",
                    ).joinToString(" · "),
                    style = TvTheme.type.labelMedium,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (view.agent.status == SubagentStatus.RUNNING) {
                TvButton("Stop", { onAction(ChatAction.StopAgent(view.agent.id)) }, icon = ArchieIcons.StopFilled)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !view.loaded && view.empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(size = 28.dp) }
                view.empty -> Text(
                    view.error ?: "Nothing yet.",
                    Modifier.align(Alignment.Center),
                    style = TvTheme.type.bodyLarge,
                    color = c.onSurfaceVariant,
                )
                else -> Transcript(items, kind, toolCards, controller, onAction = { a -> if (a is ChatAction.ToggleGroup || a is ChatAction.ToggleCard) onAction(a) }, onLongPress = {}, readOnly = true)
            }
        }
        if (view.truncated) {
            Text("Only the latest part of this agent's transcript is shown.", style = TvTheme.type.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Long-press OK on a message (spec 15 §3.3): the apps' message actions, re-laid-out. */
@Composable
fun MessageActionsSheet(
    hasText: Boolean,
    canEdit: Boolean,
    onCopyToComposer: () -> Unit,
    onFork: () -> Unit,
    onRewind: () -> Unit,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    FocusOnOpen(first)
    TvSideSheet(title = "This message", eyebrow = "Message actions", onClose = onClose, modifier = Modifier.testTag("message-actions")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TvSheetRow(
                "Copy to composer",
                onCopyToComposer,
                Modifier.focusRequester(first).testTag("msg-copy"),
                icon = ArchieIcons.ContentPaste,
                detail = if (hasText) "Opens the keyboard with this text" else "This message has no text",
                enabled = hasText && canEdit,
            )
            TvSheetRow("Fork from here", onFork, Modifier.testTag("msg-fork"), icon = ArchieIcons.CallSplit, detail = "A new conversation up to this message")
            TvSheetRow("Rewind to here", onRewind, Modifier.testTag("msg-rewind"), icon = ArchieIcons.History, detail = "Removes the messages after this one")
        }
    }
}

@Composable
fun RewindConfirm(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    FocusOnOpen(first)
    TvDialog(
        title = "Rewind to here?",
        body = "Messages after this one will be removed. This cannot be undone.",
        onDismiss = onDismiss,
    ) {
        TvButton("Cancel", onDismiss, Modifier.focusRequester(first))
        TvButton("Rewind", onConfirm, icon = ArchieIcons.History, style = TvButtonStyle.Primary)
    }
}
