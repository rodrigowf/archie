package com.assistant.core.voice.delivery

import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.voice.VoiceLogMarkers
import com.assistant.core.voice.json.typeOf
import com.assistant.core.voice.ports.CommandRelay
import com.assistant.core.voice.ports.DataChannelCommandGate
import com.assistant.core.voice.ports.ProviderCommandSink
import kotlinx.serialization.json.JsonObject

private const val TAG = "VoiceDelivery"
private const val SESSION_UPDATE = "session.update"

/**
 * Pre-provider command queue + `session.update` cache (old `VoiceManager.pendingBackendCommands`
 * / `lastSessionUpdate`, inv04 §3.2 delivery sub-machine steps 1–3; RS-01, RS-04).
 *
 * One lock covers "is a sink attached?" + enqueue on the producer side and drain + attach on the
 * consumer side, so a command that races [attach] is either drained or forwarded, never both and
 * never neither (the old `Channel` + `currentProvider` check had that window). After [attach],
 * commands are forwarded outside the lock. The queue is unbounded, like the old `Channel.UNLIMITED`
 * (RS-04 stress: 100k commands never block or drop).
 */
class LockedCommandRelay(private val log: VoiceLog) : CommandRelay {
    private val lock = Any()
    private val queue = ArrayDeque<JsonObject>()
    private var sink: ProviderCommandSink? = null

    @Volatile
    private var lastSessionUpdate: JsonObject? = null

    override val cachedSessionUpdate: JsonObject? get() = lastSessionUpdate
    override val queuedCount: Int get() = synchronized(lock) { queue.size }

    override fun onBackendCommand(command: JsonObject) {
        // Cached whichever way it is routed: a forward to a not-yet-connected provider can be lost
        // in the restart race, and the cache is what the DC-open / echo self-heal re-asserts (RS-03).
        if (typeOf(command) == SESSION_UPDATE) lastSessionUpdate = command
        val target = synchronized(lock) {
            val s = sink
            if (s == null) queue.addLast(command)
            s
        }
        if (target == null) {
            log.d(TAG, "handleBackendCommand: no provider yet, queueing type=${typeOf(command) ?: "?"}")
        } else {
            target.handleBackendCommand(command)
        }
    }

    override fun attach(sink: ProviderCommandSink): Int = synchronized(lock) {
        val n = queue.size
        if (n > 0) log.i(TAG, "${VoiceLogMarkers.DRAIN_PRE_PROVIDER} $n pre-provider backend command(s)")
        while (queue.isNotEmpty()) sink.handleBackendCommand(queue.removeFirst())
        this.sink = sink
        n
    }

    override fun detach(): Unit = synchronized(lock) {
        queue.clear()
        sink = null
    }
}

/**
 * The WebRTC data-channel command gate (old `OpenAIVoiceProvider.pendingCommands` +
 * `sessionUpdateSent`, inv04 §3.2 delivery steps 3–6; RS-02, RS-03). Fixes B2: the old list was
 * written from the socket thread and drained on the data-channel thread without a lock. Here every
 * entry point holds one lock and [transmit] runs under it, so FIFO order holds across the open.
 */
class LockedDataChannelGate(
    private val transmit: (JsonObject) -> Unit,
    private val fallback: () -> JsonObject?,
    private val log: VoiceLog,
) : DataChannelCommandGate {
    private val lock = Any()
    private val pending = ArrayDeque<JsonObject>()
    private var open = false
    private var updateSent = false

    override val isOpen: Boolean get() = synchronized(lock) { open }
    override val sessionUpdateSent: Boolean get() = synchronized(lock) { updateSent }
    override val pendingCount: Int get() = synchronized(lock) { pending.size }

    override fun send(command: JsonObject): Unit = synchronized(lock) {
        if (open) transmitLocked(command) else pending.addLast(command)
    }

    override fun onOpen(): Unit = synchronized(lock) {
        open = true
        if (pending.isNotEmpty()) log.d(TAG, "Draining ${pending.size} pending commands")
        while (pending.isNotEmpty()) transmitLocked(pending.removeFirst())
        if (!updateSent) {
            val cached = fallback()
            if (cached != null) {
                log.w(TAG, "${VoiceLogMarkers.SESSION_UPDATE_MISSING} — re-asserting cached payload (restart race self-heal)")
                transmitLocked(cached)
                updateSent = true
            } else {
                log.e(TAG, "${VoiceLogMarkers.SESSION_UPDATE_MISSING} and no cached fallback available — session will run on OpenAI defaults")
            }
        }
    }

    override fun onSessionUpdatedEcho(): Unit = synchronized(lock) {
        if (updateSent) return
        val cached = fallback() ?: return
        log.w(TAG, "session.updated seen but no session.update was ever sent — re-asserting cached payload")
        transmitLocked(cached)
        updateSent = true
    }

    override fun reset(): Unit = synchronized(lock) {
        open = false
        pending.clear()
        updateSent = false
    }

    private fun transmitLocked(command: JsonObject) {
        try {
            transmit(command)
        } catch (e: Exception) {
            log.w(TAG, "data channel send failed: ${e.message}")
            return
        }
        if (typeOf(command) == SESSION_UPDATE) updateSent = true
    }
}
