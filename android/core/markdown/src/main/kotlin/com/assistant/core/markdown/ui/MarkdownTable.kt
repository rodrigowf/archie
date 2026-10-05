package com.assistant.core.markdown.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.assistant.core.markdown.MdNode
import com.assistant.core.markdown.TableAlign

const val TABLE_TAG = "md-table"

/**
 * A closed GFM table (spec 14 §3.3): every column is as wide as its widest cell's intrinsic
 * width, capped at [MarkdownStyle.tableMaxColumnWidth] (longer cells wrap), inside one horizontal
 * scroll. Cells render the full inline AST (bold, code, links): a cell rebuilt from plain text
 * renders blank (`project_compat_remark_gfm_shim.md`). Header row on surface-container-high,
 * GFM column alignment honoured.
 */
@Composable
internal fun MarkdownTable(node: MdNode.Table, style: MarkdownStyle, onLink: (String) -> Unit) {
    val columns = node.header.size
    if (columns == 0) return
    val geometry = remember { TableGeometry() }
    val headerStyle = style.tableText.copy(fontWeight = FontWeight.W500)
    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag(TABLE_TAG),
    ) {
        Layout(
            content = {
                val cells = listOf(node.header) + node.rows
                cells.forEachIndexed { r, row ->
                    row.forEachIndexed { c, cell ->
                        val align = when (node.aligns.getOrNull(c)) {
                            TableAlign.Center -> TextAlign.Center
                            TableAlign.Right -> TextAlign.End
                            else -> TextAlign.Start
                        }
                        InlineText(
                            cell,
                            if (r == 0) headerStyle else style.tableText,
                            style.textColor,
                            style,
                            onLink,
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            textAlign = align,
                        )
                    }
                }
            },
            modifier = Modifier.drawBehind {
                val g = geometry
                if (g.colX.isEmpty() || g.rowY.isEmpty()) return@drawBehind
                val w = g.colX.last()
                val h = g.rowY.last()
                if (g.rowY.size > 1) drawRect(style.tableHeaderSurface, Offset.Zero, Size(w.toFloat(), g.rowY[1].toFloat()))
                val stroke = 1.dp.toPx()
                for (x in g.colX) drawRect(style.tableBorder, Offset(x.toFloat().coerceAtMost(w - stroke), 0f), Size(stroke, h.toFloat()))
                for (y in g.rowY) drawRect(style.tableBorder, Offset(0f, y.toFloat().coerceAtMost(h - stroke)), Size(w.toFloat(), stroke))
            },
        ) { measurables, _ ->
            val rows = measurables.size / columns
            val maxW = style.tableMaxColumnWidth.roundToPx()
            val minW = 32.dp.roundToPx()
            val border = 1.dp.roundToPx().coerceAtLeast(1)
            val colW = IntArray(columns) { c ->
                var w = minW
                for (r in 0 until rows) w = maxOf(w, measurables[r * columns + c].maxIntrinsicWidth(Constraints.Infinity))
                minOf(w, maxW)
            }
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(colW[i % columns])) }
            val rowH = IntArray(rows) { r -> (0 until columns).maxOf { c -> placeables[r * columns + c].height } }
            val colX = IntArray(columns + 1)
            for (c in 0 until columns) colX[c + 1] = colX[c] + border + colW[c]
            colX[columns] += border
            val rowY = IntArray(rows + 1)
            for (r in 0 until rows) rowY[r + 1] = rowY[r] + border + rowH[r]
            rowY[rows] += border
            geometry.colX = colX
            geometry.rowY = rowY
            layout(colX[columns], rowY[rows]) {
                placeables.forEachIndexed { i, p ->
                    val r = i / columns
                    val c = i % columns
                    p.place(colX[c] + border, rowY[r] + border)
                }
            }
        }
    }
}

private class TableGeometry {
    var colX: IntArray = IntArray(0)
    var rowY: IntArray = IntArray(0)
}
