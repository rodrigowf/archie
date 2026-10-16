package com.assistant.archie.tv.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.tv.material3.Text
import com.assistant.archie.feature.sessions.SessionTitles
import com.assistant.archie.tv.shell.Conversation
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.MemoryDoc
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvKey
import com.assistant.archie.tv.shell.TvKeyHandler
import com.assistant.archie.tv.shell.TvOption
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.shell.Viewer
import com.assistant.archie.tv.theme.CardMeta
import com.assistant.archie.tv.theme.CardWidth
import com.assistant.archie.tv.theme.FocusCard
import com.assistant.archie.tv.theme.RowHeader
import com.assistant.archie.tv.theme.TvBadge
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvButtonStyle
import com.assistant.archie.tv.theme.TvFormat
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.theme.rememberInitialFocus
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.data.ItemKind
import com.assistant.core.data.LoadState
import com.assistant.core.data.WorkspaceItem
import com.assistant.core.design.components.ArchieMark
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.model.LiveStatus
import com.assistant.core.model.MemoryNode
import com.assistant.core.model.PoolSession
import com.assistant.core.model.SessionSummary
import com.assistant.core.model.VisualInfo
import kotlinx.coroutines.launch
import java.time.Instant

/*
 * Home (spec 15 §3.5) — T0 placeholder with real data: hero (the Archie conversation), Open now
 * (pool), Visuals, Memory, Recent conversations. Every row is a LazyRow with focusRestorer().
 * T1 builds the real screen (reply preview lines, needs-approval badges, live-reload dots, actions);
 * keep HomeScreen state-driven so goldens stay graph-free.
 */

@Immutable
data class HomeCard(val title: String, val detail: String, val key: NavKey, val badge: String? = null, val busy: Boolean = false)

@Immutable
data class HomeUiState(
    val archieTitle: String = "Archie",
    val archieDetail: String = "",
    val openNow: List<HomeCard> = emptyList(),
    val visuals: List<HomeCard> = emptyList(),
    val memory: List<HomeCard> = emptyList(),
    val recent: List<HomeCard> = emptyList(),
    val loading: Boolean = false,
)

class HomeActions(
    val open: (NavKey) -> Unit = {},
    val liveVoice: () -> Unit = {},
    val newConversation: () -> Unit = {},
    val refresh: () -> Unit = {},
)

/** Graph → [HomeUiState] (pure). */
object HomeModel {
    const val ROW_LIMIT = 12

    fun state(
        items: List<WorkspaceItem>,
        pool: List<PoolSession>,
        visuals: LoadState<List<VisualInfo>>,
        tree: LoadState<List<MemoryNode>>,
        sessions: LoadState<List<SessionSummary>>,
        titleFor: (PoolSession) -> String,
        now: Instant = Instant.now(),
    ): HomeUiState {
        val archie = items.firstOrNull { it.kind == ItemKind.ARCHIE }
        return HomeUiState(
            archieTitle = archie?.title?.let { SessionTitles.conversationTitle(it, isArchie = true) } ?: "Archie",
            archieDetail = archie?.detail.orEmpty(),
            openNow = pool.filter { !it.isOrchestrator }.take(ROW_LIMIT).map { p ->
                HomeCard(
                    title = titleFor(p),
                    detail = statusText(p.status) + if (p.turns > 0) " · ${p.turns} turns" else "",
                    key = Conversation(localId = p.localId, sdkId = p.sdkId),
                    busy = p.status.isWorking(),
                )
            },
            visuals = visuals.value.orEmpty().take(ROW_LIMIT).map { v ->
                HomeCard(v.title.ifBlank { TvFormat.titleOf(v.path) }, TvFormat.ago(v.modified, now)?.let { "Updated $it" } ?: v.path, Viewer(v.path, v.title))
            },
            memory = files(tree.value.orEmpty()).take(ROW_LIMIT).map { n ->
                HomeCard(TvFormat.titleOf(n.path), TvFormat.folderOf(n.path).ifEmpty { "memory" }, MemoryDoc(n.path))
            },
            recent = sessions.value.orEmpty()
                .sortedByDescending { TvFormat.parse(it.lastActivity ?: it.startedAt) }
                .take(ROW_LIMIT)
                .map { s ->
                    HomeCard(
                        title = SessionTitles.conversationTitle(s.title, s.isOrchestrator),
                        detail = listOfNotNull(if (s.isOrchestrator) "Archie" else s.provider?.label, TvFormat.ago(s.lastActivity ?: s.startedAt, now)).joinToString(" · "),
                        key = Conversation(localId = s.localId, sdkId = s.sdkId, archie = s.isOrchestrator),
                    )
                },
            loading = visuals.loading || tree.loading || sessions.loading,
        )
    }

    /** Memory files, depth-first, skipping index files. */
    fun files(nodes: List<MemoryNode>): List<MemoryNode> = buildList {
        fun walk(list: List<MemoryNode>) {
            for (n in list) if (n.isDir) walk(n.children.orEmpty()) else if (!n.name.equals("INDEX.md", ignoreCase = true)) add(n)
        }
        walk(nodes)
    }

    private fun LiveStatus?.isWorking() = this == LiveStatus.STREAMING || this == LiveStatus.TOOL_USE || this == LiveStatus.THINKING

    private fun statusText(s: LiveStatus?): String = when (s) {
        LiveStatus.STREAMING, LiveStatus.TOOL_USE, LiveStatus.THINKING -> "Working"
        LiveStatus.INTERRUPTED -> "Interrupted"
        LiveStatus.DISCONNECTED -> "Disconnected"
        LiveStatus.IDLE, null -> "Idle"
    }
}

@Composable
fun HomeRoute(ctx: TvRouteContext) {
    val g = ctx.graph
    val connected = g.connection.status.collectAsStateWithLifecycle().value.phase == ConnectionStatus.Phase.CONNECTED
    LaunchedEffect(connected) {
        if (connected) {
            g.visuals.refresh()
            g.memory.refreshTree()
        }
    }
    val items by g.openSessions.items.collectAsStateWithLifecycle()
    val pool by g.history.pool.collectAsStateWithLifecycle()
    val visuals by g.visuals.list.collectAsStateWithLifecycle()
    val tree by g.memory.tree.collectAsStateWithLifecycle()
    val sessions by g.history.sessions.collectAsStateWithLifecycle()
    val state = remember(items, pool, visuals, tree, sessions) {
        HomeModel.state(items, pool, visuals, tree, sessions, titleFor = { p -> g.history.titleFor(p.sdkId, p.localId, p.title ?: "Agent session") })
    }
    HomeScreen(
        state,
        HomeActions(
            open = ctx.nav::push,
            liveVoice = { g.liveVoice.toggle() },
            newConversation = { ctx.toasts.show("New conversations arrive with the Conversations screen (T1)") },
            refresh = {
                g.history.refreshAll()
                g.visuals.refresh()
                g.memory.refreshTree()
            },
        ),
    )
}

@Composable
fun HomeScreen(state: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rows = listOf(state.openNow, state.visuals, state.memory, state.recent)
    // Row index (LazyColumn item) that holds focus; 0 = hero.
    var focusedRow by remember { mutableIntStateOf(0) }
    val requesters = remember { List(5) { FocusRequester() } }
    fun jump(delta: Int): Boolean {
        val target = (focusedRow + delta).coerceIn(0, rows.size)
        if (target == focusedRow) return true
        val index = (0..rows.size).filter { it == 0 || rows[it - 1].isNotEmpty() }
        val next = if (delta > 0) index.firstOrNull { it > focusedRow } else index.lastOrNull { it < focusedRow }
        next ?: return true
        scope.launch {
            list.animateScrollToItem(next)
            runCatching { requesters[next].requestFocus() }
        }
        return true
    }
    ScreenChromeEffect(
        ScreenChrome(
            title = "Home",
            hints = listOf(KeyHints.Ok("Open"), KeyHints.Mic, KeyHints.LiveVoice, KeyHints.Menu),
            options = listOf(
                TvOption("new-archie", "New conversation", ArchieIcons.AddComment) { actions.newConversation() },
                TvOption("refresh", "Refresh", ArchieIcons.Refresh) { actions.refresh() },
            ),
            keys = TvKeyHandler { key ->
                when (key) {
                    TvKey.REWIND -> jump(-1)
                    TvKey.FAST_FORWARD -> jump(1)
                    else -> false
                }
            },
        ),
    )
    val initial = rememberInitialFocus()
    LazyColumn(
        modifier.fillMaxSize().testTag("home"),
        state = list,
        verticalArrangement = Arrangement.spacedBy(26.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp, start = 6.dp, end = 6.dp),
    ) {
        item(key = "hero") {
            Hero(state, actions, Modifier.focusRequester(requesters[0]).onFocusChanged { if (it.hasFocus) focusedRow = 0 }, initial)
        }
        val titles = listOf("Open now", "Visuals", "Memory", "Recent conversations")
        val empties = listOf(null, "No visualizations yet", "No memory notes yet", "No conversations yet")
        for ((i, cards) in rows.withIndex()) {
            if (i == 0 && cards.isEmpty()) continue // Open now only when agents run
            item(key = "row-$i") {
                Column {
                    RowHeader(titles[i], trailing = if (state.loading && cards.isEmpty()) "Loading…" else if (cards.isNotEmpty()) "${cards.size}" else null)
                    if (cards.isEmpty()) {
                        Text(empties[i].orEmpty(), style = TvTheme.type.bodyMedium, color = TvTheme.colors.onSurfaceVariant)
                    } else {
                        CardRow(
                            cards, actions.open,
                            Modifier.focusRequester(requesters[i + 1]).focusRestorer().onFocusChanged { if (it.hasFocus) focusedRow = i + 1 },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(state: HomeUiState, actions: HomeActions, modifier: Modifier, initial: FocusRequester) {
    val c = TvTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.surfaceContainerLow, RoundedCornerShape(24.dp))
            .padding(22.dp)
            .testTag("home-hero"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArchieMark(size = 72.dp)
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text(state.archieTitle, style = TvTheme.type.headlineSmall, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                state.archieDetail.ifEmpty { "Hold the mic button anywhere to talk to Archie" },
                style = TvTheme.type.bodyMedium,
                color = c.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvButton("Open", { actions.open(Conversation(archie = true)) }, Modifier.focusRequester(initial).testTag("hero-open"), icon = ArchieIcons.Forum, style = TvButtonStyle.Primary)
                TvButton("Live voice", actions.liveVoice, icon = ArchieIcons.GraphicEq)
                TvButton("New conversation", actions.newConversation, icon = ArchieIcons.AddComment)
            }
        }
    }
}

@Composable
private fun CardRow(cards: List<HomeCard>, onOpen: (NavKey) -> Unit, modifier: Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)) {
        items(cards, key = { it.key.toString() }) { card ->
            FocusCard(onClick = { onOpen(card.key) }, modifier = Modifier.width(CardWidth).height(120.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (card.busy) Box(Modifier.size(8.dp).background(TvTheme.colors.primary, RoundedCornerShape(50)))
                    if (card.busy) Spacer(Modifier.width(8.dp))
                    Text(card.title, style = TvTheme.type.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                if (card.badge != null) TvBadge(card.badge)
                CardMeta(card.detail)
            }
        }
    }
}
