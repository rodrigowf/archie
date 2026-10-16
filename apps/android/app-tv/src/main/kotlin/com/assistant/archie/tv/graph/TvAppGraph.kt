package com.assistant.archie.tv.graph

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.assistant.archie.feature.settings.AccountsModel
import com.assistant.archie.feature.settings.ConnectionModel
import com.assistant.archie.feature.settings.RepositoryConnectionControl
import com.assistant.archie.feature.settings.ServerSettingsModel
import com.assistant.archie.feature.settings.SettingsMessages
import com.assistant.archie.feature.toolcards.ToolImageLoader
import com.assistant.archie.tv.shell.TvToasts
import com.assistant.archie.tv.voice.LiveVoiceToggle
import com.assistant.core.data.AgentSocketPool
import com.assistant.core.data.ConnectionRepository
import com.assistant.core.data.ContentChangesRepository
import com.assistant.core.data.ConversationEvent
import com.assistant.core.data.ConversationRepository
import com.assistant.core.data.HistoryRepository
import com.assistant.core.data.LanScanner
import com.assistant.core.data.MemoryRepository
import com.assistant.core.data.OpenSessionsRepository
import com.assistant.core.data.ServerConfigRepository
import com.assistant.core.data.ServerScanner
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
import com.assistant.core.voicehost.runtime.VoiceHostRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.io.File

/** Reached as `(application as TvGraphOwner).graph`. */
interface TvGraphOwner {
    val graph: TvAppGraph
}

/**
 * Archie TV's hand-written dependency graph (spec 15 §6.1: manual DI like `MainAppGraph`). Everything
 * is **process-scoped**: finishing the Activity (Back at Home) never closes a session or drops the
 * connection state.
 *
 * The data layer is wired exactly as in `:app-main` (same repositories, same constructor arguments);
 * the phone-only system glue (approval / turn notifications, background holds, share sheet, tile)
 * is not part of the TV app (TV-9: re-implemented here when needed, never moved).
 *
 * Seams for later work packages:
 * - [voiceHostFactory] / [voiceHost] / [voice] / [liveVoice] — T4 (remote mic, live voice).
 * - [toasts] — T3's control channel posts "Opened by Archie: …" here.
 */
class TvAppGraph(
    private val app: Application,
    val settings: SettingsStore = SettingsStore.create(app),
    scanner: ServerScanner? = null,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    log: NetLog = AndroidNetLog,
    /** T4: builds the process-scoped voice host (no wake word, VOICE_RECOGNITION mic). `null` = no voice. */
    val voiceHostFactory: ((Application, TvAppGraph) -> VoiceHostRuntime)? = null,
) {
    val application: Application get() = app

    /** TV-only preferences (first run, text size, key hints). */
    val prefs: TvPrefs = TvPrefs.create(app)

    private val serverUrl: () -> String = { settings.settings.value?.serverUrl ?: DeviceSettings.DEFAULT_SERVER_URL }

    /** The server URL for REST / web content (default until settings load). */
    fun currentServerUrl(): String = serverUrl()

    val http = HttpStack(TrustStore(SettingsPinStore(settings, scope)), File(app.cacheDir, "http"))
    private val rest = RestCaller(http, serverUrl)
    val api = ArchieApi(rest)

    /** The app's single orchestrator socket (spec 12 T-6); the conversation repository sends `start`. */
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

    /** Spec 12 §9.3: `visualization_changed` / `memory_changed` → per-file counters the viewers reload on. */
    val content = ContentChangesRepository(
        orchestrator.frames, orchestrator.state.map { it.socket }.distinctUntilChanged(), visuals, memory, scope,
    )
    val serverConfig = ServerConfigRepository(api, scope)
    val uploads = UploadRepository(UploadClient(rest))

    /** Tool-result pictures (spec 12 R-10), for T2's tool cards. */
    val toolImages = ToolImageLoader(fetch = { url -> rest.getBytes(url) })

    /** Top-center toasts (spec 15 §3.4): errors, "Opened by Archie: …". */
    val toasts = TvToasts()

    /** Settings snackbar messages of the reused `:feature:settings` models, shown as toasts. */
    val settingsMessages = SettingsMessages()

    /**
     * Connect screen + Settings → Connection (spec 15 §3.6): `:feature:settings`' ConnectionModel over
     * the same repository / probe / TOFU pin store as the phone's Settings → Connection. One per process.
     */
    val connectionModel: ConnectionModel by lazy {
        ConnectionModel(RepositoryConnectionControl(connection, orchestrator, http, settings), settings, settingsMessages, scope)
    }

    // ── T5: Settings → Voice / Server: `:feature:settings`' models, as the phone's Settings ──
    /** Server config + catalogs (CFG-1..6); a new server drops the cached copy (T-15). */
    val serverSettings: ServerSettingsModel by lazy {
        ServerSettingsModel(serverConfig, api, settingsMessages, scope).also { m ->
            connection.status.map { it.serverUrl }.filter { it != null }.distinctUntilChanged().drop(1)
                .onEach { m.reset(); accounts.reset() }
                .launchIn(scope)
        }
    }

    /** Settings → Server → Accounts (read-only on the TV). */
    val accounts: AccountsModel by lazy { AccountsModel(api, settingsMessages, scope) }
    // ── end T5 ──

    /** KEEP_AWAKE pings to the TvServerHub launcher (spec 15 §7.3): pushed content (T3), live voice (T4). */
    val launcherPing = com.assistant.archie.tv.control.LauncherPing(app)

    /** The live navigator + current screen for control commands (T3) and the voice router (T4). */
    val navHolder = com.assistant.archie.tv.shell.TvNavHolder()

    // ── T3: TV control channel, Viewer WebView, Visuals dots (control/TvControl.kt); started by the Application ──
    val tvControl: com.assistant.archie.tv.control.TvControl by lazy { com.assistant.archie.tv.control.TvControl(this) }
    // ── end T3 ──

    // ── Voice seams (T4) ────────────────────────────────────────────────────────────────────────

    /** The process-scoped voice host, built on first access (T4 passes [voiceHostFactory]). */
    val voiceHost: VoiceHostRuntime? by lazy { voiceHostFactory?.invoke(app, this) }

    /** Status cluster voice state. T4 replaces it with the host's presence. */
    var voice: VoicePresence = VoicePresence.Idle

    /** Play/Pause → live voice (spec 15 §3.3). T4 replaces it; until then it only says so. */
    var liveVoice: LiveVoiceToggle = LiveVoiceToggle {
        toasts.show("Live voice is not available yet")
        true
    }

    init {
        // §9.1: visuals refresh after any turn (debounced), as in :app-main.
        conversations.events.onEach { if (it is ConversationEvent.TurnEnded) visuals.refreshSoon() }.launchIn(scope)
        settingsMessages.messages.onEach { toasts.show(it.text, error = it.error) }.launchIn(scope)
    }

    /** T-14: reconnect immediately when a network becomes available. Called once by the Application. */
    fun attachNetwork() {
        orchestrator.attachNetwork(network.available)
    }

    /**
     * Process foreground/background (`ProcessLifecycleOwner`), as in `:app-main`: ON_STOP never sends
     * anything and never closes a session (decision P-1); it only stops retrying drops.
     */
    val processLifecycle = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            conversations.onForeground()
            history.refreshAll()
            voiceHost?.onUiStarted()
        }

        override fun onStop(owner: LifecycleOwner) {
            val keepAlive = settings.settings.value?.stayConnectedInBackground == true || voiceHost?.serviceWanted() == true
            conversations.onBackground(keepOrchestratorAlive = keepAlive)
            voiceHost?.onUiStopped()
        }
    }
}

/** Socket logs to logcat (audio frames are already suppressed by SocketClient). */
private object AndroidNetLog : NetLog {
    override fun log(level: Char, tag: String, message: String) {
        when (level) {
            'W', 'E' -> android.util.Log.w("ArchieTv/$tag", message)
            else -> android.util.Log.d("ArchieTv/$tag", message)
        }
    }
}
