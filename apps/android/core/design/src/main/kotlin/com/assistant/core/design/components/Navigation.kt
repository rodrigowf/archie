package com.assistant.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.core.design.StateLayer
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

/** What leads a workspace tab: the Archie mark, or a type icon (agent, memory doc, visual). */
sealed interface TabLead {
    data object Archie : TabLead
    data class Icon(val icon: ImageVector) : TabLead
}

/**
 * A workspace tab in the top app bar (IA §3, mockup `.tb`): 44 dp, full radius, tone only. The
 * active tab is surface-container-highest with a primary type icon; hover adds the on-surface
 * state layer. The close button shows on the active tab and on hover ([hovered] forces it, for
 * previews and touch long-press). [status] is the live indicator (dot, spinner, warning).
 */
@Composable
fun TopBarTab(
    title: String,
    lead: TabLead,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    provider: String? = null,
    status: LiveStatus? = null,
    hovered: Boolean = false,
) {
    val c = ArchieTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    val hover = hovered || isHovered
    val bg = when {
        active -> c.surfaceContainerHighest
        hover -> c.onSurface.copy(alpha = StateLayer.Hover)
        else -> Color.Transparent
    }
    val fg = if (active) c.onSurface else c.onSurfaceVariant
    Row(
        modifier
            .height(44.dp)
            .widthIn(max = 290.dp)
            .clip(CircleShape)
            .background(bg)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Tab, onClick = onClick)
            .semantics { selected = active }
            .padding(start = 14.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            when (lead) {
                TabLead.Archie -> ArchieMark(size = 20.dp)
                is TabLead.Icon -> ArchieIcon(lead.icon, null, size = 20.dp, tint = if (active) c.primary else fg)
            }
            Text(
                title,
                Modifier.weight(1f, fill = false),
                style = ArchieTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (provider != null) ProviderChip(provider)
            if (status != null) StatusIndicator(status)
            Box(
                Modifier
                    .size(28.dp)
                    .alpha(if (active || hover) 1f else 0f)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { ArchieIcon(ArchieIcons.Close, "Close $title", size = 18.dp, tint = c.onSurfaceVariant) }
        }
    }
}

/** The horizontal strip that holds [TopBarTab]s (scrolls when they overflow; gap 4 dp). */
@Composable
fun TabStrip(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The Compact top app bar (IA §5, mockup `.appbar`): 64 dp on the content surface, no divider.
 * The title is a button (opens the session switcher) with an optional ⌄, a leading mark and a
 * live subtitle. Plain bars (settings pages) pass [onTitleClick] = null and no mark.
 */
@Composable
fun ArchieTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    titleExpanded: Boolean = false,
    showMark: Boolean = false,
    subtitle: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = ArchieTheme.colors
    Row(
        modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigationIcon?.invoke()
        if (onTitleClick == null && !showMark && subtitle == null) {
            Text(
                title,
                Modifier.weight(1f).padding(start = 4.dp),
                style = ArchieTheme.typography.titleLarge,
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Row(
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .then(if (onTitleClick != null) Modifier.clickable(role = Role.Button, onClick = onTitleClick) else Modifier)
                    .padding(PaddingValues(horizontal = 8.dp)),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showMark) ArchieMark(size = 24.dp)
                Column(Modifier.weight(1f, fill = false)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            Modifier.weight(1f, fill = false),
                            style = ArchieTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
                            color = c.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (onTitleClick != null) {
                            ArchieIcon(
                                if (titleExpanded) ArchieIcons.ArrowDropUp else ArchieIcons.ArrowDropDown,
                                null,
                                tint = c.onSurfaceVariant,
                            )
                        }
                    }
                    if (subtitle != null) {
                        CompositionLocalProvider(LocalContentColor provides c.onSurfaceVariant) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                content = subtitle,
                            )
                        }
                    }
                }
            }
        }
        actions()
    }
}

/** Subtitle text style for [ArchieTopAppBar] (12/16 medium, 0.3 tracking). */
@Composable
fun TopAppBarSubtitle(text: String, icon: ImageVector? = null, iconTint: Color = Color.Unspecified) {
    if (icon != null) ArchieIcon(icon, null, size = 15.dp, tint = if (iconTint != Color.Unspecified) iconTint else LocalContentColor.current)
    Text(
        text,
        style = ArchieTheme.typography.labelMedium.copy(letterSpacing = 0.3.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
