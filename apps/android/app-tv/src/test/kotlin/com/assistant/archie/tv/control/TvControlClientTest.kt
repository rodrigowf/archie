package com.assistant.archie.tv.control

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The control socket against a fake WebSocket: handshake, ping, commands, backoff, 4409 / 4401 (spec 12 §9.5). */
@OptIn(ExperimentalCoroutinesApi::class)
class TvControlClientTest {
    class FakeSocket(val url: String, val listener: TvSocketListener) : TvSocket {
        val sent = mutableListOf<String>()
        var closedWith: Int? = null
        var cancelled = false
        override fun send(text: String): Boolean = sent.add(text)
        override fun close(code: Int, reason: String?) { closedWith = code }
        override fun cancel() { cancelled = true }
        fun json(i: Int): JsonObject = Json.parseToJsonElement(sent[i]).jsonObject
    }

    class FakeSockets : TvSocketFactory {
        val opened = mutableListOf<FakeSocket>()
        override fun open(serverUrl: String, url: String, listener: TvSocketListener): TvSocket = FakeSocket(url, listener).also { opened += it }
        val last: FakeSocket get() = opened.last()
    }

    private val hello = TvHello(deviceId = "dev-1", deviceName = "Living room TV", version = "0.1.0", token = "s3cret")

    private fun TestScope.client(
        sockets: FakeSockets,
        handler: suspend (TvCommand) -> TvReply = { TvReply.Ok(TvControlProtocol.obj("screen" to "home")) },
    ) = TvControlClient(backgroundScope, sockets, { hello }, handler)

    private fun FakeSocket.ready() {
        listener.onOpen()
        listener.onMessage("""{"type":"ready"}""")
    }

    @Test fun handshakeSendsTheHelloThenStateAfterReady() = runTest {
        val sockets = FakeSockets()
        val c = client(sockets)
        c.sendState(TvControlProtocol.state(TvControlProtocol.obj("screen" to "home")))
        c.setTarget("https://192.168.0.200")
        assertEquals("wss://192.168.0.200/api/tv/ws", sockets.last.url)
        assertEquals(TvControlState.Connecting(0), c.state.value)

        sockets.last.listener.onOpen()
        val h = sockets.last.json(0)
        assertEquals("hello", h["type"]!!.jsonPrimitive.content)
        assertEquals("dev-1", h["device_id"]!!.jsonPrimitive.content)
        assertEquals("Living room TV", h["device_name"]!!.jsonPrimitive.content)
        assertEquals("archie-tv", h["client"]!!.jsonPrimitive.content)
        assertEquals("s3cret", h["token"]!!.jsonPrimitive.content)
        assertEquals(TvCommands.ALL, h["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, sockets.last.sent.size) // no state before ready

        sockets.last.listener.onMessage("""{"type":"ready"}""")
        assertEquals(TvControlState.Ready, c.state.value)
        assertEquals("state", sockets.last.json(1)["type"]!!.jsonPrimitive.content)
        assertEquals("home", sockets.last.json(1)["screen"]!!.jsonPrimitive.content)

        // Same frame again: not resent; a new one goes out at once.
        c.sendState(TvControlProtocol.state(TvControlProtocol.obj("screen" to "home")))
        assertEquals(2, sockets.last.sent.size)
        c.sendState(TvControlProtocol.state(TvControlProtocol.obj("screen" to "viewer")))
        assertEquals("viewer", sockets.last.json(2)["screen"]!!.jsonPrimitive.content)
    }

    @Test fun ws_scheme_follows_the_server() = runTest {
        val sockets = FakeSockets()
        client(sockets).setTarget("ws://192.168.0.28:8777")
        assertEquals("ws://192.168.0.28:8777/api/tv/ws", sockets.last.url)
    }

    @Test fun answersPingAndEveryCommandWithItsId() = runTest {
        val sockets = FakeSockets()
        val seen = mutableListOf<TvCommand>()
        val c = client(sockets) { cmd ->
            seen += cmd
            when (cmd.name) {
                "boom" -> error("kaput")
                "bad" -> TvReply.Error("nope")
                else -> TvReply.Ok(TvControlProtocol.obj("screen" to "viewer", "url" to cmd.string("path")))
            }
        }
        c.setTarget("https://h")
        sockets.last.ready()
        sockets.last.listener.onMessage("""{"type":"ping"}""")
        assertEquals("""{"type":"pong"}""", sockets.last.sent.last())

        sockets.last.listener.onMessage("""{"id":"abc123","type":"command","command":"open_visualization","params":{"path":"visualizations/x.html"}}""")
        sockets.last.listener.onMessage("""{"id":"def456","type":"command","command":"bad","params":{}}""")
        sockets.last.listener.onMessage("""{"id":"ghi789","type":"command","command":"boom"}""")
        sockets.last.listener.onMessage("not json at all")
        runCurrent()
        assertEquals(listOf("open_visualization", "bad", "boom"), seen.map { it.name })
        val results = sockets.last.sent.map { Json.parseToJsonElement(it).jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "result" }
        assertEquals(listOf("abc123", "def456", "ghi789"), results.map { it["id"]!!.jsonPrimitive.content })
        assertTrue(results[0]["ok"]!!.jsonPrimitive.boolean)
        assertEquals("visualizations/x.html", results[0]["result"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertFalse(results[1]["ok"]!!.jsonPrimitive.boolean)
        assertEquals("nope", results[1]["error"]!!.jsonPrimitive.content)
        assertEquals("kaput", results[2]["error"]!!.jsonPrimitive.content)
    }

    @Test fun reconnectsWithBackoffCappedAt15sAndResetsOnReady() = runTest {
        val sockets = FakeSockets()
        val c = client(sockets)
        c.setTarget("https://h")
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L)
        for ((n, wait) in expected.withIndex()) {
            sockets.last.listener.onClosed(1006, "gone")
            assertEquals(TvControlState.Waiting(n, wait, "gone"), c.state.value)
            advanceTimeBy(wait - 1)
            runCurrent()
            assertEquals(n + 1, sockets.opened.size)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(n + 2, sockets.opened.size)
        }
        sockets.last.ready()
        sockets.last.listener.onClosed(1006, "gone")
        assertEquals(TvControlState.Waiting(0, 1_000L, "gone"), c.state.value)
    }

    @Test fun closingAndClosedReconnectOnce() = runTest {
        val sockets = FakeSockets()
        val c = client(sockets)
        c.setTarget("https://h")
        sockets.last.ready()
        sockets.last.listener.onClosed(1001, "bye") // OkHttp onClosing
        sockets.last.listener.onClosed(1001, "bye") // OkHttp onClosed
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(2, sockets.opened.size)
        assertTrue(c.state.value is TvControlState.Connecting)
    }

    @Test fun replaced4409NeverReconnectsFromThatSocket() = runTest {
        val sockets = FakeSockets()
        val c = client(sockets)
        c.setTarget("https://h")
        sockets.last.ready()
        sockets.last.listener.onClosed(TvControlProtocol.CLOSE_REPLACED, "")
        assertEquals(TvControlState.Replaced, c.state.value)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, sockets.opened.size)
        // The server becoming reachable again (orchestrator reconnect) is a new start.
        c.reconnectNow()
        assertEquals(2, sockets.opened.size)
    }

    @Test fun rejected4401StopsAndSaysWhy() = runTest {
        val sockets = FakeSockets()
        val c = client(sockets)
        c.setTarget("https://h")
        sockets.last.listener.onOpen()
        sockets.last.listener.onMessage("""{"type":"error","error":"unauthorized"}""")
        sockets.last.listener.onClosed(TvControlProtocol.CLOSE_UNAUTHORIZED, "")
        assertEquals(TvControlState.Rejected("unauthorized"), c.state.value)
        advanceTimeBy(120_000)
        runCurrent()
        c.reconnectNow()
        assertEquals(1, sockets.opened.size)
        c.retry()
        assertEquals(2, sockets.opened.size)
    }

    @Test fun noReadyWithinTheTimeoutRetries() = runTest {
        val sockets = FakeSockets()
        val c = client(sockets)
        c.setTarget("https://h")
        sockets.last.listener.onOpen()
        advanceTimeBy(TvControlClient.READY_TIMEOUT_MS + 1)
        runCurrent()
        assertTrue(sockets.opened[0].cancelled)
        assertEquals(TvControlState.Waiting(0, 1_000L, "ready timeout"), c.state.value)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, sockets.opened.size)
    }

    @Test fun aStaleSocketIsIgnoredAndResultsNeverCrossSockets() = runTest {
        val sockets = FakeSockets()
        val c = client(sockets)
        c.setTarget("https://a")
        val old = sockets.last
        old.ready()
        c.setTarget("https://b") // server switch: the old socket closes, a new one opens
        assertEquals(1000, old.closedWith)
        val fresh = sockets.last
        fresh.ready()
        val before = old.sent.size
        old.listener.onMessage("""{"id":"x1","type":"command","command":"home"}""")
        old.listener.onClosed(1006, "late")
        runCurrent()
        assertEquals(before, old.sent.size)
        assertTrue(fresh.sent.none { "x1" in it })
        assertEquals(TvControlState.Ready, c.state.value)
    }

    @Test fun noTargetMeansNoSocket() = runTest {
        val sockets = FakeSockets()
        val c = client(sockets)
        c.reconnectNow()
        assertTrue(sockets.opened.isEmpty())
        c.setTarget("https://h")
        c.stop()
        assertEquals(TvControlState.Idle, c.state.value)
        assertEquals(1000, sockets.last.closedWith)
    }

    @Test fun protocolParsesTheBackendFrames() {
        assertEquals(TvInbound.Ready, TvControlProtocol.parse("""{"type":"ready"}"""))
        assertEquals(TvInbound.Error("hello_timeout"), TvControlProtocol.parse("""{"type":"error","error":"hello_timeout"}"""))
        val cmd = TvControlProtocol.parse("""{"id":"0123456789ab","type":"command","command":"scroll","params":{"direction":"down"}}""") as TvInbound.Command
        assertEquals("scroll", cmd.command.name)
        assertEquals("down", cmd.command.string("direction"))
        assertNull(cmd.command.string("missing"))
        assertEquals(TvInbound.Other, TvControlProtocol.parse("""{"type":"command","command":"x"}""")) // no id
        val ok = Json.parseToJsonElement(TvControlProtocol.result("id1", TvReply.Ok())).jsonObject
        assertEquals(true, ok["ok"]!!.jsonPrimitive.boolean)
        assertEquals(JsonObject(emptyMap()), ok["result"])
        assertEquals(15_000L, TvControlClient.defaultBackoff(30))
    }
}
