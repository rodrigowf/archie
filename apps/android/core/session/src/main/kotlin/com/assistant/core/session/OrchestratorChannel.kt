package com.assistant.core.session

import com.assistant.core.model.LivePoolSnapshot
import com.assistant.core.model.PoolSession
import com.assistant.core.network.FrameSocket
import com.assistant.core.network.SendResult
import com.assistant.core.network.SocketEvent
import com.assistant.core.network.SocketState
import com.assistant.core.network.UrlScheme
import com.assistant.core.network.WsEndpoint
import com.assistant.core.protocol.ClientFrame
import com.assistant.core.protocol.ResumeCursor
import com.assistant.core.protocol.ServerFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** The orchestrator pool entry this device is attached to. `sdkId` = its JSONL id (G-14, ID-4). */
data class OrchestratorRef(val localId: String, val sdkId: String?)

data class ChannelState(
    val socket: SocketState = SocketState.Idle,
    val orchestrator: OrchestratorRef? = null,
    /** `session_started` received on the current socket. */
    val subscribed: Boolean = false,
    /** The probe found no orchestrator, or recovery gave up: the UI offers History / New. */
    val noOrchestrator: Boolean = false,
    val recovering: Boolean = false,
    /** `inject_text` frames waiting for `session_started` (spec 12 §6.15). */
    val pendingInjects: Int = 0,
)

sealed interface ChannelEvent {
    /** The probe (or recovery) attached to [ref]. [reconnect] = a genuine reconnect, not the first connect. */
    data class Adopted(val ref: OrchestratorRef, val reconnect: Boolean, val viaRecovery: Boolean = false) : ChannelEvent

    /**
     * Emitted after [Adopted] on every connect **after the first** to this server only
     * (`initialConnectionDone`; reset by [OrchestratorChannel.changeServer]). The voice core re-arms
     * voice on it (`voice_start` instead of `start`, T-11); a cold start never auto-starts voice.
     */
    data class Reconnected(val ref: OrchestratorRef) : ChannelEvent

    /** The socket opened for an armed new session ([OrchestratorChannel.armNewSession]); no probe ran. */
    data class NewSessionArmed(val ref: OrchestratorRef) : ChannelEvent
    data object NoOrchestrator : ChannelEvent

    /** `session_started` after an `orchestrator_active` recovery. */
    data class Recovered(val ref: OrchestratorRef) : ChannelEvent

    /** Recovery exhausted its 3 attempts (UI routes to History, inv03 §3.3). */
    data object GaveUp : ChannelEvent

    /** `error{orchestrator_active}` while the user had an intent in flight: show the conflict dialog (§6.11). */
    data class Conflict(val detail: String?) : ChannelEvent

    /**
     * WATCH-1: the attached orchestrator was closed elsewhere: live (`agent_session_closed{is_orchestrator}`)
     * or found missing from `pool/live` on a reopen / foreground on the same server process (POOL-2;
     * the channel then publishes a synthesized `agent_session_closed` frame first, so the conversation
     * and every frame consumer react exactly as to the live one).
     */
    data class OrchestratorClosed(val localId: String) : ChannelEvent

    /**
     * Spec 12 §6.11a: the orchestrator's `switch_conversation` stopped [fromLocalId] and asked this
     * device to continue in a past conversation (`orchestrator_switch`, at most once per
     * (sdk id, from id), SW-1). The channel already holds [ref] (a client-minted `localId` resuming the
     * past jsonl id) and counts it as user intent (SW-4: no auto-adoption undoes it). With `autoStart`
     * the channel sent the `start`; otherwise the conversation repository does (and focuses the view).
     * [voice]: the voice host starts voice on [ref] after its `session_started` (SW-2).
     */
    data class SwitchRequested(
        val ref: OrchestratorRef,
        val title: String?,
        val voice: Boolean,
        val fromLocalId: String?,
    ) : ChannelEvent

    /**
     * T-9: the app came to the foreground with the socket open and `pool/live` still has this
     * device's orchestrator (POOL-2); consumers re-send `start`. Never emitted without one: a `start`
     * for a conversation closed elsewhere would re-open it on the server for every device.
     */
    data object Resync : ChannelEvent
    data class Disconnected(val willReconnect: Boolean) : ChannelEvent
}

/**
 * Owns the app's **single** orchestrator socket (T-6), in a process-scoped runtime (spec 14 §2.5).
 *
 * - Adoption on every socket open: `GET /api/sessions/pool/live` for the `is_orchestrator` row,
 *   retried once after [SessionTuning.POOL_PROBE_RETRY_MS] (cold-start empty pool).
 * - The server is the source of truth (spec 12 POOL-2): on every socket open **and** every
 *   foreground the channel re-reads `pool/live` before any `start`. A conversation this device was
 *   subscribed to that is missing from the pool of the **same server process** (`X-Archie-Server-Id`,
 *   SRV-1) was closed while this device was not listening: it is dropped exactly like a live
 *   `agent_session_closed` (WATCH-1) and never re-started. Missing after a backend restart (a new
 *   server id, or a server that sends none) it is resumed, as before (RT-4).
 * - Genuine-reconnect gating (`initialConnectionDone`), reset on a server change (T-15; fixes the
 *   dead `teardownForServerUrlChange`, inv03 §8).
 * - `orchestrator_active` recovery: single-flight, one attempt per error at
 *   [SessionTuning.RECOVERY_BACKOFF_MS], reconnecting if the socket is down, else re-sending `start`
 *   with the adopted ids; [GaveUp][ChannelEvent.GaveUp] after 3.
 * - Lossless fan-out of frames ([frames]); `voice_audio_out` goes only to [audioFrames] (L-3).
 * - **P-1:** no lifecycle path ([disconnect], [onBackground], [changeServer], scope cancellation)
 *   ever sends `stop`/`voice_stop` or calls the pool `close`. Only [closeOrchestrator] does, and only
 *   from an explicit user action.
 *
 * All state changes run on one consumer coroutine fed by an ordered command queue.
 */
class OrchestratorChannel(
    private val socket: FrameSocket,
    private val pool: PoolApi,
    private val ids: OrchestratorIdStore,
    private val scope: CoroutineScope,
    private val config: Config = Config(),
    /** Mints the `localId` of a conversation the server asks this device to resume (§6.11a). */
    private val newLocalId: () -> String = { UUID.randomUUID().toString() },
) {
    class Config(
        /**
         * The channel itself sends `start` after adoption / arm and on [onForeground] (headless hosts:
         * the lite app and the voice host with no conversation view). With a conversation repository
         * attached, leave it false: the reducer's `SendStart` effect is the one `start` (no double start,
         * inv03 §3.3).
         */
        val autoStart: Boolean = false,
        /** In-memory checkpoint for `resume_from` (T-10); `null` = none. */
        val resumeCursor: (localId: String) -> ResumeCursor? = { null },
    )

    private val _state = MutableStateFlow(ChannelState())
    val state: StateFlow<ChannelState> = _state.asStateFlow()

    private val eventsOut = FanOut<ChannelEvent>()
    private val framesOut = FanOut<ServerFrame>()
    private val audioOut = FanOut<ServerFrame.VoiceAudioOut>()

    val events: Flow<ChannelEvent> = eventsOut.asFlow()

    /** Every non-audio server frame, in order, lossless from subscription. */
    val frames: Flow<ServerFrame> = framesOut.asFlow()
    val audioFrames: Flow<ServerFrame.VoiceAudioOut> = audioOut.asFlow()
    fun subscribeEvents(): ReceiveChannel<ChannelEvent> = eventsOut.subscribe()
    fun subscribeFrames(): ReceiveChannel<ServerFrame> = framesOut.subscribe()
    fun audioFramesChannel(): ReceiveChannel<ServerFrame.VoiceAudioOut> = audioOut.subscribe()

    private sealed interface Cmd {
        data class Socket(val event: SocketEvent) : Cmd
        data class Connect(val serverUrl: String) : Cmd
        data object Disconnect : Cmd
        data class ChangeServer(val serverUrl: String) : Cmd
        data class ArmNew(val localId: String) : Cmd
        data class Inject(val text: String) : Cmd
        /** [adopt] = false: the armed-new path only learns the server id (SRV-1). */
        data class ProbeDone(val generation: Int, val found: PoolSession?, val serverId: String?, val adopt: Boolean = true) : Cmd
        data class ForegroundProbeDone(val generation: Int, val snapshot: LivePoolSnapshot?) : Cmd
        data class RecoveryStep(val generation: Int, val found: PoolSession?) : Cmd
        data object RecoveryAborted : Cmd
        data object UserIntent : Cmd
        data class Foreground(val value: Boolean, val keepAliveInBackground: Boolean) : Cmd
    }

    private val inbox = Channel<Cmd>(Channel.UNLIMITED)

    // Confined to the consumer coroutine.
    private var serverUrl: String? = null
    /** Socket openness as seen through the ordered event queue (never the racy `socket.state`). */
    private var socketOpen = false
    private var connGeneration = 0
    private var initialConnectionDone = false
    private var armedNew: OrchestratorRef? = null
    private var recoveryAttempt = 0
    private var recoveryJob: Job? = null
    private var probeJob: Job? = null
    private var foregroundJob: Job? = null
    /** SRV-1: the server process id seen by the current socket's probe (`null` = unknown). */
    private var socketServerId: String? = null
    /** The orchestrator conversation this device was last subscribed to, the socket generation and server process of that subscription (POOL-2). */
    private var liveLocalId: String? = null
    private var liveGen = -1
    private var liveServerId: String? = null
    private var userIntent = false
    private val outbox = ArrayDeque<String>()
    /** `orchestrator_switch` frames already acted on, as "sdkId|fromId" (SW-1). */
    private val switchesHandled = HashSet<String>()

    init {
        scope.launch { socket.events.collect { inbox.send(Cmd.Socket(it)) } }
        scope.launch { socket.state.collect { s -> _state.update { it.copy(socket = s) } } }
        scope.launch { for (cmd in inbox) handle(cmd) }
    }

    // ───────────── public API (thread-safe; ordered) ─────────────

    fun connect(serverUrl: String) { inbox.trySend(Cmd.Connect(serverUrl)) }

    /** Lifecycle disconnect (process teardown, idle-in-background). Sends nothing (P-1). */
    fun disconnect() { inbox.trySend(Cmd.Disconnect) }

    /** T-15: tear down, reset per-server state, and **connect to the new server** (fixes A-8.3). */
    fun changeServer(serverUrl: String) { inbox.trySend(Cmd.ChangeServer(serverUrl)) }

    /** New orchestrator with a client-minted id (§6.11); `sdkId == localId` for a new one (G-14). */
    fun armNewSession(localId: String) { inbox.trySend(Cmd.ArmNew(localId)) }

    /** The user is starting/attaching explicitly: the next `orchestrator_active` is a [ChannelEvent.Conflict]. */
    fun markUserIntent() { inbox.trySend(Cmd.UserIntent) }

    /** Shared text / upload link (§6.15): sent at once when subscribed, else held until `session_started`. */
    fun inject(text: String) { inbox.trySend(Cmd.Inject(text)) }

    /** Raw send (voice frames, `send`, `interrupt`, …). */
    fun send(frame: ClientFrame): SendResult = socket.send(frame)

    /** `start` for the adopted orchestrator (T-9/T-10: [resumeFrom] only from an in-memory checkpoint). */
    fun sendStart(resumeFrom: ResumeCursor? = null): SendResult {
        val ref = _state.value.orchestrator ?: return SendResult.NOT_CONNECTED
        return socket.send(ClientFrame.Start(ref.localId, ref.sdkId, resumeFrom ?: config.resumeCursor(ref.localId)))
    }

    /** onStart/onResume (spec 12 §3.5). */
    fun onForeground() { inbox.trySend(Cmd.Foreground(true, keepAliveInBackground = false)) }

    /**
     * onStop. [keepAlive] = this device owns voice or the wake-word service needs the socket (T-14):
     * reconnects continue. Otherwise drops are not retried until the next foreground. Nothing is sent.
     */
    fun onBackground(keepAlive: Boolean) { inbox.trySend(Cmd.Foreground(false, keepAlive)) }

    /** Network became available (T-14): reconnect now, backoff reset (held while backgrounded). */
    fun onNetworkAvailable() = socket.reconnectNow()

    /** T-14: reconnect on every `ConnectivityManager` "available" (e.g. `NetworkMonitor.available`). */
    fun attachNetwork(available: Flow<Unit>): Job = scope.launch { available.collect { onNetworkAvailable() } }

    /**
     * Explicit user close of the orchestrator: closes it **for every device** (P-1). Never call this
     * from unload, teardown or lifecycle paths.
     */
    suspend fun closeOrchestrator(): Boolean {
        val ref = _state.value.orchestrator ?: return false
        val ok = pool.close(ref.localId)
        if (ok) {
            ids.clear()
            _state.update { it.copy(orchestrator = null, subscribed = false, noOrchestrator = true) }
        }
        return ok
    }

    // ───────────── consumer ─────────────

    private suspend fun handle(cmd: Cmd) {
        when (cmd) {
            is Cmd.Connect -> {
                serverUrl = cmd.serverUrl
                socket.connect(UrlScheme.wsUrl(cmd.serverUrl, WsEndpoint.ORCHESTRATOR))
            }
            Cmd.Disconnect -> {
                cancelJobs()
                socketOpen = false
                socket.disconnect()
            }
            is Cmd.ChangeServer -> {
                cancelJobs()
                socketOpen = false
                socket.disconnect()
                initialConnectionDone = false
                armedNew = null
                recoveryAttempt = 0
                outbox.clear()
                ids.clear()
                socketServerId = null
                clearLive()
                _state.update { ChannelState(socket = it.socket) }
                serverUrl = cmd.serverUrl
                socket.connect(UrlScheme.wsUrl(cmd.serverUrl, WsEndpoint.ORCHESTRATOR))
            }
            is Cmd.ArmNew -> {
                val ref = OrchestratorRef(cmd.localId, cmd.localId)
                ids.save(cmd.localId)
                userIntent = true
                _state.update { it.copy(orchestrator = ref, noOrchestrator = false) }
                if (socketOpen) {
                    probeJob?.cancel()
                    emit(ChannelEvent.NewSessionArmed(ref))
                    if (config.autoStart) sendStart()
                } else {
                    armedNew = ref
                }
            }
            Cmd.UserIntent -> userIntent = true
            is Cmd.Inject -> {
                val frame = ClientFrame.InjectText(cmd.text)
                if (_state.value.subscribed && socket.send(frame) == SendResult.SENT) return
                if (outbox.size >= SessionTuning.INJECT_OUTBOX_CAPACITY) outbox.removeFirst()
                outbox.addLast(cmd.text)
                _state.update { it.copy(pendingInjects = outbox.size) }
            }
            is Cmd.Foreground -> if (cmd.value) {
                socket.setReconnectAllowed(true)
                if (socketOpen) {
                    // POOL-2: re-read the pool before re-sending `start`; the socket may have stayed
                    // open while this device missed (or got) a close.
                    val gen = connGeneration
                    foregroundJob?.cancel()
                    foregroundJob = scope.launch { inbox.send(Cmd.ForegroundProbeDone(gen, pool.livePoolSnapshot())) }
                } else {
                    socket.reconnectNow()           // the reopen probes (POOL-2)
                }
            } else {
                socket.setReconnectAllowed(cmd.keepAliveInBackground)
            }
            is Cmd.Socket -> onSocket(cmd.event)
            is Cmd.ProbeDone -> if (cmd.generation == connGeneration) {
                cmd.serverId?.let { socketServerId = it }
                if (liveGen == cmd.generation && liveServerId == null) liveServerId = socketServerId
                if (cmd.adopt) onProbe(cmd.found)
            }
            is Cmd.ForegroundProbeDone -> if (cmd.generation == connGeneration && socketOpen) onForegroundProbe(cmd.snapshot)
            is Cmd.RecoveryStep -> onRecoveryStep(cmd.found)
            Cmd.RecoveryAborted -> _state.update { it.copy(recovering = false) }
        }
    }

    private suspend fun onSocket(e: SocketEvent) {
        when (e) {
            SocketEvent.Opened -> {
                socketOpen = true
                connGeneration++
                socketServerId = null
                foregroundJob?.cancel()
                _state.update { it.copy(subscribed = false) }
                val armed = armedNew
                val gen = connGeneration
                probeJob?.cancel()
                if (armed != null) {
                    armedNew = null
                    initialConnectionDone = true
                    emit(ChannelEvent.NewSessionArmed(armed))
                    if (config.autoStart) sendStart()
                    // No adoption, but learn this server's id so a later close-while-away is recognised (SRV-1).
                    probeJob = scope.launch { inbox.send(Cmd.ProbeDone(gen, null, pool.livePoolSnapshot()?.serverId, adopt = false)) }
                } else {
                    probeJob = scope.launch {
                        var snap = pool.livePoolSnapshot()
                        var found = snap?.sessions?.firstOrNull { it.isOrchestrator }
                        if (found == null) {
                            delay(SessionTuning.POOL_PROBE_RETRY_MS)
                            snap = pool.livePoolSnapshot() ?: snap
                            found = snap?.sessions?.firstOrNull { it.isOrchestrator }
                        }
                        inbox.send(Cmd.ProbeDone(gen, found, snap?.serverId))
                    }
                }
            }
            is SocketEvent.Closed -> {
                socketOpen = false
                probeJob?.cancel()
                foregroundJob?.cancel()
                _state.update { it.copy(subscribed = false) }
                emit(ChannelEvent.Disconnected(e.willReconnect))
            }
            is SocketEvent.Frame -> onFrame(e.frame)
        }
    }

    private suspend fun onProbe(found: PoolSession?) {
        val mine = _state.value.orchestrator
        if (found == null && mine != null && closedWhileAway(mine)) {
            // POOL-2: the same server process no longer has the conversation this device was
            // subscribed to, so it was closed while this socket was down (a backgrounded phone is
            // not a pool watcher, and misses the live `agent_session_closed`). Re-sending `start`
            // here would re-open it on the server for every device (2026-10-10).
            closedElsewhere(mine)
            return
        }
        if (found == null && mine != null) {
            // Reconnect with no live orchestrator in the pool, e.g. the backend restarted and its
            // pool is empty. The conversation this device was showing still exists on disk:
            // `start` with its ids resumes it, as the web does on every reopen. Without this the
            // view stayed "Reconnecting…" forever (2026-10-04, POCO X7 Pro). Having a conversation
            // makes this a reconnect even when the first probe found none and the user then
            // opened one from History (initialConnectionDone is still false in that case).
            initialConnectionDone = true
            emit(ChannelEvent.Adopted(mine, reconnect = true))
            emit(ChannelEvent.Reconnected(mine))
            if (config.autoStart) sendStart()
            return
        }
        if (found == null) {
            _state.update { it.copy(noOrchestrator = true) }
            emit(ChannelEvent.NoOrchestrator)
            return
        }
        adopt(OrchestratorRef(found.localId, found.sdkId), reconnect = initialConnectionDone)
    }

    /** POOL-2 on foreground with the socket open (see [Cmd.Foreground]). */
    private suspend fun onForegroundProbe(snap: LivePoolSnapshot?) {
        snap?.serverId?.let { socketServerId = it }
        if (liveGen == connGeneration && liveServerId == null) liveServerId = socketServerId
        val mine = _state.value.orchestrator
        val found = snap?.sessions?.firstOrNull { it.isOrchestrator }
        when {
            snap == null || userIntent || armedNew != null -> resync()              // can't tell / the user is starting one
            found != null && found.localId == mine?.localId -> resync()
            // Opened (or replaced) elsewhere while this device was away: follow it, as the live
            // `agent_session_opened` would (spec 12 §4.4 onWatcherEvent).
            found != null -> adopt(OrchestratorRef(found.localId, found.sdkId), reconnect = false)
            mine != null && closedWhileAway(mine) -> closedElsewhere(mine)
            else -> resync()
        }
    }

    /** T-9 resync: only with an orchestrator (a `start` for a closed one would re-open it, POOL-2). */
    private fun resync() {
        if (_state.value.orchestrator == null) return
        emit(ChannelEvent.Resync)
        if (config.autoStart) sendStart()
    }

    /** POOL-2 / SRV-1: [ref] was live on this device, on this very server process, and is gone from its pool. */
    private fun closedWhileAway(ref: OrchestratorRef): Boolean {
        val sid = socketServerId ?: return false
        return liveLocalId == ref.localId && liveServerId == sid
    }

    /** POOL-2: [ref] was closed while this device was not listening. Same effects as the live WATCH-1 frame. */
    private suspend fun closedElsewhere(ref: OrchestratorRef) {
        _state.update { it.copy(orchestrator = null, subscribed = false, noOrchestrator = true) }
        ids.clear()
        clearLive()
        framesOut.publish(ServerFrame.AgentSessionClosed(sessionId = ref.localId, isOrchestrator = true))
        emit(ChannelEvent.OrchestratorClosed(ref.localId))
    }

    private fun clearLive() {
        liveLocalId = null; liveGen = -1; liveServerId = null
    }

    private suspend fun adopt(ref: OrchestratorRef, reconnect: Boolean) {
        ids.save(ref.localId)
        _state.update { it.copy(orchestrator = ref, noOrchestrator = false) }
        emit(ChannelEvent.Adopted(ref, reconnect))
        if (reconnect) emit(ChannelEvent.Reconnected(ref)) else initialConnectionDone = true
        if (config.autoStart) sendStart()
    }

    private suspend fun onFrame(f: ServerFrame) {
        when (f) {
            is ServerFrame.VoiceAudioOut -> { audioOut.publish(f); return }       // L-3
            is ServerFrame.SessionStarted -> {
                val current = _state.value.orchestrator
                val localId = f.sessionId ?: current?.localId                     // ID-1
                val sdkId = f.jsonlId?.takeIf { it.isNotEmpty() } ?: current?.sdkId // ID-4
                val ref = localId?.let { OrchestratorRef(it, sdkId) }
                if (ref != null && ref.localId != current?.localId) ids.save(ref.localId)
                val wasRecovering = recoveryAttempt > 0
                recoveryAttempt = 0
                socket.resetBackoff()                                              // T-13
                userIntent = false
                _state.update { it.copy(orchestrator = ref ?: it.orchestrator, subscribed = true, noOrchestrator = false, recovering = false) }
                _state.value.orchestrator?.let { o ->                             // POOL-2 / SRV-1
                    liveLocalId = o.localId; liveGen = connGeneration; liveServerId = socketServerId
                }
                framesOut.publish(f)
                if (wasRecovering && ref != null) emit(ChannelEvent.Recovered(ref))
                flushOutbox()
                return
            }
            is ServerFrame.Error -> if (f.error == "orchestrator_active") onOrchestratorActive(f.detail)
            is ServerFrame.OrchestratorSwitch -> {
                framesOut.publish(f)
                onSwitch(f)
                return
            }
            is ServerFrame.AgentSessionOpened -> {
                // Spec 12 §4.4 onWatcherEvent: Archie was opened on another device → follow it, as the
                // probe would on the next connect. Skipped while this device is starting one itself
                // (armNewSession / resumeArchie set userIntent; the echo of our own start has our id).
                // Without this the lite face kept "No conversation open on the server" (2026-10-05, A300M).
                val id = f.sessionId
                if (f.isOrchestrator && id != null && socketOpen && !userIntent && armedNew == null &&
                    id != _state.value.orchestrator?.localId
                ) {
                    probeJob?.cancel()
                    framesOut.publish(f)
                    adopt(OrchestratorRef(id, f.sdkSessionId?.takeIf { it.isNotEmpty() } ?: id), reconnect = false)
                    return
                }
            }
            is ServerFrame.AgentSessionClosed -> {
                val ref = _state.value.orchestrator
                if (f.isOrchestrator && ref != null && f.sessionId == ref.localId) {   // FOCUS-2, WATCH-1
                    // noOrchestrator: the socket is open and healthy, there is just no conversation;
                    // without it the connection status read "connecting" forever (2026-10-04).
                    _state.update { it.copy(orchestrator = null, subscribed = false, noOrchestrator = true) }
                    ids.clear()
                    clearLive()
                    framesOut.publish(f)
                    emit(ChannelEvent.OrchestratorClosed(ref.localId))
                    return
                }
            }
            else -> Unit
        }
        framesOut.publish(f)
    }

    /**
     * §6.11a. The server already stopped the old orchestrator (its `agent_session_closed` came first),
     * so this is a plain resume (§6.11) with no conflict dialog: a new `localId` for the past jsonl
     * id, armed like [armNewSession] so the next `start` / `voice_start` carries it.
     */
    private suspend fun onSwitch(f: ServerFrame.OrchestratorSwitch) {
        val sdkId = f.sdkSessionId?.takeIf { it.isNotEmpty() } ?: return
        if (!switchesHandled.add("$sdkId|${f.fromSessionId}")) return              // SW-1: at most once
        probeJob?.cancel()
        armedNew = null
        userIntent = true                                                         // SW-4
        val ref = OrchestratorRef(newLocalId(), sdkId)
        ids.save(ref.localId)
        _state.update { it.copy(orchestrator = ref, subscribed = false, noOrchestrator = false) }
        emit(ChannelEvent.SwitchRequested(ref, f.title, f.voice, f.fromSessionId))
        if (config.autoStart && socketOpen) sendStart()
    }

    private fun onOrchestratorActive(detail: String?) {
        if (userIntent) {
            userIntent = false
            emit(ChannelEvent.Conflict(detail))
            return
        }
        if (recoveryJob?.isActive == true) return                                  // single-flight
        val attempt = recoveryAttempt++
        if (attempt >= SessionTuning.MAX_RECOVERY_ATTEMPTS) {
            _state.update { it.copy(noOrchestrator = true, recovering = false) }
            emit(ChannelEvent.GaveUp)
            return
        }
        _state.update { it.copy(recovering = true) }
        val gen = connGeneration
        recoveryJob = scope.launch {
            delay(SessionTuning.RECOVERY_BACKOFF_MS[attempt])
            val found = pool.livePool()?.firstOrNull { it.isOrchestrator }
            inbox.send(if (found == null) Cmd.RecoveryAborted else Cmd.RecoveryStep(gen, found))
        }
    }

    private suspend fun onRecoveryStep(found: PoolSession?) {
        found ?: return
        val ref = OrchestratorRef(found.localId, found.sdkId)
        ids.save(ref.localId)
        _state.update { it.copy(orchestrator = ref) }
        emit(ChannelEvent.Adopted(ref, reconnect = false, viaRecovery = true))
        if (!socketOpen) {
            // Sending into a stale socket would loop; reopen and let the probe re-adopt.
            socket.reconnectNow()
            return
        }
        socket.send(ClientFrame.Start(ref.localId, ref.sdkId, config.resumeCursor(ref.localId)))
    }

    private fun flushOutbox() {
        while (outbox.isNotEmpty()) {
            if (socket.send(ClientFrame.InjectText(outbox.first())) != SendResult.SENT) break
            outbox.removeFirst()
        }
        _state.update { it.copy(pendingInjects = outbox.size) }
    }

    private fun cancelJobs() {
        probeJob?.cancel(); recoveryJob?.cancel(); foregroundJob?.cancel()
    }

    private fun emit(e: ChannelEvent) = eventsOut.publish(e)
}
