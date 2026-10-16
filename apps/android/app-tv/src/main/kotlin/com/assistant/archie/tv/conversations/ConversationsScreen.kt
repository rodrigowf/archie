package com.assistant.archie.tv.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.assistant.archie.feature.sessions.HistoryList
import com.assistant.archie.feature.sessions.SessionTitles
import com.assistant.archie.tv.shell.Conversation
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.theme.CardMeta
import com.assistant.archie.tv.theme.FocusCard
import com.assistant.archie.tv.theme.RowHeader
import com.assistant.archie.tv.theme.TvEmptyState
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.theme.rememberInitialFocus
import com.assistant.core.data.ItemKind
import com.assistant.core.data.TabStatus
import com.assistant.core.data.WorkspaceItem
import com.assistant.core.design.icons.ArchieIcons
import java.time.Instant
import java.time.ZoneId

/*
 * Conversations (spec 15 §3.5) — T0 placeholder: the two panes with real data (Open now + history
 * by day, same grouping as the apps) and a plain preview of the focused row. T1 adds search, the
 * message preview, Menu actions (rename, duplicate, close, delete, new agent / Archie).
 */

@Immutable
data class ConversationRow(val title: String, val detail: String, val target: Conversation, val meta: String = "")

@Immutable
data class ConversationSection(val title: String, val rows: List<ConversationRow>)

@Composable
fun ConversationsRoute(ctx: TvRouteContext) {
    val g = ctx.graph
    val items by g.openSessions.items.collectAsStateWithLifecycle()
    val sessions by g.history.sessions.collectAsStateWithLifecycle()
    val sections = remember(items, sessions) { sectionsOf(items, sessions.value.orEmpty(), Instant.now(), ZoneId.systemDefault()) }
    ConversationsScreen(sections, loading = sessions.loading, onOpen = ctx.nav::push)
}

/** Open now (conversation items of the workspace) + the history buckets (pure). */
fun sectionsOf(
    items: List<WorkspaceItem>,
    sessions: List<com.assistant.core.model.SessionSummary>,
    now: Instant,
    zone: ZoneId,
): List<ConversationSection> {
    val open = items.filter { it.kind == ItemKind.ARCHIE || it.kind == ItemKind.AGENT }.map { w ->
        ConversationRow(
            title = SessionTitles.conversationTitle(w.title, w.kind == ItemKind.ARCHIE),
            detail = listOfNotNull(if (w.kind == ItemKind.ARCHIE) "Archie" else w.provider?.label, w.detail.ifEmpty { null }, statusLabel(w.status)).joinToString(" · "),
            target = Conversation(localId = w.localId, sdkId = w.sdkId, archie = w.kind == ItemKind.ARCHIE),
        )
    }
    val groups = HistoryList.groups(sessions, items, "", now, zone).map { g ->
        ConversationSection(
            g.bucket.label,
            g.rows.map { r ->
                val s = r.summary
                ConversationRow(
                    title = SessionTitles.conversationTitle(s.title, s.isOrchestrator),
                    detail = listOfNotNull(if (s.isOrchestrator) "Archie" else s.provider?.label, "${s.messageCount} messages").joinToString(" · "),
                    target = Conversation(localId = s.localId, sdkId = s.sdkId, archie = s.isOrchestrator),
                    meta = r.meta,
                )
            },
        )
    }
    return listOfNotNull(open.takeIf { it.isNotEmpty() }?.let { ConversationSection("Open now", it) }) + groups
}

private fun statusLabel(s: TabStatus): String? = when (s) {
    TabStatus.WORKING -> "Working"
    TabStatus.NEEDS_YOU -> "Needs approval"
    TabStatus.CONNECTING -> "Connecting"
    TabStatus.DISCONNECTED -> "Disconnected"
    TabStatus.IDLE, TabStatus.STOPPED, TabStatus.NONE -> null
}

@Composable
fun ConversationsScreen(sections: List<ConversationSection>, loading: Boolean, onOpen: (Conversation) -> Unit, modifier: Modifier = Modifier) {
    ScreenChromeEffect(ScreenChrome(title = "Conversations", hints = listOf(KeyHints.Ok("Open"), KeyHints.Mic, KeyHints.Menu, KeyHints.Back)))
    val initial = rememberInitialFocus(sections.isNotEmpty())
    if (sections.isEmpty()) {
        TvEmptyState(
            if (loading) "Loading conversations…" else "No conversations yet",
            "Hold the mic button to talk to Archie; agent sessions appear here too.",
            modifier.testTag("conversations-empty"),
            icon = ArchieIcons.Forum,
        )
    } else {
        ConversationPanes(sections, onOpen, modifier, initial)
    }
}

@Composable
private fun ConversationPanes(sections: List<ConversationSection>, onOpen: (Conversation) -> Unit, modifier: Modifier, initial: androidx.compose.ui.focus.FocusRequester) {
    val c = TvTheme.colors
    var focused by remember { mutableStateOf<ConversationRow?>(null) }
    Row(modifier.fillMaxSize().testTag("conversations")) {
        LazyColumn(
            Modifier.width(360.dp).fillMaxHeight().focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(6.dp),
        ) {
            val first = sections.first()
            for (section in sections) {
                item(key = "h-${section.title}") { RowHeader(section.title, Modifier.padding(top = 10.dp)) }
                items(section.rows, key = { "${section.title}-${it.target}" }) { row ->
                    val isFirst = section === first && row === first.rows.firstOrNull()
                    FocusCard(
                        onClick = { onOpen(row.target) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isFirst) Modifier.focusRequester(initial) else Modifier)
                            .onFocusChanged { if (it.isFocused) focused = row },
                    ) {
                        Text(row.title, style = TvTheme.type.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        CardMeta(listOf(row.detail, row.meta).filter { it.isNotEmpty() }.joinToString(" · "))
                    }
                }
            }
        }
        Spacer(Modifier.width(28.dp))
        Column(
            Modifier.weight(1f).fillMaxHeight().background(c.surfaceContainerLow, RoundedCornerShape(24.dp)).padding(28.dp),
        ) {
            val row = focused ?: sections.firstNotNullOfOrNull { it.rows.firstOrNull() }
            if (row != null) {
                Text(row.title, style = TvTheme.type.headlineSmall, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(row.detail, style = TvTheme.type.bodyMedium, color = c.onSurfaceVariant)
                if (row.meta.isNotEmpty()) Text(row.meta, style = TvTheme.type.labelMedium, color = c.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Text("Press OK to open. The message preview arrives with the full Conversations screen.", style = TvTheme.type.bodyMedium, color = c.onSurfaceVariant)
            }
        }
    }
}
