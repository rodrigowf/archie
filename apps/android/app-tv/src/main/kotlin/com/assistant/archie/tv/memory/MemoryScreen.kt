package com.assistant.archie.tv.memory

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.assistant.archie.feature.memory.MemoryRow
import com.assistant.archie.feature.memory.filterMemory
import com.assistant.archie.feature.memory.flatten
import com.assistant.archie.feature.memory.folderCrumb
import com.assistant.archie.feature.memory.folderIds
import com.assistant.archie.feature.memory.modifiedLine
import com.assistant.archie.feature.memory.pinIndexFirst
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.MemoryDoc
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvOption
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.theme.FocusCard
import com.assistant.archie.tv.theme.TvBadge
import com.assistant.archie.tv.theme.TvEmptyState
import com.assistant.archie.tv.theme.TvFormat
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.visuals.TvSearchBox
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.markdown.Frontmatter
import com.assistant.core.markdown.InternalLinks
import com.assistant.core.markdown.MdInline
import com.assistant.core.markdown.MdNode
import com.assistant.core.model.MemoryNode
import com.assistant.core.network.ApiResult
import com.assistant.core.network.UrlScheme
import kotlinx.coroutines.delay

/*
 * Memory (spec 15 §3.5, mockup 13): two panes. Left: search + the tree (OK expands / collapses a
 * folder or opens a note full screen; folders show their note count). Right: a preview of the
 * focused note (a folder previews its INDEX.md) — path, title, folder / modified chips, the first
 * blocks — with an "Updated" chip when the note changes while you look at it (spec 12 §9.3).
 */

/** One visible tree row (pure flattening of the expanded tree), kept for the T0 tests. */
@Immutable
data class TreeRow(val node: MemoryNode, val depth: Int, val expanded: Boolean)

object MemoryTree {
    fun rows(nodes: List<MemoryNode>, expanded: Set<String>, depth: Int = 0): List<TreeRow> = buildList {
        for (n in nodes.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))) {
            val open = n.isDir && n.path in expanded
            add(TreeRow(n, depth, open))
            if (open) addAll(rows(n.children.orEmpty(), expanded, depth + 1))
        }
    }

    /** The visible rows: MEMORY.md first, then the tree; a search shows every match with its folders open. */
    fun visible(tree: List<MemoryNode>, expanded: Set<String>, query: String): List<MemoryRow> {
        val filtered = pinIndexFirst(filterMemory(tree, query))
        return flatten(filtered, if (query.isBlank()) expanded else folderIds(filtered))
    }

    /** What the preview shows for a focused row: the note itself, or a folder's INDEX.md. */
    fun previewPath(row: MemoryRow?, tree: List<MemoryNode>): String? {
        row ?: return null
        if (!row.isDir) return row.path
        fun find(list: List<MemoryNode>): MemoryNode? {
            for (n in list) {
                if (n.path == row.path) return n
                if (n.isDir) find(n.children.orEmpty())?.let { return it }
            }
            return null
        }
        return find(tree)?.children?.firstOrNull { !it.isDir && it.name.equals("INDEX.md", ignoreCase = true) }?.path
    }
}

/** The preview pane (mockup 13 right side). */
@Immutable
data class MemoryPreview(
    val path: String,
    val title: String,
    val chips: List<String>,
    val blocks: List<TvBlock>,
    val updated: Boolean = false,
)

object MemoryPreviews {
    /** Title from the first `# heading` (removed from the blocks), else the file name. */
    fun of(path: String, markdown: String, blocks: List<TvBlock>, updated: Boolean = false): MemoryPreview {
        val split = Frontmatter.split(markdown)
        val first = blocks.firstOrNull()
        val h1 = (first?.node as? MdNode.Heading)?.takeIf { it.level == 1 }
        val title = h1?.let { plain(it.inlines) }?.takeIf { it.isNotBlank() } ?: TvFormat.titleOf(path)
        val chips = listOfNotNull(
            folderCrumb(path).takeIf { it.isNotEmpty() },
            split.frontmatter?.let { modifiedLine(it) },
        )
        return MemoryPreview(path, title, chips, if (h1 != null) blocks.drop(1) else blocks, updated)
    }

    private fun plain(inlines: List<MdInline>): String = TvMarkdown.plain(TvBlock(MdNode.Paragraph(inlines), listOf(TvSeg.Text(inlines)), emptyList()))
}

@Immutable
data class MemoryUi(
    val rows: List<MemoryRow>,
    val loading: Boolean = false,
    val query: String = "",
    val preview: MemoryPreview? = null,
)

class MemoryActions(
    val onToggle: (String) -> Unit = {},
    val onFocus: (MemoryRow) -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onCollapseAll: () -> Unit = {},
)

@Composable
fun MemoryRoute(ctx: TvRouteContext) {
    val g = ctx.graph
    LaunchedEffect(Unit) { g.memory.refreshTree() }
    val tree by g.memory.tree.collectAsStateWithLifecycle()
    val stamps by g.content.memory.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var focusedPath by rememberSaveable { mutableStateOf<String?>(null) }
    val nodes = tree.value.orEmpty()
    val rows = remember(nodes, expanded, query) { MemoryTree.visible(nodes, expanded, query) }
    val previewPath = remember(focusedPath, rows, nodes) { MemoryTree.previewPath(rows.firstOrNull { it.path == focusedPath }, nodes) }
    val origin = remember { UrlScheme.httpBase(g.currentServerUrl()) }
    val version = previewPath?.let { stamps[it]?.version } ?: 0

    var preview by remember { mutableStateOf<MemoryPreview?>(null) }
    var shownPath by remember { mutableStateOf<String?>(null) }
    var shownText by remember { mutableStateOf<String?>(null) }
    var updated by remember { mutableIntStateOf(0) }
    LaunchedEffect(previewPath, version) {
        val p = previewPath ?: run { preview = null; shownPath = null; return@LaunchedEffect }
        delay(PREVIEW_DEBOUNCE_MS) // moving through the tree fetches only where focus rests
        val r = g.memory.document(p)
        if (r !is ApiResult.Ok) return@LaunchedEffect
        val changed = shownPath == p && shownText != null && shownText != r.value
        if (changed) updated++
        shownPath = p
        shownText = r.value
        val nodesMd = com.assistant.core.markdown.MarkdownCache.Shared.get("memory-preview:$p", Frontmatter.split(r.value).body)
        val blocks = TvMarkdown.blocks(nodesMd.take(PREVIEW_BLOCKS), InternalLinks.Context(origin))
        preview = MemoryPreviews.of(p, r.value, blocks)
    }
    if (updated > 0) {
        LaunchedEffect(updated) {
            delay(UPDATED_CUE_MS)
            updated = 0
        }
    }
    MemoryScreen(
        state = MemoryUi(rows, tree.loading, query, preview?.copy(updated = updated > 0)),
        actions = MemoryActions(
            onToggle = { path -> expanded = if (path in expanded) expanded - path else expanded + path },
            onFocus = { focusedPath = it.path },
            onOpen = { ctx.nav.push(MemoryDoc(it)) },
            onQuery = { query = it },
            onRefresh = g.memory::refreshTree,
            onCollapseAll = { expanded = emptySet() },
        ),
        initialFocusPath = focusedPath,
    )
}

@Composable
fun MemoryScreen(state: MemoryUi, actions: MemoryActions, modifier: Modifier = Modifier, initialFocusPath: String? = null) {
    val c = TvTheme.colors
    var focused by remember { mutableStateOf<MemoryRow?>(null) }
    ScreenChromeEffect(
        ScreenChrome(
            title = "Memory",
            hints = listOf(KeyHints.Ok(if (focused?.isDir == true) (if (focused?.expanded == true) "Collapse" else "Expand") else "Open"), KeyHints.Menu, KeyHints.Mic, KeyHints.Back),
            options = buildList {
                focused?.let { r ->
                    if (r.isDir) add(TvOption("toggle", if (r.expanded) "Collapse" else "Expand", ArchieIcons.FolderOpen) { actions.onToggle(r.path) })
                    else add(TvOption("open", "Open", ArchieIcons.OpenInNew, detail = r.name) { actions.onOpen(r.path) })
                }
                add(TvOption("collapse", "Collapse all", ArchieIcons.Compress) { actions.onCollapseAll() })
                add(TvOption("refresh", "Refresh", ArchieIcons.Refresh) { actions.onRefresh() })
            },
        ),
    )
    val requesters = remember { HashMap<String, FocusRequester>() }
    val list = rememberLazyListState()
    val search = remember { FocusRequester() }
    LaunchedEffect(state.rows.isNotEmpty()) {
        withFrameNanos { }
        if (state.rows.isEmpty()) {
            runCatching { search.requestFocus() }
            return@LaunchedEffect
        }
        val i = state.rows.indexOfFirst { it.path == initialFocusPath }.takeIf { it >= 0 } ?: 0
        list.scrollToItem((i - 3).coerceAtLeast(0)) // keep a little of what is above in view
        withFrameNanos { }
        runCatching { requesters[state.rows[i].path]?.requestFocus() }
    }
    Row(modifier.fillMaxSize().testTag("memory")) {
        Column(Modifier.width(TREE_WIDTH).fillMaxHeight()) {
            TvSearchBox(state.query, actions.onQuery, "Search memory", Modifier.fillMaxWidth(), focusRequester = search)
            Spacer(Modifier.height(10.dp))
            if (state.rows.isEmpty()) {
                TvEmptyState(
                    when {
                        state.query.isNotEmpty() -> "Nothing matches “${state.query}”"
                        state.loading -> "Loading memory…"
                        else -> "No memory notes"
                    },
                    if (state.query.isNotEmpty()) "Search looks at file and folder names." else "Notes Archie keeps about you and its projects show here.",
                    icon = ArchieIcons.Book2,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().focusRestorer().testTag("memory-tree"),
                    state = list,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(4.dp),
                ) {
                    items(state.rows, key = { it.path }) { r ->
                        val req = remember { FocusRequester() }
                        DisposableEffect(req) {
                            requesters[r.path] = req
                            onDispose { if (requesters[r.path] === req) requesters.remove(r.path) }
                        }
                        TreeRowCard(
                            r,
                            onClick = { if (r.isDir) actions.onToggle(r.path) else actions.onOpen(r.path) },
                            modifier = Modifier
                                .focusRequester(req)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        focused = r
                                        actions.onFocus(r)
                                    }
                                }
                                .testTag("tree-${r.path}"),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(24.dp))
        PreviewPane(state.preview, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun TreeRowCard(r: MemoryRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(start = (r.depth * 22).dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (r.isDir) {
                Icon(if (r.expanded) ArchieIcons.KeyboardArrowDown else ArchieIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Icon(if (r.expanded) ArchieIcons.FolderOpen else ArchieIcons.Folder, contentDescription = null, modifier = Modifier.size(22.dp))
            } else {
                Spacer(Modifier.width(26.dp))
                Icon(ArchieIcons.Description, contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(r.name, style = TvTheme.type.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (r.isDir && r.count > 0) {
                Spacer(Modifier.width(8.dp))
                Text(r.count.toString(), style = TvTheme.type.labelMedium, color = LocalContentColor.current.copy(alpha = 0.72f))
            }
        }
    }
}

@Composable
private fun PreviewPane(preview: MemoryPreview?, modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    Column(
        modifier.background(c.surfaceContainerLow, RoundedCornerShape(24.dp)).padding(horizontal = 26.dp, vertical = 20.dp).clipToBounds().testTag("memory-preview"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (preview == null) {
            Text("Focus a note to preview it; OK opens it.", style = TvTheme.type.bodyMedium, color = c.onSurfaceVariant)
            return@Column
        }
        Text(
            preview.path,
            style = TvTheme.type.code.copy(fontSize = TvTheme.type.labelMedium.fontSize, lineHeight = TvTheme.type.labelMedium.lineHeight),
            color = c.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(preview.title, style = TvTheme.type.headlineSmall, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (preview.chips.isNotEmpty() || preview.updated) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                preview.chips.forEach { TvBadge(it) }
                if (preview.updated) TvBadge("Updated", Modifier.testTag("preview-updated"), emphasized = true)
            }
        }
        val style = tvMarkdownStyle().let { s -> s.copy(body = TvTheme.type.bodyMedium, headings = s.headings.map { h -> h.copy(fontSize = h.fontSize * 0.85f, lineHeight = h.lineHeight * 0.85f) }) }
        val colors = TvInlineColors(style.linkColor, style.linkColor, Color0, style.inlineCodeBackground)
        Box(Modifier.weight(1f).clipToBounds()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                preview.blocks.forEach { TvMarkdownBlock(it, style, colors) }
            }
        }
    }
}

private val Color0 = androidx.compose.ui.graphics.Color.Transparent
private val TREE_WIDTH = 340.dp
private const val PREVIEW_BLOCKS = 14
private const val PREVIEW_DEBOUNCE_MS = 250L
private const val UPDATED_CUE_MS = 4_000L
