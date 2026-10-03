package com.assistant.core.conversation

import com.assistant.core.model.SessionStatus
import com.assistant.core.protocol.ServerFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** One focused test (or a few) per spec 12 §4.1 invariant. RESULT-DELIVERY (I-6) is in [ResultDeliveryTest]. */
class InvariantsTest {

    // ───────────── I-1 tail attachment ─────────────

    @Test
    fun i1_contentGoesIntoTheTailRunOnly() {
        val s1 = agent().send("one").on(processing(), ServerFrame.TextComplete("first"), turnComplete())
        val s2 = s1.send("two").on(processing(), ServerFrame.TextDelta("sec"), toolUse("t1"))
        assertShape("U(one) A[T(first)] U(two) A[T(sec) X(t1:running)]", s2)
        // the earlier run is untouched (same object: structural sharing)
        assertSame(s1.entries[1], s2.entries[1])
    }

    @Test
    fun i1_byIdUpdatesMayTouchEarlierEntries() {
        val s = orchestrator(voiceActive = true)
            .on(toolUse("c1"), voice("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"next"}"""))
            .on(toolResult("c1", "ok"), ServerFrame.ToolExecuting("c1"))
        assertShape("A[X(c1:done=ok)] U(next):voice", s)
    }

    // ───────────── I-2 boundaries ─────────────

    @Test
    fun i2_aNonAssistantEntryEndsTheRun() {
        val s = agent().send("go").on(
            processing(),
            ServerFrame.TextComplete("Step one."),
            ServerFrame.Error("send_failed", "boom"),
        )
        // after a turn failure the next content starts a new run below the notice
        val s2 = s.on(ServerFrame.TextComplete("late"))
        assertShape("U(go) A[T(Step one.)] N:error(boom) A[T(late)]", s2)
    }

    @Test
    fun i2_turnCompleteDoesNotCloseTheRun() {
        // TL-1: content after turn_complete with no entry in between continues the same run
        val s = agent().send("go").on(processing(), ServerFrame.TextComplete("a"), turnComplete(), ServerFrame.TextComplete("b"))
        assertShape("U(go) A[T(a) T(b)]", s)
    }

    // ───────────── I-3 arrival order ─────────────

    @Test
    fun i3_blocksKeepArrivalOrder() {
        val s = agent().send("go").on(
            processing(),
            ServerFrame.ThinkingComplete("think"),
            ServerFrame.TextComplete("before"),
            toolUse("t1"), toolUse("t2"),
            toolResult("t2", "2"), toolResult("t1", "1"),
            ServerFrame.TextComplete("after"),
        )
        assertShape("U(go) A[K(think) T(before) X(t1:done=1) X(t2:done=2) T(after)]", s)
    }

    // ───────────── I-4 delta extension ─────────────

    @Test
    fun i4_deltasExtendOnlyAnOpenBlockOfTheSameTypeAndScope() {
        var s = agent().send("go").on(processing(), ServerFrame.TextDelta("a"), ServerFrame.TextDelta("b"))
        assertShape("U(go) A[T(ab)~]", s)
        s = s.on(ServerFrame.ThinkingDelta("k"))
        assertShape("U(go) A[T(ab) K(k)~]", s)
        val text = (s.entries[1] as AssistantEntry).blocks[0] as TextBlock
        assertTrue("closed by another block", text.implicitlyClosed)
        s = s.on(ServerFrame.TextDelta("c"))
        assertShape("U(go) A[T(ab) K(k) T(c)~]", s)
        // a voice-scope delta never extends a turn-scope block
        val o = orchestrator(voiceActive = true).send("typed")
            .on(ServerFrame.Status("streaming"), ServerFrame.TextDelta("turn"))
            .on(voice("""{"type":"response.audio_transcript.delta","delta":"voice"}"""))
        assertShape("U(typed) A[T(turn) T(voice)~v]", o)
    }

    // ───────────── I-5 complete replaces ─────────────

    @Test
    fun i5_completeReplacesTheDeltas() {
        val s = agent().send("go").on(processing(), ServerFrame.TextDelta("Hel"), ServerFrame.TextDelta("lo"), ServerFrame.TextComplete("Hello!"))
        assertShape("U(go) A[T(Hello!)]", s)
    }

    @Test
    fun i5_continuationAfterAnInterleavedEntryShowsOnlyTheNewPart() {
        // an auto-compaction notice splits a streaming block; the complete carries the full text
        val s = agent().send("go").on(
            processing(),
            ServerFrame.TextDelta("Hello "),
            ServerFrame.CompactComplete("auto", "sum"),
            ServerFrame.TextDelta("world"),
            ServerFrame.TextComplete("Hello world"),
        )
        assertShape("U(go) A[T(Hello )] N:compaction(sum) A[T(world)]", s)
    }

    @Test
    fun i5_completeWithoutAnOpenBlockAfterASplit() {
        val base = agent().send("go").on(processing(), ServerFrame.TextDelta("Hello "), ServerFrame.CompactComplete("auto", ""))
        assertShape("U(go) A[T(Hello )] N:compaction() A[T(world)]", base.on(ServerFrame.TextComplete("Hello world")))
        // a complete that adds nothing new does not create an empty block
        assertShape("U(go) A[T(Hello )] N:compaction()", base.on(ServerFrame.TextComplete("Hello ")))
    }

    // ───────────── I-7 no stuck running ─────────────

    @Test
    fun i7_endTurnFinalisesTurnScopeOnly() {
        val s = orchestrator(voiceActive = true)
            .on(toolUse("v1"))                                                  // voice scope (not in a turn)
            .send("typed")
            .on(ServerFrame.Status("streaming"), ServerFrame.TextDelta("x"), toolUse("t1"), ServerFrame.Status("idle"))
        assertShape("A[X(v1:running)] U(typed) A[T(x) X(t1:no_result)]", s)
        assertShape("A[X(v1:no_result)] U(typed) A[T(x) X(t1:no_result)]", s.on(ServerFrame.VoiceEnded("user_stop")))
    }

    @Test
    fun i7_pendingPermissionsExpireAtTurnEnd() {
        val s = agent().send("go").on(processing(), ServerFrame.PermissionRequest("r1", "ExitPlanMode", obj("{}")))
        assertEquals("r1", s.newestPendingPermission()?.requestId)
        val ended = s.on(turnComplete())
        val p = (ended.entries[1] as AssistantEntry).blocks[0] as PermissionBlock
        assertEquals(PermissionState.DENIED, p.state)
        assertEquals("system", p.responder)
        assertEquals("stream ended", p.message)
        assertNull(ended.newestPendingPermission())
    }

    @Test
    fun i7_stopAndTerminateFinaliseBothScopes() {
        val s = orchestrator(voiceActive = true).on(toolUse("v1"), ServerFrame.SessionStopped())
        assertShape("A[X(v1:no_result)]", s)
        assertEquals(SessionStatus.STOPPED, s.status)
        assertFalse(s.voiceActive)
    }

    // ───────────── I-8 seq idempotence ─────────────

    @Test
    fun i8_duplicateSeqFramesAreDropped() {
        val f = ServerFrame.TextDelta("x", seq = 5, streamId = "L1:1")
        val once = agent().send("go").on(processing(), f)
        assertEquals(once, once.on(f))
        // a gap is normal; an older seq is dropped
        val s = once.on(ServerFrame.TextDelta("y", 9, "L1:1"), ServerFrame.TextDelta("z", 7, "L1:1"))
        assertShape("U(go) A[T(xy)~]", s)
        assertEquals(Checkpoint("L1:1", 9), s.checkpoint)
    }

    @Test
    fun i8_newStreamIdReplacesTheCheckpoint() {
        val s = agent().send("go").on(processing(), ServerFrame.TextDelta("x", 50, "L1:1"), ServerFrame.TextDelta("y", 1, "L1:2"))
        assertShape("U(go) A[T(xy)~]", s)
        assertEquals(Checkpoint("L1:2", 1), s.checkpoint)
    }

    @Test
    fun i8_stallNoticesAreExempt() {
        val s = agent().send("go").on(processing(), toolUse("t1").copy(seq = 5, streamId = "L1:1"))
            .on(ServerFrame.SessionStalled(120.0, "Bash", "t1", 5, "L1:1"))
        assertEquals(120.0, s.stall!!.elapsedSeconds!!, 0.0)
        // any later content clears it
        assertNull(s.on(toolResult("t1", "ok")).stall)
    }

    // ───────────── I-9 voice anchor ─────────────

    @Test
    fun i9_lateTranscriptGoesToTheAnchorWithoutSplittingTheRun() {
        val s = orchestrator(voiceActive = true).on(
            voice("""{"type":"input_audio_buffer.speech_started"}"""),
            voice("""{"type":"response.audio_transcript.delta","delta":"Sure"}"""),
            voice("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"Joke?"}"""),
            voice("""{"type":"response.audio_transcript.delta","delta":"!"}"""),
        )
        assertShape("U(Joke?):voice A[T(Sure!)~v]", s)
    }

    @Test
    fun i9_withoutAnAnchorTheTranscriptIsAppended() {
        val s = orchestrator(voiceActive = true).on(
            voice("""{"type":"response.audio_transcript.done","transcript":"Hi."}"""),
            voice("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"Hello"}"""),
            voice("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"   "}"""),
        )
        assertShape("A[T(Hi.)v] U(Hello):voice", s)
    }

    // ───────────── I-10 no empty runs ─────────────

    @Test
    fun i10_inputsThatAddNoBlockNeverCreateEntries() {
        val s = agent().on(
            toolResult("nope", "x"),
            ServerFrame.PermissionResolved("r9", "allow"),
            ServerFrame.ToolExecuting("nope"),
            ServerFrame.Status("retrying"),
            ServerFrame.SessionStalled(1.0),
            turnComplete(),
        )
        assertTrue(s.entries.isEmpty())
    }

    @Test
    fun i10_removingTheOnlyBlockRemovesTheRun() {
        // §5.4: the open block duplicates a history block in the tail run → removed; the run survives
        val hist = agent().input(ConversationInput.HistoryPage(PageMode.REPLACE, page(0, 2, userLine("q"), assistantText("A."))))
        val s = hist.on(ServerFrame.TextDelta("A"), ServerFrame.TextComplete("A."))
        assertShape("U(q):history A[T(A.)]", s)
    }

    // ───────────── I-11 no duplicate cards ─────────────

    @Test
    fun i11_repeatedToolUseUpdatesTheExistingCard() {
        val s = agent().send("go").on(processing(), ServerFrame.ToolUse("t1", "", obj("{}")), ServerFrame.ToolUse("t1", "Bash", obj("""{"command":"ls"}""")))
        assertShape("U(go) A[X(t1:running)]", s)
        val tb = (s.entries[1] as AssistantEntry).blocks[0] as ToolBlock
        assertEquals("Bash", tb.toolName)
        assertEquals(obj("""{"command":"ls"}"""), tb.toolInput)
    }

    @Test
    fun i11_repeatedPermissionRequestIsIgnored() {
        val p = ServerFrame.PermissionRequest("r1", "ExitPlanMode", obj("{}"))
        assertShape("U(go) A[P(r1:pending) T(x)~]", agent().send("go").on(processing(), p, ServerFrame.TextDelta("x"), p))
    }

    // ───────────── I-12 queue ─────────────

    @Test
    fun i12_senderQueueMovesIntoTheTimelineAtDispatch() {
        var s = agent().send("first").on(processing(), ServerFrame.TextDelta("a"))
        s = s.send("second")
        assertEquals(listOf(QueuedPrompt("second", QueueOwner.LOCAL)), s.queue.toList())
        assertShape("U(first) A[T(a)~]", s)
        s = s.on(turnComplete(), processing())
        assertShape("U(first) A[T(a)] U(second)", s)
        assertTrue(s.queue.isEmpty())
    }

    @Test
    fun i12_preO6ReEchoAfterDispatchIsSwallowed() {
        // the observer already moved "second" into the timeline at status{processing}
        var s = agent().on(ServerFrame.UserMessage("first"), processing(), ServerFrame.UserMessage("second", queued = true), turnComplete(), processing())
        assertShape("U(first):echo U(second):echo", s)
        s = s.on(ServerFrame.UserMessage("second"), ServerFrame.TextComplete("reply"))
        assertShape("U(first):echo U(second):echo A[T(reply)]", s)
        assertTrue("the dispatched turn keeps running", s.inTurn)
    }

    @Test
    fun i12_interruptDropsEveryQueuedPrompt() {
        var s = agent().send("a").on(processing(), ServerFrame.UserMessage("remote", queued = true)).send("local")
        s = s.input(ConversationInput.LocalInterrupt)
        assertEquals(listOf(QueuedPrompt("remote", QueueOwner.REMOTE)), s.queue.toList())
        s = s.on(ServerFrame.Status("interrupted"))
        assertTrue(s.queue.isEmpty())
        assertShape("U(a) N:interrupted()", s)
    }

    @Test
    fun i12_observerEchoClearsOlderRemoteTrayItems() {
        val s = agent().on(
            ServerFrame.UserMessage("q1", queued = true), ServerFrame.UserMessage("q2", queued = true), ServerFrame.UserMessage("q2"),
        )
        assertTrue(s.queue.isEmpty())
        assertShape("U(q2):echo", s)
    }

    // ───────────── I-14 focus ─────────────

    @Test
    fun i14_watcherAndRoutingFramesDoNotChangeTheConversation() {
        val s = orchestrator().send("x")
        for (f in listOf(
            ServerFrame.AgentSessionOpened("A1", "S1"),
            ServerFrame.AgentSessionClosed("A1"),
            ServerFrame.NestedSessionEvent("A1", "permission_request", obj("""{"request_id":"r"}""")),
            ServerFrame.Ping(),
            ServerFrame.VoiceAudioOut("AAAA"),
            ServerFrame.VoiceCommand(obj("{}")),
        )) assertEquals(f.type, s, s.on(f))
    }

    // ───────────── I-15 transport errors are not content ─────────────

    @Test
    fun i15_transportFailuresNeverCreateEntries() {
        val s = agent().send("go").on(processing())
        val closed = s.input(ConversationInput.SocketClosed)
        assertEquals(s.entries, closed.entries)
        assertEquals(ConnectionBanner("disconnected"), closed.connectionBanner)
        val reopened = closed.input(ConversationInput.SocketOpened).on(ServerFrame.Error("start_failed", "nope"))
        assertEquals(s.entries, reopened.entries)
        assertEquals(ConnectionBanner("start_failed", "nope"), reopened.connectionBanner)
        assertNull(reopened.input(ConversationInput.DismissBanner).connectionBanner)
        // protocol / voice errors are side errors, not entries
        val e = s.effectsOf(ServerFrame.Error("voice_event_failed", "x"))
        assertEquals(listOf(ConversationEffect.SideError("voice_event_failed", "x")), e)
        assertEquals(s.entries, s.on(ServerFrame.Error("unknown_type"), ServerFrame.Error("voice_config_busy")).entries)
    }
}
