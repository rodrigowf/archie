package com.assistant.archie.feature.visuals

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.archie.feature.visuals.web.ArchieWebViewConfig
import com.assistant.archie.feature.visuals.web.WebTrust
import com.assistant.archie.feature.visuals.web.WebViewPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A pooled WebView is constructed on the Application ([WebViewPool]: an Activity would leak through
 * Chromium's construction-time lookups), so the popups that need an Activity must find it through the
 * pool's context wrapper when they open. On a device, with the WebView already moved to a second host
 * (rotation / screen change): a `<select>` opens its native popup and the choice reaches the page;
 * text input reaches a focused `<input>`; `alert` and `confirm` show the native dialog (OK → `true`).
 * With the Application as the base while attached, the `<select>` popup never opens (POCO X7; older
 * WebViews crash instead), which this test catches.
 */
@RunWith(AndroidJUnit4::class)
class PooledWebViewInteractionTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ui: UiAutomation by lazy {
        // One UiAutomation per device: a shell `uiautomator dump` (e.g. an install-prompt watcher) can hold it briefly.
        val deadline = SystemClock.uptimeMillis() + 15_000
        var automation: UiAutomation? = null
        while (automation == null) {
            automation = try {
                instr.uiAutomation
            } catch (e: IllegalStateException) {
                if (SystemClock.uptimeMillis() > deadline) throw e
                SystemClock.sleep(300)
                null
            }
        }
        automation
    }

    private val page = """
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
        <body style="margin:0">
        <select id="s" style="position:absolute;top:320px;left:24px;width:280px;height:56px;font-size:20px">
          <option value="a">Alpha option</option><option value="b">Bravo option</option><option value="c">Charlie option</option>
        </select>
        <input id="t" style="position:absolute;top:420px;left:24px;width:280px;height:56px;font-size:20px">
        </body></html>
    """.trimIndent()

    @Test fun selectInputAndJsDialogsWorkInAPooledWebView() {
        val app = instr.targetContext.applicationContext
        lateinit var pool: WebViewPool
        instr.runOnMainSync { pool = WebViewPool(app) }
        var webView: WebView? = null
        // First host: build and load (the WebView is constructed while the wrapper's base is the Application).
        ActivityScenario.launch(TestHostActivity::class.java).use { s ->
            s.onActivity { a ->
                // The production configuration (VisualWebView): its ArchieChromeClient leaves JS dialogs to the default UI.
                val e = pool.acquire("interactive.html", a, a) { e ->
                    ArchieWebViewConfig.configure(e.webView, WebTrust({ "https://archie.test" }, { null }), { e.listener }, debug = true)
                }
                webView = e.webView
                a.root.addView(e.webView, FrameLayout.LayoutParams(-1, -1))
                e.webView.loadDataWithBaseURL("https://archie.test/", page, "text/html", "utf-8", null)
            }
            waitFor("page loaded") { eval(webView!!, "document.getElementById('t') != null") == "true" }
            s.onActivity { a -> pool.release("interactive.html", a) }
        }
        // Second host: the same WebView, now on another Activity.
        ActivityScenario.launch(TestHostActivity::class.java).use { s ->
            var imm: InputMethodManager? = null
            s.onActivity { a ->
                val e = pool.acquire("interactive.html", a, a)
                assertSame(webView, e.webView)
                assertSame(a, e.baseContext)
                a.root.addView(e.webView, FrameLayout.LayoutParams(-1, -1))
                imm = a.getSystemService(InputMethodManager::class.java)
            }
            val wv = webView!!
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }

            // <select>: a real tap opens the native popup; picking an option reaches the page.
            waitFor("select popup") {
                if (popupItem("Bravo option") == null) tap { it.text?.toString() == "Alpha option" && it.className?.endsWith("TextView") != true }
                SystemClock.sleep(700)
                popupItem("Bravo option") != null
            }
            val item = popupItem("Bravo option")!!
            if (!item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tapScreen(Rect().also(item::getBoundsInScreen))
            waitFor("select value") { eval(wv, "document.getElementById('s').value") == "\"b\"" }

            // Text input: a tap focuses the <input> and connects the IME; typed keys reach the page.
            waitFor("input focus") {
                tap { it.className == "android.widget.EditText" }
                SystemClock.sleep(500)
                eval(wv, "document.activeElement.id") == "\"t\""
            }
            waitFor("IME connected") { var on = false; instr.runOnMainSync { on = imm!!.isActive(wv) }; on }
            instr.sendStringSync("viz")
            waitFor("typed text") { eval(wv, "document.getElementById('t').value") == "\"viz\"" }

            // alert / confirm: the native dialog shows; OK resolves confirm() to true.
            assertEquals("null", dialog(wv, "alert('Archie alert')", "Archie alert"))
            assertEquals("true", dialog(wv, "confirm('Archie confirm?')", "Archie confirm?"))

            s.onActivity { a -> pool.release("interactive.html", a) }
        }
        instr.runOnMainSync { pool.evict("interactive.html") }
    }

    /** Runs [js] (which opens a JS dialog showing [text]), presses OK on the dialog, returns the JS result. */
    private fun dialog(wv: WebView, js: String, text: String): String? {
        val done = CountDownLatch(1)
        var out: String? = null
        instr.runOnMainSync { wv.evaluateJavascript(js) { out = it; done.countDown() } }
        var ok: AccessibilityNodeInfo? = null
        waitFor("dialog '$text'") {
            ok = windows().firstOrNull { root -> root.findAccessibilityNodeInfosByText(text).isNotEmpty() }
                ?.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()
            ok != null
        }
        assertTrue(ok!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertTrue("no result from $js", done.await(10, TimeUnit.SECONDS))
        return out
    }

    /** An option row of the native select popup (a TextView in another window, not the page's own node). */
    private fun popupItem(text: String): AccessibilityNodeInfo? = windows().asSequence()
        .flatMap { it.findAccessibilityNodeInfosByText(text).asSequence() }
        .firstOrNull { it.className?.endsWith("TextView") == true }

    /** The roots of this process's windows (the Activity, the select popup, the JS dialogs). */
    private fun windows(): List<AccessibilityNodeInfo> =
        ui.windows.mapNotNull { it.root }.filter { it.packageName == instr.targetContext.packageName }

    /** Taps the centre of the page's node [match] (its accessibility bounds are in screen coordinates). */
    private fun tap(match: (AccessibilityNodeInfo) -> Boolean) {
        // Chromium builds its accessibility tree lazily: no node yet = nothing tapped, the caller's waitFor retries.
        val node = windows().asSequence().flatMap { it.all() }.firstOrNull(match) ?: return
        val r = Rect().also(node::getBoundsInScreen)
        tapScreen(r)
    }

    private fun AccessibilityNodeInfo.all(): Sequence<AccessibilityNodeInfo> =
        sequenceOf(this) + (0 until childCount).asSequence().mapNotNull(::getChild).flatMap { it.all() }

    private fun tapScreen(bounds: Rect) {
        val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
        val t = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 50)) {
            val ev = MotionEvent.obtain(t, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            assertTrue(ui.injectInputEvent(ev, true))
            ev.recycle()
        }
    }

    private fun eval(wv: WebView, js: String): String? {
        val latch = CountDownLatch(1)
        var out: String? = null
        instr.runOnMainSync { wv.evaluateJavascript(js) { out = it; latch.countDown() } }
        assertTrue("no result from $js", latch.await(10, TimeUnit.SECONDS))
        return out
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!check()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("timed out waiting for $what")
            SystemClock.sleep(200)
        }
    }
}
