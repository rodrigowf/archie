package com.assistant.core.session

import com.assistant.core.network.ArchieApi
import com.assistant.core.network.HttpStack
import com.assistant.core.network.SocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * End to end over MockWebServer: real [SocketClient] + REST pool probe. Cold start finds an empty
 * pool (adopted on the 400 ms retry), the server drops the socket, the client reconnects after the
 * first T-13 backoff step (1 s ± 20 %; reset by `session_started`), re-adopts as a genuine reconnect and re-sends `start`; nothing ever sends `stop`
 * or calls `close` except the explicit close at the end (P-1).
 */
class OrchestratorChannelMockServerTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val poolCalls = CopyOnWriteArrayList<Long>()
    private val wsTexts = LinkedBlockingQueue<String>()
    private val allWsTexts = CopyOnWriteArrayList<String>()
    private val sockets = LinkedBlockingQueue<WebSocket>()
    private val closes = CopyOnWriteArrayList<String>()
    private val poolHits = AtomicInteger()

    @After fun tearDown() {
        scope.cancel(); server.shutdown()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { sockets.add(webSocket) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            allWsTexts.add(text); wsTexts.add(text)
            if (text.contains("\"type\":\"start\"")) {
                webSocket.send("""{"type":"session_started","session_id":"ORCH","jsonl_id":"JSONL"}""".encodeUtf8())
            }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
    }

    @Test fun coldStartAdoption_reconnectWithBackoff_noStopEver() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/orchestrator/chat" -> MockResponse().withWebSocketUpgrade(listener)
                request.path == "/api/sessions/pool/live" -> {
                    poolCalls += System.nanoTime()
                    val body = if (poolHits.getAndIncrement() == 0) "[]"   // cold-start empty pool
                    else """[{"local_id":"ORCH","sdk_session_id":"JSONL","status":"idle","cost":0.0,"turns":0,"title":"Orchestrator","is_orchestrator":true}]"""
                    MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
                request.path!!.endsWith("/close") -> { closes += request.path!!; MockResponse().setResponseCode(204) }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val serverUrl = "ws://${server.hostName}:${server.port}"
        val stack = HttpStack()
        val api = ArchieApi(stack) { serverUrl }
        val channel = OrchestratorChannel(
            SocketClient(stack, scope), ArchiePoolApi(api), FakeIds(), scope, OrchestratorChannel.Config(autoStart = true),
        )
        val events = channel.subscribeEvents()
        channel.connect(serverUrl)

        val first = wsTexts.poll(10, TimeUnit.SECONDS)!!
        assertTrue(first, first.contains("\"local_id\":\"ORCH\"") && first.contains("\"resume_sdk_id\":\"JSONL\""))
        val retryGapMs = (poolCalls[1] - poolCalls[0]) / 1_000_000
        assertTrue("pool retry after ${retryGapMs}ms", retryGapMs in 390..1_500)
        withTimeout(5_000) { while (!channel.state.value.subscribed) kotlinx.coroutines.delay(10) }

        sockets.poll(5, TimeUnit.SECONDS)!!.close(1001, "restart")
        val droppedAt = System.nanoTime()
        val second = wsTexts.poll(10, TimeUnit.SECONDS)!!
        val gapMs = (System.nanoTime() - droppedAt) / 1_000_000
        assertTrue("reconnect + start after ${gapMs}ms (1 s ± 20 % + probe)", gapMs in 750..2_800)
        assertTrue(second.contains("\"type\":\"start\""))

        val ref = OrchestratorRef("ORCH", "JSONL")
        val seen = events.drain()
        assertEquals(ChannelEvent.Adopted(ref, reconnect = false), seen.first())
        assertTrue("$seen", seen.contains(ChannelEvent.Disconnected(willReconnect = true)))
        assertTrue("$seen", seen.contains(ChannelEvent.Reconnected(ref)))

        // Lifecycle teardown: nothing sent, nothing closed (P-1).
        channel.onBackground(keepAlive = false)
        channel.disconnect()
        kotlinx.coroutines.delay(300)
        assertTrue("$allWsTexts", allWsTexts.none { it.contains("\"type\":\"stop\"") || it.contains("voice_stop") })
        assertTrue(closes.isEmpty())

        // Explicit close (the only path that ends it for everyone).
        assertTrue(channel.closeOrchestrator())
        assertEquals(listOf("/api/sessions/ORCH/close"), closes)
    }
}
