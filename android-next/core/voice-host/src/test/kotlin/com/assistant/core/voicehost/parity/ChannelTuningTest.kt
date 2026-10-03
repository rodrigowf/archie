package com.assistant.core.voicehost.parity

import com.assistant.core.testing.OldConstants
import com.assistant.core.testing.TuningPins
import org.junit.Ignore
import org.junit.Test

/**
 * Orchestrator-channel constants the voice host depends on (inv04 §4.6), owned by A-03
 * (`:core:network`, `:core:session`). Pinned here because the host's test classpath sees both
 * modules. Names requested from A-03: `NetworkTuning.WS_PING_INTERVAL_MS`,
 * `NetworkTuning.WS_RECONNECT_DELAY_MS`, `SessionTuning.POOL_PROBE_RETRY_MS`,
 * `SessionTuning.RECOVERY_BACKOFF_MS`.
 */
@Ignore("A-03")
class ChannelTuningTest {
    private val network = TuningPins("com.assistant.core.network.NetworkTuning", "A-03")
    private val session = TuningPins("com.assistant.core.session.SessionTuning", "A-03")

    /**
     * RS-46 (`f77cd62`): the orchestrator socket survives idle periods on the A300M — okhttp pings
     * every 30 s while the backend's app-level `ping` (every 15 s) keeps the radio awake; a drop
     * reconnects after a fixed 3 s. (The ping-consumption behaviour itself is A-03's socket test.)
     */
    @Test
    fun rs46_backendPingKeepaliveAndReconnectTimings() {
        network.long("network.ws_ping_interval_ms", "WS_PING_INTERVAL_MS", 30_000L)
        network.long("network.ws_reconnect_delay_ms", "WS_RECONNECT_DELAY_MS", 3_000L)
    }

    @Test fun poolProbeRetryIs400ms() = session.long("session.pool_probe_retry_ms", "POOL_PROBE_RETRY_MS", 400L)

    /**
     * The OLD code waits `500 shl (attempt - 1)` before attempts 1 and 2 (cap 3) = 0 / 500 / 1000 ms.
     * inv04 §4.6 and spec 14 say 0 / 500 / 2000 (the code comment's claim); the code wins until Rodrigo
     * decides otherwise.
     */
    @Test fun recoveryBackoff() = session.longList("session.recovery_backoff_ms", "RECOVERY_BACKOFF_MS", listOf(0L, 500L, 1_000L))

    @Test
    fun crossCheckAgainstTheOldValues() {
        OldConstants.all.filter { it.module == "network" || it.module == "session" }
            .forEach { OldConstants.assertNewMatchesOld(it, "A-03") }
    }
}
