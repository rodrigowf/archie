package com.assistant.core.voicehost.runtime

import android.app.Activity
import com.assistant.core.protocol.ClientFrame
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voicehost.HostConfig
import com.assistant.core.voicehost.HostRig
import com.assistant.core.voicehost.WakeHealth
import com.assistant.core.voicehost.cue.CueKind
import com.assistant.core.voicehost.fgs.FgsDecision
import com.assistant.core.voicehost.fgs.FgsTypes
import com.assistant.core.voicehost.ports.Trigger
import com.assistant.core.wakeword.ports.WakeLoopEvent
import com.assistant.core.wakeword.ports.WakePhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The runtime's wake / trigger / settings wiring over the real wake-service logic. */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeWakeTest {

    /** B4: nothing (no engine, no connect) happens until the persisted settings are loaded. */
    @Test
    fun b4_nothingIsAppliedBeforeSettingsLoad() = runTest {
        val r = HostRig(this)
        r.runtime.connect()
        r.advance(5_000)
        assertTrue(r.engines.isEmpty())
        assertTrue(r.socket.connects.isEmpty())
        r.settings.value = HostRig.SETTINGS
        r.settle()
        assertEquals(1, r.socket.connects.size)
        assertTrue(r.socket.connects.single(), r.socket.connects.single().startsWith("ws://192.168.0.200"))
        assertEquals(1, r.engines.size)
        assertEquals(1.3f, r.engines.single().config.wakeGain, 0f)
        assertEquals("the persisted URL, not a default", "ws://192.168.0.200:80", r.engines.single().config.serverUrl)
    }

    /** RS-33 / `9200d50`: one wake-service update per change of its six fields; voice-only changes don't touch it. */
    @Test
    fun rs33_settingsReachTheWakeServiceOncePerWakeChange() = runTest {
        val r = HostRig(this)
        r.settings.value = HostRig.SETTINGS
        r.settle()
        r.advance(10_000)
        r.settings.value = HostRig.SETTINGS.copy(micGain = 0.7f, echoDuckingGain = 0.1f)
        r.settle()
        assertEquals("mic/duck gain changes don't rebuild the wake engine", 1, r.engines.size)
        assertEquals(1, r.store.saves.size)
        r.settings.value = HostRig.SETTINGS.copy(micGain = 0.7f, echoDuckingGain = 0.1f, talkWord = "hello friend")
        r.settle()
        assertEquals(2, r.engines.size)
        assertEquals("gain kept explicit", 1.3f, r.engines.last().config.wakeGain, 0f)
    }

    @Test
    fun buttonTriggerIsMirroredToServicePrefs() = runTest {
        val r = HostRig(this)
        r.settings.value = HostRig.SETTINGS
        r.settle()
        r.settings.value = HostRig.SETTINGS.copy(buttonTriggerEnabled = false)
        r.settle()
        assertEquals(listOf(true, false), r.buttonPrefs)
    }

    /** Wake → voice without an Activity: the wake loop's WakeDetected goes through the ingress (cue, voice start). */
    @Test
    fun wakeDetectedStartsVoiceHeadlessly() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        r.engines.last().emit(WakeLoopEvent.WakeDetected)
        r.settle()
        assertEquals(listOf(CueKind.WAKE_ACK), r.cues.kinds())
        assertEquals("lite: screen woken + face brought to front", 1, r.brought)
        r.advance(1_000)
        assertEquals(SessionPhase.ACTIVE, r.phase)
        // The wake engine was paused for voice (one mic owner).
        assertEquals(WakeHealth.PAUSED_FOR_VOICE, r.state.wake)
        assertTrue("pause" in r.engines.last().calls)
    }

    @Test
    fun theMainAppNeverBringsAnActivityToTheFront() = runTest {
        val r = HostRig(this, config = HostConfig.main(Activity::class.java, Activity::class.java))
        r.connectAndAdopt()
        r.engines.last().emit(WakeLoopEvent.WakeDetected)
        r.settle()
        assertEquals(0, r.brought)
        assertEquals(listOf(CueKind.WAKE_ACK), r.cues.kinds())
    }

    @Test
    fun aConfirmedTalkMessageIsSentOnTheOrchestratorSocket() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        val e = r.engines.last()
        e.emit(WakeLoopEvent.TalkDetected)
        r.settle()
        assertTrue(r.state.talk.isRecording)
        e.emit(WakeLoopEvent.TalkMessageCaptured(ByteArray(44) { 1 }))
        r.settle()
        val audio = r.socket.sent.filterIsInstance<ClientFrame.SendAudio>().single()
        assertEquals("wav", audio.format)
        assertTrue("voiceMessageSent" in r.transcripts.entries)
        assertFalse(r.state.talk.isRecording)
        assertEquals(listOf(CueKind.TALK_ACK), r.cues.kinds())
    }

    /** Events of a replaced engine are dropped (a stale detection can't start voice). */
    @Test
    fun lateEventsOfAReplacedEngineAreIgnored() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        val old = r.engines.last()
        r.advance(10_000)
        r.settings.value = HostRig.SETTINGS.copy(wakeWord = "hey archie")
        r.settle()
        assertEquals(2, r.engines.size)
        old.emit(WakeLoopEvent.WakeDetected)
        r.settle()
        assertTrue(r.cues.played.isEmpty())
    }

    /** R3 through the real engine adapter: a screen-on during TALK_CAPTURING leaves the engine alone. */
    @Test
    fun r3_screenOnDuringCaptureNeverRestartsTheEngine() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        r.advance(10_000)
        for (busy in listOf(WakePhase.CONFIRMING_WAKE, WakePhase.MATCH_TAIL, WakePhase.TALK_PRE_ONSET, WakePhase.TALK_CAPTURING, WakePhase.CONFIRMING_TALK)) {
            r.engines.last().phase.value = busy
            r.runtime.onScreenOnOrUserPresent()
            r.advance(300)
            assertEquals("$busy", 1, r.engines.size)
        }
        r.engines.last().phase.value = WakePhase.MONITORING
        r.runtime.onScreenOnOrUserPresent()
        r.advance(300)
        assertEquals("a healthy idle engine is restarted", 2, r.engines.size)
    }

    @Test
    fun micStalledShowsInTheWakeHealth() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        assertEquals(WakeHealth.ARMED, r.state.wake)
        r.engines.last().emit(WakeLoopEvent.MicUnavailable)
        r.settle()
        assertEquals(WakeHealth.MIC_STALLED, r.state.wake)
        r.engines.last().emit(WakeLoopEvent.MicAvailable)
        r.settle()
        assertEquals(WakeHealth.ARMED, r.state.wake)
    }

    /** Android 14 sticky restart without the microphone type: the engine is held, health says so, the promotion resumes it. */
    @Test
    fun aDegradedServiceHoldsTheMicUntilPromoted() = runTest {
        val r = HostRig(this)
        r.runtime.onServiceStarted(FgsDecision(true, FgsTypes.SPECIAL_USE, micAllowed = false))
        r.settings.value = HostRig.SETTINGS
        r.settle()
        val e = r.engines.single()
        assertFalse("no AudioRecord opened while degraded", "start" in e.calls)
        assertEquals(WakeHealth.PAUSED_NEEDS_FOREGROUND, r.state.wake)
        r.runtime.onServiceStarted(FgsDecision(true, FgsTypes.MICROPHONE or FgsTypes.SPECIAL_USE, micAllowed = true))
        r.settle()
        assertTrue("start" in e.calls)
        assertEquals(WakeHealth.ARMED, r.state.wake)
    }

    @Test
    fun pauseAndResumeListening() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        val e = r.engines.last()
        r.runtime.pauseListening()
        r.settle()
        assertEquals(WakePhase.PAUSED, e.phase.value)
        assertEquals(WakeHealth.PAUSED_BY_USER, r.state.wake)
        r.runtime.resumeListening()
        r.settle()
        assertEquals(WakePhase.MONITORING, e.phase.value)
        assertEquals(WakeHealth.ARMED, r.state.wake)
    }

    @Test
    fun recentsHonoursTheButtonToggleAtTheIngress() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt(HostRig.SETTINGS.copy(buttonTriggerEnabled = false))
        r.runtime.startVoice(Trigger.RECENTS)
        r.settle()
        assertEquals(0, r.brought)
        assertTrue(r.cues.played.isEmpty())
        assertEquals(SessionPhase.OFF, r.phase)
    }

    /** P-1: no lifecycle path sends stop / voice_stop. */
    @Test
    fun p1_lifecycleNeverSendsStop() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        r.startActiveVoice()
        r.runtime.onUiStarted()
        r.runtime.onUiStopped()
        r.runtime.disconnect()
        r.runtime.release()
        r.settle()
        assertTrue(r.socket.sent.none { it == ClientFrame.Stop || it == ClientFrame.VoiceStop })
    }

    @Test
    fun lastExchangeKeepsTheLatestFinalTranscripts() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        r.startActiveVoice()
        val t = r.transports.last
        t.emit(com.assistant.core.voice.ports.ProviderSignal.UserTranscript("what's the weather tomorrow"))
        t.emit(com.assistant.core.voice.ports.ProviderSignal.TextComplete("Tomorrow in Rio it'll be 27° and sunny"))
        r.settle()
        assertEquals("what's the weather tomorrow", r.state.lastExchange.user)
        assertEquals("Tomorrow in Rio it'll be 27° and sunny", r.state.lastExchange.assistant)
        assertTrue("user:what's the weather tomorrow" in r.transcripts.entries)
    }

    /** B7/B8: push-to-talk pauses the wake word before opening its own mic, ships the WAV, re-arms 1.5 s later. */
    @Test
    fun pushToTalkPausesWakeFirstAndReArmsAfter() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        r.advance(10_000)
        val e = r.engines.last()
        r.runtime.startPushToTalk()
        r.settle()
        assertEquals(WakePhase.PAUSED, e.phase.value)
        assertEquals(listOf("start"), r.ptt.calls)
        assertTrue(r.state.talk.isRecording)
        assertTrue(r.state.pushToTalk)
        r.runtime.stopPushToTalk(send = true)
        r.settle()
        assertEquals(1, r.socket.sent.filterIsInstance<ClientFrame.SendAudio>().size)
        assertFalse(r.state.talk.isRecording)
        val before = r.engines.size
        r.advance(1_499)
        assertEquals(before, r.engines.size)
        r.advance(1)
        assertEquals("full restart after the mic-release delay", before + 1, r.engines.size)
    }

    @Test
    fun pushToTalkMicFailureResumesWakeAndToasts() = runTest {
        val r = HostRig(this)
        r.ptt.opens = false
        r.connectAndAdopt()
        r.advance(10_000)
        r.runtime.startPushToTalk()
        r.settle()
        assertFalse(r.state.pushToTalk)
        r.advance(1_500)
        assertEquals(WakeHealth.ARMED, r.state.wake)
    }
}
