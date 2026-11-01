package com.assistant.archie.tv.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assistant.archie.tv.conversations.ConversationsModel
import com.assistant.archie.tv.conversations.PreviewLine
import com.assistant.archie.tv.graph.TvAppGraph
import com.assistant.archie.tv.theme.sampled
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.data.ConversationKey
import com.assistant.core.data.ItemKey
import com.assistant.core.data.ItemKind
import com.assistant.core.data.WorkspaceItem
import com.assistant.core.network.ApiResult
import com.assistant.core.network.ArchieApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Home's data (spec 15 §3.5): the graph's repositories mapped through [HomeModel]. The open-sessions
 * list re-emits on every frame of every open conversation, so Home samples it at 2 Hz (a streaming
 * agent must not recompose the rows 30 times a second on the stick).
 */
@Composable
fun rememberHomeState(g: TvAppGraph): State<HomeUiState> {
    val connected = g.connection.status.collectAsStateWithLifecycle().value.phase == ConnectionStatus.Phase.CONNECTED
    LaunchedEffect(connected) {
        if (connected) {
            g.visuals.refresh()
            g.memory.refreshTree()
        }
    }
    val holder = remember(g) { HomeStateHolder.of(g) }
    // The last state is kept for the process: coming back to Home composes the rows at once, so the
    // rows' saved scroll positions and the remembered card (FocusMemory) can be restored.
    val initial = remember(holder) { holder.state.value }
    return holder.state.collectAsStateWithLifecycle(initial)
}

/** Home's state flow, one per process (built on first use; the graph file stays untouched). */
class HomeStateHolder private constructor(g: TvAppGraph) {
    private val previews = PoolPreviews.of(g)

    val state: StateFlow<HomeUiState> = run {
        val items = g.openSessions.items.sampled(500)
        val content = combine(g.visuals.list, g.memory.tree, g.content.visuals, g.content.memory) { v, t, cv, cm -> ContentIn(v, t, cv, cm) }
        combine(items, g.conversations.state(ConversationKey.ARCHIE).sampled(500), g.history.sessions, content, previews.lines) { it, archie, sessions, c, lines ->
            previews.request(it)
            HomeModel.state(
                archie = archie,
                items = it,
                lastLine = { w -> lastLineOf(g, w) ?: w.sdkId?.let { lines[it] } },
                visuals = c.visuals,
                changedVisuals = c.changedVisuals,
                tree = c.tree,
                changedMemory = c.changedMemory,
                sessions = sessions,
            )
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(g.scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), HomeUiState(loading = true))
    }

    companion object {
        private const val STOP_AFTER_MS = 30_000L
        private val instances = java.util.WeakHashMap<TvAppGraph, HomeStateHolder>()

        fun of(g: TvAppGraph): HomeStateHolder = synchronized(instances) { instances.getOrPut(g) { HomeStateHolder(g) } }
    }
}

private data class ContentIn(
    val visuals: com.assistant.core.data.LoadState<List<com.assistant.core.model.VisualInfo>>,
    val tree: com.assistant.core.data.LoadState<List<com.assistant.core.model.MemoryNode>>,
    val changedVisuals: Map<String, com.assistant.core.data.ContentStamp>,
    val changedMemory: Map<String, com.assistant.core.data.ContentStamp>,
)

/** The latest reply line of an agent view open here (no request needed). */
private fun lastLineOf(g: TvAppGraph, w: WorkspaceItem): String? {
    val key = (w.key as? ItemKey.Agent)?.conversation ?: return null
    val st = g.conversations.current(key) ?: return null
    return HomeModel.lastReply(st).ifEmpty { null }
}

/**
 * Last reply lines of pool sessions with no view on this device ("Open now" cards): one
 * `GET /api/sessions/{id}/preview?max=3` per session and status change, cached for the process.
 */
class PoolPreviews internal constructor(private val api: ArchieApi, private val scope: CoroutineScope, private val hasView: (WorkspaceItem) -> Boolean) {
    private val _lines = MutableStateFlow<Map<String, String>>(emptyMap())
    val lines: StateFlow<Map<String, String>> = _lines.asStateFlow()
    private val asked = ConcurrentHashMap<String, String>()

    fun request(items: List<WorkspaceItem>) {
        for (w in items) {
            if (w.kind != ItemKind.AGENT || hasView(w)) continue
            val sdk = w.sdkId ?: continue
            val stamp = w.status.name
            if (asked.put(sdk, stamp) == stamp) continue
            scope.launch {
                val r = api.preview(sdk, 3)
                if (r is ApiResult.Ok) {
                    val line = ConversationsModel.previewLines(r.value, 3).lastOrNull { it is PreviewLine.Assistant } as? PreviewLine.Assistant
                    if (line != null) _lines.update { it + (sdk to TextPreview.lastParagraph(line.text)) }
                }
            }
        }
    }

    companion object {
        private val instances = java.util.WeakHashMap<TvAppGraph, PoolPreviews>()

        fun of(g: TvAppGraph): PoolPreviews = synchronized(instances) {
            instances.getOrPut(g) {
                PoolPreviews(g.api, g.scope) { w -> (w.key as? ItemKey.Agent)?.conversation?.let { g.conversations.current(it) } != null }
            }
        }
    }
}
