package com.assistant.archie.tv.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.assistant.archie.feature.memory.MemoryLink
import com.assistant.archie.feature.memory.MemoryLinkResolver
import com.assistant.archie.tv.control.RemoteInfo
import com.assistant.archie.tv.control.RemoteScreen
import com.assistant.archie.tv.control.RemoteScreenEffect
import com.assistant.archie.tv.control.ScrollDirection
import com.assistant.archie.tv.shell.HintGlyph
import com.assistant.archie.tv.shell.KeyHint
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.MemoryDoc
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvKey
import com.assistant.archie.tv.shell.TvKeyHandler
import com.assistant.archie.tv.shell.TvOption
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.shell.Viewer
import com.assistant.archie.tv.theme.TvDimens
import com.assistant.archie.tv.theme.TvEmptyState
import com.assistant.archie.tv.theme.TvFormat
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.visuals.StatusView
import com.assistant.archie.tv.visuals.TvStatus
import com.assistant.archie.tv.visuals.rememberStatus
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.markdown.Frontmatter
import com.assistant.core.markdown.InternalLinks
import com.assistant.core.markdown.MarkdownCache
import com.assistant.core.network.ApiResult
import com.assistant.core.network.UrlScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * MemoryDoc (spec 15 §3.5, mockup 13b): a memory note full screen with the conversation's reading
 * model — every block is a focus stop (Up/Down walk them; a block taller than the screen scrolls
 * inside first, 0.8 of the viewport per press, so no text is unreachable); Rewind/FF page; the links
 * of the focused block are selectable (Left/Right) and OK opens one: a memory note → MemoryDoc, a
 * visualization → Viewer, any other page → Viewer (the shared LNK/MEM-2 resolvers). Refetches in
 * place on `memory_changed` and shows "Updated" when the text changed (VZ-6).
 */

/** Paragraphs of a markdown body (frontmatter stripped); blank-line separated. */
fun paragraphsOf(markdown: String): List<String> =
    Frontmatter.split(markdown).body.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }

@Immutable
data class MemoryDocUi(
    val path: String,
    val title: String,
    /** null = loading; "" with [error] = failed. */
    val blocks: List<TvBlock>?,
    val error: String? = null,
    val updated: Boolean = false,
    val status: StatusView = StatusView(),
)

class MemoryDocActions(
    val onLink: (String) -> Unit = {},
    val onReload: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/** Scroll / paging of the reading list, shared by the keys, the control channel and the Rewind/FF handler. */
class ReadingScroller(val list: LazyListState, private val scope: CoroutineScope) {
    val requesters = HashMap<Int, FocusRequester>()

    fun page(direction: ScrollDirection, count: Int) {
        scope.launch {
            when (direction) {
                ScrollDirection.TOP -> list.scrollToItem(0)
                ScrollDirection.BOTTOM -> list.scrollToItem((count - 1).coerceAtLeast(0))
                ScrollDirection.UP, ScrollDirection.DOWN -> {
                    val vp = list.layoutInfo.viewportSize.height
                    list.animateScrollBy(PAGE_FRACTION * vp * if (direction == ScrollDirection.UP) -1 else 1)
                }
            }
            withFrameNanos { }
            focusVisible(direction)
        }
    }

    /** Focus the first block that starts on screen (or the one covering the top, when it is tall). */
    private fun focusVisible(direction: ScrollDirection) {
        val info = list.layoutInfo
        val visible = info.visibleItemsInfo
        val target = when (direction) {
            ScrollDirection.BOTTOM -> visible.lastOrNull()
            else -> visible.firstOrNull { it.offset >= info.viewportStartOffset } ?: visible.firstOrNull()
        } ?: return
        runCatching { requesters[target.index]?.requestFocus() }
    }

    fun focus(index: Int) {
        scope.launch {
            list.scrollToItem(index)
            withFrameNanos { }
            runCatching { requesters[index]?.requestFocus() }
        }
    }

    /**
     * Down / Up inside a tall block: scroll by up to 0.8 of the viewport while the block still has
     * text beyond the edge. Returns true when it scrolled (the key is consumed, focus stays).
     */
    fun stepInside(index: Int, down: Boolean): Boolean {
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
        val vpStart = info.viewportStartOffset
        val vpEnd = info.viewportEndOffset
        val maxStep = PAGE_FRACTION * (vpEnd - vpStart)
        val delta = if (down) {
            val beyond = item.offset + item.size - vpEnd
            if (beyond <= EDGE_SLACK_PX) return false
            minOf(beyond.toFloat(), maxStep)
        } else {
            val beyond = vpStart - item.offset
            if (beyond <= EDGE_SLACK_PX) return false
            -minOf(beyond.toFloat(), maxStep)
        }
        scope.launch { list.animateScrollBy(delta) }
        return true
    }

    companion object {
        const val PAGE_FRACTION = 0.8f
        const val EDGE_SLACK_PX = 4
    }
}

/** The note's own `# Title` when it starts with one (mockup 13b), else the file name. */
fun docTitle(blocks: List<TvBlock>?, path: String): String =
    blocks?.firstOrNull()?.takeIf { (it.node as? com.assistant.core.markdown.MdNode.Heading)?.level == 1 }
        ?.let { TvMarkdown.plain(it).trim() }?.takeIf { it.isNotEmpty() }
        ?: TvFormat.titleOf(path)

@Composable
fun MemoryDocRoute(key: MemoryDoc, ctx: TvRouteContext) {
    val g = ctx.graph
    val control = g.tvControl
    val origin = remember { UrlScheme.httpBase(g.currentServerUrl()) }
    val stamps by g.content.memory.collectAsStateWithLifecycle()
    val resync by g.content.resyncEpoch.collectAsStateWithLifecycle()
    val version = stamps[key.path]?.version ?: 0
    var text by remember(key) { mutableStateOf<String?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var reloads by remember(key) { mutableIntStateOf(0) }
    var updated by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, version, resync, reloads) {
        if (text != null) delay(REFETCH_DEBOUNCE_MS) // a burst of changes refetches once
        when (val r = g.memory.document(key.path)) {
            is ApiResult.Ok -> {
                if (text != null && text != r.value) updated++
                text = r.value
                error = null
            }
            is ApiResult.HttpError -> if (text == null) error = if (r.code == 404) "This note does not exist (any more)." else "The server answered ${r.code}."
            else -> if (text == null) error = "The server could not be reached."
        }
    }
    if (updated > 0) {
        LaunchedEffect(updated) {
            delay(UPDATED_CUE_MS)
            updated = 0
        }
    }
    val listed = g.visuals.list.collectAsStateWithLifecycle().value.value
    val linkCtx = remember(origin, listed) { InternalLinks.Context(origin) { p -> listed?.any { it.path == p } == true } }
    val body = remember(text) { text?.let { Frontmatter.split(it).body } }
    val nodes = body?.let { com.assistant.core.markdown.ui.rememberMarkdown("memory:${key.path}", it, MarkdownCache.Shared) }
    val blocks = remember(nodes, linkCtx) { nodes?.let { TvMarkdown.blocks(it, linkCtx) } }

    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scroller = remember(list) { ReadingScroller(list, scope) }
    RemoteScreenEffect(
        control.remote,
        key,
        RemoteInfo(url = origin + MemoryLinkResolver.memoryFileUrl(key.path), title = docTitle(blocks, key.path), path = key.path),
        object : RemoteScreen {
            override fun reload(): Boolean { reloads++; return true }
            override fun scroll(direction: ScrollDirection): Boolean {
                scroller.page(direction, blocks?.size ?: 0)
                return true
            }
        },
    )
    val title = remember(blocks) { docTitle(blocks, key.path) }
    MemoryDocScreen(
        state = MemoryDocUi(key.path, title, if (error != null) emptyList() else blocks, error, updated > 0, rememberStatus(g)),
        actions = MemoryDocActions(
            onLink = { href ->
                when (val l = MemoryLinkResolver.resolve(key.path, href, linkCtx)) {
                    is MemoryLink.Document -> ctx.nav.push(MemoryDoc(l.path))
                    is MemoryLink.Anchor -> blocks?.indexOfFirst { it.anchor == l.id }?.takeIf { it >= 0 }?.let(scroller::focus)
                    is MemoryLink.Visual -> ctx.nav.push(Viewer(l.path))
                    is MemoryLink.External -> {
                        val scheme = l.url.substringBefore(':').lowercase()
                        if (scheme == "http" || scheme == "https") ctx.nav.push(Viewer(l.url)) else g.toasts.show("A TV can't open $scheme: links")
                    }
                    is MemoryLink.Server -> ctx.nav.push(Viewer(origin + l.path))
                }
            },
            onReload = { reloads++ },
            onBack = { ctx.nav.pop() },
        ),
        scroller = scroller,
    )
}

@Composable
fun MemoryDocScreen(state: MemoryDocUi, actions: MemoryDocActions, scroller: ReadingScroller, modifier: Modifier = Modifier) {
    val blocks = state.blocks
    val c = TvTheme.colors
    var focusedIndex by remember(state.path) { mutableIntStateOf(0) }
    var selectedLink by remember(state.path) { mutableIntStateOf(0) }
    val focusedHasLinks = blocks?.getOrNull(focusedIndex)?.links?.isNotEmpty() == true
    ScreenChromeEffect(
        ScreenChrome(
            title = state.title,
            hints = listOf(
                if (focusedHasLinks) KeyHints.Ok("Open link") else KeyHint(HintGlyph.OK, "Read"),
                KeyHints.Page(),
                KeyHints.Menu,
                KeyHints.Back,
            ),
            fullScreen = true,
            showHeader = false,
            options = listOf(
                TvOption("reload", "Reload", ArchieIcons.Refresh) { actions.onReload() },
                TvOption("top", "Back to the top", ArchieIcons.KeyboardArrowUp) { scroller.page(ScrollDirection.TOP, blocks?.size ?: 0) },
            ),
            keys = TvKeyHandler { key ->
                when (key) {
                    TvKey.REWIND -> { scroller.page(ScrollDirection.UP, blocks?.size ?: 0); true }
                    TvKey.FAST_FORWARD -> { scroller.page(ScrollDirection.DOWN, blocks?.size ?: 0); true }
                    else -> false
                }
            },
        ),
    )
    Column(modifier.fillMaxSize().testTag("memory-doc")) {
        DocHeader(state)
        when {
            state.error != null -> TvEmptyState("Could not open this note", state.error, Modifier.weight(1f), icon = ArchieIcons.Description)
            blocks == null -> TvEmptyState("Loading…", "", Modifier.weight(1f), icon = ArchieIcons.Description)
            blocks.isEmpty() -> TvEmptyState("This note is empty", "", Modifier.weight(1f), icon = ArchieIcons.Description)
            else -> {
                LaunchedEffect(state.path) {
                    withFrameNanos { }
                    runCatching { scroller.requesters[0]?.requestFocus() }
                }
                val style = tvMarkdownStyle()
                val colors = TvInlineColors(
                    link = style.linkColor,
                    selectedLink = c.onPrimaryContainer,
                    selectedLinkBackground = c.primaryContainer,
                    codeBackground = style.inlineCodeBackground,
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = scroller.list,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    itemsIndexed(blocks, key = { i, _ -> i }) { i, b ->
                        val req = remember { FocusRequester() }
                        DisposableEffect(req) {
                            scroller.requesters[i] = req
                            onDispose { if (scroller.requesters[i] === req) scroller.requesters.remove(i) }
                        }
                        var focused by remember { mutableStateOf(false) }
                        val extraTop = if (i > 0 && b.node is com.assistant.core.markdown.MdNode.Heading) 12.dp else 0.dp
                        ReadingBlockFrame(
                            focused = focused,
                            modifier = Modifier
                                .padding(top = extraTop)
                                .widthIn(max = READING_WIDTH)
                                .fillMaxWidth()
                                .focusRequester(req)
                                .onFocusChanged {
                                    focused = it.isFocused
                                    if (it.isFocused) {
                                        focusedIndex = i
                                        selectedLink = 0
                                    }
                                }
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key) {
                                        Key.DirectionDown -> scroller.stepInside(i, down = true)
                                        Key.DirectionUp -> scroller.stepInside(i, down = false)
                                        Key.DirectionRight -> if (b.links.isEmpty()) false else { selectedLink = (selectedLink + 1).coerceAtMost(b.links.lastIndex); true }
                                        Key.DirectionLeft -> if (b.links.isEmpty()) false else { selectedLink = (selectedLink - 1).coerceAtLeast(0); true }
                                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                            if (e.nativeKeyEvent.repeatCount == 0 && b.links.isNotEmpty()) actions.onLink(b.links[selectedLink.coerceIn(0, b.links.lastIndex)])
                                            b.links.isNotEmpty()
                                        }
                                        else -> false
                                    }
                                }
                                .focusable()
                                .testTag("doc-block-$i"),
                        ) {
                            TvMarkdownBlock(b, style, colors, selectedLink = if (focused) selectedLink else -1)
                        }
                    }
                }
            }
        }
    }
}

/** A reading block: on focus a 3 dp primary border and the raised surface, no scale (mockups: text blocks don't grow). */
@Composable
fun ReadingBlockFrame(focused: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = TvTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .background(if (focused) c.surfaceContainer else Color.Transparent, shape)
            .border(TvDimens.FocusBorder, if (focused) c.primary else Color.Transparent, shape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) { content() }
}

/** Mockup 13b's header: back arrow, note icon, title, path, "Updated", status cluster. */
@Composable
private fun DocHeader(state: MemoryDocUi) {
    val c = TvTheme.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ArchieIcons.ArrowBack, contentDescription = null, modifier = Modifier.size(24.dp), tint = c.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Icon(ArchieIcons.Description, contentDescription = null, modifier = Modifier.size(24.dp), tint = c.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(state.title, style = TvTheme.type.titleLarge, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            state.path,
            style = TvTheme.type.code.copy(fontSize = TvTheme.type.labelMedium.fontSize, lineHeight = TvTheme.type.labelMedium.lineHeight),
            color = c.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        if (state.updated) {
            Spacer(Modifier.width(12.dp))
            Box(Modifier.background(c.primaryContainer, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 3.dp).testTag("doc-updated")) {
                Text("Updated", style = TvTheme.type.labelMedium, color = c.onPrimaryContainer)
            }
        }
        Spacer(Modifier.weight(1f).widthIn(min = 16.dp))
        TvStatus(state.status)
    }
}

private val READING_WIDTH = 760.dp
private const val REFETCH_DEBOUNCE_MS = 300L
private const val UPDATED_CUE_MS = 2_500L
