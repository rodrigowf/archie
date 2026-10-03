package com.assistant.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.core.design.Corner
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

// ---------------------------------------------------------------- list items (drawer, list pane, switcher)

/** Row density: one-line (48 dp), two-line (60 dp), or the switcher's large row with a 40 dp tile (64 dp). */
enum class ListItemSize { OneLine, TwoLine, Large }

/**
 * List item (mockup `.li`): full-radius row; [selected] fills it with secondary-container. Leading
 * is 24 dp ([ListLeadingIcon], an [ArchieMark]) or a 40 dp [ListLeadingTile] for [ListItemSize.Large].
 * [supporting] is the 12 sp second line (status, provider chip); [trailing] holds meta, status or
 * a close button. Renders a real button (role), not a div with a click handler.
 */
@Composable
fun ArchieListItem(
    headline: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: ListItemSize = ListItemSize.OneLine,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    supporting: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = ArchieTheme.colors
    val (minH, radius) = when (size) {
        ListItemSize.OneLine -> 48.dp to 24.dp
        ListItemSize.TwoLine -> 60.dp to 30.dp
        ListItemSize.Large -> 64.dp to 20.dp
    }
    val shape = RoundedCornerShape(radius)
    val fg = if (selected) c.onSecondaryContainer else c.onSurface
    val sub = if (selected) c.onSecondaryContainer else c.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minH)
            .clip(shape)
            .then(if (selected) Modifier.background(c.secondaryContainer) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(start = if (size == ListItemSize.Large) 12.dp else 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(
                    headline,
                    style = if (size == ListItemSize.Large) ArchieTheme.typography.titleMedium else ArchieTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supporting != null) {
                    CompositionLocalProvider(LocalContentColor provides sub) {
                        ProvideTextStyle(ArchieTheme.typography.bodySmall) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = supporting)
                        }
                    }
                }
            }
            if (trailing != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
        }
    }
}

/** 24 dp leading slot with a 20 dp on-surface-variant icon. */
@Composable
fun ListLeadingIcon(icon: ImageVector) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        ArchieIcon(icon, null, size = 20.dp, tint = ArchieTheme.colors.onSurfaceVariant)
    }
}

/** 40 dp r12 tile (switcher rows). Pass an icon or put an [ArchieMark] in [content]. */
@Composable
fun ListLeadingTile(icon: ImageVector? = null, content: (@Composable () -> Unit)? = null) {
    val c = ArchieTheme.colors
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(Corner.Medium)).background(c.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content() else if (icon != null) ArchieIcon(icon, null, tint = c.onSurfaceVariant)
    }
}

/** Trailing meta text (time, count): 12 sp, on-surface-variant, tabular figures. */
@Composable
fun ListMeta(text: String) {
    Text(
        text,
        style = ArchieTheme.typography.bodySmall.copy(letterSpacing = 0.sp, fontFeatureSettings = "tnum"),
        color = ArchieTheme.colors.onSurfaceVariant,
    )
}

/** Section header in lists ("Open now", "Today"; mockup `.lh`). */
@Composable
fun ListSectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = ArchieTheme.typography.titleSmall, color = ArchieTheme.colors.onSurfaceVariant)
        trailing?.invoke(this)
    }
}

/** Search pill (mockup `.search`): 52 dp, surface-container-high; opens search on tap. */
@Composable
fun SearchPill(placeholder: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = ArchieTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(c.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArchieIcon(ArchieIcons.Search, null, tint = c.onSurfaceVariant)
        Text(placeholder, style = ArchieTheme.typography.bodyLarge.copy(letterSpacing = 0.sp), color = c.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- settings

/**
 * Settings group header (mockup `.sgh`): 12 sp semibold uppercase primary, with optional [meta]
 * on the right (server name + connection for "Archie (server)").
 */
@Composable
fun SettingsGroupHeader(title: String, modifier: Modifier = Modifier, meta: (@Composable RowScope.() -> Unit)? = null) {
    val c = ArchieTheme.colors
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            Modifier.weight(1f),
            style = ArchieTheme.typography.labelMedium.copy(fontWeight = FontWeight.W600, letterSpacing = 0.8.sp),
            color = c.primary,
        )
        if (meta != null) {
            CompositionLocalProvider(LocalContentColor provides c.onSurfaceVariant) {
                ProvideTextStyle(ArchieTheme.typography.labelMedium.copy(letterSpacing = 0.3.sp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = meta)
                }
            }
        }
    }
}

/**
 * A group of settings rows (mockup `.sg`): rows 2 dp apart on surface-container, 4 dp inner
 * corners and 20 dp outer corners. Also used for slider/field sets ([SettingsFieldSet]).
 */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SegmentedColumn(modifier, outer = 20.dp, content = content)
}

/** A column of 4 dp-cornered items whose group outline is rounded to [outer]. */
@Composable
fun SegmentedColumn(modifier: Modifier = Modifier, outer: androidx.compose.ui.unit.Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(outer)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/**
 * Settings row (mockup `.sr`): leading icon, title (16 sp), one-line current value, and a trailing
 * chevron, switch or other control. Server rows are disabled when offline.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = if (onClick != null) {
        { ArchieIcon(ArchieIcons.ChevronRight, null, tint = ArchieTheme.colors.onSurfaceVariant) }
    } else {
        null
    },
) {
    val c = ArchieTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(Corner.ExtraSmall))
            .background(c.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alpha = if (enabled) 1f else com.assistant.core.design.StateLayer.DisabledContent
        if (icon != null) ArchieIcon(icon, null, tint = c.onSurfaceVariant.copy(alpha = alpha))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = ArchieTheme.typography.bodyLarge.copy(lineHeight = 22.sp, letterSpacing = 0.sp),
                color = c.onSurface.copy(alpha = alpha),
            )
            if (value != null) {
                Text(
                    value,
                    style = ArchieTheme.typography.bodySmall.copy(letterSpacing = 0.sp),
                    color = c.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** A settings row with a switch; the whole row toggles. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        modifier = modifier,
        icon = icon,
        value = value,
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        trailing = { ArchieSwitch(checked, null, enabled = enabled) },
    )
}

/**
 * A labelled control block inside a [SettingsGroup] (mockup `.fset`): title + current value in
 * primary on one row, the control, then one short help line (IA §7).
 */
@Composable
fun SettingsFieldSet(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    help: String? = null,
    control: @Composable () -> Unit,
) {
    val c = ArchieTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.ExtraSmall))
            .background(c.surfaceContainer)
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = ArchieTheme.typography.titleMedium.copy(lineHeight = 22.sp, letterSpacing = 0.sp), color = c.onSurface)
            if (value != null) {
                Text(value, style = ArchieTheme.typography.labelLarge.copy(fontWeight = FontWeight.W600, fontFeatureSettings = "tnum"), color = c.primary)
            }
        }
        control()
        if (help != null) Text(help, style = ArchieTheme.typography.bodySmall.copy(letterSpacing = 0.sp), color = c.onSurfaceVariant)
    }
}

/** Shorthand: a [SettingsFieldSet] holding a [LevelSlider]; commits on release. */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onCommit: () -> Unit,
    valueText: String,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    help: String? = null,
    enabled: Boolean = true,
) {
    SettingsFieldSet(title, modifier, valueText, help) {
        LevelSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onCommit,
            valueLabel = { valueText },
            enabled = enabled,
        )
    }
}
