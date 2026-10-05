package com.assistant.core.markdown.internal

import com.assistant.core.markdown.MAX_BLOCK_DEPTH
import com.assistant.core.markdown.MAX_INLINE_DEPTH
import com.assistant.core.markdown.MdInline
import com.assistant.core.markdown.MdListItem
import com.assistant.core.markdown.MdNode
import com.assistant.core.markdown.TableAlign
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak

/**
 * commonmark AST → [MdNode]. Depth-capped on both axes: a block nested deeper than
 * [MAX_BLOCK_DEPTH] and an inline nested deeper than [MAX_INLINE_DEPTH] become plain text,
 * extracted iteratively (no recursion past the caps, so no stack risk whatever the input).
 */
internal object MdConverter {

    /** Heading slugs are assigned by the document (de-duplicated in commit order). */
    fun interface Slugger {
        fun slug(text: String): String
    }

    /**
     * A block that is not a top-level list. Returns null for nodes that render nothing
     * (link reference definitions).
     */
    fun block(node: Node, slugger: Slugger, depth: Int = 0, listLevel: Int = 0): MdNode? {
        if (depth > MAX_BLOCK_DEPTH) return MdNode.Paragraph(listOf(MdInline.Text(plainText(node))))
        return when (node) {
            is Paragraph -> MdNode.Paragraph(inlines(node, 0))
            is Heading -> {
                val inl = inlines(node, 0)
                MdNode.Heading(node.level.coerceIn(1, 6), inl, slugger.slug(plainText(node)))
            }
            is FencedCodeBlock -> MdNode.CodeBlock(langOf(node.info), node.literal.orEmpty().removeSuffix("\n"))
            is IndentedCodeBlock -> MdNode.CodeBlock(null, node.literal.orEmpty().removeSuffix("\n"))
            is ThematicBreak -> MdNode.Rule
            is BlockQuote -> MdNode.Quote(children(node, slugger, depth + 1, listLevel))
            is ListBlock -> list(node, slugger, depth, itemsOf(node), startOf(node), continuation = false, listLevel = listLevel)
            is TableBlock -> table(node)
            is HtmlBlock -> MdNode.Paragraph(listOf(MdInline.Text(node.literal.orEmpty().trimEnd())))
            is LinkReferenceDefinition -> null
            else -> plainText(node).takeIf { it.isNotBlank() }?.let { MdNode.Paragraph(listOf(MdInline.Text(it))) }
        }
    }

    fun itemsOf(list: ListBlock): List<ListItem> {
        val out = ArrayList<ListItem>()
        var c = list.firstChild
        while (c != null) {
            if (c is ListItem) out += c
            c = c.next
        }
        return out
    }

    fun startOf(list: ListBlock): Int = if (list is OrderedList) list.markerStartNumber ?: 1 else 1

    fun markerOf(list: ListBlock): Char = when (list) {
        is OrderedList -> list.markerDelimiter?.firstOrNull() ?: '.'
        is BulletList -> list.marker?.firstOrNull() ?: '-'
        else -> '-'
    }

    /** A list node holding [items] (all of a nested list, or one item of a top-level list). */
    fun list(
        list: ListBlock,
        slugger: Slugger,
        depth: Int,
        items: List<ListItem>,
        start: Int,
        continuation: Boolean,
        listLevel: Int = 0,
    ): MdNode = MdNode.ListBlock(
        ordered = list is OrderedList,
        marker = markerOf(list),
        start = start,
        items = items.map { item(it, slugger, depth + 1, listLevel + 1) },
        continuation = continuation,
        level = listLevel,
    )

    private fun item(item: ListItem, slugger: Slugger, depth: Int, listLevel: Int): MdListItem {
        var task: Boolean? = null
        val kids = ArrayList<MdNode>()
        var c = item.firstChild
        while (c != null) {
            if (c is TaskListItemMarker) {
                task = c.isChecked
            } else {
                if (c is Paragraph && c.firstChild is TaskListItemMarker) task = (c.firstChild as TaskListItemMarker).isChecked
                block(c, slugger, depth, listLevel)?.let { kids += it }
            }
            c = c.next
        }
        return MdListItem(task, kids)
    }

    private fun children(parent: Node, slugger: Slugger, depth: Int, listLevel: Int): List<MdNode> {
        val out = ArrayList<MdNode>()
        var c = parent.firstChild
        while (c != null) {
            block(c, slugger, depth, listLevel)?.let { out += it }
            c = c.next
        }
        return out
    }

    private fun table(node: TableBlock): MdNode {
        var header: List<List<MdInline>> = emptyList()
        var aligns: List<TableAlign> = emptyList()
        val rows = ArrayList<List<List<MdInline>>>()
        var section = node.firstChild
        while (section != null) {
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    val cells = ArrayList<List<MdInline>>()
                    val rowAligns = ArrayList<TableAlign>()
                    var cell = row.firstChild
                    while (cell != null) {
                        if (cell is TableCell) {
                            cells += inlines(cell, 0)
                            rowAligns += when (cell.alignment) {
                                TableCell.Alignment.LEFT -> TableAlign.Left
                                TableCell.Alignment.CENTER -> TableAlign.Center
                                TableCell.Alignment.RIGHT -> TableAlign.Right
                                else -> TableAlign.None
                            }
                        }
                        cell = cell.next
                    }
                    if (section is TableHead) {
                        header = cells
                        aligns = rowAligns
                    } else if (section is TableBody) {
                        rows += cells
                    }
                }
                row = row.next
            }
            section = section.next
        }
        val n = header.size
        val normalized = rows.map { r -> if (r.size == n) r else List(n) { i -> r.getOrElse(i) { emptyList() } } }
        return MdNode.Table(aligns, header, normalized)
    }

    // ---- inlines ----

    fun inlines(parent: Node, depth: Int): List<MdInline> {
        val out = ArrayList<MdInline>()
        var c = parent.firstChild
        while (c != null) {
            inline(c, depth)?.let { add(out, it) }
            c = c.next
        }
        return out
    }

    private fun add(out: MutableList<MdInline>, item: MdInline) {
        val last = out.lastOrNull()
        if (item is MdInline.Text && last is MdInline.Text) {
            out[out.size - 1] = MdInline.Text(last.text + item.text)
        } else {
            out += item
        }
    }

    private fun inline(node: Node, depth: Int): MdInline? {
        val nested = depth + 1
        return when (node) {
            is Text -> MdInline.Text(node.literal.orEmpty())
            is Code -> MdInline.Code(node.literal.orEmpty())
            is SoftLineBreak -> MdInline.SoftBreak
            is HardLineBreak -> MdInline.HardBreak
            is HtmlInline -> MdInline.Text(node.literal.orEmpty())
            is TaskListItemMarker -> null
            is Image -> MdInline.Image(node.destination.orEmpty(), plainText(node))
            is Emphasis, is StrongEmphasis, is Strikethrough, is Link -> {
                if (nested > MAX_INLINE_DEPTH) return MdInline.Text(plainText(node))
                val kids = inlines(node, nested)
                when (node) {
                    is Emphasis -> MdInline.Emphasis(kids)
                    is StrongEmphasis -> MdInline.Strong(kids)
                    is Strikethrough -> MdInline.Strike(kids)
                    is Link -> MdInline.Link(node.destination.orEmpty(), node.title, kids)
                    else -> error("unreachable")
                }
            }
            else -> plainText(node).takeIf { it.isNotEmpty() }?.let { MdInline.Text(it) }
        }
    }

    /** The visible text of a subtree, iteratively (no recursion, any depth). */
    fun plainText(root: Node): String {
        val sb = StringBuilder()
        val stack = ArrayDeque<Node>()
        var c = root.lastChild
        while (c != null) {
            stack.addLast(c)
            c = c.previous
        }
        if (root is Text) sb.append(root.literal)
        if (root is Code) sb.append(root.literal)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            when (n) {
                is Text -> sb.append(n.literal)
                is Code -> sb.append(n.literal)
                is FencedCodeBlock -> sb.append(n.literal)
                is IndentedCodeBlock -> sb.append(n.literal)
                is HtmlInline -> sb.append(n.literal)
                is HtmlBlock -> sb.append(n.literal)
                is SoftLineBreak -> sb.append(' ')
                is HardLineBreak -> sb.append('\n')
                else -> {
                    if (n is Paragraph || n is Heading) {
                        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                    }
                    var k = n.lastChild
                    while (k != null) {
                        stack.addLast(k)
                        k = k.previous
                    }
                }
            }
        }
        return sb.toString().trim()
    }

    fun langOf(info: String?): String? = info?.trim()?.split(' ', '\t', '{')?.firstOrNull()?.takeIf { it.isNotEmpty() }
}
