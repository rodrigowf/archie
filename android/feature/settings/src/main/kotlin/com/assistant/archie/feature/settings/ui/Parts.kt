package com.assistant.archie.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.assistant.archie.feature.settings.Option
import com.assistant.archie.feature.settings.SettingsMessages
import com.assistant.core.design.components.ArchieButton
import com.assistant.core.design.components.ArchieDialogSurface
import com.assistant.core.design.components.ArchieIconButton
import com.assistant.core.design.components.ArchieSnackbarHost
import com.assistant.core.design.components.ArchieTopAppBar
import com.assistant.core.design.components.ButtonStyle
import com.assistant.core.design.components.InlineCard
import com.assistant.core.design.components.InlineCardKind
import com.assistant.core.design.components.ScopeChip
import com.assistant.core.design.components.SettingsGroupHeader
import com.assistant.core.design.components.SettingsRow
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import kotlinx.coroutines.flow.collectLatest

/** True inside a settings surface that already shows a snackbar host (two-pane, sheet). */
internal val LocalSnackbarHosted = staticCompositionLocalOf { false }

/**
 * Collects the settings snackbar stream (IA §7: "Saved" / server error verbatim + Retry). The
 * newest message replaces the one showing, so a burst of saves shows one "Saved".
 */
@Composable
internal fun SettingsSnackbars(messages: SettingsMessages, host: SnackbarHostState) {
    LaunchedEffect(messages, host) {
        messages.messages.collectLatest { m ->
            val r = host.showSnackbar(
                message = m.text,
                actionLabel = m.actionLabel,
                withDismissAction = m.error,
                duration = if (m.error) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (r == SnackbarResult.ActionPerformed) m.retry?.invoke()
        }
    }
}

/**
 * A settings detail page (mockup phone (f)): top app bar with Back, a scrolling column with 16 dp
 * gutters, the scope chip first, and a snackbar host unless an ancestor already has one.
 */
@Composable
internal fun SettingsPageFrame(
    title: String,
    messages: SettingsMessages,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    scope: ScopeLabel? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val hosted = LocalSnackbarHosted.current
    val host = remember { SnackbarHostState() }
    if (!hosted) SettingsSnackbars(messages, host)
    Box(modifier.fillMaxSize().background(ArchieTheme.colors.surface)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            ArchieTopAppBar(
                title,
                navigationIcon = onBack?.let { back -> { ArchieIconButton(ArchieIcons.ArrowBack, "Back", back) } },
                actions = actions,
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    if (scope != null) {
                        Box(Modifier.padding(start = 4.dp, bottom = 16.dp)) { ScopeChip(scope.text, scope.icon) }
                    }
                    content()
                }
            }
        }
        if (!hosted) {
            Box(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).widthIn(max = 600.dp)) {
                ArchieSnackbarHost(host)
            }
        }
    }
}

/** Device vs server scope (IA §7: "device-vs-server scope is always visible"). */
internal data class ScopeLabel(val text: String, val icon: ImageVector) {
    companion object {
        fun device(name: String) = ScopeLabel("This device · $name", ArchieIcons.Mobile)
        fun server(host: String) = ScopeLabel("Archie (server) · $host", ArchieIcons.Dns)
    }
}

/** A labelled stack of rows (web `FieldStack label`): group header, then the segmented group. */
@Composable
internal fun Section(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) SettingsGroupHeader(title, Modifier.padding(top = 0.dp))
        com.assistant.core.design.components.SettingsGroup(content = content)
        Spacer(Modifier.height(16.dp))
    }
}

/** One short help line with the details behind ⓘ (IA §7). */
@Composable
internal fun HelpLine(text: String, info: String? = null, modifier: Modifier = Modifier) {
    val c = ArchieTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f, fill = false), style = ArchieTheme.typography.bodySmall.copy(letterSpacing = 0.sp), color = c.onSurfaceVariant)
        if (info != null) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClickLabel = "More info") { open = true }, contentAlignment = Alignment.Center) {
                ArchieIcon(ArchieIcons.Info, "More info", size = 16.dp, tint = c.onSurfaceVariant)
            }
        }
    }
    if (open && info != null) {
        Dialog(onDismissRequest = { open = false }) {
            ArchieDialogSurface(text, body = { Text(info) }) {
                ArchieButton("OK", { open = false }, style = ButtonStyle.Text)
            }
        }
    }
}

/** Surface block holding free content (help, buttons) inside a [Section], like `SettingsFieldSet`. */
@Composable
internal fun FieldBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(ArchieTheme.colors.surfaceContainer)
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Title + trailing control + one help line (web `Field label … trailing`). */
@Composable
internal fun ToggleField(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    help: String? = null,
    info: String? = null,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val c = ArchieTheme.colors
    val alpha = if (enabled) 1f else 0.38f
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(c.surfaceContainer)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ArchieTheme.typography.titleMedium.copy(lineHeight = 22.sp, letterSpacing = 0.sp), color = c.onSurface.copy(alpha = alpha))
            if (help != null) HelpLine(help, info)
        }
        com.assistant.core.design.components.ArchieSwitch(checked, null, enabled = enabled)
    }
}

/**
 * A select: a settings row showing the current choice; tapping opens a radio dialog (long model
 * lists scroll there). Web parity: `Select label options value supportingText`.
 */
@Composable
internal fun SelectRow(
    title: String,
    options: List<Option>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supporting: String? = null,
    icon: ImageVector? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val current = options.firstOrNull { it.id == selected }
    Column(modifier.fillMaxWidth()) {
        SettingsRow(
            title = title,
            icon = icon,
            value = current?.label ?: selected?.takeIf { it.isNotEmpty() } ?: "—",
            onClick = { open = true },
            enabled = enabled && options.isNotEmpty(),
            trailing = { ArchieIcon(ArchieIcons.ArrowDropDown, null, tint = ArchieTheme.colors.onSurfaceVariant) },
            modifier = Modifier.testTag("select:$title"),
        )
        if (supporting != null) {
            Box(
                Modifier.fillMaxWidth().padding(top = 2.dp).clip(RoundedCornerShape(4.dp)).background(ArchieTheme.colors.surfaceContainer)
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp, top = 2.dp),
            ) {
                Text(supporting, style = ArchieTheme.typography.bodySmall.copy(letterSpacing = 0.sp), color = ArchieTheme.colors.onSurfaceVariant)
            }
        }
    }
    if (open) {
        OptionDialog(title, options, selected, onDismiss = { open = false }) { id ->
            open = false
            if (id != selected) onSelect(id)
        }
    }
}

@Composable
internal fun OptionDialog(title: String, options: List<Option>, selected: String?, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val c = ArchieTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        ArchieDialogSurface(title, modifier = Modifier.widthIn(max = 420.dp), body = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                for (o in options) {
                    val on = o.id == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .then(if (on) Modifier.background(c.secondaryContainer) else Modifier)
                            .selectable(selected = on, enabled = o.enabled, role = Role.RadioButton) { onSelect(o.id) }
                            .testTag("option:${o.id}")
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(o.label, style = ArchieTheme.typography.bodyLarge, color = if (o.enabled) (if (on) c.onSecondaryContainer else c.onSurface) else c.onSurface.copy(alpha = 0.38f))
                            if (o.description != null) Text(o.description, style = ArchieTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (on) ArchieIcon(ArchieIcons.Check, null, size = 20.dp, tint = c.onSecondaryContainer)
                    }
                }
            }
        }) {
            ArchieButton("Cancel", onDismiss, style = ButtonStyle.Text)
        }
    }
}

/** Warning / error / info notices (web `Notice tone`), on the design system's inline card. */
internal enum class NoticeTone { INFO, WARNING, ERROR }

@Composable
internal fun Notice(
    tone: NoticeTone,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector? = null,
    onDismiss: (() -> Unit)? = null,
    actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) {
    val kind = when (tone) {
        NoticeTone.INFO -> InlineCardKind.Ended
        NoticeTone.WARNING -> InlineCardKind.Stall
        NoticeTone.ERROR -> InlineCardKind.Error
    }
    val ic = icon ?: when (tone) {
        NoticeTone.INFO -> ArchieIcons.Info
        NoticeTone.WARNING -> ArchieIcons.Warning
        NoticeTone.ERROR -> ArchieIcons.Error
    }
    InlineCard(kind, title, modifier.padding(bottom = 16.dp), icon = ic, onDismiss = onDismiss, body = body?.let { { Text(it) } }, actions = actions)
}

/** Key / value lines (About, Account). */
@Composable
internal fun KeyValues(rows: List<Pair<String, String>>) {
    val c = ArchieTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((k, v) in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(k, Modifier.widthIn(min = 120.dp, max = 140.dp), style = ArchieTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                Text(v, Modifier.weight(1f), style = ArchieTheme.typography.bodyMedium.copy(fontWeight = FontWeight.W500), color = c.onSurface)
            }
        }
    }
}

/** A small "badge" (SSH, Active). */
@Composable
internal fun Badge(text: String, active: Boolean = false) {
    val c = ArchieTheme.colors
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) c.secondaryContainer else c.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = ArchieTheme.typography.labelSmall,
        color = if (active) c.onSecondaryContainer else c.onSurfaceVariant,
    )
}

/** "Loading…" body (web `Loading label`). */
@Composable
internal fun LoadingBody(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        com.assistant.core.design.components.Spinner(size = 18.dp)
        Spacer(Modifier.size(12.dp))
        Text(label, style = ArchieTheme.typography.bodyMedium, color = ArchieTheme.colors.onSurfaceVariant)
    }
}

/** Provides [LocalSnackbarHosted] = true for a subtree whose ancestor shows the host. */
@Composable
internal fun SnackbarHosted(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSnackbarHosted provides true, content = content)
}
