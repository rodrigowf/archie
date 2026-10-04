package com.assistant.archie.feature.visuals

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.archie.feature.visuals.web.WebViewPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference

/**
 * Spec 14 §7 B-07 DoD: `WebViewPool` leak test on a device. A pooled WebView survives its Activity
 * (rotation / closing the screen) and keeps no reference to it: after release + destroy + GC the
 * Activity is collected while the WebView (and the page) stay in the pool. Heap-reachability check
 * (WeakReference + GC); LeakCanary is not in the catalog yet (catalog request filed).
 */
@RunWith(AndroidJUnit4::class)
class WebViewPoolLeakTest {
    private val instr = InstrumentationRegistry.getInstrumentation()

    @Test fun releasedWebViewDoesNotRetainTheActivity() {
        val app = instr.targetContext.applicationContext
        lateinit var pool: WebViewPool
        instr.runOnMainSync { pool = WebViewPool(app) }
        var ref: WeakReference<TestHostActivity>? = null
        var webView: WebView? = null
        ActivityScenario.launch(TestHostActivity::class.java).use { s ->
            s.onActivity { a ->
                ref = WeakReference(a)
                val e = pool.acquire("viz.html", a, a)
                webView = e.webView
                a.root.addView(e.webView, FrameLayout.LayoutParams(-1, -1))
                e.webView.loadData("<html><body><script>window.state=42</script>viz</body></html>", "text/html", "utf-8")
            }
            Thread.sleep(1500)
            s.onActivity { a -> pool.release("viz.html", a) }
        }
        // Second host (e.g. after rotation): same WebView, same page state.
        ActivityScenario.launch(TestHostActivity::class.java).use { s ->
            s.onActivity { a -> assertSame(webView, pool.acquire("viz.html", a, a).webView) }
            s.onActivity { a -> pool.release("viz.html", a) }
        }
        repeat(20) {
            if (ref!!.get() != null) {
                Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(200)
            }
        }
        assertNull("the first Activity leaked through the pooled WebView", ref!!.get())
        instr.runOnMainSync { assertEquals(1, pool.size); assertSame(app, pool.peek("viz.html")!!.baseContext) }
    }
}
