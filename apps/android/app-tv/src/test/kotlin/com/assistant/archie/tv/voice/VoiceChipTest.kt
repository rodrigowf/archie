package com.assistant.archie.tv.voice

import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status chip (spec 15 §3.4): Idle · Listening · Thinking · Speaking · Live · On phone. */
class VoiceChipTest {
    private fun label(s: VoiceSessionState, o: TvVoiceOverlay = TvVoiceOverlay.Hidden, holding: Boolean = false) =
        VoiceChip.label(VoiceChip.compose(s, o, holding))

    private val holding = TvVoiceOverlay.Holding(VoiceDestination.VoiceMessage, "To Archie", 0)

    @Test
    fun labels() {
        assertEquals("Idle", label(VoiceSessionState()))
        assertEquals("Listening", label(VoiceSessionState(), holding))
        assertEquals("Thinking", label(VoiceSessionState(), TvVoiceOverlay.Working("Sending…")))
        assertEquals("Live", label(VoiceSessionState(SessionPhase.ACTIVE, isOwner = true)))
        assertEquals("Listening", label(VoiceSessionState(SessionPhase.ACTIVE, isOwner = true), holding = true))
        assertEquals("Thinking", label(VoiceSessionState(SessionPhase.THINKING, isOwner = true)))
        assertEquals("Speaking", label(VoiceSessionState(SessionPhase.SPEAKING, isOwner = true)))
        assertEquals("On phone", label(VoiceSessionState(remoteVoiceActive = true)))
        assertEquals("a held mic wins over another device's voice", "Listening", label(VoiceSessionState(remoteVoiceActive = true), holding))
    }

    @Test
    fun activeHighlight() {
        assertFalse(VoiceChip.active(VoiceChip.compose(VoiceSessionState(), TvVoiceOverlay.Hidden, false)))
        assertTrue(VoiceChip.active(VoiceChip.compose(VoiceSessionState(), holding, false)))
        assertTrue(VoiceChip.active(VoiceSessionState(SessionPhase.SPEAKING, isOwner = true)))
    }
}
