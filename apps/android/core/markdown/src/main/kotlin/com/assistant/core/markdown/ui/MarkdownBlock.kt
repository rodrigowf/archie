package com.assistant.core.markdown.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.assistant.core.markdown.MAX_BLOCK_DEPTH
import com.assistant.core.markdown.MdListItem
import com.assistant.core.markdown.MdNode

/**
 * Test hook: called every time a [MarkdownBlock] body runs (i.e. the block was not skipped).
 * The recomposition proof (`MarkdownRecompositionTest`) installs a counter; production leaves it null.
 */
val LocalMarkdownRenderProbe = staticCompositionLocalOf<((MdNode) -> Unit)?> { null }

/**
 * One top-level markdown node: the content of one lazy list item (spec 14 §3.1 `MdBlock`).
 * [onLinkClick] receives the raw href; classify it with
 * [com.assistant.core.markdown.LinkTarget.classify].
 */
@Composable
fun MarkdownBlock(
    node: MdNode,
    modifier: Modifier = Modifier,
    style: MarkdownStyle = MarkdownStyle.fromTheme(),
    onLinkClick: (String) -> Unit = {},
) {
    LocalMarkdownRenderProbe.current?.invoke(node)
    Box(modifier) { RenderNode(node, style, onLinkClick, depth = 0, color = style.textColor) }
}

@Composable
private fun RenderNode(node: MdNode, style: MarkdownStyle, onLink: (String) -> Unit, depth: Int, color: Color) {
    if (depth > MAX_BLOCK_DEPTH + 2) return // unreachable: the model is capped at MAX_BLOCK_DEPTH
    when (node) {
        is MdNode.Paragraph -> InlineText(node.inlines, style.body, color, style, onLink)
        is MdNode.Heading -> InlineText(
            node.inlines,
            style.heading(node.level),
            if (node.level == 6) style.mutedColor else color,
            style,
            onLink,
            Modifier.semantics { heading() },
        )
        is MdNode.ListBlock -> ListView(node, style, onLink, depth, color)
        is MdNode.Quote -> Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRoundRect(style.quoteBar, Offset.Zero, Size(4.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
                }
                .padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            node.children.forEach { RenderNode(it, style, onLink, depth + 1, style.mutedColor) }
        }
        is MdNode.CodeBlock -> CodeBlockView(node.lang, node.code, style)
        is MdNode.CodeTail -> CodeTailView(node, style)
        is MdNode.Table -> MarkdownTable(node, style, onLink)
        is MdNode.TableTail -> Text(
            node.raw,
            Modifier
                .fillMaxWidth()
                .background(style.codeSurface, RoundedCornerShape(style.codeCorner))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            color = style.mutedColor,
            style = style.code,
            softWrap = true,
        )
        MdNode.Rule -> Box(
            Modifier
                .padding(vertical = 8.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(style.ruleColor),
        )
    }
}

@Composable
private fun ListView(node: MdNode.ListBlock, style: MarkdownStyle, onLink: (String) -> Unit, depth: Int, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(style.itemSpacing)) {
        node.items.forEachIndexed { i, item ->
            ListItemRow(node, item, node.start + i, style, onLink, depth, color)
        }
    }
}

@Composable
private fun ListItemRow(
    list: MdNode.ListBlock,
    item: MdListItem,
    number: Int,
    style: MarkdownStyle,
    onLink: (String) -> Unit,
    depth: Int,
    color: Color,
) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.widthIn(min = if (list.ordered) style.listIndent + 4.dp else style.listIndent)) {
            when {
                list.ordered && item.task == null -> Text("$number${list.marker}", color = color, style = style.body)
                else -> DrawnMarker(list.level, item.task, color, style)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(style.itemSpacing)) {
            item.children.forEach { RenderNode(it, style, onLink, depth + 1, color) }
        }
    }
}

/**
 * A list marker drawn, not typed (a "•" glyph comes from a fallback font with its own metrics).
 * It sits in a one-space [Text] of the body style, so its box and first baseline are exactly
 * those of the item's first line, and the drawing is centred on the x-height above that baseline.
 * Bullets: level 0 disc, 1 ring, 2+ square (web `disc`/`circle`/`square`). [task] draws the
 * read-only GFM checkbox (web `.taskBox`).
 */
@Composable
private fun DrawnMarker(level: Int, task: Boolean?, color: Color, style: MarkdownStyle) {
    var baseline by remember { mutableFloatStateOf(0f) }
    val desc = when (task) {
        true -> "Done"
        false -> "Not done"
        null -> null
    }
    Text(
        "\u00A0",
        Modifier
            .then(if (desc != null) Modifier.semantics { contentDescription = desc } else Modifier)
            .drawBehind {
                val em = style.body.fontSize.toPx()
                val cy = baseline - em * 0.32f
                if (task == null) {
                    val r = 2.75.dp.toPx()
                    val c = Offset(2.dp.toPx() + r, cy)
                    when (level % 3) {
                        0 -> drawCircle(color, r, c)
                        1 -> drawCircle(color, r - 0.6.dp.toPx(), c, style = Stroke(1.2.dp.toPx()))
                        else -> drawRect(color, Offset(c.x - r * 0.9f, cy - r * 0.9f), Size(r * 1.8f, r * 1.8f))
                    }
                } else {
                    val box = 16.dp.toPx()
                    val top = cy - box / 2
                    val r = CornerRadius(3.dp.toPx())
                    val sw = 2.dp.toPx()
                    if (task) {
                        drawRoundRect(style.checkboxOn, Offset(0f, top), Size(box, box), r)
                        val p = Path().apply {
                            moveTo(box * 0.24f, top + box * 0.52f)
                            lineTo(box * 0.43f, top + box * 0.70f)
                            lineTo(box * 0.77f, top + box * 0.32f)
                        }
                        drawPath(p, style.checkboxOnContent, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    } else {
                        drawRoundRect(
                            style.checkboxOff,
                            topLeft = Offset(sw / 2, top + sw / 2),
                            size = Size(box - sw, box - sw),
                            cornerRadius = r,
                            style = Stroke(sw),
                        )
                    }
                }
            },
        style = style.body,
        onTextLayout = { baseline = it.firstBaseline },
    )
}
