package com.assistant.archie.tv.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import com.assistant.core.design.theme.ArchieTheme
import androidx.tv.material3.NavigationDrawerScope
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.tv.material3.Icon
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.assistant.archie.tv.theme.FocusCard
import com.assistant.archie.tv.theme.KeyHintBar
import com.assistant.archie.tv.theme.StatusCluster
import com.assistant.archie.tv.theme.TvDimens
import com.assistant.archie.tv.theme.TvIcons
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.theme.TvToastHost
import com.assistant.archie.tv.theme.dismissOnBack
import com.assistant.archie.tv.theme.trapFocus
import com.assistant.archie.tv.voice.VoiceChip
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.design.components.ArchieMark
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.voice.ports.VoiceSessionState

/** What the chrome renders from the graph (connection, voice, display prefs). */
@Immutable
data class TvShellState(
    val connection: ConnectionStatus = ConnectionStatus(),
    val voice: VoiceSessionState = VoiceSessionState(),
    val showKeyHints: Boolean = true,
    /** Some open agent waits for approval: the drawer's Conversations item shows the amber dot (spec 15 §3.5). */
    val needsApproval: Boolean = false,
)

/** Initial overlay state, for goldens (drawer expanded, options sheet open). */
data class TvShellOverlays(val drawerOpen: Boolean = false, val optionsOpen: Boolean = false)

/**
 * The 10-foot shell (spec 15 §3.4): overscan-safe box, tv-material navigation drawer (5
 * destinations), header (screen title + status cluster), the Navigation 3 content, the key-hint
 * bar, toasts and the options sheet. The shell owns chrome, navigation, Back and the accelerator
 * keys; every screen owns its content and tells the shell its title / hints / options / keys
 * through [ScreenChromeEffect].
 *
 * @param entry renders one back-stack key (production: `TvRoutes.Entry`; goldens: fixtures).
 */
@Composable
fun TvShell(
    state: TvShellState,
    controller: TvShellController,
    toasts: TvToasts,
    modifier: Modifier = Modifier,
    overlays: TvShellOverlays = TvShellOverlays(),
    entry: @Composable (key: NavKey) -> Unit,
) {
    val c = TvTheme.colors
    val focus = LocalFocusManager.current
    LaunchedEffect(overlays.optionsOpen) { if (overlays.optionsOpen) controller.optionsOpen = true }

    val overlayHost = remember { TvOverlayHost() }
    // The options sheet closed (Back, Menu or an option): focus goes back to the element the screen
    // had before it opened (the screen's FocusRestoreEffect, else the content's focusRestorer) —
    // without this the focused row leaves with the sheet and Compose drops focus, or hands it to
    // the drawer. An option that opened a dialog (an overlay layer) or another screen keeps its focus.
    var sheetOpenedOn by remember { mutableStateOf<NavKey?>(null) }
    LaunchedEffect(controller.optionsOpen) {
        if (controller.optionsOpen) {
            sheetOpenedOn = controller.nav.current
            return@LaunchedEffect
        }
        val openedOn = sheetOpenedOn ?: return@LaunchedEffect
        sheetOpenedOn = null
        repeat(2) { withFrameNanos { } }
        if (controller.nav.current == openedOn && overlayHost.layers.isEmpty()) controller.focusContent()
    }
    val top = controller.backStack.lastOrNull()
    val view = controller.registry.view(top) ?: ChromeView()
    val showDrawer = !view.fullScreen && TvDestination.of(controller.nav.root) != null

    Surface(
        modifier.fillMaxSize().testTag("tv-shell"),
        shape = RectangleShape,
        colors = SurfaceDefaults.colors(containerColor = c.surface, contentColor = c.onSurface),
    ) {
        // tv-material's Surface propagates min constraints; this Box gives the layers their own.
        Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalScreenChromeRegistry provides controller.registry,
            LocalTvKeyActions provides controller.keyActions,
            LocalTvOverlayHost provides overlayHost,
        ) {
            // Lowest-priority Back (stack root): Home / leave the app / leave the drawer. NavDisplay's
            // own handler pops; screens' and the options sheet's handlers come later and win.
            BackHandler { controller.back() }
            // Content, then (while the drawer has focus) a scrim, then the drawer on top: the drawer
            // overlays the content as in mockup 4 instead of pushing it (spec 15 §3.4).
            val contentFocus = remember { FocusRequester() }
            SideEffect {
                controller.focusContent = {
                    if (!controller.registry.restoreFocus(controller.nav.current)) {
                        runCatching { contentFocus.requestFocus() }.onFailure { focus.moveFocus(FocusDirection.Right) }
                    }
                }
            }
            Box(
                Modifier.fillMaxSize().padding(horizontal = TvDimens.OverscanHorizontal, vertical = TvDimens.OverscanVertical),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(start = if (showDrawer) TvDimens.DrawerCollapsed + 20.dp else 0.dp)
                        // Back from the drawer lands on the element that had focus here (focusRestorer).
                        .focusRequester(contentFocus)
                        .focusRestorer()
                        .focusGroup()
                        .testTag("shell-content"),
                ) {
                    if (view.showHeader) ShellHeader(view.title, state)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        NavDisplay(
                            backStack = controller.backStack,
                            onBack = { controller.back() },
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator(),
                            ),
                            entryProvider = { key ->
                                NavEntry(key) { k ->
                                    CompositionLocalProvider(LocalTvEntryKey provides k) { entry(k) }
                                }
                            },
                        )
                    }
                    if (state.showKeyHints) KeyHintBar(view.hints)
                }
            }
            val expanded = showDrawer && (controller.drawerFocused || overlays.drawerOpen)
            if (expanded) Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.55f)).testTag("drawer-scrim"))
            if (showDrawer) {
                Box(Modifier.fillMaxHeight().padding(start = TvDimens.OverscanHorizontal, top = TvDimens.OverscanVertical, bottom = TvDimens.OverscanVertical)) {
                    DrawerContent(controller, expanded, state.needsApproval, openOnStart = overlays.drawerOpen)
                }
            }
            TvOverlayLayers(overlayHost)
            TvToastHost(toasts, Modifier.align(Alignment.TopCenter))
            if (controller.optionsOpen) {
                OptionsSheet(
                    title = view.title,
                    options = view.options,
                    globals = globalOptions(controller),
                    onSelect = controller::runOption,
                    onClose = { controller.optionsOpen = false },
                )
            }
        }
        }
    }
}

@Composable
private fun ShellHeader(title: String, state: TvShellState) {
    val c = TvTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = TvTheme.type.displaySmall,
            color = c.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("screen-title"),
        )
        StatusCluster(state.connection.phase, VoiceChip.label(state.voice), VoiceChip.active(state.voice))
    }
}

/**
 * Archie mark + the five destinations (spec 15 §3.4, mockup 4): collapsed to icons, expanded with
 * labels over a scrim while it has focus. Left from the content's left edge enters it (on the
 * selected destination, or the item last focused); Right / OK leaves it; Back closes it.
 */
@Composable
private fun DrawerContent(controller: TvShellController, expanded: Boolean, needsApproval: Boolean, openOnStart: Boolean) {
    val c = TvTheme.colors
    val selected = TvDestination.of(controller.nav.root)
    val requesters = remember { TvDestination.entries.associateWith { FocusRequester() } }
    val scope = remember(expanded) {
        object : NavigationDrawerScope {
            override val hasFocus: Boolean = expanded
        }
    }
    if (openOnStart) {
        LaunchedEffect(Unit) {
            // After the screen's own initial focus; the second request lands inside the group (the
            // restorer sends the first one to the selected destination, as when the user enters).
            repeat(2) {
                repeat(3) { withFrameNanos { } }
                runCatching { requesters.getValue(TvDestination.CONVERSATIONS).requestFocus() }
            }
        }
    }
    Column(
        Modifier
            .fillMaxHeight()
            .width(if (expanded) DrawerOpenWidth else TvDimens.DrawerCollapsed)
            .background(if (expanded) c.surfaceContainerLow else c.surface.copy(alpha = 0f), RoundedCornerShape(24.dp))
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .onFocusChanged { controller.drawerFocused = it.hasFocus }
            // Right leaves the drawer to the element the content had focused (not the nearest one).
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                    controller.focusContent()
                    true
                } else {
                    false
                }
            }
            .focusRestorer(selected?.let { requesters.getValue(it) } ?: FocusRequester.Default)
            .focusGroup()
            .testTag("drawer"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 14.dp)) { ArchieMark(size = 40.dp, contentDescription = "Archie") }
        for (d in TvDestination.entries) {
            val isSelected = d == selected
            with(scope) {
                NavigationDrawerItem(
                    selected = isSelected,
                    onClick = {
                        controller.nav.selectRoot(d)
                        controller.focusContent()
                    },
                    leadingContent = {
                        Box {
                            Icon(d.icon(isSelected), contentDescription = null, modifier = Modifier.size(24.dp))
                            if (d == TvDestination.CONVERSATIONS && needsApproval) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 4.dp, y = (-3).dp)
                                        .size(10.dp)
                                        .background(ArchieTheme.extended.warning.color, CircleShape)
                                        .testTag("drawer-badge"),
                                )
                            }
                        }
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        contentColor = c.onSurface,
                        inactiveContentColor = c.onSurfaceVariant,
                        focusedContainerColor = c.inverseSurface,
                        focusedContentColor = c.inverseOnSurface,
                        selectedContainerColor = c.secondaryContainer,
                        selectedContentColor = c.onSecondaryContainer,
                        focusedSelectedContainerColor = c.inverseSurface,
                        focusedSelectedContentColor = c.inverseOnSurface,
                    ),
                    modifier = Modifier
                        .focusRequester(requesters.getValue(d))
                        .testTag("drawer-${d.name.lowercase()}"),
                ) {
                    Text(d.label, style = TvTheme.type.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

/** tv-material's expanded drawer item is 256 dp wide (fixed); the open drawer fits it plus padding. */
private val DrawerOpenWidth = 272.dp

private fun TvDestination.icon(selected: Boolean): ImageVector = when (this) {
    TvDestination.HOME -> if (selected) TvIcons.HomeFilled else TvIcons.Home
    TvDestination.CONVERSATIONS -> if (selected) ArchieIcons.ForumFilled else ArchieIcons.Forum
    TvDestination.VISUALS -> if (selected) ArchieIcons.BarChartFilled else ArchieIcons.BarChart
    TvDestination.MEMORY -> if (selected) ArchieIcons.Book2Filled else ArchieIcons.Book2
    TvDestination.SETTINGS -> if (selected) ArchieIcons.SettingsFilled else ArchieIcons.Settings
}

/** Navigation entries every options sheet ends with (not shown when already there). */
private fun globalOptions(controller: TvShellController): List<ChromeView.OptionView> {
    val root = TvDestination.of(controller.nav.root)
    return buildList {
        if (root != TvDestination.HOME || controller.nav.depth > 1) add(ChromeView.OptionView(TvShellController.GLOBAL_HOME, "Go to Home", TvIcons.Home, null, true))
        if (root != TvDestination.SETTINGS || controller.nav.depth > 1) add(ChromeView.OptionView(TvShellController.GLOBAL_SETTINGS, "Settings", ArchieIcons.Settings, null, true))
    }
}


/**
 * The options sheet (Menu ≡, long-press OK): a right side sheet over a scrim, focus trapped inside,
 * Back or Menu closes it. Never steals Play/Pause (live voice).
 */
@Composable
fun OptionsSheet(
    title: String,
    options: List<ChromeView.OptionView>,
    globals: List<ChromeView.OptionView>,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
) {
    val c = TvTheme.colors
    BackHandler(onBack = onClose)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val all = options + globals
    Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.55f)).testTag("options-scrim")) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(400.dp)
                .background(c.surfaceContainerHigh, RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                .padding(horizontal = 24.dp, vertical = TvDimens.OverscanVertical + 8.dp)
                // Back closes the sheet; a focus trap that cancels every exit would swallow it
                // (Compose turns Back into a focus exit), so trapFocus() lets Exit through.
                .dismissOnBack(onClose)
                .trapFocus()
                .testTag("options-sheet"),
        ) {
            Text("Options", style = TvTheme.type.labelLarge, color = c.primary)
            if (title.isNotEmpty()) Text(title, style = TvTheme.type.titleLarge, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.size(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(all, key = { _, o -> o.id }) { i, o ->
                    if (i == options.size && options.isNotEmpty()) Spacer(Modifier.size(8.dp))
                    FocusCard(
                        onClick = { onSelect(o.id) },
                        onLongClick = {},
                        enabled = o.enabled,
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier).testTag("option-${o.id}"),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (o.icon != null) {
                                Icon(o.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(14.dp))
                            }
                            Column {
                                Text(o.label, style = TvTheme.type.titleMedium, maxLines = 1)
                                if (o.detail != null) Text(o.detail, style = TvTheme.type.labelMedium, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
