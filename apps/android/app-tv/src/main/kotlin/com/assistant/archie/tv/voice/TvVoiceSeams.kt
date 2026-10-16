package com.assistant.archie.tv.voice

import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceSessionState

/*
 * Voice seams for T4 (spec 15 §4). T0 ships no voice: Play/Pause and the hero "Live voice" button
 * call [LiveVoiceToggle], whose default only says so. T4 replaces `TvAppGraph.liveVoice` and
 * `TvAppGraph.voice` (and adds RemoteMicSource / TvVoiceRouter / the overlay in this package).
 */

/** Play/Pause, the hero "Live voice" button, Menu → Live voice. Returns true when it acted. */
fun interface LiveVoiceToggle {
    fun toggle(): Boolean
}

/** The status cluster's voice chip (spec 15 §3.4): Idle · Connecting · Listening · Thinking · Speaking · On phone. */
object VoiceChip {
    fun label(s: VoiceSessionState): String = when {
        s.remoteVoiceActive && !s.isOwner -> "On phone"
        else -> when (s.phase) {
            SessionPhase.OFF, SessionPhase.ERROR -> "Idle"
            SessionPhase.CONNECTING, SessionPhase.SUMMARIZING -> "Connecting"
            SessionPhase.ACTIVE -> "Listening"
            SessionPhase.THINKING, SessionPhase.TOOL_USE -> "Thinking"
            SessionPhase.SPEAKING -> "Speaking"
            SessionPhase.ENDING -> "Ending"
        }
    }

    fun active(s: VoiceSessionState): Boolean = s.phase != SessionPhase.OFF && s.phase != SessionPhase.ERROR
}
