package com.assistant.archie.tv.settings

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assistant.archie.feature.settings.ServerSettingsModel
import com.assistant.archie.tv.control.TvControlState
import com.assistant.archie.tv.graph.TvAppGraph
import com.assistant.archie.tv.shell.Connect
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.shell.TvSettingsPageId
import com.assistant.archie.tv.voice.TvVoiceOwner
import com.assistant.archie.tv.voice.TvVoiceTuning
import com.assistant.core.data.ConnectionRepository
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.model.ServerConfig

/*
 * Settings panes wired to the graph (T5). Server settings go through `:feature:settings`'
 * ServerSettingsModel / AccountsModel (graph.serverSettings / graph.accounts) — the same partial PUTs
 * and catalog loads as the phone's Settings (CFG-1, CFG-3: every open refetches). TV-only
 * preferences live in TvPrefs / TvVoicePrefs.
 */

@Composable
fun SettingsPaneRoute(id: TvSettingsPageId, ctx: TvRouteContext) {
    when (id) {
        TvSettingsPageId.CONNECTION -> ConnectionRoute(ctx)
        TvSettingsPageId.REMOTE_MIC -> RemoteMicRoute(ctx)
        TvSettingsPageId.VOICE -> VoiceRoute(ctx)
        TvSettingsPageId.DISPLAY -> {
            val g = ctx.graph
            val display by g.prefs.display.collectAsStateWithLifecycle()
            DisplayPane(display, DisplayPaneActions(g.prefs::setTextSize, g.prefs::setShowKeyHints, g.prefs::setReduceMotion))
        }
        TvSettingsPageId.TV_CONTROL -> ControlRoute(ctx)
        TvSettingsPageId.SERVER -> ServerRoute(ctx)
        TvSettingsPageId.ABOUT -> AboutRoute(ctx)
    }
}

@Composable
private fun ConnectionRoute(ctx: TvRouteContext) {
    val g = ctx.graph
    val status by g.connection.status.collectAsStateWithLifecycle()
    val pins by g.settings.pins.collectAsStateWithLifecycle()
    ConnectionPane(
        connectionUi(status, pins),
        ConnectionPaneActions(
            changeServer = { ctx.nav.push(Connect(firstRun = false)) },
            reconnect = { g.connectionModel.connect() },
        ),
    )
}

fun connectionUi(status: ConnectionStatus, pins: Map<String, String>): ConnectionPaneUi {
    val (title, body) = TvSettingsStatus.connection(status)
    val url = status.serverUrl.orEmpty()
    val certificate = when {
        url.isEmpty() -> "—"
        !TvSettingsStatus.isSecure(url) -> "Not encrypted (LAN)"
        pins.containsKey(TvSettingsStatus.hostPortOf(url)) -> "Pinned on this TV"
        else -> "System trust"
    }
    return ConnectionPaneUi(
        title = title,
        body = body,
        tone = when (status.phase) {
            ConnectionStatus.Phase.CONNECTED -> TvSettingsStatus.Tone.OK
            ConnectionStatus.Phase.OFFLINE -> TvSettingsStatus.Tone.BAD
            else -> TvSettingsStatus.Tone.WAIT
        },
        serverLabel = status.serverLabel.ifEmpty { url.takeIf { it.isNotEmpty() }?.let(ConnectionRepository::hostOf) ?: "None" },
        serverUrl = url.ifEmpty { "No server yet" },
        certificate = certificate,
    )
}

@Composable
private fun VoiceRoute(ctx: TvRouteContext) {
    val g = ctx.graph
    val model = g.serverSettings
    LaunchedEffect(Unit) { model.refresh() }
    val st by model.state.collectAsStateWithLifecycle()
    val status by g.connection.status.collectAsStateWithLifecycle()
    val prefs = (g.application as? TvVoiceOwner)?.tvVoice?.prefs
    var idle by remember { mutableIntStateOf(prefs?.liveIdleSeconds ?: TvVoiceTuning.DEFAULT_LIVE_IDLE_SECONDS) }
    val cfg = st.config.value
    VoicePane(
        VoicePaneUi(
            choices = cfg?.let { TvVoiceSettings.choices(it, st) },
            error = st.config.error.takeIf { cfg == null },
            offline = cfg != null && status.phase == ConnectionStatus.Phase.OFFLINE,
            idle = LiveIdle.choice(idle),
            autoCorrected = st.autoCorrected?.let { "${it.from} is no longer offered by Google; now ${it.to}." },
        ),
        VoicePaneActions(
            pick = { ch, v ->
                if (ch.key == "live-idle") {
                    v.toIntOrNull()?.let { s -> idle = s; prefs?.liveIdleSeconds = s }
                } else {
                    TvVoiceSettings.patch(ch.key, v)?.let { model.launchSave(it, ServerSettingsModel.KEY_VOICE) }
                }
            },
            retry = model::refresh,
            dismissNotice = model::dismissAutoCorrect,
        ),
    )
}

@Composable
private fun ControlRoute(ctx: TvRouteContext) {
    val g = ctx.graph
    val control = g.tvControl
    val state by control.client.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(g.prefs.controlDeviceName.orEmpty()) }
    var allow by remember { mutableStateOf(g.prefs.allowAgentsToOpen) }
    var toasts by remember { mutableStateOf(g.prefs.showOpenedToasts) }
    var tokenSet by remember { mutableStateOf(g.prefs.controlToken != null) }
    val context = LocalContext.current
    // What the hello sends without a saved name (TvControl.deviceName: the system name, else the model).
    val systemName = remember(context) {
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: if (android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) "Fire TV" else android.os.Build.MODEL
    }
    ControlPane(
        ControlPaneUi(
            status = TvSettingsStatus.control(state),
            deviceName = name,
            systemName = systemName,
            allowOpen = allow,
            openedToasts = toasts,
            canRetry = state is TvControlState.Rejected || state is TvControlState.Waiting || state is TvControlState.Replaced,
            tokenSet = tokenSet,
        ),
        ControlPaneActions(
            rename = { v ->
                if (v != name) {
                    name = v
                    g.prefs.controlDeviceName = v.ifEmpty { null }
                    reconnectControl(g) // the name travels in the hello
                }
            },
            allowOpen = { allow = it; g.prefs.allowAgentsToOpen = it },
            openedToasts = { toasts = it; g.prefs.showOpenedToasts = it },
            retry = { reconnectControl(g) },
            token = { v ->
                g.prefs.controlToken = v.ifEmpty { null }
                tokenSet = v.isNotEmpty()
                reconnectControl(g) // the token travels in the hello; a rejection is retried with it
            },
        ),
    )
}

/** Re-sends the hello (new device name, a retry after a rejection): close and reopen the socket. */
private fun reconnectControl(g: TvAppGraph) {
    val url = g.connection.status.value.serverUrl ?: return
    val client = g.tvControl.client
    if (g.connection.status.value.phase != ConnectionStatus.Phase.CONNECTED) {
        client.retry()
        return
    }
    client.setTarget(null)
    client.setTarget(url)
}

@Composable
private fun ServerRoute(ctx: TvRouteContext) {
    val g = ctx.graph
    val model = g.serverSettings
    LaunchedEffect(Unit) {
        model.refresh()
        g.accounts.load()
    }
    val st by model.state.collectAsStateWithLifecycle()
    val accounts by g.accounts.state.collectAsStateWithLifecycle()
    val status by g.connection.status.collectAsStateWithLifecycle()
    val cfg: ServerConfig? = st.config.value
    ServerPane(
        ServerPaneUi(
            sections = cfg?.let { TvServerSettings.sections(it, st, accounts.services) },
            error = st.config.error.takeIf { cfg == null },
            offline = cfg != null && status.phase == ConnectionStatus.Phase.OFFLINE,
            host = status.serverLabel.ifEmpty { status.serverUrl?.let(ConnectionRepository::hostOf).orEmpty() },
        ),
        ServerPaneActions(
            pick = { ch, v -> cfg?.let { TvServerSettings.patch(it, ch.key, v) }?.let { model.launchSave(it, ch.key.substringBefore(':')) } },
            toggle = { t, on ->
                val c = model.current
                c.config.value?.let { TvServerSettings.togglePatch(it, c.catalogs.mcpNames, t.key, on) }?.let { model.launchSave(it, t.key.substringBefore(':')) }
            },
            retry = model::refresh,
            elsewhere = { g.toasts.show(ELSEWHERE_TOAST) },
        ),
    )
}

@Composable
private fun AboutRoute(ctx: TvRouteContext) {
    val g = ctx.graph
    val context = LocalContext.current
    val status by g.connection.status.collectAsStateWithLifecycle()
    val info = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val host = status.serverLabel.ifEmpty { status.serverUrl?.let(ConnectionRepository::hostOf).orEmpty() }.ifEmpty { "—" }
    AboutPane(
        AboutPaneUi(
            appVersion = "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})",
            build = if (debuggable) "Debug" else "Release",
            packageName = context.packageName,
            server = "$host · ${phaseWord(status.phase)}",
        ),
        onRefresh = {
            g.connectionModel.connect()
            g.serverSettings.refresh()
        },
    )
}

fun phaseWord(p: ConnectionStatus.Phase) = when (p) {
    ConnectionStatus.Phase.CONNECTED -> "Connected"
    ConnectionStatus.Phase.CONNECTING -> "Connecting"
    ConnectionStatus.Phase.RECONNECTING -> "Reconnecting"
    ConnectionStatus.Phase.OFFLINE -> "Offline"
}
