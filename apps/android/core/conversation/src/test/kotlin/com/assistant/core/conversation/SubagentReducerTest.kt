package com.assistant.core.conversation

import com.assistant.core.protocol.ProtocolCodec
import com.assistant.core.protocol.ServerFrame
import com.assistant.core.protocol.SubagentSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 12 §4.8 rules the shared fixtures do not reach: SA-3 snapshot and stop, SA-5 failure, LRU
 * and stale pages, SA-6 nested spawn cards inside an open transcript, SA-8 list effect, SA-9 codec.
 */
class SubagentReducerTest {
    private val spawn = """{"subagent_type":"Explore","description":"Find it","prompt":"p"}"""

    private fun started(id: String, parent: String? = null, bg: Boolean = false) =
        ServerFrame.SubagentStarted(id, parent, "Explore", "Find it", bg, "2026-10-12T12:00:01Z")

    private fun inner(id: String, f: ServerFrame) = ServerFrame.SubagentEvent(id, f)

    private fun running(vararg ids: String): ConversationState {
        var s = agent().send("go").on(processing())
        for (id in ids) s = s.on(toolUse(id, "Agent", spawn), started(id))
        return s
    }

    @Test
    fun sa1_subagentFramesNeverTouchTheParent() {
        val s = running("t1")
        val after = s.on(
            inner("t1", toolUse("g1", "Grep")),
            inner("t1", toolResult("g1", "x")),
            inner("t1", ServerFrame.TextDelta("thinking aloud")),
            ServerFrame.SubagentProgress("t1", "Grep", null),
        )
        assertEquals(s.entries, after.entries)
        assertEquals(s.tools, after.tools)
        assertEquals(s.status, after.status)
        assertEquals(s.inTurn, after.inTurn)
        assertEquals(s.counters, after.counters)
        assertEquals("Grep", after.subagents["t1"]!!.activity)
        assertNull("unopened: summary only (SA-5)", after.subagents["t1"]!!.conv)
    }

    @Test
    fun sa3_snapshotIsAuthoritativeOverTheSpawnCard() {
        val snap = ServerFrame.SessionStarted(
            "L1",
            subagents = listOf(SubagentSummary("t1", null, "general-purpose", "Digest logs", true, "running")),
        )
        val s = agent().on(snap).send("go").on(processing(), toolUse("t1", "Agent", spawn), toolResult("t1", "Async agent launched"))
        val a = s.subagents["t1"]!!
        assertEquals(SubagentStatus.RUNNING, a.status)
        assertTrue(a.background)
        assertTrue(a.lifecycle)
    }

    @Test
    fun sa3_stopAndTerminationStopRunningAgents() {
        val s = running("t1", "t2").on(ServerFrame.SubagentFinished("t2", "done", "ok", null, "x"))
            .on(ServerFrame.SessionTerminated("subprocess_crashed"))
        assertEquals(SubagentStatus.STOPPED, s.subagents["t1"]!!.status)
        assertEquals(SubagentStatus.DONE, s.subagents["t2"]!!.status)
    }

    @Test
    fun sa5_failedFetchReleasesHeldFramesAndShowsTheError() {
        var s = running("t1").input(ConversationInput.OpenSubagent("t1"))
        assertTrue(s.subagents["t1"]!!.loading)
        s = s.on(inner("t1", toolUse("g1", "Grep"))).input(ConversationInput.SubagentPageFailed("t1", "boom"))
        val a = s.subagents["t1"]!!
        assertFalse(a.loading)
        assertEquals("boom", a.historyError)
        assertShape("A[X(g1:running)]", a.conv!!)
        assertTrue(a.conv!!.history.loaded)
    }

    @Test
    fun sa5_openEmitsTheFetchOnceAndAStalePageIsIgnored() {
        val s = running("t1")
        assertEquals(listOf<ConversationEffect>(ConversationEffect.FetchSubagentPage("t1")), s.effectsOf(ConversationInput.OpenSubagent("t1")))
        val open = s.input(ConversationInput.OpenSubagent("t1"), ConversationInput.SubagentPage("t1", page(0, 1, userLine("Find it"))))
        assertTrue(open.effectsOf(ConversationInput.OpenSubagent("t1")).isEmpty())
        val live = open.on(inner("t1", ServerFrame.TextComplete("live")))
        val stale = live.input(ConversationInput.SubagentPage("t1", page(0, 1, userLine("other"))))
        assertEquals(live, stale)
        assertShape("U(Find it):history A[T(live)]", live.subagents["t1"]!!.conv!!)
    }

    @Test
    fun sa5_keepsAtMostThreeTranscriptsLeastRecentFirstOut() {
        var s = running("a", "b", "c", "d")
        for (id in listOf("a", "b", "c")) s = s.input(ConversationInput.OpenSubagent(id))
        s = s.input(ConversationInput.OpenSubagent("a"), ConversationInput.OpenSubagent("d"))
        assertEquals(listOf("d", "a", "c"), s.subagentLru)
        assertFalse("b was the least recently viewed", s.subagents["b"]!!.open)
        assertTrue(s.subagents["a"]!!.open && s.subagents["c"]!!.open && s.subagents["d"]!!.open)
        s = s.input(ConversationInput.ReleaseSubagent("c"))
        assertEquals(listOf("d", "a"), s.subagentLru)
        assertNull(s.subagents["c"]!!.conv)
    }

    @Test
    fun sa6_spawnCardInsideAnOpenTranscriptIsANestedPlaceholder() {
        val s = running("t1").input(ConversationInput.OpenSubagent("t1"), ConversationInput.SubagentPage("t1", page(0, 0)))
            .on(inner("t1", toolUse("n1", "Agent", """{"subagent_type":"worker","description":"Sub"}""")))
        assertEquals(listOf("t1", "n1"), s.subagents.keys.toList())
        val n = s.subagents["n1"]!!
        assertEquals("t1", n.parentToolUseId)
        assertEquals("worker", n.agentType)
        assertTrue("the nested card lives in t1's transcript, not the parent's", s.tools["n1"] == null)
        // no lifecycle frames: it follows its card in t1's transcript (SA-2)
        val done = s.on(inner("t1", toolResult("n1", "sub report")))
        assertEquals(SubagentStatus.DONE, done.subagents["n1"]!!.status)
        assertEquals("sub report", done.subagents["n1"]!!.summary)
    }

    @Test
    fun sa4_finishedEndsTheOpenTranscriptsTurn() {
        val s = running("t1").input(ConversationInput.OpenSubagent("t1"), ConversationInput.SubagentPage("t1", page(0, 0)))
            .on(inner("t1", toolUse("g1", "Grep")), ServerFrame.SubagentFinished("t1", "failed", "timeout", null, "x"))
        val conv = s.subagents["t1"]!!.conv!!
        assertFalse(conv.inTurn)
        assertShape("A[X(g1:no_result)]", conv)
    }

    @Test
    fun sa8_listIsFetchedOnceAfterAHistoryPageWithSpawnCards() {
        val p = page(0, 2, assistantTool("t0", "Task", spawn), resultLine("t0", "report"))
        val first = ConversationReducer.step(agent(), ConversationInput.HistoryPage(PageMode.REPLACE, p))
        assertEquals(listOf<ConversationEffect>(ConversationEffect.FetchSubagentList), first.effects)
        assertTrue(ConversationReducer.step(first.state, ConversationInput.HistoryPage(PageMode.REPLACE, p)).effects.isEmpty())
        val rows = first.state.input(ConversationInput.SubagentList(listOf(SubagentSummary("t0", activity = "Read", status = "done", summary = "full report"))))
        assertEquals("full report", rows.subagents["t0"]!!.summary)
        assertTrue(rows.subagents["t0"]!!.lifecycle)
    }

    @Test
    fun sa7_taskNotificationIsABackgroundNoticeAndKeepsTheTray() {
        val s = running("t1").send("queued").on(ServerFrame.UserMessage("<task-notification>x</task-notification>", source = "task_notification"))
        assertEquals(NoticeKind.BACKGROUND, (s.entries.last() as NoticeEntry).notice)
        assertEquals(1, s.queue.size)
    }

    @Test
    fun orchestratorConversationsHaveNoSubagents() {
        val s = orchestrator().on(ServerFrame.Status("streaming"), toolUse("x", "Task", spawn))
        assertTrue(s.subagents.isEmpty())
    }

    @Test
    fun codecDecodesTheRestListAndStopFrame() {
        val rows = ProtocolCodec.decodeSubagentList(
            obj("""{"subagents":[{"tool_use_id":"t1","agent_type":"Explore","usage":{"tokens":1200.0,"tool_uses":2,"duration_ms":null}},{"x":1}]}"""),
        )
        assertEquals(1, rows.size)
        assertEquals(1200L, rows[0].usage!!.tokens)
        assertEquals("""{"type":"stop_subagent","tool_use_id":"t1"}""", ProtocolCodec.encodeClient(com.assistant.core.protocol.ClientFrame.StopSubagent("t1")))
    }
}
