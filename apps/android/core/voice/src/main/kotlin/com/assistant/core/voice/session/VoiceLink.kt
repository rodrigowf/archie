package com.assistant.core.voice.session

import com.assistant.core.voice.VoiceTuning

/**
 * Orchestrator-link health of a LIVE voice session (decision P-2): "auto-restart, not quietly".
 *
 * ```
 * UP ──link lost (Disconnected willReconnect)──► RECONNECTING(elapsed) ──voice re-confirmed──► UP   (+ Restored)
 *                                                    │  socket back → voice_start re-arm (old path, unchanged)
 *                                                    └── elapsed ≥ LINK_RETRY_BUDGET_MS ──► FAILED   (+ Failed)
 * FAILED ──manual Reconnect / new start──► UP
 * ```
 *
 * "Voice re-confirmed" = the backend answered the re-arm `voice_start` with
 * `session_started{voice: true, voice_initiator: true}` (the server ends voice at once when the
 * owner's socket drops, spec 12 V-8 / G-31, so a bare socket reconnect is not enough).
 *
 * The host (A-08) turns the events into the call-app cues: [VoiceLinkEvent.Lost] starts the
 * "reconnecting" tone + "Reconnecting…" visual, [VoiceLinkEvent.Restored] plays the distinct
 * "reconnected" cue, [VoiceLinkEvent.Failed] the failure cue + manual Reconnect.
 */
sealed interface VoiceLinkState {
    data object Up : VoiceLinkState

    /**
     * The link dropped at [sinceMs] (monotonic); [elapsedMs] is updated every `LINK_TICK_MS`.
     * [attempts] counts link losses in this outage; [socketBack] is true once the socket is back
     * and the re-arm `voice_start` went out (awaiting the backend's confirmation).
     */
    data class Reconnecting(
        val sinceMs: Long,
        val elapsedMs: Long,
        val budgetMs: Long,
        val attempts: Int,
        val socketBack: Boolean,
    ) : VoiceLinkState

    /** The retry budget ran out after [downtimeMs]; voice was ended locally. Manual Reconnect only. */
    data class Failed(val downtimeMs: Long) : VoiceLinkState
}

sealed interface VoiceLinkEvent {
    /** The link was lost during live voice: start the reconnecting cue/visual NOW. */
    data object Lost : VoiceLinkEvent

    /** Voice is back after [downtimeMs]: play the "reconnected" cue. */
    data class Restored(val downtimeMs: Long) : VoiceLinkEvent

    /** Retry budget exhausted after [downtimeMs]: failure cue + manual Reconnect. */
    data class Failed(val downtimeMs: Long) : VoiceLinkEvent
}

/** Pure P-2 state machine; time is passed in (monotonic ms). Not thread-safe: the controller serialises it. */
class VoiceLinkMachine(private val budgetMs: Long = VoiceTuning.LINK_RETRY_BUDGET_MS) {
    var state: VoiceLinkState = VoiceLinkState.Up
        private set

    val isReconnecting: Boolean get() = state is VoiceLinkState.Reconnecting

    fun onLinkLost(nowMs: Long): VoiceLinkEvent? = when (val s = state) {
        VoiceLinkState.Up -> {
            state = VoiceLinkState.Reconnecting(nowMs, 0L, budgetMs, attempts = 1, socketBack = false)
            VoiceLinkEvent.Lost
        }
        is VoiceLinkState.Reconnecting -> {
            state = s.copy(elapsedMs = nowMs - s.sinceMs, attempts = s.attempts + 1, socketBack = false)
            null
        }
        is VoiceLinkState.Failed -> null
    }

    fun onSocketBack(nowMs: Long) {
        val s = state as? VoiceLinkState.Reconnecting ?: return
        state = s.copy(elapsedMs = nowMs - s.sinceMs, socketBack = true)
    }

    fun onVoiceConfirmed(nowMs: Long): VoiceLinkEvent? {
        val s = state as? VoiceLinkState.Reconnecting ?: return null
        state = VoiceLinkState.Up
        return VoiceLinkEvent.Restored(nowMs - s.sinceMs)
    }

    /** Elapsed-time update; returns [VoiceLinkEvent.Failed] when the budget is exhausted. */
    fun tick(nowMs: Long): VoiceLinkEvent? {
        val s = state as? VoiceLinkState.Reconnecting ?: return null
        val elapsed = nowMs - s.sinceMs
        if (elapsed >= budgetMs) {
            state = VoiceLinkState.Failed(elapsed)
            return VoiceLinkEvent.Failed(elapsed)
        }
        state = s.copy(elapsedMs = elapsed)
        return null
    }

    /** Delay until the next [tick] is due, or null when not reconnecting. */
    fun nextTickDelayMs(nowMs: Long): Long? {
        val s = state as? VoiceLinkState.Reconnecting ?: return null
        val toBudget = s.sinceMs + budgetMs - nowMs
        val toNextSecond = VoiceTuning.LINK_TICK_MS - ((nowMs - s.sinceMs) % VoiceTuning.LINK_TICK_MS)
        return minOf(toBudget, toNextSecond).coerceAtLeast(0L)
    }

    /** Voice ended for another reason (stop, error, terminal disconnect) or a new start: back to UP. */
    fun reset() {
        state = VoiceLinkState.Up
    }
}
