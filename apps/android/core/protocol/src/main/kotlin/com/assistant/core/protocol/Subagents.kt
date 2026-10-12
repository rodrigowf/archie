package com.assistant.core.protocol

import kotlinx.serialization.json.JsonObject

/**
 * Wire shapes of spec 12 §4.8 (subagents). Plain classes parsed leniently by [ProtocolCodec] (a
 * wrong-typed field reads as `null`), shared by the `subagent_*` frames, the
 * `session_started.subagents` snapshot and `GET /api/sessions/{sdkId}/subagents`.
 */
data class SubagentUsage(
    val tokens: Long? = null,
    val toolUses: Int? = null,
    val durationMs: Long? = null,
)

/** `SubagentSummary`: the `Subagent` fields without `conv` / `held`. [status] is the raw wire string. */
data class SubagentSummary(
    val toolUseId: String,
    val parentToolUseId: String? = null,
    val agentType: String? = null,
    val description: String? = null,
    val background: Boolean? = null,
    /** `running` | `done` | `failed` | `stopped`. */
    val status: String? = null,
    val activity: String? = null,
    val usage: SubagentUsage? = null,
    val summary: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
)

internal fun decodeUsage(o: JsonObject?): SubagentUsage? =
    o?.let { SubagentUsage(it.long("tokens"), it.int("tool_uses"), it.long("duration_ms")) }

internal fun encodeUsage(u: SubagentUsage?): JsonObject? =
    u?.let { jsonObject { put("tokens", it.tokens); put("tool_uses", it.toolUses); put("duration_ms", it.durationMs) } }

/** One summary row; `null` without a string `tool_use_id`. */
internal fun decodeSubagentSummary(o: JsonObject): SubagentSummary? {
    val id = o.str("tool_use_id")?.takeIf { it.isNotEmpty() } ?: return null
    return SubagentSummary(
        toolUseId = id,
        parentToolUseId = o.str("parent_tool_use_id"),
        agentType = o.str("agent_type"),
        description = o.str("description"),
        background = o.bool("background"),
        status = o.str("status"),
        activity = o.str("activity"),
        usage = decodeUsage(o.obj("usage")),
        summary = o.str("summary"),
        startedAt = o.str("started_at"),
        endedAt = o.str("ended_at"),
    )
}

internal fun encodeSubagentSummary(s: SubagentSummary): JsonObject = jsonObject {
    put("tool_use_id", s.toolUseId)
    put("parent_tool_use_id", s.parentToolUseId)
    put("agent_type", s.agentType)
    put("description", s.description)
    put("background", s.background)
    put("status", s.status)
    put("activity", s.activity)
    put("usage", encodeUsage(s.usage))
    put("summary", s.summary)
    put("started_at", s.startedAt)
    put("ended_at", s.endedAt)
}
