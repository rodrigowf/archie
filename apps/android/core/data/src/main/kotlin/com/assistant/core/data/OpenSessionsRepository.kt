package com.assistant.core.data

import com.assistant.core.conversation.ConversationState
import com.assistant.core.model.HarnessProvider
import com.assistant.core.model.LiveStatus
import com.assistant.core.model.PoolSession
import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
import com.assistant.core.model.SessionStatus
import com.assistant.core.model.SessionSummary
import com.assistant.core.protocol.ServerFrame
import com.assistant.core.session.OrchestratorChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a workspace tab holds (spec 14 §2.4: tabs are data, not routes). */
sealed interface ItemKey {
    /** The Archie conversation (always first). */
    data object Archie : ItemKey

    data class Agent(val conversation: ConversationKey) : ItemKey

    /** A memory document (a tab on Expanded, a detail screen on Compact; IA §2). */
    data class Memory(val path: String) : ItemKey

    data class Visual(val path: String) : ItemKey
}

enum class ItemKind { ARCHIE, AGENT, MEMORY, VISUAL }

/** The live indicator of a tab / switcher row (IA §3, ST-3). */
enum class TabStatus { IDLE, WORKING, NEEDS_YOU, CONNECTING, DISCONNECTED, STOPPED, NONE }

/** One workspace item, derived (titles per MC-2, status from the conversation state). */
data class WorkspaceItem(
    val key: ItemKey,
    val kind: ItemKind,
    val title: String,
    /** Agent harness chip ("Claude", "Qwen", "Gemini"); `null` for Archie, memory and visuals. */
    val provider: HarnessProvider? = null,
    val status: TabStatus = TabStatus.NONE,
    /** One-line state for the switcher / subtitle ("Ready · 14 turns", "Using Bash…"). */
    val detail: String = "",
    /** Opened by a server event and not looked at yet (P-6 background tab badge). */
    val unread: Boolean = false,
    /** Pool key / history key of a conversation item (`null` for memory and visuals, or not known yet). */
    val localId: String? = null,
    val sdkId: String? = null,
)

/**
 * The workspace "tabs" (spec 14 §2.4): the Archie conversation, open agent sessions and (on
 * Expanded) open memory documents and visuals; [active] is the selected one. Selecting changes state,
 * never the back stack.
 *
 * Focus rules (spec 12 FOCUS-1/3, decision P-6): server events (pool sync, `agent_session_opened`)
 * may add a **background** item with an unread badge but never change [active]; only user calls
 * ([select], [openSession], [newAgentSession], [openMemory], [openVisual]) do, plus Archie's
 * `orchestrator_switch` (§6.11a), which is the user's own request relayed by Archie. A server-side close
 * removes only a sync-opened item the user never focused; anything else stays and shows "stopped".
 *
 * Decision P-1: [close] is the only path that ends a session on the server, and only for an
 * explicit user close. There is deliberately no lifecycle hook here that closes anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenSessionsRepository(
    private val conversations: ConversationRepository,
    private val history: HistoryRepository,
    private val orchestrator: OrchestratorChannel,
    private val scope: CoroutineScope,
) {
    private data class Slot(
        val key: ItemKey,
        val openedBySync: Boolean = false,
        val everFocused: Boolean = false,
        val unread: Boolean = false,
    )

    private val slots = MutableStateFlow<List<Slot>>(emptyList())
    private val _active = MutableStateFlow<ItemKey?>(null)
    val active: StateFlow<ItemKey?> = _active.asStateFlow()

    private val _items = MutableStateFlow<List<WorkspaceItem>>(emptyList())
    val items: StateFlow<List<WorkspaceItem>> = _items.asStateFlow()

    init {
        // The Archie item exists while an Archie conversation is open (adopted, armed or resumed).
        conversations.openKeys.onEach { keys ->
            if (ConversationKey.ARCHIE in keys) {
                slots.update { s -> if (s.any { it.key == ItemKey.Archie }) s else listOf(Slot(ItemKey.Archie)) + s }
                _active.compareAndSet(null, ItemKey.Archie)
            }
        }.launchIn(scope)
        // §6.11a SW-2: Archie moved this device to a past conversation because the user asked it to
        // (by voice or text), so it is a user action: focus the resumed view.
        conversations.events.onEach { if (it is ConversationEvent.ArchieSwitched) focus(ItemKey.Archie) }.launchIn(scope)
        // Watcher events (T-7): a session opened elsewhere becomes a background item (P-6).
        val frames = orchestrator.subscribeFrames()
        scope.launch {
            frames.consumeEach { f ->
                when (f) {
                    is ServerFrame.AgentSessionOpened -> {
                        val pool = history.syncPool()
                        if (!f.isOrchestrator) f.sessionId?.let { onAgentOpened(it, f.sdkSessionId, pool) }
                        history.refreshListSoon()
                    }
                    is ServerFrame.AgentSessionClosed -> {
                        if (!f.isOrchestrator) f.sessionId?.let { onAgentClosed(it) }
                        history.refreshListSoon()
                    }
                    else -> Unit
                }
            }
        }
        // Derived items: slots × titles × per-conversation state.
        slots.flatMapLatest { list ->
            val states = list.map { s ->
                when (val k = s.key) {
                    ItemKey.Archie -> conversations.state(ConversationKey.ARCHIE)
                    is ItemKey.Agent -> conversations.state(k.conversation)
                    else -> flowOf(null)
                }
            }
            if (states.isEmpty()) flowOf(emptyList())
            else combine(states) { arr -> list.mapIndexed { i, s -> s to arr[i] } }
        }.let { pairs ->
            combine(pairs, history.sessions, history.pool) { p, _, _ -> p.map { (slot, st) -> derive(slot, st) } }
        }.onEach { _items.value = it }.launchIn(scope)
    }

    // ───────────── user actions ─────────────

    fun select(key: ItemKey) {
        if (slots.value.none { it.key == key }) return
        _active.value = key
        slots.update { l -> l.map { if (it.key == key) it.copy(everFocused = true, unread = false) else it } }
        (key as? ItemKey.Agent)?.let { conversations.touch(it.conversation) }
    }

    /** The next / previous item (Compact title swipe, Ctrl+Tab). Wraps around. */
    fun selectRelative(delta: Int) {
        val list = slots.value
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.key == _active.value }.coerceAtLeast(0)
        select(list[(i + delta).mod(list.size)].key)
    }

    /** Opens a history row: an Archie conversation resumes on the one orchestrator socket, an agent opens its view. */
    fun openSession(summary: SessionSummary) {
        if (summary.isOrchestrator) {
            val cur = conversations.current(ConversationKey.ARCHIE)?.ref
            if (cur?.sdkId != summary.sdkId) conversations.resumeArchie(summary.sdkId)
            focus(ItemKey.Archie)
            return
        }
        val live = history.pool.value.firstOrNull { it.sdkId == summary.sdkId && !it.isOrchestrator }
        val ref = SessionRef(
            localId = live?.localId ?: summary.localId ?: java.util.UUID.randomUUID().toString(),
            sdkId = summary.sdkId,
            kind = SessionKind.AGENT,
            provider = summary.provider,
            live = live != null,
            liveStatus = live?.status,
        )
        focus(ItemKey.Agent(conversations.openAgent(ref)))
    }

    /** A live pool session (e.g. from the drawer's Open now) as a focused view. */
    fun openLive(pool: PoolSession) {
        if (pool.isOrchestrator) { focus(ItemKey.Archie); return }
        focus(ItemKey.Agent(conversations.openAgent(pool.toRef())))
    }

    /**
     * A tap on an approval notification (§6.9): focuses the agent view of pool key [localId],
     * opening it from the live pool when it is not open here. False = no such live agent session.
     */
    suspend fun openAgentByLocalId(localId: String): Boolean {
        slots.value.firstOrNull { s ->
            (s.key as? ItemKey.Agent)?.let { conversations.current(it.conversation)?.ref?.localId == localId } == true
        }?.let { select(it.key); return true }
        val live = history.pool.value.firstOrNull { it.localId == localId && !it.isOrchestrator }
            ?: history.syncPool()?.firstOrNull { it.localId == localId && !it.isOrchestrator }
            ?: return false
        openLive(live)
        return true
    }

    /** Opens a fork / continuation result (user-initiated, focused). */
    fun openRef(ref: SessionRef) {
        if (ref.kind == SessionKind.ORCHESTRATOR) {
            ref.sdkId?.let { conversations.resumeArchie(it) }
            focus(ItemKey.Archie)
        } else {
            focus(ItemKey.Agent(conversations.openAgent(ref)))
        }
    }

    fun newAgentSession(provider: HarnessProvider? = null) = focus(ItemKey.Agent(conversations.newAgent(provider)))

    fun newArchieConversation() {
        conversations.newArchie()
        focus(ItemKey.Archie)
    }

    fun openMemory(path: String) = focus(ItemKey.Memory(path))
    fun openVisual(path: String) = focus(ItemKey.Visual(path))

    /**
     * Explicit close (§6.7, P-1): agent and Archie views close the session for everyone; memory and
     * visual tabs just go away. Focus moves to the neighbour, Archie first.
     */
    suspend fun close(key: ItemKey): Boolean {
        val ok = when (key) {
            ItemKey.Archie -> conversations.close(ConversationKey.ARCHIE)
            is ItemKey.Agent -> conversations.close(key.conversation)
            else -> true
        }
        dropSlot(key)
        history.syncPool()
        return ok
    }

    /** After a delete (§6.8): views of that session go away (their pool entry was closed). */
    fun forgetSession(sdkId: String) {
        slots.value.filter { s -> (s.key as? ItemKey.Agent)?.let { conversations.current(it.conversation)?.ref?.sdkId == sdkId } == true }
            .forEach { s -> (s.key as ItemKey.Agent).let { conversations.forget(it.conversation); dropSlot(it) } }
    }

    /** T-15: a new server. Everything local goes; nothing is closed on either server. */
    fun resetForServer() {
        slots.value = emptyList()
        _active.value = null
    }

    // ───────────── server-driven (never changes focus) ─────────────

    /**
     * `agent_session_opened` (§3.7): a session started on another device or by Archie opens as a
     * **background** item with an unread badge; [active] does not change (FOCUS-1, P-6). Live
     * sessions found by a plain `syncPool` are only listed (drawer / switcher "Open now"), not opened.
     */
    private fun onAgentOpened(localId: String, sdkId: String?, pool: List<PoolSession>?) {
        val open = slots.value.any { s ->
            (s.key as? ItemKey.Agent)?.let { conversations.current(it.conversation)?.ref?.localId == localId } == true
        }
        if (open) return
        val row = pool?.firstOrNull { it.localId == localId }
        val ref = row?.toRef() ?: SessionRef(localId, sdkId, SessionKind.AGENT, provider = null, live = true)
        val key = ItemKey.Agent(conversations.openAgent(ref))
        slots.update { l -> if (l.any { it.key == key }) l else l + Slot(key, openedBySync = true, unread = true) }
    }

    private fun onAgentClosed(localId: String) {
        val slot = slots.value.firstOrNull { s ->
            (s.key as? ItemKey.Agent)?.let { conversations.current(it.conversation)?.ref?.localId == localId } == true
        } ?: return
        if (slot.openedBySync && !slot.everFocused && _active.value != slot.key) {
            conversations.forget((slot.key as ItemKey.Agent).conversation)
            dropSlot(slot.key)
        }
        // Otherwise the view stays and shows "stopped" (FOCUS-3); the reducer saw the frame.
    }

    private fun focus(key: ItemKey) {
        slots.update { l ->
            when {
                l.any { it.key == key } -> l
                key == ItemKey.Archie -> listOf(Slot(key)) + l
                else -> l + Slot(key)
            }
        }
        select(key)
    }

    private fun dropSlot(key: ItemKey) {
        val before = slots.value
        val idx = before.indexOfFirst { it.key == key }
        if (idx < 0) return
        val after = before.filterNot { it.key == key }
        slots.value = after
        if (_active.value == key) _active.value = after.getOrNull((idx - 1).coerceAtLeast(0))?.key ?: after.firstOrNull()?.key
    }

    private fun derive(slot: Slot, st: ConversationState?): WorkspaceItem = when (val k = slot.key) {
        ItemKey.Archie -> WorkspaceItem(
            key = k, kind = ItemKind.ARCHIE,
            title = archieTitle(history.titleFor(st?.ref?.sdkId, st?.ref?.localId, ARCHIE_PLACEHOLDER)),
            status = statusOf(st), detail = detailOf(st, "Archie"), unread = slot.unread,
            localId = st?.ref?.localId, sdkId = st?.ref?.sdkId,
        )
        is ItemKey.Agent -> WorkspaceItem(
            key = k, kind = ItemKind.AGENT,
            title = history.titleFor(st?.ref?.sdkId, st?.ref?.localId, AGENT_PLACEHOLDER),
            provider = st?.ref?.provider,
            status = statusOf(st), detail = detailOf(st, null), unread = slot.unread,
            localId = st?.ref?.localId, sdkId = st?.ref?.sdkId,
        )
        is ItemKey.Memory -> WorkspaceItem(k, ItemKind.MEMORY, k.path.substringAfterLast('/'), detail = memoryDetail(k.path))
        is ItemKey.Visual -> WorkspaceItem(k, ItemKind.VISUAL, k.path.substringAfterLast('/').substringBeforeLast('.'), detail = "Visual")
    }

    private fun PoolSession.toRef() = SessionRef(
        localId = localId, sdkId = sdkId, kind = if (isOrchestrator) SessionKind.ORCHESTRATOR else SessionKind.AGENT,
        provider = history.sessions.value.value?.firstOrNull { it.sdkId == sdkId }?.provider,
        live = true, liveStatus = status,
    )

    companion object {
        const val ARCHIE_PLACEHOLDER = "Archie"
        const val NEW_CONVERSATION = "New conversation"
        private val GENERIC_ARCHIE = Regex("^\\s*(orchestrator|archie)?\\s*$", RegexOption.IGNORE_CASE)

        /**
         * IA §1 / CR-9: the UI never says "Orchestrator". The backend titles an untitled Archie
         * conversation "Orchestrator", and the placeholder is "Archie": both show as
         * "New conversation" (same rule as `SessionTitles.conversationTitle` and the web).
         */
        fun archieTitle(raw: String): String = raw.trim().let { if (GENERIC_ARCHIE.matches(it)) NEW_CONVERSATION else it }
        const val AGENT_PLACEHOLDER = "New agent session"

        /** ST-3: connecting, subscribed-idle, busy, needs-you, stopped/terminated, disconnected/failed. */
        fun statusOf(st: ConversationState?): TabStatus = when {
            st == null -> TabStatus.NONE
            st.newestPendingPermission() != null || st.agentApprovals.isNotEmpty() -> TabStatus.NEEDS_YOU
            st.status == SessionStatus.STOPPED || st.status == SessionStatus.TERMINATED -> TabStatus.STOPPED
            st.connection == com.assistant.core.model.ConnectionState.FAILED -> TabStatus.DISCONNECTED
            st.connection == com.assistant.core.model.ConnectionState.OFFLINE && st.connectionBanner != null -> TabStatus.DISCONNECTED
            st.status.busy || st.inTurn -> TabStatus.WORKING
            st.status == SessionStatus.CONNECTING -> TabStatus.CONNECTING
            else -> TabStatus.IDLE
        }

        /** The live status line (Compact subtitle, switcher rows). */
        fun detailOf(st: ConversationState?, prefix: String?): String {
            val text = when {
                st == null -> "Not connected"
                st.newestPendingPermission() != null || st.agentApprovals.isNotEmpty() -> "Waiting for your approval"
                st.status == SessionStatus.TERMINATED -> "Session ended"
                st.status == SessionStatus.STOPPED -> "Stopped"
                st.connectionBanner != null && st.connection != com.assistant.core.model.ConnectionState.SUBSCRIBED -> "Reconnecting…"
                st.status == SessionStatus.THINKING -> "Thinking…"
                st.status == SessionStatus.TOOL_USE -> "Using tools…"
                st.status == SessionStatus.STREAMING || st.status == SessionStatus.PROCESSING -> "Working…"
                st.status == SessionStatus.RETRYING -> "Retrying…"
                st.status == SessionStatus.COMPACTING -> "Compacting…"
                st.status == SessionStatus.CONNECTING -> "Connecting…"
                st.counters.turns > 0 -> "Ready · ${st.counters.turns} turns"
                else -> "Ready"
            }
            return if (prefix != null) "$prefix · ${text.replaceFirstChar { it.lowercase() }}" else text
        }

        private fun memoryDetail(path: String): String {
            val dir = path.substringBeforeLast('/', "")
            return if (dir.isEmpty()) "Memory" else "Memory · $dir"
        }

        /** Pool `status` → tab status for rows that have no open view (drawer Open now). */
        fun statusOf(live: LiveStatus?): TabStatus = when (live) {
            LiveStatus.STREAMING, LiveStatus.TOOL_USE, LiveStatus.THINKING -> TabStatus.WORKING
            LiveStatus.DISCONNECTED -> TabStatus.DISCONNECTED
            LiveStatus.IDLE, LiveStatus.INTERRUPTED -> TabStatus.IDLE
            null -> TabStatus.NONE
        }
    }
}
