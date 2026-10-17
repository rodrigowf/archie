package com.assistant.archie.feature.visuals

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.assistant.archie.feature.visuals.ui.VisualWebView
import com.assistant.core.design.theme.ArchieTheme
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A dead renderer (`onRenderProcessGone`) evicts the pooled WebView and the composable builds a new
 * one: the new WebView must actually be attached on screen (the AndroidView is keyed by the pool
 * entry), not left detached behind a FrameLayout that still holds the destroyed one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VisualWebViewRebuildTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val path = "energy/weekly-energy.html"

    @Test fun `a renderer crash attaches the rebuilt WebView`() {
        val deps = FakeVisualsDeps(app)
        compose.setContent { ArchieTheme { VisualWebView(deps, path, "http://192.168.0.200/$path", reload = 0) } }
        compose.waitForIdle()
        val first = deps.pool.peek(path)!!
        assertNotNull("the first WebView is on screen", first.webView.parent)

        compose.runOnIdle {
            // What ArchieWebViewClient.onRenderProcessGone does: evict, then tell the host.
            val host = first.listener
            deps.pool.evict(path)
            host!!.onRenderProcessGone()
        }
        compose.waitForIdle()

        val second = deps.pool.peek(path)!!
        assertNotSame(first.webView, second.webView)
        assertNull("the dead WebView left the screen", first.webView.parent)
        assertNotNull("the new WebView is on screen", second.webView.parent)
    }
}
