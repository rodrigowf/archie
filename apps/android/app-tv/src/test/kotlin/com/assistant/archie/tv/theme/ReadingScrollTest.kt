package com.assistant.archie.tv.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** The reading views' bring-into-view: no pivot, the shortest move (the paging loop of 2026-10-17). */
class ReadingScrollTest {
    @Test
    fun aVisibleItemNeverScrolls() {
        // the TV default would move this block to its 30 % pivot and undo a Rewind page
        assertEquals(0f, readingScrollDistance(offset = 400f, size = 100f, containerSize = 1000f))
        assertEquals(0f, readingScrollDistance(offset = 0f, size = 1000f, containerSize = 1000f))
    }

    @Test
    fun partlyHiddenItemsMoveTheShortestWay() {
        assertEquals(-50f, readingScrollDistance(offset = -50f, size = 100f, containerSize = 1000f))
        assertEquals(80f, readingScrollDistance(offset = 980f, size = 100f, containerSize = 1000f))
    }

    @Test
    fun tallBlocksScrollInsideThemselvesNotByFocus() {
        assertEquals(0f, readingScrollDistance(offset = -300f, size = 2000f, containerSize = 1000f))
        assertEquals(500f, readingScrollDistance(offset = 500f, size = 2000f, containerSize = 1000f))   // its start comes to the top
    }
}
