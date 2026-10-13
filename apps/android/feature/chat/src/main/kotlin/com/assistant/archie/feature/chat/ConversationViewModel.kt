package com.assistant.archie.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assistant.archie.feature.chat.model.AgentUiMapper
import com.assistant.archie.feature.chat.model.AgentViewUi
import com.assistant.archie.feature.chat.model.AgentsUi
import com.assistant.archie.feature.chat.model.ChatItem
import com.assistant.archie.feature.chat.model.ToolDescriptor
import com.assistant.core.conversation.AssistantEntry
import com.assistant.core.conversation.ConversationState
import com.assistant.core.conversation.TextBlock
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.UserEntry
import com.assistant.core.data.ApprovalAnswer
import com.assistant.core.data.ConversationEvent
import com.assistant.core.data.CutResult
import com.assistant.core.model.ConnectionState
import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
import com.assistant.core.model.UploadResult
import com.assistant.core.network.ApiResult
import com.assistant.core.network.SendResult
import com.assistant.core.network.UploadSource
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the screen can ask for (spec 14 §2.1: `onAction(a)`). */
sealed interface ChatAction {
    data class DraftChanged(val text: String) : ChatAction

    /** Composer primary button / IME Send. */
    data object Primary : ChatAction
    data object Send : ChatAction
    data object Stop : ChatAction
    data class SendText(val text: String) : ChatAction
    data object Compact : ChatAction
    data class ToggleGroup(val key: String, val expanded: Boolean) : ChatAction
    data class ToggleCard(val key: String, val expanded: Boolean) : ChatAction
    data object LoadOlder : ChatAction
    data class Permission(val requestId: String, val allow: Boolean) : ChatAction
    data class AgentApproval(val localId: String, val requestId: String, val allow: Boolean) : ChatAction
    data class DismissCard(val id: String) : ChatAction
    data class Retry(val kind: RetryKind, val cardId: String) : ChatAction
    data class Rewind(val entryId: String) : ChatAction
    data class Fork(val entryId: String) : ChatAction
    data class Upload(val source: UploadSource, val subject: String? = null) : ChatAction
    data object Reload : ChatAction

    // subagents (spec 12 §4.8, plan §4.4)
    /** Select an agent tab (materialises its transcript, SA-5); null = Main. Only a user action selects (FOCUS-1). */
    data class SelectAgent(val toolUseId: String?) : ChatAction
    /** SA-9. */
    data class StopAgent(val toolUseId: String) : ChatAction
    /** Hide the tabs of finished agents (they stay in the Agents list and come back when opened). */
    data object CloseFinishedAgents : ChatAction

    // voice (Archie only)
    data object StartVoice : ChatAction
    data object EndVoice : ChatAction
    data object ToggleMic : ChatAction
    data object ToggleSpeaker : ChatAction
    data object TakeOverVoice : ChatAction
    data object ToggleRecording : ChatAction
}

/** One-shot effects (spec 14 §2.1: a buffered channel). */
sealed interface ChatEffect {
    data class Snackbar(val message: String) : ChatEffect
    data class OpenSession(val ref: SessionRef) : ChatEffect
}

/**
 * The conversation screen's ViewModel (spec 14 §2.1). Domain state stays in the repository; this
 * class maps it to [ConversationUiState] and holds UI-local state: the draft, group/card toggles,
 * answered permissions, dismissed cards, the voice reconnect timeline.
 *
 * Flattening runs on one confined background dispatcher ([flattenDispatcher]); while a block streams
 * the list is published at most every [SAMPLE_MS] (≈30 recompositions/s, §2.3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModel(
    private val backend: ChatBackend,
    private val voice: ChatVoice,
    private val describe: (ToolBlock, SessionKind) -> ToolDescriptor,
    private val saved: SavedStateHandle = SavedStateHandle(),
    flattenDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val clock: () -> Long = System::currentTimeMillis,
    externalScope: CoroutineScope? = null,
    private val title: () -> String? = { null },
    /** The process-wide voice dock model (shared with the floating controls); null: a local one. */
    voiceDock: VoiceDockModel? = null,
) : ViewModel() {
    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val markdown = MarkdownStore()
    private val flattener = ChatListFlattener(describe) { id, text, streaming -> markdown.snapshot(id, text, streaming) }

    // The selected agent tab's transcript has its own markdown store: its live block ids restart at 1.
    // Its own parse cache too, so agent-tab history never evicts Main's parsed blocks.
    private val agentMarkdown = MarkdownStore(com.assistant.core.markdown.MarkdownCache(AGENT_MARKDOWN_CACHE))
    private val agentFlattener = ChatListFlattener(describe) { id, text, streaming -> agentMarkdown.snapshot(id, text, streaming) }
    private val selectedAgent = MutableStateFlow(saved.get<String>(KEY_AGENT))
    private val hiddenAgents = MutableStateFlow(saved.get<ArrayList<String>>(KEY_HIDDEN_AGENTS)?.toSet() ?: emptySet())
    /** The Main list while an agent tab is on screen (not re-flattened until Main shows again). */
    private var lastMain: FlattenResult? = null
    /** What [lastMain] was flattened from: a frame that changes none of it (subagent traffic) only patches agent cards. */
    private var lastMainKey: ListKey? = null
    private var lastMainSubagents: Map<String, com.assistant.core.conversation.Subagent>? = null
    private var lastAgent: FlattenResult? = null
    private var lastAgentKey: ListKey? = null
    private var lastAgentSubagents: Map<String, com.assistant.core.conversation.Subagent>? = null

    /** Everything a flatten reads except the subagent map (persistent collections: identity first, so cheap). */
    private data class ListKey(
        val id: String?,
        val entries: Any,
        val history: Any,
        val inTurn: Boolean,
        val busy: Boolean,
        val voiceActive: Boolean,
        val orphans: Any,
        val unattributed: Any,
        val options: FlattenOptions,
    )

    private fun keyOf(id: String?, s: ConversationState, o: FlattenOptions) =
        ListKey(id, s.entries, s.history, s.inTurn, s.status.busy, s.voiceActive, s.orphanResults, s.unattributed, o)

    private val draft = MutableStateFlow(saved.get<String>(KEY_DRAFT) ?: "")
    val draftText: StateFlow<String> = draft

    /** The voice dock orb's live level, kept out of [state] so 15 Hz levels never recompose the screen. */
    val voiceLevel: StateFlow<Float?> get() = voice.level

    private val groupToggles = MutableStateFlow(readToggles(KEY_GROUPS))
    private val cardToggles = MutableStateFlow(readToggles(KEY_CARDS))
    private val loadingOlder = MutableStateFlow(false)
    private val answered = MutableStateFlow<Set<String>>(emptySet())
    private val dismissed = MutableStateFlow<Set<String>>(emptySet())
    private val transientErrors = MutableStateFlow<List<InlineCardUi.Error>>(emptyList())
    private val busy = MutableStateFlow<String?>(null)
    private var resendOnConnect: String? = null
    private var olderGuard: String? = null

    /** The voice dock's state and actions, with the reconnect timeline (shared with the floating controls). */
    private val dock = voiceDock ?: VoiceDockModel(voice, scope, clock)

    private val effects = Channel<ChatEffect>(Channel.BUFFERED)
    val effectFlow: Flow<ChatEffect> = effects.receiveAsFlow()

    private val options = combine(groupToggles, cardToggles, loadingOlder) { g, c, l ->
        FlattenOptions(groupToggles = g, cardToggles = c, loadingOlder = l)
    }

    private class Flat(
        val s: ConversationState,
        val main: FlattenResult,
        val agents: AgentsUi,
        val agentView: AgentViewUi?,
        val streaming: Boolean,
    )

    private val flattened: Flow<Flat> =
        combine(backend.state.filterNotNull(), options, combine(selectedAgent, hiddenAgents, ::Pair)) { s, o, a -> Triple(s, o, a) }
            .conflate()
            .map { (s, o, a) -> flattenAll(s, o, a.first, a.second) }
            .flowOn(flattenDispatcher)
            .conflate()
            // Sampled (≤ 30/s) while text streams *or* subagents work: their frames arrive in bursts too.
            .transform { emit(it); if (it.streaming) delay(SAMPLE_MS) }

    /** Main list (skipped while an agent tab is on screen), the agent tabs, and the selected agent's transcript. */
    private fun flattenAll(s: ConversationState, o: FlattenOptions, selected: String?, hidden: Set<String>): Flat {
        val sub = selected?.let { s.subagents[it] }
        val prev = lastMain
        val main = when {
            sub != null && prev != null -> prev                                    // hidden: not re-flattened
            prev != null && keyOf(null, s, o) == lastMainKey -> {
                // Only the subagent map changed (progress, inner frames): refresh the agent cards in place.
                if (s.subagents === lastMainSubagents) prev
                else FlattenResult(ChatListFlattener.patchAgents(prev.items, s.subagents), prev.streaming).also { lastMain = it }
            }
            else -> flattener.flatten(s, o).also { markdown.sweep(); lastMain = it; lastMainKey = keyOf(null, s, o) }
        }
        if (sub == null || prev == null) lastMainSubagents = s.subagents
        val agents = AgentUiMapper.agents(s, selected, hidden)
        val subagentsBusy = agents.running > 0
        if (sub == null) return Flat(s, main, agents, null, main.streaming || subagentsBusy)
        val conv = sub.conv
        var streaming = subagentsBusy || conv?.inTurn == true
        val items = if (conv != null) {
            // First page only (SA-5): no "Load older" in an agent tab; only the running card opens by itself.
            val ao = o.copy(loadingOlder = false, expandLiveRun = false)
            val view = conv.copy(history = conv.history.copy(hasMore = false))
            val key = keyOf(sub.toolUseId, view, ao)
            val pa = lastAgent
            val r = if (pa != null && key == lastAgentKey) {
                if (s.subagents === lastAgentSubagents) pa else FlattenResult(ChatListFlattener.patchAgents(pa.items, s.subagents), pa.streaming)
            } else {
                agentFlattener.flatten(view, ao, s.subagents).also { agentMarkdown.sweep(); lastAgentKey = key }
            }
            lastAgent = r
            lastAgentSubagents = s.subagents
            streaming = streaming || r.streaming
            r.items
        } else {
            emptyList()
        }
        val view = AgentViewUi(
            agent = AgentUiMapper.agent(sub, s.subagents),
            path = AgentUiMapper.path(sub, s.subagents).toImmutableList(),
            items = items.toImmutableList(),
            loaded = conv != null && sub.held == null && conv.history.loaded,
            error = sub.historyError,
            empty = conv?.entries?.isEmpty() ?: true,
            truncated = conv?.history?.hasMore == true,
        )
        return Flat(s, main, agents, view, streaming)
    }

    private val voiceUi: Flow<VoiceUi> = dock.ui

    private data class Local(
        val answered: Set<String>,
        val dismissed: Set<String>,
        val transient: List<InlineCardUi.Error>,
        val busy: String?,
        val draftEmpty: Boolean,
    )

    private val local = combine(answered, dismissed, transientErrors, busy, draft) { a, d, t, b, dr ->
        Local(a, d, t, b, dr.isBlank())
    }

    val state: StateFlow<ConversationUiState> = combine(flattened, voiceUi, voice.recording, local) { f, v, rec, l ->
        val s = f.s
        val r = f.main
        val archie = s.kind == SessionKind.ORCHESTRATOR
        val vu = if (archie) v else VoiceUi.Off
        ConversationUiState(
            kind = s.kind,
            loaded = s.history.loaded,
            items = r.items.toImmutableList(),
            oldestEntryId = s.entries.firstOrNull()?.id,
            hasMore = s.history.hasMore,
            empty = s.entries.isEmpty(),
            composer = ConversationUiMapper.composer(s, l.draftEmpty, vu, rec, title()),
            cards = ConversationUiMapper.cards(s, l.answered, l.dismissed, l.transient),
            queue = s.queue.toImmutableList(),
            voice = vu,
            counters = CountersUi(s.counters.cost, s.counters.turns, s.counters.contextTokens, s.counters.contextWindow),
            busyOverlay = l.busy,
            agents = f.agents,
            agentView = f.agentView,
        )
    }.stateIn(scope, SharingStarted.Eagerly, ConversationUiState())

    init {
        scope.launch { backend.events.collect(::onEvent) }
        scope.launch { backend.state.filterNotNull().collect(::onConversation) }
        dock.start()
        backend.touch()
    }

    fun onAction(a: ChatAction) {
        when (a) {
            is ChatAction.DraftChanged -> setDraft(a.text)
            ChatAction.Primary -> primary()
            ChatAction.Send -> sendDraft()
            ChatAction.Stop -> stop()
            is ChatAction.SendText -> sendText(a.text)
            ChatAction.Compact -> backend.compact()
            is ChatAction.ToggleGroup -> groupToggles.update { (it + (a.key to a.expanded)).also { m -> writeToggles(KEY_GROUPS, m) } }
            is ChatAction.ToggleCard -> cardToggles.update { (it + (a.key to a.expanded)).also { m -> writeToggles(KEY_CARDS, m) } }
            ChatAction.LoadOlder -> loadOlder()
            is ChatAction.Permission -> {
                if (a.requestId in answered.value) return
                answered.update { it + a.requestId }
                backend.respondToPermission(a.requestId, a.allow)
            }
            is ChatAction.AgentApproval -> {
                val id = "${a.localId}:${a.requestId}"
                if (id in answered.value) return
                answered.update { it + id }
                viewModelScope.launch {
                    val r = backend.respondToAgentApproval(a.localId, a.requestId, a.allow)
                    if (r is ApprovalAnswer.Failed) {
                        answered.update { it - id }
                        effects.trySend(ChatEffect.Snackbar(r.message))
                    }
                }
            }
            is ChatAction.DismissCard -> {
                dismissed.update { it + a.id }
                transientErrors.update { l -> l.filterNot { it.id == a.id } }
                if (a.id.startsWith("banner:")) backend.dismissBanner()
            }
            is ChatAction.Retry -> retry(a.kind, a.cardId)
            is ChatAction.Rewind -> cut(a.entryId, rewind = true)
            is ChatAction.Fork -> cut(a.entryId, rewind = false)
            is ChatAction.Upload -> upload(a.source, a.subject)
            ChatAction.Reload -> { dismissed.update { it + "gap" }; backend.reload() }
            is ChatAction.SelectAgent -> selectAgent(a.toolUseId)
            is ChatAction.StopAgent -> if (backend.stopSubagent(a.toolUseId) != SendResult.SENT) {
                effects.trySend(ChatEffect.Snackbar("Not connected: the agent was not stopped"))
            }
            ChatAction.CloseFinishedAgents -> closeFinishedAgents()
            ChatAction.StartVoice, ChatAction.EndVoice, ChatAction.ToggleMic, ChatAction.ToggleSpeaker,
            ChatAction.TakeOverVoice, ChatAction.ToggleRecording -> dock.onAction(a)
        }
    }

    /** The raw text of an entry for Copy / Select text (assistant: its text blocks, markdown kept). */
    fun textOf(entryId: String): String? = when (val e = backend.state.value?.entries?.firstOrNull { it.id == entryId }) {
        is UserEntry -> e.text
        is AssistantEntry -> e.blocks.filterIsInstance<TextBlock>().joinToString("\n\n") { it.text }.ifEmpty { null }
        else -> null
    }

    // ───────────────────────── agent tabs (spec 12 §4.8) ─────────────────────────

    private fun selectAgent(id: String?) {
        selectedAgent.value = id
        saved[KEY_AGENT] = id
        if (id == null) return
        if (id in hiddenAgents.value) setHidden(hiddenAgents.value - id)                  // back from "Close finished"
        backend.openSubagent(id)
    }

    private fun closeFinishedAgents() {
        val s = backend.state.value ?: return
        val finished = s.subagents.values.filter { it.status.finished }.map { it.toolUseId }
        setHidden(hiddenAgents.value + finished)
        if (selectedAgent.value in finished) selectAgent(null)
    }

    private fun setHidden(ids: Set<String>) {
        hiddenAgents.value = ids
        saved[KEY_HIDDEN_AGENTS] = ArrayList(ids)
    }

    // ───────────────────────── composer ─────────────────────────

    private fun setDraft(text: String) {
        draft.value = text
        saved[KEY_DRAFT] = text
    }

    private fun primary() {
        val s = state.value
        when (s.composer.primary) {
            com.assistant.core.design.components.ComposerPrimary.Send -> sendDraft()
            com.assistant.core.design.components.ComposerPrimary.Stop -> stop()
            com.assistant.core.design.components.ComposerPrimary.Voice -> voice.start()
            com.assistant.core.design.components.ComposerPrimary.SendDisabled -> Unit
        }
    }

    private fun stop() {
        // In voice mode Stop is voice_stop, the voice host's job (§6.3); the composer is the dock then.
        backend.interrupt()
    }

    private fun sendDraft() {
        val text = draft.value.trim()
        if (text.isEmpty()) return
        if (sendText(text)) setDraft("")
    }

    /** Sends (or queues, while working: I-12) [text]. False = not sent; the draft is kept. */
    private fun sendText(text: String): Boolean {
        val s = backend.state.value ?: return false
        if (s.connection != ConnectionState.SUBSCRIBED && s.connection != ConnectionState.OPEN) {
            showUnsent(text)
            return false
        }
        val slash = s.kind == SessionKind.AGENT && SLASH.matches(text)
        val r = if (slash) backend.command(text) else backend.send(text)
        if (r != SendResult.SENT) {
            showUnsent(text, partial = !slash)
            return slash
        }
        transientErrors.update { l -> l.filterNot { it.id == UNSENT } }
        return true
    }

    private fun showUnsent(text: String, partial: Boolean = false) {
        resendOnConnect = text
        transientErrors.update { l ->
            l.filterNot { it.id == UNSENT } + InlineCardUi.Error(
                id = UNSENT,
                title = if (partial) "Message may not have been sent" else "Message not sent",
                body = if (partial) "The connection dropped while sending. Check the reply before retrying."
                else "Not connected to the server. Your message is kept and sends when you retry.",
                detail = null,
                retry = if (partial) null else RetryKind.Resend,
            )
        }
    }

    private fun retry(kind: RetryKind, cardId: String) {
        when (kind) {
            RetryKind.Reconnect -> backend.retry()
            RetryKind.Resend -> {
                backend.retry()
                transientErrors.update { l -> l.filterNot { it.id == cardId } }
                // Sent by onConversation once the view is subscribed again.
            }
            RetryKind.Reload -> backend.reload()
        }
    }

    // ───────────────────────── list ─────────────────────────

    private fun loadOlder() {
        val s = backend.state.value ?: return
        val oldest = s.entries.firstOrNull()?.id
        // Guard keyed on the oldest entry id: never ask twice for the same page (port of 5c029d6).
        if (!s.history.hasMore || oldest == olderGuard || loadingOlder.value) return
        olderGuard = oldest
        loadingOlder.value = true
        backend.loadOlder()
    }

    private fun onConversation(s: ConversationState) {
        if (loadingOlder.value && s.entries.firstOrNull()?.id != olderGuard) loadingOlder.value = false
        if (!s.history.hasMore) loadingOlder.value = false
        val pending = resendOnConnect
        if (pending != null && s.connection == ConnectionState.SUBSCRIBED) {
            resendOnConnect = null
            if (backend.send(pending) == SendResult.SENT) {
                if (draft.value.trim() == pending) setDraft("")
                transientErrors.update { l -> l.filterNot { it.id == UNSENT } }
            } else {
                showUnsent(pending)
            }
        }
        // A new stall instance (different elapsed) re-shows a card the user dismissed with Keep waiting.
        if (s.stall == null) dismissed.update { it - "stall" }
        // The selected agent tab keeps its transcript (restored selection, or released elsewhere): SA-5 re-open.
        selectedAgent.value?.let { id -> if (s.subagents[id]?.conv == null && id in s.subagents) backend.openSubagent(id) }
    }

    private fun onEvent(e: ConversationEvent) {
        when (e) {
            is ConversationEvent.SideError -> effects.trySend(
                ChatEffect.Snackbar(if (e.code == "unsupported") e.detail ?: "This session can't stop a single agent" else e.detail ?: e.code),
            )
            is ConversationEvent.StartFailed -> Unit   // shown through the connection banner card
            else -> Unit
        }
    }

    // ───────────────────────── rewind / fork / upload ─────────────────────────

    private fun cut(entryId: String, rewind: Boolean) {
        if (busy.value != null) return
        busy.value = if (rewind) "Rewinding…" else "Forking…"
        scope.launch {
            val r = try {
                if (rewind) backend.rewind(entryId) else backend.fork(entryId)
            } finally {
                busy.value = null
            }
            when (r) {
                is CutResult.Done -> if (rewind) effects.send(ChatEffect.Snackbar("Rewound")) else effects.send(ChatEffect.OpenSession(r.ref))
                is CutResult.Failed -> effects.send(ChatEffect.Snackbar(r.message))
            }
        }
    }

    private fun upload(source: UploadSource, subject: String?) {
        if (busy.value != null) return
        busy.value = "Uploading ${source.fileName}…"
        scope.launch {
            val r = try {
                backend.upload(source) { sent, total ->
                    if (total > 0) busy.value = "Uploading ${source.fileName} · ${sent * 100 / total}%"
                }
            } finally {
                busy.value = null
            }
            when (r) {
                is ApiResult.Ok -> backend.inject(shareLine(r.value, subject))
                else -> effects.send(ChatEffect.Snackbar(r.errorMessage() ?: "Upload failed"))
            }
        }
    }

    // ───────────────────────── saved toggles ─────────────────────────

    private fun readToggles(key: String): Map<String, Boolean> =
        saved.get<ArrayList<String>>(key).orEmpty().associate { it.substring(1) to (it[0] == '1') }

    private fun writeToggles(key: String, m: Map<String, Boolean>) {
        saved[key] = ArrayList(m.map { (k, v) -> (if (v) "1" else "0") + k })
    }

    companion object {
        /** List publication interval while streaming (spec 14 §2.3). */
        const val SAMPLE_MS = 33L
        const val OUTCOME_MS = VoiceDockModel.OUTCOME_MS
        private const val KEY_DRAFT = "draft"
        private const val KEY_GROUPS = "groups"
        private const val KEY_CARDS = "cards"
        private const val UNSENT = "unsent"
        private const val AGENT_MARKDOWN_CACHE = 120
        private const val KEY_AGENT = "agent"
        private const val KEY_HIDDEN_AGENTS = "hidden_agents"

        /** A one-word slash command (`/help`, `/cost model`), not a path like `/home/x`. */
        val SLASH = Regex("^/[A-Za-z][\\w:-]*(\\s[\\s\\S]*)?$")

        /** §6.15 share line (the Android format, `AssistantViewModel.kt:262-332`). */
        fun shareLine(u: UploadResult, subject: String?): String {
            val size = when {
                u.size < 1024 -> "${u.size} B"
                u.size < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", u.size / 1024.0)
                else -> String.format(java.util.Locale.US, "%.1f MB", u.size / (1024.0 * 1024.0))
            }
            val sb = StringBuilder("[shared file] ${u.filename} ($size, ${u.contentType}) — ${u.url}")
            if (!subject.isNullOrBlank()) sb.append("\nNote: ").append(subject)
            sb.append("\nLocal path: ").append(u.path)
            return sb.toString()
        }

        /** Items in display order for the reversed list. */
        fun reversed(items: List<ChatItem>): List<ChatItem> = items.asReversed()
    }
}

/**
 * The factory the host uses (spec 14 §2.2: `viewModelFactory { initializer { … } }`). The shell keys
 * the ViewModel by the conversation key so each open view keeps its own UI-local state.
 */
fun conversationViewModelFactory(
    backend: () -> ChatBackend,
    voice: ChatVoice,
    toolCards: com.assistant.archie.feature.chat.ui.ToolCardRenderer = com.assistant.archie.feature.chat.ui.DefaultToolCardRenderer,
    title: () -> String? = { null },
    voiceDock: VoiceDockModel? = null,
): androidx.lifecycle.ViewModelProvider.Factory = androidx.lifecycle.viewmodel.viewModelFactory {
    initializer {
        ConversationViewModel(
            backend = backend(),
            voice = voice,
            describe = toolCards::describe,
            saved = createSavedStateHandle(),
            title = title,
            voiceDock = voiceDock,
        )
    }
}
