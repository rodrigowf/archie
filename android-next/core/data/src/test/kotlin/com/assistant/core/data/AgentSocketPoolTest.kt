package com.assistant.core.data

import com.assistant.core.network.FrameSocket
import com.assistant.core.network.SendResult
import com.assistant.core.network.SocketEvent
import com.assistant.core.network.SocketState
import com.assistant.core.protocol.ClientFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LRU parking of idle agent sockets beyond 6 (spec 14 §1.2). */
class AgentSocketPoolTest {
    private class Sock : FrameSocket {
        var disconnects = 0
        override val state: StateFlow<SocketState> = MutableStateFlow(SocketState.Idle)
        override val events: Flow<SocketEvent> = emptyFlow()
        override fun connect(url: String) {}
        override fun disconnect() { disconnects++ }
        override fun send(frame: ClientFrame) = SendResult.SENT
        override fun reconnectNow() {}
        override fun resetBackoff() {}
        override fun setReconnectAllowed(allowed: Boolean) {}
    }

    @Test fun parksLeastRecentlyUsedIdle_neverBusyOrTouched() {
        val pool = AgentSocketPool({ Sock() }, maxConnected = 3)
        val keys = (1..4).map { ConversationKey("k$it") }
        keys.forEach { pool.acquire(it) }
        val busy = setOf(keys[0])
        val r = pool.touch(keys[3]) { it in busy }
        assertEquals("k1 is busy, so k2 (next LRU) parks", listOf(keys[1]), r.park)
        assertTrue(pool.isParked(keys[1]))
        assertFalse(r.reconnectSelf)

        val back = pool.touch(keys[1]) { it in busy }
        assertTrue("a parked conversation reconnects when used", back.reconnectSelf)
        assertFalse(pool.isParked(keys[1]))
        assertEquals(listOf(keys[2]), back.park)
    }

    @Test fun releaseDisconnects() {
        val socks = mutableListOf<Sock>()
        val pool = AgentSocketPool({ Sock().also { socks += it } })
        val k = ConversationKey("a")
        pool.acquire(k)
        pool.release(k)
        assertEquals(1, socks.single().disconnects)
        assertTrue(pool.keys.isEmpty())
    }
}
