package com.assistant.archie.tv.voice

import com.assistant.core.data.VoicePresence
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/*
 * Voice seams of the shell (spec 15 §4): Play/Pause, the hero "Live voice" button and Menu call
 * [LiveVoiceToggle] (`TvAppGraph.liveVoice`); the status cluster renders `TvAppGraph.voice` through
 * [VoiceChip]. `TvVoice.install` replaces both with the real ones.
 */

/** Play/Pause, the hero "Live voice" button, Menu → Live voice. Returns true when it acted. */
fun interface LiveVoiceToggle {
    fun toggle(): Boolean
}

/**
 * The status cluster's voice chip (spec 15 §3.4): Idle · Connecting · Listening · Thinking ·
 * Speaking · Live · On phone. The TV's hold-to-talk states ride on [VoiceSessionState.vadState]
 * ([HOLD] = the mic button is held), so the shell keeps its one state type.
 */
object VoiceChip {
    /** `vadState` marker: the remote's mic button is held (hold-to-talk or a live turn). */
    const val HOLD = "tv-hold"

    fun label(s: VoiceSessionState): String = when {
        s.vadState == HOLD -> "Listening"
        s.remoteVoiceActive && !s.isOwner && (s.phase == SessionPhase.OFF || s.phase == SessionPhase.ERROR) -> "On phone"
        else -> when (s.phase) {
            SessionPhase.OFF, SessionPhase.ERROR -> "Idle"
            SessionPhase.CONNECTING, SessionPhase.SUMMARIZING -> "Connecting"
            SessionPhase.ACTIVE -> if (s.isOwner) "Live" else "Listening"
            SessionPhase.THINKING, SessionPhase.TOOL_USE -> "Thinking"
            SessionPhase.SPEAKING -> "Speaking"
            SessionPhase.ENDING -> "Ending"
        }
    }

    fun active(s: VoiceSessionState): Boolean = s.vadState == HOLD || (s.phase != SessionPhase.OFF && s.phase != SessionPhase.ERROR)

    /**
     * The chip's state from the live session ([session]) and hold-to-talk ([overlay], [liveHolding]):
     * a held mic shows Listening, "Sending… / Transcribing…" shows Thinking, otherwise the session.
     */
    fun compose(session: VoiceSessionState, overlay: TvVoiceOverlay, liveHolding: Boolean): VoiceSessionState = when {
        liveHolding || overlay is TvVoiceOverlay.Holding -> session.copy(vadState = HOLD)
        overlay is TvVoiceOverlay.Working && !session.isOwner -> session.copy(phase = SessionPhase.THINKING)
        else -> session
    }
}

/** [VoicePresence] for the shell: the live session plus hold-to-talk (`VoiceChip.compose`). */
class TvVoicePresence(
    session: StateFlow<VoiceSessionState>?,
    controller: TvVoiceController,
    scope: CoroutineScope,
) : VoicePresence {
    override val state: StateFlow<VoiceSessionState> = combine(
        session ?: flowOf(VoiceSessionState()),
        controller.overlay,
        controller.liveHolding,
    ) { s, o, h -> VoiceChip.compose(s, o, h) }.stateIn(scope, SharingStarted.Eagerly, VoiceSessionState())
}
