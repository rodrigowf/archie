package com.assistant.archie.tv.firstrun

import com.assistant.archie.tv.theme.dpadFocusRestorer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.assistant.archie.feature.settings.ConnectionModel
import com.assistant.archie.feature.settings.ConnectionUiState
import com.assistant.archie.feature.settings.ServerRow
import com.assistant.archie.feature.settings.TrustRequest
import com.assistant.archie.tv.shell.Connect
import com.assistant.archie.tv.shell.Home
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvKeyHandler
import com.assistant.archie.tv.shell.TvOption
import com.assistant.archie.tv.shell.TvOverlay
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.theme.CardMeta
import com.assistant.archie.tv.theme.FocusCard
import com.assistant.archie.tv.theme.RowHeader
import com.assistant.archie.tv.theme.TvBadge
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvButtonStyle
import com.assistant.archie.tv.theme.TvDimens
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.theme.dismissOnBack
import com.assistant.archie.tv.theme.rememberInitialFocus
import com.assistant.archie.tv.theme.trapFocus
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.design.icons.ArchieIcons
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/*
 * Connect (spec 15 §3.6): LAN discovery (ServerDiscovery / LanScanner through ConnectionRepository),
 * a manual address typed with the IME, and the TOFU certificate trust for self-signed servers — the
 * phone's Settings → Connection logic (`:feature:settings`' ConnectionModel), TV-styled. First run:
 * the back-stack root; Continue marks setup done and replaces it with Home. From Settings → Connection
 * it is pushed and Continue pops. First run: Continue shows the remote-mic check (T4, MicCheckStep.kt),
 * which finishes setup.
 */

@Composable
fun ConnectRoute(key: Connect, ctx: TvRouteContext) {
    val g = ctx.graph
    val model = g.connectionModel
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // T4: first run, step 2 = the remote-mic check (spec 15 §3.6), then Home.
    var micStep by rememberSaveable { mutableStateOf(false) }
    if (micStep) {
        MicCheckRoute(ctx) {
            g.prefs.setupDone = true
            ctx.nav.replaceRoot(Home)
        }
    } else {
        ConnectRouteBody(key, ctx, model, state, scope) { micStep = true }
    }
}

@Composable
private fun ConnectRouteBody(
    key: Connect,
    ctx: TvRouteContext,
    model: ConnectionModel,
    state: ConnectionUiState,
    scope: kotlinx.coroutines.CoroutineScope,
    startMicStep: () -> Unit,
) {
    LaunchedEffect(Unit) { model.scan() }
    ConnectScreen(
        state = state,
        firstRun = key.firstRun,
        actions = ConnectActions(
            select = model::select,
            scan = model::scan,
            address = { raw ->
                val url = ConnectionModel.normalizeServerUrl(raw)
                if (url == null) {
                    "Enter an address like 192.168.0.200 or https://192.168.0.200"
                } else {
                    scope.launch { model.switchTo(url) }
                    null
                }
            },
            trust = model::trustAndSwitch,
            cancelTrust = model::cancelTrust,
            done = {
                if (key.firstRun) {
                    startMicStep() // T4: the mic check finishes setup
                } else {
                    ctx.nav.pop()
                }
            },
        ),
    )
}

class ConnectActions(
    val select: (ServerRow) -> Unit = {},
    val scan: () -> Unit = {},
    /** Returns an error for the field, or null when the address was accepted. */
    val address: (String) -> String? = { null },
    val trust: () -> Unit = {},
    val cancelTrust: () -> Unit = {},
    val done: () -> Unit = {},
)

/** The status line above the server list. */
fun connectSummary(st: ConnectionStatus): String {
    val where = st.serverLabel.ifEmpty { st.serverUrl.orEmpty() }
    return when (st.phase) {
        ConnectionStatus.Phase.CONNECTED -> "Connected to $where"
        ConnectionStatus.Phase.CONNECTING -> "Connecting to $where…"
        ConnectionStatus.Phase.RECONNECTING -> "Reconnecting to $where…"
        ConnectionStatus.Phase.OFFLINE -> "Not connected"
    }
}

@Composable
fun ConnectScreen(state: ConnectionUiState, firstRun: Boolean, actions: ConnectActions, modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    val connected = state.status.phase == ConnectionStatus.Phase.CONNECTED
    var typing by rememberSaveable { mutableStateOf(false) }
    ScreenChromeEffect(
        ScreenChrome(
            title = if (firstRun) "Connect to Archie" else "Connection",
            hints = listOf(KeyHints.Ok("Select"), KeyHints.Menu, KeyHints.Back),
            options = listOf(
                TvOption("scan", "Search again", ArchieIcons.Refresh) { actions.scan() },
                TvOption("address", "Enter address", ArchieIcons.Keyboard) { typing = true },
            ),
            // The trust dialog owns the keys while it shows (no options sheet over it).
            keys = if (state.trust != null) TvKeyHandler { true } else null,
        ),
    )
    val primary = rememberInitialFocus(connected)
    Row(modifier.fillMaxSize().testTag("connect"), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f)) {
            Text(connectSummary(state.status), style = TvTheme.type.titleLarge, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Pick your Archie server. It runs on your computer or home server; this TV finds it on the local network.",
                style = TvTheme.type.bodyMedium,
                color = c.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            RowHeader("Servers", trailing = if (state.status.scanning) "Searching the network…" else null)
            LazyColumn(Modifier.weight(1f).dpadFocusRestorer(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
                itemsIndexed(state.rows, key = { _, r -> r.url }) { i, row ->
                    ServerCard(
                        row,
                        connected = row.current && connected,
                        switching = state.switching == row.url,
                        onClick = { actions.select(row) },
                        modifier = if (i == 0 && !connected) Modifier.focusRequester(primary) else Modifier,
                    )
                }
            }
        }
        // End padding: room for the focused button's 1.05 scale inside the overscan-safe box.
        Column(Modifier.width(316.dp).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(4.dp))
            TvButton(
                if (firstRun) (if (connected) "Continue" else "Skip for now") else "Done",
                actions.done,
                Modifier.fillMaxWidth().then(if (connected || state.rows.isEmpty()) Modifier.focusRequester(primary) else Modifier).testTag("connect-continue"),
                icon = ArchieIcons.ArrowForward,
                style = TvButtonStyle.Primary,
            )
            TvButton("Search again", actions.scan, Modifier.fillMaxWidth(), icon = ArchieIcons.Refresh, enabled = !state.status.scanning)
            TvButton("Enter address", { typing = true }, Modifier.fillMaxWidth().testTag("connect-enter-address"), icon = ArchieIcons.Keyboard)
            if (typing) AddressField(onSubmit = { raw -> actions.address(raw).also { if (it == null) typing = false } }, onCancel = { typing = false })
        }
    }
    state.trust?.let { TrustDialog(it, onTrust = actions.trust, onCancel = actions.cancelTrust) }
}

@Composable
private fun ServerCard(row: ServerRow, connected: Boolean, switching: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth().testTag("server-${row.label}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (row.discovered) ArchieIcons.Lan else ArchieIcons.Dns, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(row.label, style = TvTheme.type.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CardMeta(row.detail)
            }
            Spacer(Modifier.width(12.dp))
            when {
                switching -> TvBadge("Checking…")
                connected -> TvBadge("Connected", emphasized = true)
                row.current -> TvBadge("Current")
                row.needsTrust -> TvBadge("Certificate check")
                row.saved -> TvBadge("Saved")
            }
        }
    }
}

/** Manual address: OK on the IME's "Go" connects (spec 15 §3.6). Opens the keyboard when shown. */
@Composable
private fun AddressField(onSubmit: (String) -> String?, onCancel: () -> Unit) {
    val c = TvTheme.colors
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    BackHandler(onBack = onCancel)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Server address", style = TvTheme.type.labelLarge, color = c.onSurfaceVariant)
        BasicTextField(
            value = text,
            onValueChange = { text = it; error = null },
            singleLine = true,
            textStyle = TvTheme.type.bodyLarge.copy(color = c.onSurface),
            cursorBrush = SolidColor(c.primary),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { error = onSubmit(text) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .background(c.surfaceContainerHigh, RoundedCornerShape(12.dp))
                .border(if (focused) TvDimens.FocusBorder else 1.dp, if (focused) c.primary else c.outline, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("connect-address"),
        )
        if (error != null) Text(error.orEmpty(), style = TvTheme.type.labelMedium, color = c.error)
        else Text("e.g. 192.168.0.200 or https://192.168.0.200", style = TvTheme.type.labelMedium, color = c.onSurfaceVariant)
    }
}

/**
 * TOFU (spec 14 §4.3): the server's certificate is unknown, or changed since it was trusted. A
 * full-screen shell overlay (TvOverlay, like the T1/T2 dialogs) over a scrim: focus is trapped
 * inside, Back cancels, Cancel has focus.
 */
@Composable
fun TrustDialog(t: TrustRequest, onTrust: () -> Unit, onCancel: () -> Unit) {
    val c = TvTheme.colors
    TvOverlay {
        BackHandler(onBack = onCancel)
        Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.6f)).testTag("trust-scrim"), contentAlignment = Alignment.Center) {
            TrustPanel(t, onTrust, onCancel)
        }
    }
}

@Composable
internal fun TrustPanel(t: TrustRequest, onTrust: () -> Unit, onCancel: () -> Unit) {
    val c = TvTheme.colors
    val cancel = rememberInitialFocus(t)
    val until = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t.certificate.notAfterMillis))
    Column(
        Modifier
            .widthIn(max = 680.dp)
            .background(c.surfaceContainerHigh, RoundedCornerShape(28.dp))
            .padding(horizontal = 32.dp, vertical = 28.dp)
            // Back cancels (a trap that cancels every exit would swallow it: Compose turns Back
            // into a focus exit); trapFocus() keeps the D-pad inside and lets Exit through.
            .dismissOnBack(onCancel)
            .trapFocus()
            .testTag("trust-dialog"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (t.changed) "The certificate changed" else "Trust this server?", style = TvTheme.type.headlineSmall, color = c.onSurface)
        Text(
            if (t.changed) "${t.hostPort} now presents a different certificate than the one you trusted. Only continue if you replaced it yourself."
            else "${t.hostPort} uses a certificate this TV doesn't know. Only trust it if this is your own Archie server.",
            style = TvTheme.type.bodyMedium,
            color = if (t.changed) c.error else c.onSurfaceVariant,
        )
        KeyValue("Subject", t.certificate.subject)
        KeyValue("Issuer", t.certificate.issuer)
        KeyValue("Valid until", until)
        Text("SHA-256 key fingerprint", style = TvTheme.type.labelMedium, color = c.onSurfaceVariant)
        Text(t.certificate.spkiSha256Hex, style = TvTheme.type.code.copy(fontSize = TvTheme.type.labelMedium.fontSize, lineHeight = TvTheme.type.labelMedium.lineHeight), color = c.onSurface)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvButton("Cancel", onCancel, Modifier.focusRequester(cancel).testTag("trust-cancel"))
            TvButton(if (t.changed) "Trust new certificate" else "Trust", onTrust, Modifier.testTag("trust-confirm"), style = TvButtonStyle.Primary)
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row {
        Text("$key  ", style = TvTheme.type.labelLarge, color = TvTheme.colors.onSurfaceVariant)
        Text(value, style = TvTheme.type.bodyMedium, color = TvTheme.colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
