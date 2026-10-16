package com.assistant.archie.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.assistant.archie.tv.graph.TvDisplayPrefs
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.SettingsPage
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.shell.TvSettingsPageId
import com.assistant.archie.tv.theme.TvEmptyState
import com.assistant.archie.tv.theme.TvTextSize
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.theme.rememberInitialFocus
import com.assistant.core.design.icons.ArchieIcons

/** Settings detail pages. T0: Display and About; the others are placeholders for T3–T5. */
@Composable
fun SettingsPageRoute(key: SettingsPage, ctx: TvRouteContext) {
    val g = ctx.graph
    when (key.id) {
        TvSettingsPageId.DISPLAY -> {
            val display by g.prefs.display.collectAsStateWithLifecycle()
            DisplayPage(
                display,
                onTextSize = g.prefs::setTextSize,
                onKeyHints = g.prefs::setShowKeyHints,
                onReduceMotion = g.prefs::setReduceMotion,
            )
        }
        TvSettingsPageId.ABOUT -> {
            val context = LocalContext.current
            val info = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
            AboutPage(appVersion = "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})", server = g.currentServerUrl())
        }
        else -> PendingPage(key.id)
    }
}

@Composable
fun DisplayPage(
    display: TvDisplayPrefs,
    onTextSize: (TvTextSize) -> Unit,
    onKeyHints: (Boolean) -> Unit,
    onReduceMotion: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenChromeEffect(ScreenChrome(title = TvSettingsPageId.DISPLAY.title, hints = listOf(KeyHints.Ok("Change"), KeyHints.Back)))
    val first = rememberInitialFocus()
    val next = TvTextSize.entries[(display.textSize.ordinal + 1) % TvTextSize.entries.size]
    LazyColumn(modifier.fillMaxSize().widthIn(max = 720.dp).testTag("settings-display"), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
        item { SettingsRow("Text size", display.textSize.label, ArchieIcons.Tune, { onTextSize(next) }, Modifier.focusRequester(first)) }
        item { SettingsRow("Show key hints", if (display.showKeyHints) "On" else "Off", ArchieIcons.Keyboard, { onKeyHints(!display.showKeyHints) }) }
        item { SettingsRow("Reduce motion", if (display.reduceMotion) "On" else "Off", ArchieIcons.Speed, { onReduceMotion(!display.reduceMotion) }) }
    }
}

@Composable
fun AboutPage(appVersion: String, server: String, modifier: Modifier = Modifier) {
    ScreenChromeEffect(ScreenChrome(title = TvSettingsPageId.ABOUT.title, hints = listOf(KeyHints.Back)))
    val c = TvTheme.colors
    Column(modifier.fillMaxSize().testTag("settings-about"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Archie TV $appVersion", style = TvTheme.type.titleLarge, color = c.onSurface)
        Text("Server: $server", style = TvTheme.type.bodyMedium, color = c.onSurfaceVariant)
        Text("Open-source licenses: AndroidX (Compose, tv-material, Navigation 3), Kotlin, OkHttp, commonmark, WebRTC — Apache-2.0 / BSD.", style = TvTheme.type.bodyMedium, color = c.onSurfaceVariant)
    }
}

@Composable
fun PendingPage(id: TvSettingsPageId, modifier: Modifier = Modifier) {
    ScreenChromeEffect(ScreenChrome(title = id.title, hints = listOf(KeyHints.Back)))
    TvEmptyState(id.title, "This page is on its way.", modifier.fillMaxSize(), icon = ArchieIcons.Settings)
}
