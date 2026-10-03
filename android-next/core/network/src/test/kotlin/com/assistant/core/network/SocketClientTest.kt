package com.assistant.core.network

import com.assistant.core.protocol.ClientFrame
import com.assistant.core.protocol.ServerFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * spec 14 A-03 DoD: ping 30 s, reconnect backoff (spec 12 T-13, superseding the old fixed 3000 ms,
 * A-3.3), willReconnect, no dropped frames at 10k frames/s, P-1.
 */
class SocketClientTest {
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stack = HttpStack()

    /** One scripted server-side socket per upgrade. */
    private class ServerSide : WebSocketListener() {
        val opened = LinkedBlockingQueue<WebSocket>()
        val texts = LinkedBlockingQueue<String>()
        val binaryFromClient = CopyOnWriteArrayList<ByteString>()
        val upgradeTimes = CopyOnWriteArrayList<Long>()
        override fun onOpen(webSocket: WebSocket, response: Response) {
            upgradeTimes.add(System.nanoTime()); opened.add(webSocket)
        }
        override fun onMessage(webSocket: WebSocket, text: String) { texts.add(text) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { binaryFromClient.add(bytes) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
    }

    private val side = ServerSide()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun enqueueUpgrades(n: Int) = repeat(n) { server.enqueue(MockResponse().withWebSocketUpgrade(side)) }
    private fun url() = server.url("/api/orchestrator/chat").toString().replace("http", "ws")
    private fun client(policy: ReconnectPolicy = ReconnectPolicy.DEFAULT) = SocketClient(stack, scope, policy)

    private suspend fun SocketClient.collectFrames(into: Channel<ServerFrame>, events: Channel<SocketEvent>? = null) =
        scope.launch {
            this@collectFrames.events.collect { e ->
                events?.send(e)
                if (e is SocketEvent.Frame) into.send(e.frame)
            }
        }

    @Test fun wsClientPingsEvery30sWithNoReadTimeout() {
        val ws: OkHttpClient = stack.wsClient("ws://192.168.0.200:80")
        assertEquals(NetworkTuning.WS_PING_INTERVAL_MS.toInt(), ws.pingIntervalMillis)
        assertEquals(30_000, ws.pingIntervalMillis)
        assertEquals(0, ws.readTimeoutMillis)
        assertEquals(0, stack.restClient("http://x").pingIntervalMillis)    // REST: no pings
    }

    @Test fun textFramesOut_binaryAndTextIn_pingAndMalformedDropped() = runBlocking {
        enqueueUpgrades(1)
        val c = client()
        val frames = Channel<ServerFrame>(Channel.UNLIMITED)
        c.collectFrames(frames)
        c.connect(url())
        val s = side.opened.poll(5, TimeUnit.SECONDS)!!
        withTimeout(5_000) { c.state.first { it == SocketState.Open } }

        assertEquals(SendResult.SENT, c.send(ClientFrame.Start("L1")))
        val sent = side.texts.poll(5, TimeUnit.SECONDS)!!
        assertTrue(sent, sent.contains("\"type\":\"start\"") && sent.contains("\"local_id\":\"L1\""))
        assertTrue("client must never send binary (T-2)", side.binaryFromClient.isEmpty())

        s.send("""{"type":"ping"}""".encodeUtf8())                        // T-4: dropped
        s.send("not json")                                                 // T-3: dropped
        s.send("""{"no_type":1}""")                                        // T-3: dropped
        s.send("""{"type":"text_delta","text":"a"}""".encodeUtf8())        // binary (T-1)
        s.send("""{"type":"text_delta","text":"b"}""")                     // text accepted too
        val a = withTimeout(5_000) { frames.receive() } as ServerFrame.TextDelta
        val b = withTimeout(5_000) { frames.receive() } as ServerFrame.TextDelta
        assertEquals("a", a.text); assertEquals("b", b.text)
        delay(200)
        assertTrue(frames.tryReceive().isFailure)
    }

    @Test fun dropsReconnectWithExponentialBackoff_resetBySessionStartedOnly() = runBlocking {
        enqueueUpgrades(4)
        val c = client()                                                    // the default policy (T-13)
        val events = Channel<SocketEvent>(Channel.UNLIMITED)
        c.collectFrames(Channel(Channel.UNLIMITED), events)
        c.connect(url())
        assertEquals(SocketEvent.Opened, withTimeout(5_000) { events.receive() })

        // Each drop without session_started backs off further: ~1 s, then ~2 s (± 20 %).
        for ((i, window) in listOf(750L..1_600L, 1_550L..2_900L).withIndex()) {
            side.opened.poll(5, TimeUnit.SECONDS)!!.close(1001, "server going away")
            val closed = withTimeout(5_000) { events.receive() } as SocketEvent.Closed
            val droppedAt = System.nanoTime()
            assertTrue("transient drop must say willReconnect=true", closed.willReconnect)
            assertEquals(SocketState.Disconnected(true, closed.reason), c.state.value)
            assertEquals(SocketEvent.Opened, withTimeout(8_000) { events.receive() })
            val waitedMs = (side.upgradeTimes[i + 1] - droppedAt) / 1_000_000
            assertTrue("attempt $i reconnected after ${waitedMs}ms, expected $window", waitedMs in window)
        }
        assertEquals("opening a socket does not reset the backoff", 2, c.backoffAttempt)

        c.resetBackoff()                                                    // what session_started does
        side.opened.poll(5, TimeUnit.SECONDS)!!.close(1001, "again")
        withTimeout(5_000) { events.receive() }
        val droppedAt = System.nanoTime()
        assertEquals(SocketEvent.Opened, withTimeout(5_000) { events.receive() })
        val waitedMs = (side.upgradeTimes[3] - droppedAt) / 1_000_000
        assertTrue("after reset: ${waitedMs}ms, expected ~1 s", waitedMs in 750L..1_600L)
    }

    @Test fun disconnectIsFinal_willReconnectFalse_andSendsNoApplicationFrame() = runBlocking {
        enqueueUpgrades(2)
        val c = client(ReconnectPolicy { 200L })
        val events = Channel<SocketEvent>(Channel.UNLIMITED)
        c.collectFrames(Channel(Channel.UNLIMITED), events)
        c.connect(url())
        side.opened.poll(5, TimeUnit.SECONDS)!!
        withTimeout(5_000) { events.receive() }

        c.disconnect()                                                     // lifecycle path (P-1)
        val closed = withTimeout(5_000) { events.receive() } as SocketEvent.Closed
        assertFalse(closed.willReconnect)
        assertEquals(SocketState.Disconnected(false, "client"), c.state.value)
        delay(800)                                                         // 4× the reconnect delay
        assertEquals(1, side.upgradeTimes.size)
        assertTrue("no stop/close/any app frame on teardown: ${side.texts}", side.texts.isEmpty())
        assertEquals(SendResult.NOT_CONNECTED, c.send(ClientFrame.Send("x")))
    }

    @Test fun noDroppedFramesUnder10kFramesPerSecond() = runBlocking {
        val total = 20_000
        enqueueUpgrades(1)
        val c = client()
        val frames = Channel<ServerFrame>(Channel.UNLIMITED)
        c.collectFrames(frames)
        c.connect(url())
        val s = side.opened.poll(5, TimeUnit.SECONDS)!!
        val t0 = System.nanoTime()
        for (i in 0 until total) s.send("""{"type":"text_delta","text":"$i","seq":$i,"stream_id":"s"}""".encodeUtf8())
        val sendRate = total / ((System.nanoTime() - t0) / 1e9)
        assertTrue("server burst rate $sendRate frames/s must be >= 10k", sendRate >= 10_000)

        // A deliberately slow consumer: the old 64-slot tryEmit bus dropped here (inv03 §3.3).
        withTimeout(60_000) {
            for (i in 0 until total) {
                val f = frames.receive() as ServerFrame.TextDelta
                assertEquals(i.toLong(), f.seq)
                if (i % 2_000 == 0) delay(50)
            }
        }
    }

    @Test fun reconnectIsHeldWhileNotAllowed_thenImmediateWhenAllowed() = runBlocking {
        enqueueUpgrades(2)
        val c = client(ReconnectPolicy { 100L })
        val events = Channel<SocketEvent>(Channel.UNLIMITED)
        c.collectFrames(Channel(Channel.UNLIMITED), events)
        c.connect(url())
        val s1 = side.opened.poll(5, TimeUnit.SECONDS)!!
        withTimeout(5_000) { events.receive() }
        c.setReconnectAllowed(false)                                       // backgrounded (T-14)
        s1.close(1001, "bye")
        assertTrue((withTimeout(5_000) { events.receive() } as SocketEvent.Closed).willReconnect)
        delay(300)
        c.reconnectNow()                                                   // network callback while backgrounded
        delay(200)
        assertEquals("held while gated (T-14)", 1, side.upgradeTimes.size)
        c.setReconnectAllowed(true)                                        // foreground
        side.opened.poll(5, TimeUnit.SECONDS)!!
        assertEquals(SocketEvent.Opened, withTimeout(5_000) { events.receive() })
    }

    private fun fixedRandom(v: Double) = object : kotlin.random.Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextDouble() = v
    }

    @Test fun backoffScheduleIsT13_1s_doubling_cappedAt15s() {
        val center = ReconnectPolicy.exponential(fixedRandom(0.5))           // jitter factor exactly 1.0
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L), (0..6).map(center::delayMillis))
        assertEquals(15_000L, center.delayMillis(1_000))                      // no overflow
        assertEquals(1_000L, NetworkTuning.WS_RECONNECT_BASE_DELAY_MS)
        assertEquals(15_000L, NetworkTuning.WS_RECONNECT_MAX_DELAY_MS)
        assertEquals(0.2, NetworkTuning.WS_RECONNECT_JITTER, 0.0)
        assertEquals(30_000L, NetworkTuning.WS_PING_INTERVAL_MS)              // the LB part is unchanged
    }

    @Test fun jitterStaysWithin20PercentAndCoversTheRange() {
        val nominal = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
        val low = ReconnectPolicy.exponential(fixedRandom(0.0))
        val high = ReconnectPolicy.exponential(fixedRandom(0.999_999_999))
        nominal.forEachIndexed { a, n ->
            assertEquals((n * 0.8).toLong(), low.delayMillis(a))
            assertTrue(high.delayMillis(a) in (n * 1.2).toLong() - 1..(n * 1.2).toLong())
        }
        val p = ReconnectPolicy.exponential(kotlin.random.Random(42))
        for (a in 0..8) {
            val n = nominal[minOf(a, 4)]
            val samples = (1..2_000).map { p.delayMillis(a) }
            assertTrue("attempt $a: ${samples.min()}..${samples.max()}", samples.all { it in (n * 0.8).toLong()..(n * 1.2).toLong() })
            assertTrue("jitter spreads both ways", samples.min() < n * 0.85 && samples.max() > n * 1.15)
        }
    }
}
