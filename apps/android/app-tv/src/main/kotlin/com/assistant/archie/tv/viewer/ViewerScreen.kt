package com.assistant.archie.tv.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.shell.Viewer
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvEmptyState
import com.assistant.archie.tv.theme.TvFormat
import com.assistant.archie.tv.theme.rememberInitialFocus
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.network.UrlScheme

/*
 * Viewer (spec 15 §3.5) — T0 placeholder that names the page it would show. T3 replaces the body:
 * :feature:visuals' ArchieWebView (WebViewPool size 1, WebTrust), spatial navigation, pointer mode,
 * media keys (Play/Pause → page media via this screen's `keys`), live reload, history Back (its own
 * BackHandler, which wins over the shell's).
 */

/** The absolute URL a [Viewer] target opens: root-relative paths resolve against the server. */
fun viewerUrl(target: String, serverUrl: String): String =
    if ("://" in target) target else UrlScheme.httpBase(serverUrl).trimEnd('/') + "/" + target.trimStart('/')

@Composable
fun ViewerRoute(key: Viewer, ctx: TvRouteContext) {
    ViewerScreen(
        title = key.title ?: TvFormat.titleOf(key.target),
        url = viewerUrl(key.target, ctx.graph.currentServerUrl()),
        onBack = { ctx.nav.pop() },
    )
}

@Composable
fun ViewerScreen(title: String, url: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    ScreenChromeEffect(ScreenChrome(title = title, hints = listOf(KeyHints.Mic, KeyHints.Menu, KeyHints.Back), fullScreen = true))
    val back = rememberInitialFocus()
    TvEmptyState(title, "The web viewer arrives next. It will open $url", modifier.fillMaxSize().testTag("viewer"), icon = ArchieIcons.Language) {
        TvButton("Back", onBack, Modifier.focusRequester(back), icon = ArchieIcons.ArrowBack)
    }
}
