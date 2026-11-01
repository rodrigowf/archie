package com.assistant.archie.tv.control

import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation3.runtime.NavKey
import com.assistant.archie.feature.visuals.web.WebTrust
import com.assistant.archie.feature.visuals.web.WebViewPool
import com.assistant.archie.tv.graph.TvAppGraph
import com.assistant.archie.tv.shell.Memory
import com.assistant.archie.tv.shell.TvNavHolder
import com.assistant.archie.tv.shell.Visuals
import com.assistant.archie.tv.theme.TvFormat
import com.assistant.archie.tv.visuals.VisualsSeen
import com.assistant.archie.tv.voice.VoiceChip
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.network.ApiResult
import com.assistant.core.network.UrlScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * T3's process-scoped part of the graph (spec 15 §5, §3.5): the TV control channel
 * ([client] + [handler] + [StateReporter]), the screens it can drive ([remote]), the Viewer's single
 * WebView ([webPool], size 1, [webTrust]: the server's pinned certificate only) and the Visuals
 * "changed since last open" dots ([visualsSeen]).
 *
 * **When the socket runs.** It connects once the app has connected to its server and then stays up
 * for the life of the process, reconnecting with backoff — also while another app (YouTube) is in
 * front and the orchestrator socket is parked (spec 12 P-1), because that is exactly when agents
 * push pages. A new server URL moves it; the orchestrator reconnecting or the network coming back
 * retries at once.
 */
class TvControl(private val graph: TvAppGraph) : TvCommandEnv {
    private val app = graph.application

    override val nav: TvNavHolder get() = graph.navHolder
    override val remote = TvRemoteScreens()

    /** The Viewer's WebView (spec 15 §6.2: one WebView max, freed when the Viewer closes). */
    val webPool: WebViewPool by lazy { WebViewPool(app, maxEntries = 1) }
    val webTrust = WebTrust(serverUrl = graph::currentServerUrl, pinFor = graph.settings::pinFor)
    val debugWeb: Boolean get() = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val visualsSeen = VisualsSeen()

    val handler = CommandHandler(this)

    val client = TvControlClient(
        scope = graph.scope,
        sockets = OkHttpTvSocketFactory(graph.http),
        hello = ::hello,
        onCommand = { cmd -> withContext(Dispatchers.Main.immediate) { handler.handle(cmd) } },
        log = { level, msg -> if (level == 'W') Log.w(TAG, msg) else Log.i(TAG, msg) },
    )

    private var started = false
    private var target: String? = null

    /** Called once by the Application (after the graph is built). */
    fun start() {
        if (started) return
        started = true
        graph.connection.status
            .map { it.serverUrl to it.phase }
            .distinctUntilChanged()
            .onEach { (url, phase) ->
                when {
                    url == null -> Unit
                    phase == ConnectionStatus.Phase.CONNECTED && url != target -> {
                        target = url
                        client.setTarget(url)
                    }
                    phase == ConnectionStatus.Phase.CONNECTED -> client.reconnectNow()
                    // Switched to another server that is not up yet: leave the old one, wait for this one.
                    target != null && url != target -> {
                        target = null
                        client.setTarget(null)
                    }
                }
            }
            .launchIn(graph.scope)
        graph.network.available.onEach { client.reconnectNow() }.launchIn(graph.scope)
        StateReporter(
            current = graph.navHolder.current,
            info = remote.info,
            voice = flow { emitAll(graph.voice.state) }.map { VoiceChip.label(it).lowercase() },
            foreground = foregroundFlow(),
            send = client::sendState,
        ).start(graph.scope)
    }

    private fun foregroundFlow(): Flow<Boolean> = flow {
        emitAll(ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) })
    }

    private fun hello() = TvHello(
        deviceId = graph.prefs.controlDeviceId,
        deviceName = deviceName(),
        version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0",
        token = graph.prefs.controlToken,
    )

    /** Settings → TV control name, else the system's device name ("Living room TV"), else the model. */
    fun deviceName(): String = graph.prefs.controlDeviceName
        ?: runCatching { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: if (Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) "Fire TV" else Build.MODEL

    // ── TvCommandEnv ────────────────────────────────────────────────────────────────────────────

    override fun isForeground(): Boolean = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    override fun bringToFront() {
        graph.navHolder.bringToFront?.invoke()
    }

    override fun allowAgentsToOpen(): Boolean = graph.prefs.allowAgentsToOpen
    override fun showOpenedToasts(): Boolean = graph.prefs.showOpenedToasts

    override fun origin(): String = UrlScheme.httpBase(graph.currentServerUrl())

    override fun isListedVisual(path: String): Boolean = graph.visuals.list.value.value?.any { it.path == path } == true

    override suspend fun visual(path: String, refresh: Boolean): VisualLookup {
        if (refresh || graph.visuals.list.value.value == null) graph.visuals.refreshNow()
        val list = graph.visuals.list.value.value ?: return VisualLookup.Unknown
        val v = list.firstOrNull { it.path == path } ?: return VisualLookup.Missing
        return VisualLookup.Listed(v.title.ifBlank { TvFormat.titleOf(path) })
    }

    override suspend fun memoryExists(path: String): Boolean? = when (val r = graph.memory.document(path)) {
        is ApiResult.Ok -> true
        is ApiResult.HttpError -> if (r.code == 404) false else null
        else -> null
    }

    override fun refreshList(key: NavKey?): Boolean = when (key) {
        Visuals -> { graph.visuals.refresh(); true }
        Memory -> { graph.memory.refreshTree(); true }
        else -> false
    }

    override fun toast(text: String, seconds: Int) {
        graph.toasts.show(text, seconds = seconds)
    }

    override fun systemToast(text: String, seconds: Int) {
        Toast.makeText(app, text, if (seconds > 3) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    override fun pingLauncher() = graph.launcherPing.ping(force = true)

    override fun state(): JsonObject {
        val key = graph.navHolder.current.value
        return TvScreens.frame(key, key?.let { remote.info.value[it] }, VoiceChip.label(graph.voice.state.value).lowercase(), isForeground())
    }

    companion object {
        const val TAG = "ArchieTv/control"
    }
}
