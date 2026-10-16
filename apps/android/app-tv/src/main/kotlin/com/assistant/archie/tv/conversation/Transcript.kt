package com.assistant.archie.tv.conversation

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.assistant.archie.feature.chat.ChatAction
import com.assistant.archie.feature.chat.ui.ToolCardRenderer
import com.assistant.core.model.SessionKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Transcript column width (spec 15 §3.5: max 760 dp, centered). */
val TranscriptMaxWidth = 760.dp

/**
 * The reading state of one transcript list: which block has focus, where each composed block sits,
 * and how to scroll / page / follow the tail (spec 15 §3.5). The list is `reverseLayout` (index 0 =
 * newest at the bottom), so streaming growth stays pinned without per-delta scroll calls, as in
 * the apps; all geometry here is read from layout coordinates, so it does not care about the
 * direction.
 */
@Stable
class TranscriptController(val listState: LazyListState, private val scope: CoroutineScope, private val reduceMotion: Boolean) {
    internal val coords = HashMap<String, LayoutCoordinates>()
    internal val requesters = HashMap<String, FocusRequester>()
    internal var listCoords: LayoutCoordinates? = null

    /** The block (item key) that has focus, or null (focus is elsewhere: composer, header). */
    var focusedKey by mutableStateOf<String?>(null)
        internal set

    /** Keys of items that can take focus, newest first (as laid out). */
    internal var order: List<String> = emptyList()

    private fun bounds(c: LayoutCoordinates): Pair<Float, Float> {
        val y = c.positionInRoot().y
        return y to y + c.size.height
    }

    private fun viewport(): Pair<Float, Float>? = listCoords?.takeIf { it.isAttached }?.let { bounds(it) }

    /** Moves the content up by [px] (negative: down), whatever the layout direction. */
    private suspend fun moveContent(px: Float, animate: Boolean): Float {
        // reverseLayout: scrolling forward shows older items (above), i.e. moves content down.
        val delta = -px
        return if (animate && !reduceMotion) {
            listState.animateScrollBy(delta, tween(160))
            delta
        } else {
            listState.scrollBy(delta)
        }
    }

    /** Up/Down on a block taller than the viewport: scroll inside it first. True = consumed. */
    fun stepInside(down: Boolean): Boolean {
        val k = focusedKey ?: return false
        val c = coords[k]?.takeIf { it.isAttached } ?: return false
        val (vt, vb) = viewport() ?: return false
        val (top, bottom) = bounds(c)
        val d = TranscriptModel.internalScroll(top, bottom, vt, vb, down) ?: return false
        scope.launch { moveContent(d, animate = true) }
        return true
    }

    /** Rewind / Fast-forward: a page (0.8 viewport), then focus the block now in the middle. */
    fun page(down: Boolean, newest: String?) {
        val (vt, vb) = viewport() ?: return
        scope.launch {
            val want = TranscriptModel.PAGE_STEP * (vb - vt) * (if (down) 1f else -1f)
            val moved = kotlin.math.abs(moveContent(want, animate = false)) > 1f
            withFrameNanos { }
            val (t, b) = viewport() ?: return@launch
            val placed = order.mapNotNull { k ->
                coords[k]?.takeIf { it.isAttached }?.let { c -> bounds(c).let { (top, bottom) -> TranscriptModel.Placed(k, top, bottom) } }
            }
            val target = TranscriptModel.afterPage(placed, focusedKey, t, b, moved, down, newest)
            if (target != null && target != focusedKey) runCatching { requesters[target]?.requestFocus() }
        }
    }

    /** Focuses block [key] (follow the tail, initial focus). False when it is not composed. */
    fun focus(key: String): Boolean = runCatching { requesters[key]?.requestFocus(); requesters[key] != null }.getOrDefault(false)

    /** Back to the newest item (the composer took focus). */
    fun toBottom() {
        scope.launch { listState.scrollToItem(0) }
    }
}

@Composable
fun rememberTranscriptController(listState: LazyListState, reduceMotion: Boolean): TranscriptController {
    val scope = rememberCoroutineScope()
    return remember(listState) { TranscriptController(listState, scope, reduceMotion) }
}

/**
 * The transcript list (spec 15 §3.5): every item a focus stop; Up/Down walk blocks, a block taller
 * than the viewport scrolls inside first; keys and content types are the items' spec-12 keys.
 * Reaching the top loads the previous page. When focus sits on the newest block it follows the tail.
 */
@Composable
fun Transcript(
    items: List<TItem>,
    kind: SessionKind,
    toolCards: ToolCardRenderer,
    controller: TranscriptController,
    onAction: (ChatAction) -> Unit,
    onLongPress: (entryId: String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    val listState = controller.listState
    val reversed = remember(items) { items.asReversed() }
    controller.order = remember(reversed) { reversed.map { it.key } }
    val newest = remember(items) { TranscriptModel.newestFollowable(items) }

    // Follow the tail: the list stays at the bottom when it was there (apps' FollowNewest), and focus
    // moves to the new newest block when it sat on the previous one (never onto a card, FOCUS-1).
    val newestKey = reversed.firstOrNull()?.key
    val last = remember { arrayOfNulls<Any>(1) }
    SideEffect {
        if (newestKey == last[0]) return@SideEffect
        last[0] = newestKey
        val atBottom = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 48
        if (atBottom && !listState.isScrollInProgress) listState.requestScrollToItem(0)
    }
    var previousNewest by remember { mutableStateOf(newest) }
    LaunchedEffect(newest) {
        val to = TranscriptModel.followTo(controller.focusedKey, previousNewest, newest)
        previousNewest = newest
        if (to != null) {
            withFrameNanos { }
            controller.focus(to)
        }
    }

    // Load older: the oldest loaded item is within 3 of the top (the ViewModel guards repeats).
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 1 - 3
        }.distinctUntilChanged().filter { it }.collect { onAction(ChatAction.LoadOlder) }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier
                .widthIn(max = TranscriptMaxWidth)
                .fillMaxSize()
                .onPlaced { controller.listCoords = it }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> controller.stepInside(down = true)
                        Key.DirectionUp -> controller.stepInside(down = false)
                        else -> false
                    }
                }
                .testTag("transcript"),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 12.dp),
        ) {
            items(
                count = reversed.size,
                key = { reversed[it].key },
                contentType = { reversed[it].contentType },
            ) { i ->
                val item = reversed[i]
                val requester = remember { FocusRequester() }
                DisposableEffect(item.key) {
                    controller.requesters[item.key] = requester
                    onDispose {
                        if (controller.requesters[item.key] === requester) controller.requesters.remove(item.key)
                        controller.coords.remove(item.key)
                    }
                }
                val itemModifier = Modifier
                    .focusRequester(requester)
                    .onFocusChanged { s ->
                        if (s.hasFocus) controller.focusedKey = item.key
                        else if (controller.focusedKey == item.key) controller.focusedKey = null
                    }
                    .onPlaced { controller.coords[item.key] = it }
                    .testTag("t:${item.key}")
                TranscriptItem(item, kind, toolCards, onAction, onLongPress, itemModifier, readOnly)
            }
        }
    }
}
