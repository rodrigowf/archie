package com.assistant.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * TOFU pin flow (spec 14 §4.3) with real self-signed certificates (`src/test/resources/tls/` (`selfsigned.p12`, `other.p12`):
 * RSA 2048, `CN=192.168.0.200`, no SAN — like the Jetson's nginx cert — password `archie`).
 * Without a pin both the system trust and the default hostname check reject them.
 */
class TrustStoreTest {
    private val servers = mutableListOf<MockWebServer>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() {
        scope.cancel(); servers.forEach { runCatching { it.shutdown() } }
    }

    private fun keyStore(name: String): KeyStore = KeyStore.getInstance("PKCS12").apply {
        TrustStoreTest::class.java.getResourceAsStream("/tls/$name.p12")!!.use { load(it, "archie".toCharArray()) }
    }

    private fun leaf(name: String) = keyStore(name).getCertificate("archie") as X509Certificate

    private fun httpsServer(name: String, port: Int = 0): MockWebServer {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore(name), "archie".toCharArray())
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        return MockWebServer().apply {
            useHttps(ctx.socketFactory, false)
            start(port)
            servers += this
        }
    }

    private fun ok() = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"authenticated":true,"auth_url":null,"headless":false}""")

    @Test fun firstUseIsRejectedWithCertificateDetails_thenPinnedConnectsAndAChangedCertIsFlagged() = runBlocking {
        val pins = MemoryPinStore()
        val trust = TrustStore(pins)
        val stack = HttpStack(trust)
        var server = httpsServer("selfsigned")
        val baseUrl = server.url("/").toString().trimEnd('/')
        val api = ArchieApi(stack) { baseUrl }
        val hostPort = UrlScheme.hostPort(baseUrl)
        assertEquals(TrustMode.SYSTEM_TRUSTED, trust.modeFor(baseUrl))

        // 1. Trust on first use: the call fails with what the dialog shows.
        server.enqueue(ok())
        val first = api.authStatus()
        assertTrue("$first", first is ApiResult.Untrusted)
        val err = (first as ApiResult.Untrusted).error
        assertFalse(err.changed)
        assertEquals(hostPort, err.hostPort)
        assertEquals(CertificateInfo.spkiSha256(leaf("selfsigned")), err.certificate.spkiSha256)
        assertTrue(err.certificate.subject, err.certificate.subject.contains("CN=192.168.0.200"))
        assertEquals(32, err.certificate.spkiSha256Hex.split(":").size)

        // 2. The user confirms → pinned → the same host connects (hostname check passes via the pin).
        trust.trust(hostPort, err.certificate)
        assertEquals(TrustMode.PINNED_SELF_SIGNED, trust.modeFor(baseUrl))
        val second = api.authStatus()
        assertTrue("$second", second is ApiResult.Ok)

        // 3. A different certificate on the pinned host is an error, never a silent proceed.
        val port = server.port
        server.shutdown()
        server = httpsServer("other", port)
        server.enqueue(ok())
        stack.restClient(baseUrl).connectionPool.evictAll()
        val third = api.authStatus()
        assertTrue("$third", third is ApiResult.Untrusted)
        assertTrue((third as ApiResult.Untrusted).error.changed)
        assertEquals(CertificateInfo.spkiSha256(leaf("other")), third.error.certificate.spkiSha256)

        // 4. Re-pin ("Review new certificate") → works again.
        trust.trust(hostPort, third.error.certificate)
        assertTrue(api.authStatus() is ApiResult.Ok)
    }

    @Test fun pinnedWssSocketConnects() = runBlocking {
        val pins = MemoryPinStore()
        val server = httpsServer("selfsigned")
        val wsUrl = server.url("/api/orchestrator/chat").toString().replace("https", "wss")
        pins.savePin(UrlScheme.hostPort(wsUrl), CertificateInfo.spkiSha256(leaf("selfsigned")))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"status","status":"idle"}""") }
        }))
        val c = SocketClient(HttpStack(TrustStore(pins)), scope)
        // Subscribe before connecting: the server sends its frame as soon as the socket opens.
        val firstFrame = async(start = CoroutineStart.UNDISPATCHED) { c.events.first { it is SocketEvent.Frame } }
        c.connect(wsUrl)
        withTimeout(15_000) { c.state.first { it == SocketState.Open } }
        val e = withTimeout(15_000) { firstFrame.await() }
        assertEquals("status", (e as SocketEvent.Frame).frame.type)
    }

    @Test fun cleartextNeedsNoTrust() {
        assertEquals(TrustMode.CLEARTEXT, TrustStore(MemoryPinStore()).modeFor("ws://192.168.0.200:80"))
    }
}
