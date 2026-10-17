package com.assistant.archie.tv.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.assistant.archie.feature.visuals.VisualUrls
import com.assistant.archie.tv.control.MediaAction
import com.assistant.archie.tv.control.RemoteInfo
import com.assistant.archie.tv.control.RemoteScreen
import com.assistant.archie.tv.control.RemoteScreenEffect
import com.assistant.archie.tv.control.ScrollDirection
import com.assistant.archie.tv.shell.HintGlyph
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvDestination
import com.assistant.archie.tv.shell.TvKey
import com.assistant.archie.tv.shell.TvKeyHandler
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.shell.Viewer
import com.assistant.archie.tv.theme.FocusCard
import com.assistant.archie.tv.theme.KeyCap
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvButtonStyle
import com.assistant.archie.tv.theme.TvDimens
import com.assistant.archie.tv.theme.TvFormat
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.theme.dismissOnBack
import com.assistant.archie.tv.theme.rememberInitialFocus
import com.assistant.archie.tv.theme.trapFocus
import com.assistant.core.design.components.ArchieMark
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.markdown.InternalLinks
import com.assistant.core.network.UrlScheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * Viewer (spec 15 §3.5, mockups 11 / 11b / 12): a full-screen WebView with no app chrome. D-pad =
 * Chromium spatial navigation (the TV has no touchscreen), Up/Down scroll when focus can't move
 * further; Pointer mode (Menu sheet, or by itself on pages with nothing focusable); Menu → options
 * sheet; Play/Pause → page media; Rewind/FF scroll; Back = page history, then close. A pushed page
 * ("Opened by Archie") keeps the TvServerHub launcher awake. Live reload keeps the scroll position.
 */

/**
 * Pings the launcher at once and every [everyMs] while [foreground] is true; stops while it is false
 * and starts over (an immediate ping) when it is true again. Runs until cancelled.
 */
suspend fun keepAwakeWhileForeground(foreground: Flow<Boolean>, everyMs: Long, ping: () -> Unit) {
    foreground.distinctUntilChanged().collectLatest { inFront ->
        while (inFront) {
            ping()
            delay(everyMs)
        }
    }
}

/** The absolute URL a [Viewer] target opens: root-relative paths resolve against the server, each segment encoded (VZ-1). */
fun viewerUrl(target: String, serverUrl: String): String {
    if ("://" in target) return target
    val path = target.trimStart('/').split('/').joinToString("/") { if (it.isEmpty()) it else VisualUrls.encodeSegment(it) }
    return UrlScheme.httpBase(serverUrl).trimEnd('/') + "/" + path
}

/** The visualization behind a Viewer target (live reload, "changed" dots), or null for another site. */
fun viewerVisualPath(target: String, origin: String): String? {
    if ("://" !in target) return target.trimStart('/').let { if (it.endsWith("/")) it + "index.html" else it }.takeIf { it.isNotEmpty() }
    return (InternalLinks.resolve(target, InternalLinks.Context(origin)) as? InternalLinks.Target.Visual)?.path
}

/** What the Viewer chrome shows (goldens build it directly). */
@Immutable
data class ViewerUi(
    val title: String,
    val url: String,
    /** The 3 s top toast ("Opened by Archie · Solar dashboard"), null = none. */
    val toast: String? = null,
    val pointerMode: Boolean = false,
    val cursor: Offset = Offset.Zero,
    val loading: Boolean = false,
    val progress: Int = 0,
    val error: String? = null,
    val zoom: Int = 100,
    /** The live-reload cue: "Updated" (or "Deleted" when [deleted]). */
    val updated: Boolean = false,
    val deleted: Boolean = false,
)

class ViewerActions(
    val onBack: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onReload: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onHardReset: () -> Unit = {},
    val onPointer: (Boolean) -> Unit = {},
    val onZoom: (Int) -> Unit = {},
    val onFindInVisuals: () -> Unit = {},
    val onMedia: () -> Unit = {},
    val onScroll: (ScrollDirection) -> Unit = {},
    /** Gives Android focus back to the page (after the sheet closes). */
    val onFocusPage: () -> Unit = {},
)

@Composable
fun ViewerRoute(key: Viewer, ctx: TvRouteContext) {
    val g = ctx.graph
    val control = g.tvControl
    val origin = remember { UrlScheme.httpBase(g.currentServerUrl()) }
    val url = remember(key) { viewerUrl(key.target, g.currentServerUrl()) }
    val vizPath = remember(key) { viewerVisualPath(key.target, origin) }
    val controller = remember(key) { ViewerController(url) }
    val scope = rememberCoroutineScope()

    val stamps by g.content.visuals.collectAsStateWithLifecycle()
    val stamp = vizPath?.let { stamps[it] }
    val live = stamp?.takeUnless { it.deleted }?.version ?: 0
    var updated by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(vizPath, live) { if (vizPath != null) control.visualsSeen.markOpened(vizPath, live) }
    if (updated > 0) {
        LaunchedEffect(updated) {
            delay(UPDATED_CUE_MS)
            updated = 0
        }
    }
    // KEEP_AWAKE while a pushed page shows (spec 15 TV-10): nobody touches the remote while watching
    // it. Only while the Viewer is in front (RESUMED): never from the background (B4).
    if (key.pushed) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(key, lifecycle) {
            keepAwakeWhileForeground(
                foreground = lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.RESUMED) },
                everyMs = KEEP_AWAKE_EVERY_MS,
                ping = { g.launcherPing.ping(force = true) },
            )
        }
    }

    val baseTitle = key.title ?: TvFormat.titleOf(key.target.substringBefore('?').trimEnd('/').ifEmpty { key.target })
    val title = key.title ?: controller.pageTitle ?: baseTitle
    val toast = remember(key) {
        when {
            key.pushed && g.prefs.showOpenedToasts -> "Opened by Archie · $baseTitle"
            key.pushed -> null
            else -> baseTitle
        }
    }
    RemoteScreenEffect(
        control.remote,
        key,
        RemoteInfo(url = controller.url, title = title, path = vizPath),
        object : RemoteScreen {
            override fun back() = controller.back()
            override fun reload(): Boolean {
                controller.reload()
                return controller.webView != null
            }
            override fun scroll(direction: ScrollDirection) = controller.scroll(direction)
            override suspend fun media(action: MediaAction) = controller.media(action)
        },
    )
    val actions = remember(controller) {
        ViewerActions(
            onBack = { if (!controller.back()) ctx.nav.pop() },
            onClose = { ctx.nav.pop() },
            onReload = controller::reload,
            onRetry = {
                controller.retry()
                // The error panel (and its focused Retry) leaves: the page takes focus (B9).
                controller.webView?.let { w -> w.post { w.requestFocus() } }
            },
            onHardReset = controller::hardReset,
            onPointer = { controller.setPointer(it) },
            onZoom = controller::zoomBy,
            onFindInVisuals = {
                vizPath?.let(control.visualsSeen::requestFocus)
                ctx.nav.selectRoot(TvDestination.VISUALS)
            },
            onMedia = {
                scope.launch {
                    if (controller.media(MediaAction.TOGGLE) == null) g.toasts.show("No playable media on this page")
                }
            },
            onScroll = { controller.scroll(it) },
            onFocusPage = { controller.webView?.let { w -> w.post { w.requestFocus() } } },
        )
    }
    ViewerScreen(
        state = ViewerUi(
            title = title,
            url = controller.url,
            toast = toast,
            pointerMode = controller.pointerMode,
            cursor = Offset(controller.cursorX, controller.cursorY),
            loading = controller.loading,
            progress = controller.progress,
            error = controller.error,
            zoom = controller.zoom,
            updated = updated > 0 || stamp?.deleted == true,
            deleted = stamp?.deleted == true,
        ),
        actions = actions,
    ) {
        ViewerWebPage(
            pool = control.webPool,
            trust = control.webTrust,
            debug = control.debugWeb,
            poolKey = key.target,
            controller = controller,
            live = live,
            onLiveReload = { updated++ },
        )
    }
}

@Composable
fun ViewerScreen(
    state: ViewerUi,
    actions: ViewerActions,
    modifier: Modifier = Modifier,
    initialOptionsOpen: Boolean = false,
    page: @Composable () -> Unit,
) {
    val c = TvTheme.colors
    var optionsOpen by rememberSaveable { mutableStateOf(initialOptionsOpen) }
    var toastVisible by remember { mutableStateOf(state.toast != null) }
    LaunchedEffect(Unit) {
        delay(TOAST_MS)
        toastVisible = false
    }
    val closeOptions = {
        optionsOpen = false
        actions.onFocusPage()
    }
    ScreenChromeEffect(
        ScreenChrome(
            title = state.title,
            hints = emptyList(),
            fullScreen = true,
            showHeader = false,
            // Spec 15 §3.5: the page fills the screen (made for 1920×1080, its own safe area); the
            // Viewer's chips, toast and sheet keep to the overscan-safe box below.
            edgeToEdge = true,
            keys = TvKeyHandler { key ->
                when (key) {
                    TvKey.MENU -> {
                        when {
                            optionsOpen -> closeOptions()
                            state.pointerMode -> actions.onPointer(false) // mockup 11b: "≡ Turn off"
                            else -> optionsOpen = true
                        }
                        true
                    }
                    TvKey.PLAY_PAUSE -> {
                        if (!optionsOpen) actions.onMedia()
                        true
                    }
                    TvKey.REWIND -> {
                        if (!optionsOpen) actions.onScroll(ScrollDirection.UP)
                        true
                    }
                    TvKey.FAST_FORWARD -> {
                        if (!optionsOpen) actions.onScroll(ScrollDirection.DOWN)
                        true
                    }
                    TvKey.LONG_OK -> false
                }
            },
        ),
    )
    BackHandler {
        if (optionsOpen) closeOptions() else actions.onBack()
    }
    Box(modifier.fillMaxSize().background(c.surface).testTag("viewer")) {
        page()
        if (state.loading && state.progress in 1..99) {
            Box(Modifier.fillMaxWidth(state.progress / 100f).height(3.dp).background(c.primary).align(Alignment.TopStart))
        }
        if (state.pointerMode && state.error == null) PointerCursor(state.cursor)
        state.error?.let { err -> ErrorPanel(err, actions) }
        // The chips and the toast stay inside the overscan-safe box (the page itself is edge to edge).
        Box(Modifier.fillMaxSize().padding(horizontal = TvDimens.OverscanHorizontal, vertical = TvDimens.OverscanVertical)) {
            if (state.pointerMode && state.error == null) {
                PointerChip(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
            }
            if (state.updated) {
                UpdatedChip(if (state.deleted) "Deleted" else "Updated", Modifier.align(Alignment.TopEnd).padding(12.dp))
            }
            if (toastVisible && state.toast != null) {
                ViewerToast(state.toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
        }
        if (optionsOpen) {
            ViewerOptionsSheet(
                state = state,
                onDismiss = closeOptions,
                onReload = { closeOptions(); actions.onReload() },
                onPointer = { actions.onPointer(!state.pointerMode); closeOptions() },
                onZoom = actions.onZoom,
                onFind = { optionsOpen = false; actions.onFindInVisuals() },
                onHardReset = { closeOptions(); actions.onHardReset() },
                onClose = { optionsOpen = false; actions.onClose() },
            )
        }
    }
}

/** Mockup 11's toast: the Archie mark, "Opened by Archie · title", and how to get out. */
@Composable
private fun ViewerToast(text: String, modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    Row(
        modifier
            .widthIn(max = 560.dp)
            .background(c.surfaceContainerHighest, RoundedCornerShape(24.dp))
            .border(1.dp, c.outlineVariant, RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("viewer-toast"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArchieMark(size = 30.dp, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text, style = TvTheme.type.labelLarge, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("≡ Options · Back closes", style = TvTheme.type.labelMedium, color = c.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun UpdatedChip(text: String, modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    Box(modifier.background(c.primaryContainer, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp).testTag("viewer-updated")) {
        Text(text, style = TvTheme.type.labelMedium, color = c.onPrimaryContainer)
    }
}

/** The virtual cursor: an arrow with a dark outline, its tip at [at] (px in the page). */
@Composable
private fun PointerCursor(at: Offset) {
    val c = TvTheme.colors
    val fill = c.inverseSurface
    val outline = c.surface
    Canvas(Modifier.fillMaxSize().testTag("viewer-cursor")) {
        val s = 1.4f * density
        val p = Path().apply {
            moveTo(at.x, at.y)
            lineTo(at.x, at.y + 18 * s)
            lineTo(at.x + 4.5f * s, at.y + 13.5f * s)
            lineTo(at.x + 8 * s, at.y + 21 * s)
            lineTo(at.x + 11 * s, at.y + 19.5f * s)
            lineTo(at.x + 7.5f * s, at.y + 12.5f * s)
            lineTo(at.x + 13.5f * s, at.y + 12.5f * s)
            close()
        }
        drawPath(p, fill)
        drawPath(p, outline, style = Stroke(width = 1.5f * density, join = StrokeJoin.Round))
    }
}

/** Mockup 11b's chip: "Pointer mode · OK Click · ≡ Turn off". */
@Composable
private fun PointerChip(modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    Row(
        modifier
            .background(c.surfaceContainerHighest, RoundedCornerShape(50))
            .border(1.dp, c.outlineVariant, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("pointer-chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(ArchieIcons.TouchApp, contentDescription = null, modifier = Modifier.size(20.dp), tint = c.onSurface)
        Text("Pointer mode", style = TvTheme.type.labelLarge, color = c.onSurface)
        Spacer(Modifier.width(4.dp))
        KeyCap(HintGlyph.OK)
        Text("Click", style = TvTheme.type.labelLarge, color = c.onSurfaceVariant)
        KeyCap(HintGlyph.MENU)
        Text("Turn off", style = TvTheme.type.labelLarge, color = c.onSurfaceVariant)
    }
}

@Composable
private fun ErrorPanel(error: String, actions: ViewerActions) {
    val c = TvTheme.colors
    val retry = rememberInitialFocus(error)
    Column(
        Modifier.fillMaxSize().background(c.surface).padding(horizontal = TvDimens.OverscanHorizontal, vertical = TvDimens.OverscanVertical).focusGroup().testTag("viewer-error"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(ArchieIcons.CloudOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = c.onSurfaceVariant)
        Text("Could not load this page", style = TvTheme.type.titleLarge, color = c.onSurface)
        Text(error, style = TvTheme.type.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.widthIn(max = 560.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvButton("Retry", actions.onRetry, Modifier.focusRequester(retry), icon = ArchieIcons.Refresh, style = TvButtonStyle.Primary)
            TvButton("Close", actions.onClose, icon = ArchieIcons.Close)
        }
    }
}

/**
 * Mockup 12: a side sheet over the dimmed page — title, URL, Reload, Pointer mode (switch), Zoom
 * (Left/Right step it), Find in Visuals, Hard reset, Close. Focus stays inside; Back or Menu closes it.
 */
@Composable
fun ViewerOptionsSheet(
    state: ViewerUi,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onPointer: () -> Unit,
    onZoom: (Int) -> Unit,
    onFind: () -> Unit,
    onHardReset: () -> Unit,
    onClose: () -> Unit,
) {
    val c = TvTheme.colors
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.7f)).testTag("viewer-options-scrim")) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(400.dp + TvDimens.OverscanHorizontal)
                .background(c.surfaceContainerHigh, RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                // Drawn edge to edge: the rows keep to the overscan-safe box.
                .padding(start = 22.dp, end = 22.dp + TvDimens.OverscanHorizontal, top = TvDimens.OverscanVertical, bottom = TvDimens.OverscanVertical)
                // Back closes the sheet; a trap that cancels every exit would swallow it (B1).
                .dismissOnBack(onDismiss)
                .trapFocus()
                .testTag("viewer-options"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(state.title, style = TvTheme.type.titleLarge, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
            Text(
                state.url.substringAfter("://"),
                style = TvTheme.type.code.copy(fontSize = TvTheme.type.labelMedium.fontSize, lineHeight = TvTheme.type.labelMedium.lineHeight),
                color = c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            Spacer(Modifier.height(6.dp))
            SheetRow(ArchieIcons.Refresh, "Reload", onReload, Modifier.focusRequester(first).testTag("viewer-option-reload"))
            SheetRow(ArchieIcons.TouchApp, "Pointer mode", onPointer, Modifier.testTag("viewer-option-pointer")) {
                Switch(state.pointerMode)
            }
            SheetRow(
                ArchieIcons.Search,
                "Zoom",
                onClick = {},
                modifier = Modifier
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft -> { onZoom(-1); true }
                            Key.DirectionRight -> { onZoom(1); true }
                            else -> false
                        }
                    }
                    .testTag("viewer-option-zoom"),
            ) {
                ZoomStepper(state.zoom)
            }
            SheetRow(ArchieIcons.BarChart, "Find in Visuals", onFind, Modifier.testTag("viewer-option-find"))
            SheetRow(ArchieIcons.RestartAlt, "Hard reset", onHardReset, Modifier.testTag("viewer-option-reset"), detail = "Clear the page's cache and reload")
            SheetRow(ArchieIcons.Logout, "Close", onClose, Modifier.testTag("viewer-option-close"))
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
                KeyCap(HintGlyph.BACK)
                Spacer(Modifier.width(8.dp))
                Text("Close menu", style = TvTheme.type.labelLarge, color = c.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    FocusCard(
        onClick = onClick,
        onLongClick = {},
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = TvTheme.type.titleMedium, maxLines = 1)
                if (detail != null) Text(detail, style = TvTheme.type.labelMedium, maxLines = 1, color = LocalContentColor.current.copy(alpha = 0.72f))
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun Switch(on: Boolean) {
    val c = TvTheme.colors
    Box(
        Modifier
            .size(width = 44.dp, height = 26.dp)
            .background(if (on) c.primary else c.surfaceContainerHighest, RoundedCornerShape(50))
            .border(2.dp, if (on) c.primary else c.outline, RoundedCornerShape(50))
            .padding(4.dp)
            .testTag(if (on) "switch-on" else "switch-off"),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(if (on) 18.dp else 14.dp).background(if (on) c.onPrimary else c.outline, CircleShape))
    }
}

@Composable
private fun ZoomStepper(percent: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepCap("−")
        Text("$percent%", style = TvTheme.type.labelLarge, color = LocalContentColor.current)
        StepCap("+")
    }
}

@Composable
private fun StepCap(text: String) {
    val c = TvTheme.colors
    Box(Modifier.size(26.dp).background(c.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
        Text(text, style = TvTheme.type.labelLarge, color = c.onSurface)
    }
}

private const val TOAST_MS = 3_000L
private const val UPDATED_CUE_MS = 2_500L
private const val KEEP_AWAKE_EVERY_MS = 60_000L
