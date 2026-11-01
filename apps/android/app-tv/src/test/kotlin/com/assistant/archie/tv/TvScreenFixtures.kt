package com.assistant.archie.tv

import com.assistant.archie.feature.chat.ChatListFlattener
import com.assistant.archie.feature.chat.ComposerUi
import com.assistant.archie.feature.chat.ConversationUiState
import com.assistant.archie.feature.chat.FlattenOptions
import com.assistant.archie.feature.chat.InlineCardUi
import com.assistant.archie.feature.chat.MarkdownStore
import com.assistant.archie.feature.chat.model.AgentUi
import com.assistant.archie.feature.chat.model.AgentsUi
import com.assistant.archie.feature.chat.model.ChatItem
import com.assistant.archie.feature.chat.ui.CatalogToolCardRenderer
import com.assistant.archie.feature.sessions.SessionTarget
import com.assistant.archie.feature.toolcards.AgentRun
import com.assistant.archie.feature.toolcards.AgentRunStatus
import com.assistant.archie.tv.conversation.ConversationHeaderUi
import com.assistant.archie.tv.conversations.ConvRef
import com.assistant.archie.tv.conversations.ConvRowUi
import com.assistant.archie.tv.conversations.ConvSectionUi
import com.assistant.archie.tv.conversations.OpenTarget
import com.assistant.archie.tv.conversations.PreviewLine
import com.assistant.archie.tv.conversations.PreviewUi
import com.assistant.archie.tv.home.AgentCardUi
import com.assistant.archie.tv.home.HeroUi
import com.assistant.archie.tv.home.HomeUiState
import com.assistant.archie.tv.home.MemoryCardUi
import com.assistant.archie.tv.home.RecentCardUi
import com.assistant.archie.tv.home.VisualCardUi
import com.assistant.archie.tv.shell.MemoryDoc
import com.assistant.archie.tv.shell.Viewer
import com.assistant.archie.tv.theme.SessionState
import com.assistant.archie.tv.visuals.VisualKind
import com.assistant.core.conversation.AgentApproval
import com.assistant.core.conversation.AssistantEntry
import com.assistant.core.conversation.ConversationState
import com.assistant.core.conversation.SubagentStatus
import com.assistant.core.conversation.TextBlock
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.ToolStatus
import com.assistant.core.conversation.UserEntry
import com.assistant.core.conversation.UserOrigin
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.data.ItemKey
import com.assistant.core.data.ItemKind
import com.assistant.core.data.TabStatus
import com.assistant.core.data.WorkspaceItem
import com.assistant.core.model.HarnessProvider
import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
import com.assistant.core.model.SessionStatus
import com.assistant.core.model.SessionSummary
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** State fixtures of the T1/T2 screens (mockups 3, 3b, 5, 6), shared by goldens and JVM tests. */
object TvScreenFixtures {
    private fun summary(sdk: String, title: String, archie: Boolean, provider: HarnessProvider? = null, at: String = "2026-10-16T16:20:00Z", n: Int = 12) =
        SessionSummary(sdk, at, at, title, n, archie, provider, null)

    private fun item(local: String, sdk: String, title: String, provider: HarnessProvider, status: TabStatus, detail: String) = WorkspaceItem(
        key = ItemKey.Agent(com.assistant.core.data.ConversationKey.agent(local)),
        kind = ItemKind.AGENT,
        title = title,
        provider = provider,
        status = status,
        detail = detail,
        localId = local,
        sdkId = sdk,
    )

    val openItems = listOf(
        WorkspaceItem(ItemKey.Archie, ItemKind.ARCHIE, "Plan the TV app", status = TabStatus.IDLE, detail = "Archie · ready", localId = "o1", sdkId = "s-archie"),
        item("a1", "s1", "Fix wake word drift", HarnessProvider.CLAUDE, TabStatus.WORKING, "Working…"),
        item("a2", "s2", "Solar dashboard", HarnessProvider.CODEX, TabStatus.NEEDS_YOU, "Waiting for your approval"),
        item("a3", "s3", "TV control channel", HarnessProvider.CLAUDE, TabStatus.IDLE, "Ready · 9 turns"),
        item("a4", "s4", "Biography, chapter 3", HarnessProvider.QWEN, TabStatus.IDLE, "Ready · 4 turns"),
    )

    private fun convRef(w: WorkspaceItem) = ConvRef.of(w)
    private fun histRef(s: SessionSummary) = ConvRef(OpenTarget.History(s), SessionTarget.of(s), null)

    val recentSummaries = listOf(
        summary("s-archie", "Plan the TV app", true, at = "2026-10-16T21:14:00Z", n = 48),
        summary("h1", "Remote mic probe results", false, HarnessProvider.CLAUDE, "2026-10-16T16:20:00Z", 48),
        summary("h2", "Voice on the A300M cut off", true, at = "2026-10-15T22:40:00Z", n = 31),
        summary("h3", "History search eval", false, HarnessProvider.GEMINI, "2026-10-13T10:00:00Z", 77),
    )

    val home = HomeUiState(
        hero = HeroUi(
            hasConversation = true,
            title = "Plan the TV app",
            state = SessionState.IDLE,
            stateLabel = "Idle",
            time = "Today 21:14",
            lastReply = "The spec draft is in docs/specs/15-tv-app.md. Next I'll split it into work packages and start the backend hub with the tests.",
        ),
        openNow = listOf(
            AgentCardUi("open:a1", convRef(openItems[1]), "Claude", "Fix wake word drift", SessionState.WORKING, "Running :core:wakeword:test…"),
            AgentCardUi("open:a2", convRef(openItems[2]), "Codex", "Solar dashboard", SessionState.NEEDS_APPROVAL, "Wants to run npm run build"),
            AgentCardUi("open:a3", convRef(openItems[3]), "Claude", "TV control channel", SessionState.IDLE, "Done. 14 tests pass."),
            AgentCardUi("open:a4", convRef(openItems[4]), "Qwen", "Biography, chapter 3", SessionState.IDLE, "Draft saved."),
        ),
        visuals = listOf(
            VisualCardUi("viz:a", "Solar dashboard", "solar-dashboard.html", "updated 5 min ago", true, Viewer("visualizations/solar-dashboard.html"), VisualKind.DASHBOARD),
            VisualCardUi("viz:b", "Archie TV plan", "tv-app/", "updated 1 h ago", false, Viewer("visualizations/tv-app/index.html"), VisualKind.PROJECT),
            VisualCardUi("viz:c", "Wake word timeline", "wakeword-timeline.html", "updated yesterday", false, Viewer("visualizations/wakeword-timeline.html"), VisualKind.EXPLAINER),
            VisualCardUi("viz:d", "Tarot app screens", "tarot-review/", "updated 2 days ago", false, Viewer("visualizations/tarot-review/index.html"), VisualKind.REVIEW),
        ),
        memory = listOf(
            MemoryCardUi("mem:a", "Fire tv", "archie/devices", "Changed while you watched", MemoryDoc("archie/devices/fire-tv.md")),
            MemoryCardUi("mem:b", "Rodrigo personal context", "people/rodrigo", "Start here", MemoryDoc("people/rodrigo/rodrigo_personal_context.md")),
            MemoryCardUi("mem:c", "Projects index", "projects", "Start here", MemoryDoc("projects/INDEX.md")),
        ),
        recent = listOf(
            RecentCardUi("recent:s-archie", histRef(recentSummaries[0]), true, null, "Plan the TV app", "Today 21:14", "48 messages"),
            RecentCardUi("recent:h1", histRef(recentSummaries[1]), false, "Claude", "Remote mic probe results", "Today 16:20", "48 messages"),
            RecentCardUi("recent:h2", histRef(recentSummaries[2]), true, null, "Voice on the A300M cut off", "Yesterday", "31 messages"),
            RecentCardUi("recent:h3", histRef(recentSummaries[3]), false, "Gemini", "History search eval", "Tue", "77 messages"),
        ),
    )

    // ───────────────────────── Conversations ─────────────────────────

    private fun row(w: WorkspaceItem, archie: Boolean, who: String, state: SessionState) =
        ConvRowUi("open:${w.key}", convRef(w), w.title, who, state, "", null)

    private fun hrow(s: SessionSummary, time: String) =
        ConvRowUi("h:${s.sdkId}", histRef(s), s.title, if (s.isOrchestrator) "Archie" else s.provider!!.label, null, time, s.messageCount)

    val focusedRow = hrow(recentSummaries[1], "16:20")

    val sections = listOf(
        ConvSectionUi(
            "Open now",
            listOf(
                row(openItems[0], true, "Archie", SessionState.IDLE),
                row(openItems[1], false, "Claude", SessionState.WORKING),
                row(openItems[2], false, "Codex", SessionState.NEEDS_APPROVAL),
            ),
        ),
        ConvSectionUi("Today", listOf(focusedRow, hrow(summary("h5", "What's on the calendar tomorrow", true, at = "2026-10-16T08:05:00Z", n = 6), "08:05"))),
        ConvSectionUi("Yesterday", listOf(hrow(recentSummaries[2], "22:40"))),
        ConvSectionUi("Previous 7 days", listOf(hrow(recentSummaries[3], "Tue"))),
    )

    val preview = PreviewUi(
        lines = listOf(
            PreviewLine.User("Record a WAV while I hold the mic and run it through Whisper."),
            PreviewLine.Assistant("Armed. Frames arrive only while the button is held: 3.0 s at −9 to −20 dBFS."),
            PreviewLine.Steps(2, listOf("adb", "whisper")),
            PreviewLine.Assistant("Whisper heard: **“Archie, what time is it in Rio?”** Alexa answered too."),
            PreviewLine.User("Try again with Alexa's voice UI disabled."),
            PreviewLine.Assistant("Done. With `com.amazon.vizzini` disabled the screen stayed quiet and the recording is clean."),
        ),
        workingDir = "/home/rodrigo/assistant-wt/tv",
    )

    // ───────────────────────── Conversation ─────────────────────────

    private fun obj(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    /** A conversation state that runs through the apps' real flattener (grouping, keys). */
    val conversationState: ConversationState = ConversationState.initial(SessionRef("o1", "s-archie", SessionKind.ORCHESTRATOR, null)).copy(
        status = SessionStatus.STREAMING,
        inTurn = true,
        entries = persistentListOf(
            UserEntry("u1", "Open the solar dashboard on the TV.", UserOrigin.AUDIO),
            AssistantEntry(
                "a1",
                persistentListOf(
                    ToolBlock("b1", "t1", "open_visualization", obj("path" to "visualizations/solar-dashboard.html"), ToolStatus.DONE, "Opened on Living room TV"),
                    ToolBlock("b2", "t2", "read_file", obj("path" to "context/public/visualizations/solar/data.json"), ToolStatus.DONE, "{ \"battery\": 76 }"),
                    ToolBlock("b3", "t3", "run_script", obj("name" to "tv_cmd", "args" to "state"), ToolStatus.DONE, "{\"screen\": \"viewer\"}"),
                    TextBlock("x1", "Done, it's on screen. The battery is at 76% and you've exported 2.1 kWh today; production peaked at 2.9 kWh around noon."),
                    ToolBlock("b4", "t4", "send_to_agent_session", obj("session_id" to "7f3a91c2", "message" to "Plan the drift fix first"), ToolStatus.DONE, "status: running · turn 7f3a91 · plan mode on"),
                ),
            ),
        ),
    )

    private val flattener = ChatListFlattener(CatalogToolCardRenderer::describe) { id, text, streaming -> MarkdownStore().snapshot(id, text, streaming) }

    /** The flattened items: "3 steps" collapsed, the agent call card expanded. */
    val conversationItems: List<ChatItem> = flattener.flatten(
        conversationState,
        FlattenOptions(groupToggles = mapOf("g:t:t1" to false), cardToggles = mapOf("t:t4" to true)),
    ).items

    /** The paragraph block (mockup 6 focuses it). */
    val paragraphKey: String = conversationItems.first { it is ChatItem.MdBlock }.key

    private val approval = AgentApproval(
        localId = "a1",
        requestId = "r1",
        toolName = "ExitPlanMode",
        toolInput = buildJsonObject { put("plan", JsonPrimitive("Add a 30-minute drift test, then retune the confirm window.")) },
    )

    private fun agent(id: String, desc: String, status: SubagentStatus, run: AgentRun) = AgentUi(id, null, 0, "Explore", desc, status, run, open = false)

    val conversation = ConversationUiState(
        kind = SessionKind.ORCHESTRATOR,
        loaded = true,
        items = conversationItems.toImmutableList(),
        empty = false,
        composer = ComposerUi(working = true, context = 0.42f, placeholder = "Message Archie…"),
        cards = persistentListOf(InlineCardUi.AgentApprovalCard(approval, answered = false)),
        agents = AgentsUi(
            all = persistentListOf(
                agent("g1", "Find the drift source", SubagentStatus.RUNNING, AgentRun("g1", AgentRunStatus.Running, activity = "Grep", toolUses = 6, tokens = 12_400)),
                agent("g2", "Check the confirm window", SubagentStatus.DONE, AgentRun("g2", AgentRunStatus.Done, summary = "Window is 1.2 s; 0.8 s works.", toolUses = 3)),
            ),
            running = 1,
        ),
    )

    val conversationHeader = ConversationHeaderUi(title = "Plan the TV app", archie = true, connection = ConnectionStatus.Phase.CONNECTED)
}
