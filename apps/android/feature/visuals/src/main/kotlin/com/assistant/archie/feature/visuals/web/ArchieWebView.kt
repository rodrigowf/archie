package com.assistant.archie.feature.visuals.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.assistant.core.network.CertificateInfo
import com.assistant.core.network.UrlScheme
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Who the WebView trusts (spec 14 §4.2 "TLS errors", §4.3; VZ-3): the configured server only.
 * [serverUrl] is read on every decision so a server switch applies at once; [pinFor] is the TOFU
 * pin store A-03 fills (`SettingsStore.pinFor(host:port)`).
 */
class WebTrust(
    private val serverUrl: () -> String,
    private val pinFor: (hostPort: String) -> String?,
) {
    /** `http(s)://host[:port]` of the server, no trailing slash. */
    fun origin(): String = UrlScheme.httpBase(serverUrl())

    /** Same scheme, host and port as the server (default ports filled in). */
    fun isSameOrigin(url: String): Boolean {
        val u = Uri.parse(url)
        val o = Uri.parse(origin())
        val scheme = u.scheme?.lowercase() ?: return false
        if (scheme != o.scheme?.lowercase()) return false
        if (!u.host.equals(o.host, ignoreCase = true)) return false
        return effectivePort(u) == effectivePort(o)
    }

    /**
     * `onReceivedSslError`: proceed **only** when the page is on the server's own `host:port`, the
     * server is `https`, a pin exists for it and the leaf's SPKI SHA-256 equals the pin. Never a
     * blanket `proceed()`.
     */
    fun allowsCertificate(url: String, leaf: X509Certificate?): Boolean {
        if (leaf == null) return false
        val server = serverUrl()
        if (!UrlScheme.isSecure(server) || !isSameOrigin(url)) return false
        val pin = pinFor(UrlScheme.hostPort(server)) ?: return false
        return CertificateInfo.spkiSha256(leaf) == pin
    }

    private fun effectivePort(u: Uri): Int = when {
        u.port != -1 -> u.port
        u.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
}

/** What the page reports back to its host (one instance per pooled WebView, see [WebViewPool]). */
interface PageListener {
    fun onExternal(url: String)
    fun onProgress(percent: Int) {}
    fun onPageStarted(url: String) {}
    fun onPageFinished(url: String) {}

    /** The main frame failed ([description]) or its certificate was refused ([certificate] = true). */
    fun onMainFrameError(url: String, description: String, certificate: Boolean) {}

    /** The renderer died (crash or OOM kill); this WebView is unusable and must be replaced. */
    fun onRenderProcessGone() {}
}

/**
 * The `ArchieWebView` security configuration (spec 14 §4.2 table). A visualization is arbitrary
 * agent-written code, so: JS and DOM storage on (interactive vizzes, localStorage), no file or
 * content access, no popups, no mixed content, **no `addJavascriptInterface` ever**, no algorithmic
 * darkening (vizzes ship their own palettes). Clients: [ArchieWebViewClient], [ArchieChromeClient].
 */
object ArchieWebViewConfig {
    @SuppressLint("SetJavaScriptEnabled")
    fun applySettings(webView: WebView) {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            // Layout width = the view's width (like the web iframe); pinch-zoom without the +/- overlay.
            builtInZoomControls = true
            displayZoomControls = false
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            try {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
            } catch (_: UnsupportedOperationException) {
                // Older WebView APKs: darkening is not applied to non-dark-aware pages there anyway.
            }
        }
    }

    /** Settings + clients. [debug] routes `console.*` to logcat tag `ArchieViz`. */
    fun configure(webView: WebView, trust: WebTrust, listener: () -> PageListener?, debug: Boolean, onGone: () -> Unit = {}) {
        applySettings(webView)
        webView.webViewClient = ArchieWebViewClient(trust, listener, onGone)
        webView.webChromeClient = ArchieChromeClient(listener, debug)
    }
}

/**
 * Navigation and TLS (spec 14 §4.2): the server's own origin loads in place; any other main-frame
 * navigation (links, `target=_blank`, `window.open` once multiple windows are off) leaves the
 * WebView for a Custom Tab, the web's "no top navigation". TLS errors: [WebTrust.allowsCertificate].
 */
class ArchieWebViewClient(
    private val trust: WebTrust,
    private val listener: () -> PageListener?,
    /** Drops the dead WebView from the pool, also when no host is showing it. */
    private val onGone: () -> Unit = {},
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        decide(request.url.toString(), request.isForMainFrame)

    /** true = handled outside (the WebView does not navigate). Subframes stay in place (iframe parity). */
    fun decide(url: String, mainFrame: Boolean): Boolean {
        if (!mainFrame) return false
        val scheme = Uri.parse(url).scheme?.lowercase()
        if ((scheme == "http" || scheme == "https") && trust.isSameOrigin(url)) return false
        if (scheme == "about" || scheme == "javascript") return false
        listener()?.onExternal(url)
        return true
    }

    /** A dead renderer must not crash the app (lint MissingOnRenderProcessGone): the host replaces the WebView. */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        val host = listener()
        onGone()
        host?.onRenderProcessGone()
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        listener()?.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        listener()?.onPageFinished(url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) listener()?.onMainFrameError(request.url.toString(), error.description?.toString() ?: "Error ${error.errorCode}", false)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        if (trust.allowsCertificate(error.url, leafOf(error.certificate))) {
            handler.proceed()
        } else {
            handler.cancel()
            listener()?.onMainFrameError(error.url, "The server's certificate is not trusted", true)
        }
    }

    companion object {
        /** The leaf certificate of an [SslCertificate] (API 29+ directly; 26–28 via its saved state). */
        fun leafOf(cert: SslCertificate?): X509Certificate? {
            cert ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return cert.x509Certificate
            val bytes = SslCertificate.saveState(cert)?.getByteArray("x509-certificate") ?: return null
            return try {
                CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as? X509Certificate
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Permissions and windows (spec 14 §4.2): deny camera/mic/MIDI, geolocation and file choosers; no new windows. */
class ArchieChromeClient(
    private val listener: () -> PageListener?,
    private val debug: Boolean,
) : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest) = request.deny()

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) =
        callback.invoke(origin, false, false)

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean = false

    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        listener()?.onProgress(newProgress)
    }

    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        if (debug) Log.d("ArchieViz", "${message.messageLevel()} ${message.sourceId()}:${message.lineNumber()} ${message.message()}")
        return true
    }
}
