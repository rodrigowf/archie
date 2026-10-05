package com.assistant.core.conversation

import com.assistant.core.protocol.ContentBlockDto
import com.assistant.core.protocol.MessagePreviewDto
import com.assistant.core.protocol.ServerFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryMergerTest {
    private fun replace(s: ConversationState, vararg m: MessagePreviewDto, start: Int = 0, total: Int = start + m.size) =
        s.input(ConversationInput.HistoryPage(PageMode.REPLACE, page(start, total, *m)))

    private fun prepend(s: ConversationState, vararg m: MessagePreviewDto, start: Int = 0, total: Int) =
        s.input(ConversationInput.HistoryPage(PageMode.PREPEND, page(start, total, *m)))

    // ───────────── §5.1 conversion ─────────────

    @Test
    fun consecutiveAssistantLinesFormOneRunAndWrappersAreNotTurns() {
        val s = replace(
            agent(),
            userLine("Refactor"),
            assistantText("I'll read it."),
            assistantTool("t1", "Read"),
            resultLine("t1", "code"),
            MessagePreviewDto("assistant", "", emptyList()),                    // Claude thinking placeholder
            MessagePreviewDto("assistant", "", listOf(ContentBlockDto("thinking", text = "hmm"))),   // backend O-2
            assistantText("Done."),
            MessagePreviewDto("user", "", listOf(ContentBlockDto("image"))),     // image-only line
        )
        assertShape("U(Refactor):history A[T(I'll read it.) X(t1:done=code) K(hmm) T(Done.)]", s)
        assertTrue(s.history.loaded)
    }

    @Test
    fun classifyUserLine() {
        fun c(t: String) = HistoryMerger.classifyUserLine(t)
        assertEquals(HistoryMerger.UserLine.User("hello", UserOrigin.VOICE), c("[voice] hello"))
        assertEquals(HistoryMerger.UserLine.User("hi there", UserOrigin.VOICE), c("[voice, recording: O1 120-2400ms] hi there"))
        assertEquals(HistoryMerger.UserLine.User("say", UserOrigin.AUDIO), c("[audio:webm] say"))
        assertEquals(HistoryMerger.UserLine.User("", UserOrigin.AUDIO), c("[audio:wav]"))
        assertEquals(HistoryMerger.UserLine.User("", UserOrigin.AUDIO), c("[audio:wav] (audio message)"))  // VM-1
        assertEquals(HistoryMerger.UserLine.User("[shared file] a.pdf (1 KB)", UserOrigin.INJECT), c("[shared file] a.pdf (1 KB)"))
        assertEquals(HistoryMerger.UserLine.User("[shared text]\nx", UserOrigin.INJECT), c("[shared text]\nx"))
        assertEquals(HistoryMerger.UserLine.Notice(NoticeKind.INTERRUPTED, ""), c("[Request interrupted by user for tool use]"))
        val summary = "This session is being continued from a previous conversation that ran out of context."
        assertEquals(HistoryMerger.UserLine.Notice(NoticeKind.COMPACTION, summary), c(summary))
        assertEquals(HistoryMerger.UserLine.Notice(NoticeKind.BACKGROUND, "<task-notification>x"), c("<task-notification>x"))
        for (tag in listOf("command-name", "command-message", "command-args", "local-command-stdout", "local-command-stderr", "local-command-caveat")) {
            assertEquals(NoticeKind.COMMAND, (c("<$tag>/help</$tag>") as HistoryMerger.UserLine.Notice).kind)
        }
        assertEquals(HistoryMerger.UserLine.User("[voice]no space", UserOrigin.HISTORY), c("[voice]no space"))
        assertEquals(HistoryMerger.UserLine.User("plain <command-name>", UserOrigin.HISTORY), c("plain <command-name>"))
    }

    @Test
    fun tc2_historyToolsWithoutResultsAreNeverDone() {
        // not in a turn: the tail tool did not finish (session died mid-tool)
        assertShape("U(q):history A[X(t1:no_result)]", replace(agent(), userLine("q"), assistantTool("t1")))
        // in a turn: the tail run's tool may still run
        val busy = agent().input(ConversationInput.PoolStatus(com.assistant.core.model.LiveStatus.TOOL_USE))
        assertShape("U(q):history A[X(t0:no_result)] U(r):history A[X(t1:running)]", replace(busy, userLine("q"), assistantTool("t0"), userLine("r"), assistantTool("t1")))
    }

    // ───────────── ids ─────────────

    @Test
    fun historyIdsAreDerivedFromTheAbsoluteIndexAndStable() {
        val msgs = arrayOf(userLine("a"), assistantText("b"), assistantTool("t1"))
        val one = replace(agent(), *msgs, start = 10)
        val two = replace(replace(agent(), userLine("x")), *msgs, start = 10)
        assertEquals(listOf("h:sdk-1:10", "h:sdk-1:11"), one.entries.map { it.id })
        assertEquals(one.entries, two.entries)
        val run = one.entries[1] as AssistantEntry
        assertEquals(listOf("h:sdk-1:11:0", "h:sdk-1:12:0"), run.blocks.map { it.id })
    }

    @Test
    fun replaceKeepsTheLiveIdsOfContentAlreadyOnScreen() {
        val live = agent().send("hi").on(processing(), ServerFrame.TextComplete("Hello"), toolUse("t1"), toolResult("t1", "x"), turnComplete())
        val ids = live.entries.map { it.id }
        val blockIds = (live.entries[1] as AssistantEntry).blocks.map { it.id }
        val reloaded = replace(live, userLine("hi"), assistantText("Hello"), assistantTool("t1"), resultLine("t1", "x"), assistantText("More from REST"))
        assertShape("U(hi):history A[T(Hello) X(t1:done=x) T(More from REST)]", reloaded)
        assertEquals(ids, reloaded.entries.map { it.id })
        val newBlocks = (reloaded.entries[1] as AssistantEntry).blocks.map { it.id }
        assertEquals(blockIds, newBlocks.take(2))
        assertTrue(newBlocks[2].startsWith("h:"))
        assertEquals("tools index follows the carried ids", reloaded.entries[1].id, reloaded.tools["t1"])
    }

    @Test
    fun prependNeverChangesExistingIdsAndMergesTheSplitRun() {
        val newest = replace(agent(), resultLine("t1", "out"), assistantText("tail"), userLine("next"), start = 3, total = 6)
        val before = newest.entries.map { it.id }
        val p = prepend(newest, userLine("q"), assistantText("head"), assistantTool("t1"), start = 0, total = 6)
        assertShape("U(q):history A[T(head) X(t1:done=out) T(tail)] U(next):history", p)
        assertEquals(before, p.entries.drop(1).map { it.id })                 // the merged run keeps the newer id
        assertEquals(p.entries[1].id, p.tools["t1"])
        assertEquals(0, p.history.startIndex)
        // live results find the merged card
        assertShape("U(q):history A[T(head) X(t1:done=out2) T(tail)] U(next):history", p.on(toolResult("t1", "out2")))
    }

    @Test
    fun prependDoesNotDisturbLiveBookkeeping() {
        val live = replace(agent(), userLine("q"), start = 5, total = 6).send("typed")
        assertTrue(live.promptSinceTurnEnd)
        val p = prepend(live, userLine("older"), start = 4, total = 6)
        assertTrue("a prepend must not reset promptSinceTurnEnd", p.promptSinceTurnEnd)
        assertShape("U(older):history U(q):history U(typed)", p)
        // a running Gemini transcript stays open across a prepend, and the anchor shifts with the page
        val o = orchestrator(voiceActive = true).on(
            voice("""{"type":"input_audio_buffer.speech_started"}"""),
            voice("""{"serverContent":{"inputTranscription":{"text":"Hel"}}}"""),
        )
        val op = prepend(o, userLine("old"), start = 0, total = 1)
            .on(voice("""{"serverContent":{"inputTranscription":{"text":"lo"}}}"""))
        assertShape("U(old):history U(Hello):voice~", op)
    }

    // ───────────── §5.4 overlap dedupe ─────────────

    @Test
    fun dedupeLooksOnlyAtTheTailRun() {
        val h = replace(agent(), userLine("q1"), assistantText("Same."), userLine("q2"), assistantText("Other."))
        // identical text in an OLDER run is not touched; the tail run already has "Other."
        val s = h.on(ServerFrame.TextComplete("Same."), ServerFrame.TextDelta("Oth"), ServerFrame.TextComplete("Other."))
        assertShape("U(q1):history A[T(Same.)] U(q2):history A[T(Other.) T(Same.)]", s)
    }

    @Test
    fun implicitlyClosedDuplicateCompleteIsSkipped() {
        // deltas closed by a tool, then a late complete of the same text: not duplicated
        val s = agent().send("go").on(processing(), ServerFrame.TextDelta("Look."), toolUse("t1"), ServerFrame.TextComplete("Look."))
        assertShape("U(go) A[T(Look.) X(t1:running)]", s)
    }

    @Test
    fun replaceResetsSideCollections() {
        val s = agent().on(toolResult("o", "x"), ServerFrame.ToolResult("", kotlinx.serialization.json.JsonPrimitive("u"), false))
        assertTrue(s.orphanResults.isNotEmpty() && s.unattributed.isNotEmpty())
        val r = replace(s, userLine("q"))
        assertTrue(r.orphanResults.isEmpty() && r.unattributed.isEmpty())
        assertNotEquals(s.entries, r.entries)
    }
}
