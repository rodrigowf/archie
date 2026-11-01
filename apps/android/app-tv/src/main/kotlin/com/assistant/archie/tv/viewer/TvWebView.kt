package com.assistant.archie.tv.viewer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.assistant.archie.feature.visuals.web.ArchieWebViewClient
import com.assistant.archie.feature.visuals.web.ArchieWebViewConfig
import com.assistant.archie.feature.visuals.web.PageListener
import com.assistant.archie.feature.visuals.web.WebTrust
import com.assistant.archie.feature.visuals.web.WebViewPool
import com.assistant.archie.tv.control.MediaAction
import com.assistant.archie.tv.control.ScrollDirection
import com.assistant.archie.tv.theme.TvTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The Viewer's page state and actions (spec 15 §3.5): one per Viewer entry. Compose reads the
 * observable fields; the options sheet, the key handler and the control channel call the actions.
 * The [webView] is attached by [ViewerWebPage] while the page is on screen (null in goldens).
 */
@Stable
class ViewerController(initialUrl: String) {
    var webView: WebView? = null
        internal set

    var url by mutableStateOf(initialUrl)
        internal set
    var pageTitle by mutableStateOf<String?>(null)
        internal set
    var progress by mutableIntStateOf(0)
        internal set
    var loading by mutableStateOf(true)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    var zoom by mutableIntStateOf(100)
        private set

    /** Pointer mode (mockup 11b): the D-pad moves [cursorX]/[cursorY]; OK taps there. */
    var pointerMode by mutableStateOf(false)
        private set
    var cursorX by mutableFloatStateOf(0f)
        private set
    var cursorY by mutableFloatStateOf(0f)
        private set

    /** The user set pointer mode by hand on this page: the automatic switch leaves it alone. */
    private var pointerChosen = false
    private var pointer: PointerModel? = null
    private var pressing = false

    internal fun attach(view: WebView) {
        webView = view
        val density = view.resources.displayMetrics.density
        pointer = PointerModel(view.width.toFloat(), view.height.toFloat(), density)
        syncCursor()
    }

    internal fun detach(view: WebView) {
        if (webView === view) webView = null
    }

    internal fun onSize(w: Int, h: Int) {
        val p = pointer ?: return
        p.resize(w.toFloat(), h.toFloat())
        syncCursor()
    }

    private fun syncCursor() {
        val p = pointer ?: return
        cursorX = p.x
        cursorY = p.y
    }

    fun setPointer(on: Boolean, byUser: Boolean = true) {
        if (byUser) pointerChosen = true
        if (on == pointerMode) return
        pointerMode = on
        if (on) {
            pointer?.center()
            syncCursor()
            hover()
        }
    }

    // ── page actions ─────────────────────────────────────────────────────────────────────────────

    fun reload() {
        if (rendererGone) return retry() // the rebuilt WebView has no page to reload
        val w = webView ?: return
        error = null
        w.reload()
    }

    /** Retry after an error: load the URL again (a failed first load has nothing to reload). */
    fun retry() {
        val w = webView ?: return
        rendererGone = false
        error = null
        w.loadUrl(url)
    }

    /**
     * The page's renderer died (`onRenderProcessGone`): the pooled WebView was evicted and a new one
     * is being built. The new one stays blank behind the error panel until Retry — a page that
     * crashes its renderer must not crash it again in a loop.
     */
    var rendererGone by mutableStateOf(false)
        private set

    internal fun onRendererGone() {
        rendererGone = true
        loading = false
        error = RENDERER_GONE_MESSAGE
    }

    /** Back in the page's history; false = nothing to go back to. */
    fun back(): Boolean {
        val w = webView ?: return false
        if (!w.canGoBack()) return false
        w.goBack()
        return true
    }

    fun scroll(direction: ScrollDirection, fraction: Double = 0.8): Boolean {
        val w = webView ?: return false
        w.evaluateJavascript(PageScripts.scroll(direction, fraction), null)
        return true
    }

    suspend fun media(action: MediaAction): Boolean? {
        val w = webView ?: return null
        return suspendCancellableCoroutine { c ->
            w.evaluateJavascript(PageScripts.media(action)) { r -> if (c.isActive) c.resume(PageScripts.parseMedia(r)) }
        }
    }

    fun zoomBy(delta: Int) {
        zoom = PageScripts.zoomStep(zoom, delta)
        webView?.evaluateJavascript(PageScripts.zoom(zoom), null)
    }

    /**
     * Hard reset (Menu): clear this origin's storage, cookies and the HTTP cache, then load the
     * page again from the network.
     */
    fun hardReset() {
        val w = webView ?: return
        val current = w.url ?: url
        val u = current.toUri()
        val origin = "${u.scheme}://${u.authority}"
        w.evaluateJavascript(PageScripts.CLEAR_STORAGE, null)
        runCatching { WebStorage.getInstance().deleteOrigin(origin) }
        runCatching {
            val cm = CookieManager.getInstance()
            cm.getCookie(current)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                cm.setCookie(current, "$name=; Max-Age=0; Path=/")
            }
            cm.flush()
        }
        w.clearCache(true)
        error = null
        w.loadUrl(current)
    }

    // ── keys (the WebView's OnKeyListener and WebViewClient.onUnhandledKeyEvent) ────────────────

    /** Pointer mode owns the D-pad and OK (via [KeyFrame]); otherwise the page (spatial navigation) gets them. */
    internal fun onKey(view: View, keyCode: Int, event: KeyEvent): Boolean {
        if (!pointerMode) return false
        val p = pointer ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val dir = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> PointerDir.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> PointerDir.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> PointerDir.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> PointerDir.DOWN
            else -> null
        }
        if (dir != null) {
            if (down) {
                if (p.width <= 0f) p.resize(view.width.toFloat(), view.height.toFloat())
                val m = p.move(dir, event.repeatCount)
                syncCursor()
                if (m.scrollX != 0 || m.scrollY != 0) wheel(m.scrollX, m.scrollY) else hover()
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down && event.repeatCount == 0) {
                touch(MotionEvent.ACTION_DOWN)
                pressing = true
            } else if (!down && pressing) {
                touch(MotionEvent.ACTION_UP)
                pressing = false
            }
            return true
        }
        return false
    }

    /** The page did not use a D-pad key (no link further that way): Up/Down scroll instead, focus stays here. */
    internal fun onUnhandledKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        val dpad = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!dpad) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> scroll(ScrollDirection.UP, KEY_SCROLL_FRACTION)
                KeyEvent.KEYCODE_DPAD_DOWN -> scroll(ScrollDirection.DOWN, KEY_SCROLL_FRACTION)
            }
        }
        return true
    }

    internal fun onPageFinished(view: WebView) {
        loading = false
        url = view.url ?: url
        pageTitle = view.title?.takeIf { it.isNotBlank() && !it.startsWith("http") }
        if (zoom != 100) view.evaluateJavascript(PageScripts.zoom(zoom), null)
        if (!pointerChosen) {
            view.evaluateJavascript(PageScripts.FOCUSABLES) { r ->
                if (r?.trim() == "0" && !pointerChosen) setPointer(true, byUser = false)
            }
        }
    }

    internal fun onPageStarted(newUrl: String) {
        if (rendererGone) return // the blank replacement WebView, before Retry
        loading = true
        error = null
        url = newUrl
    }

    private fun touch(action: Int) {
        val w = webView ?: return
        val t = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(t, t, action, cursorX, cursorY, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        w.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun mouseEvent(action: Int, configure: (MotionEvent.PointerCoords) -> Unit = {}): MotionEvent {
        val t = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply { x = cursorX; y = cursorY; configure(this) }
        return MotionEvent.obtain(t, t, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
    }

    /** Hover at the cursor, so the page shows its tooltips (mockup 11b "12:00 · 2.9 kWh"). */
    private fun hover() {
        val w = webView ?: return
        val ev = mouseEvent(MotionEvent.ACTION_HOVER_MOVE)
        w.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    /** A mouse-wheel event at the cursor: scrolls the box under it (positive = right / down). */
    private fun wheel(notchesX: Int, notchesY: Int) {
        val w = webView ?: return
        val ev = mouseEvent(MotionEvent.ACTION_SCROLL) { c ->
            c.setAxisValue(MotionEvent.AXIS_HSCROLL, notchesX.toFloat())
            c.setAxisValue(MotionEvent.AXIS_VSCROLL, -notchesY.toFloat()) // Android: positive VSCROLL = up
        }
        w.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    companion object {
        /** Up/Down at the page's edge of focus scroll this much of the viewport. */
        const val KEY_SCROLL_FRACTION = 0.4

        const val RENDERER_GONE_MESSAGE = "The page stopped working (its renderer crashed). Retry loads it again."
    }
}

/**
 * Delegates everything to `:feature:visuals`' [ArchieWebViewClient] (server-only TLS trust, other
 * origins handed to the host) and adds the TV's D-pad rule: a key the page did not use stays in
 * the Viewer ([ViewerController.onUnhandledKey]) instead of moving Android focus out of the WebView.
 */
internal class TvWebViewClient(
    private val base: ArchieWebViewClient,
    private val onUnhandledKey: (KeyEvent) -> Boolean,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = base.shouldOverrideUrlLoading(view, request)
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        base.onRenderProcessGone(view, detail) // evicts the dead WebView; the Viewer builds a new one
        return true
    }
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = base.onPageStarted(view, url, favicon)
    override fun onPageFinished(view: WebView, url: String) = base.onPageFinished(view, url)
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) = base.onReceivedError(view, request, error)
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = base.onReceivedSslError(view, handler, error)
    override fun onUnhandledKeyEvent(view: WebView, event: KeyEvent) {
        if (!onUnhandledKey(event)) super.onUnhandledKeyEvent(view, event)
    }
}

/**
 * The pooled WebView of one Viewer entry (spec 15 §3.5, §6.2): `:feature:visuals`' [WebViewPool]
 * (size 1) and ArchieWebView configuration (no JS bridge, no file access, server-pinned TLS), plus
 * the TV parts: focus, the D-pad/pointer key rules, media autoplay by remote key, links to other
 * sites loading in place (the TV has no browser), and live reload (spec 12 VZ-6) that keeps the
 * scroll position. The WebView is destroyed when the entry leaves (one WebView max, freed with the
 * Viewer).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ViewerWebPage(
    pool: WebViewPool,
    trust: WebTrust,
    debug: Boolean,
    poolKey: String,
    controller: ViewerController,
    live: Int,
    onLiveReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val background = TvTheme.colors.surface.toArgb()
    var generation by remember(poolKey) { mutableIntStateOf(0) }
    val token = remember(poolKey, generation) { Any() }
    val entry = remember(poolKey, generation) {
        pool.acquire(poolKey, context, token) { e ->
            ArchieWebViewConfig.configure(e.webView, trust, { e.listener }, debug, onGone = { pool.evict(e.key) })
            e.webView.webViewClient = TvWebViewClient(
                ArchieWebViewClient(trust, { e.listener }, onGone = { pool.evict(e.key) }),
                onUnhandledKey = { ev -> controller.onUnhandledKey(ev) },
            )
            e.webView.settings.mediaPlaybackRequiresUserGesture = false // remote keys are the user's gesture
            e.webView.setBackgroundColor(background)
            e.webView.isFocusable = true
            e.webView.isFocusableInTouchMode = true
            e.liveVersion = live
            if (!controller.rendererGone) e.webView.loadUrl(controller.url)
        }
    }
    DisposableEffect(entry) {
        val view = entry.webView
        controller.attach(view)
        val layout = View.OnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> controller.onSize(r - l, b - t) }
        view.addOnLayoutChangeListener(layout)
        entry.listener = object : PageListener {
            // Another site: load it here (the TV has no browser to hand it to). Other schemes stay inert.
            override fun onExternal(url: String) {
                val s = url.toUri().scheme?.lowercase()
                if (s == "http" || s == "https") view.loadUrl(url)
            }
            override fun onProgress(percent: Int) { controller.progress = percent }
            override fun onPageStarted(url: String) = controller.onPageStarted(url)
            override fun onPageFinished(url: String) = controller.onPageFinished(view)
            override fun onMainFrameError(url: String, description: String, certificate: Boolean) {
                controller.error = description
                controller.loading = false
            }
            override fun onRenderProcessGone() {
                controller.onRendererGone()
                generation++
            }
        }
        // The error panel (renderer crash) owns focus until Retry; otherwise the page takes it.
        if (controller.error == null) view.post { view.requestFocus() }
        onDispose {
            view.removeOnLayoutChangeListener(layout)
            controller.detach(view)
            pool.release(poolKey, token)
            // One WebView max (spec 15 §6.2): freed when the Viewer closes, not kept for later.
            if (pool.peek(poolKey)?.attached == false) pool.evict(poolKey)
        }
    }
    LaunchedEffect(entry, live) {
        if (live > entry.liveVersion) {
            delay(LIVE_RELOAD_DEBOUNCE_MS) // one reload per burst (VZ-6)
            entry.liveVersion = live
            controller.error = null
            entry.webView.reload() // keeps the scroll position
            onLiveReload()
        }
    }
    // Keyed by the pool entry: after a renderer crash the factory runs again and attaches the new
    // WebView (an unkeyed AndroidView keeps its first KeyFrame, holding the destroyed one).
    key(entry) {
        AndroidView(
            factory = { ctx ->
                KeyFrame(ctx) { ev -> controller.onKey(entry.webView, ev.keyCode, ev) }.apply {
                    (entry.webView.parent as? ViewGroup)?.removeView(entry.webView)
                    addView(entry.webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
            },
            modifier = modifier.fillMaxSize().testTag("viewer-webview"),
        )
    }
}

/**
 * The WebView's parent: sees every key before the WebView does. `WebView.dispatchKeyEvent` hands
 * keys straight to Chromium (an `OnKeyListener` on the WebView is never called), so Pointer mode
 * takes the D-pad here.
 */
@SuppressLint("ViewConstructor")
internal class KeyFrame(context: android.content.Context, private val onKey: (KeyEvent) -> Boolean) : FrameLayout(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = onKey(event) || super.dispatchKeyEvent(event)
}

/** Changes closer together than this reload once (spec 12 VZ-6). */
const val LIVE_RELOAD_DEBOUNCE_MS = 300L
