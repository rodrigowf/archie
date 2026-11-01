package com.assistant.core.conversation

import com.assistant.core.model.ConnectionState
import com.assistant.core.model.LiveStatus
import com.assistant.core.model.SessionStatus
import com.assistant.core.protocol.ClientFrame
import com.assistant.core.protocol.ResumeCursor
import com.assistant.core.protocol.ServerFrame

/** Entry point of one reducer step. */
internal fun Draft.apply(input: ConversationInput) {
    applyInput(input)
    if (toolsTouched) syncSpawnStatus()                                      // SA-2 (top level only)
}

private fun Draft.applyInput(input: ConversationInput) {
    when (input) {
        is ConversationInput.Frame -> onFrame(input.frame)
        is ConversationInput.HistoryPage -> {
            HistoryMerger.applyPage(this, input.mode, input.page)
            if (input.mode == PageMode.REPLACE && reloading) {
                gapPossible = false                                            // the page covers the gap (web parity)
                refetchOpenSubagents()                                         // §5.6: before the buffered frames, so theirs are held
                finishReload()
            }
        }
        ConversationInput.BeginReload -> reloading = true
        ConversationInput.ReloadFailed -> if (reloading) { gapPossible = true; finishReload() }
        is ConversationInput.DataChannelEvent -> onVoiceEvent(input.event)
        is ConversationInput.LocalSend -> localSend(input.text)
        ConversationInput.LocalSendAudio -> {
            appendEntry(UserEntry(newEntryId(), "", UserOrigin.AUDIO, UserState.SENT))
            localTurnsPending += 1
        }
        is ConversationInput.LocalInject -> localInject(input.text)
        ConversationInput.LocalInterrupt -> if (isAgent) {
            val keep = queue.filter { it.owner != QueueOwner.LOCAL }
            queue.clear(); queue.addAll(keep)
        }
        ConversationInput.LocalCompact -> {
            compactPending = true
            if (isAgent) status = SessionStatus.COMPACTING else localTurnsPending += 1
        }
        ConversationInput.LocalStop -> expectStopAck = true
        ConversationInput.VoiceLocalEnd -> endVoice()
        ConversationInput.SocketOpened -> { connection = ConnectionState.OPEN; sendStart() }
        ConversationInput.Resync -> if (connection == ConnectionState.OPEN || connection == ConnectionState.SUBSCRIBED) sendStart()
        ConversationInput.SocketClosed -> {
            connection = ConnectionState.OFFLINE
            connectionBanner = ConnectionBanner("disconnected")                // banner only (A-8.2)
            // No replay for the orchestrator and Qwen/Gemini (SEQ-7): live activity may be missing.
            if (inTurn && !ref.seqCapable) gapPossible = true
        }
        is ConversationInput.SdkIdLearned -> if (ref.sdkId == null) {
            ref = ref.copy(sdkId = input.sdkId)
            reloadIfAttachedWithoutHistory()
        }
        is ConversationInput.PoolStatus -> poolStatus(input.status)
        ConversationInput.DismissBanner -> connectionBanner = null
        is ConversationInput.ClearAgentApprovals -> {
            val keep = agentApprovals.filter { it.localId != input.localId }
            agentApprovals.clear(); agentApprovals.addAll(keep)
        }
        is ConversationInput.OpenSubagent -> openSubagent(input.toolUseId)
        is ConversationInput.SubagentPage -> subagentPage(input.toolUseId, input.page)
        is ConversationInput.SubagentPageFailed -> subagentPageFailed(input.toolUseId, input.message)
        is ConversationInput.ReleaseSubagent -> releaseSubagent(input.toolUseId)
        is ConversationInput.SubagentList -> input.rows.forEach { upsertSummary(it) }
    }
}

// ───────────────────────── connection manager (§3.6, §5.2) ─────────────────────────

internal fun Draft.onFrame(f: ServerFrame) {
    if (f is ServerFrame.VoiceAudioOut) return                               // L-3: the audio engine's
    if (reloading) { reloadBuffer.add(f); return }                           // §5.2 / §5.6
    if (awaitingSessionStarted && f !is ServerFrame.SessionStarted) {
        // SEQ-5 exception: errors are applied at once; only a start error also ends the wait (SEQ-8).
        if (f is ServerFrame.Error) { dispatch(f); return }
        preStart.add(f); return                                              // SEQ-5
    }
    if (f is ServerFrame.SessionStarted) { onSessionStarted(f); return }
    dispatch(f)
}

internal fun Draft.dispatch(f: ServerFrame) {
    val seq = f.seq
    val sid = f.streamId
    if (seq != null && !sid.isNullOrEmpty() && f !is ServerFrame.SessionStalled) {   // SEQ-2 exemption
        val cp = checkpoint
        if (cp != null && cp.streamId == sid && seq <= cp.seq) return        // duplicate (SEQ-1)
        checkpoint = Checkpoint(sid, seq)                                    // SEQ-4: a new stream replaces it
    }
    reduceFrame(f)
}

private fun Draft.onSessionStarted(f: ServerFrame.SessionStarted) {
    awaitingSessionStarted = false
    stoppingRetried = false
    userStart = false                                                        // OPEN-2: from now on, reattach
    // T-12
    connection = ConnectionState.SUBSCRIBED
    connectionBanner = null
    if (!f.sessionId.isNullOrEmpty() && f.sessionId != ref.localId) ref = ref.copy(localId = f.sessionId!!)   // ID-1
    if (isOrchestrator && !f.jsonlId.isNullOrEmpty()) ref = ref.copy(sdkId = f.jsonlId)                      // backend O-3
    if (isAgent && ref.live && ref.sdkId == null) effects += ConversationEffect.LookupSdkId                   // ID-2: attached mid first turn
    val window = if (isAgent) f.contextWindow else f.modelInfo?.modelInfo?.contextWindow
    counters = counters.copy(contextWindow = window ?: counters.contextWindow ?: ConversationReducer.DEFAULT_CONTEXT_WINDOW)

    reduceFrame(f)                                                           // voiceActive when f.voice
    val rf = startRequest?.resumeFrom
    val rs = f.resumeState
    val held = preStart.toList()
    preStart.clear()
    if (f.replayOverflow) {
        checkpoint = rs?.let { Checkpoint(it.streamId, it.nextSeq - 1) }
        if (startCanonicalReload()) {
            reloadBuffer.addAll(0, held)                                     // applied after the reload
        } else {
            for (g in held) dispatch(g)
        }
    } else if (rf != null && rs != null && rs.streamId == rf.streamId) {
        for (g in held) if (!g.hasSeq()) dispatch(g)                           // seq-stamped ones WILL be replayed
    } else {
        for (g in held) dispatch(g)
        if (rs != null) {
            val cp = checkpoint
            checkpoint = if (cp != null && cp.streamId == rs.streamId) {
                Checkpoint(rs.streamId, maxOf(cp.seq, rs.nextSeq - 1))
            } else {
                Checkpoint(rs.streamId, rs.nextSeq - 1)
            }
        }
    }
    if (!inTurn && status in RESTART_STATUSES) status = SessionStatus.IDLE
    termination = null
}

private fun ServerFrame.hasSeq() = seq != null && !streamId.isNullOrEmpty()

private val RESTART_STATUSES = setOf(SessionStatus.CONNECTING, SessionStatus.STOPPED, SessionStatus.TERMINATED)

/**
 * A view attached to a session that started elsewhere (`ref.live`) before its sdk id was known had
 * nothing to cold-open (§5.2), and a fresh subscriber gets no replay: its history must come from REST
 * once the id is known (pool/live or the first `turn_complete`, ID-2). A session created here
 * (`live = false`) already holds everything since its first prompt and is left alone.
 */
internal fun Draft.reloadIfAttachedWithoutHistory() {
    if (isAgent && ref.live && !reloading) startCanonicalReload()
}

/**
 * §5.6 / SEQ-6. Returns true when a reload is now pending. Without an sdk id the effect handler asks
 * `pool/live` for it first and answers [ConversationInput.ReloadFailed] (gap, entries kept) only if
 * there is still none — as the web app's `runReload` does. (Flagging the gap and only learning the id
 * left a view opened during a long first turn with just its live tail, 2026-10-17.)
 */
internal fun Draft.startCanonicalReload(): Boolean {
    reloading = true
    effects += ConversationEffect.CanonicalReload
    return true
}

private fun Draft.finishReload() {
    reloading = false
    val buffered = reloadBuffer.toList()
    reloadBuffer.clear()
    for (f in buffered) onFrame(f)                                           // §3.6 rules apply now
}

/** §3.3 `sendStart`: every socket open and every resync; `reattach` unless the user asked for this one (OPEN-2). */
internal fun Draft.sendStart() {
    val cp = checkpoint
    val resumeFrom = if (ref.seqCapable && cp != null && history.loaded) ResumeCursor(cp.streamId, cp.seq) else null   // T-10
    val msg = ClientFrame.Start(localId = ref.localId, resumeSdkId = ref.sdkId, resumeFrom = resumeFrom, reattach = true.takeUnless { userStart })
    startRequest = msg
    awaitingSessionStarted = true
    preStart.clear()
    effects += ConversationEffect.SendStart(msg)
}

/** ST-2: `pool/live` is authoritative for an agent turn state the client does not know. */
private fun Draft.poolStatus(s: LiveStatus) {
    if (!isAgent) return                                                     // the orchestrator row always says idle (G-15)
    when (s) {
        LiveStatus.STREAMING, LiveStatus.TOOL_USE, LiveStatus.THINKING -> {
            if (!inTurn) { inTurn = true; promptSinceTurnEnd = false }
            status = SessionStatus.fromWire(s.wire)!!
        }
        // idle, interrupted (Codex/Gemini/Qwen keep it after a stop), disconnected: no turn runs
        LiveStatus.IDLE, LiveStatus.INTERRUPTED, LiveStatus.DISCONNECTED -> if (inTurn) endTurn("unknown")
    }
}

// ───────────────────────── local actions (§4.3) ─────────────────────────

private fun Draft.localSend(text: String) {
    if (isAgent) {
        if (status.busy || inTurn) { queue.add(QueuedPrompt(text, QueueOwner.LOCAL)); return }   // I-12
        appendEntry(UserEntry(newEntryId(), text, UserOrigin.LOCAL, UserState.SENT))
        status = SessionStatus.PROCESSING                                    // optimistic; status:processing follows
    } else {
        appendEntry(UserEntry(newEntryId(), text, UserOrigin.LOCAL, UserState.SENT))
        localTurnsPending += 1
    }
}

private fun Draft.localInject(text: String) {
    if (voiceActive) {
        appendEntry(UserEntry(newEntryId(), text, UserOrigin.INJECT, UserState.PENDING))
        pendingInjects.add(text)
    } else {
        appendEntry(UserEntry(newEntryId(), text, UserOrigin.INJECT, UserState.SENT))
        localTurnsPending += 1
    }
}
