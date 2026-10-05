package com.assistant.core.data

import com.assistant.core.model.DeviceSettings
import com.assistant.core.network.DiscoveredServer
import com.assistant.core.network.SocketState
import com.assistant.core.session.OrchestratorChannel
import com.assistant.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** LAN discovery seam (`ServerDiscovery` over the device's /24 subnets in the app; fakes in tests). */
fun interface ServerScanner {
    suspend fun scan(): List<DiscoveredServer>
}

/** What the drawer header and Settings → Connection show. */
data class ConnectionStatus(
    /** The stored server URL; `null` until settings loaded (nobody acts on defaults, inv04 B4). */
    val serverUrl: String? = null,
    /** "jetson" style label: the saved-server label, else the host. */
    val serverLabel: String = "",
    val phase: Phase = Phase.OFFLINE,
    val scanning: Boolean = false,
    val discovered: List<DiscoveredServer> = emptyList(),
) {
    enum class Phase { OFFLINE, CONNECTING, CONNECTED, RECONNECTING }
}

/**
 * The device's link to a backend (spec 14 §2.10, spec 12 §3.4, T-15):
 *
 * - [start] runs the launch effects of the old shell (inv03 §1.1): connect when auto-connect is on;
 *   when the URL is still the default, scan the LAN as well.
 * - **Adopting a scanned server connects** (fixes inv03 §8 bug 3): [adopt] stores the URL and
 *   switches every connection to it.
 * - [changeServer] (T-15): tear down all sockets and per-server state, then connect to the new one.
 *   Nothing is closed on the old server (P-1).
 */
class ConnectionRepository(
    private val settings: SettingsStore,
    private val orchestrator: OrchestratorChannel,
    private val conversations: ConversationRepository,
    private val openSessions: OpenSessionsRepository,
    private val history: HistoryRepository,
    private val scanner: ServerScanner,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(ConnectionStatus())
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    @Volatile private var started = false
    private var scanJob: Job? = null

    /** The current server URL for REST / agent sockets (`null` before settings load). */
    val serverUrl: String? get() = settings.settings.value?.serverUrl

    init {
        combine(settings.settings, orchestrator.state) { s, ch -> s to ch }.onEach { (s, ch) ->
            _status.update {
                it.copy(
                    serverUrl = s?.serverUrl,
                    serverLabel = s?.let(::labelOf) ?: "",
                    phase = when (ch.socket) {
                        SocketState.Open -> if (ch.subscribed || ch.orchestrator != null || ch.noOrchestrator) ConnectionStatus.Phase.CONNECTED else ConnectionStatus.Phase.CONNECTING
                        is SocketState.Connecting -> if (it.phase == ConnectionStatus.Phase.CONNECTED || it.phase == ConnectionStatus.Phase.RECONNECTING) ConnectionStatus.Phase.RECONNECTING else ConnectionStatus.Phase.CONNECTING
                        is SocketState.Disconnected -> if ((ch.socket as SocketState.Disconnected).willReconnect) ConnectionStatus.Phase.RECONNECTING else ConnectionStatus.Phase.OFFLINE
                        SocketState.Idle -> ConnectionStatus.Phase.OFFLINE
                    },
                )
            }
        }.launchIn(scope)
    }

    /** App launch (idempotent). Waits for settings first (`awaitLoaded`, `28d982d`). */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val s = settings.awaitLoaded()
            if (s.autoConnect) connect(s.serverUrl)
            if (s.isDefaultServer) scan(adoptFirst = true)
        }
    }

    /** Connect (or reconnect) to the stored server. */
    fun connect() {
        scope.launch { connect(settings.awaitLoaded().serverUrl) }
    }

    private fun connect(url: String) {
        orchestrator.connect(url)
        history.refreshAll()
    }

    /**
     * LAN scan. With [adoptFirst] (launch on the default URL), the first backend found is adopted
     * **and connected** when the URL is still the default and nothing is connected yet.
     */
    fun scan(adoptFirst: Boolean = false) {
        if (scanJob?.isActive == true) return
        _status.update { it.copy(scanning = true) }
        scanJob = scope.launch {
            val found = try { scanner.scan() } catch (_: Exception) { emptyList() }
            _status.update { it.copy(scanning = false, discovered = found) }
            if (!adoptFirst || found.isEmpty()) return@launch
            val s = settings.awaitLoaded()
            val connected = orchestrator.state.value.subscribed
            if (s.isDefaultServer && !connected) {
                val pick = found.firstOrNull { !it.needsTrust } ?: return@launch
                if (pick.serverUrl != s.serverUrl) adopt(pick)
            }
        }
    }

    /** Adopts a discovered server: store it and connect (bug 3). */
    suspend fun adopt(server: DiscoveredServer) = changeServer(server.serverUrl)

    /**
     * T-15: store [url]; tear down every socket and per-server state locally; connect to the new server.
     * No `close`/`stop` is sent to the old server (P-1).
     */
    suspend fun changeServer(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        settings.setServerUrl(trimmed)
        openSessions.resetForServer()
        conversations.resetForServer()
        history.reset()
        orchestrator.changeServer(trimmed)
        history.refreshAll()
    }

    companion object {
        fun labelOf(s: DeviceSettings): String =
            s.savedServers.firstOrNull { it.url == s.serverUrl }?.label ?: hostOf(s.serverUrl)

        fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore(':')
    }
}
