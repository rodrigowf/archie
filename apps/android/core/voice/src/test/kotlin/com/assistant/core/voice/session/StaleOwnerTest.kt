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
import com.assistant.core.voice.ports.ProviderSignal
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceInbound
import com.assistant.core.voice.ports.VoiceSessionDeps
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-13, POCO X7 Pro: the backend's late answer to a re-arm re-reported the phone as voice
 * owner after its local end. Minutes later another device's Qwen call ended, and the phone, still
 * "owner" without a call, took that `voice_ending` and sat in "Ending…" forever (the ENDING exit is
 * a finalize, which was a no-op on an already-finalized session).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StaleOwnerTest {

    private class Rig(private val ts: TestScope) {
        val clock = FakeClock.boundTo(ts.testScheduler)
        val wire = FakeVoiceWire(clock)
        val transports = FakeVoiceTransportFactory(clock)
        val controller = DefaultVoiceSessionController(
            VoiceSessionDeps(
                ts.backgroundScope, clock, RecordingLog(), FakeVoiceApi(clock), wire, FakeOrchestratorContext(),
                FakeWakeHandoff(clock), transports, FakeAudioSession(clock), RecordingTranscriptSink(), RecordingCues(),
            ),
        )
        val state get() = controller.state.value
        fun advance(ms: Long) { ts.testScheduler.advanceTimeBy(ms); ts.runCurrent() }
        fun inbound(f: VoiceInbound) { controller.onInbound(f); ts.runCurrent() }

        /** A live call that ended locally, then the server's late answer naming this device owner. */
        fun becomeStaleOwner() {
            controller.startVoice()
            advance(1_000)
            check(state.phase == SessionPhase.ACTIVE)
            transports.last.emit(ProviderSignal.Error("boom"))
            advance(VoiceTuning.MIC_RELEASE_DELAY_MS + 100)
            inbound(VoiceInbound.SessionStarted(voice = true, voiceInitiator = true, voiceSessionUpdate = null))
            inbound(VoiceInbound.OwnerActive(active = true, ownerLocalId = "local-1")) // its echo
            check(state.isOwner && state.phase != SessionPhase.ACTIVE)
        }
    }

    @Test
    fun anotherDevicesVoiceDropsStaleOwnershipAndItsEndDoesNotShowEnding() = runTest {
        val r = Rig(this)
        r.becomeStaleOwner()
        r.inbound(VoiceInbound.OwnerActive(active = true, ownerLocalId = "local-1"))
        assertFalse(r.state.isOwner)
        assertTrue("shows the other device's call", r.state.remoteVoiceActive)

        r.inbound(VoiceInbound.Ending(reason = null))
        assertTrue(r.state.phase != SessionPhase.ENDING)
        r.inbound(VoiceInbound.Ended(reason = null))
        r.advance(VoiceTuning.ENDING_ACK_TIMEOUT_MS)
        assertTrue(r.state.phase != SessionPhase.ENDING)
    }

    @Test
    fun aStaleOwnerNeverEntersEndingEvenWithoutTheOwnerFrame() = runTest {
        val r = Rig(this)
        r.becomeStaleOwner()
        r.inbound(VoiceInbound.Ending(reason = null))
        r.advance(VoiceTuning.ENDING_ACK_TIMEOUT_MS + 100)
        assertTrue(r.state.phase != SessionPhase.ENDING)
        r.inbound(VoiceInbound.Ended(reason = null))
        assertFalse("the server's end clears the stale ownership", r.state.isOwner)
    }

    @Test
    fun theStaleOwnersStopStillClearsTheServer() = runTest {
        val r = Rig(this)
        r.becomeStaleOwner()
        val stops = r.wire.voiceStops.size
        r.controller.stopVoice()
        r.advance(10)
        assertEquals(stops + 1, r.wire.voiceStops.size)
        assertTrue(r.state.phase != SessionPhase.ENDING)
    }
}
