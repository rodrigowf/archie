package com.assistant.archie.tv.visuals

import com.assistant.archie.tv.theme.dpadFocusRestorer
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.assistant.archie.feature.visuals.VisualUrls
import com.assistant.archie.tv.control.TvLinks
import com.assistant.archie.tv.shell.FocusRestoreEffect
import com.assistant.archie.tv.theme.FocusSettling
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvOption
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.shell.Viewer
import com.assistant.archie.tv.theme.CardMeta
import com.assistant.archie.tv.theme.FocusCard
import com.assistant.archie.tv.theme.TvEmptyState
import com.assistant.archie.tv.theme.TvFormat
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.model.VisualInfo
import com.assistant.core.network.ApiResult
import com.assistant.core.network.UrlScheme
import kotlinx.coroutines.launch

/*
 * Visuals (spec 15 §3.5, mockup 10): a 4-column grid of typographic cards — kind band + label, title,
 * path, "updated …", and a dot when the page changed since it was last opened — with search. OK opens
 * the Viewer; Menu → Open, Rename (IME), Copy link (the address, with a QR code for a phone), Refresh.
 */

@Immutable
data class VisualCardUi(
    val path: String,
    val title: String,
    val kind: VisualKind,
    val file: String,
    val updated: String?,
    val changed: Boolean = false,
    /** Starred by the user (`PUT /api/visualizations/favorite`); these sort to the top. */
    val favorite: Boolean = false,
)

@Immutable
data class VisualsUi(
    val cards: List<VisualCardUi>,
    val total: Int = cards.size,
    val loading: Boolean = false,
    val query: String = "",
    val status: StatusView = StatusView(),
) {
    /** How many of [cards] are starred. [cards] is always starred-first (`VisualsModel.ordered`). */
    val starred: Int get() = cards.count { it.favorite }
}

class VisualsActions(
    val onOpen: (VisualCardUi) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onRename: (VisualCardUi, String) -> Unit = { _, _ -> },
    /** The URL a phone can open ("Copy link"). */
    val linkOf: (VisualCardUi) -> String = { it.path },
)

object VisualsModel {
    fun card(v: VisualInfo, changed: Boolean): VisualCardUi {
        val title = v.title.ifBlank { TvFormat.titleOf(v.path) }
        return VisualCardUi(
            path = v.path,
            title = title,
            kind = VisualKind.of(v.path, title),
            file = VisualKind.fileLabel(v.path),
            updated = TvFormat.ago(v.modified)?.let { "updated $it" },
            changed = changed,
            favorite = v.favorite,
        )
    }

    /** Every word of [query] in the title or the path (any order, case-insensitive). */
    fun filter(cards: List<VisualCardUi>, query: String): List<VisualCardUi> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return cards
        return cards.filter { c ->
            val hay = (c.title + " " + c.path).lowercase()
            words.all { it in hay }
        }
    }

    /**
     * Starred first, then the rest, each keeping the server's order (`modified` desc) — the same
     * split the phone's list makes (`visualSections`). Stable, so it can be applied after [filter].
     */
    fun ordered(cards: List<VisualCardUi>): List<VisualCardUi> {
        val (starred, others) = cards.partition { it.favorite }
        return if (starred.isEmpty()) cards else starred + others
    }
}

/**
 * Where a card sits among the grid's lazy items. With something starred the grid gains a full-width
 * "Starred" header above the starred cards and an "All visuals" header above the rest, so an item
 * index no longer equals the card's index in [VisualsUi.cards] — which the scroll and the focus
 * restore both rely on. [starred] is the starred count *after* the search filter.
 */
object VisualsGrid {
    fun hasHeaders(starred: Int): Boolean = starred > 0

    /** The grid item index of the card at [cardIndex]. */
    fun itemIndex(cardIndex: Int, starred: Int): Int = when {
        !hasHeaders(starred) -> cardIndex
        cardIndex < starred -> cardIndex + 1   // after the "Starred" header
        else -> cardIndex + 2                  // also after the "All visuals" header
    }

    /** The card nearest to grid item [itemIndex] (a header maps to the first card below it). */
    fun cardIndex(itemIndex: Int, starred: Int): Int = when {
        !hasHeaders(starred) -> itemIndex
        itemIndex <= starred -> (itemIndex - 1).coerceAtLeast(0)
        // itemIndex == starred + 1 is the "All visuals" header: the first unstarred card.
        else -> (itemIndex - 2).coerceAtLeast(starred)
    }
}

@Composable
fun VisualsRoute(ctx: TvRouteContext) {
    val g = ctx.graph
    val control = g.tvControl
    LaunchedEffect(Unit) { g.visuals.refresh() }
    val list by g.visuals.list.collectAsStateWithLifecycle()
    val stamps by g.content.visuals.collectAsStateWithLifecycle()
    val seen by control.visualsSeen.seen.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val all = list.value.orEmpty().map { VisualsModel.card(it, VisualsSeen.changed(it.path, stamps, seen)) }
    val focus = remember { control.visualsSeen.consumeFocus() }
    VisualsScreen(
        state = VisualsUi(
            VisualsModel.ordered(VisualsModel.filter(all, query)),
            total = all.size,
            loading = list.loading,
            query = query,
            status = rememberStatus(g),
        ),
        actions = VisualsActions(
            onOpen = { ctx.nav.push(Viewer(it.path, it.title)) },
            onQuery = { query = it },
            onRefresh = g.visuals::refresh,
            onRename = { card, title ->
                scope.launch {
                    val r = g.visuals.rename(card.path, title)
                    if (r !is ApiResult.Ok) g.toasts.show("Could not rename: ${(r as? ApiResult.HttpError)?.detail ?: "server unreachable"}", error = true)
                }
            },
            linkOf = { TvLinks.withoutDefaultPort(VisualUrls.href(UrlScheme.httpBase(g.currentServerUrl()), it.path)) },
        ),
        initialFocusPath = focus,
    )
}

/** A dialog the Visuals menu opened (goldens can start with one). */
sealed interface VisualDialog {
    data class Rename(val card: VisualCardUi) : VisualDialog
    data class Link(val card: VisualCardUi, val url: String) : VisualDialog
}

@Composable
fun VisualsScreen(
    state: VisualsUi,
    actions: VisualsActions,
    modifier: Modifier = Modifier,
    initialFocusPath: String? = null,
    dialog: VisualDialog? = null,
) {
    var focusedPath by rememberSaveable { mutableStateOf(initialFocusPath) }
    var open by remember { mutableStateOf(dialog) }
    val focused = state.cards.firstOrNull { it.path == focusedPath } ?: state.cards.firstOrNull()
    ScreenChromeEffect(
        ScreenChrome(
            title = "Visuals",
            hints = listOf(KeyHints.Ok("Open"), KeyHints.Menu, KeyHints.Mic, KeyHints.Back),
            showHeader = false,
            options = buildList {
                if (focused != null) {
                    add(TvOption("open", "Open", ArchieIcons.OpenInNew, detail = focused.title) { actions.onOpen(focused) })
                    add(TvOption("rename", "Rename", ArchieIcons.Edit) { open = VisualDialog.Rename(focused) })
                    add(TvOption("link", "Copy link", ArchieIcons.Link, detail = "Address and QR code for a phone") { open = VisualDialog.Link(focused, actions.linkOf(focused)) })
                }
                add(TvOption("refresh", "Refresh", ArchieIcons.Refresh) { actions.onRefresh() })
            },
        ),
    )
    val requesters = remember { HashMap<String, FocusRequester>() }
    val search = remember { FocusRequester() }
    val grid = rememberLazyGridState()
    // Back from the drawer, a closed dialog or sheet, or a lost focus: the card the user was on,
    // else the first card in view, else the search box (B3).
    FocusRestoreEffect {
        fun tryFocus(r: FocusRequester?) = r != null && runCatching { r.requestFocus() }.getOrDefault(false)
        val inView = VisualsGrid.cardIndex(grid.firstVisibleItemIndex, state.starred)
        tryFocus(focusedPath?.let { requesters[it] }) ||
            tryFocus(state.cards.getOrNull(inView)?.path?.let { requesters[it] }) ||
            tryFocus(search)
    }
    // Initial focus (spec 15 §3.1-3): the remembered / requested card, else the first one, else the search box.
    LaunchedEffect(state.cards.isNotEmpty()) {
        withFrameNanos { }
        if (state.cards.isNotEmpty()) {
            val target = state.cards.indexOfFirst { it.path == focusedPath }.takeIf { it >= 0 } ?: 0
            grid.scrollToItem(VisualsGrid.itemIndex(target, state.starred))
            withFrameNanos { }
            runCatching { requesters[state.cards[target].path]?.requestFocus() }
        } else {
            runCatching { search.requestFocus() }
        }
    }
    Column(modifier.fillMaxSize().testTag("visuals")) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Visuals", style = TvTheme.type.displaySmall, color = TvTheme.colors.onSurface, maxLines = 1)
            Spacer(Modifier.width(14.dp))
            Text(
                if (state.query.isEmpty()) "${state.total} pages" else "${state.cards.size} of ${state.total}",
                style = TvTheme.type.labelMedium,
                color = TvTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            Spacer(Modifier.weight(1f))
            TvSearchBox(state.query, actions.onQuery, "Search visuals", Modifier.width(260.dp), focusRequester = search)
            Spacer(Modifier.width(20.dp))
            TvStatus(state.status)
        }
        if (state.cards.isEmpty()) {
            TvEmptyState(
                when {
                    state.query.isNotEmpty() -> "Nothing matches “${state.query}”"
                    state.loading -> "Loading visualizations…"
                    else -> "No visualizations yet"
                },
                if (state.query.isNotEmpty()) "Search looks at titles and paths." else "Ask Archie to build one: a dashboard, a project page, an explainer.",
                Modifier.weight(1f),
                icon = ArchieIcons.BarChart,
            )
        } else {
            LazyVerticalGrid(
                GridCells.Fixed(4),
                Modifier.weight(1f).fillMaxWidth().dpadFocusRestorer().testTag("visuals-grid"),
                state = grid,
                contentPadding = PaddingValues(6.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                val cardItems: LazyGridScope.(List<VisualCardUi>) -> Unit = { cards ->
                    items(cards, key = { it.path }) { v ->
                        val req = remember { FocusRequester() }
                        DisposableEffect(req) {
                            requesters[v.path] = req
                            onDispose { if (requesters[v.path] === req) requesters.remove(v.path) }
                        }
                        VisualCard(
                            v,
                            onClick = { actions.onOpen(v) },
                            modifier = Modifier
                                .focusRequester(req)
                                .onFocusChanged { if (it.isFocused && !FocusSettling.active) focusedPath = v.path }
                                .testTag("visual-${v.path}"),
                        )
                    }
                }
                // Starred first under their own heading, the rest under "All visuals" (as the phone
                // list). With nothing starred there are no headings and the grid is one flat run —
                // VisualsGrid keeps the scroll/focus indices in step either way.
                if (VisualsGrid.hasHeaders(state.starred)) {
                    val starred = state.cards.take(state.starred)
                    val others = state.cards.drop(state.starred)
                    sectionHeader("h-starred", "Starred")
                    cardItems(starred)
                    if (others.isNotEmpty()) {
                        sectionHeader("h-all", "All visuals")
                        cardItems(others)
                    }
                } else {
                    cardItems(state.cards)
                }
            }
        }
    }
    when (val d = open) {
        is VisualDialog.Rename -> RenameDialog(
            d.card.title,
            onRename = { t ->
                open = null
                if (t.isNotBlank() && t.trim() != d.card.title) actions.onRename(d.card, t.trim())
            },
            onDismiss = { open = null },
        )
        is VisualDialog.Link -> LinkDialog(d.card.title, d.url, onDismiss = { open = null })
        null -> Unit
    }
}

/** A full-width heading between the grid's sections. Not focusable, so the D-pad steps past it. */
private fun LazyGridScope.sectionHeader(key: String, text: String) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
        Text(
            text,
            style = TvTheme.type.titleMedium,
            color = TvTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp).testTag(key),
        )
    }
}

@Composable
fun VisualCard(v: VisualCardUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val kindColor = v.kind.color
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth().height(CARD_HEIGHT), contentPadding = PaddingValues(0.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp).background(kindColor))
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(v.kind.label.uppercase(), style = TvTheme.type.labelMedium, color = kindColor, maxLines = 1, modifier = Modifier.weight(1f))
                if (v.favorite) {
                    Icon(
                        ArchieIcons.StarFilled,
                        contentDescription = "Starred",
                        tint = TvTheme.colors.primary,
                        modifier = Modifier.size(16.dp).testTag("visual-star"),
                    )
                    if (v.changed) Spacer(Modifier.width(6.dp))
                }
                if (v.changed) Dot(TvTheme.colors.primary, Modifier.testTag("changed-dot"))
            }
            Spacer(Modifier.height(2.dp))
            Text(v.title, style = TvTheme.type.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 56.dp))
            Text(
                v.file,
                style = TvTheme.type.code.copy(fontSize = TvTheme.type.labelMedium.fontSize, lineHeight = TvTheme.type.labelMedium.lineHeight),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = LocalContentColor.current.copy(alpha = 0.72f),
            )
            if (v.updated != null) CardMeta(v.updated)
        }
    }
}

private val CARD_HEIGHT = 150.dp
