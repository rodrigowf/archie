package com.assistant.archie.tv.memory

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.MemoryDoc
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvOption
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.theme.FocusCard
import com.assistant.archie.tv.theme.TvEmptyState
import com.assistant.archie.tv.theme.TvFormat
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.theme.rememberInitialFocus
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.model.MemoryNode
import com.assistant.core.network.ApiResult

/*
 * Memory (spec 15 §3.5) — T0 placeholder: the tree (folders expand/collapse with OK) and a plain
 * preview of the focused note. T3 adds search, the markdown preview (`:core:markdown`) and the
 * "Updated" cue (`memory_changed`).
 */

/** One visible tree row (pure flattening of the expanded tree). */
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
}

@Composable
fun MemoryRoute(ctx: TvRouteContext) {
    val g = ctx.graph
    LaunchedEffect(Unit) { g.memory.refreshTree() }
    val tree by g.memory.tree.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    val preview by produceState<String?>(null, previewPath) {
        value = null
        val p = previewPath ?: return@produceState
        value = (g.memory.document(p) as? ApiResult.Ok)?.value
    }
    MemoryScreen(
        rows = MemoryTree.rows(tree.value.orEmpty(), expanded),
        loading = tree.loading,
        preview = preview,
        onToggle = { path -> expanded = if (path in expanded) expanded - path else expanded + path },
        onFocusFile = { previewPath = it },
        onOpen = { ctx.nav.push(MemoryDoc(it)) },
        onRefresh = g.memory::refreshTree,
    )
}

@Composable
fun MemoryScreen(
    rows: List<TreeRow>,
    loading: Boolean,
    preview: String?,
    onToggle: (String) -> Unit,
    onFocusFile: (String) -> Unit,
    onOpen: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenChromeEffect(
        ScreenChrome(
            title = "Memory",
            hints = listOf(KeyHints.Ok("Open / expand"), KeyHints.Mic, KeyHints.Menu, KeyHints.Back),
            options = listOf(TvOption("refresh", "Refresh", ArchieIcons.Refresh) { onRefresh() }),
        ),
    )
    val c = TvTheme.colors
    val initial = rememberInitialFocus(rows.isNotEmpty())
    if (rows.isEmpty()) {
        TvEmptyState(if (loading) "Loading memory…" else "No memory notes", "Notes Archie keeps about you and its projects show here.", modifier, icon = ArchieIcons.Book2)
    } else {
        Row(modifier.fillMaxSize().testTag("memory")) {
            LazyColumn(
                Modifier.width(380.dp).fillMaxHeight().focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(6.dp),
            ) {
                itemsIndexed(rows, key = { _, r -> r.node.path }) { i, r ->
                    FocusCard(
                        onClick = { if (r.node.isDir) onToggle(r.node.path) else onOpen(r.node.path) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (r.depth * 20).dp)
                            .then(if (i == 0) Modifier.focusRequester(initial) else Modifier)
                            .onFocusChanged { if (it.isFocused && !r.node.isDir) onFocusFile(r.node.path) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val icon = when {
                                !r.node.isDir -> ArchieIcons.Description
                                r.expanded -> ArchieIcons.FolderOpen
                                else -> ArchieIcons.Folder
                            }
                            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(if (r.node.isDir) r.node.name else TvFormat.titleOf(r.node.path), style = TvTheme.type.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f).fillMaxHeight().background(c.surfaceContainerLow, RoundedCornerShape(24.dp)).padding(28.dp)) {
                Text(
                    preview?.lineSequence()?.take(PREVIEW_LINES)?.joinToString("\n") ?: "Focus a note to preview it; OK opens it.",
                    style = TvTheme.type.bodyMedium,
                    color = if (preview == null) c.onSurfaceVariant else c.onSurface,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private const val PREVIEW_LINES = 18
