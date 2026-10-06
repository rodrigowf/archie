package com.assistant.core.session

/**
 * Orchestrator-channel constants, pinned against the old app by `ChannelTuningTest`
 * (`:core:voice-host`) and `tools/parity/old_constants.json`.
 */
object SessionTuning {
    /** Retry-once delay of the adoption probe on an empty pool (`OrchestratorConnectionController.kt:85`). **LB** */
    const val POOL_PROBE_RETRY_MS: Long = 400L

    /**
     * `orchestrator_active` recovery: delay before attempt n is `0` then `500 shl (n-1)`, capped at 3
     * attempts = 0 / 500 / 1000 ms (the OLD CODE; inv04 §4.6 and the code comment said 2000 — errata
     * spec 14 §12, `old_constants.json` wins). **LB**
     */
    @JvmField
    val RECOVERY_BACKOFF_MS: List<Long> = listOf(0L, 500L, 1_000L)

    /** `MAX_RECOVERY_RETRIES` (`:82`). */
    const val MAX_RECOVERY_ATTEMPTS: Int = 3

    /** Pending `inject_text` frames held until `session_started` (spec 12 §6.15). */
    const val INJECT_OUTBOX_CAPACITY: Int = 16
}
