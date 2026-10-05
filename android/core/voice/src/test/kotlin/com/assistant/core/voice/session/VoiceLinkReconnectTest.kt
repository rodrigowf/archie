package com.assistant.core.voice.session

import com.assistant.core.testing.FakeAudioSession
import com.assistant.core.testing.FakeClock
import com.assistant.core.testing.FakeOrchestratorContext
import com.assistant.core.testing.FakeVoiceApi
import com.assistant.core.testing.FakeVoiceTransportFactory
import com.assistant.core.testing.FakeVoiceWire
import com.assistant.core.testing.FakeWakeHandoff
import com.assistant.core.testing.RecordingCues
import com.assistant.core.testing.RecordingLog
import com.assistant.core.testing.RecordingTranscriptSink
import com.assistant.core.voice.VoiceTuning
import com.assistant.core.voice.ports.ConnectionSignal
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceInbound
import com.assistant.core.voice.ports.VoiceSessionDeps
import java.util.Collections
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision P-2 (plan/20): a network drop during live voice auto-restarts "not quietly":
 * lost → reconnecting(elapsed) → reconnected | failed (retry budget) + manual Reconnect.
 * The host plays the cues from [VoiceLinkEvent]s; these tests pin the states and events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLinkReconnectTest {

    private class Rig(private val ts: TestScope) {
        val clock = FakeClock.boundTo(ts.testScheduler)
        val wire = FakeVoiceWire(clock)
        val wake = FakeWakeHandoff(clock)
        val transports = FakeVoiceTransportFactory(clock)
        val controller = DefaultVoiceSessionController(
            VoiceSessionDeps(
                ts.backgroundScope, clock, RecordingLog(), FakeVoiceApi(clock), wire, FakeOrchestratorContext(), wake,
                transports, FakeAudioSession(clock), RecordingTranscriptSink(), RecordingCues(),
            ),
        )
        val events: MutableList<VoiceLinkEvent> = Collections.synchronizedList(mutableListOf())

        init {
            ts.backgroundScope.launch(UnconfinedTestDispatcher(ts.testScheduler)) { controller.linkEvents.collect { events += it } }
        }

        val link get() = controller.link.value
        val phase get() = controller.state.value.phase
        fun advance(ms: Long) { ts.testScheduler.advanceTimeBy(ms); ts.runCurrent() }
        fun connection(s: ConnectionSignal) { controller.onConnection(s); ts.runCurrent() }
        fun inbound(f: VoiceInbound) { controller.onInbound(f); ts.runCurrent() }

        fun startActive() {
            controller.startVoice()
            advance(1_000)
            check(phase == SessionPhase.ACTIVE) { "not active: $phase" }
        }

        fun reconfirmVoice() = inbound(VoiceInbound.SessionStarted(voice = true, voiceInitiator = true, voiceSessionUpdate = null))
    }

    @Test
    fun linkLossDuringVoiceIsSignalledAtOnceAndElapsedTicksEverySecond() = runTest {
        val r = Rig(this)
        r.startActive()
        r.connection(ConnectionSignal.Disconnected(willReconnect = true))
        assertEquals(listOf<VoiceLinkEvent>(VoiceLinkEvent.Lost), r.events)
        val s0 = r.link as VoiceLinkState.Reconnecting
        assertEquals(0L, s0.elapsedMs)
        assertEquals(VoiceTuning.LINK_RETRY_BUDGET_MS, s0.budgetMs)
        assertFalse(s0.socketBack)
        r.advance(3_000)
        assertEquals(3_000L, (r.link as VoiceLinkState.Reconnecting).elapsedMs)
        // The session itself is kept (RS-10): no teardown, no wake re-arm, UI phase unchanged.
        assertEquals(SessionPhase.ACTIVE, r.phase)
        assertEquals(0, r.transports.last.disconnects)
        assertTrue(r.wake.resumes.isEmpty())
        // A second drop in the same outage is not a second "lost" cue.
        r.connection(ConnectionSignal.Disconnected(willReconnect = true))
        assertEquals(1, r.events.size)
        assertEquals(2, (r.link as VoiceLinkState.Reconnecting).attempts)
    }

    @Test
    fun reconnectedOnlyOnceTheBackendReconfirmsVoice() = runTest {
        val r = Rig(this)
        r.startActive()
        r.connection(ConnectionSignal.Disconnected(willReconnect = true))
        r.advance(4_500)
        r.connection(ConnectionSignal.Reconnected("local-1", "sdk-1"))
        assertEquals("re-armed via voice_start (unchanged path)", 2, r.wire.voiceStarts.size)
        assertTrue((r.link as VoiceLinkState.Reconnecting).socketBack)
        assertEquals(listOf<VoiceLinkEvent>(VoiceLinkEvent.Lost), r.events)
        r.advance(500)
        r.reconfirmVoice()
        assertEquals(VoiceLinkState.Up, r.link)
        assertEquals(listOf(VoiceLinkEvent.Lost, VoiceLinkEvent.Restored(5_000L)), r.events)
        // No more ticks, and no failure later.
        r.advance(VoiceTuning.LINK_RETRY_BUDGET_MS * 2)
        assertEquals(VoiceLinkState.Up, r.link)
        assertEquals(SessionPhase.ACTIVE, r.phase)
    }

    @Test
    fun exhaustedRetryBudgetFailsEndsVoiceLocallyAndWaitsForManualReconnect() = runTest {
        val r = Rig(this)
        r.startActive()
        r.connection(ConnectionSignal.Disconnected(willReconnect = true))
        r.advance(VoiceTuning.LINK_RETRY_BUDGET_MS - 1)
        assertTrue(r.link is VoiceLinkState.Reconnecting)
        r.advance(1)
        assertEquals(VoiceLinkState.Failed(VoiceTuning.LINK_RETRY_BUDGET_MS), r.link)
        assertEquals(VoiceLinkEvent.Failed(VoiceTuning.LINK_RETRY_BUDGET_MS), r.events.last())
        assertEquals(SessionPhase.ERROR, r.phase)
        assertEquals("Voice connection lost", r.controller.state.value.errorMessage)
        assertFalse(r.controller.state.value.isOwner)
        r.advance(100)
        assertEquals(1, r.transports.last.disconnects)
        r.advance(VoiceTuning.MIC_RELEASE_DELAY_MS)
        assertEquals("wake word re-armed after the mic release delay", 1, r.wake.resumes.size)
        // The socket coming back later does not resurrect voice: plain resume start, failure kept.
        r.connection(ConnectionSignal.Reconnected("local-1", "sdk-1"))
        assertEquals(1, r.wire.voiceStarts.size)
        assertEquals(1, r.wire.resumeStarts.size)
        assertTrue(r.link is VoiceLinkState.Failed)
        // Manual Reconnect: clears the failure and starts a fresh session.
        r.controller.reconnectVoice()
        assertEquals(VoiceLinkState.Up, r.link)
        r.advance(1_000)
        assertEquals(SessionPhase.ACTIVE, r.phase)
        assertEquals(2, r.transports.created.size)
        assertEquals(2, r.wire.voiceStarts.size)
    }

    @Test
    fun aDropWithoutLiveVoiceIsNotALinkLoss() = runTest {
        val r = Rig(this)
        r.connection(ConnectionSignal.Disconnected(willReconnect = true))
        r.advance(VoiceTuning.LINK_RETRY_BUDGET_MS * 2)
        assertEquals(VoiceLinkState.Up, r.link)
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun stoppingOrATerminalDropDuringTheOutageClearsTheLinkState() = runTest {
        val r = Rig(this)
        r.startActive()
        r.connection(ConnectionSignal.Disconnected(willReconnect = true))
        r.controller.stopVoice()
        assertEquals(VoiceLinkState.Up, r.link)
        r.advance(VoiceTuning.LINK_RETRY_BUDGET_MS * 2)
        assertEquals(listOf<VoiceLinkEvent>(VoiceLinkEvent.Lost), r.events)
        assertEquals(SessionPhase.OFF, r.phase)

        val r2 = Rig(this)
        r2.startActive()
        r2.connection(ConnectionSignal.Disconnected(willReconnect = true))
        r2.connection(ConnectionSignal.Disconnected(willReconnect = false))
        assertEquals(VoiceLinkState.Up, r2.link)
        assertEquals(SessionPhase.OFF, r2.phase)
        r.controller.release()
        r2.controller.release()
    }

    @Test
    fun machineTicksOnWholeSecondsAndStopsAtTheBudget() {
        val m = VoiceLinkMachine(budgetMs = 2_500)
        assertEquals(null, m.nextTickDelayMs(0))
        assertEquals(VoiceLinkEvent.Lost, m.onLinkLost(100))
        assertEquals(1_000L, m.nextTickDelayMs(100))
        assertEquals(null, m.tick(1_100))
        assertEquals(400L, m.nextTickDelayMs(1_700))
        assertEquals(500L, m.nextTickDelayMs(2_100))
        assertEquals(VoiceLinkEvent.Failed(2_500), m.tick(2_600))
        assertEquals(null, m.onLinkLost(3_000))
        assertEquals(null, m.onVoiceConfirmed(3_000))
        m.reset()
        assertEquals(VoiceLinkState.Up, m.state)
    }
}
