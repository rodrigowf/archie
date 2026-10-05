package com.assistant.archie

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * One scripted on-device Archie backend for the whole instrumented run (never the live Jetson):
 * the orchestrator WebSocket (`start` → `session_started`, `voice_stop` → `voice_ended`), the live
 * pool with one orchestrator, history, `POST /api/uploads` (records the body size; [uploadCode]
 * 413 returns nginx's HTML page) and `POST /api/orchestrator/voice/session`, which hangs so a voice
 * start stays in CONNECTING (no provider is ever reached).
 */
object TestBackend {
    val server = MockWebServer()
    val frames = CopyOnWriteArrayList<String>()
    val paths = CopyOnWriteArrayList<String>()

    /** `(fileName-ish header, body bytes)` of every upload. */
    val uploads = CopyOnWriteArrayList<Long>()
    @Volatile var uploadCode = 200
    val sockets = CopyOnWriteArrayList<WebSocket>()

    const val LOCAL_ID = "b09-orchestrator"
    const val SDK_ID = "b09-jsonl"

    val url: String by lazy {
        // Bind off the main thread (Application.onCreate runs on it).
        val t = Thread { start() }
        t.start()
        t.join()
        "ws://127.0.0.1:${server.port}"
    }

    fun reset() {
        frames.clear()
        paths.clear()
        uploads.clear()
        uploadCode = 200
    }

    fun frameTypes(): List<String> = frames.mapNotNull { runCatching { JSONObject(it).optString("type") }.getOrNull() }

    private fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                paths += "${request.method} $path"
                return when {
                    path.startsWith("/api/orchestrator/chat") -> MockResponse().withWebSocketUpgrade(socket)
                    path.startsWith("/api/sessions/pool/live") -> json("""[{"local_id":"$LOCAL_ID","sdk_session_id":"$SDK_ID","status":"idle","cost":0.0,"turns":0,"title":"B-09 test","is_orchestrator":true}]""")
                    path == "/api/sessions" || path.startsWith("/api/sessions?") -> json("[]")
                    path.startsWith("/api/sessions/") && path.contains("/messages") ->
                        json("""{"messages":[],"total_count":0,"has_more":false,"start_index":0}""")
                    path.startsWith("/api/uploads") -> upload(request)
                    path.startsWith("/api/orchestrator/voice/session") ->
                        MockResponse().setHeadersDelay(60, TimeUnit.SECONDS).setResponseCode(503).setBody("""{"detail":"scripted (B-09)"}""")
                    path.startsWith("/api/config/openai-key") -> json("""{"api_key":"sk-b09-test"}""")
                    path.startsWith("/api/auth/status") -> json("""{"authenticated":true,"headless":true}""")
                    path.startsWith("/api/config") -> json("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
    }

    private fun upload(request: RecordedRequest): MockResponse {
        val size = request.bodySize
        uploads += size
        if (uploadCode == 413) {
            return MockResponse().setResponseCode(413).setHeader("Content-Type", "text/html")
                .setBody("<html><head><title>413 Request Entity Too Large</title></head><body><center><h1>413 Request Entity Too Large</h1></center><hr><center>nginx</center></body></html>")
        }
        val n = uploads.size
        return json(
            """{"filename":"shared-$n.bin","path":"/home/rodrigo/uploads/shared-$n.bin","url":"/uploads/shared-$n.bin","size":$size,"content_type":"application/octet-stream"}""",
        )
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private val socket = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { sockets += webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) {
            frames += text
            when (runCatching { JSONObject(text).optString("type") }.getOrDefault("")) {
                "start" -> webSocket.send("""{"type":"session_started","session_id":"$LOCAL_ID","jsonl_id":"$SDK_ID","voice":false}""".encodeUtf8())
                "voice_stop" -> webSocket.send("""{"type":"voice_ended","reason":"user"}""".encodeUtf8())
            }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
    }
}
