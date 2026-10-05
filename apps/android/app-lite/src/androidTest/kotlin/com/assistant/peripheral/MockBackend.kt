package com.assistant.peripheral

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scripted on-device backend (never the live Jetson): one orchestrator in the live pool, the
 * orchestrator WebSocket answering `start` with `session_started`, and
 * `POST /api/orchestrator/voice/session` failing — the G-01 error path (spec 14 §6.2).
 */
class MockBackend(private val voiceSessionCode: Int = 503) : AutoCloseable {
    val server = MockWebServer()
    val frames = CopyOnWriteArrayList<String>()
    val paths = CopyOnWriteArrayList<String>()

    val serverUrl: String get() = "ws://127.0.0.1:${server.port}"

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                paths += "${request.method} $path"
                return when {
                    path.startsWith("/api/orchestrator/chat") -> MockResponse().withWebSocketUpgrade(socket)
                    path.startsWith("/api/sessions/pool/live") -> json("""[{"local_id":"$LOCAL_ID","sdk_session_id":"$SDK_ID","status":"idle","is_orchestrator":true}]""")
                    path.startsWith("/api/orchestrator/voice/session") -> MockResponse().setResponseCode(voiceSessionCode).setBody("""{"detail":"scripted failure (G-01)"}""")
                    path.startsWith("/api/config/openai-key") -> json("""{"api_key":"sk-g01-test"}""")
                    path.startsWith("/api/auth/status") -> json("""{"authenticated":true,"headless":true}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    private val socket = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = Unit
        override fun onMessage(webSocket: WebSocket, text: String) {
            frames += text
            val type = runCatching { JSONObject(text).optString("type") }.getOrDefault("")
            when (type) {
                "start" -> webSocket.send("""{"type":"session_started","session_id":"$LOCAL_ID","jsonl_id":"$SDK_ID","voice":false}""")
                "voice_stop" -> webSocket.send("""{"type":"voice_ended","reason":"user"}""")
            }
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    override fun close() = server.shutdown()

    companion object {
        const val LOCAL_ID = "g01-orchestrator"
        const val SDK_ID = "g01-jsonl"
    }
}
