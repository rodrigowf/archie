package com.assistant.archie.tv.settings

import androidx.compose.foundation.layout.Box
import com.assistant.archie.tv.theme.FocusMemory
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.assistant.archie.feature.settings.Option
import com.assistant.archie.tv.graph.TvDisplayPrefs
import com.assistant.archie.tv.theme.TvTextInput
import com.assistant.archie.tv.theme.TvTextSize
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.core.design.icons.ArchieIcons

/*
 * The detail panes of Settings (spec 15 §3.5, mockup 14), state-driven so the goldens render them
 * without a graph: Connection, Voice, Display, TV control, Server, About. Remote microphone is
 * T4's RemoteMicPage.kt. Each pane is one LazyColumn; every row is a focus stop.
 */

/** Padding inside a pane: room for a focused row's 1.05 scale and border. */
internal val PanePadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

@Composable
internal fun PaneColumn(tag: String, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    val memory = LocalPaneFocus.current
    LazyColumn(
        modifier.fillMaxSize().testTag(tag),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PanePadding,
        content = { if (memory == null) content() else RowMemoryScope(this, memory).content() },
    )
}

/** The pane's focus memory (SettingsScreen provides one per page): every row reports to it (B3). */
internal val LocalPaneFocus = staticCompositionLocalOf<FocusMemory?> { null }

/**
 * Wraps each row of a pane in a focus-memory box (by key, else position), so focus can come back to
 * the row the user left when an overlay closes; the rows themselves stay as they are.
 */
private class RowMemoryScope(private val base: LazyListScope, private val memory: FocusMemory) : LazyListScope by base {
    private var next = 0

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        val id = "row:" + (key ?: next)
        next++
        val m = memory
        base.item(key, contentType) { Box(Modifier.rowMemory(m, id)) { content() } }
    }

    override fun items(count: Int, key: ((index: Int) -> Any)?, contentType: (index: Int) -> Any?, itemContent: @Composable LazyItemScope.(index: Int) -> Unit) {
        val start = next
        next += count
        val m = memory
        base.items(count, key, contentType) { i -> Box(Modifier.rowMemory(m, "row:" + (key?.invoke(i) ?: (start + i)))) { itemContent(i) } }
    }
}

/** A requester on the row's box (it focuses the row inside) + "had focus last" for [memory]. */
private fun Modifier.rowMemory(memory: FocusMemory, id: String): Modifier =
    focusRequester(memory.requester(id)).onFocusChanged { if (it.hasFocus) memory.onFocused(id) }

// ───────────────────────────── choices: picker + focus back on the row ─────────────────────────────

/** Which choice's picker is open; focus returns to that row when it closes. */
@Stable
class ChoiceHost {
    var open: TvChoice? by mutableStateOf(null)
    internal var restore: String? by mutableStateOf(null)
    private val rows = HashMap<String, FocusRequester>()

    fun requester(key: String): FocusRequester = rows.getOrPut(key) { FocusRequester() }
}

@Composable
fun rememberChoiceHost(): ChoiceHost = remember { ChoiceHost() }

/** Draws the open picker; a pick that changes the value calls [onPick]. */
@Composable
fun ChoiceHostLayer(host: ChoiceHost, onPick: (TvChoice, String) -> Unit) {
    host.open?.let { ch ->
        ChoicePicker(
            ch,
            onPick = { v ->
                host.open = null
                host.restore = ch.key
                if (v != ch.selected) onPick(ch, v)
            },
            onClose = {
                host.open = null
                host.restore = ch.key
            },
        )
    }
    host.restore?.let { key ->
        LaunchedEffect(key) {
            withFrameNanos { }
            runCatching { host.requester(key).requestFocus() }
            host.restore = null
        }
    }
}

/** A choice row wired to [host] (its focus requester, OK opens the picker). */
@Composable
fun HostedChoiceRow(choice: TvChoice, host: ChoiceHost, modifier: Modifier = Modifier) {
    SettingsChoiceRow(choice, onOpen = { host.open = choice }, modifier = modifier.focusRequester(host.requester(choice.key)))
}

/** Rows that say where the TV leaves an edit: OK shows a toast pointing at the other apps. */
@Composable
fun ElsewhereRow(what: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SettingsActionRow(what, onClick, modifier.testTag("elsewhere"), value = "Phone or web app", icon = ArchieIcons.Mobile)
}

const val ELSEWHERE_TOAST = "Edit this in Settings on the phone or web app"

// ───────────────────────────── Connection ─────────────────────────────

@Immutable
data class ConnectionPaneUi(
    val title: String,
    val body: String,
    val tone: TvSettingsStatus.Tone,
    val serverLabel: String,
    val serverUrl: String,
    /** "Pinned on this TV", "Not encrypted (local network)", … */
    val certificate: String,
)

class ConnectionPaneActions(
    val changeServer: () -> Unit = {},
    val reconnect: () -> Unit = {},
)

@Composable
fun ConnectionPane(ui: ConnectionPaneUi, actions: ConnectionPaneActions, modifier: Modifier = Modifier) {
    PaneColumn("settings-connection", modifier) {
        item { SettingsStatusBox(ui.title, ui.body, ui.tone, icon = if (ui.tone == TvSettingsStatus.Tone.OK) ArchieIcons.CheckCircle else null) }
        item { SettingsActionRow("Server", actions.changeServer, Modifier.testTag("connection-server"), note = ui.serverUrl, value = ui.serverLabel, icon = ArchieIcons.Dns) }
        item { SettingsActionRow("Reconnect", actions.reconnect, Modifier.testTag("connection-reconnect"), note = "Connect again now", icon = ArchieIcons.Refresh) }
        item { SettingsActionRow("Certificate", actions.changeServer, note = "Trust is confirmed on the Connect screen", value = ui.certificate, icon = ArchieIcons.Shield) }
        item { SettingsInfoBox("Server opens the Connect screen: servers on this network, an address typed with the remote, the certificate check.") }
    }
}

// ───────────────────────────── Voice ─────────────────────────────

@Immutable
data class VoicePaneUi(
    /** The server's live-voice choices; null while loading ([error] when the load failed). */
    val choices: List<TvChoice>?,
    val error: String? = null,
    val offline: Boolean = false,
    val idle: TvChoice,
    /** CFG-6: "The saved Gemini Live model … was switched to …". */
    val autoCorrected: String? = null,
)

class VoicePaneActions(
    val pick: (TvChoice, String) -> Unit = { _, _ -> },
    val retry: () -> Unit = {},
    val dismissNotice: () -> Unit = {},
)

@Composable
fun VoicePane(ui: VoicePaneUi, actions: VoicePaneActions, modifier: Modifier = Modifier) {
    val host = rememberChoiceHost()
    PaneColumn("settings-voice", modifier) {
        ui.autoCorrected?.let { notice ->
            item { SettingsActionRow("Gemini Live model switched", actions.dismissNotice, note = notice, value = "Dismiss", icon = ArchieIcons.Warning, warn = true) }
        }
        item { SettingsSectionTitle("Live voice · on the server") }
        if (ui.offline) item { SettingsStatusBox("Offline", "These are the last values read from the server. Saving needs the connection.", TvSettingsStatus.Tone.WAIT) }
        when {
            ui.choices != null -> ui.choices.forEach { ch -> item(key = ch.key) { HostedChoiceRow(ch, host) } }
            ui.error != null -> item { SettingsActionRow("Couldn't load the voice settings", actions.retry, note = ui.error, value = "Retry", icon = ArchieIcons.Refresh, warn = true) }
            else -> item { SettingsStatusBox("Loading the voice settings…", "Reading the server's providers and models.", TvSettingsStatus.Tone.WAIT) }
        }
        item { SettingsSectionTitle("On this TV") }
        item(key = ui.idle.key) { HostedChoiceRow(ui.idle, host) }
        item {
            SettingsInfoBox(
                "Archie's voice plays through the TV's speakers: use the remote's volume buttons. Changes apply to the next live voice conversation; the phone and web apps share the server settings.",
            )
        }
    }
    ChoiceHostLayer(host, actions.pick)
}

// ───────────────────────────── Display ─────────────────────────────

class DisplayPaneActions(
    val textSize: (TvTextSize) -> Unit = {},
    val keyHints: (Boolean) -> Unit = {},
    val reduceMotion: (Boolean) -> Unit = {},
)

fun textSizeChoice(current: TvTextSize) = TvChoice(
    "text-size", "Text size",
    TvTextSize.entries.map { Option(it.name, it.label, if (it == TvTextSize.NORMAL) "Body text 20 sp" else "×${it.scale}") },
    current.name,
)

@Composable
fun DisplayPane(display: TvDisplayPrefs, actions: DisplayPaneActions, modifier: Modifier = Modifier) {
    val host = rememberChoiceHost()
    PaneColumn("settings-display", modifier) {
        item { HostedChoiceRow(textSizeChoice(display.textSize), host) }
        item { SettingsToggleRow("Show key hints", display.showKeyHints, actions.keyHints, Modifier.testTag("toggle-key-hints"), note = "The bar at the bottom of every screen") }
        item { SettingsToggleRow("Reduce motion", display.reduceMotion, actions.reduceMotion, Modifier.testTag("toggle-reduce-motion"), note = "No focus zoom or slide animations") }
    }
    ChoiceHostLayer(host) { _, v -> TvTextSize.entries.firstOrNull { it.name == v }?.let(actions.textSize) }
}

// ───────────────────────────── TV control ─────────────────────────────

@Immutable
data class ControlPaneUi(
    val status: TvSettingsStatus.Line,
    /** The saved name, or "" for the system's. */
    val deviceName: String,
    /** What agents see when [deviceName] is empty ("Fire TV"). */
    val systemName: String,
    val allowOpen: Boolean,
    val openedToasts: Boolean,
    val canRetry: Boolean,
    /** A TV_CONTROL_TOKEN is saved (never shown). */
    val tokenSet: Boolean = false,
)

class ControlPaneActions(
    val rename: (String) -> Unit = {},
    val allowOpen: (Boolean) -> Unit = {},
    val openedToasts: (Boolean) -> Unit = {},
    val retry: () -> Unit = {},
    /** "" removes the token. */
    val token: (String) -> Unit = {},
)

@Composable
fun ControlPane(ui: ControlPaneUi, actions: ControlPaneActions, modifier: Modifier = Modifier) {
    var draft by rememberSaveable(ui.deviceName) { mutableStateOf(ui.deviceName) }
    PaneColumn("settings-tv-control", modifier) {
        item { SettingsStatusBox(ui.status.title, ui.status.body, ui.status.tone) }
        if (ui.canRetry) item { SettingsActionRow("Connect again", actions.retry, Modifier.testTag("control-retry"), icon = ArchieIcons.Refresh) }
        item {
            Column(Modifier.fillMaxWidth()) {
                Text("Device name · shown to agents", style = TvTheme.type.labelLarge, color = TvTheme.colors.onSurfaceVariant, modifier = Modifier.testTag("control-name-label"))
                TvTextInput(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = ui.systemName,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = ArchieIcons.Tv,
                    imeAction = ImeAction.Done,
                    onSubmit = { actions.rename(it.trim()) },
                )
            }
        }
        item {
            Column(Modifier.fillMaxWidth()) {
                Text("Server token · only if the server sets TV_CONTROL_TOKEN", style = TvTheme.type.labelLarge, color = TvTheme.colors.onSurfaceVariant)
                // Never shows the saved token: typing a new one replaces it.
                var token by remember { mutableStateOf("") }
                TvTextInput(
                    value = token,
                    onValueChange = { token = it },
                    placeholder = if (ui.tokenSet) "Saved · OK to replace" else "None",
                    modifier = Modifier.fillMaxWidth().testTag("control-token"),
                    leadingIcon = ArchieIcons.Shield,
                    imeAction = ImeAction.Done,
                    onSubmit = {
                        if (it.isNotBlank()) actions.token(it.trim())
                        token = ""
                    },
                )
            }
        }
        if (ui.tokenSet) item { SettingsActionRow("Remove the token", { actions.token("") }, Modifier.testTag("control-token-remove"), icon = ArchieIcons.Delete) }
        item { SettingsToggleRow("Allow agents to open pages", ui.allowOpen, actions.allowOpen, Modifier.testTag("toggle-allow-open"), note = "Off: agents can still ask what is on screen") }
        item { SettingsToggleRow("Show “Opened by Archie” toasts", ui.openedToasts, actions.openedToasts, Modifier.testTag("toggle-opened-toasts"), note = "A 3 s note when an agent opens a page") }
        item { SettingsInfoBox("Agents open pages here with tv_cmd.py, or when you ask Archie to show something on the TV.") }
    }
}

// ───────────────────────────── Server ─────────────────────────────

@Immutable
data class ServerPaneUi(
    /** null while the config loads ([error] when it failed). */
    val sections: List<TvSettingsSection>?,
    val error: String? = null,
    val offline: Boolean = false,
    val host: String = "",
)

class ServerPaneActions(
    val pick: (TvChoice, String) -> Unit = { _, _ -> },
    val toggle: (TvToggle, Boolean) -> Unit = { _, _ -> },
    val retry: () -> Unit = {},
    val elsewhere: () -> Unit = {},
)

@Composable
fun ServerPane(ui: ServerPaneUi, actions: ServerPaneActions, modifier: Modifier = Modifier) {
    val host = rememberChoiceHost()
    PaneColumn("settings-server", modifier) {
        if (ui.offline) item { SettingsStatusBox("Offline", "These are the last values read from ${ui.host.ifEmpty { "the server" }}. Saving needs the connection.", TvSettingsStatus.Tone.WAIT) }
        when {
            ui.sections != null -> for (s in ui.sections) {
                item(key = "title:${s.title}") { SettingsSectionTitle(s.title) }
                s.choices.forEach { ch -> item(key = ch.key) { HostedChoiceRow(ch, host) } }
                s.toggles.forEach { t ->
                    item(key = t.key) { SettingsToggleRow(t.title, t.on, { actions.toggle(t, it) }, Modifier.testTag("toggle-${t.key}"), note = t.note, enabled = t.enabled) }
                }
                s.info.forEach { line ->
                    item(key = line.key) { SettingsActionRow(line.title, actions.elsewhere, Modifier.testTag("info-${line.key}"), value = line.value, warn = line.warn) }
                }
                s.elsewhere?.let { what -> item(key = "elsewhere:${s.title}") { ElsewhereRow(what, actions.elsewhere) } }
            }
            ui.error != null -> item { SettingsActionRow("Couldn't load server settings", actions.retry, note = ui.error, value = "Retry", icon = ArchieIcons.Refresh, warn = true) }
            else -> item { SettingsStatusBox("Loading server settings…", "Reading the config and catalogs from ${ui.host.ifEmpty { "the server" }}.", TvSettingsStatus.Tone.WAIT) }
        }
    }
    ChoiceHostLayer(host, actions.pick)
}

// ───────────────────────────── About ─────────────────────────────

@Immutable
data class AboutPaneUi(
    val appVersion: String,
    val build: String,
    val packageName: String,
    val server: String,
    val serverVersion: String = "Not reported by the server",
)

/** What the TV APK ships (spec 14 §1.4 catalog; no Vosk model or engine in this app). */
val TV_LICENSES = listOf(
    "AndroidX (Compose, tv-material, Navigation 3, Lifecycle, DataStore)" to "Apache-2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache-2.0",
    "OkHttp, Okio" to "Apache-2.0",
    "commonmark-java" to "BSD-2-Clause",
    "Highlights, java-diff-utils" to "Apache-2.0",
    "stream-webrtc-android (WebRTC)" to "Apache-2.0 / BSD-3-Clause",
    "JNA" to "Apache-2.0 / LGPL-2.1",
    "Roboto Flex, JetBrains Mono" to "OFL-1.1",
    "Material Symbols" to "Apache-2.0",
)

@Composable
fun AboutPane(ui: AboutPaneUi, onRefresh: () -> Unit, modifier: Modifier = Modifier, licensesOpen: Boolean = false) {
    var licenses by rememberSaveable { mutableStateOf(licensesOpen) }
    PaneColumn("settings-about", modifier) {
        item { SettingsActionRow("Archie TV", {}, Modifier.testTag("about-version"), note = ui.packageName, value = ui.appVersion, icon = ArchieIcons.Tv) }
        item { SettingsActionRow("Build", {}, value = ui.build, icon = ArchieIcons.Build) }
        item { SettingsActionRow("Server", onRefresh, note = "OK checks the server again", value = ui.server, icon = ArchieIcons.Dns) }
        item { SettingsActionRow("Server version", {}, value = ui.serverVersion, icon = ArchieIcons.Info) }
        item {
            SettingsActionRow(
                "Open-source licenses", { licenses = !licenses }, Modifier.testTag("about-licenses"),
                value = if (licenses) "Hide" else "Show", icon = ArchieIcons.Description,
            )
        }
        if (licenses) {
            item { SettingsTextBlock(TV_LICENSES.map { (name, license) -> "$name · $license" }, Modifier.testTag("licenses")) }
        }
    }
}
