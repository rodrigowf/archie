package com.assistant.archie.feature.memory.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assistant.archie.feature.memory.MemoryDeps
import com.assistant.archie.feature.memory.MemoryRow
import com.assistant.archie.feature.memory.countAll
import com.assistant.archie.feature.memory.filterMemory
import com.assistant.archie.feature.memory.flatten
import com.assistant.archie.feature.memory.folderIds
import com.assistant.archie.feature.memory.pinIndexFirst
import com.assistant.archie.feature.memory.topFolderIds
import com.assistant.core.data.LoadState
import com.assistant.core.design.components.ArchieButton
import com.assistant.core.design.components.ArchieIconButton
import com.assistant.core.design.components.ArchieTopAppBar
import com.assistant.core.design.components.ButtonStyle
import com.assistant.core.design.components.ButtonSize
import com.assistant.core.design.components.Spinner
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.model.MemoryNode

/**
 * The Memory destination on Compact (IA §5, mockup phone (g1)): a full screen with a back arrow
 * over [MemoryTreePane]. The tree refreshes on entry (spec 14 §4.1).
 */
@Composable
fun MemoryScreen(
    deps: MemoryDeps,
    onBack: () -> Unit,
    onOpenDoc: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedPath: String? = null,
    initialExpanded: Set<String>? = null,
) {
    val tree by deps.tree.collectAsStateWithLifecycle()
    LaunchedEffect(deps) { deps.refreshTree() }
    Column(
        modifier
            .fillMaxSize()
            .background(ArchieTheme.colors.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .testTag("memory-screen"),
    ) {
        ArchieTopAppBar("Memory", navigationIcon = { ArchieIconButton(ArchieIcons.ArrowBack, "Back", onBack) })
        MemoryTreePane(tree, deps::refreshTree, onOpenDoc, selectedPath, Modifier.weight(1f), initialExpanded)
    }
}

/** The Memory list pane on Medium/Expanded (IA §3): the same tree, refreshed when the pane opens. */
@Composable
fun MemoryPane(
    deps: MemoryDeps,
    onOpenDoc: (String) -> Unit,
    selectedPath: String?,
    modifier: Modifier = Modifier,
    initialExpanded: Set<String>? = null,
) {
    val tree by deps.tree.collectAsStateWithLifecycle()
    LaunchedEffect(deps) { deps.refreshTree() }
    MemoryTreePane(tree, deps::refreshTree, onOpenDoc, selectedPath, modifier, initialExpanded)
}

/**
 * Tree + search + refresh (spec 14 §4.1 `MemoryTreePane`; web parity: frontend-next MemoryPane.tsx).
 * The search field says "Search 138 memory files" and matches every word against each file's path;
 * folders carry their file counts; `MEMORY.md` is pinned first; top-level folders start expanded
 * (MEM-4). Expanded state is saved (rotation, process death) and, while searching, every folder on
 * the way to a match starts open (a new query resets it). Pull-to-refresh reloads the tree.
 *
 * Gap vs the mockup (same as the web): the tree endpoint has no modification times, so files show
 * no age.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryTreePane(
    state: LoadState<List<MemoryNode>>,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    selectedPath: String?,
    modifier: Modifier = Modifier,
    initialExpanded: Set<String>? = null,
) {
    val c = ArchieTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    // Saved as newline-joined paths (Bundle-safe). null = the default (top-level folders open).
    var expanded by rememberSaveable { mutableStateOf(initialExpanded?.joinToString("\n")) }
    // Folder state while searching, valid only for the query it was made for ("query\npath\npath…").
    var searchExpanded by rememberSaveable { mutableStateOf<String?>(null) }

    val nodes = remember(state.value) { pinIndexFirst(state.value.orEmpty()) }
    val total = remember(nodes) { countAll(nodes) }
    val searching = query.isNotBlank()
    val shown = remember(nodes, query) { if (searching) filterMemory(nodes, query) else nodes }
    val open: Set<String> = when {
        searching -> searchExpanded?.split('\n')?.takeIf { it.first() == query }?.drop(1)?.toSet() ?: folderIds(shown)
        else -> expanded?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: topFolderIds(nodes)
    }
    val rows = remember(shown, open) { flatten(shown, open) }
    val toggle: (String) -> Unit = { path ->
        val next = (if (path in open) open - path else open + path).joinToString("\n")
        if (searching) searchExpanded = query + "\n" + next else expanded = next
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("memory-tree")) {
        MemorySearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = if (state.loaded) "Search $total memory ${if (total == 1) "file" else "files"}" else "Search memory",
            trailing = {
                if (state.loading) {
                    Box(Modifier.padding(12.dp)) { Spinner(size = 24.dp) }
                } else {
                    ArchieIconButton(ArchieIcons.Refresh, "Refresh memory", onRefresh, size = 40.dp, iconSize = 20.dp)
                }
            },
        )
        state.error?.let { err ->
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = "Could not load the memory tree: $err" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Could not load the memory tree: $err", Modifier.weight(1f).padding(start = 8.dp), style = ArchieTheme.typography.bodyMedium, color = c.error)
                ArchieButton("Retry", onRefresh, style = ButtonStyle.Text, size = ButtonSize.Small)
            }
        }
        PullToRefreshBox(
            isRefreshing = state.loading && state.loaded,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("memory-tree-list"),
                contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                when {
                    !state.loaded && state.loading -> item(key = "loading") { Note("Loading memory…") }
                    state.loaded && nodes.isEmpty() -> item(key = "empty") {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ArchieIcon(ArchieIcons.Book2, null, size = 32.dp, tint = c.onSurfaceVariant)
                            Text("No memory files", style = ArchieTheme.typography.titleMedium, color = c.onSurface)
                            Text("Markdown under context/memory/ appears here.", style = ArchieTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                        }
                    }
                    searching && rows.isEmpty() && nodes.isNotEmpty() -> item(key = "nomatch") { Note("No files match “${query.trim()}”") }
                }
                items(rows, key = { it.path }, contentType = { if (it.isDir) "dir" else "file" }) { row ->
                    TreeRow(row, selected = !row.isDir && row.path == selectedPath, onClick = { if (row.isDir) toggle(row.path) else onOpen(row.path) })
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(16.dp), style = ArchieTheme.typography.bodyMedium, color = ArchieTheme.colors.onSurfaceVariant)
}

/** One tree row (mockup `.tr`): 48 dp, r24, 22 dp indent per level; folder chevron + icon + count. */
@Composable
private fun TreeRow(row: MemoryRow, selected: Boolean, onClick: () -> Unit) {
    val c = ArchieTheme.colors
    val fg = if (selected) c.onSecondaryContainer else c.onSurface
    val muted = if (selected) c.onSecondaryContainer else c.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .then(if (selected) Modifier.background(c.secondaryContainer) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                if (row.isDir) stateDescription = if (row.expanded) "Expanded" else "Collapsed"
            }
            .testTag(if (row.isDir) "memory-folder:${row.path}" else "memory-file:${row.path}")
            .padding(start = (8 + row.depth * 22).dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.isDir) {
            ArchieIcon(if (row.expanded) ArchieIcons.KeyboardArrowDown else ArchieIcons.ChevronRight, null, tint = muted)
            ArchieIcon(if (row.expanded) ArchieIcons.FolderOpenFilled else ArchieIcons.Folder, null, tint = if (selected) fg else c.primary)
        } else {
            ArchieIcon(ArchieIcons.Description, null, tint = muted)
        }
        Text(
            row.name,
            Modifier.weight(1f),
            style = ArchieTheme.typography.bodyLarge.copy(fontSize = 15.sp, letterSpacing = 0.sp),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (row.isDir) {
            Text(
                "${row.count}",
                style = ArchieTheme.typography.bodySmall.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"),
                color = muted,
            )
        }
    }
}

/** The search pill as an editable field (mockup `.search`: 52 dp, r26, surface-container-high). */
@Composable
internal fun MemorySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = ArchieTheme.colors
    val style = ArchieTheme.typography.bodyLarge.copy(letterSpacing = 0.sp, color = c.onSurface)
    Row(
        modifier
            .widthIn(max = 840.dp)
            .fillMaxWidth()
            .height(52.dp)
            .background(c.surfaceContainerHigh, RoundedCornerShape(26.dp))
            .padding(start = 16.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArchieIcon(ArchieIcons.Search, null, tint = c.onSurfaceVariant)
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).semantics { contentDescription = placeholder },
            singleLine = true,
            textStyle = style,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.primary),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = style, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) ArchieIconButton(ArchieIcons.Close, "Clear search", { onValueChange("") }, size = 40.dp, iconSize = 20.dp)
        trailing?.invoke()
    }
}
