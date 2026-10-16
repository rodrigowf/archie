package com.assistant.archie.tv.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.assistant.archie.tv.shell.FocusRestoreEffect
import com.assistant.archie.tv.shell.HintGlyph
import com.assistant.archie.tv.shell.KeyHint
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.SettingsPage
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.shell.TvSettingsPageId
import com.assistant.archie.tv.theme.FocusCard
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.theme.focusMemory
import com.assistant.archie.tv.theme.rememberFocusMemory
import com.assistant.core.design.icons.ArchieIcons

/*
 * Settings (spec 15 §3.5, mockup 14): a list on the left, the selected page's detail pane on the
 * right — one screen, D-pad only. Moving through the list shows each page; OK or Right enters the
 * pane; Back from the pane returns to the list; Back from the list leaves Settings. `Settings`
 * opens on the list, `SettingsPage(id)` opens with that page's pane focused.
 */

fun TvSettingsPageId.icon(): ImageVector = when (this) {
    TvSettingsPageId.CONNECTION -> ArchieIcons.Dns
    TvSettingsPageId.REMOTE_MIC -> ArchieIcons.Mic
    TvSettingsPageId.VOICE -> ArchieIcons.GraphicEq
    TvSettingsPageId.DISPLAY -> ArchieIcons.Visibility
    TvSettingsPageId.TV_CONTROL -> ArchieIcons.Tv
    TvSettingsPageId.SERVER -> ArchieIcons.Tune
    TvSettingsPageId.ABOUT -> ArchieIcons.Info
}

/** The key-hint bar: the list opens pages; the pane changes values (the mic tests on Remote microphone). */
fun settingsHints(page: TvSettingsPageId, inPane: Boolean): List<KeyHint> = when {
    !inPane -> listOf(KeyHints.Ok("Open"), KeyHints.Mic, KeyHints.Back)
    page == TvSettingsPageId.REMOTE_MIC -> listOf(KeyHints.Ok("Change"), KeyHint(HintGlyph.MIC, "Hold to test"), KeyHints.Back)
    page == TvSettingsPageId.ABOUT -> listOf(KeyHints.Ok("Select"), KeyHints.Mic, KeyHints.Back)
    else -> listOf(KeyHints.Ok("Change"), KeyHints.Mic, KeyHints.Back)
}

@Composable
fun SettingsRoute(ctx: TvRouteContext) {
    var page by rememberSaveable { mutableStateOf(TvSettingsPageId.CONNECTION) }
    SettingsScreen(page, onSelect = { page = it }, focusPaneOnStart = false) { id -> SettingsPaneRoute(id, ctx) }
}

@Composable
fun SettingsPageRoute(key: SettingsPage, ctx: TvRouteContext) {
    var page by rememberSaveable { mutableStateOf(key.id) }
    SettingsScreen(page, onSelect = { page = it }, focusPaneOnStart = true) { id -> SettingsPaneRoute(id, ctx) }
}

/**
 * The two panes. [pane] renders the detail of a page (production: [SettingsPaneRoute]; goldens:
 * fixtures). Selection follows list focus.
 */
@Composable
fun SettingsScreen(
    selected: TvSettingsPageId,
    onSelect: (TvSettingsPageId) -> Unit,
    focusPaneOnStart: Boolean,
    modifier: Modifier = Modifier,
    pages: List<TvSettingsPageId> = TvSettingsPages.ORDER,
    pane: @Composable (TvSettingsPageId) -> Unit,
) {
    var inPane by remember { mutableStateOf(false) }
    // Survives a pushed screen (Connect) and coming back: focus returns to the pane or list row it left.
    var paneHadFocus by rememberSaveable { mutableStateOf(focusPaneOnStart) }
    val memory = rememberFocusMemory()
    ScreenChromeEffect(ScreenChrome(title = "Settings", hints = settingsHints(selected, inPane)))
    val paneFocus = remember { FocusRequester() }
    fun focusList() = runCatching { memory.requester(selected.name).requestFocus() }.isSuccess
    fun focusPane() = runCatching { paneFocus.requestFocus() }.isSuccess

    FocusRestoreEffect { if (paneHadFocus) focusPane() || focusList() else focusList() }
    LaunchedEffect(Unit) {
        // After the pane's first layout (its rows exist only then).
        repeat(2) { withFrameNanos { } }
        if (!(paneHadFocus && focusPane())) focusList()
    }

    Row(modifier.fillMaxSize().testTag("settings"), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(
            Modifier
                .width(ListWidth)
                .fillMaxHeight()
                .padding(vertical = 6.dp)
                .onFocusChanged { if (it.hasFocus) paneHadFocus = false }
                .focusRestorer(memory.requester(selected.name))
                .focusGroup()
                .testTag("settings-list"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (p in pages) {
                SettingsListItem(
                    p,
                    selected = p == selected,
                    onOpen = {
                        onSelect(p)
                        runCatching { paneFocus.requestFocus() }
                    },
                    modifier = Modifier
                        .focusMemory(memory, p.name)
                        .onFocusChanged { if (it.isFocused && p != selected) onSelect(p) }
                        .testTag("settings-item-${p.name.lowercase()}"),
                )
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                // Back in the pane returns to the list row (bubbling phase: a text field in edit
                // mode or a picker takes Back first). Down is consumed too, or Compose would turn
                // it into a focus exit.
                .onKeyEvent { e ->
                    if (e.key != Key.Back) return@onKeyEvent false
                    if (e.type == KeyEventType.KeyUp) focusList()
                    true
                }
                .onFocusChanged {
                    inPane = it.hasFocus
                    // Remember only real moves between the panes (not focus leaving for an overlay).
                    if (it.hasFocus) paneHadFocus = true
                }
                .focusRequester(paneFocus)
                .focusRestorer()
                .focusGroup()
                .testTag("settings-pane"),
        ) {
            pane(selected)
        }
    }
}

private val ListWidth = 248.dp

/** One list entry (mockup 14 `.li`): icon tile + title; the selected one sits on a tinted row. */
@Composable
fun SettingsListItem(page: TvSettingsPageId, selected: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    FocusCard(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        containerColor = if (selected) c.secondaryContainer else c.surface.copy(alpha = 0f),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .background(if (selected) c.primaryContainer else c.surfaceContainerHigh, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(page.icon(), contentDescription = null, modifier = Modifier.size(20.dp), tint = if (selected) c.onPrimaryContainer else c.onSurfaceVariant)
            }
            Spacer(Modifier.width(14.dp))
            Text(page.title, style = TvTheme.type.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
