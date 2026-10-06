package com.assistant.core.network

import com.assistant.core.model.ConfigPatch
import com.assistant.core.model.VoiceConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.Source
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArchieApiTest {
    private lateinit var server: MockWebServer
    private val stack = HttpStack()
    private lateinit var base: String

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        // Stored URLs are ws://…; REST maps them to http:// (inv03 §3.1).
        base = server.url("/").toString().replace("http://", "ws://").trimEnd('/') + "/api/orchestrator/chat"
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun livePoolDecodesAndUsesHttpBase() = runBlocking {
        server.enqueue(json("""[{"local_id":"L","sdk_session_id":"S","status":"idle","cost":0.0,"turns":0,"title":"Orchestrator","is_orchestrator":true}]"""))
        val r = ArchieApi(stack) { base }.livePool()
        val row = (r as ApiResult.Ok).value.single()
        assertEquals("L", row.localId); assertEquals("S", row.sdkId); assertTrue(row.isOrchestrator)
        assertEquals("/api/sessions/pool/live", server.takeRequest().path)
    }

    @Test fun errorsAreValuesWithTheBackendDetail() = runBlocking {
        server.enqueue(json("""{"detail":"voice_vad_threshold must be in [0.15, 0.50]"}""", 400))
        val r = ArchieApi(stack) { base }.updateConfig(ConfigPatch(voiceVadThreshold = 0.9))
        assertEquals(ApiResult.HttpError(400, "voice_vad_threshold must be in [0.15, 0.50]", html = false), r)
        assertEquals("voice_vad_threshold must be in [0.15, 0.50]", r.errorMessage())
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("""{"voice_vad_threshold":0.9}""", req.body.readUtf8())   // partial PUT (CFG-1)

        server.shutdown()
        val down = ArchieApi(stack) { base }.listSessions()
        assertTrue("$down", down is ApiResult.NetworkError)
    }

    @Test fun pathsAndBodies() = runBlocking {
        val api = ArchieApi(stack) { base }
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(api.rename("sdk 1", "New title") is ApiResult.Ok)
        server.takeRequest().let { assertEquals("PATCH", it.method); assertEquals("/api/sessions/sdk%201/rename", it.path); assertEquals("""{"title":"New title"}""", it.body.readUtf8()) }

        server.enqueue(json("""{"messages":[],"total_count":0,"has_more":false,"start_index":0}"""))
        assertTrue(api.messages("S", limit = 500, before = 120) is ApiResult.Ok)
        assertEquals("/api/sessions/S/messages?limit=200&before=120", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(api.closePoolSession("L") is ApiResult.Ok)
        server.takeRequest().let { assertEquals("POST", it.method); assertEquals("/api/sessions/L/close", it.path) }

        server.enqueue(MockResponse().setBody("# Memory"))
        assertEquals("# Memory", api.memoryDocument("assistant/notes x.md").getOrNull())
        assertEquals("/memory/assistant/notes%20x.md", server.takeRequest().path)
    }

    @Test fun resolvePermissionPostsTheDecision_section6_9() = runBlocking {
        val api = ArchieApi(stack) { base }
        server.enqueue(json("""{"ok":true}"""))
        assertTrue(api.resolvePermission("L 1", "r1", allow = true) is ApiResult.Ok)
        server.takeRequest().let {
            assertEquals("POST", it.method)
            assertEquals("/api/sessions/L%201/permission", it.path)
            assertTrue(it.getHeader("Content-Type")!!.startsWith("application/json"))
            assertEquals("""{"request_id":"r1","decision":"allow"}""", it.body.readUtf8())
        }

        server.enqueue(json("""{"detail":"No pending permission request 'r2'"}""", 409))
        val r = api.resolvePermission("L", "r2", allow = false, message = "not now")
        assertEquals(409, (r as ApiResult.HttpError).code)
        assertEquals("""{"request_id":"r2","decision":"deny","message":"not now"}""", server.takeRequest().body.readUtf8())
    }

    @Test fun voiceSessionUsesQueryParamsAndOmitsNulls() = runBlocking {
        server.enqueue(json("""{"connection_info":{"connection_type":"webrtc","endpoint":"e","ephemeral_token":"t","model":"gpt-realtime"}}"""))
        val r = VoiceApi(stack) { base }.startVoiceSession(VoiceConfig(provider = "openai", voice = "cedar"))
        assertEquals("t", (r as ApiResult.Ok).value.ephemeralToken)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/orchestrator/voice/session?provider=openai&voice=cedar", req.path)
    }

    /** A source that produces [size] bytes without ever holding them (inv03 §3.4: no whole-file read). */
    private class GeneratedSource(private var remaining: Long) : Source {
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (remaining == 0L) return -1
            val n = minOf(byteCount, remaining, 8192)
            sink.write(ByteArray(n.toInt()) { 'a'.code.toByte() })
            remaining -= n
            return n
        }
        override fun timeout() = Timeout.NONE
        override fun close() = Unit
    }

    @Test fun uploadStreamsMultipartWithProgress() = runBlocking {
        val size = 5L * 1024 * 1024
        server.enqueue(json("""{"filename":"big.bin","path":"/srv/u/big.bin","url":"/uploads/x-big.bin","size":$size,"content_type":"application/octet-stream"}"""))
        var last = 0L
        val r = UploadClient(stack) { base }.upload(UploadSource("big.bin", null, size) { GeneratedSource(size) }) { w, _ -> last = w }
        assertEquals("/srv/u/big.bin", (r as ApiResult.Ok).value.path)
        assertEquals(size, last)
        val req = server.takeRequest()
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertTrue(req.bodySize > size)
        assertTrue(req.body.readUtf8(400).contains("name=\"file\"; filename=\"big.bin\""))
    }

    @Test fun nginxHtml413IsReportedAsTheUploadLimit() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(413).setHeader("Content-Type", "text/html")
            .setBody("<html><head><title>413 Request Entity Too Large</title></head></html>"))
        val r = UploadClient(stack) { base }.upload(UploadSource("a.txt", "text/plain", 3) { Buffer().writeUtf8("abc") })
        r as ApiResult.HttpError
        assertTrue(r.uploadTooLarge && r.html)
        assertEquals("File is larger than the server's 1 MB upload limit", r.errorMessage())
    }

    @Test fun discoveryAcceptsOnlyRealBackends_DISC1() = runBlocking {
        val discovery = ServerDiscovery(stack, ports = listOf(server.port))
        server.enqueue(json("""{"authenticated":false,"auth_url":"https://x","headless":true}"""))
        val found = discovery.probeHost("127.0.0.1")
        assertEquals(DiscoveredServer("127.0.0.1", server.port, secure = false), found)
        assertEquals("/api/auth/status", server.takeRequest().path)
        assertEquals("ws://127.0.0.1:${server.port}", found!!.serverUrl)

        server.enqueue(MockResponse().setBody("<html>Router login</html>"))          // a router on :80
        assertNull(discovery.probeHost("127.0.0.1"))
        server.enqueue(json("""{"authenticated":"yes","headless":false}"""))          // wrong types
        assertNull(discovery.probeHost("127.0.0.1"))
        server.enqueue(json("""{"status":"ok"}""", 200))
        assertNull(discovery.probeHost("127.0.0.1"))

        // Closed port: the TCP probe fails fast and no HTTP request is made.
        val closed = ServerDiscovery(stack, ports = listOf(1), tcpProbe = { _, _, _ -> false })
        assertNull(closed.probeHost("127.0.0.1"))
    }

    @Test fun scanSortsByLastOctet() = runBlocking {
        val d = ServerDiscovery(stack, ports = listOf(server.port), tcpProbe = { h, _, _ -> h == "127.0.0.1" })
        server.enqueue(json("""{"authenticated":true,"headless":false}"""))
        assertEquals(listOf("127.0.0.1"), d.scanHosts(listOf("127.0.0.9", "127.0.0.1")).map { it.host })
    }
}
