package com.assistant.core.voicehost.runtime

import android.app.Activity
import com.assistant.core.protocol.ClientFrame
import com.assistant.core.protocol.ServerFrame
import com.assistant.core.session.OrchestratorRef
import com.assistant.core.voice.VoiceTuning
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voicehost.HostConfig
import com.assistant.core.voicehost.HostRig
import com.assistant.core.voicehost.HostTuning
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 12 §6.11a: Archie's `switch_conversation` moves the call into a past conversation. The
 * server ends voice (`reason:"switch"`), closes the old orchestrator and sends `orchestrator_switch`
 * to this socket; the device resumes the past conversation and, in a call, starts voice on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeSwitchTest {
    private val past = "past-9"

    /** The server half of `switch_conversation` (backend/orchestrator/tools/agent_sessions.py `_run_switch`). */
    private fun HostRig.serverSwitches(voice: Boolean) {
        if (voice) {
            socket.frame(ServerFrame.VoiceEnding("switch", HostRig.ORCH.localId))
            socket.frame(ServerFrame.VoiceEnded("switch", HostRig.ORCH.localId))
        }
        socket.frame(ServerFrame.AgentSessionClosed(HostRig.ORCH.localId, isOrchestrator = true))
        socket.frame(ServerFrame.OrchestratorSwitch(past, "Lamps", voice, HostRig.ORCH.localId))
        settle()
    }

    private fun HostRig.resumed(): OrchestratorRef {
        val ref = channel.state.value.orchestrator
        assertNotNull("the channel armed the resumed conversation", ref)
        assertEquals(past, ref!!.sdkId)
        assertNotEquals("a new local id, never the stopped one", HostRig.ORCH.localId, ref.localId)
        return ref
    }

    private fun HostRig.voiceStarts() = socket.sent.filterIsInstance<ClientFrame.VoiceStart>()

    /** Lite (autoStart): start → session_started → voice_start with the new id, quietly. */
    @Test
    fun liteResumesThePastConversationAndRestartsVoiceOnIt() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        r.startActiveVoice()
        val wakeCallsBefore = r.engines.last().calls.toList()
        val cuesBefore = r.cues.kinds()
        r.socket.sent.clear()

        r.serverSwitches(voice = true)
        val ref = r.resumed()
        assertEquals(SessionPhase.OFF, r.phase)
        assertEquals(
            "the channel's start resumes it; no voice_start before its session_started (a voice_start first would make this device passive)",
            listOf<ClientFrame>(ClientFrame.Start(ref.localId, past)), r.socket.sent.toList(),
        )
        assertTrue("P-1 / SW-3: nothing is stopped by the client", ClientFrame.VoiceStop !in r.socket.sent)

        // SW-3 quiet end: no cue, and the wake word is not re-armed in between (old delay: 1.5 s).
        r.advance(VoiceTuning.MIC_RELEASE_DELAY_MS + 500)
        assertEquals(cuesBefore, r.cues.kinds())
        assertEquals(wakeCallsBefore, r.engines.last().calls.toList())
        assertTrue(r.voiceStarts().isEmpty())

        r.socket.frame(ServerFrame.SessionStarted(sessionId = ref.localId, jsonlId = past, voice = false)); r.settle()
        r.advance(1_000)
        assertEquals(SessionPhase.ACTIVE, r.phase)
        val vs = r.voiceStarts().single()
        assertEquals(ref.localId, vs.localId)
        assertEquals(past, vs.resumeSdkId)
        assertEquals("the device's current voice settings", "cedar", vs.voice.voice)
        assertEquals(listOf("start", "voice_start"), r.socket.sent.map { it.type })
        assertEquals("SW-2: no wake / start cue for the switch", cuesBefore, r.cues.kinds())

        // The deferred re-arm of the ended session never fires into the new call.
        r.advance(VoiceTuning.SWITCH_WAKE_REARM_DELAY_MS)
        assertEquals(SessionPhase.ACTIVE, r.phase)
        assertFalse("resume" in r.engines.last().calls.drop(wakeCallsBefore.size))
    }

    @Test
    fun aTextSwitchResumesWithoutVoice() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        r.socket.sent.clear()

        r.serverSwitches(voice = false)
        val ref = r.resumed()
        r.socket.frame(ServerFrame.SessionStarted(sessionId = ref.localId, jsonlId = past)); r.settle()
        r.advance(5_000)
        assertEquals(SessionPhase.OFF, r.phase)
        assertEquals(listOf<ClientFrame>(ClientFrame.Start(ref.localId, past)), r.socket.sent.toList())
    }

    /** SW-1: a duplicate `orchestrator_switch` starts nothing twice. */
    @Test
    fun aDuplicateSwitchStartsVoiceOnce() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        r.startActiveVoice()
        r.socket.sent.clear()
        r.serverSwitches(voice = true)
        val ref = r.resumed()
        r.socket.frame(ServerFrame.OrchestratorSwitch(past, "Lamps", true, HostRig.ORCH.localId)); r.settle()
        r.socket.frame(ServerFrame.SessionStarted(sessionId = ref.localId, jsonlId = past)); r.settle()
        r.socket.frame(ServerFrame.SessionStarted(sessionId = ref.localId, jsonlId = past)); r.settle()   // a foreground resync
        r.advance(1_000)
        assertEquals(1, r.socket.sent.count { it is ClientFrame.Start })
        assertEquals(1, r.voiceStarts().size)
    }

    /**
     * Main app (no autoStart: the conversation repository sends the `start`). The foreground service
     * is kept through the switch — a background call must be able to take the mic again — and voice
     * starts once the repository's start is answered.
     */
    @Test
    fun mainKeepsTheServiceThroughTheSwitchAndStartsVoiceAfterTheRepositoryStart() = runTest {
        val r = HostRig(this, config = HostConfig.main(Activity::class.java, Activity::class.java), autoStart = false)
        r.connectAndAdopt(HostRig.SETTINGS.copy(enableWakeWord = false))
        r.runtime.onUiStarted()
        r.startActiveVoice()
        r.settle()
        val stopsBefore = r.service.stops
        r.socket.sent.clear()

        r.serverSwitches(voice = true)
        val ref = r.resumed()
        r.advance(2_000)
        assertEquals(SessionPhase.OFF, r.phase)
        assertEquals("held for the switch", stopsBefore, r.service.stops)
        assertTrue("the repository owns the start", r.socket.sent.isEmpty())

        r.channel.send(ClientFrame.Start(ref.localId, ref.sdkId))                 // ConversationRepository.switchArchie
        r.socket.frame(ServerFrame.SessionStarted(sessionId = ref.localId, jsonlId = past)); r.settle()
        r.advance(1_000)
        assertEquals(SessionPhase.ACTIVE, r.phase)
        assertEquals(listOf("start", "voice_start"), r.socket.sent.map { it.type })
        assertEquals(ref.localId, r.voiceStarts().single().localId)
        assertEquals(stopsBefore, r.service.stops)
    }

    /** The resumed conversation never comes up: the hold ends and the service may stop. */
    @Test
    fun theHoldEndsWhenTheResumeNeverStarts() = runTest {
        val r = HostRig(this, config = HostConfig.main(Activity::class.java, Activity::class.java), autoStart = false)
        r.connectAndAdopt(HostRig.SETTINGS.copy(enableWakeWord = false))
        r.runtime.onUiStarted()
        r.startActiveVoice()
        r.settle()
        val stopsBefore = r.service.stops

        r.serverSwitches(voice = true)
        r.advance(HostTuning.SWITCH_HOLD_MS + 1_000)
        assertTrue(r.service.stops > stopsBefore)
        // A late session_started no longer starts voice.
        val ref = r.resumed()
        r.socket.frame(ServerFrame.SessionStarted(sessionId = ref.localId, jsonlId = past)); r.settle()
        r.advance(1_000)
        assertEquals(SessionPhase.OFF, r.phase)
        assertTrue(r.voiceStarts().size == 1)                                         // the original call only
    }
}
