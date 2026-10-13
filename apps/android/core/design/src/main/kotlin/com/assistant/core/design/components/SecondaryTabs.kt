package com.assistant.core.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assistant.core.design.theme.ArchieTheme
import androidx.compose.material3.SecondaryScrollableTabRow as M3SecondaryScrollableTabRow

/**
 * M3 secondary scrollable tabs (IA §6, spec 12 §4.8 UI): a second level of navigation *inside* a
 * pane, e.g. the agent tabs above a conversation ("Main", one tab per subagent). Tone only: the
 * selected tab is primary with the 2 dp secondary indicator, the others on-surface-variant, on the
 * conversation surface with an outline-variant divider. The row scrolls when the tabs overflow;
 * [selectedTabIndex] is scrolled into view by M3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecondaryScrollableTabRow(
    selectedTabIndex: Int,
    modifier: Modifier = Modifier,
    edgePadding: Dp = 8.dp,
    tabs: @Composable () -> Unit,
) {
    val c = ArchieTheme.colors
    M3SecondaryScrollableTabRow(
        selectedTabIndex = selectedTabIndex,
        modifier = modifier,
        containerColor = c.surface,
        contentColor = c.onSurface,
        edgePadding = edgePadding,
        divider = { HorizontalDivider(color = c.outlineVariant) },
        minTabWidth = 0.dp,
        tabs = tabs,
    )
}

/**
 * One tab of [SecondaryScrollableTabRow]: an optional [leading] glyph (a status icon) and a one-line
 * [label] (max 200 dp, ellipsised). [contentDescription] replaces the label for accessibility when
 * the glyph carries meaning ("Explore: find scroll logic, running").
 */
@Composable
fun SecondaryTab(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    contentDescription: String? = null,
) {
    val c = ArchieTheme.colors
    Tab(
        selected = selected,
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 48.dp)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        selectedContentColor = c.primary,
        unselectedContentColor = c.onSurfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Text(label, Modifier.widthIn(max = 200.dp), style = ArchieTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
