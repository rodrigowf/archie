package com.assistant.archie.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.assistant.archie.feature.settings.Option
import com.assistant.archie.tv.theme.CardMeta
import com.assistant.archie.tv.theme.FocusCard
import com.assistant.archie.tv.theme.FocusOnOpen
import com.assistant.archie.tv.theme.TvSheetRow
import com.assistant.archie.tv.theme.TvSideSheet
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

/*
 * The rows of the Settings detail pane (spec 15 §3.5, mockup 14): every row is one focus stop —
 * a switch (OK flips it), a choice (OK opens a picker sheet), an action, or an info line (OK says
 * where to change it). Nothing in the pane takes typing except the TV control device name.
 */

/** A switch drawn like the mockup's (pill track, round thumb); not focusable itself. */
@Composable
fun TvSwitch(on: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = TvTheme.colors
    val track = when {
        on && enabled -> c.primary
        on -> c.primary.copy(alpha = 0.38f)
        else -> c.surfaceContainerHighest
    }
    Box(
        modifier
            .size(width = 52.dp, height = 32.dp)
            .background(track, RoundedCornerShape(50))
            .then(if (on) Modifier else Modifier.border(2.dp, c.outline, RoundedCornerShape(50)))
            .testTag(if (on) "switch-on" else "switch-off"),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = if (on) 24.dp else 8.dp)
                .size(if (on) 24.dp else 16.dp)
                .background(if (on) c.onPrimary else c.outline, CircleShape),
        )
    }
}

/** Title + optional note, the left part of every row. */
@Composable
private fun RowText(title: String, note: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = TvTheme.type.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note != null) CardMeta(note, maxLines = 2)
    }
}

@Composable
fun SettingsToggleRow(
    title: String,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    FocusCard(
        onClick = { if (enabled) onToggle(!on) },
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
            }
            RowText(title, note, Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            TvSwitch(on, enabled = enabled)
        }
    }
}

/** A choice: title (+ note) on the left, the current value on the right; OK opens the picker. */
@Composable
fun SettingsChoiceRow(choice: TvChoice, onOpen: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    FocusCard(
        onClick = { if (choice.enabled) onOpen() },
        modifier = modifier.fillMaxWidth().testTag("choice-${choice.key}"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
            }
            RowText(choice.title, choice.note, Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Text(
                choice.valueLabel,
                style = TvTheme.type.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = if (choice.enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp),
            )
            Icon(ArchieIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(22.dp), tint = LocalContentColor.current.copy(alpha = 0.6f))
        }
    }
}

/** A row that does something (Reconnect, Change server) or shows a value; [value] on the right. */
@Composable
fun SettingsActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    warn: Boolean = false,
) {
    FocusCard(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
            }
            RowText(title, note, Modifier.weight(1f))
            if (value != null) {
                Spacer(Modifier.width(16.dp))
                Text(
                    value,
                    style = TvTheme.type.labelLarge,
                    color = if (warn) ArchieTheme.extended.warning.color else LocalContentColor.current.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 300.dp),
                )
            }
        }
    }
}

/** Section title inside the detail pane. */
@Composable
fun SettingsSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = TvTheme.type.labelLarge,
        color = TvTheme.colors.primary,
        modifier = modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** The status box at the top of a pane (mockup 14 `.stbox`): good / waiting / bad. */
@Composable
fun SettingsStatusBox(title: String, body: String, tone: TvSettingsStatus.Tone, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = TvTheme.colors
    val x = ArchieTheme.extended
    val (bg, fg) = when (tone) {
        TvSettingsStatus.Tone.OK -> x.success.colorContainer to x.success.onColorContainer
        TvSettingsStatus.Tone.WAIT -> c.surfaceContainerHigh to c.onSurface
        TvSettingsStatus.Tone.BAD -> c.errorContainer to c.onErrorContainer
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .testTag("settings-status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            icon ?: when (tone) {
                TvSettingsStatus.Tone.OK -> ArchieIcons.CheckCircle
                TvSettingsStatus.Tone.WAIT -> ArchieIcons.HourglassTop
                TvSettingsStatus.Tone.BAD -> ArchieIcons.Warning
            },
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(28.dp),
        )
        Column {
            Text(title, style = TvTheme.type.titleMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(body, style = TvTheme.type.bodyMedium, color = fg.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The outlined note at the bottom of a pane (mockup 14 `.infob`). A focus stop with no action, so
 * the D-pad can scroll the pane down to it (nothing on a TV screen may be unreachable).
 */
@Composable
fun SettingsInfoBox(text: String, modifier: Modifier = Modifier, lead: String? = null) {
    val c = TvTheme.colors
    val shape = RoundedCornerShape(16.dp)
    FocusCard(
        onClick = {},
        modifier = modifier.fillMaxWidth().border(1.5.dp, c.outlineVariant, shape).testTag("settings-info"),
        shape = shape,
        containerColor = c.surface.copy(alpha = 0f),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(ArchieIcons.Info, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Text(if (lead != null) "$lead $text" else text, style = TvTheme.type.bodyMedium, color = c.onSurfaceVariant)
        }
    }
}

/** A block of plain lines (licenses) that the D-pad can reach; no action. */
@Composable
fun SettingsTextBlock(lines: List<String>, modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    FocusCard(
        onClick = {},
        modifier = modifier.fillMaxWidth(),
        containerColor = c.surface.copy(alpha = 0f),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) {
        for (l in lines) Text(l, style = TvTheme.type.labelMedium, color = c.onSurfaceVariant)
    }
}

/**
 * The picker of a [TvChoice] (a right side sheet): every option with its description, the current
 * one checked and focused on open. OK picks and closes; Back closes.
 */
@Composable
fun ChoicePicker(choice: TvChoice, onPick: (String) -> Unit, onClose: () -> Unit) {
    val requesters = remember(choice.key) { choice.options.associate { it.id to FocusRequester() } }
    val initial = requesters[choice.selected] ?: requesters[choice.options.firstOrNull { it.enabled }?.id]
    TvSideSheet(title = choice.title, onClose = onClose, eyebrow = "Choose", subtitle = choice.note, width = 460.dp, modifier = Modifier.testTag("choice-picker")) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
            itemsIndexed(choice.options, key = { _, o -> o.id }) { _, o ->
                PickerRow(o, o.id == choice.selected, { onPick(o.id) }, Modifier.focusRequester(requesters.getValue(o.id)))
            }
        }
    }
    if (initial != null) FocusOnOpen(initial, choice.key)
}

@Composable
private fun PickerRow(o: Option, checked: Boolean, onClick: () -> Unit, modifier: Modifier) {
    TvSheetRow(
        label = o.label,
        onClick = onClick,
        modifier = modifier.testTag("option-${o.id}"),
        detail = o.description,
        enabled = o.enabled,
        trailing = if (checked) {
            { Icon(ArchieIcons.Check, contentDescription = "Selected", modifier = Modifier.size(22.dp)) }
        } else {
            null
        },
    )
}

/** Small vertical gap between pane blocks. */
@Composable
fun PaneGap() = Spacer(Modifier.height(4.dp))
