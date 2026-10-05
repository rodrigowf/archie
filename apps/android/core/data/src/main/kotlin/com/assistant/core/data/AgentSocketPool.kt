package com.assistant.core.data

import com.assistant.core.network.FrameSocket

/**
 * One chat WebSocket per open agent conversation (T-5; spec 14 §1.2). Sockets are process-scoped
 * and never closed by a lifecycle path with an application frame (P-1): [release] and [park] only
 * drop the WebSocket.
 *
 * LRU: when more than [maxConnected] sockets are connected, the least recently used **idle**
 * sockets are parked (disconnected). A parked conversation reconnects when it is used again
 * ([touch] returns true for it). Busy conversations and the one being touched are never parked.
 */
class AgentSocketPool(
    private val factory: () -> FrameSocket,
    private val maxConnected: Int = MAX_CONNECTED,
) {
    private val lock = Any()
    private val sockets = LinkedHashMap<ConversationKey, Entry>(16, 0.75f, true)   // access order = LRU

    private class Entry(val socket: FrameSocket, var parked: Boolean = false)

    fun acquire(key: ConversationKey): FrameSocket = synchronized(lock) {
        sockets.getOrPut(key) { Entry(factory()) }.socket
    }

    /** The view closed (explicit close, rewind replace, server change): drop the socket. */
    fun release(key: ConversationKey) {
        val e = synchronized(lock) { sockets.remove(key) } ?: return
        e.socket.disconnect()
    }

    fun releaseAll() {
        val all = synchronized(lock) { sockets.values.toList().also { sockets.clear() } }
        all.forEach { it.socket.disconnect() }
    }

    val keys: Set<ConversationKey> get() = synchronized(lock) { sockets.keys.toSet() }

    fun isParked(key: ConversationKey): Boolean = synchronized(lock) { sockets[key]?.parked == true }

    /**
     * Marks [key] most recently used. Returns the keys to park (caller disconnects them through
     * their handles so the reducer sees `SocketClosed`), and whether [key] itself was parked and
     * must reconnect.
     */
    fun touch(key: ConversationKey, isBusy: (ConversationKey) -> Boolean): TouchResult = synchronized(lock) {
        val self = sockets[key] ?: return TouchResult(false, emptyList())
        val wasParked = self.parked
        self.parked = false
        val connected = sockets.filterValues { !it.parked }.keys.toList()   // LRU first
        var excess = connected.size - maxConnected
        val toPark = mutableListOf<ConversationKey>()
        for (k in connected) {
            if (excess <= 0) break
            if (k == key || isBusy(k)) continue
            sockets[k]?.parked = true
            toPark += k
            excess--
        }
        TouchResult(wasParked, toPark)
    }

    data class TouchResult(val reconnectSelf: Boolean, val park: List<ConversationKey>)

    companion object {
        /** "idle sessions beyond 6 disconnect LRU" (spec 14 §1.2). */
        const val MAX_CONNECTED = 6
    }
}
