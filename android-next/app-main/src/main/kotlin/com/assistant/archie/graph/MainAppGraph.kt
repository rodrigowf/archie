package com.assistant.archie.graph

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.assistant.core.data.AgentSocketPool
import com.assistant.core.data.ConnectionRepository
import com.assistant.core.data.ConversationRepository
import com.assistant.core.data.HistoryRepository
import com.assistant.core.data.LanScanner
import com.assistant.core.data.MemoryRepository
import com.assistant.core.data.OpenSessionsRepository
import com.assistant.core.data.ServerConfigRepository
import com.assistant.core.data.ServerScanner
import com.assistant.core.data.ShareRepository
import com.assistant.core.data.UploadRepository
import com.assistant.core.data.VisualsRepository
import com.assistant.core.data.VoicePresence
import com.assistant.core.model.DeviceSettings
import com.assistant.core.network.ArchieApi
import com.assistant.core.network.HttpStack
import com.assistant.core.network.NetLog
import com.assistant.core.network.NetworkMonitor
import com.assistant.core.network.RestCaller
import com.assistant.core.network.ServerDiscovery
import com.assistant.core.network.SocketClient
import com.assistant.core.network.TrustStore
import com.assistant.core.network.UploadClient
import com.assistant.core.session.ArchiePoolApi
import com.assistant.core.session.OrchestratorChannel
import com.assistant.core.session.SettingsOrchestratorIdStore
import com.assistant.core.session.SettingsPinStore
import com.assistant.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.File

/** Reached as `(application as GraphOwner).graph` (spec 14 §2.2). */
interface GraphOwner {
    val graph: MainAppGraph
}

/**
 * The main app's hand-written dependency graph (spec 14 §2.2: manual DI, no Hilt). Everything here
 * is **process-scoped**: closing the Activity does not end chat or voice state (inv03 §0).
 *
 * Owners: B-03 (this shape), B-09 (voice host, notifications, share sheet wiring; the graph is handed
 * over after wave 3). Parameters with defaults exist so tests can build the graph against a local
 * server and a fake scanner.
 */
class MainAppGraph(
    private val app: Application,
    val settings: SettingsStore = SettingsStore.create(app),
    scanner: ServerScanner? = null,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    log: NetLog = AndroidNetLog,
) {
    private val serverUrl: () -> String = { settings.settings.value?.serverUrl ?: DeviceSettings.DEFAULT_SERVER_URL }

    val http = HttpStack(TrustStore(SettingsPinStore(settings, scope)), File(app.cacheDir, "http"))
    private val rest = RestCaller(http, serverUrl)
    val api = ArchieApi(rest)

    /** The app's single orchestrator socket (T-6); the conversation repository sends `start` (no autoStart). */
    val orchestrator = OrchestratorChannel(
        SocketClient(http, scope, log = log, tag = "orch"),
        ArchiePoolApi(api),
        SettingsOrchestratorIdStore(settings),
        scope,
    )

    val network: NetworkMonitor by lazy { NetworkMonitor(app) }
    val history = HistoryRepository(api, scope)
    val agentSockets = AgentSocketPool({ SocketClient(http, scope, log = log, tag = "agent") })
    val conversations = ConversationRepository(
        api, orchestrator, agentSockets, history, scope, serverUrl = { settings.settings.value?.serverUrl },
    )
    val openSessions = OpenSessionsRepository(conversations, history, orchestrator, scope)
    val connection = ConnectionRepository(
        settings, orchestrator, conversations, openSessions, history,
        scanner ?: LanScanner(ServerDiscovery(http), network),
        scope,
    )
    val memory = MemoryRepository(api, scope)
    val visuals = VisualsRepository(api, scope)
    val serverConfig = ServerConfigRepository(api, scope)
    val uploads = UploadRepository(UploadClient(rest))
    val share = ShareRepository()

    /** B-09 replaces this with the process-scoped voice host (A-08). */
    var voice: VoicePresence = VoicePresence.Idle

    init {
        // §9.1: visuals refresh after any turn (debounced).
        conversations.events.onEach { if (it is com.assistant.core.data.ConversationEvent.TurnEnded) visuals.refreshSoon() }.launchIn(scope)
    }

    /** T-14: reconnect immediately when a network becomes available. Called once by the Application. */
    fun attachNetwork() {
        orchestrator.attachNetwork(network.available)
    }

    /**
     * Process foreground/background (`ProcessLifecycleOwner`, spec 14 §2.5). ON_STOP never sends
     * anything and never closes a session (decision P-1); it only stops retrying drops.
     */
    val processLifecycle = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            conversations.onForeground()
            history.refreshAll()
        }

        override fun onStop(owner: LifecycleOwner) {
            val keepAlive = settings.settings.value?.stayConnectedInBackground == true
            conversations.onBackground(keepOrchestratorAlive = keepAlive)
        }
    }
}

/** Socket logs to logcat (audio frames are already suppressed by SocketClient). */
private object AndroidNetLog : NetLog {
    override fun log(level: Char, tag: String, message: String) {
        when (level) {
            'W', 'E' -> android.util.Log.w("Archie/$tag", message)
            else -> android.util.Log.d("Archie/$tag", message)
        }
    }
}
