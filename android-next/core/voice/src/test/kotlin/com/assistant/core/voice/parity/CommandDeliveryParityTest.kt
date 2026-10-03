package com.assistant.core.voice.parity

import com.assistant.core.testing.RecordingLog
import com.assistant.core.testing.obj
import com.assistant.core.voice.ports.ProviderCommandSink
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * `session.update` delivery (inv04 §3.2 sub-machine; RS-01…RS-04; fixes B2). Ports the intent of the
 * old `PendingBackendCommandsParityTest`.
 */
@Ignore("A-06")
class CommandDeliveryParityTest {
    private val log = RecordingLog()
    private val update = obj("""{"type":"session.update","session":{"voice":"cedar","instructions":"You are Archie"}}""")
    private fun cmd(i: Int) = obj("""{"type":"response.create","n":$i}""")

    // ── CommandRelay (pre-provider queue + cache) ─────────────────────────────────────────────

    /** RS-01 (`8424f0f`): an update that arrives before the provider exists is queued, then delivered first. */
    @Test
    fun rs01_sessionUpdateQueuedBeforeProviderIsDrainedOnAttach() {
        val relay = voiceCore.commandRelay(log)
        relay.onBackendCommand(update)
        relay.onBackendCommand(cmd(1))
        assertEquals(2, relay.queuedCount)
        val got = mutableListOf<JsonObject>()
        assertEquals(2, relay.attach { got += it })
        assertEquals(listOf(update, cmd(1)), got)
        assertEquals(0, relay.queuedCount)
        assertTrue(log.dump(), log.contains("start: draining 2 pre-provider backend command(s)"))
        relay.onBackendCommand(cmd(2))
        assertEquals("after attach commands go straight through", listOf(update, cmd(1), cmd(2)), got)
    }

    @Test
    fun sessionUpdateIsCachedWhetherQueuedOrForwarded() {
        val relay = voiceCore.commandRelay(log)
        assertNull(relay.cachedSessionUpdate)
        relay.onBackendCommand(cmd(1))
        assertNull("only session.update is cached", relay.cachedSessionUpdate)
        relay.onBackendCommand(update)
        assertEquals(update, relay.cachedSessionUpdate)
        relay.attach { }
        val newer = obj("""{"type":"session.update","session":{"voice":"marin"}}""")
        relay.onBackendCommand(newer)
        assertEquals(newer, relay.cachedSessionUpdate)
    }

    @Test
    fun detachDropsQueuedCommandsButStaysUsable() {
        val relay = voiceCore.commandRelay(log)
        relay.onBackendCommand(cmd(1))
        relay.detach()
        assertEquals(0, relay.queuedCount)
        relay.onBackendCommand(cmd(2))
        val got = mutableListOf<JsonObject>()
        relay.attach { got += it }
        assertEquals(listOf(cmd(2)), got)
    }

    @Test
    fun fifoOrderForASingleProducer() {
        val relay = voiceCore.commandRelay(log)
        repeat(100) { relay.onBackendCommand(cmd(it)) }
        val got = mutableListOf<Int>()
        relay.attach { got += it["n"]!!.jsonPrimitive.int }
        assertEquals((0 until 100).toList(), got)
    }

    @Test
    fun queueingNeverBlocks() {
        val relay = voiceCore.commandRelay(log)
        val t = System.nanoTime()
        repeat(100_000) { relay.onBackendCommand(cmd(it)) }
        assertEquals(100_000, relay.queuedCount)
        assertTrue("took ${(System.nanoTime() - t) / 1_000_000} ms", System.nanoTime() - t < TimeUnit.SECONDS.toNanos(10))
    }

    /** RS-04 (`d8fbedf`): producers on socket threads while attach drains: no loss, no duplicates (100k). */
    @Test
    fun rs04_concurrentBackendCommandsDuringAttachAreNeitherLostNorDuplicated() {
        val relay = voiceCore.commandRelay(log)
        val received = ConcurrentLinkedQueue<Int>()
        val sink = ProviderCommandSink { received += it["n"]!!.jsonPrimitive.int }
        val producers = 4
        val perProducer = 25_000
        val start = CountDownLatch(1)
        val threads = (0 until producers).map { p ->
            thread(name = "ws-$p") {
                start.await()
                for (i in 0 until perProducer) relay.onBackendCommand(cmd(p * perProducer + i))
            }
        }
        start.countDown()
        Thread.sleep(2)
        relay.attach(sink)
        threads.forEach { it.join(30_000) }
        assertEquals(producers * perProducer, received.size)
        assertEquals("duplicates delivered", producers * perProducer, received.toSet().size)
        assertEquals(0, relay.queuedCount)
    }

    // ── DataChannelCommandGate (provider-side pending + self-heal) ────────────────────────────

    private class Gate(fallbackValue: () -> JsonObject?) {
        val sent = ConcurrentLinkedQueue<JsonObject>()
        val log = RecordingLog()
        val gate = voiceCore.dataChannelGate({ sent += it }, fallbackValue, log)
    }

    /** RS-01/RS-02 (`9515576`): pending commands survive until the channel opens, then go out FIFO. */
    @Test
    fun rs02_pendingCommandsAreSentAtOpenInOrder() {
        val g = Gate { null }
        g.gate.send(update)
        g.gate.send(cmd(1))
        assertTrue(g.sent.isEmpty())
        assertEquals(2, g.gate.pendingCount)
        g.gate.onOpen()
        assertEquals(listOf(update, cmd(1)), g.sent.toList())
        assertTrue(g.gate.sessionUpdateSent)
        assertEquals(0, g.gate.pendingCount)
        g.gate.send(cmd(2))
        assertEquals("open → immediate", cmd(2), g.sent.last())
    }

    /** RS-03 (`d4bc698`): a restart with an empty drain re-asserts the cached update at open. */
    @Test
    fun rs03_cachedSessionUpdateReassertedAtOpenWhenNothingWasPending() {
        val g = Gate { update }
        g.gate.onOpen()
        assertEquals(listOf(update), g.sent.toList())
        assertTrue(g.gate.sessionUpdateSent)
        assertTrue(g.log.dump(), g.log.contains("session.update missing at DC_OPEN"))
    }

    @Test
    fun noCachedUpdateAtOpenIsLoggedAsAnError() {
        val g = Gate { null }
        g.gate.onOpen()
        assertTrue(g.sent.isEmpty())
        assertFalse(g.gate.sessionUpdateSent)
        assertTrue(g.log.lines.any { it.level == 'E' })
    }

    /** RS-03: the `session.updated` echo self-heals once when nothing was ever sent. */
    @Test
    fun rs03_sessionUpdatedEchoSelfHealsOnce() {
        var cache: JsonObject? = null
        val g = Gate { cache }
        g.gate.onOpen()
        cache = update
        g.gate.onSessionUpdatedEcho()
        g.gate.onSessionUpdatedEcho()
        assertEquals(listOf(update), g.sent.toList())
    }

    @Test
    fun anUpdateSentAtOpenSuppressesTheSelfHeal() {
        val g = Gate { obj("""{"type":"session.update","session":{"voice":"stale"}}""") }
        g.gate.send(update)
        g.gate.onOpen()
        g.gate.onSessionUpdatedEcho()
        assertEquals(listOf(update), g.sent.toList())
    }

    @Test
    fun resetClearsPendingAndTheSentFlag() {
        val g = Gate { null }
        g.gate.send(update)
        g.gate.onOpen()
        g.gate.send(cmd(9))
        g.gate.reset()
        assertFalse(g.gate.isOpen)
        assertFalse(g.gate.sessionUpdateSent)
        g.gate.send(cmd(1))
        assertEquals(1, g.gate.pendingCount)
    }

    /** B2: sends from the socket thread racing the open on the data-channel thread lose nothing. */
    @Test
    fun b2_concurrentSendAndOpenLoseNothing() {
        val g = Gate { null }
        val n = 20_000
        val start = CountDownLatch(1)
        val producer = thread(name = "ws") { start.await(); for (i in 0 until n) g.gate.send(cmd(i)) }
        val opener = thread(name = "dc") { start.await(); Thread.sleep(1); g.gate.onOpen() }
        start.countDown()
        producer.join(30_000)
        opener.join(30_000)
        assertEquals(n, g.sent.size)
        assertEquals(n, g.sent.map { it["n"]!!.jsonPrimitive.int }.toSet().size)
        assertEquals(0, g.gate.pendingCount)
    }
}
