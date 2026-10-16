package com.assistant.archie.tv.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.MemoryDoc
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvKey
import com.assistant.archie.tv.shell.TvKeyHandler
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.theme.FocusCard
import com.assistant.archie.tv.theme.TvEmptyState
import com.assistant.archie.tv.theme.TvFormat
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.theme.rememberInitialFocus
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.markdown.Frontmatter
import com.assistant.core.network.ApiResult
import kotlinx.coroutines.launch

/*
 * MemoryDoc (spec 15 §3.5) — T0 placeholder: the note as plain paragraphs, each one focusable so
 * Up/Down reads it block by block, Rewind/FF page. T3 renders it with :core:markdown, focusable
 * links (shared LNK resolver → Memory / Viewer) and the conversation reading model.
 */

@Composable
fun MemoryDocRoute(key: MemoryDoc, ctx: TvRouteContext) {
    val text by produceState<String?>(null, key.path) {
        value = when (val r = ctx.graph.memory.document(key.path)) {
            is ApiResult.Ok -> r.value
            else -> ""
        }
    }
    MemoryDocScreen(TvFormat.titleOf(key.path), text)
}

/** Paragraphs of a markdown body (frontmatter stripped); blank-line separated. */
fun paragraphsOf(markdown: String): List<String> =
    Frontmatter.split(markdown).body.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }

@Composable
fun MemoryDocScreen(title: String, text: String?, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    ScreenChromeEffect(
        ScreenChrome(
            title = title,
            hints = listOf(KeyHints.Page(), KeyHints.Mic, KeyHints.Menu, KeyHints.Back),
            keys = TvKeyHandler { key ->
                when (key) {
                    TvKey.REWIND -> { scope.launch { list.animateScrollToItem((list.firstVisibleItemIndex - 3).coerceAtLeast(0)) }; true }
                    TvKey.FAST_FORWARD -> { scope.launch { list.animateScrollToItem(list.firstVisibleItemIndex + 3) }; true }
                    else -> false
                }
            },
        ),
    )
    val paragraphs = text?.let(::paragraphsOf)
    val first = rememberInitialFocus(paragraphs?.isNotEmpty())
    if (paragraphs.isNullOrEmpty()) {
        TvEmptyState(if (text == null) "Loading…" else "This note is empty or could not be read", "", modifier, icon = ArchieIcons.Description)
    } else {
        LazyColumn(
            modifier.fillMaxSize().testTag("memory-doc"),
            state = list,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(paragraphs) { i, p ->
                FocusCard(
                    onClick = {},
                    modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                ) {
                    Text(p, style = TvTheme.type.bodyLarge)
                }
            }
        }
    }
}
