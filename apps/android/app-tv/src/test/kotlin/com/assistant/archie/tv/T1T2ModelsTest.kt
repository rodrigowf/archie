package com.assistant.archie.tv

import com.assistant.archie.feature.chat.InlineCardUi
import com.assistant.archie.feature.chat.model.ChatItem
import com.assistant.archie.tv.conversation.TItem
import com.assistant.archie.tv.conversation.TranscriptModel
import com.assistant.archie.tv.conversations.ConversationRouting
import com.assistant.archie.tv.conversations.ConversationsModel
import com.assistant.archie.tv.conversations.PreviewLine
import com.assistant.archie.tv.conversations.ViewRef
import com.assistant.archie.tv.home.HomeModel
import com.assistant.archie.tv.home.TextPreview
import com.assistant.archie.tv.shell.Conversation
import com.assistant.archie.tv.shell.MemoryDoc
import com.assistant.archie.tv.theme.SessionState
import com.assistant.core.conversation.AgentApproval
import com.assistant.core.conversation.AssistantEntry
import com.assistant.core.conversation.ConversationState
import com.assistant.core.conversation.PermissionBlock
import com.assistant.core.conversation.TextBlock
import com.assistant.core.data.ContentStamp
import com.assistant.core.data.ConversationKey
import com.assistant.core.data.LoadState
import com.assistant.core.model.HarnessProvider
import com.assistant.core.model.LiveStatus
import com.assistant.core.model.MemoryNode
import com.assistant.core.model.PoolSession
import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
import com.assistant.core.model.SessionSummary
import com.assistant.core.model.VisualInfo
import com.assistant.core.protocol.ContentBlockDto
import com.assistant.core.protocol.MessagePreviewDto
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** T1/T2 pure logic: Home rows, Conversations sections/preview, open rules, the reading model. */
class T1T2ModelsTest {
    private val now = Instant.parse("2026-10-16T22:00:00Z")
    private val zone = ZoneOffset.UTC

    // ───────────────────────── Home ─────────────────────────

    @Test fun homeRowsFromTheGraph() {
        val archie = ConversationState.initial(SessionRef("o1", "s-archie", SessionKind.ORCHESTRATOR, null)).copy(
            entries = persistentListOf(AssistantEntry("a", persistentListOf(TextBlock("t", "First paragraph.\n\n**Done.** It's [on screen](x).")))),
        )
        val visuals = listOf(
            VisualInfo("visualizations/old.html", "u", "Old", null, "2026-10-10T10:00:00Z", 1),
            VisualInfo("visualizations/tv-app/index.html", "u", "", null, "2026-10-16T21:00:00Z", 1),
        )
        val tree = listOf(
            MemoryNode("MEMORY.md", "MEMORY.md", false, null),
            MemoryNode("people", "people", true, listOf(MemoryNode("INDEX.md", "people/INDEX.md", false, null), MemoryNode("a.md", "people/a.md", false, null))),
        )
        val s = HomeModel.state(
            archie = archie,
            items = TvScreenFixtures.openItems,
            lastLine = { w -> if (w.sdkId == "s1") "Running tests" else null },
            visuals = LoadState(visuals),
            changedVisuals = mapOf("visualizations/tv-app/index.html" to ContentStamp(1)),
            tree = LoadState(tree),
            changedMemory = mapOf("people/a.md" to ContentStamp(1), "gone.md" to ContentStamp(2, deleted = true)),
            sessions = LoadState(TvScreenFixtures.recentSummaries),
            now = now,
            zone = zone,
            locale = Locale.US,
        )
        // Hero: the Archie item's title and state, the end of the latest reply, plain.
        assertEquals("Plan the TV app", s.hero.title)
        assertEquals(SessionState.IDLE, s.hero.state)
        assertEquals("Done. It's on screen.", s.hero.lastReply)
        assertEquals("Today 21:14", s.hero.time)
        // Open now: agents only, pool order, status mapped, last line from the view or the item detail.
        assertEquals(listOf("Fix wake word drift", "Solar dashboard", "TV control channel", "Biography, chapter 3"), s.openNow.map { it.title })
        assertEquals(listOf(SessionState.WORKING, SessionState.NEEDS_APPROVAL, SessionState.IDLE, SessionState.IDLE), s.openNow.map { it.state })
        assertEquals("Running tests", s.openNow[0].lastLine)
        assertEquals("Waiting for your approval", s.openNow[1].lastLine)
        // Visuals: newest first, folder pages shown as the folder, changed dot from the live counters.
        assertEquals(listOf("tv-app/", "old.html"), s.visuals.map { it.file })
        assertEquals(listOf(true, false), s.visuals.map { it.changed })
        // Memory: changed this session first (deleted ones skipped), then root files and folder indexes.
        assertEquals(listOf(MemoryDoc("people/a.md"), MemoryDoc("MEMORY.md"), MemoryDoc("people/INDEX.md")), s.memory.map { it.target })
        assertEquals(listOf("A", "Memory index", "People index"), s.memory.map { it.title })
        // Recent: newest first with the apps' day buckets.
        assertEquals(listOf("Plan the TV app", "Remote mic probe results", "Voice on the A300M cut off", "History search eval"), s.recent.map { it.title })
        assertEquals(listOf("Today 21:14", "Today 16:20", "Yesterday", "Tue"), s.recent.map { it.time })
    }

    @Test fun starredVisualsGetTheirOwnHomeRow() {
        val visuals = listOf(
            VisualInfo("visualizations/old.html", "u", "Old", null, "2026-10-10T10:00:00Z", 1),
            VisualInfo("visualizations/pinned.html", "u", "Pinned", null, "2026-10-12T10:00:00Z", 1, favorite = true),
            VisualInfo("visualizations/tv-app/index.html", "u", "", null, "2026-10-16T21:00:00Z", 1),
        )
        val s = HomeModel.state(null, emptyList(), { null }, LoadState(visuals), emptyMap(), LoadState(), emptyMap(), LoadState(), now, zone)
        assertEquals(listOf("Pinned"), s.starred.map { it.title })
        // A starred page is not repeated in the Visuals row, which keeps its newest-first order.
        assertEquals(listOf("tv-app/", "old.html"), s.visuals.map { it.file })
    }

    @Test fun heroWithoutArchie() {
        val s = HomeModel.state(null, emptyList(), { null }, LoadState(), emptyMap(), LoadState(), emptyMap(), LoadState(), now, zone)
        assertFalse(s.hero.hasConversation)
        assertTrue(s.openNow.isEmpty())
    }

    @Test fun textPreview() {
        assertEquals("Last one with code.", TextPreview.lastParagraph("# Title\n\nFirst.\n\n- Last one with `code`.\n\n```\nfence\n```"))
        assertEquals("a b link", TextPreview.plain("## a\n*b* [link](http://x)"))
    }

    // ───────────────────────── Conversations ─────────────────────────

    @Test fun sectionsOpenFirstThenDaysAndSearch() {
        val history = TvScreenFixtures.recentSummaries + SessionSummary("h9", null, "2026-09-01T10:00:00Z", "Old one", 3, false, HarnessProvider.CLAUDE, null)
        val sections = ConversationsModel.sections(TvScreenFixtures.openItems, history, "", now, zone, Locale.US)
        assertEquals(listOf("Open now", "Today", "Yesterday", "Previous 7 days", "Earlier"), sections.map { it.title })
        // The open Archie conversation is not repeated in history (the apps' rule).
        assertTrue(sections.drop(1).flatMap { it.rows }.none { it.sdkId == "s-archie" })
        assertEquals("Archie · idle", ConversationsModel.metaOf(sections[0].rows[0]))
        assertEquals("Codex · needs approval", ConversationsModel.metaOf(sections[0].rows[2]))
        assertEquals("Claude · 16:20", ConversationsModel.metaOf(sections[1].rows[0]))
        // Search: every word must appear in the title.
        val found = ConversationsModel.sections(TvScreenFixtures.openItems, history, "mic probe", now, zone, Locale.US)
        assertEquals(listOf("Remote mic probe results"), found.flatMap { it.rows }.map { it.title })
    }

    @Test fun previewFoldsToolRunsIntoSteps() {
        fun tool(name: String, cmd: String? = null) = ContentBlockDto("tool_use", toolName = name, toolInput = JsonObject(cmd?.let { mapOf("command" to JsonPrimitive(it)) } ?: emptyMap()))
        val msgs = listOf(
            MessagePreviewDto("user", blocks = listOf(ContentBlockDto("text", text = "Record a WAV."))),
            MessagePreviewDto("assistant", blocks = listOf(ContentBlockDto("text", text = "Armed."), tool("Bash", "adb pull x"), tool("Bash", "whisper y"))),
            MessagePreviewDto("user", blocks = listOf(ContentBlockDto("tool_result", text = "ok"))),
            MessagePreviewDto("assistant", blocks = listOf(tool("Read"), ContentBlockDto("text", text = "Heard it."))),
        )
        val lines = ConversationsModel.previewLines(msgs, 6)
        assertEquals(
            listOf(PreviewLine.User("Record a WAV."), PreviewLine.Assistant("Armed."), PreviewLine.Steps(3, listOf("adb", "whisper", "Read")), PreviewLine.Assistant("Heard it.")),
            lines,
        )
        assertEquals("adb ×2, whisper", ConversationsModel.stepsSummary(listOf("adb", "whisper", "adb")))
        assertEquals(2, ConversationsModel.previewLines(msgs, 2).size)
    }

    // ───────────────────────── open rules ─────────────────────────

    @Test fun routeResolutionOnlyReattachesAutomatically() {
        val views = listOf(ViewRef(ConversationKey.agent("L1"), "L1", "S1", SessionKind.AGENT))
        val pool = listOf(PoolSession("L2", "S2", LiveStatus.IDLE, 0.0, 3, "t", false), PoolSession("O", "SO", null, 0.0, 1, null, true))
        assertEquals(ConversationRouting.Resolution.Archie, ConversationRouting.resolve(Conversation(archie = true), views, pool))
        assertEquals(ConversationRouting.Resolution.View(ConversationKey.agent("L1")), ConversationRouting.resolve(Conversation("L1", null), views, pool))
        // Re-keyed session (rewind): found by its sdk id.
        assertEquals(ConversationRouting.Resolution.View(ConversationKey.agent("L1")), ConversationRouting.resolve(Conversation("Lx", "S1"), views, pool))
        assertEquals(ConversationRouting.Resolution.Reattach(pool[0]), ConversationRouting.resolve(Conversation("L2", "S2"), views, pool))
        // The orchestrator row is never an agent to attach to; a session in neither waits for the user.
        assertEquals(ConversationRouting.Resolution.NotOpen, ConversationRouting.resolve(Conversation("O", "SO"), views, pool))
        assertEquals(ConversationRouting.Resolution.NotOpen, ConversationRouting.resolve(Conversation("L9", "S9"), views, pool))
    }

    @Test fun historyOpens() {
        val archieRow = SessionSummary("S", null, null, "t", 1, true, null, null)
        val agentRow = archieRow.copy(isOrchestrator = false)
        assertEquals(ConversationRouting.HistoryAction.SHOW_ARCHIE, ConversationRouting.historyAction(archieRow, "S"))
        assertEquals(ConversationRouting.HistoryAction.RESUME_ARCHIE, ConversationRouting.historyAction(archieRow, "other"))
        assertEquals(ConversationRouting.HistoryAction.RESUME_ARCHIE, ConversationRouting.historyAction(archieRow, null))
        assertEquals(ConversationRouting.HistoryAction.OPEN_AGENT, ConversationRouting.historyAction(agentRow, "S"))
        assertEquals(Conversation(archie = true), ConversationRouting.routeOf(SessionRef("x", "y", SessionKind.ORCHESTRATOR, null)))
        assertEquals(Conversation("x", "y"), ConversationRouting.routeOf(SessionRef("x", "y", SessionKind.AGENT, null)))
    }

    // ───────────────────────── reading model ─────────────────────────

    @Test fun tallBlocksScrollInsideBeforeFocusMoves() {
        // Viewport 0..400 px; a page is 320 px.
        assertEquals(320f, TranscriptModel.internalScroll(100f, 1500f, 0f, 400f, down = true)!!, 0.01f)
        assertEquals(60f, TranscriptModel.internalScroll(100f, 460f, 0f, 400f, down = true)!!, 0.01f)
        assertNull(TranscriptModel.internalScroll(100f, 399f, 0f, 400f, down = true))
        assertEquals(-320f, TranscriptModel.internalScroll(-900f, 300f, 0f, 400f, down = false)!!, 0.01f)
        assertEquals(-50f, TranscriptModel.internalScroll(-50f, 300f, 0f, 400f, down = false)!!, 0.01f)
        assertNull(TranscriptModel.internalScroll(10f, 300f, 0f, 400f, down = false))
    }

    @Test fun pagingLandsOnTheMiddleBlock() {
        val placed = listOf(
            TranscriptModel.Placed("a", -200f, 90f),
            TranscriptModel.Placed("b", 100f, 260f),
            TranscriptModel.Placed("c", 270f, 600f),
        )
        assertEquals("b", TranscriptModel.afterPage(placed, "a", 0f, 400f, moved = true, down = true, newest = "c"))
        // The focused block still crossing the middle keeps focus.
        assertEquals("c", TranscriptModel.afterPage(placed, "c", 0f, 600f, moved = true, down = true, newest = "c"))
        // At the end: FF goes to the newest, Rewind to the topmost visible.
        assertEquals("c", TranscriptModel.afterPage(placed, "a", 0f, 400f, moved = false, down = true, newest = "c"))
        assertEquals("a", TranscriptModel.afterPage(placed, "c", 0f, 400f, moved = false, down = false, newest = "c"))
    }

    @Test fun followTheTailButNeverOntoACard() {
        val perm = InlineCardUi.Permission(PermissionBlock("p", "r", "Bash", JsonObject(emptyMap())), answered = false)
        val items = TranscriptModel.items(TvScreenFixtures.conversationItems, listOf(perm), ended = null)
        assertTrue(items.last() is TItem.Card)
        val newest = TranscriptModel.newestFollowable(items)
        assertEquals(TvScreenFixtures.conversationItems.last().key, newest)
        assertEquals("new", TranscriptModel.followTo("old", "old", "new"))
        assertNull(TranscriptModel.followTo("reading", "old", "new"))
        assertNull(TranscriptModel.followTo("old", "old", "old"))
        assertTrue(TranscriptModel.approvalPending(listOf(perm)))
        assertFalse(TranscriptModel.approvalPending(listOf(perm.copy(answered = true))))
        val agentCard = InlineCardUi.AgentApprovalCard(AgentApproval("l", "r", "Bash", JsonObject(emptyMap())), answered = false)
        assertTrue(TranscriptModel.approvalPending(listOf(agentCard)))
        assertEquals("ended", TranscriptModel.items(emptyList(), emptyList(), "This session has ended").single().key)
    }

    @Test fun stepsAreGroupedLikeTheApps() {
        // The fixture runs the apps' flattener: three calls fold into one "3 steps" header.
        val group = TvScreenFixtures.conversationItems.filterIsInstance<ChatItem.ToolGroup>().single()
        assertEquals(3, group.count)
        assertFalse(group.expanded)
        val solo = TvScreenFixtures.conversationItems.filterIsInstance<ChatItem.ToolCard>().single()
        assertEquals(ChatItem.GroupPosition.Solo, solo.position)
        assertTrue(solo.expanded)
    }
}
