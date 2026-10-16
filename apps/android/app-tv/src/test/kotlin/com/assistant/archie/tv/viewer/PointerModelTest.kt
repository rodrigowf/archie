package com.assistant.archie.tv.viewer

import com.assistant.archie.tv.control.MediaAction
import com.assistant.archie.tv.control.ScrollDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pointer mode math (spec 15 §3.5, mockup 11b) and the page scripts' small helpers. */
class PointerModelTest {
    @Test fun startsCentredAndMovesByTheBaseStep() {
        val p = PointerModel(1920f, 1080f, density = 2f)
        assertEquals(960f, p.x)
        assertEquals(540f, p.y)
        val m = p.move(PointerDir.RIGHT, repeat = 0)
        assertEquals(960f + PointerModel.BASE_STEP_DP * 2f, m.x)
        assertEquals(0, m.scrollX)
        p.move(PointerDir.UP, 0)
        assertEquals(540f - 24f, p.y)
    }

    @Test fun aHeldKeyAcceleratesUpToTheCap() {
        assertEquals(12f, PointerModel.stepDp(0))
        assertEquals(18f, PointerModel.stepDp(1))
        assertEquals(42f, PointerModel.stepDp(5))
        assertEquals(PointerModel.MAX_STEP_DP, PointerModel.stepDp(100))
        val p = PointerModel(1920f, 1080f, 1f)
        val steps = (0..6).map { r -> val before = p.x; p.move(PointerDir.LEFT, r); before - p.x }
        assertTrue(steps.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun theCursorStopsAtTheEdgeThenPushingScrolls() {
        val p = PointerModel(400f, 300f, 1f)
        repeat(50) { p.move(PointerDir.DOWN, 3) }
        assertEquals(299f, p.y)
        val m = p.move(PointerDir.DOWN, 0)
        assertEquals(299f, m.y)
        assertEquals(1, m.scrollY)
        assertEquals(3, p.move(PointerDir.DOWN, 40).scrollY)
        repeat(50) { p.move(PointerDir.LEFT, 3) }
        assertEquals(0f, p.x)
        assertEquals(-1, p.move(PointerDir.LEFT, 0).scrollX)
        assertEquals(0, p.move(PointerDir.RIGHT, 0).scrollX)
    }

    @Test fun resizingKeepsTheCursorInProportion() {
        val p = PointerModel(1000f, 500f, 1f)
        p.move(PointerDir.RIGHT, 0)
        val fx = p.x / 1000f
        p.resize(2000f, 1000f)
        assertEquals(fx * 2000f, p.x, 0.01f)
        assertEquals(500f, p.y)
        val q = PointerModel(0f, 0f, 1f)
        q.resize(800f, 600f)
        assertEquals(400f, q.x)
    }

    @Test fun pageScripts() {
        assertEquals(110, PageScripts.zoomStep(100, 1))
        assertEquals(90, PageScripts.zoomStep(100, -1))
        assertEquals(200, PageScripts.zoomStep(200, 1))
        assertEquals(50, PageScripts.zoomStep(50, -3))
        assertEquals(110, PageScripts.zoomStep(123, 1)) // off-grid values restart from 100 %
        assertEquals(true, PageScripts.parseMedia("\"playing\""))
        assertEquals(false, PageScripts.parseMedia("\"paused\""))
        assertEquals(null, PageScripts.parseMedia("\"none\""))
        assertEquals(null, PageScripts.parseMedia(null))
        assertTrue("('bottom',0.8)" in PageScripts.scroll(ScrollDirection.BOTTOM))
        assertTrue("('toggle')" in PageScripts.media(MediaAction.TOGGLE))
        assertTrue("addJavascriptInterface" !in PageScripts.FOCUSABLES)
    }

    @Test fun viewerUrlsEncodeSegments() {
        assertEquals("https://192.168.0.200/visualizations/my%20chart.html", viewerUrl("visualizations/my chart.html", "wss://192.168.0.200"))
        assertEquals("tv-app/index.html", viewerVisualPath("/tv-app/", "https://192.168.0.200"))
        assertEquals("tv-app/index.html", viewerVisualPath("https://192.168.0.200/tv-app/index.html", "https://192.168.0.200"))
        assertEquals(null, viewerVisualPath("https://example.com/x.html", "https://192.168.0.200"))
    }
}
