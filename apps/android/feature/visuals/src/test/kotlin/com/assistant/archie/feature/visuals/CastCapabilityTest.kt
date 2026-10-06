package com.assistant.archie.feature.visuals

import com.assistant.core.network.ApiResult
import com.assistant.core.network.ArchieApi
import com.assistant.core.network.HttpStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Spec 14 §4.2 `CastCapabilityTest` (B-07 DoD) against a MockWebServer — never the Jetson (a real
 * `POST /api/visualizations/cast` would put something on Rodrigo's TV). Shipped BX-2:
 * `GET /api/visualizations/cast` → `{available, reason}`, `POST {path}` → `{ok, message}`.
 */
class CastCapabilityTest {
    private val server = MockWebServer()
    private lateinit var api: ArchieApi
    private lateinit var cast: CastController

    @Before fun setUp() {
        server.start()
        api = ArchieApi(HttpStack()) { server.url("/").toString() }
        cast = CastController(api::castProbe, api::castVisualization, CoroutineScope(Dispatchers.Unconfined))
    }

    @After fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun probe(response: MockResponse, check: Boolean = true): CastCapability = runBlocking {
        server.enqueue(response)
        cast.probeNow().also {
            if (check) {
                val req = server.takeRequest()
                assertEquals("GET", req.method)
                assertEquals("/api/visualizations/cast", req.path)
            }
        }
    }

    private fun awaitCapability(expected: CastCapability) {
        val end = System.currentTimeMillis() + 5_000
        while (cast.capability.value != expected && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(expected, cast.capability.value)
    }

    @Test fun `available true → Available`() =
        assertEquals(CastCapability.Available, probe(json(200, """{"available":true,"reason":""}""")))

    @Test fun `available false → Unavailable with the server's reason`() =
        assertEquals(CastCapability.Unavailable("No Fire TV connected over adb"), probe(json(200, """{"available":false,"reason":"No Fire TV connected over adb"}""")))

    @Test fun `404 → Unavailable`() = assertTrue(probe(json(404, """{"detail":"Not Found"}""")) is CastCapability.Unavailable)

    @Test fun `405 → Unavailable`() = assertTrue(probe(json(405, """{"detail":"Method Not Allowed"}""")) is CastCapability.Unavailable)

    @Test fun `SPA fallback html 200 (backend without BX-2) → Unavailable`() = assertTrue(
        probe(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<!doctype html><div id=root></div>")) is CastCapability.Unavailable,
    )

    @Test fun `IOException → Unknown, retried on the next connect`() {
        assertEquals(CastCapability.Unknown, probe(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START), check = false))
        server.enqueue(json(200, """{"available":true}"""))
        cast.onConnected()
        awaitCapability(CastCapability.Available)
    }

    @Test fun `ensure probes only while Unknown`() {
        server.enqueue(json(200, """{"available":true}"""))
        cast.ensure()
        awaitCapability(CastCapability.Available)
        cast.ensure()
        assertEquals(1, server.requestCount)
    }

    @Test fun `cast posts the path and reports success with the title`() = runBlocking {
        server.enqueue(json(200, """{"ok":true,"message":"Showing on TV: https://192.168.0.200/energy/weekly-energy.html"}"""))
        val r = cast.cast("energy/weekly-energy.html", "Weekly energy usage")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/visualizations/cast", req.path)
        assertEquals("""{"path":"energy/weekly-energy.html"}""", req.body.readUtf8())
        assertEquals(CastOutcome(true, "Showing “Weekly energy usage” on TV"), r)
    }

    @Test fun `ok false shows the server's message verbatim`() = runBlocking {
        server.enqueue(json(200, """{"ok":false,"message":"The TV did not respond in time"}"""))
        assertEquals(CastOutcome(false, "The TV did not respond in time"), cast.cast("a.html", "A"))
    }

    @Test fun `404 Visualization not found is shown verbatim and keeps the action`() = runBlocking {
        server.enqueue(json(200, """{"available":true}""")); cast.probeNow()
        server.enqueue(json(404, """{"detail":"Visualization not found"}"""))
        assertEquals(CastOutcome(false, "Visualization not found"), cast.cast("gone.html", "Gone"))
        assertEquals(CastCapability.Available, cast.capability.value)
    }

    @Test fun `405 on cast hides the action`() = runBlocking {
        server.enqueue(json(200, """{"available":true}""")); cast.probeNow()
        server.enqueue(MockResponse().setResponseCode(405).setBody("<html>405</html>"))
        val r = cast.cast("a.html", "A")
        assertEquals(false, r.ok)
        assertTrue(cast.capability.value is CastCapability.Unavailable)
    }

    @Test fun `network error on cast is reported`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val r = cast.cast("a.html", "A")
        assertEquals(false, r.ok)
        assertTrue(r.message.isNotBlank())
    }

    @Test fun `of maps untrusted to Unknown`() {
        assertEquals(CastCapability.Unknown, CastCapability.of(ApiResult.NetworkError(java.io.IOException("x"))))
    }
}
