package com.assistant.peripheral.face

import com.assistant.core.network.NetworkTuning
import com.assistant.core.network.SocketState

/**
 * Estimates when the orchestrator socket retries next, for the Offline face ("Retrying jetson in
 * 5 s", mockup §4). `SocketClient` does not expose its timer, so this mirrors its schedule: a drop
 * after `Connecting(attempt = n)` waits `ReconnectPolicy.DEFAULT.delayMillis(n)`, nominally
 * min(max, base × 2^n) (± jitter, so the face rounds up and says "Retrying…" at zero).
 */
class RetryEstimator(
    private val baseMs: Long = NetworkTuning.WS_RECONNECT_BASE_DELAY_MS,
    private val maxMs: Long = NetworkTuning.WS_RECONNECT_MAX_DELAY_MS,
) {
    private var lastAttempt = 0
    private var retryAtMs: Long? = null

    fun onSocket(state: SocketState, nowMs: Long) {
        when (state) {
            is SocketState.Connecting -> {
                lastAttempt = state.attempt
                retryAtMs = null
            }
            is SocketState.Disconnected -> retryAtMs = if (state.willReconnect) nowMs + nominalDelay(lastAttempt) else null
            SocketState.Open, SocketState.Idle -> {
                retryAtMs = null
                if (state == SocketState.Idle) lastAttempt = 0
            }
        }
    }

    /** Milliseconds until the next attempt, or null when none is scheduled. Never negative. */
    fun retryInMs(nowMs: Long): Long? = retryAtMs?.let { (it - nowMs).coerceAtLeast(0) }

    fun nominalDelay(attempt: Int): Long =
        minOf(maxMs.toDouble(), baseMs * Math.pow(2.0, attempt.coerceIn(0, 30).toDouble())).toLong()
}
