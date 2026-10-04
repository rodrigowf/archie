package com.assistant.archie.feature.visuals

import android.app.Application
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.assistant.archie.feature.visuals.web.ArchieChromeClient
import com.assistant.archie.feature.visuals.web.ArchieWebViewClient
import com.assistant.archie.feature.visuals.web.ArchieWebViewConfig
import com.assistant.archie.feature.visuals.web.PageListener
import com.assistant.archie.feature.visuals.web.WebTrust
import com.assistant.core.network.CertificateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * The JVM half of spec 14 §6.3 `VisualWebViewSecurityTest` (the TLS proceed/cancel half runs on the
 * POCO_X7 emulator, see androidTest): the §4.2 settings table, the navigation rule, the pin rule,
 * and the denials of the chrome client.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WebViewSecurityConfigTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val external = mutableListOf<String>()
    private val listener = object : PageListener { override fun onExternal(url: String) { external += url } }

    @Test fun `settings table`() {
        val wv = WebView(context)
        ArchieWebViewConfig.configure(wv, WebTrust({ "http://192.168.0.200" }, { null }), { listener }, debug = false)
        val s = wv.settings
        assertTrue(s.javaScriptEnabled)
        assertTrue(s.domStorageEnabled)
        assertFalse(s.allowFileAccess)
        assertFalse(s.allowContentAccess)
        assertFalse(s.javaScriptCanOpenWindowsAutomatically)
        assertFalse(s.supportMultipleWindows())
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, s.mixedContentMode)
        assertTrue(wv.webViewClient is ArchieWebViewClient)
    }

    @Test fun `same origin loads in place, anything else leaves the WebView`() {
        val client = ArchieWebViewClient(WebTrust({ "ws://192.168.0.200:80" }, { null }), { listener })
        assertFalse(client.decide("http://192.168.0.200/energy/weekly-energy.html", mainFrame = true))
        assertFalse(client.decide("http://192.168.0.200:80/other.html#x", mainFrame = true))
        assertTrue(client.decide("https://192.168.0.200/x.html", mainFrame = true)) // other scheme = other origin
        assertTrue(client.decide("http://192.168.0.200:8765/x.html", mainFrame = true))
        assertTrue(client.decide("https://example.com/", mainFrame = true))
        assertTrue(client.decide("mailto:a@b.c", mainFrame = true))
        assertFalse(client.decide("https://www.youtube.com/embed/x", mainFrame = false)) // iframe stays
        assertEquals(listOf("https://192.168.0.200/x.html", "http://192.168.0.200:8765/x.html", "https://example.com/", "mailto:a@b.c"), external)
    }

    @Test fun `a certificate is accepted only when pinned for the server's own host`() {
        val cert = selfSigned()
        val pin = CertificateInfo.spkiSha256(cert)
        val pins = mutableMapOf("192.168.0.200:443" to pin)
        val trust = WebTrust({ "wss://192.168.0.200" }, { pins[it] })
        assertTrue(trust.allowsCertificate("https://192.168.0.200/a.html", cert))
        assertFalse(trust.allowsCertificate("https://evil.example/a.html", cert)) // another host
        assertFalse(trust.allowsCertificate("https://192.168.0.200:8443/a.html", cert)) // another port
        assertFalse(trust.allowsCertificate("https://192.168.0.200/a.html", null))
        pins["192.168.0.200:443"] = "AAAA"
        assertFalse(trust.allowsCertificate("https://192.168.0.200/a.html", cert)) // changed cert
        pins.clear()
        assertFalse(trust.allowsCertificate("https://192.168.0.200/a.html", cert)) // never pinned
        assertFalse(WebTrust({ "ws://192.168.0.200" }, { pin }).allowsCertificate("https://192.168.0.200/a.html", cert)) // cleartext server
    }

    @Test fun `chrome client denies permissions, geolocation, file choosers and windows`() {
        val chrome = ArchieChromeClient({ listener }, debug = false)
        var denied = false
        val req = object : PermissionRequest() {
            override fun getOrigin() = android.net.Uri.parse("http://192.168.0.200")
            override fun getResources() = arrayOf(RESOURCE_VIDEO_CAPTURE, RESOURCE_AUDIO_CAPTURE)
            override fun grant(resources: Array<out String>?) = error("must not grant")
            override fun deny() { denied = true }
        }
        chrome.onPermissionRequest(req)
        assertTrue(denied)
        var geo: Boolean? = null
        chrome.onGeolocationPermissionsShowPrompt("http://x", GeolocationPermissions.Callback { _, allow, _ -> geo = allow })
        assertEquals(false, geo)
        assertFalse(chrome.onShowFileChooser(null, null, null))
        assertFalse(chrome.onCreateWindow(null, false, true, null))
    }

    @Test fun `no javascript interface is ever added`() {
        // The module has no addJavascriptInterface call (a viz is arbitrary agent-written code).
        val src = java.io.File("src/main").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(src.contains("addJavascriptInterface("))
    }

    private fun selfSigned(): X509Certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(Base64.getMimeDecoder().decode(TestCert.PEM_BODY))) as X509Certificate
}
