package com.assistant.archie.tv.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Bring-into-view for the reading views (conversation transcript, memory note). On a TV device
 * Compose's default spec pins the focused item to a pivot ~30 % from the start, so focusing the
 * block in the middle after Rewind / Fast-forward scrolled the list back and paging went in circles
 * (2026-10-17, the lamp session). These views scroll themselves (pages, tall blocks); focus only
 * needs the item visible: no scroll when it fits, else the shortest move.
 */
@OptIn(ExperimentalFoundationApi::class)
object ReadingBringIntoView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        readingScrollDistance(offset, size, containerSize)
}

/** Pure part of [ReadingBringIntoView]: [offset] of the item's leading edge in the viewport. */
fun readingScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
    val trailing = offset + size
    return when {
        offset >= 0f && trailing <= containerSize -> 0f        // fully visible
        size >= containerSize && offset <= 0f && trailing >= containerSize -> 0f   // taller than the view, covering it
        offset < 0f -> offset                                   // starts above: bring its start in
        else -> if (size > containerSize) offset else trailing - containerSize   // ends below
    }
}

/** Provides [ReadingBringIntoView] to a reading list. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReadingScroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides ReadingBringIntoView, content = content)
}
