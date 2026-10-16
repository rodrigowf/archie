package com.assistant.archie.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.assistant.archie.tv.shell.Connect
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.SettingsPage
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.shell.TvSettingsPageId
import com.assistant.archie.tv.theme.CardMeta
import com.assistant.archie.tv.theme.FocusCard
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.theme.rememberInitialFocus
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.design.icons.ArchieIcons

/*
 * Settings (spec 15 §3.5) — list → detail. T0: the list, Connection (the Connect screen), Display
 * (text size, key hints, reduce motion) and About work; Remote microphone and Voice are T4, TV
 * control T3, Server T5 (SettingsPageScreen shows a placeholder for them).
 */

@Immutable
data class SettingsEntry(val id: String, val title: String, val summary: String, val icon: ImageVector, val key: NavKey)

fun settingsEntries(connection: ConnectionStatus): List<SettingsEntry> = listOf(
    SettingsEntry("connection", "Connection", connectionSummary(connection), ArchieIcons.Dns, Connect(firstRun = false)),
    SettingsEntry("remote-mic", TvSettingsPageId.REMOTE_MIC.title, "Hold the mic button to talk to Archie", ArchieIcons.Mic, SettingsPage(TvSettingsPageId.REMOTE_MIC)),
    SettingsEntry("voice", TvSettingsPageId.VOICE.title, "Live voice provider, model and voice", ArchieIcons.RecordVoiceOver, SettingsPage(TvSettingsPageId.VOICE)),
    SettingsEntry("display", TvSettingsPageId.DISPLAY.title, "Text size, key hints, motion", ArchieIcons.Tv, SettingsPage(TvSettingsPageId.DISPLAY)),
    SettingsEntry("tv-control", TvSettingsPageId.TV_CONTROL.title, "Let agents open pages on this TV", ArchieIcons.Cast, SettingsPage(TvSettingsPageId.TV_CONTROL)),
    SettingsEntry("server", TvSettingsPageId.SERVER.title, "Models, agents, working directories", ArchieIcons.Tune, SettingsPage(TvSettingsPageId.SERVER)),
    SettingsEntry("about", TvSettingsPageId.ABOUT.title, "Versions and licenses", ArchieIcons.Info, SettingsPage(TvSettingsPageId.ABOUT)),
)

private fun connectionSummary(s: ConnectionStatus): String {
    val phase = when (s.phase) {
        ConnectionStatus.Phase.CONNECTED -> "Connected"
        ConnectionStatus.Phase.CONNECTING -> "Connecting"
        ConnectionStatus.Phase.RECONNECTING -> "Reconnecting"
        ConnectionStatus.Phase.OFFLINE -> "Offline"
    }
    return if (s.serverLabel.isEmpty()) phase else "$phase · ${s.serverLabel}"
}

@Composable
fun SettingsRoute(ctx: TvRouteContext) {
    val connection = ctx.graph.connection.status.collectAsStateWithLifecycle().value
    SettingsScreen(settingsEntries(connection), onOpen = { ctx.nav.push(it.key) })
}

@Composable
fun SettingsScreen(entries: List<SettingsEntry>, onOpen: (SettingsEntry) -> Unit, modifier: Modifier = Modifier) {
    ScreenChromeEffect(ScreenChrome(title = "Settings", hints = listOf(KeyHints.Ok("Open"), KeyHints.Mic, KeyHints.Back)))
    val initial = rememberInitialFocus()
    LazyColumn(
        modifier.fillMaxSize().widthIn(max = 720.dp).focusRestorer().testTag("settings"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(6.dp),
    ) {
        itemsIndexed(entries, key = { _, e -> e.id }) { i, e ->
            SettingsRow(e.title, e.summary, e.icon, { onOpen(e) }, if (i == 0) Modifier.focusRequester(initial).testTag("settings-${e.id}") else Modifier.testTag("settings-${e.id}"))
        }
    }
}

/** One list row: icon, title, one-line summary. Reused by the detail pages. */
@Composable
fun SettingsRow(title: String, summary: String?, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(18.dp))
            }
            Column {
                Text(title, style = TvTheme.type.titleMedium)
                if (summary != null) CardMeta(summary)
            }
        }
    }
}
