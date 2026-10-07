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
 * Spec 12 §6.11a SW-3: `voice_ended{reason:"switch"}` is a quiet end. The session tears down like
 * any end, but the wake word is not re-armed [VoiceTuning.MIC_RELEASE_DELAY_MS] later: the call
 * continues in the resumed conversation, whose start cancels the deferred re-arm.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchQuietEndTest {

    private class Rig(private val ts: TestScope) {
        val clock = FakeClock.boundTo(ts.testScheduler)
        val wire = FakeVoiceWire(clock)
        val wake = FakeWakeHandoff(clock)
        val cues = RecordingCues()
        val controller = DefaultVoiceSessionController(
            VoiceSessionDeps(
                ts.backgroundScope, clock, RecordingLog(), FakeVoiceApi(clock), wire, FakeOrchestratorContext(), wake,
                FakeVoiceTransportFactory(clock), FakeAudioSession(clock), RecordingTranscriptSink(), cues,
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
    fun aSwitchEndDefersTheWakeReArm_andTheResumedStartCancelsIt() = runTest {
        val r = Rig(this)
        r.startActive()
        val resumes = r.wake.resumes.size

        r.controller.onInbound(VoiceInbound.Ending("switch"))
        r.controller.onInbound(VoiceInbound.Ended("switch"))
        r.advance(0)
        assertEquals(SessionPhase.OFF, r.phase)
        r.advance(VoiceTuning.MIC_RELEASE_DELAY_MS + 500)
        assertEquals("no wake re-arm at the usual delay", resumes, r.wake.resumes.size)
        assertTrue("no voice_stop from the client", r.wire.voiceStops.isEmpty())

        r.controller.startVoice()                                                  // the resumed conversation's call
        r.advance(VoiceTuning.SWITCH_WAKE_REARM_DELAY_MS)
        assertEquals(SessionPhase.ACTIVE, r.phase)
        assertEquals("the deferred re-arm was cancelled by the new start", resumes, r.wake.resumes.size)
        r.controller.release()
    }

    @Test
    fun aSwitchThatNeverResumesStillReArmsTheWakeWord() = runTest {
        val r = Rig(this)
        r.startActive()
        val resumes = r.wake.resumes.size
        r.controller.onInbound(VoiceInbound.Ended("switch"))
        r.advance(VoiceTuning.SWITCH_WAKE_REARM_DELAY_MS + 100)
        assertEquals(resumes + 1, r.wake.resumes.size)
        r.controller.release()
    }

    @Test
    fun otherEndsKeepTheUsualReArmDelay() = runTest {
        val r = Rig(this)
        r.startActive()
        val resumes = r.wake.resumes.size
        r.controller.onInbound(VoiceInbound.Ended("agent_end"))
        r.advance(VoiceTuning.MIC_RELEASE_DELAY_MS + 100)
        assertEquals(resumes + 1, r.wake.resumes.size)
        r.controller.release()
    }
}
