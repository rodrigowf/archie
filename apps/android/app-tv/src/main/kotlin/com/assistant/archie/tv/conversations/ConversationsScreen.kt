package com.assistant.archie.tv.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.assistant.archie.tv.graph.TvAppGraph
import com.assistant.archie.tv.home.TextPreview
import com.assistant.archie.tv.shell.FocusRestoreEffect
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvOption
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.theme.FocusCard
import com.assistant.archie.tv.theme.FocusMemory
import com.assistant.archie.tv.theme.MetaChip
import com.assistant.archie.tv.theme.MetaChipView
import com.assistant.archie.tv.theme.ProviderPill
import com.assistant.archie.tv.theme.SessionState
import com.assistant.archie.tv.theme.TvEmptyState
import com.assistant.archie.tv.theme.TvTextInput
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.theme.focusMemory
import com.assistant.archie.tv.theme.rememberFocusMemory
import com.assistant.archie.tv.theme.sampled
import com.assistant.core.design.components.ArchieMark
import com.assistant.core.design.components.Spinner
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.markdown.ui.Markdown
import com.assistant.core.markdown.ui.MarkdownStyle
import com.assistant.core.network.ApiResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.ZoneId

/*
 * Conversations (spec 15 §3.5, mockup 5): two panes. Left: the search field (Fire TV keyboard on OK)
 * and the list grouped like the apps — Open now, Today, Yesterday, Previous 7 days, Earlier.
 * Right: a small preview of the focused row (title, harness, working directory, the last messages),
 * following focus. OK opens (a user action: it may resume); Menu offers Open, Rename, Duplicate,
 * Close, Delete, New agent and New Archie conversation through `:feature:sessions`' flows.
 */

class ConversationsActions(
    val open: (ConvRef) -> Unit = {},
    val optionsFor: (ConvRef) -> List<TvOption> = { emptyList() },
    val newAgent: () -> Unit = {},
    val newArchie: () -> Unit = {},
    val onFocusRow: (ConvRowUi) -> Unit = {},
)

@Composable
fun ConversationsRoute(ctx: TvRouteContext) {
    val g = ctx.graph
    val sessions = remember(g) { TvSessions.of(g) }
    var query by rememberSaveable { mutableStateOf("") }
    val flow = remember(g) { combine(g.openSessions.items.sampled(500), g.history.sessions) { a, b -> a to b } }
    val initial = remember(g) { g.openSessions.items.value to g.history.sessions.value }
    val data by flow.collectAsStateWithLifecycle(initial)
    val sections = remember(data, query) { ConversationsModel.sections(data.first, data.second.value.orEmpty(), query, Instant.now(), ZoneId.systemDefault()) }
    var focused by remember { mutableStateOf<ConvRowUi?>(null) }
    val preview by rememberPreview(g, focused)
    var picker by rememberSaveable { mutableStateOf(false) }
    ConversationsScreen(
        sections = sections,
        loading = data.second.loading,
        query = query,
        onQuery = { query = it },
        preview = preview,
        focused = focused,
        actions = ConversationsActions(
            open = { sessions.open(it.target, ctx.nav) },
            optionsFor = { conversationOptions(it, sessions, ctx.nav) },
            newAgent = { picker = true },
            newArchie = { sessions.newArchie(ctx.nav) },
            onFocusRow = { focused = it },
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

/** The preview of the focused row: fetched after focus rests 300 ms, cached for the process. */
@Composable
private fun rememberPreview(g: TvAppGraph, row: ConvRowUi?): State<PreviewUi> {
    val sdk = row?.sdkId
    return produceState(PreviewCache.get(sdk) ?: PreviewUi(loading = sdk != null), sdk) {
        if (sdk == null) {
            value = PreviewUi()
            return@produceState
        }
        PreviewCache.get(sdk)?.let {
            value = it
            return@produceState
        }
        value = PreviewUi(loading = true)
        delay(300)
        val msgs = g.api.preview(sdk, 12)
        val cfg = g.api.sessionConfig(sdk)
        val ui = when (msgs) {
            is ApiResult.Ok -> PreviewUi(lines = ConversationsModel.previewLines(msgs.value, 6), workingDir = (cfg as? ApiResult.Ok)?.value?.workingDirectory)
            else -> PreviewUi(error = msgs.errorMessage() ?: "Couldn't load the preview")
        }
        if (ui.error == null) PreviewCache.put(sdk, ui)
        value = ui
    }
}

/** A small LRU of previews (the list re-focuses the same rows often). */
private object PreviewCache {
    private val map = object : LinkedHashMap<String, PreviewUi>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreviewUi>?) = size > 30
    }

    @Synchronized fun get(sdk: String?): PreviewUi? = sdk?.let { map[it] }

    @Synchronized fun put(sdk: String, ui: PreviewUi) {
        map[sdk] = ui
    }
}

@Composable
fun ConversationsScreen(
    sections: List<ConvSectionUi>,
    loading: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    preview: PreviewUi,
    focused: ConvRowUi?,
    actions: ConversationsActions,
    modifier: Modifier = Modifier,
    memory: FocusMemory = rememberFocusMemory(),
    /** Goldens: the row key to focus first. */
    initialFocusKey: String? = null,
) {
    ScreenChromeEffect(
        ScreenChrome(
            title = "Conversations",
            hints = listOf(KeyHints.Ok("Open"), KeyHints.Menu, KeyHints.Mic, KeyHints.Back),
            options = buildList {
                focused?.let { addAll(actions.optionsFor(it.ref)) }
                add(TvOption("conv:new-agent", "New agent session", ArchieIcons.Terminal) { actions.newAgent() })
                add(TvOption("conv:new-archie", "New Archie conversation", ArchieIcons.AddComment) { actions.newArchie() })
            },
        ),
    )
    val firstRow = sections.firstOrNull()?.rows?.firstOrNull()?.key
    val search = remember { FocusRequester() }
    FocusRestoreEffect { memory.restore() }
    LaunchedEffect(firstRow != null) {
        withFrameNanos { }
        val key = initialFocusKey ?: firstRow
        if (!memory.restore()) {
            if (key != null) runCatching { memory.requester(key).requestFocus() } else runCatching { search.requestFocus() }
        }
    }
    Row(modifier.fillMaxSize().testTag("conversations")) {
        Column(Modifier.width(360.dp).fillMaxHeight()) {
            TvTextInput(
                value = query,
                onValueChange = onQuery,
                placeholder = "Search conversations",
                leadingIcon = ArchieIcons.Search,
                imeAction = ImeAction.Search,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).testTag("conversations-search"),
                pillRequester = search,
            )
            if (sections.isEmpty()) {
                TvEmptyState(
                    when {
                        loading -> "Loading conversations…"
                        query.isNotBlank() -> "Nothing matches “$query”"
                        else -> "No conversations yet"
                    },
                    if (query.isNotBlank()) "Every word must appear in the title." else "Hold the mic button to talk to Archie.",
                    Modifier.testTag("conversations-empty"),
                    icon = ArchieIcons.Forum,
                )
            } else {
                ConversationList(sections, memory, actions, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.width(24.dp))
        PreviewPane(focused, preview, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun ConversationList(sections: List<ConvSectionUi>, memory: FocusMemory, actions: ConversationsActions, modifier: Modifier) {
    val listState = rememberLazyListState()
    LazyColumn(
        modifier.fillMaxWidth().focusRestorer().testTag("conversations-list"),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 24.dp),
    ) {
        for (section in sections) {
            item(key = "h:${section.title}", contentType = "header") {
                Text(
                    section.title,
                    style = TvTheme.type.labelMedium,
                    color = TvTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
                )
            }
            items(section.rows, key = { it.key }, contentType = { "row" }) { row ->
                ConversationRowView(row, memory, actions)
            }
        }
    }
}

@Composable
private fun ConversationRowView(row: ConvRowUi, memory: FocusMemory, actions: ConversationsActions) {
    val c = TvTheme.colors
    FocusCard(
        onClick = { actions.open(row.ref) },
        modifier = Modifier
            .fillMaxWidth()
            .focusMemory(memory, row.key)
            .onFocusChanged { if (it.isFocused) actions.onFocusRow(row) }
            .testTag("row-${row.key}"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
        containerColor = Color.Transparent,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.archie) {
                ArchieMark(size = 32.dp)
            } else {
                Box(Modifier.size(32.dp).background(c.surfaceContainerHigh, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(ArchieIcons.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.title, style = TvTheme.type.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val muted = LocalContentColor.current.copy(alpha = 0.72f)
                    if (row.state == SessionState.WORKING) {
                        Text("${row.who} · ", style = TvTheme.type.labelMedium, color = muted, maxLines = 1)
                        Spinner(size = 12.dp, strokeWidth = 2.dp)
                        Text(" working", style = TvTheme.type.labelMedium, color = muted, maxLines = 1)
                    } else {
                        Text(
                            ConversationsModel.metaOf(row),
                            style = TvTheme.type.labelMedium,
                            color = if (row.state == SessionState.NEEDS_APPROVAL) ArchieTheme.extended.warning.color else muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewPane(row: ConvRowUi?, preview: PreviewUi, modifier: Modifier) {
    val c = TvTheme.colors
    Column(
        modifier
            .background(c.surfaceContainerLow, RoundedCornerShape(16.dp))
            .padding(horizontal = 26.dp, vertical = 20.dp)
            .clipToBounds()
            .testTag("conversation-preview"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (row == null) {
            Text("Move to a conversation to preview it here.", style = TvTheme.type.bodyMedium, color = c.onSurfaceVariant)
            return@Column
        }
        Text(row.title, style = TvTheme.type.titleLarge, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (row.archie) {
                ArchieMark(size = 22.dp)
                Text("Archie", style = TvTheme.type.labelMedium, color = c.onSurfaceVariant)
            } else {
                ProviderPill(row.provider ?: row.who)
            }
            preview.workingDir?.let { MetaChipView(MetaChip(shortDir(it), mono = true), Modifier.weight(1f, fill = false)) }
        }
        val meta = listOfNotNull(
            row.time.ifEmpty { null },
            row.messageCount?.let { if (it == 1) "1 message" else "$it messages" },
            row.state?.let { ConversationsModel.metaOf(row).substringAfter(" · ", "") }?.ifEmpty { null },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = TvTheme.type.labelMedium, color = c.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.size(4.dp))
        val sdk = row.sdkId
        when {
            sdk == null -> Text("No messages yet: this conversation starts with its first reply.", style = TvTheme.type.bodyMedium, color = c.onSurfaceVariant)
            preview.loading -> Spinner(size = 20.dp)
            preview.error != null -> Text(preview.error, style = TvTheme.type.bodyMedium, color = c.onSurfaceVariant)
            preview.lines.isEmpty() -> Text("No messages to show.", style = TvTheme.type.bodyMedium, color = c.onSurfaceVariant)
            else -> PreviewLines(preview.lines, sdk)
        }
    }
}

/** "~/assistant-wt/tv" for a home path. */
private fun shortDir(p: String): String = p.replace(Regex("^/home/[^/]+"), "~")

@Composable
private fun PreviewLines(lines: List<PreviewLine>, sdk: String) {
    val c = TvTheme.colors
    val small = TvTheme.type.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val style = MarkdownStyle.fromTheme().copy(
        body = small,
        textColor = c.onSurface,
        headings = List(6) { small.copy(fontWeight = FontWeight.W600) },
        code = TvTheme.type.code.copy(fontSize = 13.sp, lineHeight = 18.sp),
        blockSpacing = 4.dp,
        headingSpacing = 6.dp,
        codeMaxHeight = 120.dp,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEachIndexed { i, l ->
            when (l) {
                is PreviewLine.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        TextPreview.plain(l.text),
                        style = small,
                        color = c.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 420.dp)
                            .background(c.surfaceContainerHigh, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 5.dp))
                            .padding(horizontal = 13.dp, vertical = 6.dp),
                    )
                }
                is PreviewLine.Assistant -> Box(Modifier.fillMaxWidth().heightIn(max = 120.dp).clipToBounds()) {
                    Markdown(l.text, style = style, cacheId = "pv:$sdk:$i", syncLimit = 1_200)
                }
                is PreviewLine.Steps -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ArchieIcons.KeyboardArrowDown, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (l.count == 1) "1 step" else "${l.count} steps", style = TvTheme.type.labelMedium, color = c.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Text(ConversationsModel.stepsSummary(l.names), style = small, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
