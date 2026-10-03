package com.assistant.archie.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assistant.archie.graph.MainAppGraph
import com.assistant.core.conversation.ConversationState
import com.assistant.core.data.ConversationKey
import com.assistant.core.data.ItemKey

/**
 * The content the shell hosts (spec 14 §7: "navigation destinations as clearly-marked placeholder
 * composables"). The shell owns chrome, navigation and focus; a destination owns its screen. Later
 * WPs (B-04…B-08) replace the bodies of [GraphDestinations] with their feature screens.
 */
@Stable
interface ShellDestinations {
    /** The active workspace item's content (conversation, or a memory doc / visual opened as a tab). */
    @Composable fun WorkspaceItemContent(key: ItemKey, modifier: Modifier)

    @Composable fun HistoryScreen(onBack: () -> Unit)
    @Composable fun MemoryScreen(onBack: () -> Unit, onOpenDoc: (String) -> Unit)
    @Composable fun MemoryDocScreen(path: String, onBack: () -> Unit)
    @Composable fun VisualsScreen(onBack: () -> Unit, onOpen: (String) -> Unit)
    @Composable fun VisualScreen(path: String, onBack: () -> Unit)
    @Composable fun SettingsScreen(onBack: () -> Unit, onOpenPage: (SettingsPageId) -> Unit)
    @Composable fun SettingsPageScreen(id: SettingsPageId, onBack: () -> Unit)
    @Composable fun SessionSettingsScreen(localId: String, onBack: () -> Unit)

    /** Expanded / Medium list pane bodies for the Memory and Visuals rail destinations (B-07). */
    @Composable fun MemoryPane(onOpenDoc: (String) -> Unit, modifier: Modifier)
    @Composable fun VisualsPane(onOpen: (String) -> Unit, modifier: Modifier)
}

/** Production destinations: the placeholders above, fed by the process-scoped graph. */
class GraphDestinations(private val graph: MainAppGraph) : ShellDestinations {
    @Composable
    override fun WorkspaceItemContent(key: ItemKey, modifier: Modifier) {
        when (key) {
            ItemKey.Archie -> Conversation(ConversationKey.ARCHIE, true, modifier)
            is ItemKey.Agent -> Conversation(key.conversation, false, modifier)
            is ItemKey.Memory -> Column(modifier.fillMaxSize()) {
                PlaceholderBody("B-07", "Memory document\n${key.path}")
            }
            is ItemKey.Visual -> Column(modifier.fillMaxSize()) {
                PlaceholderBody("B-07", "Visual\n${key.path}")
            }
        }
    }

    @Composable
    private fun Conversation(key: ConversationKey, archie: Boolean, modifier: Modifier) {
        val state: ConversationState? by graph.conversations.state(key).collectAsStateWithLifecycle()
        ConversationPlaceholder(
            state = state,
            isArchie = archie,
            onSend = { graph.conversations.send(key, it) },
            onStop = { graph.conversations.interrupt(key) },
            modifier = modifier,
        )
    }

    @Composable
    override fun HistoryScreen(onBack: () -> Unit) =
        PlaceholderScreen("History", "B-06", "All past conversations, search and date groups.", onBack)

    @Composable
    override fun MemoryScreen(onBack: () -> Unit, onOpenDoc: (String) -> Unit) =
        PlaceholderScreen("Memory", "B-07", "The memory tree.", onBack)

    @Composable
    override fun MemoryDocScreen(path: String, onBack: () -> Unit) =
        PlaceholderScreen(path.substringAfterLast('/'), "B-07", "Memory document\n$path", onBack)

    @Composable
    override fun VisualsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) =
        PlaceholderScreen("Visuals", "B-07", "Visualizations gallery.", onBack)

    @Composable
    override fun VisualScreen(path: String, onBack: () -> Unit) =
        PlaceholderScreen(path.substringAfterLast('/'), "B-07", "Visual\n$path", onBack)

    @Composable
    override fun SettingsScreen(onBack: () -> Unit, onOpenPage: (SettingsPageId) -> Unit) =
        PlaceholderScreen("Settings", "B-08", "This device · Archie (server) · About", onBack)

    @Composable
    override fun SettingsPageScreen(id: SettingsPageId, onBack: () -> Unit) =
        PlaceholderScreen(id.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, "B-08", "Settings page", onBack)

    @Composable
    override fun SessionSettingsScreen(localId: String, onBack: () -> Unit) =
        PlaceholderScreen("Session settings", "B-08", "Working directory, MCPs, skills & agents.", onBack)

    @Composable
    override fun MemoryPane(onOpenDoc: (String) -> Unit, modifier: Modifier) =
        PlaceholderBody("B-07", "Memory tree", modifier)

    @Composable
    override fun VisualsPane(onOpen: (String) -> Unit, modifier: Modifier) =
        PlaceholderBody("B-07", "Visuals", modifier)
}
