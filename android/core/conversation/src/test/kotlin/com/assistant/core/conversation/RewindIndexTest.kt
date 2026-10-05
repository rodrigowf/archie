package com.assistant.core.conversation

import com.assistant.core.conversation.RewindIndex.Result
import com.assistant.core.conversation.RewindIndex.TailLine
import com.assistant.core.protocol.MessagePreviewDto
import com.assistant.core.protocol.ServerFrame
import org.junit.Assert.assertEquals
import org.junit.Test

/** spec 12 §6.5: prompt-anchored cut resolved against a REST tail listing (fixes W-6, A-4.4.3). */
class RewindIndexTest {
    // REST lines 0..9 (absolute indices). Tool-result wrappers are not visible to the backend count.
    private val rest: List<MessagePreviewDto> = listOf(
        userLine("first"),                 // 0  prompt
        assistantText("a1"),               // 1
        assistantTool("t1"),               // 2
        resultLine("t1", "x"),             // 3  wrapper (not visible)
        assistantText("a2"),               // 4
        userLine("[voice] second"),        // 5  prompt (voice)
        assistantText("b1"),               // 6
        userLine("<command-name>/x</command-name>"),   // 7 command notice, not a prompt
        userLine("third"),                 // 8  prompt
        assistantText("c1"),               // 9
    )
    private val lines = rest.mapIndexed { i, p -> TailLine(i, p) }

    /** The same conversation as rendered live: local prompts, a voice transcript, a local-only error notice. */
    private val view: ConversationState = orchestrator(voiceActive = false)
        .send("first").on(ServerFrame.Status("streaming"), ServerFrame.TextComplete("a1"), toolUse("t1"), toolResult("t1", "x"), ServerFrame.TextComplete("a2"), ServerFrame.Status("idle"))
        .on(ServerFrame.VoiceOwnerActive(true))
        .on(voice("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"second"}"""), voice("""{"type":"response.audio_transcript.done","transcript":"b1"}"""))
        .on(ServerFrame.VoiceEnded("user_stop"))
        .on(ServerFrame.Error("api_error", "local-only error"))   // a notice the backend never wrote
        .send("third").on(ServerFrame.Status("streaming"), ServerFrame.TextComplete("c1"), ServerFrame.Status("idle"))

    private fun id(pred: (Entry) -> Boolean) = view.entries.first(pred).id

    @Test
    fun theViewHasTheExpectedShape() {
        assertShape("U(first) A[T(a1) X(t1:done=x) T(a2)] U(second):voice A[T(b1)v] N:error(local-only error) U(third) A[T(c1)]", view)
    }

    @Test
    fun rewindToAUserPromptKeepsThePromptAndDropsEverythingAfter() {
        val target = id { it is UserEntry && it.text == "second" }
        assertEquals(2, RewindIndex.promptsNeeded(view, target))              // 1 prompt after it, plus itself
        // drop lines 6..9, of which 6, 7, 8, 9 are visible
        assertEquals(Result.DropLastN(4), RewindIndex.compute(view, target, lines))
        assertEquals(Result.DropLastN(1), RewindIndex.compute(view, id { it is UserEntry && it.text == "third" }, lines))
        // first prompt: drop 1..9 except the wrapper at 3 → 8 lines
        assertEquals(Result.DropLastN(8), RewindIndex.compute(view, id { it is UserEntry && it.text == "first" }, lines))
    }

    @Test
    fun rewindToARunOrNoticeCutsAtTheNextPrompt() {
        val firstRun = id { it is AssistantEntry }
        assertEquals(Result.DropLastN(5), RewindIndex.compute(view, firstRun, lines))      // lines 5..9
        val notice = id { it is NoticeEntry }
        assertEquals(Result.DropLastN(2), RewindIndex.compute(view, notice, lines))        // lines 8, 9
        assertEquals(Result.DropLastN(0), RewindIndex.compute(view, view.entries.last().id, lines))
    }

    @Test
    fun mismatchesAbort() {
        val changed = lines.map { if (it.index == 8) TailLine(8, userLine("something else")) else it }
        assertEquals(Result.Abort, RewindIndex.compute(view, id { it is UserEntry && it.text == "third" }, changed))
        // not enough prompt lines fetched
        assertEquals(Result.Abort, RewindIndex.compute(view, id { it is UserEntry && it.text == "first" }, lines.drop(5)))
        assertEquals(Result.Abort, RewindIndex.compute(view, "no-such-entry", lines))
        // a voice prompt only needs the class to agree (transcripts may differ in text)
        val voiceText = lines.map { if (it.index == 5) TailLine(5, userLine("[voice] secnd")) else it }
        assertEquals(Result.DropLastN(4), RewindIndex.compute(view, id { it is UserEntry && it.text == "second" }, voiceText))
        // whitespace is normalised for typed prompts
        val spaced = lines.map { if (it.index == 8) TailLine(8, userLine("  third ")) else it }
        assertEquals(Result.DropLastN(1), RewindIndex.compute(view, id { it is UserEntry && it.text == "third" }, spaced))
    }
}
