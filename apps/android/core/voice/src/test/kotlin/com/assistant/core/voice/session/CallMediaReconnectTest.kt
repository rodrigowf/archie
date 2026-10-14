package com.assistant.core.voice.session

import com.assistant.core.testing.FakeAudioSession
import com.assistant.core.testing.FakeClock
import com.assistant.core.testing.FakeOrchestratorContext
import com.assistant.core.testing.FakeVoiceApi
import com.assistant.core.testing.FakeVoiceTransport
import com.assistant.core.testing.FakeVoiceTransportFactory
import com.assistant.core.testing.FakeVoiceWire
import com.assistant.core.testing.FakeWakeHandoff
import com.assistant.core.testing.RecordingCues
import com.assistant.core.testing.RecordingLog
import com.assistant.core.testing.RecordingTranscriptSink
import com.assistant.core.testing.obj
import com.assistant.core.voice.VoiceTuning
import com.assistant.core.voice.ports.ConnectionSignal
import com.assistant.core.voice.ports.ProviderPhase
import com.assistant.core.voice.ports.ProviderSignal
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceInbound
import com.assistant.core.voice.ports.VoiceSessionDeps
import com.assistant.core.voice.ports.VoiceSessionEvent
import java.util.Collections
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call's own media path drops while the orchestrator socket stays up (2026-10-13: a Wi-Fi blip
 * killed the OpenAI WebRTC call and voice ended with only a toast). The drop is signalled at once
 * (P-2 cues + the "Reconnecting…" banner) and the call is replaced on the same voice session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallMediaReconnectTest {

    private class Rig(private val ts: TestScope) {
        val clock = FakeClock.boundTo(ts.testScheduler)
        val wire = FakeVoiceWire(clock)
        val wake = FakeWakeHandoff(clock)
        val api = FakeVoiceApi(clock)
        val transports = FakeVoiceTransportFactory(clock)
        val transcripts = RecordingTranscriptSink()
        val controller = DefaultVoiceSessionController(
            VoiceSessionDeps(
                ts.backgroundScope, clock, RecordingLog(), api, wire, FakeOrchestratorContext(), wake,
                transports, FakeAudioSession(clock), transcripts, RecordingCues(),
            ),
        )
        val events: MutableList<VoiceLinkEvent> = Collections.synchronizedList(mutableListOf())
        val toasts: MutableList<String> = Collections.synchronizedList(mutableListOf())

        init {
            ts.backgroundScope.launch(UnconfinedTestDispatcher(ts.testScheduler)) { controller.linkEvents.collect { events += it } }
            ts.backgroundScope.launch(UnconfinedTestDispatcher(ts.testScheduler)) {
                controller.events.collect { if (it is VoiceSessionEvent.Toast) toasts += it.message }
            }
        }

        val link get() = controller.link.value
        val state get() = controller.state.value
        val first: FakeVoiceTransport get() = transports.created.first()
        fun advance(ms: Long) { ts.testScheduler.advanceTimeBy(ms); ts.runCurrent() }
        fun inbound(f: VoiceInbound) { controller.onInbound(f); ts.runCurrent() }
        fun emit(t: FakeVoiceTransport, s: ProviderSignal) { t.emit(s); ts.runCurrent() }

        fun startActive() {
            controller.startVoice()
            advance(1_000)
            check(state.phase == SessionPhase.ACTIVE) { "not active: ${state.phase}" }
        }

        /** The backend's answer to a `voice_start` (re-arm): a fresh session.update. */
        fun answerVoiceStart(update: String = """{"type":"session.update","session":{"instructions":"fresh"}}""") =
            inbound(VoiceInbound.SessionStarted(voice = true, voiceInitiator = true, voiceSessionUpdate = obj(update)))

        /** Every replacement attempt's call fails to come up (no internet yet). */
        fun callsFail() {
            transports.configure = { t ->
                t.onConnect = {
                    setPhase(ProviderPhase.CONNECTING)
                    setPhase(ProviderPhase.ERROR, "SDP exchange failed")
                    emit(ProviderSignal.Error("OpenAI SDP exchange failed"))
                }
            }
        }
    }

    @Test
    fun iceDropIsSignalledAtOnceAndARecoveryOnItsOwnRestoresWithoutANewCall() = runTest {
        val r = Rig(this)
        r.startActive()
        r.emit(r.first, ProviderSignal.LinkDown)
        assertEquals(listOf<VoiceLinkEvent>(VoiceLinkEvent.Lost), r.events)
        assertTrue(r.link is VoiceLinkState.Reconnecting)
        assertEquals("Reconnecting…", r.state.reconnectBanner)

        r.advance(2_000)
        r.emit(r.first, ProviderSignal.LinkUp)
        assertEquals(listOf(VoiceLinkEvent.Lost, VoiceLinkEvent.Restored(2_000L)), r.events)
        assertEquals(VoiceLinkState.Up, r.link)
        assertNull(r.state.reconnectBanner)
        assertEquals(1, r.transports.created.size)
        r.advance(VoiceTuning.MEDIA_DISCONNECT_GRACE_MS)
        assertEquals("no replacement after a recovery", 1, r.transports.created.size)
    }

    @Test
    fun iceFailureReplacesTheCallOnTheSameVoiceSession() = runTest {
        val r = Rig(this)
        r.startActive()
        val startsBefore = r.wire.voiceStarts.size
        r.emit(r.first, ProviderSignal.LinkDown)
        r.advance(1_000)
        r.emit(r.first, ProviderSignal.LinkFailed("Connection failed"))
        r.answerVoiceStart()
        r.advance(500)

        assertEquals(1, r.first.disconnects)
        assertEquals(2, r.transports.created.size)
        val second = r.transports.created[1]
        assertTrue("the new call is connected", second.connectedAtMs != null)
        val rearm = r.wire.voiceStarts.drop(startsBefore).single().request
        assertTrue("the re-arm only reattaches", rearm.reattach)
        assertTrue("the fresh session.update reaches the new call", second.commands.any { "fresh" in it.toString() })

        assertEquals(SessionPhase.ACTIVE, r.state.phase)
        assertEquals(VoiceLinkState.Up, r.link)
        assertNull(r.state.reconnectBanner)
        assertTrue(r.events.toString(), r.events.last() is VoiceLinkEvent.Restored)
        assertEquals("one outage, one Lost", 1, r.events.count { it == VoiceLinkEvent.Lost })
        // Same session: never ended, the wake word stays paused, no error surfaced.
        assertTrue(r.wire.voiceStops.isEmpty())
        assertTrue(r.wake.resumes.isEmpty())
        assertTrue(r.toasts.toString(), r.toasts.none { it.startsWith("Voice error") })
        assertTrue(r.transcripts.entries.none { it.startsWith("system:Voice error") })
    }

    @Test
    fun iceStillDisconnectedAfterTheGraceReplacesTheCallWithoutWaitingForFailed() = runTest {
        val r = Rig(this)
        r.startActive()
        r.emit(r.first, ProviderSignal.LinkDown)
        r.advance(VoiceTuning.MEDIA_DISCONNECT_GRACE_MS - 100)
        assertEquals(1, r.transports.created.size)
        r.advance(100)
        r.advance(200)
        assertEquals(1, r.first.disconnects)
        assertEquals(2, r.transports.created.size)
        assertEquals(VoiceLinkState.Up, r.link)
    }

    @Test
    fun replacementsThatNeverComeUpEndInTheFailureStateAtTheBudget() = runTest {
        val r = Rig(this)
        r.startActive()
        r.callsFail()
        r.emit(r.first, ProviderSignal.LinkFailed("Connection failed"))
        r.advance(10_000)
        assertTrue("retrying", r.transports.created.size >= 3)
        assertTrue(r.link is VoiceLinkState.Reconnecting)
        assertEquals("a failed attempt is not the session's state", SessionPhase.CONNECTING, r.state.phase)
        assertTrue(r.wake.resumes.isEmpty())

        r.advance(VoiceTuning.LINK_RETRY_BUDGET_MS)
        assertTrue(r.link is VoiceLinkState.Failed)
        assertTrue(r.events.last() is VoiceLinkEvent.Failed)
        assertEquals(SessionPhase.ERROR, r.state.phase)
        val attempts = r.transports.created.size
        r.advance(VoiceTuning.MEDIA_RETRY_DELAY_MS * 5)
        assertEquals("no attempts after the failure", attempts, r.transports.created.size)
        assertEquals("the wake word re-arms", 1, r.wake.resumes.size)
    }

    @Test
    fun theUsersMuteSurvivesTheReplacement() = runTest {
        val r = Rig(this)
        r.startActive()
        r.controller.toggleMute()
        r.emit(r.first, ProviderSignal.LinkFailed("Connection failed"))
        r.advance(500)
        assertTrue(r.transports.created[1].muted)
        assertTrue(r.state.isMuted)
    }

    @Test
    fun theReArmAnswerDoesNotEndTheOutageBeforeTheNewCallIsUp() = runTest {
        val r = Rig(this)
        r.startActive()
        r.transports.configure = { t -> t.onConnect = { setPhase(ProviderPhase.CONNECTING) } } // stays connecting
        r.emit(r.first, ProviderSignal.LinkFailed("Connection failed"))
        r.answerVoiceStart()
        r.advance(300)
        assertTrue(r.link is VoiceLinkState.Reconnecting)
        assertEquals("Reconnecting…", r.state.reconnectBanner)
        assertFalse(r.events.any { it is VoiceLinkEvent.Restored })
    }

    @Test
    fun stoppingDuringAReplacementStopsIt() = runTest {
        val r = Rig(this)
        r.startActive()
        r.callsFail()
        r.emit(r.first, ProviderSignal.LinkFailed("Connection failed"))
        r.advance(300)
        r.controller.stopVoice()
        r.advance(10)
        val attempts = r.transports.created.size
        r.inbound(VoiceInbound.Ended(reason = null))
        r.advance(VoiceTuning.MEDIA_RETRY_DELAY_MS * 5)
        assertEquals(attempts, r.transports.created.size)
        assertEquals(SessionPhase.OFF, r.state.phase)
        assertEquals(VoiceLinkState.Up, r.link)
    }

    @Test
    fun theOrchestratorSocketDropAlsoRaisesTheReconnectingBanner() = runTest {
        val r = Rig(this)
        r.startActive()
        r.controller.onConnection(ConnectionSignal.Disconnected(willReconnect = true))
        r.advance(10)
        assertEquals("Reconnecting…", r.state.reconnectBanner)
        r.inbound(VoiceInbound.SessionStarted(voice = true, voiceInitiator = true, voiceSessionUpdate = null))
        assertNull(r.state.reconnectBanner)
        assertEquals(VoiceLinkState.Up, r.link)
    }
}
