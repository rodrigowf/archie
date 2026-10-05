package com.assistant.core.conversation

import com.assistant.core.model.SessionKind
import com.assistant.core.protocol.ServerFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The intent of the old `chat/parity/ChatControllerParityTest.kt` re-expressed against the pure
 * reducer (spec 14 §6.1). Cases about caches, REST fetching and bucket switching belong to
 * `:core:data` (B-03); here are the ones that test reducer behaviour.
 */
class ChatParityTest {

    @Test
    fun streamingOrder_consecutiveDeltasExtendTheTrailingStreamingBlock() {
        val s = agent().send("q").on(processing(), ServerFrame.TextDelta("a"), ServerFrame.TextDelta("b"), ServerFrame.TextDelta("c"))
        assertShape("U(q) A[T(abc)~]", s)
    }

    @Test
    fun streamingOrder_toolUseSlotsAfterTextAndTheNextDeltaStartsANewBlock() {
        val s = agent().send("q").on(processing(), ServerFrame.TextDelta("before"), toolUse("t1"), ServerFrame.TextDelta("after"))
        assertShape("U(q) A[T(before) X(t1:running) T(after)~]", s)
    }

    @Test
    fun streamingOrder_toolResultPopulatesTheMatchingCardInPlace() {
        val s = agent().send("q").on(processing(), toolUse("t1"), ServerFrame.TextDelta("x"), toolResult("t1", "out"))
        assertShape("U(q) A[X(t1:done=out) T(x)~]", s)
    }

    @Test
    fun endpointIsolation_conversationsShareNothing() {
        // the old app had two buckets in one controller; here every view is its own state value
        val a0 = agent()
        val o0 = orchestrator()
        val a1 = a0.send("agent prompt").on(processing(), ServerFrame.TextComplete("agent reply"))
        val o1 = o0.send("orch prompt").on(ServerFrame.Status("streaming"), ServerFrame.TextComplete("orch reply"))
        assertShape("U(agent prompt) A[T(agent reply)]", a1)
        assertShape("U(orch prompt) A[T(orch reply)]", o1)
        assertEquals(SessionKind.AGENT, a1.kind)
        assertSame(o0.entries, orchestrator().entries)                         // untouched by the agent's inputs
    }

    @Test
    fun sessionStarted_adoptsTheServerSessionIdAsLocalId() {
        val s = orchestrator().input(ConversationInput.SocketOpened).on(ServerFrame.SessionStarted("O-new"))
        assertEquals("O-new", s.ref.localId)
    }

    @Test
    fun userMessage_rendersAUserBubbleInItsOwnConversationOnly() {
        val s = orchestrator().on(ServerFrame.UserMessage("from another device"))
        assertShape("U(from another device):echo", s)
        assertEquals(0, agent().entries.size)
    }

    @Test
    fun bg1_echoedPromptFromAnotherDeviceGetsNoBackgroundNotice() {
        // backend O-3: a prompt typed elsewhere arrives as user_message; its reply is visibly prompted
        val echoed = orchestrator().on(
            ServerFrame.UserMessage("Is it done?"), ServerFrame.Status("streaming"), ServerFrame.TextComplete("Yes."), ServerFrame.Status("idle"),
        )
        assertShape("U(Is it done?):echo A[T(Yes.)]", echoed)
        // an unprompted wake turn after an assistant run still gets the divider
        val wake = echoed.on(ServerFrame.Status("streaming"), ServerFrame.TextComplete("Build finished."), ServerFrame.Status("idle"))
        assertShape("U(Is it done?):echo A[T(Yes.)] N:background() A[T(Build finished.)]", wake)
    }

    @Test
    fun voiceTranscriptsAndToolsKeepTheirOrderAcrossVoiceTurns() {
        // the bug the old ChatController had (inv03 §4.3): every later tool joined the first tool's message
        val s = orchestrator(voiceActive = true).on(
            toolUse("A"),
            voice("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"one"}"""),
            toolUse("B"),
            voice("""{"type":"response.audio_transcript.done","transcript":"said"}"""),
            voice("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"two"}"""),
            toolUse("C"),
        )
        assertShape("A[X(A:running)] U(one):voice A[X(B:running) T(said)v] U(two):voice A[X(C:running)]", s)
        // a typed turn after voice ended never lands in an old voice run
        val t = s.on(ServerFrame.VoiceEnded("user_stop")).send("typed").on(ServerFrame.Status("streaming"), ServerFrame.TextDelta("reply"))
        assertShape("A[X(A:no_result)] U(one):voice A[X(B:no_result) T(said)v] U(two):voice A[X(C:no_result)] U(typed) A[T(reply)~]", t)
    }
}
