package com.assistant.archie.feature.visuals

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.archie.feature.visuals.web.ArchieWebViewConfig
import com.assistant.archie.feature.visuals.web.PageListener
import com.assistant.archie.feature.visuals.web.WebTrust
import com.assistant.core.network.CertificateInfo
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Spec 14 §6.3 `VisualWebViewSecurityTest` (instrumented, POCO_X7): a real WebView with the
 * `ArchieWebView` configuration. JS on; file access off; a foreign navigation leaves the WebView;
 * against a local HTTPS MockWebServer with a self-signed certificate, the pinned certificate loads
 * (proceed) and an unpinned one is refused (cancel).
 */
@RunWith(AndroidJUnit4::class)
class VisualWebViewSecurityTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val server = MockWebServer()
    private var scenario: ActivityScenario<TestHostActivity>? = null

    @After fun tearDown() {
        scenario?.close()
        server.shutdown()
    }

    private class Recorder : PageListener {
        val external = CopyOnWriteArrayList<String>()
        val finished = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<Pair<String, Boolean>>()
        @Volatile var latch = CountDownLatch(1)
        override fun onExternal(url: String) { external += url; latch.countDown() }
        override fun onPageFinished(url: String) { finished += url; latch.countDown() }
        override fun onMainFrameError(url: String, description: String, certificate: Boolean) { errors += url to certificate; latch.countDown() }
    }

    private fun webView(trust: WebTrust, rec: Recorder): WebView {
        var wv: WebView? = null
        scenario = ActivityScenario.launch(TestHostActivity::class.java).onActivity { a ->
            wv = WebView(a).also {
                ArchieWebViewConfig.configure(it, trust, { rec }, debug = true)
                a.root.addView(it, FrameLayout.LayoutParams(-1, -1))
            }
        }
        return wv!!
    }

    private fun onMain(block: () -> Unit) = instr.runOnMainSync(block)

    private fun evalJs(wv: WebView, js: String): String? {
        val latch = CountDownLatch(1)
        var out: String? = null
        onMain { wv.evaluateJavascript(js) { out = it; latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return out
    }

    private fun load(wv: WebView, rec: Recorder, url: String) {
        rec.latch = CountDownLatch(1)
        onMain { wv.loadUrl(url) }
        assertTrue("no callback for $url", rec.latch.await(20, TimeUnit.SECONDS))
    }

    @Test fun javascriptOn_fileAccessOff_foreignNavigationLeaves() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body><a id=x href='https://example.com/out'>out</a></body></html>"))
        server.start()
        val origin = server.url("/").toString().trimEnd('/')
        val rec = Recorder()
        val wv = webView(WebTrust({ origin }, { null }), rec)
        load(wv, rec, "$origin/viz.html")
        assertEquals("2", evalJs(wv, "1+1"))
        var file = true; var content = true
        onMain { file = wv.settings.allowFileAccess; content = wv.settings.allowContentAccess }
        assertFalse(file); assertFalse(content)
        // file:// is not readable from the page either.
        assertEquals("false", evalJs(wv, "(function(){try{var r=new XMLHttpRequest();r.open('GET','file:///system/etc/hosts',false);r.send();return r.responseText.length>0}catch(e){return false}})()"))
        rec.latch = CountDownLatch(1)
        onMain { wv.evaluateJavascript("document.getElementById('x').click()", null) }
        assertTrue(rec.latch.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("https://example.com/out"), rec.external.toList())
        var url: String? = null
        onMain { url = wv.url }
        assertTrue("the WebView must stay on the viz, was $url", url!!.startsWith(origin))
    }

    @Test fun pinnedCertificateProceeds_unpinnedIsCancelled() {
        val ks = KeyStore.getInstance("PKCS12")
        instr.context.assets.open("archie-test.p12").use { ks.load(it, "archie".toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "archie".toCharArray()) }
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server.useHttps(ssl.socketFactory, false)
        repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>pinned ok</body></html>")) }
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        val leaf = ks.getCertificate("archie") as X509Certificate
        val pins = HashMap<String, String>()
        val serverUrl = "https://127.0.0.1:${server.port}"
        val rec = Recorder()
        val wv = webView(WebTrust({ serverUrl }, { pins[it] }), rec)

        // Unpinned → cancel + certificate error, nothing rendered.
        load(wv, rec, "$serverUrl/viz.html")
        assertEquals(1, rec.errors.size)
        assertTrue(rec.errors[0].second)
        assertEquals(0, server.requestCount)

        // Pinned (TOFU confirmed) → proceed and render. The WebView remembers a cancel per host, so clear it.
        pins["127.0.0.1:${server.port}"] = CertificateInfo.spkiSha256(leaf)
        onMain { wv.clearSslPreferences() }
        rec.errors.clear()
        load(wv, rec, "$serverUrl/viz2.html")
        assertTrue("errors=${rec.errors}", rec.errors.isEmpty())
        assertEquals("\"pinned ok\"", evalJs(wv, "document.body.innerText"))
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertNull(rec.external.firstOrNull())
    }
}
