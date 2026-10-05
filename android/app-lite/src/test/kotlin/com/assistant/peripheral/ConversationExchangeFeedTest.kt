package com.assistant.peripheral

import com.assistant.core.protocol.ContentBlockDto
import com.assistant.core.protocol.MessagePreviewDto
import com.assistant.core.protocol.ServerFrame
import com.assistant.core.voicehost.LastExchange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationExchangeFeedTest {
    private val sink = LastExchangeSink(clock = { 0L })
    private var voiceLive = false
    private val fetched = mutableListOf<String>()
    private var page: suspend (String) -> List<MessagePreviewDto>? = { null }

    private fun TestScope.feed() = ConversationExchangeFeed(
        scope = backgroundScope,
        sink = sink,
        history = { id -> fetched += id; page(id) },
        voiceLive = { voiceLive },
    )

    private fun started(localId: String = "L1", jsonl: String? = "S1", voice: Boolean? = null, initiator: Boolean? = null) =
        ServerFrame.SessionStarted(sessionId = localId, jsonlId = jsonl, voice = voice, voiceInitiator = initiator)

    private fun turn(f: ConversationExchangeFeed, vararg texts: String) {
        f.onFrame(ServerFrame.Status("streaming"))
        for (t in texts) {
            f.onFrame(ServerFrame.TextDelta(t.take(3)))
            f.onFrame(ServerFrame.TextComplete(t))
        }
        f.onFrame(ServerFrame.TurnComplete(inputTokens = 1, outputTokens = 1))
        f.onFrame(ServerFrame.Status("idle"))
    }

    private fun user(text: String, blocks: List<ContentBlockDto> = emptyList()) = MessagePreviewDto("user", text, blocks)
    private fun assistant(vararg texts: String) = MessagePreviewDto("assistant", texts.lastOrNull().orEmpty(), texts.map { ContentBlockDto("text", it) })
    private fun toolOnly() = MessagePreviewDto("assistant", "", listOf(ContentBlockDto("tool_use", toolUseId = "t1", toolName = "run_script")))
    private fun toolResult() = user("", listOf(ContentBlockDto("tool_result", toolUseId = "t1")))

    @Test fun echoedPromptAndTextReply_fillTheExchange() = runTest(UnconfinedTestDispatcher()) {
        val f = feed()
        f.onFrame(ServerFrame.UserMessage("What's the **weather**?"))
        assertEquals(LastExchange("What's the weather?", null), sink.exchange.value)
        f.onFrame(ServerFrame.Status("streaming"))
        f.onFrame(ServerFrame.TextDelta("Sun"))
        assertEquals(LastExchange("What's the weather?", null), sink.exchange.value) // no per-token rebind
        f.onFrame(ServerFrame.TextComplete("Sunny, *29°*."))
        assertEquals(LastExchange("What's the weather?", "Sunny, 29°."), sink.exchange.value)
    }

    @Test fun laterTextOfTheSameTurn_replacesTheReply_keepsThePrompt() = runTest(UnconfinedTestDispatcher()) {
        val f = feed()
        f.onFrame(ServerFrame.UserMessage("check the TV"))
        turn(f, "Let me check.", "The TV is on.")
        assertEquals(LastExchange("check the TV", "The TV is on."), sink.exchange.value)
    }

    @Test fun backgroundTurn_afterACompleteExchange_hasNoPrompt() = runTest(UnconfinedTestDispatcher()) {
        val f = feed()
        f.onFrame(ServerFrame.UserMessage("start the build"))
        turn(f, "Started.")
        turn(f, "The build finished.")
        assertEquals(LastExchange(null, "The build finished."), sink.exchange.value)
    }

    @Test fun remoteVoiceMessage_isShownAsAVoiceMessage() = runTest(UnconfinedTestDispatcher()) {
        val f = feed()
        f.onFrame(ServerFrame.UserMessage("", source = "voice_message"))
        assertEquals(LastExchangeSink.REMOTE_VOICE_MESSAGE, sink.exchange.value.user)
        f.onFrame(ServerFrame.UserMessage("about this", source = "voice_message"))
        assertEquals("(voice message) about this", sink.exchange.value.user)
        f.onFrame(ServerFrame.UserMessage("queued one", queued = true))
        assertEquals("(voice message) about this", sink.exchange.value.user)
    }

    @Test fun localVoiceMessage_replyFillsTheArchieLine() = runTest(UnconfinedTestDispatcher()) {
        val f = feed()
        f.onFrame(ServerFrame.UserMessage("old"))
        turn(f, "old reply")
        sink.voiceMessageSent()
        turn(f, "Here you go.")
        assertEquals(LastExchange(LastExchangeSink.VOICE_MESSAGE, "Here you go."), sink.exchange.value)
    }

    @Test fun turnWithoutTextComplete_fallsBackToTheDeltas() = runTest(UnconfinedTestDispatcher()) {
        val f = feed()
        f.onFrame(ServerFrame.UserMessage("hi"))
        f.onFrame(ServerFrame.Status("streaming"))
        f.onFrame(ServerFrame.TextDelta("Hel"))
        f.onFrame(ServerFrame.TextDelta("lo"))
        f.onFrame(ServerFrame.TurnComplete())
        assertEquals(LastExchange("hi", "Hello"), sink.exchange.value)
        // Bounded buffer.
        f.onFrame(ServerFrame.UserMessage("long"))
        f.onFrame(ServerFrame.Status("streaming"))
        repeat(100) { f.onFrame(ServerFrame.TextDelta("x".repeat(100))) }
        f.onFrame(ServerFrame.Status("idle"))
        assertEquals(LastExchange.MAX_CHARS, sink.exchange.value.assistant!!.length)
    }

    @Test fun ownVoiceSession_textPathIsSkipped_transcriptsOnly() = runTest(UnconfinedTestDispatcher()) {
        val f = feed()
        f.onFrame(started())
        fetched.clear()
        voiceLive = true
        sink.userTranscript("turn on the lights", final = true)
        f.onFrame(ServerFrame.UserMessage("typed elsewhere"))
        turn(f, "duplicate text")
        sink.assistantTranscript("Done.", final = true)
        assertEquals(LastExchange("turn on the lights", "Done."), sink.exchange.value)
        // No history load during own voice either (re-attach after a reconnect mid-voice).
        page = { listOf(user("x"), assistant("y")) }
        f.onFrame(started(voice = true, initiator = true))
        assertEquals(emptyList<String>(), fetched)
        assertEquals(LastExchange("turn on the lights", "Done."), sink.exchange.value)
    }

    @Test fun sessionStarted_loadsTheLastExchangeFromHistory() = runTest(UnconfinedTestDispatcher()) {
        val f = feed()
        page = {
            listOf(
                user("older"), assistant("older reply"),
                user("[voice] what's on the **calendar**?"),
                toolOnly(), toolResult(),
                assistant("You have `two` meetings.", "First at 10."),
                toolOnly(),
            )
        }
        f.onFrame(started())
        assertEquals(listOf("S1"), fetched)
        assertEquals(LastExchange("what's on the calendar?", "First at 10."), sink.exchange.value)
    }

    @Test fun lateHistory_neverOverwritesANewerLiveFrame() = runTest(UnconfinedTestDispatcher()) {
        val f = feed()
        val gate = CompletableDeferred<List<MessagePreviewDto>>()
        page = { gate.await() }
        f.onFrame(started())
        f.onFrame(ServerFrame.UserMessage("fresh prompt"))
        gate.complete(listOf(user("stale"), assistant("stale reply")))
        runCurrent()
        assertEquals(LastExchange("fresh prompt", null), sink.exchange.value)
    }

    @Test fun anotherConversation_orClosed_clearsTheExchange() = runTest(UnconfinedTestDispatcher()) {
        val f = feed()
        f.onFrame(started())
        f.onFrame(ServerFrame.UserMessage("hello"))
        // Reconnect to the same conversation: kept.
        f.onFrame(started())
        assertEquals("hello", sink.exchange.value.user)
        // Another conversation: cleared, then its history.
        page = { listOf(user("other"), assistant("other reply")) }
        f.onFrame(started(localId = "L2", jsonl = "S2"))
        assertEquals(LastExchange("other", "other reply"), sink.exchange.value)
        // Some other session closing: kept. Ours: cleared.
        f.onFrame(ServerFrame.AgentSessionClosed("L9", isOrchestrator = true))
        assertEquals("other", sink.exchange.value.user)
        f.onFrame(ServerFrame.AgentSessionClosed("L2", isOrchestrator = true))
        assertEquals(LastExchange(), sink.exchange.value)
        // Channel detached (close / server change).
        f.onFrame(started(localId = "L3", jsonl = "S3"))
        f.onFrame(ServerFrame.UserMessage("third"))
        f.onDetached()
        assertEquals(LastExchange(), sink.exchange.value)
    }

    @Test fun voiceOnAnotherDevice_reloadsHistoryWhenItEnds() = runTest(UnconfinedTestDispatcher()) {
        val f = feed()
        f.onFrame(started())
        fetched.clear()
        f.onFrame(ServerFrame.VoiceOwnerActive(active = true))
        page = { listOf(user("[voice] play some jazz"), assistant("Playing jazz.")) }
        f.onFrame(ServerFrame.VoiceEnded("user"))
        f.onFrame(ServerFrame.VoiceStopped())
        f.onFrame(ServerFrame.VoiceOwnerActive(active = false))
        assertEquals(listOf("S1"), fetched) // once
        assertEquals(LastExchange("play some jazz", "Playing jazz."), sink.exchange.value)
        // Own voice ending does not reload (its transcripts are already shown).
        fetched.clear()
        voiceLive = true
        f.onFrame(ServerFrame.VoiceOwnerActive(active = true))
        voiceLive = false
        f.onFrame(ServerFrame.VoiceEnded("user"))
        assertEquals(emptyList<String>(), fetched)
    }

    @Test fun historyClassification() {
        val of = ConversationExchangeFeed::lastExchangeOf
        assertEquals(LastExchange("(voice message)", "Got it."), of(listOf(user("[audio:wav] (audio message)"), assistant("Got it."))))
        assertEquals(LastExchange("(voice message) see this", null), of(listOf(user("[audio:webm] see this"))))
        assertEquals(LastExchange("hi", "Hello"), of(listOf(user("hi"), assistant("Hello"), user("[Request interrupted by user]"))))
        assertEquals(LastExchange(null, "Task done."), of(listOf(user("hi"), assistant("Hello"), user("<task-notification>x</task-notification>"), assistant("Task done."))))
        assertEquals(LastExchange(null, "Only a reply"), of(listOf(toolResult(), assistant("Only a reply"))))
        assertEquals(LastExchange("typed", null), of(listOf(user("[voice] "), user("typed"), user("<command-name>/clear</command-name>"))))
        assertNull(of(listOf(toolOnly(), toolResult())))
        assertNull(of(emptyList()))
    }
}
