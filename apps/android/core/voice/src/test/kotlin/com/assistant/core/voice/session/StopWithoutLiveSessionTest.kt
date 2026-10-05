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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OI-4: `stopVoice()` with no live session (never started, or already finalized) must be a no-op.
 * The old app only reached `stopVoiceSession()` from an active voice button (`VoiceButton`:
 * `if (isActive) onStop() else onStart()`), so a stop on a finished session never happened there.
 * Here it did, and it sat in ENDING forever: the 5 s ending timeout calls `finalize()`, which is a
 * no-op once the session is finalized.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StopWithoutLiveSessionTest {

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

        val phase get() = controller.state.value.phase
        fun advance(ms: Long) { ts.testScheduler.advanceTimeBy(ms); ts.runCurrent() }

        fun startActive() {
            controller.startVoice()
            advance(1_000)
            check(phase == SessionPhase.ACTIVE) { "not active: $phase" }
        }
    }

    @Test
    fun stopAfterTheSessionEndedIsANoOp() = runTest {
        val r = Rig(this)
        r.startActive()
        r.controller.onInbound(VoiceInbound.Ended("idle"))
        r.advance(VoiceTuning.MIC_RELEASE_DELAY_MS + 100)
        assertEquals(SessionPhase.OFF, r.phase)
        val resumes = r.wake.resumes.size

        r.controller.stopVoice()
        r.advance(0)
        assertEquals("no ENDING for a finished session", SessionPhase.OFF, r.phase)
        assertTrue("no voice_stop for a finished session", r.wire.voiceStops.isEmpty())
        r.advance(VoiceTuning.ENDING_ACK_TIMEOUT_MS * 2)
        assertEquals(SessionPhase.OFF, r.phase)
        assertEquals("no second wake re-arm", resumes, r.wake.resumes.size)
        r.controller.release()
    }

    @Test
    fun stopAfterTheEndingTimeoutFinalizedIsANoOp() = runTest {
        val r = Rig(this)
        r.startActive()
        r.controller.stopVoice()
        r.advance(VoiceTuning.ENDING_ACK_TIMEOUT_MS)
        assertEquals(SessionPhase.OFF, r.phase)
        assertEquals(1, r.wire.voiceStops.size)

        r.controller.stopVoice()
        r.advance(VoiceTuning.ENDING_ACK_TIMEOUT_MS * 2)
        assertEquals(SessionPhase.OFF, r.phase)
        assertEquals(1, r.wire.voiceStops.size)
        r.controller.release()
    }

    @Test
    fun stopOnANeverStartedControllerIsANoOp() = runTest {
        val r = Rig(this)
        r.controller.stopVoice()
        r.advance(VoiceTuning.ENDING_ACK_TIMEOUT_MS * 2)
        assertEquals(SessionPhase.OFF, r.phase)
        assertTrue(r.wire.voiceStops.isEmpty())
        assertTrue("no wake re-arm without a session", r.wake.resumes.isEmpty())
        r.controller.release()
    }

    @Test
    fun stopAfterTheLinkFailedKeepsTheErrorAndSendsNothing() = runTest {
        val r = Rig(this)
        r.startActive()
        r.controller.onConnection(ConnectionSignal.Disconnected(willReconnect = true))
        r.advance(VoiceTuning.LINK_RETRY_BUDGET_MS)
        assertEquals(SessionPhase.ERROR, r.phase)

        r.controller.stopVoice()
        r.advance(VoiceTuning.ENDING_ACK_TIMEOUT_MS * 2)
        assertEquals(SessionPhase.ERROR, r.phase)
        assertTrue(r.wire.voiceStops.isEmpty())
        assertTrue("the failure state stays for the host's manual Reconnect", r.controller.link.value is VoiceLinkState.Failed)
        r.controller.release()
    }

    @Test
    fun stopAfterABareMarkConnectingReturnsToOff() = runTest {
        val r = Rig(this)
        r.controller.markConnecting()
        r.controller.stopVoice()
        r.advance(VoiceTuning.ENDING_ACK_TIMEOUT_MS * 2)
        assertEquals(SessionPhase.OFF, r.phase)
        assertTrue(r.wire.voiceStops.isEmpty())
        r.controller.release()
    }

    @Test
    fun stopWhenTheBackendStillReportsUsAsOwnerAfterTeardownSendsVoiceStopWithoutEnding() = runTest {
        val r = Rig(this)
        r.startActive()
        r.controller.stopVoice()
        r.advance(VoiceTuning.ENDING_ACK_TIMEOUT_MS)
        assertEquals(SessionPhase.OFF, r.phase)
        r.controller.onInbound(VoiceInbound.SessionStarted(voice = true, voiceInitiator = true, voiceSessionUpdate = null))

        r.controller.stopVoice()
        r.advance(VoiceTuning.ENDING_ACK_TIMEOUT_MS * 2)
        assertEquals(2, r.wire.voiceStops.size)
        assertEquals(SessionPhase.OFF, r.phase)
        r.controller.release()
    }

    @Test
    fun stopWhileStartingStillEndsTheSession() = runTest {
        val r = Rig(this)
        r.controller.startVoice()
        r.controller.stopVoice()
        r.advance(0)
        assertEquals(1, r.wire.voiceStops.size)
        r.advance(VoiceTuning.ENDING_ACK_TIMEOUT_MS + 1_000)
        assertEquals(SessionPhase.OFF, r.phase)
        r.controller.release()
    }
}
