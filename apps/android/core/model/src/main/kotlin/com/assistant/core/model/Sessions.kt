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

/**
 * Agent harness (REST `SessionInfo.provider`): an id from the backend's harness registry (`claude`,
 * `qwen`, `gemini`, `codex`, `modelstudio`, …). Open-ended on purpose — a new harness needs no
 * client edit; its label comes from [HarnessLabels]. `null` on a [SessionRef] means orchestrator.
 */
data class HarnessProvider(val wire: String) {
    /** The short tag shown on tabs and history rows ("Claude", "Model Studio", or the registry label / id). */
    val label: String
        get() = HarnessLabels.label(wire) ?: wire

    override fun toString(): String = wire

    companion object {
        val CLAUDE = HarnessProvider("claude")
        val QWEN = HarnessProvider("qwen")
        val GEMINI = HarnessProvider("gemini")
        val CODEX = HarnessProvider("codex")
        val MODELSTUDIO = HarnessProvider("modelstudio")

        /** Any non-blank id is a provider (unknown ids included); null / blank → null. */
        fun fromWire(value: String?): HarnessProvider? = value?.trim()?.takeIf { it.isNotEmpty() }?.let(::HarnessProvider)
    }
}

/**
 * Labels of session harnesses. The short tag of a harness Archie ships comes from [SHORT]; any
 * other id is labelled from the harness registry the app loaded (`GET /api/config/harnesses`, kept
 * in [registry] by the settings feature); an id nobody knows shows as is. Never null for a
 * non-blank id.
 */
object HarnessLabels {
    /** Compact tags for the harnesses Archie ships (web `SHORT_PROVIDER_LABELS`). */
    val SHORT: Map<String, String> = mapOf(
        "claude" to "Claude",
        "qwen" to "Qwen",
        "gemini" to "Gemini",
        "codex" to "Codex",
        "modelstudio" to "Model Studio",
    )

    /** id → label from the last registry the app loaded (empty until then). */
    @Volatile var registry: Map<String, String> = emptyMap()
        private set

    /** Remember the registry's labels (blank labels are skipped). */
    fun register(harnesses: List<HarnessInfo>) {
        registry = harnesses.filter { it.id.isNotEmpty() && it.label.isNotBlank() }.associate { it.id to it.label }
    }

    /** The label of a harness id: short tag → registry label → the id ("" / null → null). */
    fun label(id: String?, harnesses: List<HarnessInfo>? = null): String? {
        if (id.isNullOrBlank()) return null
        SHORT[id]?.let { return it }
        val fromList = harnesses?.firstOrNull { it.id == id }?.label?.takeIf { it.isNotBlank() }
        return fromList ?: registry[id] ?: id
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

/**
 * `GET /api/sessions/pool/live` with its `X-Archie-Server-Id` header (spec 12 SRV-1): [serverId]
 * names the server process, `null` from a server that does not send it.
 */
data class LivePoolSnapshot(
    val sessions: List<PoolSession>,
    val serverId: String?,
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
