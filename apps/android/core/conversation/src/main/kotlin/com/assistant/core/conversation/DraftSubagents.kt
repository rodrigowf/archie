package com.assistant.core.conversation

import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
import com.assistant.core.model.SessionStatus
import com.assistant.core.model.ConnectionState
import com.assistant.core.protocol.PaginatedMessagesDto
import com.assistant.core.protocol.ServerFrame
import com.assistant.core.protocol.SubagentSummary
import com.assistant.core.protocol.SubagentUsage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Spec 12 §4.8 (SA-1 … SA-9): subagents. A subagent is keyed by the `tool_use_id` of the call that
 * spawned it and lives in `ConversationState.subagents` (first-sight order). Its frames never touch
 * the parent's timeline, status, turn, stall or counters (SA-1): only this file handles them.
 */

/** `upsertSubagent`: creates in first-sight order, then merges the non-null fields. */
internal fun Draft.upsertSubagent(
    id: String,
    parent: String? = null,
    agentType: String? = null,
    description: String? = null,
    background: Boolean? = null,
    status: SubagentStatus? = null,
    activity: String? = null,
    usage: SubagentUsage? = null,
    summary: String? = null,
    startedAt: String? = null,
    endedAt: String? = null,
    lifecycle: Boolean = false,
): Subagent {
    val cur = subagents[id]
    val base = cur ?: Subagent(id)
    // An empty string never overwrites a known value (SA-2; the web skips them too).
    fun String?.known() = this?.takeIf { it.isNotEmpty() }
    val n = base.copy(
        parentToolUseId = parent.known() ?: base.parentToolUseId,
        agentType = agentType.known() ?: base.agentType,
        description = description.known() ?: base.description,
        background = background ?: base.background,
        status = status ?: base.status,
        activity = activity.known() ?: base.activity,
        usage = usage ?: base.usage,
        summary = summary ?: base.summary,
        startedAt = startedAt.known() ?: base.startedAt,
        endedAt = endedAt.known() ?: base.endedAt,
        lifecycle = base.lifecycle || lifecycle,
    )
    if (cur == null || n != cur) subagents[id] = n
    return n
}

/**
 * A server row (SA-3 snapshot, SA-8 list): authoritative, like the lifecycle frames, except that
 * **terminal statuses stick** — a row computed before a live `subagent_finished` may arrive after it, so
 * a row never moves a terminal status (from a lifecycle frame or an earlier row) back to `running`. A
 * status only the spawn card set (no lifecycle yet, SA-2) is always corrected by the row.
 */
internal fun Draft.upsertSummary(r: SubagentSummary) {
    val cur = subagents[r.toolUseId]
    var status = SubagentStatus.fromWire(r.status)
    if (cur != null && cur.lifecycle && cur.status.finished && status == SubagentStatus.RUNNING) status = null
    val s = upsertSubagent(
        r.toolUseId, r.parentToolUseId, r.agentType, r.description, r.background, status,
        r.activity, r.usage, r.summary, r.startedAt, r.endedAt, lifecycle = true,
    )
    if (s.status.finished) endSubagentTurn(r.toolUseId)
}

/**
 * SA-2: a tool card whose normalised name is `Task` creates (or refreshes) the subagent placeholder.
 * Empty strings never overwrite what is known. Inside a subagent's own transcript the card belongs to
 * a nested agent: it goes to the top-level map with that subagent as its parent (flat routing, SA-6).
 */
internal fun Draft.spawnPlaceholder(tb: ToolBlock) {
    if (!isAgent || tb.toolUseId.isEmpty() || !ToolNameNormalizer.isSubagentSpawn(tb.toolName)) return
    val sink = spawnSink ?: this
    val input = tb.toolInput
    sink.upsertSubagent(
        tb.toolUseId,
        parent = spawnParent,
        agentType = (input.str("subagent_type") ?: input.str("agent_name"))?.takeIf { it.isNotEmpty() },
        description = input.str("description")?.takeIf { it.isNotEmpty() },
    )
    sink.spawnCards++
    sink.toolsTouched = true
}

/**
 * SA-2: a subagent that no lifecycle frame or server row described follows its spawn card:
 * `done`→done, `error`→failed, `no_result`→stopped; its summary is the card's output.
 * Runs at the end of a top-level step that touched a tool card.
 */
internal fun Draft.syncSpawnStatus() {
    if (subagents.isEmpty()) return
    for ((id, s) in subagents.entries.toList()) {
        if (s.lifecycle) continue
        val tb = tool(id) ?: s.parentToolUseId?.let { p -> subagents[p]?.conv?.toolBlock(id) } ?: continue
        val st = when (tb.status) {
            ToolStatus.RUNNING -> SubagentStatus.RUNNING
            ToolStatus.DONE -> SubagentStatus.DONE
            ToolStatus.ERROR -> SubagentStatus.FAILED
            ToolStatus.NO_RESULT -> SubagentStatus.STOPPED
        }
        val n = s.copy(status = st, summary = tb.output ?: s.summary)
        if (n != s) subagents[id] = n
        if (st.finished) endSubagentTurn(id)
    }
}

// ───────────────────────── frames ─────────────────────────

internal fun Draft.onSubagentStarted(f: ServerFrame.SubagentStarted) {
    val id = f.toolUseId?.takeIf { it.isNotEmpty() } ?: return
    upsertSubagent(id, f.parentToolUseId, f.agentType, f.description, f.background, startedAt = f.startedAt, lifecycle = true)
}

internal fun Draft.onSubagentProgress(f: ServerFrame.SubagentProgress) {
    val id = f.toolUseId?.takeIf { it.isNotEmpty() } ?: return
    upsertSubagent(id, activity = f.activity, usage = f.usage)
}

internal fun Draft.onSubagentFinished(f: ServerFrame.SubagentFinished) {
    val id = f.toolUseId?.takeIf { it.isNotEmpty() } ?: return
    upsertSubagent(
        id, status = SubagentStatus.fromWire(f.status)?.takeIf { it.finished } ?: SubagentStatus.DONE,   // unknown reads as done
        summary = f.summary, usage = f.usage, endedAt = f.endedAt, lifecycle = true,
    )
    endSubagentTurn(id)
}

internal fun Draft.onSubagentEvent(f: ServerFrame.SubagentEvent) {
    val id = f.toolUseId?.takeIf { it.isNotEmpty() } ?: return
    val s = upsertSubagent(id)
    val inner = f.frame ?: return
    if (!inner.isSubagentContent()) return                                    // §4.8: content frames only
    if (inner is ServerFrame.ToolUse && !inner.toolName.isNullOrEmpty()) upsertSubagent(id, activity = inner.toolName)
    val held = s.held
    when {
        held != null -> subagents[id] = subagents[id]!!.copy(held = held.add(inner))
        s.conv != null -> reduceInner(id) { reduceFrame(inner) }
        else -> Unit                                                          // SA-5: summary only
    }
}

/** SA-3: the session left the server; nothing of it runs any more. */
internal fun Draft.stopRunningSubagents() {
    for ((id, s) in subagents.entries.toList()) {
        if (s.status != SubagentStatus.RUNNING) continue
        subagents[id] = s.copy(status = SubagentStatus.STOPPED)
        endSubagentTurn(id)
    }
}

// ───────────────────────── lazy transcripts (SA-5) ─────────────────────────

internal fun Draft.openSubagent(id: String) {
    val s = subagents[id] ?: return
    subagentLru.remove(id)
    subagentLru.add(0, id)
    if (s.conv == null) {
        subagents[id] = s.copy(conv = newSubagentConversation(s), held = persistentListOf(), historyError = null)
        effects += ConversationEffect.FetchSubagentPage(id)
    }
    while (subagentLru.size > ConversationState.MAX_OPEN_SUBAGENTS) releaseSubagent(subagentLru.last())
}

internal fun Draft.releaseSubagent(id: String) {
    subagentLru.remove(id)
    val s = subagents[id] ?: return
    if (s.conv != null || s.held != null) subagents[id] = s.copy(conv = null, held = null, historyError = null)
}

/** The first page arrived: `history_page{replace}` on its transcript, then the held frames in order. */
internal fun Draft.subagentPage(id: String, page: PaginatedMessagesDto) {
    val s = subagents[id] ?: return
    val held = s.held ?: return                                               // not waiting (stale answer)
    if (s.conv == null) return
    reduceInner(id) {
        HistoryMerger.applyPage(this, PageMode.REPLACE, page)
        replayHeld(held)
    }
    subagents[id] = subagents[id]!!.copy(held = null, historyError = null)
}

/** A failed fetch still releases the held frames; the transcript starts at the live tail. */
internal fun Draft.subagentPageFailed(id: String, message: String) {
    val s = subagents[id] ?: return
    val held = s.held ?: return
    if (s.conv == null) return
    reduceInner(id) {
        history = history.copy(loaded = true)
        replayHeld(held)
    }
    subagents[id] = subagents[id]!!.copy(held = null, historyError = message)
}

/**
 * SA-4: [block] runs the §4.3 reducer on the subagent's own transcript (no §3.6: the parent's
 * checkpoint already deduplicated the wrapper). Effects of the inner step are not the view's and are
 * dropped; spawn cards found there go to this map (SA-6).
 */
private inline fun Draft.reduceInner(id: String, block: Draft.() -> Unit) {
    val conv = subagents[id]?.conv ?: return
    val inner = Draft(conv)
    inner.spawnSink = this
    inner.spawnParent = id
    inner.block()
    subagents[id] = subagents[id]!!.copy(conv = inner.build())
    toolsTouched = true
}

/**
 * `endTurnOf(s.conv)`: the subagent finished, so its transcript's turn ends (I-7). While its first page
 * loads (SA-4) the end is queued in `held` behind the held frames (a `subagent_finished` marker), so the
 * held content cannot restart a turn that then never ends.
 */
private fun Draft.endSubagentTurn(id: String) {
    val s = subagents[id] ?: return
    val conv = s.conv ?: return
    val held = s.held
    if (held != null) {
        if (held.lastOrNull() !is ServerFrame.SubagentFinished) subagents[id] = s.copy(held = held.add(ServerFrame.SubagentFinished(id)))
        return
    }
    if (!conv.inTurn && conv.status != SessionStatus.PROCESSING) return
    reduceInner(id) { endTurn("subagent_finished") }
}

/** The held frames in order; a queued end marker ends the transcript's turn at its place. */
private fun Draft.replayHeld(held: List<ServerFrame>) {
    for (f in held) if (f is ServerFrame.SubagentFinished) endTurn("subagent_finished") else reduceFrame(f)
}

private fun ServerFrame.isSubagentContent(): Boolean =
    this is ServerFrame.TextDelta || this is ServerFrame.TextComplete || this is ServerFrame.ThinkingDelta ||
        this is ServerFrame.ThinkingComplete || this is ServerFrame.ToolUse || this is ServerFrame.ToolResult

/**
 * §5.6 / D6: a canonical reload may have missed inner frames, so every open transcript that is not
 * already loading fetches its first page again (held from now on, before the buffered frames replay).
 */
internal fun Draft.refetchOpenSubagents() {
    for ((id, s) in subagents.entries.toList()) {
        if (s.conv == null || s.held != null) continue
        subagents[id] = s.copy(held = persistentListOf())
        effects += ConversationEffect.FetchSubagentPage(id)
    }
}

/** Kind `agent`, the parent's provider; a running subagent opens mid-turn (its history tail may still run). */
private fun Draft.newSubagentConversation(s: Subagent): ConversationState {
    val running = s.status == SubagentStatus.RUNNING
    return ConversationState(
        ref = SessionRef(localId = "${ref.localId}/subagent/${s.toolUseId}", sdkId = null, kind = SessionKind.AGENT, provider = ref.provider),
        status = if (running) SessionStatus.PROCESSING else SessionStatus.IDLE,
        inTurn = running,
        connection = ConnectionState.SUBSCRIBED,
    )
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
