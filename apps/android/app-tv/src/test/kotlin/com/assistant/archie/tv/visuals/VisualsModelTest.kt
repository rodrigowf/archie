package com.assistant.archie.tv.visuals

import com.assistant.core.data.ContentStamp
import com.assistant.core.model.VisualInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Visuals cards (mockup 10): kind from path/title, file label, search, "changed since last open". */
class VisualsModelTest {
    @Test fun kinds() {
        assertEquals(VisualKind.DASHBOARD, VisualKind.of("visualizations/solar-dashboard.html", "Solar dashboard"))
        assertEquals(VisualKind.PROJECT, VisualKind.of("tv-app/index.html", "Archie TV plan"))
        assertEquals(VisualKind.EXPLAINER, VisualKind.of("visualizations/wakeword-timeline.html", "Wake word timeline"))
        assertEquals(VisualKind.MODEL, VisualKind.of("visualizations/qvcm-sim.html", "QVCM simulation"))
        assertEquals(VisualKind.REVIEW, VisualKind.of("tarot-review/index.html", "Tarot app screens"))
        assertEquals(VisualKind.PAGE, VisualKind.of("visualizations/showcase.html", "Showcase"))
        assertEquals("solar-dashboard.html", VisualKind.fileLabel("visualizations/solar-dashboard.html"))
        assertEquals("tv-app/", VisualKind.fileLabel("tv-app/index.html"))
        assertEquals("index.html", VisualKind.fileLabel("index.html"))
    }

    @Test fun cardsAndSearch() {
        val cards = listOf(
            VisualsModel.card(VisualInfo("visualizations/solar-dashboard.html", "/visualizations/solar-dashboard.html", "Solar dashboard", null, null, 1), changed = true),
            VisualsModel.card(VisualInfo("tv-app/index.html", "/tv-app/index.html", "", null, null, 1), changed = false),
        )
        assertEquals("index", cards[1].title) // blank title → file name
        assertEquals(cards, VisualsModel.filter(cards, "  "))
        assertEquals(listOf(cards[0]), VisualsModel.filter(cards, "DASH solar"))
        assertEquals(listOf(cards[1]), VisualsModel.filter(cards, "tv-app"))
        assertEquals(emptyList<VisualCardUi>(), VisualsModel.filter(cards, "solar tv"))
    }

    @Test fun changedSinceLastOpen() {
        val seen = VisualsSeen()
        val stamps = mapOf("a.html" to ContentStamp(2), "gone.html" to ContentStamp(3, deleted = true))
        assertTrue(VisualsSeen.changed("a.html", stamps, seen.seen.value))
        assertFalse(VisualsSeen.changed("b.html", stamps, seen.seen.value))
        assertFalse(VisualsSeen.changed("gone.html", stamps, seen.seen.value))
        seen.markOpened("a.html", 2)
        assertFalse(VisualsSeen.changed("a.html", stamps, seen.seen.value))
        seen.markOpened("a.html", 1) // never goes back
        assertFalse(VisualsSeen.changed("a.html", stamps, seen.seen.value))
        seen.requestFocus("a.html")
        assertEquals("a.html", seen.consumeFocus())
        assertEquals(null, seen.consumeFocus())
    }
}
