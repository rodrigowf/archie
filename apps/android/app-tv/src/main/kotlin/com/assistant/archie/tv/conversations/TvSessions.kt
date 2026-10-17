package com.assistant.archie.tv.conversations

import androidx.compose.runtime.Immutable
import com.assistant.archie.feature.sessions.ConflictChoice
import com.assistant.archie.feature.sessions.RepositorySessionsBackend
import com.assistant.archie.feature.sessions.SessionDialog
import com.assistant.archie.feature.sessions.SessionTarget
import com.assistant.archie.feature.sessions.SessionsController
import com.assistant.archie.feature.sessions.SessionsIntent
import com.assistant.archie.feature.sessions.SessionsUiState
import com.assistant.archie.tv.graph.TvAppGraph
import com.assistant.archie.tv.shell.Conversation
import com.assistant.archie.tv.shell.TvNavigator
import com.assistant.core.data.ConversationKey
import com.assistant.core.data.ItemKey
import com.assistant.core.data.WorkspaceItem
import com.assistant.core.model.HarnessInfo
import com.assistant.core.model.HarnessProvider
import com.assistant.core.model.PoolSession
import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
import com.assistant.core.model.SessionSummary
import com.assistant.core.network.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.WeakHashMap

/*
 * Opening conversations from the TV (spec 15 §3.5, spec 12 §3.7 OPEN-1..4). Every open here is a
 * user action, so it may resume a session; only the Conversation route's own resolution of a
 * restored back stack is automatic, and that one only reattaches to what the pool lists.
 *
 * The session flows themselves are `:feature:sessions`' SessionsController over the same
 * repositories as `:app-main` (the §6.11 Archie conflict dialog, rename / duplicate / close /
 * delete with confirmations). The TV only adds navigation: after a user open, the Conversation
 * route of the view that open focused is pushed.
 */

/** What a Home card / Conversations row opens. */
@Immutable
sealed interface OpenTarget {
    /** The current Archie (orchestrator) conversation of this device. */
    data object ArchieCurrent : OpenTarget

    /** A conversation in "Open now" (the server's open set): its view here, or the pool row (reattach). */
    data class OpenItem(val key: ItemKey, val localId: String?, val sdkId: String?, val archie: Boolean) : OpenTarget

    /** A history row (user open: an Archie conversation resumes, an agent opens or resumes). */
    data class History(val summary: SessionSummary) : OpenTarget
}

/** An open conversation view of this device, as the route resolution sees it. */
data class ViewRef(val key: ConversationKey, val localId: String, val sdkId: String?, val kind: SessionKind)

/** Pure open rules (JVM-tested). */
object ConversationRouting {
    sealed interface Resolution {
        /** The Archie view (whatever conversation it holds). */
        data object Archie : Resolution

        /** A view open here. */
        data class View(val key: ConversationKey) : Resolution

        /** Not open here but in the pool: automatic attach is allowed (OPEN-2: it only reattaches). */
        data class Reattach(val pool: PoolSession) : Resolution

        /** Neither: only a user action may open (and so resume) it. */
        data object NotOpen : Resolution
    }

    /** A restored / pushed Conversation route → the view it shows. Never starts anything by itself. */
    fun resolve(route: Conversation, views: List<ViewRef>, pool: List<PoolSession>): Resolution {
        if (route.archie) return Resolution.Archie
        fun matches(localId: String?, sdkId: String?) =
            (route.localId != null && localId == route.localId) || (route.sdkId != null && sdkId == route.sdkId)
        views.firstOrNull { it.kind == SessionKind.AGENT && matches(it.localId, it.sdkId) }?.let { return Resolution.View(it.key) }
        pool.firstOrNull { !it.isOrchestrator && matches(it.localId, it.sdkId) }?.let { return Resolution.Reattach(it) }
        return Resolution.NotOpen
    }

    /** What a user open of a history row does. */
    enum class HistoryAction { SHOW_ARCHIE, RESUME_ARCHIE, OPEN_AGENT }

    fun historyAction(summary: SessionSummary, archieViewSdkId: String?): HistoryAction = when {
        !summary.isOrchestrator -> HistoryAction.OPEN_AGENT
        archieViewSdkId != null && archieViewSdkId == summary.sdkId -> HistoryAction.SHOW_ARCHIE
        else -> HistoryAction.RESUME_ARCHIE
    }

    /** The route for an open view's ref. */
    fun routeOf(ref: SessionRef): Conversation =
        if (ref.kind == SessionKind.ORCHESTRATOR) Conversation(archie = true) else Conversation(localId = ref.localId, sdkId = ref.sdkId)
}

/**
 * The session flows of the TV app: one per process (built on first use by [of]). Navigation goes
 * through the navigator the caller passes, or the live one ([TvAppGraph.navHolder]) for flows that
 * finish later (the Archie conflict dialog, a start that has to wait for the socket).
 */
class TvSessions internal constructor(private val graph: TvAppGraph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val controller = SessionsController(
        RepositorySessionsBackend(graph.conversations, graph.openSessions, graph.history, graph.orchestrator, graph.api),
        scope,
    )

    /** Closes / deletes this TV confirmed: their own "closed" notice is not news (B11). */
    private val ownCloses = OwnCloseFilter(now = System::currentTimeMillis)

    /** Dialogs, busy label and snack of the session flows. */
    val ui: StateFlow<SessionsUiState> get() = controller.state

    init {
        // Snackbar messages become toasts (a TV toast is not focusable, so actions are dropped).
        scope.launch {
            controller.state.map { it.snack }.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { s ->
                if (s != null) graph.toasts.show(s.message, error = s.error, seconds = if (s.error) 6 else 4)
            }
        }
        scope.launch {
            graph.openSessions.notices.collect { n ->
                // A close / delete this TV just confirmed comes back as "<title> was closed elsewhere":
                // not news. Only that session's notice, once, within a few seconds (B11).
                if (n.reopenSdkId == null && ownCloses.consume(n.message)) return@collect
                graph.toasts.show(n.message, error = n.reopenSdkId != null, seconds = 6)
            }
        }
    }

    private fun navOr(nav: TvNavigator?): TvNavigator? = nav ?: graph.navHolder.navigator

    // ───────────────────────── opening ─────────────────────────

    fun open(target: OpenTarget, nav: TvNavigator) {
        when (target) {
            OpenTarget.ArchieCurrent -> nav.push(Conversation(archie = true))
            is OpenTarget.OpenItem -> openItem(target, nav)
            is OpenTarget.History -> openHistory(target.summary, nav)
        }
    }

    private fun openItem(t: OpenTarget.OpenItem, nav: TvNavigator) {
        if (t.archie || t.key == ItemKey.Archie) {
            nav.push(Conversation(archie = true))
            return
        }
        graph.openSessions.select(t.key)          // a pool row with no view here gets its (reattaching) view
        if (!pushActiveAgent(nav, t.localId, t.sdkId)) {
            // The pool no longer lists it and no view is open here.
            graph.toasts.show("That conversation is no longer open")
            graph.history.refreshAll()
        }
    }

    fun openHistory(summary: SessionSummary, nav: TvNavigator) {
        val archieSdk = graph.conversations.current(ConversationKey.ARCHIE)?.ref?.sdkId
        when (ConversationRouting.historyAction(summary, archieSdk)) {
            ConversationRouting.HistoryAction.SHOW_ARCHIE -> nav.push(Conversation(archie = true))
            ConversationRouting.HistoryAction.RESUME_ARCHIE -> openArchieConversation(summary.sdkId, nav)
            ConversationRouting.HistoryAction.OPEN_AGENT -> {
                graph.openSessions.openSession(summary)
                pushActiveAgent(nav, sdkId = summary.sdkId)
            }
        }
    }

    /** A past Archie conversation (spec 12 §6.11: asks first when another one runs). */
    fun openArchieConversation(sdkId: String, nav: TvNavigator? = null) {
        if (graph.conversations.current(ConversationKey.ARCHIE)?.ref?.sdkId == sdkId) {
            navOr(nav)?.push(Conversation(archie = true))
            return
        }
        val before = graph.conversations.current(ConversationKey.ARCHIE)?.ref?.localId
        controller.requestResumeArchie(sdkId)
        watchArchieStart(before, nav)
    }

    /** New Archie conversation (§6.11: with Archie running anywhere the conflict dialog asks). */
    fun newArchie(nav: TvNavigator? = null) {
        val before = graph.conversations.current(ConversationKey.ARCHIE)?.ref?.localId
        controller.requestNewArchie()
        watchArchieStart(before, nav)
    }

    /** §6.10 new agent session with [provider] (null = the server's default harness). */
    fun newAgent(provider: HarnessProvider?, nav: TvNavigator) {
        graph.openSessions.newAgentSession(provider)
        pushActiveAgent(nav)
    }

    /** A fork / continuation result (ChatEffect.OpenSession): user-initiated, so it may resume. */
    fun openRef(ref: SessionRef, nav: TvNavigator) {
        val sdk = ref.sdkId
        if (ref.kind == SessionKind.ORCHESTRATOR) {
            if (sdk != null) openArchieConversation(sdk, nav) else nav.push(Conversation(archie = true))
        } else {
            graph.openSessions.openRef(ref)
            pushActiveAgent(nav)
        }
    }

    /**
     * Automatic open of a restored route: only a pool session is (re)attached (OPEN-2); returns
     * false when there is nothing to attach to.
     */
    fun reattach(pool: PoolSession): Boolean {
        if (pool.isOrchestrator) return false
        graph.openSessions.openLive(pool)
        return true
    }

    /**
     * Pushes the agent view the last user open focused (the open calls set it synchronously). With
     * [localId] / [sdkId] the focused view must be that session (a failed select leaves the old one).
     */
    private fun pushActiveAgent(nav: TvNavigator, localId: String? = null, sdkId: String? = null): Boolean {
        val active = graph.openSessions.active.value as? ItemKey.Agent ?: return false
        val ref = graph.conversations.current(active.conversation)?.ref ?: return false
        if (localId != null || sdkId != null) {
            val same = (localId != null && ref.localId == localId) || (sdkId != null && ref.sdkId == sdkId)
            if (!same) return false
        }
        nav.push(ConversationRouting.routeOf(ref))
        return true
    }

    /**
     * After a user start of Archie: push the Archie conversation once the view is the new one; a
     * conflict dialog instead waits for the user's choice ([onIntent]).
     */
    private fun watchArchieStart(beforeLocalId: String?, nav: TvNavigator?) {
        scope.launch {
            val r = withTimeoutOrNull(START_WAIT_MS) {
                combine(controller.state, graph.conversations.state(ConversationKey.ARCHIE)) { ui, st ->
                    when {
                        ui.dialog is SessionDialog.ArchieConflict -> WAIT_DIALOG
                        st != null && st.ref.localId != beforeLocalId -> WAIT_STARTED
                        else -> 0
                    }
                }.first { it != 0 }
            }
            when (r) {
                WAIT_STARTED -> navOr(nav)?.push(Conversation(archie = true))
                WAIT_DIALOG -> Unit
                else -> graph.toasts.show("Archie didn't start. Check the connection and try again.", error = true)
            }
        }
    }

    // ───────────────────────── actions with dialogs ─────────────────────────

    /** Session dialog answers; a resolved Archie conflict (Open / Stop and start) shows the Archie conversation. */
    fun onIntent(i: SessionsIntent) {
        // Stamped on confirm (not on request: a cancelled dialog closes nothing), for that session.
        when (val d = controller.state.value.dialog) {
            is SessionDialog.Close -> if (i == SessionsIntent.ConfirmClose) ownCloses.expect(titlesOf(d.key, d.title))
            is SessionDialog.Delete -> if (i == SessionsIntent.ConfirmDelete) {
                ownCloses.expect(setOf(d.target.title, graph.history.titleFor(d.target.sdkId, d.target.localId, AGENT_PLACEHOLDER)))
            }
            else -> Unit
        }
        controller.onIntent(i)
        if (i is SessionsIntent.ResolveConflict && i.choice != ConflictChoice.CANCEL) {
            navOr(null)?.let { nav -> if (nav.current != Conversation(archie = true)) nav.push(Conversation(archie = true)) }
        }
    }

    fun rename(target: SessionTarget) = controller.onIntent(SessionsIntent.RequestRename(target))
    fun duplicate(target: SessionTarget) = controller.onIntent(SessionsIntent.Duplicate(target))
    fun delete(target: SessionTarget) = controller.onIntent(SessionsIntent.RequestDelete(target))
    fun close(item: WorkspaceItem) {
        controller.onIntent(SessionsIntent.RequestClose(item))
        // An idle agent closes at once (no dialog): that is the confirmation.
        if (controller.state.value.dialog !is SessionDialog.Close) ownCloses.expect(titlesOf(item.key, item.title))
    }

    /** The titles a "closed" notice of [key] can start with (the repository names it from history). */
    private fun titlesOf(key: ItemKey, shown: String): Set<String> {
        val ref = (key as? ItemKey.Agent)?.let { graph.conversations.current(it.conversation)?.ref }
        return setOfNotNull(shown, ref?.let { graph.history.titleFor(it.sdkId, it.localId, AGENT_PLACEHOLDER) })
    }

    /** The server's harness catalog (`GET /api/config/harnesses`) for the New agent picker. */
    suspend fun harnesses(): List<HarnessInfo>? = when (val r = graph.api.harnesses()) {
        is ApiResult.Ok -> r.value
        else -> null
    }

    companion object {
        const val START_WAIT_MS = 15_000L
        /** `OpenSessionsRepository`'s placeholder title of an agent without history. */
        private const val AGENT_PLACEHOLDER = "New agent session"
        private const val WAIT_DIALOG = 1
        private const val WAIT_STARTED = 2

        private val instances = WeakHashMap<TvAppGraph, TvSessions>()

        /** The process's instance for [graph] (the graph is process-scoped; this keeps the graph file untouched). */
        fun of(graph: TvAppGraph): TvSessions = synchronized(instances) { instances.getOrPut(graph) { TvSessions(graph) } }
    }
}
