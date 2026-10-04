package com.assistant.core.network

import com.assistant.core.protocol.ClientFrame
import com.assistant.core.protocol.ProtocolCodec
import com.assistant.core.protocol.ServerFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlin.random.Random

/** Observable socket state (for UI and gating). Transitions are also delivered, losslessly, as [SocketEvent]s. */
sealed interface SocketState {
    data object Idle : SocketState
    data class Connecting(val attempt: Int) : SocketState
    data object Open : SocketState

    /** [willReconnect] = a reconnect is scheduled or held for the foreground (inv03 §3.3). */
    data class Disconnected(val willReconnect: Boolean, val reason: String?) : SocketState
}

/** Ordered, lossless stream of what happened on the socket: one queue, one consumer (spec 12 L-2). */
sealed interface SocketEvent {
    data object Opened : SocketEvent
    data class Frame(val frame: ServerFrame) : SocketEvent
    data class Closed(val willReconnect: Boolean, val code: Int?, val reason: String?) : SocketEvent
}

enum class SendResult { SENT, NOT_CONNECTED, FAILED }

/**
 * Delay before reconnect attempt `attempt` (0-based). The counter is reset by
 * [FrameSocket.resetBackoff] (call it on `session_started`, T-13) and by [FrameSocket.reconnectNow].
 */
fun interface ReconnectPolicy {
    fun delayMillis(attempt: Int): Long

    companion object {
        /** spec 12 T-13 — the default for every socket. */
        val DEFAULT: ReconnectPolicy = exponential()

        /** min([max], [base] × 2^attempt) ± [jitter] (uniform). Never overflows for large attempts. */
        fun exponential(
            random: Random = Random.Default,
            base: Long = NetworkTuning.WS_RECONNECT_BASE_DELAY_MS,
            max: Long = NetworkTuning.WS_RECONNECT_MAX_DELAY_MS,
            jitter: Double = NetworkTuning.WS_RECONNECT_JITTER,
        ): ReconnectPolicy = ReconnectPolicy { attempt ->
            val raw = base.toDouble() * Math.pow(2.0, attempt.coerceIn(0, 30).toDouble())
            val nominal = minOf(max.toDouble(), raw)
            (nominal * (1.0 - jitter + 2.0 * jitter * random.nextDouble())).toLong()
        }
    }
}

/** Minimal log seam (no android.util.Log on the JVM test path). */
fun interface NetLog {
    fun log(level: Char, tag: String, message: String)

    companion object {
        val NONE = NetLog { _, _, _ -> }
    }
}

/** What `:core:session` needs from a socket; [SocketClient] is the real one, tests use fakes. */
interface FrameSocket {
    val state: StateFlow<SocketState>

    /** Single-collector, UNLIMITED, ordered. Never drops (replaces `tryEmit` on a 64-slot bus). */
    val events: Flow<SocketEvent>
    fun connect(url: String)

    /** Lifecycle/teardown close. Sends **no** application frame (decision P-1): never `stop`/`close`. */
    fun disconnect()
    fun send(frame: ClientFrame): SendResult

    /**
     * Reconnect immediately and reset the backoff (network available, foreground, Retry, T-14).
     * No-op while open or not wanted; held until [setReconnectAllowed] while reconnects are gated.
     */
    fun reconnectNow()

    /** T-13: reset the backoff counter. Call on `session_started`. */
    fun resetBackoff()

    /** T-14 gate: while false, a drop does not schedule a reconnect; it is held until true. */
    fun setReconnectAllowed(allowed: Boolean)
}

/**
 * One WebSocket endpoint (spec 14 §1.2): text frames out (T-2), text or binary in (T-1),
 * malformed frames and server `ping` frames dropped before the consumer (T-3, T-4),
 * reconnect with exponential backoff while wanted (T-13), `Disconnected(willReconnect)`.
 *
 * [url] passed to [connect] is the full `ws(s)://…/api/…` URL ([UrlScheme.wsUrl]).
 */
class SocketClient(
    private val clientFor: (url: String) -> OkHttpClient,
    private val scope: CoroutineScope,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy.DEFAULT,
    private val log: NetLog = NetLog.NONE,
    private val tag: String = "ws",
    private val handshakeTimeoutMs: Long = NetworkTuning.WS_HANDSHAKE_TIMEOUT_MS,
) : FrameSocket {
    constructor(stack: HttpStack, scope: CoroutineScope, reconnectPolicy: ReconnectPolicy = ReconnectPolicy.DEFAULT, log: NetLog = NetLog.NONE, tag: String = "ws") :
        this({ url -> stack.wsClient(url) }, scope, reconnectPolicy, log, tag)

    private val lock = Any()
    private var socket: WebSocket? = null
    private var url: String? = null
    private var wanted = false
    @Volatile private var generation = 0
    private var attempt = 0
    private var reconnectJob: Job? = null
    private var handshakeJob: Job? = null
    private var reconnectAllowed = true
    private var heldReconnect = false

    private val inbox = Channel<SocketEvent>(Channel.UNLIMITED)
    private val _state = MutableStateFlow<SocketState>(SocketState.Idle)
    override val state: StateFlow<SocketState> = _state.asStateFlow()
    override val events: Flow<SocketEvent> = inbox.receiveAsFlow()

    override fun connect(url: String) = synchronized(lock) {
        if (this.url == url && wanted && socket != null) return
        if (this.url != url) closeCurrentLocked()
        this.url = url
        wanted = true
        attempt = 0
        openLocked()
    }

    override fun disconnect() = synchronized(lock) {
        val wasActive = wanted || socket != null
        wanted = false
        heldReconnect = false
        reconnectJob?.cancel(); reconnectJob = null
        closeCurrentLocked()
        if (wasActive) {
            _state.value = SocketState.Disconnected(willReconnect = false, reason = "client")
            inbox.trySend(SocketEvent.Closed(willReconnect = false, code = 1000, reason = "client"))
        }
    }

    override fun reconnectNow() = synchronized(lock) {
        if (!wanted || socket != null) return
        attempt = 0
        if (!reconnectAllowed) {
            heldReconnect = true; return
        }
        openLocked()
    }

    override fun resetBackoff() = synchronized(lock) { attempt = 0 }

    /** Attempts since the last reset (for tests and diagnostics). */
    val backoffAttempt: Int get() = synchronized(lock) { attempt }

    override fun setReconnectAllowed(allowed: Boolean) = synchronized(lock) {
        reconnectAllowed = allowed
        if (allowed && heldReconnect) {
            heldReconnect = false
            if (wanted && socket == null) openLocked()
        }
    }

    override fun send(frame: ClientFrame): SendResult {
        val s = synchronized(lock) { if (_state.value == SocketState.Open) socket else null }
            ?: return SendResult.NOT_CONNECTED
        val text = ProtocolCodec.encodeClient(frame)
        if (!isAudio(frame.type)) log.log('D', tag, "send ${frame.type}")
        return if (s.send(text)) SendResult.SENT else SendResult.FAILED   // TEXT frame only (T-2)
    }

    private fun closeCurrentLocked() {
        generation++                               // callbacks of the old socket become stale
        socket?.close(1000, null)                  // WS close frame only; no app message (P-1)
        socket = null
    }

    private fun openLocked() {
        val target = url ?: return
        reconnectJob?.cancel(); reconnectJob = null
        socket?.cancel()
        val gen = ++generation
        _state.value = SocketState.Connecting(attempt)
        log.log('D', tag, "connect $target (attempt $attempt)")
        socket = clientFor(target).newWebSocket(Request.Builder().url(target).build(), Listener(gen))
        // T-16: an upgrade nobody answers is cancelled; the failure then reconnects with backoff.
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            delay(handshakeTimeoutMs)
            val stuck = synchronized(lock) { if (gen == generation && _state.value is SocketState.Connecting) socket else null }
            if (stuck != null) {
                log.log('W', tag, "handshake timed out after $handshakeTimeoutMs ms")
                stuck.cancel()
            }
        }
    }

    private fun onDrop(gen: Int, code: Int?, reason: String?) = synchronized(lock) {
        if (gen != generation) return
        socket = null
        val will = wanted
        _state.value = SocketState.Disconnected(will, reason)
        inbox.trySend(SocketEvent.Closed(will, code, reason))
        if (!will) return
        if (!reconnectAllowed) {
            heldReconnect = true; return
        }
        val wait = reconnectPolicy.delayMillis(attempt)
        attempt++
        reconnectJob = scope.launch {
            delay(wait)
            synchronized(lock) { if (wanted && socket == null && gen == generation) openLocked() }
        }
    }

    private fun onText(gen: Int, text: String) {
        if (gen != generation) return
        val frame = ProtocolCodec.decodeServer(text)
        if (frame == null) {
            log.log('W', tag, "dropped malformed frame (${text.take(80)})")   // T-3
            return
        }
        if (frame is ServerFrame.Ping) return                                // T-4
        if (!isAudio(frame.type)) log.log('D', tag, "recv ${frame.type}")
        inbox.trySend(SocketEvent.Frame(frame))                              // UNLIMITED: never fails while open
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (gen != generation) {
                    webSocket.cancel(); return
                }
                // The backoff is NOT reset on open: only `session_started` proves the server is
                // healthy (T-13), so a server that accepts then drops keeps backing off.
                handshakeJob?.cancel(); handshakeJob = null
                _state.value = SocketState.Open
                inbox.trySend(SocketEvent.Opened)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = onText(gen, text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onText(gen, bytes.utf8())
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDrop(gen, code, reason)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log.log('W', tag, "failure: ${t.message}")
            onDrop(gen, null, t.message ?: t.javaClass.simpleName)
        }
    }

    private companion object {
        /** Audio-frame log suppression (~50 Hz, 4 KB each; inv03 §3.3). */
        fun isAudio(type: String) = type == "voice_audio_out" || type == "voice_audio_in"
    }
}
