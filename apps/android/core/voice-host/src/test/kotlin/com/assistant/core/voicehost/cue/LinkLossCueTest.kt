package com.assistant.core.voicehost.cue

import com.assistant.core.protocol.ClientFrame
import com.assistant.core.protocol.ServerFrame
import com.assistant.core.voice.VoiceTuning
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.session.VoiceLinkState
import com.assistant.core.voicehost.HostRig
import com.assistant.core.voicehost.HostTuning
import com.assistant.core.voicehost.VoiceUiEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision P-2 (plan/20): "auto-restart, not quietly". End to end through the runtime: the real
 * orchestrator channel over a scripted socket, the real voice-session controller (A-06), the host's
 * cue scheduler, and the aggregated `VoiceUiState`.
 *  - link lost → the soft "reconnecting" cue at once, repeating every LINK_CUE_REPEAT_MS, and
 *    `Reconnecting(elapsed)` in the visible state;
 *  - restored (backend re-confirms voice) → the distinct "reconnected" cue, pattern stops;
 *  - 30 s without recovery → the failure cue + `Failed`, and a manual Reconnect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkLossCueTest {

    private fun HostRig.reconnectingCues() = cues.played.filter { it.kind == CueKind.LINK_RECONNECTING }

    @Test
    fun lostPlaysTheReconnectingCueAtOnceThenRepeatsAndShowsElapsed() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        r.startActiveVoice()
        val lostAt = r.clock.nowMs()
        r.socket.drop(willReconnect = true)
        r.settle()

        assertEquals("cue at the moment of the loss", listOf(lostAt), r.reconnectingCues().map { it.atMs })
        val s0 = r.state.link as VoiceLinkState.Reconnecting
        assertEquals(0L, s0.elapsedMs)

        r.advance(5_000)
        assertEquals(
            listOf(lostAt, lostAt + 2_000, lostAt + 4_000),
            r.reconnectingCues().map { it.atMs },
        )
        assertEquals(5_000L, (r.state.link as VoiceLinkState.Reconnecting).elapsedMs)
        // The call itself is kept during the outage (RS-10): no teardown, still ACTIVE.
        assertEquals(SessionPhase.ACTIVE, r.phase)
        assertEquals(0, r.transports.last.disconnects)
    }

    @Test
    fun restoredPlaysTheReconnectedCueAndStopsThePattern() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        r.startActiveVoice()
        r.socket.drop(willReconnect = true)
        r.settle()
        r.advance(3_000)

        // Socket back: the channel re-adopts and the session re-arms voice with voice_start.
        r.socket.open()
        r.settle()
        assertTrue("re-arm voice_start sent", r.socket.sent.count { it is ClientFrame.VoiceStart } >= 2)
        assertTrue((r.state.link as VoiceLinkState.Reconnecting).socketBack)
        // Backend re-confirms voice for this device → Restored.
        r.socket.frame(ServerFrame.SessionStarted(sessionId = HostRig.ORCH.localId, voice = true, voiceInitiator = true))
        r.settle()

        assertEquals(VoiceLinkState.Up, r.state.link)
        assertEquals(1, r.cues.count(CueKind.LINK_RESTORED))
        val beforeQuiet = r.reconnectingCues().size
        assertEquals("cues at 0 and +2 s only", 2, beforeQuiet)
        r.advance(10_000)
        assertEquals("pattern stopped", beforeQuiet, r.reconnectingCues().size)
        assertEquals(0, r.cues.count(CueKind.LINK_FAILED))
        assertEquals(SessionPhase.ACTIVE, r.phase)
        // The restored cue comes after every reconnecting cue.
        val kinds = r.cues.kinds().filter { it == CueKind.LINK_RECONNECTING || it == CueKind.LINK_RESTORED }
        assertEquals(CueKind.LINK_RESTORED, kinds.last())
    }

    @Test
    fun budgetExhaustedPlaysTheFailureCueAndShowsFailedWithManualReconnect() = runTest {
        val r = HostRig(this)
        val events = mutableListOf<VoiceUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.runtime.events.collect { events += it } }
        r.connectAndAdopt()
        r.startActiveVoice()
        val lostAt = r.clock.nowMs()
        r.socket.drop(willReconnect = true)
        r.settle()

        r.advance(VoiceTuning.LINK_RETRY_BUDGET_MS - 1)
        assertEquals(0, r.cues.count(CueKind.LINK_FAILED))
        assertTrue(r.state.link is VoiceLinkState.Reconnecting)
        r.advance(1)

        assertEquals(1, r.cues.count(CueKind.LINK_FAILED))
        assertEquals(VoiceLinkState.Failed(VoiceTuning.LINK_RETRY_BUDGET_MS), r.state.link)
        assertEquals(SessionPhase.ERROR, r.state.session.phase)
        // 0, 2, …, 28 s: 15 reconnecting cues, none after the failure.
        val expected = (0 until (VoiceTuning.LINK_RETRY_BUDGET_MS / HostTuning.LINK_CUE_REPEAT_MS)).map { lostAt + it * HostTuning.LINK_CUE_REPEAT_MS }
        assertEquals(expected, r.reconnectingCues().map { it.atMs })
        r.advance(10_000)
        assertEquals(expected.size, r.reconnectingCues().size)
        // Cues are mirrored to the UI event stream.
        assertTrue(events.contains(VoiceUiEvent.Cue(CueKind.LINK_FAILED)))

        // Manual Reconnect clears the failure and starts voice again.
        r.socket.open()
        r.settle()
        r.runtime.reconnectVoice()
        r.advance(1_000)
        assertEquals(VoiceLinkState.Up, r.state.link)
        assertEquals(SessionPhase.ACTIVE, r.phase)
    }

    @Test
    fun endInTheFailureStateDismissesTheBannerAndSendsNothing() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        r.startActiveVoice()
        r.socket.drop(willReconnect = true)
        r.settle()
        r.advance(VoiceTuning.LINK_RETRY_BUDGET_MS)
        assertTrue(r.state.link is VoiceLinkState.Failed)
        r.socket.open()
        r.settle()
        val stopsBefore = r.socket.sent.count { it == ClientFrame.VoiceStop }

        r.runtime.stopVoice()
        r.settle()

        assertEquals(VoiceLinkState.Up, r.state.link)
        assertEquals(stopsBefore, r.socket.sent.count { it == ClientFrame.VoiceStop })
    }

    @Test
    fun aUserStopDuringTheOutageStopsThePatternWithoutAFailureCue() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        r.startActiveVoice()
        r.socket.drop(willReconnect = true)
        r.settle()
        r.advance(2_500)
        r.runtime.stopVoice()
        r.settle()
        val n = r.reconnectingCues().size
        r.advance(40_000)
        assertEquals(n, r.reconnectingCues().size)
        assertEquals(0, r.cues.count(CueKind.LINK_FAILED))
        assertEquals(0, r.cues.count(CueKind.LINK_RESTORED))
    }

    @Test
    fun aDropWithoutLiveVoiceIsSilent() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        r.socket.drop(willReconnect = true)
        r.advance(40_000)
        assertTrue(r.cues.played.isEmpty())
        assertEquals(VoiceLinkState.Up, r.state.link)
    }
}
