package com.assistant.archie.shell

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assistant.archie.graph.MainAppGraph
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.data.HistoryGroup
import com.assistant.core.data.HistoryGrouping
import com.assistant.core.data.ItemKey
import com.assistant.core.data.WorkspaceItem
import com.assistant.core.model.PoolSession
import com.assistant.core.model.SessionSummary
import com.assistant.core.model.ThemeMode
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock

/** Everything the shell chrome renders (spec 14 §2.1: immutable UI state from domain state). */
@Immutable
data class ShellUiState(
    val connection: ConnectionStatus = ConnectionStatus(),
    val items: List<WorkspaceItem> = emptyList(),
    val active: ItemKey? = null,
    /** Live pool sessions with no open view here ("Open now" rows that open on tap). */
    val liveElsewhere: List<PoolSession> = emptyList(),
    val history: List<HistoryGroup> = emptyList(),
    val historyLoading: Boolean = false,
    val historyError: String? = null,
    val search: String = "",
    val voice: VoiceSessionState = VoiceSessionState(),
    val listPaneCollapsed: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
) {
    val activeItem: WorkspaceItem? get() = items.firstOrNull { it.key == active }
    val voiceActive: Boolean get() = voice.phase != SessionPhase.OFF && voice.phase != SessionPhase.ERROR
}

/** Shell intents (UDF: UI → ViewModel). */
sealed interface ShellAction {
    data class Select(val key: ItemKey) : ShellAction
    data class SelectRelative(val delta: Int) : ShellAction
    data class OpenHistory(val session: SessionSummary) : ShellAction
    data class OpenLive(val session: PoolSession) : ShellAction
    data object NewArchie : ShellAction
    data object NewAgent : ShellAction
    /** Explicit close by the user (P-1: the only path that closes on the server). */
    data class Close(val key: ItemKey) : ShellAction
    data class Search(val query: String) : ShellAction
    data object ToggleListPane : ShellAction
    data object RefreshHistory : ShellAction
    data object Reconnect : ShellAction
    data class OpenMemoryDoc(val path: String) : ShellAction
    data class OpenVisual(val path: String) : ShellAction
    data class Compact(val key: ItemKey) : ShellAction
}

/**
 * The `WorkspaceViewModel` of spec 14 §2.1: maps [com.assistant.core.data.OpenSessionsRepository]
 * (tabs / switcher), the session directory (drawer history, grouped by local day with UTC-correct
 * parsing), the connection and the voice presence to [ShellUiState]. Domain state stays in the
 * process-scoped repositories; this only maps it.
 */
class ShellViewModel(
    private val graph: MainAppGraph,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val search = MutableStateFlow("")

    private val base = combine(
        graph.openSessions.items,
        graph.openSessions.active,
        graph.connection.status,
        graph.history.pool,
        graph.voice.state,
    ) { items, active, conn, pool, voice ->
        val openLocal = items.mapNotNull { it.localId }.toSet()
        ShellUiState(
            connection = conn,
            items = items,
            active = active,
            liveElsewhere = pool.filter { !it.isOrchestrator && it.localId !in openLocal },
            voice = voice,
        )
    }

    val state: StateFlow<ShellUiState> = combine(base, graph.history.sessions, search, graph.settings.settings) { b, list, q, s ->
        b.copy(
            history = HistoryGrouping.group(HistoryGrouping.filter(list.value.orEmpty(), q), clock.instant(), clock.zone),
            historyLoading = list.loading,
            historyError = list.error,
            search = q,
            listPaneCollapsed = s?.listPaneCollapsed == true,
            themeMode = s?.themeMode ?: ThemeMode.SYSTEM,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShellUiState())

    fun onAction(a: ShellAction) {
        val open = graph.openSessions
        when (a) {
            is ShellAction.Select -> open.select(a.key)
            is ShellAction.SelectRelative -> open.selectRelative(a.delta)
            is ShellAction.OpenHistory -> open.openSession(a.session)
            is ShellAction.OpenLive -> open.openLive(a.session)
            ShellAction.NewArchie -> open.newArchieConversation()
            ShellAction.NewAgent -> open.newAgentSession()
            is ShellAction.Close -> viewModelScope.launch { open.close(a.key) }
            is ShellAction.Search -> search.value = a.query
            ShellAction.ToggleListPane -> viewModelScope.launch {
                graph.settings.setListPaneCollapsed(!(graph.settings.settings.value?.listPaneCollapsed ?: false))
            }
            ShellAction.RefreshHistory -> graph.history.refreshAll()
            ShellAction.Reconnect -> graph.connection.connect()
            is ShellAction.OpenMemoryDoc -> open.openMemory(a.path)
            is ShellAction.OpenVisual -> open.openVisual(a.path)
            is ShellAction.Compact -> when (val k = a.key) {
                ItemKey.Archie -> graph.conversations.compact(com.assistant.core.data.ConversationKey.ARCHIE)
                is ItemKey.Agent -> graph.conversations.compact(k.conversation)
                else -> Unit
            }
        }
    }
}
