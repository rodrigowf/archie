package com.assistant.core.network

/**
 * Transport constants. The **LB** (load-bearing) values are pinned against the old app by
 * `ChannelTuningTest` (`:core:voice-host`) and `tools/parity/old_constants.json`.
 */
object NetworkTuning {
    /** OkHttp protocol ping (`WebSocketManager.kt:47`, `f77cd62`). Never add an app heartbeat (T-4). **LB** */
    const val WS_PING_INTERVAL_MS: Long = 30_000L

    /**
     * Reconnect backoff, spec 12 T-13: delay = min(MAX, BASE × 2^attempt) ± JITTER, unlimited
     * attempts, reset on `session_started`. Supersedes the old fixed 3000 ms
     * (`WebSocketManager.kt:42`, spec 12 A-3.3); `old_constants.json` marks that row superseded.
     */
    const val WS_RECONNECT_BASE_DELAY_MS: Long = 1_000L
    const val WS_RECONNECT_MAX_DELAY_MS: Long = 15_000L
    const val WS_RECONNECT_JITTER: Double = 0.2

    /** REST client timeouts (`ApiClient.kt:35-39`). */
    const val REST_CONNECT_TIMEOUT_MS: Long = 10_000L
    const val REST_READ_TIMEOUT_MS: Long = 30_000L

    /** Discovery TCP connect timeout per host:port (`NetworkScanner.kt:22`). */
    const val DISCOVERY_CONNECT_TIMEOUT_MS: Int = 400

    /** Discovery ports, preferred first (spec 14 §1.2: 80/443/8765). */
    @JvmField
    val DISCOVERY_PORTS: List<Int> = listOf(80, 8765, 443)
}
