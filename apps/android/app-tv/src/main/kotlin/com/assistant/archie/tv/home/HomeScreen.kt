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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.assistant.archie.feature.sessions.SessionTarget
import com.assistant.archie.tv.conversations.ConvRef
import com.assistant.archie.tv.conversations.NewAgentSheet
import com.assistant.archie.tv.conversations.OpenTarget
import com.assistant.archie.tv.conversations.SessionFlowsHost
import com.assistant.archie.tv.conversations.TvSessions
import com.assistant.archie.tv.conversations.conversationOptions
import com.assistant.archie.tv.shell.FocusRestoreEffect
import com.assistant.archie.tv.shell.HintGlyph
import com.assistant.archie.tv.shell.KeyHint
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvKey
import com.assistant.archie.tv.shell.TvKeyHandler
import com.assistant.archie.tv.shell.TvOption
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.theme.FocusCard
import com.assistant.archie.tv.theme.FocusMemory
import com.assistant.archie.tv.theme.ProviderPill
import com.assistant.archie.tv.theme.SessionState
import com.assistant.archie.tv.theme.SessionStatusLine
import com.assistant.archie.tv.theme.StatusDot
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvButtonStyle
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.theme.focusMemory
import com.assistant.archie.tv.theme.rememberFocusMemory
import com.assistant.core.design.components.ArchieMark
import com.assistant.core.design.components.Spinner
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import kotlinx.coroutines.launch

/*
 * Home (spec 15 §3.5, mockups 3 / 3b): the hero (current Archie conversation) and four rows — Open
 * now, Visuals, Memory, Recent conversations. Every row is a LazyRow with focusRestorer(); the
 * screen remembers the card that had focus (FocusMemory) so coming back from a conversation or a
 * viewer lands on it. Rewind / Fast-forward jump a row. HomeScreen is state-driven (goldens).
 */

class HomeActions(
    val open: (ConvRef) -> Unit = {},
    val push: (NavKey) -> Unit = {},
    val liveVoice: () -> Unit = {},
    val newConversation: () -> Unit = {},
    val newAgent: () -> Unit = {},
    val refresh: () -> Unit = {},
    /** Menu options for a focused conversation card. */
    val optionsFor: (ConvRef) -> List<TvOption> = { emptyList() },
)

@Composable
fun HomeRoute(ctx: TvRouteContext) {
    val g = ctx.graph
    val sessions = remember(g) { TvSessions.of(g) }
    val state by rememberHomeState(g)
    var picker by rememberSaveable { mutableStateOf(false) }
    HomeScreen(
        state,
        HomeActions(
            open = { ref -> sessions.open(ref.target, ctx.nav) },
            push = ctx.nav::push,
            liveVoice = { g.liveVoice.toggle() },
            newConversation = { sessions.newArchie(ctx.nav) },
            newAgent = { picker = true },
            refresh = {
                g.history.refreshAll()
                g.visuals.refresh()
                g.memory.refreshTree()
            },
            optionsFor = { ref -> conversationOptions(ref, sessions, ctx.nav) },
        ),
    )
    SessionFlowsHost(sessions)
    if (picker) {
        NewAgentSheet(sessions, onPick = { p ->
            picker = false
            sessions.newAgent(p, ctx.nav)
        }, onClose = { picker = false })
    }
}

/** The rows of the LazyColumn, in order (the hero is first). */
enum class HomeRow { HERO, OPEN_NOW, VISUALS, MEMORY, RECENT }

@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    memory: FocusMemory = rememberFocusMemory(),
    /** Goldens: the card key to focus instead of the hero's Open. */
    initialFocusKey: String? = null,
) {
    val scope = rememberCoroutineScope()
    val rows = HomeRow.entries.filter { r ->
        when (r) {
            HomeRow.HERO, HomeRow.RECENT -> true
            HomeRow.OPEN_NOW -> state.openNow.isNotEmpty()
            HomeRow.VISUALS -> state.visuals.isNotEmpty() || state.loading
            HomeRow.MEMORY -> state.memory.isNotEmpty() || state.loading
        }
    }
    val rowRequesters = remember { HomeRow.entries.associateWith { FocusRequester() } }
    var focusedRow by rememberSaveable { mutableStateOf(HomeRow.HERO) }
    var focusedConv by remember { mutableStateOf<ConvRef?>(null) }

    fun jump(delta: Int): Boolean {
        val i = rows.indexOf(focusedRow).coerceAtLeast(0)
        val target = rows.getOrNull(i + delta) ?: return true
        scope.launch {
            listState.animateScrollToItem(rows.indexOf(target))
            runCatching { rowRequesters.getValue(target).requestFocus() }
        }
        return true
    }

    val inRows = focusedRow != HomeRow.HERO
    ScreenChromeEffect(
        ScreenChrome(
            title = "",
            hints = listOf(
                KeyHints.Mic,
                if (inRows) KeyHints.Page("Jump a row") else KeyHints.LiveVoice,
                KeyHints.Menu,
                KeyHint(HintGlyph.BACK, "Exit"),
            ),
            options = buildList {
                focusedConv?.let { addAll(actions.optionsFor(it)) }
                add(TvOption("home:new-archie", "New conversation", ArchieIcons.AddComment) { actions.newConversation() })
                add(TvOption("home:new-agent", "New agent session", ArchieIcons.Terminal) { actions.newAgent() })
                add(TvOption("home:refresh", "Refresh", ArchieIcons.Refresh) { actions.refresh() })
            },
            keys = TvKeyHandler { key ->
                when (key) {
                    TvKey.REWIND -> jump(-1)
                    TvKey.FAST_FORWARD -> jump(1)
                    else -> false
                }
            },
        ),
    )

    val heroOpen = rowRequesters.getValue(HomeRow.HERO)
    FocusRestoreEffect { memory.restore() }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        val restored = (initialFocusKey != null && runCatching { memory.requester(initialFocusKey).requestFocus() }.isSuccess) || memory.restore()
        if (!restored) runCatching { heroOpen.requestFocus() }
    }

    fun Modifier.trackRow(row: HomeRow): Modifier = onFocusChanged { if (it.hasFocus) focusedRow = row }

    LazyColumn(
        modifier.fillMaxSize().testTag("home"),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp, start = 4.dp, end = 4.dp),
    ) {
        item(key = "hero", contentType = "hero") {
            Hero(state.hero, actions, heroOpen, memory, Modifier.trackRow(HomeRow.HERO).onFocusChanged { if (it.hasFocus) focusedConv = null })
        }
        if (HomeRow.OPEN_NOW in rows) {
            item(key = "open-now", contentType = "row") {
                RowSection("Open now", trailing = agentsLabel(state.openNow.size)) {
                    CardRow(state.openNow, { it.key }, rowRequesters.getValue(HomeRow.OPEN_NOW), Modifier.trackRow(HomeRow.OPEN_NOW)) { c ->
                        AgentCard(c, memory, { actions.open(c.ref) }, { focusedConv = c.ref })
                    }
                }
            }
        }
        if (HomeRow.VISUALS in rows) {
            item(key = "visuals", contentType = "row") {
                RowSection("Visuals", trailing = if (state.visuals.isEmpty()) "Loading…" else null) {
                    CardRow(state.visuals, { it.key }, rowRequesters.getValue(HomeRow.VISUALS), Modifier.trackRow(HomeRow.VISUALS)) { c ->
                        VisualCard(c, memory, { actions.push(c.target) }, { focusedConv = null })
                    }
                }
            }
        }
        if (HomeRow.MEMORY in rows) {
            item(key = "memory", contentType = "row") {
                RowSection("Memory", trailing = if (state.memory.isEmpty()) "Loading…" else "recently changed") {
                    CardRow(state.memory, { it.key }, rowRequesters.getValue(HomeRow.MEMORY), Modifier.trackRow(HomeRow.MEMORY)) { c ->
                        MemoryCard(c, memory, { actions.push(c.target) }, { focusedConv = null })
                    }
                }
            }
        }
        item(key = "recent", contentType = "row") {
            RowSection("Recent conversations", trailing = if (state.loading && state.recent.isEmpty()) "Loading…" else null) {
                if (state.recent.isEmpty()) {
                    Text(
                        if (state.loading) "" else "No conversations yet. Hold the mic button and talk to Archie.",
                        style = TvTheme.type.bodyMedium,
                        color = TvTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                    )
                } else {
                    CardRow(state.recent, { it.key }, rowRequesters.getValue(HomeRow.RECENT), Modifier.trackRow(HomeRow.RECENT)) { c ->
                        RecentCard(c, memory, { actions.open(c.ref) }, { focusedConv = c.ref })
                    }
                }
            }
        }
    }
}

private fun agentsLabel(n: Int) = if (n == 1) "1 agent" else "$n agents"

@Composable
private fun RowSection(title: String, trailing: String?, content: @Composable () -> Unit) {
    val c = TvTheme.colors
    Column {
        Row(Modifier.padding(start = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, style = TvTheme.type.titleMedium, color = c.onSurface)
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                Text(trailing, style = TvTheme.type.labelMedium, color = c.onSurfaceVariant)
            }
        }
        content()
    }
}

@Composable
private fun <T> CardRow(
    cards: List<T>,
    key: (T) -> String,
    requester: FocusRequester,
    modifier: Modifier,
    card: @Composable (T) -> Unit,
) {
    LazyRow(
        modifier.focusRequester(requester).focusRestorer(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp),
    ) {
        items(cards, key = key) { card(it) }
    }
}

private val ARCHIE_TARGET = SessionTarget(sdkId = null, localId = null, title = "Archie", isArchie = true)

@Composable
private fun Hero(hero: HeroUi, actions: HomeActions, openRequester: FocusRequester, memory: FocusMemory, modifier: Modifier) {
    val c = TvTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(0f to c.primaryContainer.copy(alpha = 0.55f), 0.62f to c.surfaceContainer), shape)
            .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 20.dp)
            .testTag("home-hero"),
    ) {
        ArchieMark(size = 56.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Archie · current conversation", style = TvTheme.type.labelMedium, color = c.onSurfaceVariant, maxLines = 1)
                if (hero.hasConversation) {
                    Text(" · ", style = TvTheme.type.labelMedium, color = c.onSurfaceVariant)
                    HeroState(hero.state, hero.stateLabel)
                    hero.time?.let { Text(" · $it", style = TvTheme.type.labelMedium, color = c.onSurfaceVariant, maxLines = 1) }
                }
            }
            Text(hero.title, style = TvTheme.type.headlineSmall, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                hero.lastReply.ifEmpty {
                    if (hero.hasConversation) "No reply yet." else "No conversation is open. Start one, or hold the mic button and talk."
                },
                style = TvTheme.type.bodyMedium,
                color = c.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp).fillMaxWidth(0.92f),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvButton(
                    "Open",
                    { actions.open(ConvRef(OpenTarget.ArchieCurrent, ARCHIE_TARGET, null)) },
                    Modifier.focusRequester(openRequester).focusMemory(memory, "hero:open").testTag("hero-open"),
                    icon = ArchieIcons.Forum,
                    style = TvButtonStyle.Primary,
                )
                TvButton("Live voice", actions.liveVoice, Modifier.focusMemory(memory, "hero:voice").testTag("hero-voice"), icon = ArchieIcons.GraphicEq)
                TvButton("New conversation", actions.newConversation, Modifier.focusMemory(memory, "hero:new").testTag("hero-new"), icon = ArchieIcons.AddComment)
                Spacer(Modifier.weight(1f))
                Icon(ArchieIcons.Mic, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Hold to talk", style = TvTheme.type.labelMedium, color = c.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun HeroState(state: SessionState, label: String) {
    val c = TvTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            SessionState.WORKING, SessionState.CONNECTING -> Spinner(size = 13.dp, strokeWidth = 2.dp)
            SessionState.NEEDS_APPROVAL -> Box(Modifier.size(9.dp).background(ArchieTheme.extended.warning.color, CircleShape))
            else -> StatusDot()
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = TvTheme.type.labelMedium, color = if (state == SessionState.WORKING) c.primary else c.onSurfaceVariant, maxLines = 1)
    }
}

private val CardHeight: Dp = 106.dp

@Composable
private fun AgentCard(c: AgentCardUi, memory: FocusMemory, onOpen: () -> Unit, onFocus: () -> Unit) {
    FocusCard(
        onClick = onOpen,
        modifier = Modifier.width(250.dp).height(CardHeight).focusMemory(memory, c.key).onFocusChanged { if (it.isFocused) onFocus() }.testTag("card-${c.key}"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (c.provider != null) ProviderPill(c.provider)
            Spacer(Modifier.weight(1f))
            SessionStatusLine(c.state)
        }
        Spacer(Modifier.height(6.dp))
        Text(c.title, style = TvTheme.type.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Muted(c.lastLine)
    }
}

@Composable
private fun VisualCard(c: VisualCardUi, memory: FocusMemory, onOpen: () -> Unit, onFocus: () -> Unit) {
    val t = TvTheme.colors
    FocusCard(
        onClick = onOpen,
        modifier = Modifier.width(220.dp).height(CardHeight).focusMemory(memory, c.key).onFocusChanged { if (it.isFocused) onFocus() }.testTag("card-${c.key}"),
        contentPadding = PaddingValues(0.dp),
    ) {
        val kindColor = c.kind.color
        Box(Modifier.fillMaxWidth().height(7.dp).background(kindColor))
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.kind.label.uppercase(), style = TvTheme.type.labelMedium, color = kindColor, maxLines = 1, modifier = Modifier.weight(1f))
                if (c.changed) Box(Modifier.size(10.dp).background(t.primary, CircleShape).testTag("changed-dot"))
            }
            Text(c.title, style = TvTheme.type.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                c.file,
                style = TvTheme.type.code.copy(fontSize = TvTheme.type.labelMedium.fontSize, lineHeight = TvTheme.type.labelMedium.lineHeight),
                color = LocalContentColor.current.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            c.updated?.let { Text(it, style = TvTheme.type.labelMedium, color = LocalContentColor.current.copy(alpha = 0.72f), maxLines = 1) }
        }
    }
}

@Composable
private fun MemoryCard(c: MemoryCardUi, memory: FocusMemory, onOpen: () -> Unit, onFocus: () -> Unit) {
    FocusCard(
        onClick = onOpen,
        modifier = Modifier.width(280.dp).height(CardHeight).focusMemory(memory, c.key).onFocusChanged { if (it.isFocused) onFocus() }.testTag("card-${c.key}"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ArchieIcons.Folder, contentDescription = null, modifier = Modifier.size(16.dp), tint = LocalContentColor.current.copy(alpha = 0.72f))
            Spacer(Modifier.width(6.dp))
            Text(c.folder, style = TvTheme.type.labelMedium, color = LocalContentColor.current.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Text(c.title, style = TvTheme.type.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Muted(c.detail)
    }
}

@Composable
private fun RecentCard(c: RecentCardUi, memory: FocusMemory, onOpen: () -> Unit, onFocus: () -> Unit) {
    FocusCard(
        onClick = onOpen,
        modifier = Modifier.width(290.dp).height(CardHeight).focusMemory(memory, c.key).onFocusChanged { if (it.isFocused) onFocus() }.testTag("card-${c.key}"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (c.archie) {
                ArchieMark(size = 22.dp)
                Spacer(Modifier.width(7.dp))
                Text("Archie", style = TvTheme.type.labelMedium, color = LocalContentColor.current.copy(alpha = 0.72f))
            } else if (c.provider != null) {
                ProviderPill(c.provider)
            }
            Spacer(Modifier.weight(1f))
            Text(c.time, style = TvTheme.type.labelMedium, color = LocalContentColor.current.copy(alpha = 0.72f), maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Text(c.title, style = TvTheme.type.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Muted(c.detail)
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = TvTheme.type.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
}
