package com.assistant.archie.feature.toolcards

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The orchestrator's conversation-history tools return JSON (backend orchestrator/tools/search.py):
 * search_history → sessions with matching excerpts, read_conversation → a window of turns.
 * Port of the web `renderers/history.tsx` parsers; null means "not this shape" and the card shows
 * the plain text instead (older result shapes, errors).
 */

data class HistoryTurn(val turn: Int, val role: String, val date: String?, val text: String)

data class HistorySession(
    val sessionId: String,
    val title: String?,
    val startedAt: String?,
    val relevance: String?,
    val note: String?,
    val copies: Int,
    val hits: List<HistoryTurn>,
)

data class HistorySearchResult(val sessions: List<HistorySession>, val error: String?, val note: String?)

data class ConversationReadResult(val title: String?, val total: Int?, val turns: List<HistoryTurn>, val error: String?)

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toInt()

private fun parseObject(output: String?): JsonObject? {
    val t = output?.trim().orEmpty()
    if (!t.startsWith("{")) return null
    return runCatching { Json.parseToJsonElement(t) }.getOrNull() as? JsonObject
}

private fun turnsOf(v: Any?): List<HistoryTurn> =
    (v as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { t ->
        HistoryTurn(t.int("turn") ?: 0, t.text("role") ?: "", t.text("date"), (t["text"] as? JsonPrimitive)?.content ?: "")
    }

fun parseHistorySearch(output: String?): HistorySearchResult? {
    val v = parseObject(output) ?: return null
    val sessions = v["sessions"] as? JsonArray ?: return null
    return HistorySearchResult(
        sessions = sessions.filterIsInstance<JsonObject>().map { x ->
            HistorySession(
                sessionId = x.text("session_id") ?: "",
                title = x.text("title"),
                startedAt = x.text("started_at"),
                relevance = x.text("relevance"),
                note = x.text("note"),
                copies = (x["copies"] as? JsonArray)?.size ?: 0,
                hits = turnsOf(x["hits"]),
            )
        },
        error = v.text("error"),
        note = v.text("note"),
    )
}

fun parseConversationRead(output: String?): ConversationReadResult? {
    val v = parseObject(output) ?: return null
    if (v["turns"] !is JsonArray && v.text("error") == null) return null
    return ConversationReadResult(v.text("title"), v.int("total_turns"), turnsOf(v["turns"]), v.text("error"))
}

/** `2026-06-20 · strong · 96a377c7 · +2 copies` (web search card meta line). */
fun historySessionMeta(s: HistorySession): String =
    listOf(
        s.startedAt?.take(10).orEmpty(),
        s.relevance.orEmpty(),
        shortId(s.sessionId),
        if (s.copies > 0) "+${s.copies} ${if (s.copies == 1) "copy" else "copies"}" else "",
    ).filter { it.isNotEmpty() }.joinToString(" · ")

/** `#12 user · 2026-02-25 18:20`. */
fun historyTurnHead(t: HistoryTurn): String =
    "#${t.turn} ${t.role}" + (t.date?.take(16)?.replace('T', ' ')?.let { " · $it" } ?: "")
