package com.assistant.core.data

import com.assistant.core.conversation.ConversationEffect
import com.assistant.core.conversation.ConversationInput
import com.assistant.core.conversation.ConversationReducer
import com.assistant.core.conversation.ConversationState
import com.assistant.core.conversation.PageMode
import com.assistant.core.conversation.RewindIndex
import com.assistant.core.model.ConnectionState
import com.assistant.core.model.LivePoolSnapshot
import com.assistant.core.model.SessionStatus
import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
import com.assistant.core.network.ApiResult
import com.assistant.core.network.ArchieApi
import com.assistant.core.network.FrameSocket
import com.assistant.core.network.SendResult
import com.assistant.core.network.SocketEvent
import com.assistant.core.network.SocketState
import com.assistant.core.network.UrlScheme
import com.assistant.core.network.WsEndpoint
import com.assistant.core.protocol.ClientFrame
import com.assistant.core.protocol.PaginatedMessagesDto
import com.assistant.core.protocol.ServerFrame
import com.assistant.core.session.ChannelEvent
import com.assistant.core.session.OrchestratorChannel
import com.assistant.core.session.OrchestratorRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Stable key of an open conversation view. It survives ID-1 re-keying of the session's `localId`. */
@JvmInline
value class ConversationKey(val value: String) {
    companion object {
        /** The one Archie (orchestrator) conversation of this app instance (T-6). */
        val ARCHIE = ConversationKey("archie")

        fun agent(localId: String) = ConversationKey("agent:$localId")
    }
}

/** One-shot things the UI may surface (snackbar, conflict dialog). Never conversation entries. */
sealed interface ConversationEvent {
    val key: ConversationKey

    data class StartFailed(override val key: ConversationKey, val code: String, val detail: String?) : ConversationEvent
    data class SideError(override val key: ConversationKey, val code: String, val detail: String?) : ConversationEvent
    data class TurnEnded(override val key: ConversationKey) : ConversationEvent

    /**
     * Spec 12 POOL-2: the agent session [localId] was found closed elsewhere on a reconnect /
     * foreground (it left the pool of the server it was live on). The view already stopped; the
     * workspace treats it like the live `agent_session_closed{is_orchestrator:false}`.
     */
    data class ClosedElsewhere(override val key: ConversationKey, val localId: String) : ConversationEvent

    /** `error{orchestrator_active}` while the user asked to start/attach (§6.11 conflict dialog, B-06). */
    data class OrchestratorConflict(val detail: String?) : ConversationEvent {
        override val key: ConversationKey get() = ConversationKey.ARCHIE
    }

    /** The probe found no orchestrator, or recovery gave up: offer History / New (no auto-navigation, FOCUS-1). */
    data object NoOrchestrator : ConversationEvent {
        override val key: ConversationKey get() = ConversationKey.ARCHIE
    }

    /**
     * §6.11a: Archie moved this device to a past conversation (its `switch_conversation` tool). The
     * Archie view now resumes it; the request was the user's (SW-2), so the UI focuses it.
     */
    data class ArchieSwitched(val sdkId: String, val title: String?) : ConversationEvent {
        override val key: ConversationKey get() = ConversationKey.ARCHIE
    }
}

/** Result of answering an agent approval (§6.9). */
sealed interface ApprovalAnswer {
    data object Sent : ApprovalAnswer

    /** 409: someone (another device, the orchestrator) answered first, or the request expired. */
    data object AlreadyAnswered : ApprovalAnswer
    data class Failed(val message: String) : ApprovalAnswer
}

/** Result of rewind/fork (§6.5). */
sealed interface CutResult {
    data class Done(val ref: SessionRef) : CutResult
    data class Failed(val message: String) : CutResult
}

/**
 * The spec-12 data layer per open conversation (spec 14 §1.2, §2.1, §2.3):
 *
 * - **Reducer confinement**: every conversation has one ordered, lossless input queue drained by one
 *   consumer on a single-thread view of [dispatcher] (L-2, L-4). Socket frames, local actions and REST
 *   pages are all [ConversationInput]s; the pure [ConversationReducer] turns them into state + effects,
 *   and the effects (start, reloads, lookups) are executed here.
 * - **Transport**: the Archie conversation rides the app's single [OrchestratorChannel] (T-6); every
 *   agent conversation owns one chat socket from [AgentSocketPool] (T-5).
 * - **P-1**: nothing here sends `stop` or calls the pool `close` from a lifecycle path. Only [close]
 *   (an explicit user action) does.
 *
 * State is published per key through a stable slot, so a rewind or a new Archie session replaces the
 * conversation without breaking observers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRepository(
    private val api: ArchieApi,
    private val orchestrator: OrchestratorChannel,
    private val agentSockets: AgentSocketPool,
    private val history: HistoryRepository,
    private val scope: CoroutineScope,
    private val serverUrl: () -> String?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val reconcileDelayMs: Long = RECONCILE_DEBOUNCE_MS,
) {
    private val confined = dispatcher.limitedParallelism(1)
    private val lock = Any()
    private val handles = LinkedHashMap<ConversationKey, Handle>()
    private val slots = HashMap<ConversationKey, MutableStateFlow<ConversationState?>>()

    private val _events = MutableSharedFlow<ConversationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ConversationEvent> = _events.asSharedFlow()

    private val _openKeys = MutableStateFlow<List<ConversationKey>>(emptyList())
    /** Keys with a live handle, in open order. */
    val openKeys: StateFlow<List<ConversationKey>> = _openKeys.asStateFlow()

    init {
        val frames = orchestrator.subscribeFrames()
        val channelEvents = orchestrator.subscribeEvents()
        scope.launch { frames.consumeEach { f -> archieHandle()?.post(ConversationInput.Frame(f)) } }
        scope.launch { channelEvents.consumeEach { onChannelEvent(it) } }
    }

    // ───────────────────────── observation ─────────────────────────

    /** The state of [key]; `null` while no conversation is open under it. Stable across replacements. */
    fun state(key: ConversationKey): StateFlow<ConversationState?> = slot(key).asStateFlow()

    fun current(key: ConversationKey): ConversationState? = slot(key).value

    // ───────────────────────── opening ─────────────────────────

    /**
     * Opens (or returns) the agent conversation for [ref]: cold open per §5.2 (hold frames, connect,
     * fetch the last page when `sdkId` is known, then apply the held frames). Never re-opens an
     * already open session.
     */
    fun openAgent(ref: SessionRef): ConversationKey {
        require(ref.kind == SessionKind.AGENT)
        synchronized(lock) {
            handles.entries.firstOrNull { (_, h) ->
                h.kind == SessionKind.AGENT &&
                    (h.state.value.ref.localId == ref.localId || (ref.sdkId != null && h.state.value.ref.sdkId == ref.sdkId))
            }?.let { return it.key }
        }
        val key = ConversationKey.agent(ref.localId)
        install(key, AgentHandle(key, ConversationState.initial(ref)))
        return key
    }

    /** §6.10: a brand-new agent session (`sdkId` null until its first turn ends). */
    fun newAgent(provider: com.assistant.core.model.HarnessProvider? = null): ConversationKey =
        openAgent(SessionRef(localId = newId(), sdkId = null, kind = SessionKind.AGENT, provider = provider))

    /** §6.11 new orchestrator: arm a client-minted id; the channel adopts it on the open socket. */
    fun newArchie() {
        orchestrator.armNewSession(newId())
    }

    /**
     * §6.11 resume a past Archie conversation: `start{local_id: uuid, resume_sdk_id: jsonl}` on the
     * one orchestrator socket. `orchestrator_active` comes back as [ConversationEvent.OrchestratorConflict].
     * With [localId] (B-06 "Open the running one", §6.11 attach / G-15) the `start` reuses the running
     * orchestrator's pool key, so the server attaches instead of starting a second one.
     */
    fun resumeArchie(sdkId: String, localId: String? = null) {
        orchestrator.markUserIntent()
        openArchie(localId ?: newId(), sdkId)
    }

    // ───────────────────────── actions (§6) ─────────────────────────

    /** §6.1 / §6.2. While a permission is pending the server turns the text into "deny with feedback" (§6.9). */
    fun send(key: ConversationKey, text: String) = handle(key)?.let { h ->
        h.post(ConversationInput.LocalSend(text))
        h.sendUserFrame(ClientFrame.Send(text))
    }

    /** §6.3. In voice mode the Stop control is `voice_stop` (the voice host's job), not this. */
    fun interrupt(key: ConversationKey) = handle(key)?.let { h ->
        h.post(ConversationInput.LocalInterrupt)
        h.sendFrame(ClientFrame.Interrupt)
    }

    fun compact(key: ConversationKey) = handle(key)?.let { h ->
        h.post(ConversationInput.LocalCompact)
        h.sendFrame(ClientFrame.Compact)
    }

    /** Agent slash command. */
    fun command(key: ConversationKey, text: String) = handle(key)?.sendFrame(ClientFrame.Command(text))

    /** §6.9 approve / deny (deny-with-feedback is a plain [send]). */
    fun respondToPermission(key: ConversationKey, requestId: String, allow: Boolean, message: String? = null) =
        handle(key)?.sendFrame(ClientFrame.PermissionResponse(requestId, if (allow) "allow" else "deny", message))

    /**
     * §6.9 answer an agent's permission from anywhere (Agent approvals list, notification actions).
     * With [preferSocket] and the agent's view open here, the answer rides its own chat socket;
     * otherwise (or when that socket cannot send) `POST /api/sessions/{localId}/permission`. The
     * card / notification goes away on the `permission_resolved` the server broadcasts, not here.
     */
    suspend fun answerAgentApproval(
        agentLocalId: String,
        requestId: String,
        allow: Boolean,
        message: String? = null,
        preferSocket: Boolean = true,
    ): ApprovalAnswer {
        if (preferSocket) {
            val h = synchronized(lock) {
                handles.values.firstOrNull { it.kind == SessionKind.AGENT && it.state.value.ref.localId == agentLocalId }
            }
            val frame = ClientFrame.PermissionResponse(requestId, if (allow) "allow" else "deny", message)
            if (h != null && h.sendFrame(frame) == SendResult.SENT) return ApprovalAnswer.Sent
        }
        return when (val r = api.resolvePermission(agentLocalId, requestId, allow, message)) {
            is ApiResult.Ok -> ApprovalAnswer.Sent
            is ApiResult.HttpError -> when {
                r.code == 409 -> ApprovalAnswer.AlreadyAnswered
                r.code == 404 && r.detail == "Not Found" -> ApprovalAnswer.Failed("This server can't answer from here. Open that session to answer.")
                r.code == 404 -> ApprovalAnswer.Failed("That agent session is no longer running")
                else -> ApprovalAnswer.Failed(r.errorMessage() ?: "Couldn't send the answer")
            }
            else -> ApprovalAnswer.Failed(r.errorMessage() ?: "Couldn't send the answer")
        }
    }

    /** §6.15 shared text / upload link on the Archie conversation (held until subscribed by the channel). */
    fun inject(text: String) {
        archieHandle()?.post(ConversationInput.LocalInject(text))
        orchestrator.inject(text)
    }

    /**
     * §4.7 / VT-2: an inbound OpenAI (WebRTC) data-channel event of the voice session this device
     * owns. The server never mirrors these back, so the owner feeds its own transcripts (user
     * transcription, streaming assistant deltas, the final transcript) into the Archie timeline
     * here — the reducer's `datachannel_event` input. WS providers arrive as `voice_event` frames.
     */
    fun voiceDataChannelEvent(event: kotlinx.serialization.json.JsonObject) {
        archieHandle()?.post(ConversationInput.DataChannelEvent(event))
    }

    /**
     * The voice host shipped a talk-phrase capture (`send_audio`) on the orchestrator socket: a
     * "Voice message" bubble, and the turn it starts is this device's (not a BG-1 background run).
     */
    fun voiceMessageSent() {
        archieHandle()?.post(ConversationInput.LocalSendAudio)
    }

    /** §5.3 older page; a page that does not abut triggers a canonical reload (A-4.4.6). */
    fun loadOlder(key: ConversationKey) {
        val h = handle(key) ?: return
        h.loadOlder()
    }

    /** §5.6 user "Reload". */
    fun reload(key: ConversationKey) = handle(key)?.canonicalReload()

    fun dismissBanner(key: ConversationKey) = handle(key)?.post(ConversationInput.DismissBanner)

    /** Connection banner Retry: reconnect now, backoff reset (§6.13). */
    fun retry(key: ConversationKey) {
        val h = handle(key) ?: return
        h.post(ConversationInput.DismissBanner)
        h.reconnectNow()
    }

    /**
     * Explicit close of a view (§6.7). Agent: `POST /api/sessions/{localId}/close` (closes it for every
     * device, P-1), then the socket is dropped. Archie: the channel's explicit close. This is the
     * **only** path that ends a session; lifecycle paths never call it.
     */
    suspend fun close(key: ConversationKey): Boolean {
        val h = handle(key) ?: return false
        h.post(ConversationInput.LocalStop)
        val ok = if (h.kind == SessionKind.ORCHESTRATOR) {
            orchestrator.closeOrchestrator()
        } else {
            api.closePoolSession(h.state.value.ref.localId) is ApiResult.Ok
        }
        remove(key)
        history.refreshListSoon()
        return ok
    }

    /** Drops a view without touching the server (a read-only view, or after a delete). */
    fun forget(key: ConversationKey) = remove(key)

    /** §6.5 rewind: replaces the view in place (same key) with a new `localId` and the same `sdkId`. */
    suspend fun rewind(key: ConversationKey, targetEntryId: String): CutResult {
        val h = handle(key) ?: return CutResult.Failed("Conversation is not open")
        val st = h.state.value
        val sdkId = st.ref.sdkId ?: return CutResult.Failed("Available after the first reply")
        if (st.status.busy || st.inTurn) return CutResult.Failed("Stop the current reply first")
        val n = when (val r = dropLastN(st, sdkId, targetEntryId)) {
            is RewindIndex.Result.DropLastN -> r.n
            RewindIndex.Result.Abort -> { h.canonicalReload(); return CutResult.Failed("The conversation changed. Try again.") }
            null -> return CutResult.Failed("Couldn't read the conversation")
        }
        if (n == 0) return CutResult.Failed("Nothing to rewind")
        h.post(ConversationInput.LocalStop)
        api.closePoolSession(st.ref.localId)
        var result: ApiResult<String> = api.truncate(sdkId, n)
        var tries = 0
        while (result is ApiResult.HttpError && result.code == 409 && tries < 3) {
            delay(500); tries++; result = api.truncate(sdkId, n)
        }
        if (result !is ApiResult.Ok) return CutResult.Failed(result.errorMessage() ?: "Rewind failed")
        val ref = st.ref.copy(localId = newId(), live = false, liveStatus = null)
        val fresh = if (st.kind == SessionKind.ORCHESTRATOR) OrchestratorHandle(key, ConversationState.initial(ref)) else AgentHandle(key, ConversationState.initial(ref))
        install(key, fresh)
        history.refreshListSoon()
        return CutResult.Done(ref)
    }

    /**
     * §6.13 "Continue in a new view" after `session_terminated` (B-04): replaces the view **in place**
     * (same key, same kind, A-8.5) with a new `localId` resuming [sdkId], canonical cold open. An
     * Archie view resumes through the orchestrator socket ([resumeArchie]), never the agent endpoint
     * (fixes inv03 §8 bug 5).
     */
    fun continueInNewView(key: ConversationKey, sdkId: String): SessionRef? {
        val h = handle(key) ?: return null
        if (h.kind == SessionKind.ORCHESTRATOR) {
            resumeArchie(sdkId)
            return current(ConversationKey.ARCHIE)?.ref
        }
        val ref = h.state.value.ref.copy(localId = newId(), sdkId = sdkId, live = false, liveStatus = null)
        install(key, AgentHandle(key, ConversationState.initial(ref)))
        history.refreshListSoon()
        return ref
    }

    /** §6.5 fork: a new session; the caller opens it (focused: user-initiated). */
    suspend fun fork(key: ConversationKey, targetEntryId: String): CutResult {
        val h = handle(key) ?: return CutResult.Failed("Conversation is not open")
        val st = h.state.value
        val sdkId = st.ref.sdkId ?: return CutResult.Failed("Available after the first reply")
        val n = when (val r = dropLastN(st, sdkId, targetEntryId)) {
            is RewindIndex.Result.DropLastN -> r.n
            RewindIndex.Result.Abort -> { h.canonicalReload(); return CutResult.Failed("The conversation changed. Try again.") }
            null -> return CutResult.Failed("Couldn't read the conversation")
        }
        return when (val r = api.fork(sdkId, n)) {
            is ApiResult.Ok -> {
                history.refreshListSoon()
                CutResult.Done(SessionRef(localId = newId(), sdkId = r.value, kind = st.kind, provider = st.ref.provider))
            }
            else -> CutResult.Failed(r.errorMessage() ?: "Fork failed")
        }
    }

    // ───────────────────────── lifecycle (spec 12 §3.5, T-14) ─────────────────────────

    /** onStart: re-send `start` on open sockets (resync), reconnect the others now. */
    fun onForeground() {
        orchestrator.onForeground()       // emits Resync → the Archie handle re-sends start
        agentHandles().forEach { it.onForeground() }
    }

    /** onStop: drops are no longer retried until the next foreground. Nothing is sent (P-1). */
    fun onBackground(keepOrchestratorAlive: Boolean) {
        orchestrator.onBackground(keepOrchestratorAlive)
        agentHandles().forEach { it.onBackground() }
    }

    /** T-15: a new server. Tear every conversation down locally (no close, no stop) and forget it. */
    fun resetForServer() {
        val keys = synchronized(lock) { handles.keys.toList() }
        keys.forEach { remove(it) }
        agentSockets.releaseAll()
    }

    /** The user looked at [key]: LRU bookkeeping for agent sockets (parks idle ones beyond 6). */
    fun touch(key: ConversationKey) {
        val h = handle(key) as? AgentHandle ?: return
        val r = agentSockets.touch(key) { k -> current(k)?.let { it.inTurn || it.status.busy } == true }
        r.park.forEach { k -> (handle(k) as? AgentHandle)?.park() }
        if (r.reconnectSelf) h.unpark()
    }

    // ───────────────────────── internals ─────────────────────────

    private fun slot(key: ConversationKey) = synchronized(lock) { slots.getOrPut(key) { MutableStateFlow(null) } }

    private fun handle(key: ConversationKey): Handle? = synchronized(lock) { handles[key] }

    private fun archieHandle(): Handle? = handle(ConversationKey.ARCHIE)

    private fun agentHandles(): List<AgentHandle> = synchronized(lock) { handles.values.filterIsInstance<AgentHandle>() }

    private fun install(key: ConversationKey, h: Handle) {
        val old = synchronized(lock) {
            val o = handles.put(key, h)
            _openKeys.value = handles.keys.toList()
            o
        }
        old?.dispose(dropSocket = true)      // before the new handle acquires its socket (same key)
        h.begin()
    }

    private fun remove(key: ConversationKey) {
        val h = synchronized(lock) {
            val o = handles.remove(key)
            _openKeys.value = handles.keys.toList()
            o
        } ?: return
        h.dispose(dropSocket = true)
        slot(key).value = null
    }

    private fun onChannelEvent(e: ChannelEvent) {
        when (e) {
            is ChannelEvent.Adopted -> attachArchie(e.ref)
            is ChannelEvent.NewSessionArmed -> attachArchie(e.ref, fresh = true)
            is ChannelEvent.Disconnected -> archieHandle()?.post(ConversationInput.SocketClosed)
            ChannelEvent.Resync -> archieHandle()?.post(ConversationInput.Resync)
            is ChannelEvent.Conflict -> _events.tryEmit(ConversationEvent.OrchestratorConflict(e.detail))
            ChannelEvent.NoOrchestrator, ChannelEvent.GaveUp -> _events.tryEmit(ConversationEvent.NoOrchestrator)
            is ChannelEvent.SwitchRequested -> switchArchie(e)
            // POOL-2: closed live or while away; the socket is open and no `start` follows.
            is ChannelEvent.OrchestratorClosed -> archieHandle()?.post(ConversationInput.ClosedWhileAway(e.localId))
            is ChannelEvent.Reconnected, is ChannelEvent.Recovered -> Unit
        }
    }

    /**
     * §6.11a: the server stopped the old orchestrator and asked this device to resume a past one.
     * The old (stopped) view is replaced in place, no REST close (it is already gone); the new view
     * cold-opens the past conversation's history and sends `start{local_id, resume_sdk_id}` with the
     * channel's armed ids (the channel holds the user intent, SW-4). Voice, when the call should go
     * on, is the voice host's (it waits for this start's `session_started`).
     */
    private fun switchArchie(e: ChannelEvent.SwitchRequested) {
        val sdkId = e.ref.sdkId ?: return
        openArchie(e.ref.localId, sdkId)
        history.refreshListSoon()
        _events.tryEmit(ConversationEvent.ArchieSwitched(sdkId, e.title))
    }

    /** Replaces the Archie view with [localId] resuming [sdkId]; `start` once the socket is open. */
    private fun openArchie(localId: String, sdkId: String) {
        val ref = SessionRef(localId = localId, sdkId = sdkId, kind = SessionKind.ORCHESTRATOR, provider = null)
        val h = OrchestratorHandle(ConversationKey.ARCHIE, ConversationState.initial(ref))
        install(ConversationKey.ARCHIE, h)
        if (orchestrator.state.value.socket == SocketState.Open) h.post(ConversationInput.SocketOpened)
    }

    /** Adoption: the socket is open and [ref] is the pool's orchestrator. Same session → resync; another → new view. */
    private fun attachArchie(ref: OrchestratorRef, fresh: Boolean = false) {
        val h = archieHandle()
        val sameSession = h != null && (h.state.value.ref.localId == ref.localId ||
            (ref.sdkId != null && h.state.value.ref.sdkId == ref.sdkId))
        if (sameSession) {
            h!!.post(ConversationInput.SocketOpened)
            return
        }
        val sref = SessionRef(localId = ref.localId, sdkId = ref.sdkId, kind = SessionKind.ORCHESTRATOR, provider = null)
        val nh = OrchestratorHandle(ConversationKey.ARCHIE, ConversationState.initial(sref), coldOpen = !fresh)
        install(ConversationKey.ARCHIE, nh)
        nh.post(ConversationInput.SocketOpened)
    }

    private suspend fun dropLastN(st: ConversationState, sdkId: String, target: String): RewindIndex.Result? {
        val need = RewindIndex.promptsNeeded(st, target)
        val lines = ArrayList<RewindIndex.TailLine>()
        var before: Int? = null
        while (true) {
            val page = api.messages(sdkId, limit = 200, before = before).getOrNull() ?: return null
            lines.addAll(0, page.messages.mapIndexed { i, p -> RewindIndex.TailLine(page.startIndex + i, p) })
            val prompts = lines.count { it.preview.role == "user" && RewindIndex.isVisible(it.preview) }
            if (prompts >= need || !page.hasMore || page.startIndex <= 0) break
            before = page.startIndex
        }
        return RewindIndex.compute(st, target, lines)
    }

    /** One conversation: queue + consumer + effect executor. */
    private abstract inner class Handle(val key: ConversationKey, initial: ConversationState) {
        val state = MutableStateFlow(initial)
        val kind: SessionKind get() = state.value.kind
        private val inputs = Channel<ConversationInput>(Channel.UNLIMITED)
        protected val jobs = mutableListOf<Job>()
        private var reconcileJob: Job? = null
        private var olderJob: Job? = null
        @Volatile private var disposed = false

        private fun publish(s: ConversationState) {
            synchronized(lock) { if (handles[key] === this) slot(key).value = s }
        }

        fun post(input: ConversationInput) {
            if (!disposed) inputs.trySend(input)
        }

        /** User frames waiting for the `session_started` of an explicit resume ([sendUserFrame]). */
        private val heldForStart = ArrayList<ClientFrame>()

        /**
         * A user frame. A view whose session ended on the server (STOPPED: closed live or while away,
         * spec 12 POOL-2) has no subscription: typing into it is the explicit resume its "Send a
         * message to start it again" card offers. `start` goes first and the frame after its
         * `session_started` (it used to be refused with `not_started` and lost).
         */
        fun sendUserFrame(frame: ClientFrame): SendResult {
            val st = state.value
            val open = st.connection == ConnectionState.OPEN || st.connection == ConnectionState.SUBSCRIBED
            if (st.status == SessionStatus.STOPPED && open && !st.awaitingSessionStarted) {
                synchronized(heldForStart) { heldForStart += frame }
                onExplicitResume()
                post(ConversationInput.Resync)
                return SendResult.SENT
            }
            return sendFrame(frame)
        }

        protected open fun onExplicitResume() = Unit

        private fun flushHeld(subscribed: Boolean) {
            val held = synchronized(heldForStart) { heldForStart.toList().also { heldForStart.clear() } }
            if (subscribed) held.forEach { sendFrame(it) }
        }

        open fun begin() {
            publish(state.value)
            jobs += scope.launch(confined) {
                for (input in inputs) {
                    val before = state.value
                    val r = ConversationReducer.step(state.value, input)
                    state.value = r.state
                    if (before.awaitingSessionStarted && !r.state.awaitingSessionStarted) {
                        flushHeld(r.state.connection == ConnectionState.SUBSCRIBED)
                    }
                    publish(r.state)
                    r.effects.forEach { onEffect(it) }
                }
            }
        }

        open fun dispose(dropSocket: Boolean) {
            disposed = true
            inputs.close()
            jobs.forEach { it.cancel() }
            reconcileJob?.cancel(); olderJob?.cancel()
        }

        abstract fun sendFrame(frame: ClientFrame): SendResult
        abstract fun reconnectNow()

        /** §5.2: hold frames, fetch the last page (404 = empty), apply, then flush. */
        fun coldOpen() {
            val sdkId = state.value.ref.sdkId
            post(ConversationInput.BeginReload)
            jobs += scope.launch {
                val page = if (sdkId == null) ApiResult.Ok(PaginatedMessagesDto()) else fetchPage(sdkId)
                post(page.getOrNull()?.let { ConversationInput.HistoryPage(PageMode.REPLACE, it) } ?: ConversationInput.ReloadFailed)
            }
        }

        fun canonicalReload() {
            val sdkId = state.value.ref.sdkId ?: return
            post(ConversationInput.BeginReload)
            jobs += scope.launch {
                post(fetchPage(sdkId).getOrNull()?.let { ConversationInput.HistoryPage(PageMode.REPLACE, it) } ?: ConversationInput.ReloadFailed)
            }
        }

        fun loadOlder() {
            val st = state.value
            val sdkId = st.ref.sdkId ?: return
            if (!st.history.hasMore || olderJob?.isActive == true) return
            val start = st.history.startIndex
            olderJob = scope.launch {
                val page = api.messages(sdkId, limit = PAGE_SIZE, before = start).getOrNull() ?: return@launch
                if (page.startIndex + page.messages.size != state.value.history.startIndex) canonicalReload()
                else post(ConversationInput.HistoryPage(PageMode.PREPEND, page))
            }
        }

        private suspend fun fetchPage(sdkId: String): ApiResult<PaginatedMessagesDto> =
            when (val r = api.messages(sdkId, limit = PAGE_SIZE)) {
                is ApiResult.HttpError -> if (r.code == 404) ApiResult.Ok(PaginatedMessagesDto()) else r
                else -> r
            }

        private fun onEffect(e: ConversationEffect) {
            when (e) {
                is ConversationEffect.SendStart -> sendFrame(e.frame)
                ConversationEffect.CanonicalReload -> {
                    val sdkId = state.value.ref.sdkId
                    if (sdkId == null) post(ConversationInput.ReloadFailed)
                    else jobs += scope.launch {
                        post(fetchPage(sdkId).getOrNull()?.let { ConversationInput.HistoryPage(PageMode.REPLACE, it) } ?: ConversationInput.ReloadFailed)
                    }
                }
                ConversationEffect.LookupSdkId -> jobs += scope.launch {
                    val localId = state.value.ref.localId
                    history.syncPool()?.firstOrNull { it.localId == localId }?.sdkId?.let { post(ConversationInput.SdkIdLearned(it)) }
                }
                ConversationEffect.ScheduleReconcile -> {
                    reconcileJob?.cancel()
                    reconcileJob = scope.launch {
                        delay(reconcileDelayMs)
                        val sdkId = state.value.ref.sdkId ?: return@launch
                        api.messages(sdkId, limit = PAGE_SIZE).getOrNull()?.let { post(ConversationInput.HistoryPage(PageMode.RECONCILE, it)) }
                    }
                }
                ConversationEffect.TurnEnded -> {
                    history.refreshListSoon()
                    _events.tryEmit(ConversationEvent.TurnEnded(key))
                }
                is ConversationEffect.StartError -> _events.tryEmit(ConversationEvent.StartFailed(key, e.code, e.detail))
                is ConversationEffect.ScheduleStartRetry -> jobs += scope.launch {
                    delay(e.delayMillis); post(ConversationInput.Resync)
                }
                is ConversationEffect.SideError -> _events.tryEmit(ConversationEvent.SideError(key, e.code, e.detail))
            }
        }
    }

    /** The Archie conversation over the shared orchestrator socket. */
    private inner class OrchestratorHandle(key: ConversationKey, initial: ConversationState, private val coldOpen: Boolean = true) :
        Handle(key, initial) {
        override fun begin() {
            super.begin()
            if (coldOpen) coldOpen() else post(ConversationInput.HistoryPage(PageMode.REPLACE, PaginatedMessagesDto()))
        }

        override fun sendFrame(frame: ClientFrame): SendResult = orchestrator.send(frame)
        override fun reconnectNow() = orchestrator.onNetworkAvailable()
        override fun onExplicitResume() = orchestrator.markUserIntent()    // a conflict is the §6.11 dialog
    }

    /** An agent conversation with its own chat socket (T-5). */
    private inner class AgentHandle(key: ConversationKey, initial: ConversationState) : Handle(key, initial) {
        private lateinit var socket: FrameSocket

        /** POOL-2 / SRV-1: `pool/live` server id read right after this view's last `session_started` here. */
        @Volatile private var liveServerId: String? = null
        @Volatile private var subscribedHere = false
        /** Bumped on every socket open / close: a pool read answers only the open it was made for. */
        @Volatile private var openGen = 0

        override fun begin() {
            socket = agentSockets.acquire(key)
            super.begin()
            jobs += scope.launch {
                socket.events.collect { ev ->
                    when (ev) {
                        SocketEvent.Opened -> {
                            val gen = ++openGen
                            whenInPool(gen) { post(ConversationInput.SocketOpened) }
                        }
                        is SocketEvent.Frame -> {
                            if (ev.frame is ServerFrame.SessionStarted) socket.resetBackoff()   // T-13
                            post(ConversationInput.Frame(ev.frame))
                            if (ev.frame is ServerFrame.SessionStarted) syncTurnWithPool()
                        }
                        is SocketEvent.Closed -> {
                            openGen++
                            post(ConversationInput.SocketClosed)
                        }
                    }
                }
            }
            coldOpen()
            connect()
        }

        /** The session ended on the server as far as this view knows (closed live: `session_stopped`, WATCH-1). */
        private fun endedHere(): Boolean = state.value.status.let { it == SessionStatus.STOPPED || it == SessionStatus.TERMINATED }

        /**
         * Spec 12 POOL-2: before an automatic re-`start` (socket reopen, foreground) of a view that was
         * live here, re-read `pool/live`. In the pool, or gone after a backend restart (a new or unknown
         * server id), or the read failed: [go]. Gone from the same server process, or already closed
         * live: closed elsewhere, no `start` (it would re-open the session on the server). A view never
         * subscribed here (e.g. opened from History) just starts.
         */
        private fun whenInPool(gen: Int, go: () -> Unit) {
            if (!subscribedHere && !endedHere()) { go(); return }
            jobs += scope.launch {
                val snap = history.syncPoolSnapshot()
                if (gen != openGen) return@launch                               // the socket moved on
                val localId = state.value.ref.localId
                val inPool = snap?.sessions?.any { !it.isOrchestrator && it.localId == localId } == true
                val sameServer = snap?.serverId != null && snap.serverId == liveServerId
                if (!inPool && (endedHere() || (snap != null && sameServer))) closedElsewhere(localId) else go()
            }
        }

        private fun closedElsewhere(localId: String) {
            subscribedHere = false
            liveServerId = null
            post(ConversationInput.ClosedWhileAway(localId))
            _events.tryEmit(ConversationEvent.ClosedElsewhere(key, localId))
            history.refreshListSoon()
        }

        /**
         * ST-2: after every (re)subscribe, `pool/live` says whether a turn is really
         * running. A turn that ended while this socket was away (or ended without a
         * terminal frame) would otherwise stay "running" here forever.
         */
        private fun syncTurnWithPool() {
            val gen = openGen
            subscribedHere = true
            jobs += scope.launch {
                val localId = state.value.ref.localId
                val snap: LivePoolSnapshot? = history.syncPoolSnapshot()
                if (gen == openGen) liveServerId = snap?.serverId                // POOL-2 / SRV-1: the same read
                snap?.sessions?.firstOrNull { it.localId == localId }?.status
                    ?.let { post(ConversationInput.PoolStatus(it)) }
            }
        }

        private fun connect() {
            val url = serverUrl() ?: return
            socket.connect(UrlScheme.wsUrl(url, WsEndpoint.AGENT))
        }

        override fun dispose(dropSocket: Boolean) {
            super.dispose(dropSocket)
            if (dropSocket && ::socket.isInitialized) agentSockets.release(key)
        }

        override fun sendFrame(frame: ClientFrame): SendResult = socket.send(frame)
        override fun reconnectNow() = socket.reconnectNow()

        fun onForeground() {
            socket.setReconnectAllowed(true)
            if (state.value.connection == ConnectionState.OPEN || state.value.connection == ConnectionState.SUBSCRIBED) {
                whenInPool(openGen) { post(ConversationInput.Resync) }       // POOL-2
            } else if (!agentSockets.isParked(key)) {
                socket.reconnectNow()
            }
        }

        fun onBackground() = socket.setReconnectAllowed(false)

        /** LRU park: the WebSocket closes (no app frame); the reducer sees `SocketClosed`. */
        fun park() = socket.disconnect()

        fun unpark() = connect()
    }

    companion object {
        const val PAGE_SIZE = 50
        /** R-7 reconcile debounce. */
        const val RECONCILE_DEBOUNCE_MS = 500L
    }
}
