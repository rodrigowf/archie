package com.assistant.core.conversation

import com.assistant.core.protocol.ServerFrame
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** I-6 RESULT-DELIVERY: a tool result is never lost and always shown (spec 12 §4.5, R-1…R-9). */
class ResultDeliveryTest {

    @Test
    fun r1_byIdAcrossUserVoiceNoticeAndInjectEntries() {
        val s = orchestrator(voiceActive = true).on(
            toolUse("a"), toolUse("b"),
            ServerFrame.UserMessage("[shared text]\nx", source = "shared_inject"),
            voice("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"hm"}"""),
            ServerFrame.Error("interrupted"),                                 // no notice (not in a turn) — harmless
            toolResult("b", "B"), toolResult("a", "A"),
        )
        assertShape("A[X(a:done=A) X(b:done=B)] U([shared text]\nx):inject U(hm):voice", s)
    }

    @Test
    fun r1_lateResultUpgradesNoResult() {
        val s = agent().send("go").on(processing(), toolUse("t1"), turnComplete())
        assertShape("U(go) A[X(t1:no_result)]", s)
        assertShape("U(go) A[X(t1:done=late)]", s.on(toolResult("t1", "late")))
    }

    @Test
    fun r2_orphansAreHeldAndAttachLive() {
        var s = orchestrator().send("x").on(ServerFrame.Status("streaming"), toolResult("c5", "early"), toolResult("c9", ""))
        assertEquals(setOf("c5", "c9"), s.orphanResults.keys)
        assertTrue(s.entries.size == 1)                                       // R-8: no entry for results
        s = s.on(toolUse("c5"))
        assertShape("U(x) A[X(c5:done=early)]", s)
        assertEquals(setOf("c9"), s.orphanResults.keys)
    }

    @Test
    fun r2_emptyOrphanNeverReplacesANonEmptyOne() {
        val s = orchestrator().on(toolResult("c1", "real"), toolResult("c1", ""))
        assertEquals("real", s.orphanResults["c1"]!!.output)
        assertEquals("newer", s.on(toolResult("c1", "newer")).orphanResults["c1"]!!.output)
        // an empty one is replaced by a later non-empty one, keeping its insertion position
        val t = orchestrator().on(toolResult("c1", ""), toolResult("c2", "x"), toolResult("c1", "filled"))
        assertEquals(listOf("c1", "c2"), t.orphanResults.keys.toList())
        assertEquals("filled", t.orphanResults["c1"]!!.output)
    }

    @Test
    fun r2_orphanAttachesWhenAnOlderHistoryPageBringsItsToolUse() {
        val s = agent()
            .input(ConversationInput.HistoryPage(PageMode.REPLACE, page(2, 4, resultLine("t1", "out"), assistantText("done"))))
        assertEquals(setOf("t1"), s.orphanResults.keys)
        assertTrue("history orphans stay hidden while hasMore (R-9)", s.reachableOrphans().isEmpty())
        val p = s.input(ConversationInput.HistoryPage(PageMode.PREPEND, page(0, 4, userLine("read"), assistantTool("t1", "Read"))))
        assertShape("U(read):history A[X(t1:done=out) T(done)]", p)
        assertTrue(p.orphanResults.isEmpty())
    }

    @Test
    fun r3_everyOutputShapeReachesTheCard() {
        val s = orchestrator(voiceActive = true).on(
            toolUse("o"), toolUse("n"), toolUse("s"), toolUse("num"),
            ServerFrame.ToolResult("o", obj("""{"provider":"claude","list":[1,"a"]}"""), false),
            ServerFrame.ToolResult("n", JsonNull, null),
            ServerFrame.ToolResult("s", JsonPrimitive("plain"), null),
            ServerFrame.ToolResult("num", JsonPrimitive(42), true),
        )
        assertShape("""A[X(o:done={"provider":"claude","list":[1,"a"]}) X(n:done=) X(s:done=plain) X(num:error=42)]""", s)
    }

    @Test
    fun r3_executingAndProgressNeverCreateCards() {
        val s = orchestrator().on(ServerFrame.ToolExecuting("x"), ServerFrame.ToolProgress("x", null, 5.0, "m"))
        assertTrue(s.entries.isEmpty())
        val t = orchestrator().send("q").on(ServerFrame.Status("streaming"), toolUse("x"), ServerFrame.ToolExecuting("x"), ServerFrame.ToolProgress("x", null, 5.0, "m"))
        val tb = (t.entries[1] as AssistantEntry).blocks[0] as ToolBlock
        assertTrue(tb.executing)
        assertEquals(ToolProgressInfo(5.0, "m"), tb.progress)
        val done = t.on(toolResult("x", "ok"))
        val db = (done.entries[1] as AssistantEntry).blocks[0] as ToolBlock
        assertFalse(db.executing)
    }

    @Test
    fun r4_emptyIdWithOneRunningCardIsInferred() {
        val s = agent().send("go").on(processing(), toolUse("t1"), toolUse("t0"), toolResult("t0", "x"), ServerFrame.ToolResult("", JsonPrimitive("Error: timeout"), false))
        assertShape("U(go) A[X(t1:done=Error: timeout)? X(t0:done=x)]", s)
        assertTrue(s.unattributed.isEmpty())
    }

    @Test
    fun r4_emptyIdIsNeverGuessedAmongSeveral() {
        val s = agent().send("go").on(processing(), toolUse("a"), toolUse("b"), ServerFrame.ToolResult("", JsonPrimitive(""), false))
        assertShape("U(go) A[X(a:running) X(b:running)]", s)
        assertEquals(listOf(PendingResult("", false, ResultOrigin.LIVE)), s.unattributed.toList())
    }

    @Test
    fun r4_historyResultsWithoutIdAreNotInferred() {
        val s = agent().send("go").on(processing(), toolUse("a"))
            .input(ConversationInput.HistoryPage(PageMode.RECONCILE, page(0, 1, resultLine("", "x"))))
        assertShape("U(go) A[X(a:running)]", s)
    }

    @Test
    fun r5_neverDowngrade() {
        val s = agent().send("go").on(processing(), toolUse("t1"), toolResult("t1", "full"), toolResult("t1", ""))
        assertShape("U(go) A[X(t1:done=full)]", s)
    }

    @Test
    fun r5_reconcileOverridesInferredAndEmptyButNotIdMatchedLive() {
        val live = agent().send("go").on(
            processing(), toolUse("a"), ServerFrame.ToolResult("", JsonPrimitive("guess"), false),
            toolUse("b"), toolResult("b", ""), toolUse("c"), toolResult("c", "live c"), turnComplete(),
        )
        assertShape("U(go) A[X(a:done=guess)? X(b:done=) X(c:done=live c)]", live)
        val rec = live.input(
            ConversationInput.HistoryPage(
                PageMode.RECONCILE,
                page(0, 6, resultLine("a", "real a"), resultLine("b", "real b"), resultLine("c", "rest c"), resultLine("zzz", "unknown")),
            ),
        )
        assertShape("U(go) A[X(a:done=real a) X(b:done=real b) X(c:done=live c)]", rec)
        assertEquals(live.entries.map { it.id }, rec.entries.map { it.id })   // reconcile never re-keys
    }

    @Test
    fun r6_noStuckRunningAtEveryEndPath() {
        val running = agent().send("go").on(processing(), toolUse("t1"))
        for (end in listOf<ServerFrame>(
            turnComplete(), ServerFrame.Status("interrupted"), ServerFrame.Error("send_failed"),
            ServerFrame.SessionStopped(), ServerFrame.SessionTerminated("subprocess_lost"), processing(),
        )) {
            val s = running.on(end)
            val tb = s.entries.filterIsInstance<AssistantEntry>().flatMap { it.blocks }.filterIsInstance<ToolBlock>().single()
            assertEquals("after ${end.type}", ToolStatus.NO_RESULT, tb.status)
        }
        // voice: every end path, owner or passive (VT-3)
        val v = orchestrator(voiceActive = true).on(toolUse("v"))
        for (end in listOf<ServerFrame>(ServerFrame.VoiceEnded("x"), ServerFrame.VoiceStopped(), ServerFrame.VoiceOwnerActive(false))) {
            assertShape("A[X(v:no_result)]", v.on(end))
        }
        assertShape("A[X(v:no_result)]", v.input(ConversationInput.VoiceLocalEnd))
    }

    @Test
    fun r7_reconcileIsRequestedOnlyWhenLiveDeliveryWasIncomplete() {
        val clean = agent().send("go").on(processing(), toolUse("t1"), toolResult("t1", "ok"))
        assertFalse(ConversationEffect.ScheduleReconcile in clean.effectsOf(turnComplete()))
        assertTrue(ConversationEffect.TurnEnded in clean.effectsOf(turnComplete()))

        val noResult = agent().send("go").on(processing(), toolUse("t1"))
        assertTrue(ConversationEffect.ScheduleReconcile in noResult.effectsOf(turnComplete()))

        val inferred = agent().send("go").on(processing(), toolUse("t1"), ServerFrame.ToolResult("", JsonPrimitive("x"), false))
        assertTrue(ConversationEffect.ScheduleReconcile in inferred.effectsOf(turnComplete()))

        val orphan = agent().send("go").on(processing(), toolResult("zz", "x"))
        assertTrue(ConversationEffect.ScheduleReconcile in orphan.effectsOf(turnComplete()))

        // needs an sdkId; never for the orchestrator (no REST tool results there)
        val noSdk = agent(sdkId = null).send("go").on(processing(), toolUse("t1"))
        assertFalse(ConversationEffect.ScheduleReconcile in noSdk.effectsOf(turnComplete(sessionId = null)))
        val orch = orchestrator().send("go").on(ServerFrame.Status("streaming"), toolUse("t1"))
        assertFalse(ConversationEffect.ScheduleReconcile in orch.effectsOf(ServerFrame.Status("idle")))

        // the flag is per turn
        val next = noResult.on(turnComplete()).send("again").on(processing(), ServerFrame.TextComplete("fine"))
        assertFalse(ConversationEffect.ScheduleReconcile in next.effectsOf(turnComplete()))
    }

    @Test
    fun r8_resultsNeverCreateEntriesOrBlocks() {
        val s = agent().send("go").on(processing(), ServerFrame.TextComplete("a"))
        val after = s.on(toolResult("x", "1"), ServerFrame.ToolResult("", JsonPrimitive("2"), false), toolResult("y", ""))
        assertEquals(s.entries, after.entries)
    }

    @Test
    fun r9_liveOrphansAreReachableHistoryOnesOnceAllPagesAreLoaded() {
        val s = agent().on(toolResult("live", "x"))
        assertEquals(setOf("live"), s.reachableOrphans().keys)
        val h = agent().input(ConversationInput.HistoryPage(PageMode.REPLACE, page(0, 1, resultLine("old", "y"))))
        assertEquals(setOf("old"), h.reachableOrphans().keys)                 // has_more = false
    }
}
