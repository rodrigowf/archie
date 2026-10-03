package com.assistant.core.voicehost.parity

import com.assistant.core.testing.ArchieRoot
import com.assistant.core.testing.OldConstants
import com.assistant.core.testing.TuningPins
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Orchestrator-channel constants the voice host depends on (inv04 §4.6), owned by A-03
 * (`:core:network`, `:core:session`). Pinned here because the host's test classpath sees both
 * modules. Names requested from A-03: `NetworkTuning.WS_PING_INTERVAL_MS`,
 * `NetworkTuning.WS_RECONNECT_BASE_DELAY_MS` (+ `_MAX_DELAY_MS`, `_JITTER`),
 * `SessionTuning.POOL_PROBE_RETRY_MS`, `SessionTuning.RECOVERY_BACKOFF_MS`.
 *
 * `network.ws_reconnect_delay_ms` (old fixed 3000 ms) is an INTENTIONAL change: superseded by
 * spec 12 T-13 (A-3.3). Its `old_constants.json` row keeps the old value and carries
 * `"superseded"`; the cross-check skips superseded rows and the new schedule is pinned below
 * (behaviour tests: `SocketClientTest` in `:core:network`).
 */
class ChannelTuningTest {
    private val network = TuningPins("com.assistant.core.network.NetworkTuning", "A-03")
    private val session = TuningPins("com.assistant.core.session.SessionTuning", "A-03")

    /**
     * RS-46 (`f77cd62`): the orchestrator socket survives idle periods on the A300M — okhttp pings
     * every 30 s while the backend's app-level `ping` (every 15 s) keeps the radio awake. The LB
     * label of this row belongs to the ping, which is kept exactly. (The ping-consumption behaviour
     * itself is A-03's socket test.)
     */
    @Test
    fun rs46_backendPingKeepaliveIs30s() {
        network.long("network.ws_ping_interval_ms", "WS_PING_INTERVAL_MS", 30_000L)
    }

    /**
     * Old fixed 3 s reconnect — superseded by spec 12 T-13 (A-3.3): min(15 s, 1 s × 2^attempt)
     * ± 20 % jitter, reset on `session_started`.
     */
    @Test
    fun reconnectBackoffIsT13_supersedingTheFixed3s() {
        assertEquals("spec 12 T-13 (A-3.3)", supersededBy("network.ws_reconnect_delay_ms"))
        assertEquals("old value kept in the extraction", 3_000L, OldConstants["network.ws_reconnect_delay_ms"].value.toString().toLong())
        network.long("network.ws_reconnect_delay_ms", "WS_RECONNECT_BASE_DELAY_MS", 1_000L)
        network.long("network.ws_reconnect_delay_ms", "WS_RECONNECT_MAX_DELAY_MS", 15_000L)
        network.double("network.ws_reconnect_delay_ms", "WS_RECONNECT_JITTER", 0.2)
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
            .filter { supersededBy(it.id) == null }
            .forEach { OldConstants.assertNewMatchesOld(it, "A-03") }
    }

    /** The `"superseded"` marker of an `old_constants.json` row (intentional changes), or null. */
    private fun supersededBy(id: String): String? {
        val root = Json.parseToJsonElement(ArchieRoot.file("tools/parity/old_constants.json").readText()).jsonObject
        val row = root["constants"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == id }
        return row["superseded"]?.jsonPrimitive?.content
    }
}
