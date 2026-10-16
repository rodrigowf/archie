package com.assistant.core.voicehost.runtime

import com.assistant.core.protocol.ClientFrame
import com.assistant.core.session.SessionTuning
import com.assistant.core.voicehost.HostRig
import com.assistant.core.voicehost.cue.CueKind
import com.assistant.core.voicehost.ports.Trigger
import com.assistant.core.wakeword.ports.WakeLoopEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No conversation open on the server: the wake word and the voice button start a new orchestrator
 * conversation at once (Rodrigo, 2026-10-15), in both apps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeNoConversationTest {

    /** Settings loaded → socket open → the probe (and its retry) finds no orchestrator. */
    private fun HostRig.connectToEmptyPool() {
        settings.value = HostRig.SETTINGS
        settle()
        socket.open()
        settle()
        advance(SessionTuning.POOL_PROBE_RETRY_MS + 100)
        check(channel.state.value.noOrchestrator) { "probe did not report no orchestrator\n${log.dump()}" }
        socket.sent.clear()
    }

    private fun HostRig.assertNewConversationStarted(expectStart: Boolean) {
        val ref = assertNotNullRef()
        assertEquals("a new conversation: sdkId == localId", ref.localId, ref.sdkId)
        assertFalse(channel.state.value.noOrchestrator)
        val voice = socket.sent.filterIsInstance<ClientFrame.VoiceStart>().single()
        assertEquals(ref.localId, voice.localId)
        assertNull("the user's own start, not a reattach", voice.reattach)
        val starts = socket.sent.filterIsInstance<ClientFrame.Start>()
        if (expectStart) {
            assertEquals(ref.localId, starts.single().localId)
            assertNull("the start creates it (OPEN-2)", starts.single().reattach)
        } else {
            assertTrue("main: the conversation repository sends the start", starts.isEmpty())
        }
    }

    private fun HostRig.assertNotNullRef() = channel.state.value.orchestrator.also { assertNotNull("no conversation armed\n${log.dump()}", it) }!!

    @Test
    fun liteWakeWordStartsANewConversation() = runTest {
        val r = HostRig(this, pool = emptyList())
        r.connectToEmptyPool()
        r.engines.last().emit(WakeLoopEvent.WakeDetected)
        r.settle()
        assertEquals(listOf(CueKind.WAKE_ACK), r.cues.kinds())
        r.advance(1_000)
        r.assertNewConversationStarted(expectStart = true)
    }

    @Test
    fun liteButtonStartsANewConversation() = runTest {
        val r = HostRig(this, pool = emptyList())
        r.connectToEmptyPool()
        r.runtime.startVoice(Trigger.BUTTON)
        r.advance(1_000)
        r.assertNewConversationStarted(expectStart = true)
    }

    @Test
    fun mainWakeWordStartsANewConversation() = runTest {
        val r = HostRig(this, pool = emptyList(), autoStart = false)
        r.connectToEmptyPool()
        r.engines.last().emit(WakeLoopEvent.WakeDetected)
        r.advance(1_000)
        r.assertNewConversationStarted(expectStart = false)
    }

    /** With a conversation open the wake word uses it: nothing new is armed. */
    @Test
    fun wakeWordKeepsTheOpenConversation() = runTest {
        val r = HostRig(this)
        r.connectAndAdopt()
        r.socket.sent.clear()
        r.engines.last().emit(WakeLoopEvent.WakeDetected)
        r.advance(1_000)
        assertEquals(HostRig.ORCH.localId, r.channel.state.value.orchestrator?.localId)
        assertEquals(HostRig.ORCH.localId, r.socket.sent.filterIsInstance<ClientFrame.VoiceStart>().single().localId)
        assertTrue(r.socket.sent.none { it is ClientFrame.Start })
    }
}
