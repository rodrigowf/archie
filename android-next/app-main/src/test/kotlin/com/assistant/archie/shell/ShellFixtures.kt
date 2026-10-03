package com.assistant.archie.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.assistant.core.conversation.AssistantEntry
import com.assistant.core.conversation.ConversationState
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.ToolStatus
import com.assistant.core.conversation.TextBlock
import com.assistant.core.conversation.UserEntry
import com.assistant.core.conversation.UserOrigin
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.data.ConversationKey
import com.assistant.core.data.HistoryGrouping
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
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** Mockup content (IA / archie-mockups.html) as shell state, so goldens show the approved scenes. */
object ShellFixtures {
    val agentKey = ItemKey.Agent(ConversationKey.agent("AG1"))
    private val agent2 = ItemKey.Agent(ConversationKey.agent("AG2"))

    val items = listOf(
        WorkspaceItem(ItemKey.Archie, ItemKind.ARCHIE, "Living-room TV", status = TabStatus.NEEDS_YOU, detail = "Archie · waiting for your approval", localId = "ORCH", sdkId = "JSONL"),
        WorkspaceItem(agentKey, ItemKind.AGENT, "Refactor voice module", HarnessProvider.CLAUDE, TabStatus.IDLE, "Ready · 14 turns", localId = "AG1", sdkId = "S1"),
        WorkspaceItem(agent2, ItemKind.AGENT, "Energy dashboard", HarnessProvider.QWEN, TabStatus.WORKING, "Using tools…", localId = "AG2", sdkId = "S2"),
        WorkspaceItem(ItemKey.Memory("assistant/architecture/voice_subsystem.md"), ItemKind.MEMORY, "voice_subsystem.md", detail = "Memory · assistant/architecture"),
        WorkspaceItem(ItemKey.Visual("visualizations/weekly-energy.html"), ItemKind.VISUAL, "weekly-energy", detail = "Visual"),
    )

    /** "Now" for every golden: Sat 3 Oct 2026, 15:00 UTC; shown in UTC so goldens do not depend on the host zone. */
    val now: Instant = Instant.parse("2026-10-03T15:00:00Z")

    private fun s(id: String, title: String, last: String, orch: Boolean) =
        SessionSummary(id, last, last, title, 6, orch, if (orch) null else HarnessProvider.CLAUDE, null)

    val sessions = listOf(
        s("h1", "Weekly energy report", "2026-10-03T14:20:00+00:00", true),
        s("h2", "Fix context-sync delete race", "2026-10-03T11:05:00+00:00", false),
        s("h3", "Morning briefing", "2026-10-02T08:12:00+00:00", true),
        s("h4", "Wake-word tuning review", "2026-10-02T07:40:00+00:00", false),
        s("h5", "Movie list for the weekend", "2026-09-29T19:00:00+00:00", true),
        s("h6", "Compat build for iPad mini", "2026-09-28T10:00:00+00:00", false),
        s("h7", "Jetson thermal check", "2026-08-28T21:49:03.550000+00:00", false),
    )

    fun state(active: ItemKey = ItemKey.Archie, search: String = "") = ShellUiState(
        connection = ConnectionStatus("ws://192.168.0.200:80", "jetson", ConnectionStatus.Phase.CONNECTED),
        items = items,
        active = active,
        history = HistoryGrouping.group(HistoryGrouping.filter(sessions, search), now, ZoneOffset.UTC, Locale.US),
        search = search,
    )

    private fun archieConversation(): ConversationState {
        val ref = SessionRef("ORCH", "JSONL", SessionKind.ORCHESTRATOR, null)
        return ConversationState(
            ref = ref,
            status = SessionStatus.IDLE,
            entries = persistentListOf(
                UserEntry("e1", "Plan the living-room TV setup for movie night on Friday.", UserOrigin.LOCAL),
                AssistantEntry(
                    "e2",
                    persistentListOf(
                        TextBlock("b1", "On it. I'll check what the Fire TV can play, then ask an agent to draft the setup."),
                        ToolBlock("b2", "t1", "run_script", JsonObject(emptyMap()), ToolStatus.DONE, "Connected to Fire TV"),
                        ToolBlock("b3", "t2", "send_to_agent_session", JsonObject(emptyMap()), ToolStatus.RUNNING),
                    ),
                ),
            ),
        )
    }

    private fun agentConversation(): ConversationState {
        val ref = SessionRef("AG1", "S1", SessionKind.AGENT, HarnessProvider.CLAUDE)
        return ConversationState(
            ref = ref,
            status = SessionStatus.IDLE,
            entries = persistentListOf(
                AssistantEntry("a1", persistentListOf(TextBlock("t1", "Voice state is split between OrchestratorSession and OpenAIVoiceProvider. I'll move every transition into one VoiceStateMachine."))),
                UserEntry("a2", "Go ahead. Keep the parity tests green.", UserOrigin.LOCAL),
                AssistantEntry(
                    "a3",
                    persistentListOf(
                        ToolBlock("t2", "u1", "Bash", JsonObject(emptyMap()), ToolStatus.DONE, "46 passed"),
                        TextBlock("t3", "Done. VoiceStateMachine owns every transition and all 46 parity tests pass. Should I commit this?"),
                    ),
                ),
            ),
        )
    }

    /** Destinations with fixed conversation content (no graph). Records the sends. */
    class Destinations : ShellDestinations {
        val sent = mutableListOf<String>()

        @Composable
        override fun WorkspaceItemContent(key: ItemKey, modifier: Modifier) {
            when (key) {
                ItemKey.Archie -> ConversationPlaceholder(archieConversation(), true, { sent += it }, {}, modifier)
                is ItemKey.Agent -> ConversationPlaceholder(agentConversation(), false, { sent += it }, {}, modifier)
                is ItemKey.Memory -> Column(modifier.fillMaxSize()) { PlaceholderBody("B-07", "Memory document\n${key.path}") }
                is ItemKey.Visual -> Column(modifier.fillMaxSize()) { PlaceholderBody("B-07", "Visual\n${key.path}") }
            }
        }

        @Composable override fun HistoryScreen(onBack: () -> Unit) = PlaceholderScreen("History", "B-06", "History", onBack)
        @Composable override fun MemoryScreen(onBack: () -> Unit, onOpenDoc: (String) -> Unit) = PlaceholderScreen("Memory", "B-07", "Memory", onBack)
        @Composable override fun MemoryDocScreen(path: String, onBack: () -> Unit) = PlaceholderScreen(path, "B-07", path, onBack)
        @Composable override fun VisualsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) = PlaceholderScreen("Visuals", "B-07", "Visuals", onBack)
        @Composable override fun VisualScreen(path: String, onBack: () -> Unit) = PlaceholderScreen(path, "B-07", path, onBack)
        @Composable override fun SettingsScreen(onBack: () -> Unit, onOpenPage: (SettingsPageId) -> Unit) = PlaceholderScreen("Settings", "B-08", "Settings", onBack)
        @Composable override fun SettingsPageScreen(id: SettingsPageId, onBack: () -> Unit) = PlaceholderScreen(id.name, "B-08", "Page", onBack)
        @Composable override fun SessionSettingsScreen(localId: String, onBack: () -> Unit) = PlaceholderScreen("Session settings", "B-08", localId, onBack)
        @Composable override fun MemoryPane(onOpenDoc: (String) -> Unit, modifier: Modifier) = PlaceholderBody("B-07", "Memory tree", modifier)
        @Composable override fun VisualsPane(onOpen: (String) -> Unit, modifier: Modifier) = PlaceholderBody("B-07", "Visuals", modifier)
    }
}
