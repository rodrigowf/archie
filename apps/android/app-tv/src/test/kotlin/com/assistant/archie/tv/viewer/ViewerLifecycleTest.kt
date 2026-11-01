package com.assistant.archie.tv.viewer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B4 (KEEP_AWAKE only while the Viewer is in front) and B2 (a renderer crash shows the Retry panel). */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerLifecycleTest {
    @Test
    fun keepAwakePingsOnlyWhileInFront() = runTest {
        val foreground = MutableStateFlow(true)
        var pings = 0
        val job = launch { keepAwakeWhileForeground(foreground, everyMs = 60_000) { pings++ } }
        runCurrent()
        assertEquals("at once", 1, pings)
        advanceTimeBy(60_001)
        assertEquals(2, pings)

        foreground.value = false // the app went to the background
        runCurrent()
        advanceTimeBy(10 * 60_000)
        assertEquals("never from the background", 2, pings)

        foreground.value = true // back in front: ping at once, then every minute
        runCurrent()
        assertEquals(3, pings)
        advanceTimeBy(60_001)
        assertEquals(4, pings)
        job.cancel()
    }

    @Test
    fun rendererCrashShowsRetryAndTheBlankReplacementDoesNotClearIt() {
        val c = ViewerController("http://h/a.html")
        c.onRendererGone()
        assertTrue(c.rendererGone)
        assertEquals(ViewerController.RENDERER_GONE_MESSAGE, c.error)
        assertFalse(c.loading)
        c.onPageStarted("about:blank") // the rebuilt WebView before Retry
        assertEquals(ViewerController.RENDERER_GONE_MESSAGE, c.error)
        assertEquals("http://h/a.html", c.url)
        c.retry() // no WebView attached in a JVM test: nothing loads, the panel stays
        assertTrue(c.rendererGone)
        assertNull(c.webView)
    }
}
