package com.assistant.archie.tv.visuals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
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
import com.assistant.archie.tv.theme.rememberInitialFocus
import com.assistant.core.design.icons.ArchieIcons

/*
 * Visuals (spec 15 §3.5) — T0 placeholder: the 4-column grid of visualization cards with real data.
 * T3 adds search, Menu → Rename / Copy link (QR), live-reload dots, and the Viewer.
 */

@Immutable
data class VisualCard(val path: String, val title: String, val meta: String)

@Composable
fun VisualsRoute(ctx: TvRouteContext) {
    val g = ctx.graph
    LaunchedEffect(Unit) { g.visuals.refresh() }
    val list by g.visuals.list.collectAsStateWithLifecycle()
    val cards = list.value.orEmpty().map { v -> VisualCard(v.path, v.title.ifBlank { TvFormat.titleOf(v.path) }, TvFormat.ago(v.modified)?.let { "Updated $it" } ?: v.path) }
    VisualsScreen(cards, loading = list.loading, onOpen = { ctx.nav.push(Viewer(it.path, it.title)) }, onRefresh = g.visuals::refresh)
}

@Composable
fun VisualsScreen(cards: List<VisualCard>, loading: Boolean, onOpen: (VisualCard) -> Unit, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    ScreenChromeEffect(
        ScreenChrome(
            title = "Visuals",
            hints = listOf(KeyHints.Ok("Open"), KeyHints.Mic, KeyHints.Menu, KeyHints.Back),
            options = listOf(TvOption("refresh", "Refresh", ArchieIcons.Refresh) { onRefresh() }),
        ),
    )
    val initial = rememberInitialFocus(cards.isNotEmpty())
    if (cards.isEmpty()) {
        TvEmptyState(
            if (loading) "Loading visualizations…" else "No visualizations yet",
            "Ask Archie to build one: a dashboard, a project page, an explainer.",
            modifier,
            icon = ArchieIcons.BarChart,
        )
    } else {
        LazyVerticalGrid(
            GridCells.Fixed(4),
            modifier.fillMaxSize().testTag("visuals-grid"),
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(cards, key = { _, v -> v.path }) { i, v ->
                FocusCard(
                    onClick = { onOpen(v) },
                    modifier = Modifier.fillMaxWidth().height(132.dp).then(if (i == 0) Modifier.focusRequester(initial) else Modifier),
                ) {
                    Box(Modifier.fillMaxWidth().height(4.dp).background(TvTheme.colors.primary, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.height(10.dp))
                    Text(v.title, style = TvTheme.type.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    CardMeta(v.meta)
                }
            }
        }
    }
}
