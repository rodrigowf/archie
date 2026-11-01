package com.assistant.archie.feature.visuals

import com.assistant.archie.feature.visuals.ui.visualSections
import com.assistant.core.model.VisualInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list's Starred / All visuals split: server order kept, no duplicates, search filters both. */
class VisualSectionsTest {
    private fun v(path: String, title: String, favorite: Boolean = false) = VisualInfo(path, "/$path", title, null, null, 1, favorite)

    private val items = listOf(
        v("a.html", "Energy dashboard"),
        v("b.html", "Tarot canvas", favorite = true),
        v("c/index.html", "Energy forecast", favorite = true),
        v("d.html", "Music report"),
    )

    private fun paths(l: List<VisualInfo>) = l.map { it.path }

    @Test fun starredFirstThenTheRestEachInServerOrder() {
        val s = visualSections(items, "")
        assertEquals(listOf("b.html", "c/index.html"), paths(s.starred))
        assertEquals(listOf("a.html", "d.html"), paths(s.others))
    }

    @Test fun nothingStarredLeavesOneUnheadedList() {
        val s = visualSections(items.map { it.copy(favorite = false) }, "")
        assertTrue(s.starred.isEmpty())
        assertEquals(listOf("a.html", "b.html", "c/index.html", "d.html"), paths(s.others))
    }

    @Test fun searchFiltersBothSections() {
        val s = visualSections(items, "  energy ")
        assertEquals(listOf("c/index.html"), paths(s.starred))
        assertEquals(listOf("a.html"), paths(s.others))
        val byPath = visualSections(items, "c/ forecast")
        assertEquals(listOf("c/index.html"), paths(byPath.starred))
        assertTrue(byPath.others.isEmpty())
        assertTrue(visualSections(items, "nothing").isEmpty)
    }
}
