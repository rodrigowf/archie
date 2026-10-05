package com.assistant.core.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.core.design.ColorFamily
import com.assistant.core.design.Corner
import com.assistant.core.design.Motion
import com.assistant.core.design.ShapeFull
import com.assistant.core.design.StateLayer
import com.assistant.core.design.ToolCategory
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

/** Tool call state shown at the right of a card (mockup `.tc-st`). */
enum class ToolStatus { Running, Waiting, Done, Error }

/** Where a card sits: alone (r16) or inside a group, where the group rounds the outer corners. */
enum class ToolCardPlacement { Solo, Grouped }

/** The category color family of a tool. */
@Composable
fun toolColors(category: ToolCategory): ColorFamily = ArchieTheme.extended.tool(category)

/** 28 dp r9 tile with the category icon on its container color (mockup `.ti`). */
@Composable
fun ToolIconTile(category: ToolCategory, icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 28.dp, iconSize: Dp = 18.dp, corner: Dp = 9.dp) {
    val k = toolColors(category)
    Box(
        modifier.size(size).clip(RoundedCornerShape(corner)).background(k.colorContainer),
        contentAlignment = Alignment.Center,
    ) { ArchieIcon(icon, null, size = iconSize, tint = k.onColorContainer) }
}

/** The status glyph: spinner (running), hourglass (waiting), check (done), error. */
@Composable
fun ToolStatusIcon(status: ToolStatus) {
    val c = ArchieTheme.colors
    val x = ArchieTheme.extended
    when (status) {
        ToolStatus.Running -> Spinner()
        ToolStatus.Waiting -> ArchieIcon(ArchieIcons.HourglassTop, "Waiting", size = 18.dp, tint = x.warning.color)
        ToolStatus.Done -> ArchieIcon(ArchieIcons.CheckCircle, "Done", size = 18.dp, tint = x.success.color)
        ToolStatus.Error -> ArchieIcon(ArchieIcons.Error, "Failed", size = 18.dp, tint = c.error)
    }
}

/**
 * Tool card shell (spec 14 §3.4, mockup `.tc`): a neutral surface-container card; the category
 * color marks only the icon tile and the tool name. The header is one line — tile, name, mono
 * summary, then [meta] (duration, "+4 −1") and the status glyph — and toggles [expanded]. The
 * output slot ([ToolOutput]) shows whenever the caller passes one and the card is expanded;
 * callers keep R7: output is present whenever a result exists.
 */
@Composable
fun ToolCardShell(
    category: ToolCategory,
    icon: ImageVector,
    name: String,
    summary: String,
    status: ToolStatus,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    meta: (@Composable () -> Unit)? = null,
    placement: ToolCardPlacement = ToolCardPlacement.Solo,
    output: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = ArchieTheme.colors
    val k = toolColors(category)
    val shape = RoundedCornerShape(if (placement == ToolCardPlacement.Solo) Corner.Large else Corner.ExtraSmall)
    Column(modifier.fillMaxWidth().clip(shape).background(c.surfaceContainer)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolIconTile(category, icon)
            Text(name, style = ArchieTheme.typography.labelLarge, color = k.color, maxLines = 1)
            Text(
                summary,
                Modifier.weight(1f),
                style = ArchieTheme.text.codeSmall.copy(lineHeight = 18.sp),
                color = c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (meta != null) {
                    CompositionLocalProvider(LocalContentColor provides c.onSurfaceVariant) {
                        ProvideTextStyle(ArchieTheme.typography.bodySmall.copy(letterSpacing = 0.sp, fontFeatureSettings = "tnum")) { meta() }
                    }
                }
                ToolStatusIcon(status)
            }
        }
        if (output != null) {
            AnimatedVisibility(expanded, enter = expandVertically(tween(Motion.DurationShort4)), exit = shrinkVertically(tween(Motion.DurationShort4))) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), content = output)
            }
        }
    }
}

/** Tool meta text in the success color with mono figures ("+4 −1"). */
@Composable
fun ToolMetaDiffCount(text: String) {
    Text(text, style = ArchieTheme.text.codeSmall.copy(fontSize = ArchieTheme.typography.bodySmall.fontSize), color = ArchieTheme.extended.success.color)
}

/**
 * The output well under a tool header (mockup `.tc-o`): surface-container-lowest, r10, mono
 * 12.5/19 on-surface-variant. [content] lays out lines; use [ToolOutputLine] for styled lines.
 */
@Composable
fun ToolOutput(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = ArchieTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.surfaceContainerLowest)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides c.onSurfaceVariant) {
            ProvideTextStyle(ArchieTheme.text.codeSmall, content = { content() })
        }
    }
}

/** Line roles inside [ToolOutput]: plain, diff add/remove, hunk header, highlighted, ok, error, dim. */
enum class OutputLineKind { Plain, Added, Removed, Hunk, Highlight, Ok, Error, Dim }

/** One output line. Added/removed lines bleed to the well's edges with a tint, like the web diff. */
@Composable
fun ToolOutputLine(text: String, kind: OutputLineKind = OutputLineKind.Plain, trailing: (@Composable () -> Unit)? = null) {
    val c = ArchieTheme.colors
    val x = ArchieTheme.extended
    val (fg, bg) = when (kind) {
        OutputLineKind.Plain -> LocalContentColor.current to Color.Transparent
        OutputLineKind.Added -> x.toolWrite.color to x.toolWrite.tint
        OutputLineKind.Removed -> c.error to c.error.copy(alpha = StateLayer.Hover)
        OutputLineKind.Hunk -> x.toolRead.color to Color.Transparent
        OutputLineKind.Highlight -> c.onSurface to Color.Transparent
        OutputLineKind.Ok -> x.success.color to Color.Transparent
        OutputLineKind.Error -> c.error to Color.Transparent
        OutputLineKind.Dim -> LocalContentColor.current.copy(alpha = 0.7f) to Color.Transparent
    }
    val bleed = kind == OutputLineKind.Added || kind == OutputLineKind.Removed
    Row(
        Modifier
            .then(if (bleed) Modifier.bleed(12.dp).background(bg).padding(horizontal = 12.dp) else Modifier.fillMaxWidth()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = fg)
        trailing?.invoke()
    }
}

/**
 * "N steps" group header (IA §6, mockup `.tg-h`): stacked category tiles, the count, a summary,
 * the status, and a chevron that turns when [expanded].
 */
@Composable
fun ToolGroupHeader(
    count: Int,
    tiles: List<Pair<ToolCategory, ImageVector>>,
    summary: String,
    status: ToolStatus,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ArchieTheme.colors
    val turn by animateFloatAsState(
        if (expanded) 180f else 0f,
        if (ArchieTheme.reduceMotion) tween(0) else tween(Motion.DurationShort4, easing = Motion.EasingStandard),
        label = "chevron",
    )
    Row(
        modifier
            .height(40.dp)
            .clip(ShapeFull)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .padding(start = 6.dp, end = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OverlapRow(step = 18.dp) {
            tiles.take(4).forEach { (category, icon) ->
                // 2 dp ring of the conversation surface around each tile (mockup box-shadow).
                Box(Modifier.size(28.dp).background(c.surface, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    ToolIconTile(category, icon, size = 24.dp, iconSize = 15.dp, corner = 8.dp)
                }
            }
        }
        Text("$count steps", style = ArchieTheme.typography.labelLarge, color = c.onSurface, maxLines = 1)
        Text(
            summary,
            Modifier.weight(1f, fill = false),
            style = ArchieTheme.typography.bodyMedium.copy(letterSpacing = ArchieTheme.typography.labelLarge.letterSpacing),
            color = c.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ToolStatusIcon(status)
        ArchieIcon(ArchieIcons.KeyboardArrowDown, null, Modifier.rotate(turn), tint = c.onSurfaceVariant)
    }
}

/**
 * A tool group: header plus a 2 dp-gapped stack of [ToolCardShell]s (placement Grouped) whose
 * outer corners round to 16 dp (mockup `.tg-b`). Expanded while live; collapses once text follows.
 */
@Composable
fun ToolGroup(
    header: @Composable () -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    cards: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        header()
        AnimatedVisibility(expanded, enter = expandVertically(tween(Motion.DurationShort4)), exit = shrinkVertically(tween(Motion.DurationShort4))) {
            SegmentedColumn(Modifier.padding(top = 6.dp), outer = Corner.Large, content = cards)
        }
    }
}

/** Widens the rest of the chain by [h] on both sides (diff lines bleed to the well's edges). */
private fun Modifier.bleed(h: Dp): Modifier = layout { measurable, constraints ->
    val px = h.roundToPx()
    val w = constraints.maxWidth + 2 * px
    val p = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(constraints.maxWidth, p.height) { p.place(-px, 0) }
}

/** Places children left to right, each [step] after the previous one, so they overlap. */
@Composable
private fun OverlapRow(step: Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val stepPx = step.roundToPx()
        val width = if (placeables.isEmpty()) 0 else stepPx * (placeables.size - 1) + placeables.last().width
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) { placeables.forEachIndexed { i, p -> p.place(i * stepPx, 0) } }
    }
}
