package com.assistant.archie.system

import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceLevels
import com.assistant.core.voice.ports.VoiceSessionState
import com.assistant.core.voice.session.VoiceLinkState
import com.assistant.core.voicehost.VoiceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [HostChatVoice.dockLevel]: which side of the call drives the dock orb. */
class DockLevelTest {

    private val levels = VoiceLevels(mic = 0.04f, speaker = 0.25f)

    private fun ui(phase: SessionPhase, micMuted: Boolean = false, speakerMuted: Boolean = false, link: VoiceLinkState = VoiceLinkState.Up) =
        VoiceUiState(session = VoiceSessionState(phase = phase, isOwner = true, isMuted = micMuted), speakerMuted = speakerMuted, link = link)

    private fun near(expected: Float, actual: Float?) = assertEquals(expected, actual!!, 1e-4f)

    @Test
    fun listeningShowsTheMicAndSpeakingTheSpeaker() {
        near(VoiceLevels.visual(0.04f), HostChatVoice.dockLevel(levels, ui(SessionPhase.ACTIVE)))
        near(VoiceLevels.visual(0.25f), HostChatVoice.dockLevel(levels, ui(SessionPhase.SPEAKING)))
    }

    @Test
    fun aMutedSideReadsSilent() {
        near(0f, HostChatVoice.dockLevel(levels, ui(SessionPhase.ACTIVE, micMuted = true)))
        near(0f, HostChatVoice.dockLevel(levels, ui(SessionPhase.SPEAKING, speakerMuted = true)))
        // The other side is unaffected.
        near(VoiceLevels.visual(0.25f), HostChatVoice.dockLevel(levels, ui(SessionPhase.SPEAKING, micMuted = true)))
    }

    @Test
    fun noLiveLevelKeepsTheOrbsOwnPulse() {
        assertNull(HostChatVoice.dockLevel(null, ui(SessionPhase.ACTIVE)))
        for (p in listOf(SessionPhase.THINKING, SessionPhase.TOOL_USE, SessionPhase.CONNECTING, SessionPhase.ENDING, SessionPhase.OFF)) {
            assertNull(p.name, HostChatVoice.dockLevel(levels, ui(p)))
        }
        val down = VoiceLinkState.Reconnecting(sinceMs = 0, elapsedMs = 1_000, budgetMs = 30_000, attempts = 1, socketBack = false)
        assertNull(HostChatVoice.dockLevel(levels, ui(SessionPhase.ACTIVE, link = down)))
    }
}
