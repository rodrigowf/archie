package com.assistant.archie.tv.control

import com.assistant.core.network.HttpStack
import com.assistant.core.network.UrlScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** A text WebSocket (OkHttp in the app, a fake in tests). */
interface TvSocket {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String?)
    fun cancel()
}

/** Callbacks of one [TvSocket]; any thread. */
interface TvSocketListener {
    fun onOpen()
    fun onMessage(text: String)

    /** The socket is gone: a close handshake ([code]) or a failure (`code` null). */
    fun onClosed(code: Int?, reason: String?)
}

fun interface TvSocketFactory {
    /** Opens [url] (`ws(s)://host[:port]/api/tv/ws`) for the server [serverUrl] (TLS / pins). */
    fun open(serverUrl: String, url: String, listener: TvSocketListener): TvSocket
}

/** [TvSocketFactory] over the app's [HttpStack]: the same OkHttp client, TOFU pins and ping interval as the orchestrator socket. */
class OkHttpTvSocketFactory(private val http: HttpStack) : TvSocketFactory {
    override fun open(serverUrl: String, url: String, listener: TvSocketListener): TvSocket {
        val ws = http.wsClient(serverUrl).newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    listener.onClosed(code, reason)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(code, reason)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    listener.onClosed(null, t.message ?: t.javaClass.simpleName)
            },
        )
        return object : TvSocket {
            override fun send(text: String) = ws.send(text)
            override fun close(code: Int, reason: String?) { ws.close(code, reason) }
            override fun cancel() = ws.cancel()
        }
    }
}

/** What the control socket is doing (Settings → TV control shows it; logs). */
sealed interface TvControlState {
    data object Idle : TvControlState
    data class Connecting(val attempt: Int) : TvControlState
    data object Ready : TvControlState

    /** Dropped; the next attempt in [delayMs]. */
    data class Waiting(val attempt: Int, val delayMs: Long, val reason: String?) : TvControlState

    /**
     * Close 4401 after the server's `unauthorized` (wrong / missing token): stopped until
     * [TvControlClient.retry] or a new target. Other 4401 reasons (`hello_timeout`,
     * `invalid_json`, …) and 4409 (replaced) back off and retry ([Waiting]).
     */
    data class Rejected(val reason: String) : TvControlState
}

/**
 * The TV app's control socket (spec 15 §5, spec 12 §9.5 RD-1..6): connects `/api/tv/ws` on the
 * [setTarget] server, says hello, answers pings, runs every `command` through [onCommand] and
 * answers exactly one `result` per id (RD-2), and sends `state` frames ([sendState], RD-5).
 *
 * Reconnects with 1 s × 2ⁿ backoff capped at 15 s (spec 12 T-13; reset once `ready` arrives). Only
 * a **4401** whose reason (the `error` frame the server sends before closing) is `unauthorized`
 * stops for good — until [retry] or a new target — and is logged as "TV control rejected"; any other
 * 4401 (`hello_timeout`, `invalid_json`, …) retries with the backoff. **4409** (replaced by a newer
 * socket with this device id) retries too, with a backoff that keeps growing across consecutive
 * replacements (two clients with one id never ping-pong faster than every 15 s).
 *
 * Thread-safe: socket callbacks arrive on OkHttp threads; state changes are serialized on [lock].
 */
class TvControlClient(
    private val scope: CoroutineScope,
    private val sockets: TvSocketFactory,
    private val hello: () -> TvHello,
    private val onCommand: suspend (TvCommand) -> TvReply,
    private val log: (level: Char, message: String) -> Unit = { _, _ -> },
    private val backoff: (attempt: Int) -> Long = ::defaultBackoff,
    private val readyTimeoutMs: Long = READY_TIMEOUT_MS,
) {
    private val lock = Any()
    private val _state = MutableStateFlow<TvControlState>(TvControlState.Idle)
    val state: StateFlow<TvControlState> = _state.asStateFlow()

    private var serverUrl: String? = null
    private var socket: TvSocket? = null
    private var generation = 0
    private var attempt = 0
    private var reconnectJob: Job? = null
    private var readyJob: Job? = null
    private var ready = false
    private var lastError: String? = null
    private var lastState: String? = null

    /** Consecutive 4409 closes (reset by [setTarget], [retry], [reconnectNow]). */
    private var replacedStreak = 0

    /** The server to stay connected to (null = none). A new server reconnects at once and clears a rejection. */
    fun setTarget(url: String?) = synchronized(lock) {
        if (url == serverUrl && (socket != null || reconnectJob?.isActive == true || _state.value is TvControlState.Rejected)) return
        closeLocked()
        serverUrl = url
        attempt = 0
        replacedStreak = 0
        if (url == null) _state.value = TvControlState.Idle else openLocked()
    }

    /**
     * The server is reachable again (orchestrator connected, network back): connect now with a
     * fresh backoff. No-op while connected, without a target, or after a rejection.
     */
    fun reconnectNow() = synchronized(lock) {
        if (serverUrl == null || socket != null) return
        if (_state.value is TvControlState.Rejected) return
        attempt = 0
        replacedStreak = 0
        openLocked()
    }

    /** After a rejection (a new token in Settings): try again. */
    fun retry() = synchronized(lock) {
        if (serverUrl == null || socket != null) return
        attempt = 0
        replacedStreak = 0
        openLocked()
    }

    /** Stops for good (tests, process teardown). */
    fun stop() = setTarget(null)

    /** Sends the current `state` frame now if ready, and again after every `ready` (RD-5). Duplicate frames are skipped. */
    fun sendState(frame: String) {
        val s = synchronized(lock) {
            if (frame == lastState && ready) return
            lastState = frame
            if (ready) socket else null
        }
        s?.send(frame)
    }

    private fun closeLocked() {
        generation++
        reconnectJob?.cancel(); reconnectJob = null
        readyJob?.cancel(); readyJob = null
        ready = false
        socket?.close(1000, null)
        socket = null
    }

    private fun openLocked() {
        val server = serverUrl ?: return
        reconnectJob?.cancel(); reconnectJob = null
        socket?.cancel()
        val gen = ++generation
        ready = false
        lastError = null
        _state.value = TvControlState.Connecting(attempt)
        val url = UrlScheme.wsBase(server) + TvControlProtocol.PATH
        log('D', "connect $url (attempt $attempt)")
        socket = try {
            sockets.open(server, url, Listener(gen))
        } catch (e: Exception) {
            log('W', "connect failed: ${e.message}")
            null
        }
        if (socket == null) {
            scheduleLocked(gen, e = "connect failed")
            return
        }
        readyJob?.cancel()
        readyJob = scope.launch {
            delay(readyTimeoutMs)
            val stuck = synchronized(lock) { if (gen == generation && !ready) socket else null }
            if (stuck != null) {
                log('W', "no ready within $readyTimeoutMs ms")
                handleClosed(gen, null, "ready timeout")
                stuck.cancel()
            }
        }
    }

    private fun scheduleLocked(gen: Int, e: String?) {
        val wait = backoff(attempt)
        val n = attempt
        attempt++
        _state.value = TvControlState.Waiting(n, wait, e)
        reconnectJob = scope.launch {
            delay(wait)
            synchronized(lock) { if (gen == generation && socket == null && serverUrl != null) openLocked() }
        }
    }

    private fun handleOpen(gen: Int) {
        val s = synchronized(lock) { if (gen == generation) socket else null } ?: return
        s.send(TvControlProtocol.hello(hello()))
    }

    private fun handleMessage(gen: Int, text: String) {
        val s = synchronized(lock) { if (gen == generation) socket else null } ?: return
        when (val m = TvControlProtocol.parse(text)) {
            TvInbound.Ready -> {
                val state = synchronized(lock) {
                    if (gen != generation) return
                    ready = true
                    attempt = 0
                    readyJob?.cancel(); readyJob = null
                    _state.value = TvControlState.Ready
                    lastState
                }
                log('I', "TV control ready")
                state?.let(s::send)
            }
            is TvInbound.Error -> synchronized(lock) { if (gen == generation) lastError = m.reason }
            TvInbound.Ping -> s.send(TvControlProtocol.PONG)
            is TvInbound.Command -> scope.launch {
                val reply = try {
                    onCommand(m.command)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TvReply.Error(e.message ?: e.javaClass.simpleName)
                }
                // RD-2: the result goes to the socket the command came from, never to a newer one.
                val target = synchronized(lock) { if (gen == generation) socket else null }
                target?.send(TvControlProtocol.result(m.command.id, reply))
            }
            TvInbound.Pong, TvInbound.Other -> Unit
        }
    }

    private fun handleClosed(gen: Int, code: Int?, reason: String?) = synchronized(lock) {
        // Stale socket, or already handled (OkHttp reports onClosing and then onClosed).
        if (gen != generation || socket == null) return
        socket = null
        ready = false
        readyJob?.cancel(); readyJob = null
        when (code) {
            TvControlProtocol.CLOSE_UNAUTHORIZED -> {
                val why = lastError ?: reason?.takeIf { it.isNotBlank() } ?: "no reason given"
                if (why == UNAUTHORIZED) {
                    log('W', "TV control rejected: $why")
                    _state.value = TvControlState.Rejected(why)
                } else if (serverUrl != null) {
                    // hello_timeout, invalid_json, …: a transient or local hiccup, not a verdict on this TV.
                    log('W', "TV control refused the hello ($why) — retrying")
                    scheduleLocked(gen, "refused: $why")
                } else {
                    _state.value = TvControlState.Idle
                }
            }
            TvControlProtocol.CLOSE_REPLACED -> {
                log('W', "TV control socket replaced by a newer one (4409) — retrying")
                attempt = maxOf(attempt, replacedStreak)
                replacedStreak++
                if (serverUrl != null) scheduleLocked(gen, REPLACED_REASON) else _state.value = TvControlState.Idle
            }
            else -> {
                if (serverUrl == null) {
                    _state.value = TvControlState.Idle
                } else {
                    log('D', "closed ($code ${reason.orEmpty()})")
                    scheduleLocked(gen, reason)
                }
            }
        }
    }

    private inner class Listener(private val gen: Int) : TvSocketListener {
        override fun onOpen() = handleOpen(gen)
        override fun onMessage(text: String) = handleMessage(gen, text)
        override fun onClosed(code: Int?, reason: String?) = handleClosed(gen, code, reason)
    }

    companion object {
        const val READY_TIMEOUT_MS = 15_000L
        const val BASE_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 15_000L

        /** The only 4401 reason that stops the client for good (a wrong / missing token). */
        const val UNAUTHORIZED = "unauthorized"

        const val REPLACED_REASON = "replaced by a newer connection with this TV's id"

        /** 1 s × 2ⁿ, capped at 15 s (spec 15 §5.1 → spec 12 T-13). */
        fun defaultBackoff(attempt: Int): Long = minOf(MAX_DELAY_MS, BASE_DELAY_MS shl attempt.coerceIn(0, 20))
    }
}
