package com.assistant.archie.feature.visuals

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.MutableContextWrapper
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.assistant.archie.feature.visuals.web.WebViewPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference

/**
 * `WebViewPool` (spec 14 §4.2 "State retention"): LRU of 3 by path, a visible WebView is never
 * evicted, the WebView survives its host (tab switch / rotation), and no Activity is retained after
 * release (JVM leak check; the device run is in androidTest `WebViewPoolLeakTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WebViewPoolTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `same key returns the same WebView across hosts (rotation, tab switch)`() {
        val pool = WebViewPool(app)
        val a1 = Robolectric.buildActivity(Activity::class.java).setup().get()
        val first = pool.acquire("a.html", a1).webView
        pool.release("a.html")
        val a2 = Robolectric.buildActivity(Activity::class.java).setup().get()
        val entry = pool.acquire("a.html", a2)
        assertSame(first, entry.webView)
        assertSame(a2, entry.baseContext)
    }

    @Test fun `the WebView is constructed on the Application, then sees its host`() {
        // Chromium keeps construction-time lookups (AutofillManager → Activity): never build on the Activity.
        var constructedOn: Context? = null
        val pool = WebViewPool(app, create = { ctx -> constructedOn = (ctx as MutableContextWrapper).baseContext; WebView(ctx) })
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val e = pool.acquire("a.html", act)
        assertSame(app, constructedOn)
        assertSame(act, e.baseContext)
    }

    @Test fun `release swaps the base context to the Application and drops the parent`() {
        val pool = WebViewPool(app)
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val e = pool.acquire("a.html", act)
        FrameLayout(act).addView(e.webView)
        pool.release("a.html")
        assertSame(app, e.baseContext)
        assertNull(e.webView.parent)
        assertTrue(!e.attached)
    }

    @Test fun `LRU of three, attached entries are never evicted`() {
        val pool = WebViewPool(app)
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        pool.acquire("visible.html", act) // stays attached
        for (k in listOf("1.html", "2.html", "3.html")) { pool.acquire(k, act); pool.release(k) }
        assertEquals(3, pool.size)
        assertEquals(listOf("visible.html", "2.html", "3.html"), pool.keys.sorted().let { listOf("visible.html") + (pool.keys - "visible.html").sorted() })
        assertTrue("visible.html" in pool.keys && "1.html" !in pool.keys)
    }

    @Test fun `a stale host's release does not detach the new host`() {
        val pool = WebViewPool(app)
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val oldOwner = Any(); val newOwner = Any()
        pool.acquire("a.html", act, oldOwner)
        val e = pool.acquire("a.html", act, newOwner)
        pool.release("a.html", oldOwner)
        assertTrue(e.attached)
        assertSame(act, e.baseContext)
    }

    @Test fun `memory pressure evicts all but the visible one, UI_HIDDEN keeps them`() {
        val pool = WebViewPool(app)
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        pool.acquire("visible.html", act)
        pool.acquire("hidden.html", act); pool.release("hidden.html")
        pool.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(2, pool.size)
        pool.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(listOf("visible.html"), pool.keys)
    }

    @Test fun `evicted WebViews are new on the next acquire`() {
        val pool = WebViewPool(app)
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val first = pool.acquire("a.html", act).webView
        pool.release("a.html"); pool.evict("a.html")
        assertNotSame(first, pool.acquire("a.html", act).webView)
    }

    @Test fun `a released WebView does not retain its Activity`() {
        val pool = WebViewPool(app)
        var controller: org.robolectric.android.controller.ActivityController<Activity>? = Robolectric.buildActivity(Activity::class.java).setup()
        val ref = WeakReference(controller!!.get())
        val e = pool.acquire("a.html", controller.get())
        FrameLayout(controller.get()).addView(e.webView)
        pool.release("a.html")
        controller.pause().stop().destroy()
        controller = null
        repeat(10) { if (ref.get() != null) { System.gc(); System.runFinalization(); Thread.sleep(50) } }
        assertNull("Activity leaked through the pooled WebView", ref.get())
        assertEquals(1, pool.size) // the page itself is kept
    }
}
