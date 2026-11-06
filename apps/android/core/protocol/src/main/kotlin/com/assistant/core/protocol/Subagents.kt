package com.assistant.core.protocol

import kotlinx.serialization.json.JsonArray
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

// ───────────── pending permissions (PM-6) ─────────────
// Same leniency as the subagent rows above: a row without a `request_id` is dropped (it could
// never be answered). Lives here beside the other `session_started` snapshot codecs.

private val EMPTY_JSON_OBJECT = JsonObject(emptyMap())

/** One `session_started.permissions` row; `null` without a string `request_id`. */
internal fun decodePendingPermission(o: JsonObject): PendingPermission? {
    val rid = o.str("request_id")?.takeIf { it.isNotEmpty() } ?: return null
    return PendingPermission(rid, o.str("tool_name") ?: "", o.obj("tool_input") ?: EMPTY_JSON_OBJECT)
}

internal fun encodePendingPermission(p: PendingPermission): JsonObject = jsonObject {
    put("request_id", p.requestId)
    put("tool_name", p.toolName)
    put("tool_input", p.toolInput)
}

/** One `session_started.agent_permissions` row; `null` without a `session_id` and `request_id`. */
internal fun decodeAgentPendingPermission(o: JsonObject): AgentPendingPermission? {
    val sid = o.str("session_id")?.takeIf { it.isNotEmpty() } ?: return null
    val rid = o.str("request_id")?.takeIf { it.isNotEmpty() } ?: return null
    return AgentPendingPermission(sid, rid, o.str("tool_name") ?: "", o.obj("tool_input") ?: EMPTY_JSON_OBJECT)
}

internal fun encodeAgentPendingPermission(p: AgentPendingPermission): JsonObject = jsonObject {
    put("session_id", p.sessionId)
    put("request_id", p.requestId)
    put("tool_name", p.toolName)
    put("tool_input", p.toolInput)
}

// ───────────── pending questions (QU-6) ─────────────
// Same leniency: a row with no `request_id` or no questions is dropped — it could never be
// answered, and showing an empty picker would just block the agent silently.

private val EMPTY_JSON_ARRAY = JsonArray(emptyList())

/** One `session_started.questions` row; `null` without a `request_id` and a non-empty list. */
internal fun decodePendingQuestion(o: JsonObject): PendingQuestion? {
    val rid = o.str("request_id")?.takeIf { it.isNotEmpty() } ?: return null
    val questions = o.arr("questions")?.takeIf { it.isNotEmpty() } ?: return null
    return PendingQuestion(rid, questions, o.str("tool_use_id")?.takeIf { it.isNotEmpty() })
}

internal fun encodePendingQuestion(q: PendingQuestion): JsonObject = jsonObject {
    put("request_id", q.requestId)
    put("questions", q.questions)
    put("tool_use_id", q.toolUseId)
}

/** One `session_started.agent_questions` row; `null` without a `session_id` and `request_id`. */
internal fun decodeAgentPendingQuestion(o: JsonObject): AgentPendingQuestion? {
    val sid = o.str("session_id")?.takeIf { it.isNotEmpty() } ?: return null
    val rid = o.str("request_id")?.takeIf { it.isNotEmpty() } ?: return null
    val questions = o.arr("questions")?.takeIf { it.isNotEmpty() } ?: return null
    return AgentPendingQuestion(sid, rid, questions, o.str("tool_use_id")?.takeIf { it.isNotEmpty() })
}

internal fun encodeAgentPendingQuestion(q: AgentPendingQuestion): JsonObject = jsonObject {
    put("session_id", q.sessionId)
    put("request_id", q.requestId)
    put("questions", q.questions)
    put("tool_use_id", q.toolUseId)
}
