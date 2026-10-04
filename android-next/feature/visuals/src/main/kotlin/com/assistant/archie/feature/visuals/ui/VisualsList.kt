package com.assistant.archie.feature.visuals.ui

import android.content.ClipData
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assistant.archie.feature.visuals.CastCapability
import com.assistant.archie.feature.visuals.ExternalLinks
import com.assistant.archie.feature.visuals.VisualUrls
import com.assistant.archie.feature.visuals.VisualsDeps
import com.assistant.archie.feature.visuals.shortAge
import com.assistant.core.data.LoadState
import com.assistant.core.design.components.ArchieButton
import com.assistant.core.design.components.ArchieDialogSurface
import com.assistant.core.design.components.ArchieDropdownMenu
import com.assistant.core.design.components.ArchieIconButton
import com.assistant.core.design.components.ArchieListItem
import com.assistant.core.design.components.ArchieMenuItem
import com.assistant.core.design.components.ArchieSnackbarHost
import com.assistant.core.design.components.ArchieTextField
import com.assistant.core.design.components.ArchieTopAppBar
import com.assistant.core.design.components.ButtonSize
import com.assistant.core.design.components.ButtonStyle
import com.assistant.core.design.components.ListItemSize
import com.assistant.core.design.components.Spinner
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.model.VisualInfo
import kotlinx.coroutines.launch
import java.time.Instant

/** The Visuals destination on Compact (IA §5): a full screen with a back arrow over [VisualsListPane]. */
@Composable
fun VisualsScreen(deps: VisualsDeps, onBack: () -> Unit, onOpen: (String) -> Unit, modifier: Modifier = Modifier, now: Instant? = null) {
    val snackbar = remember { SnackbarHostState() }
    Box(modifier.fillMaxSize().background(ArchieTheme.colors.surface).testTag("visuals-screen")) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            ArchieTopAppBar("Visuals", navigationIcon = { ArchieIconButton(ArchieIcons.ArrowBack, "Back", onBack) })
            VisualsPane(deps, onOpen, selectedPath = null, snackbar = snackbar, modifier = Modifier.weight(1f), now = now)
        }
        ArchieSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars))
    }
}

/** The list pane (Medium/Expanded) or the Compact screen body: refreshed on entry, plus the cast probe. */
@Composable
fun VisualsPane(
    deps: VisualsDeps,
    onOpen: (String) -> Unit,
    selectedPath: String?,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState? = null,
    now: Instant? = null,
) {
    val state by deps.list.collectAsStateWithLifecycle()
    val capability by deps.cast.capability.collectAsStateWithLifecycle()
    val ownHost = remember { SnackbarHostState() }
    val host = snackbar ?: ownHost
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    var renaming by remember { mutableStateOf<VisualInfo?>(null) }
    LaunchedEffect(deps) {
        deps.refresh()
        deps.cast.ensure()
    }
    Box(modifier.fillMaxSize()) {
        VisualsListPane(
            state = state,
            castAvailable = capability == CastCapability.Available,
            onRefresh = deps::refresh,
            onOpen = onOpen,
            selectedPath = selectedPath,
            onAction = { v, action ->
                val href = VisualUrls.href(deps.origin(), v.path, v.url)
                when (action) {
                    VisualAction.Rename -> renaming = v
                    VisualAction.ShowOnTv -> scope.launch { host.showSnackbar(deps.cast.cast(v.path, v.title.ifBlank { v.path }).message) }
                    VisualAction.OpenInBrowser -> ExternalLinks.open(context, href)
                    VisualAction.CopyLink -> scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("visual link", href)))
                        host.showSnackbar("Link copied")
                    }
                }
            },
            now = now,
        )
        if (snackbar == null) ArchieSnackbarHost(ownHost, Modifier.align(Alignment.BottomCenter))
    }
    renaming?.let { v ->
        RenameVisualDialog(
            initial = v.title,
            onCommit = { title ->
                renaming = null
                scope.launch {
                    val r = deps.rename(v.path, title)
                    r.errorMessage()?.let { host.showSnackbar(it) }
                }
            },
            onDismiss = { renaming = null },
        )
    }
}

enum class VisualAction(val label: String) { Rename("Rename"), ShowOnTv("Show on TV"), OpenInBrowser("Open in browser"), CopyLink("Copy link") }

/**
 * The visuals list (spec 14 §4.2 `VisualsListPane`; web parity: frontend-next VisualsPane.tsx):
 * search, Refresh, thumbnail-less rows with the title and "folder · age" (VZ-5), and a ⋮ per row
 * with Rename, Show on TV (only when available), Open in browser, Copy link. Rename only, no
 * delete (by design, F-36). Pull-to-refresh reloads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualsListPane(
    state: LoadState<List<VisualInfo>>,
    castAvailable: Boolean,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    selectedPath: String?,
    onAction: (VisualInfo, VisualAction) -> Unit,
    modifier: Modifier = Modifier,
    now: Instant? = null,
) {
    val c = ArchieTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    val items = state.value.orEmpty()
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val shown = remember(items, query) {
        items.filter { v -> "${v.title} ${v.path}".lowercase().let { t -> words.all { t.contains(it) } } }
    }
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("visuals-list")) {
        VisualsSearchField(query, { query = it }, "Search visuals") {
            if (state.loading) Box(Modifier.padding(12.dp)) { Spinner(size = 24.dp) }
            else ArchieIconButton(ArchieIcons.Refresh, "Refresh visuals", onRefresh, size = 40.dp, iconSize = 20.dp)
        }
        state.error?.let { err ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Could not load the visuals: $err", Modifier.weight(1f).padding(start = 8.dp), style = ArchieTheme.typography.bodyMedium, color = c.error)
                ArchieButton("Retry", onRefresh, style = ButtonStyle.Text, size = ButtonSize.Small)
            }
        }
        PullToRefreshBox(isRefreshing = state.loading && state.loaded, onRefresh = onRefresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp)) {
                when {
                    !state.loaded && state.loading -> item(key = "loading") { Note("Loading visuals…") }
                    state.loaded && items.isEmpty() -> item(key = "empty") {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ArchieIcon(ArchieIcons.BarChart, null, size = 32.dp, tint = c.onSurfaceVariant)
                            Text("No visuals yet", style = ArchieTheme.typography.titleMedium, color = c.onSurface)
                            Text("HTML files under context/public/ appear here.", style = ArchieTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                        }
                    }
                    words.isNotEmpty() && shown.isEmpty() && items.isNotEmpty() -> item(key = "nomatch") { Note("No visuals match “${query.trim()}”") }
                }
                items(shown, key = { it.path }) { v ->
                    val age = shortAge(v.modified, now ?: Instant.now())
                    val folder = VisualUrls.folder(v.path)
                    ArchieListItem(
                        headline = v.title.ifBlank { v.path },
                        onClick = { onOpen(v.path) },
                        modifier = Modifier.testTag("visual-row:${v.path}"),
                        size = ListItemSize.TwoLine,
                        selected = v.path == selectedPath,
                        leading = { VisualTile() },
                        supporting = { Text(if (age.isNotEmpty()) "$folder · $age" else folder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailing = { RowMenu(v, castAvailable, onAction) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VisualTile() {
    val c = ArchieTheme.colors
    Box(Modifier.size(40.dp).background(c.surfaceContainerHigh, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        ArchieIcon(ArchieIcons.BarChart, null, size = 20.dp, tint = c.primary)
    }
}

@Composable
private fun RowMenu(v: VisualInfo, castAvailable: Boolean, onAction: (VisualInfo, VisualAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ArchieIconButton(ArchieIcons.MoreVert, "Actions for ${v.title.ifBlank { v.path }}", { open = true }, Modifier.testTag("visual-row-menu:${v.path}"), size = 40.dp, iconSize = 20.dp)
        ArchieDropdownMenu(open, onDismissRequest = { open = false }) {
            ArchieMenuItem("Rename", { open = false; onAction(v, VisualAction.Rename) }, icon = ArchieIcons.Edit)
            if (castAvailable) ArchieMenuItem("Show on TV", { open = false; onAction(v, VisualAction.ShowOnTv) }, icon = ArchieIcons.Cast)
            ArchieMenuItem("Open in browser", { open = false; onAction(v, VisualAction.OpenInBrowser) }, icon = ArchieIcons.OpenInNew)
            ArchieMenuItem("Copy link", { open = false; onAction(v, VisualAction.CopyLink) }, icon = ArchieIcons.Link)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(16.dp), style = ArchieTheme.typography.bodyMedium, color = ArchieTheme.colors.onSurfaceVariant)
}

/** "Rename visual": Save commits a changed, non-empty title; Cancel leaves it (web RenameDialog). */
@Composable
fun RenameVisualDialog(initial: String, onCommit: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val commit = { val t = value.trim(); if (t.isNotEmpty() && t != initial) onCommit(t) else onDismiss() }
    Dialog(onDismissRequest = onDismiss) {
        ArchieDialogSurface(
            "Rename visual",
            body = {
                ArchieTextField(
                    value, { value = it }, label = "Title",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
                )
            },
        ) {
            ArchieButton("Cancel", onDismiss, style = ButtonStyle.Text)
            ArchieButton("Save", commit, style = ButtonStyle.Text)
        }
    }
}

/** The search pill as an editable field (mockup `.search`: 52 dp, r26, surface-container-high). */
@Composable
internal fun VisualsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = ArchieTheme.colors
    val style = ArchieTheme.typography.bodyLarge.copy(letterSpacing = 0.sp, color = c.onSurface)
    Row(
        Modifier
            .widthIn(max = 840.dp)
            .fillMaxWidth()
            .height(52.dp)
            .background(c.surfaceContainerHigh, RoundedCornerShape(26.dp))
            .padding(start = 16.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArchieIcon(ArchieIcons.Search, null, tint = c.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).semantics { contentDescription = placeholder },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(c.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = style, color = c.onSurfaceVariant, maxLines = 1)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) ArchieIconButton(ArchieIcons.Close, "Clear search", { onValueChange("") }, size = 40.dp, iconSize = 20.dp)
        trailing?.invoke()
    }
}
