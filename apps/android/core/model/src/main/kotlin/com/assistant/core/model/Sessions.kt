package com.assistant.core.model

/**
 * Session identity and status (spec 12 §2.1, §2.2, §2.5).
 *
 * The backend overloads `session_id` (G-13). Client code never uses that bare word:
 * a [SessionRef] carries `localId` (pool key, minted by the client) and `sdkId`
 * (history key; the jsonl id for the orchestrator).
 */
enum class SessionKind(val wire: String) {
    AGENT("agent"),
    ORCHESTRATOR("orchestrator");

    companion object {
        fun fromWire(value: String?): SessionKind? = entries.firstOrNull { it.wire == value }
    }
}

/** Agent harness (REST `SessionInfo.provider`). `null` on a [SessionRef] means orchestrator. */
enum class HarnessProvider(val wire: String) {
    CLAUDE("claude"),
    QWEN("qwen"),
    GEMINI("gemini");

    companion object {
        fun fromWire(value: String?): HarnessProvider? = entries.firstOrNull { it.wire == value }
    }
}

/** `pool/live[].status` (manager/types.py:15-23). */
enum class LiveStatus(val wire: String) {
    IDLE("idle"),
    STREAMING("streaming"),
    TOOL_USE("tool_use"),
    THINKING("thinking"),
    INTERRUPTED("interrupted"),
    DISCONNECTED("disconnected");

    companion object {
        fun fromWire(value: String?): LiveStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** Conversation status machine (spec 12 §2.5). */
enum class SessionStatus(val wire: String) {
    CONNECTING("connecting"),
    IDLE("idle"),
    PROCESSING("processing"),
    STREAMING("streaming"),
    THINKING("thinking"),
    TOOL_USE("tool_use"),
    RETRYING("retrying"),
    COMPACTING("compacting"),
    STOPPED("stopped"),
    TERMINATED("terminated");

    /** `busy(status)` of spec 12 §2.5. */
    val busy: Boolean
        get() = this == PROCESSING || this == STREAMING || this == THINKING ||
            this == TOOL_USE || this == RETRYING || this == COMPACTING

    companion object {
        fun fromWire(value: String?): SessionStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** Socket state of one conversation (spec 12 §3.3). Separate from [SessionStatus]. */
enum class ConnectionState { OFFLINE, CONNECTING, OPEN, SUBSCRIBED, FAILED }

/** spec 12 §2.2. `title` is derived (MC-2), so it is not stored here. */
data class SessionRef(
    val localId: String,
    val sdkId: String?,
    val kind: SessionKind,
    val provider: HarnessProvider?,
    val live: Boolean = false,
    val liveStatus: LiveStatus? = null,
) {
    /** Only Claude agent sessions carry `seq`/`stream_id` (spec 12 §2.2). */
    val seqCapable: Boolean
        get() = kind == SessionKind.AGENT && provider == HarnessProvider.CLAUDE
}

/** One row of `GET /api/sessions/pool/live`. */
data class PoolSession(
    val localId: String,
    val sdkId: String?,
    val status: LiveStatus?,
    val cost: Double,
    val turns: Int,
    val title: String?,
    val isOrchestrator: Boolean,
)

/** One row of `GET /api/sessions` (`SessionInfoResponse`). Timestamps are ISO-8601 with offset. */
data class SessionSummary(
    val sdkId: String,
    val startedAt: String?,
    val lastActivity: String?,
    val title: String,
    val messageCount: Int,
    val isOrchestrator: Boolean,
    val provider: HarnessProvider?,
    val localId: String?,
)

/** Result of `POST /api/sessions/{id}/truncate|fork|duplicate` and similar id-returning calls. */
data class SessionIdResult(val sdkId: String)
