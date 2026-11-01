package com.assistant.archie.tv.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.assistant.core.markdown.InternalLinks
import com.assistant.core.markdown.MdInline
import com.assistant.core.markdown.MdNode
import com.assistant.core.markdown.autoLinkPaths
import com.assistant.core.markdown.ui.MarkdownBlock
import com.assistant.core.markdown.ui.MarkdownStyle

/*
 * Markdown for the TV reading model (spec 15 §3.5 Conversation / MemoryDoc): every top-level node is
 * one focusable block; the links inside a block are reachable on their own (Left/Right pick one,
 * OK opens it), so the renderer needs to know each link's place in the block. [TvMarkdown.blocks]
 * flattens a node into segments with link indices (pure, JVM-tested); [TvMarkdownBlock] draws them
 * with the selected link highlighted. Code blocks, tables and rules reuse :core:markdown's views.
 */

/** One drawable piece of a block. */
@Immutable
sealed interface TvSeg {
    /** A run of inline text: a paragraph, a heading, or one list item's paragraph (with its [marker]). */
    data class Text(
        val inlines: List<MdInline>,
        val heading: Int = 0,
        val quote: Boolean = false,
        val indent: Int = 0,
        val marker: String? = null,
        /** Index (in the block's [TvBlock.links]) of this run's first link. */
        val linkStart: Int = 0,
    ) : TvSeg

    data class Code(val lang: String?, val code: String, val indent: Int = 0) : TvSeg

    /** Rendered by :core:markdown as is (tables, rules, streaming tails); its links are not selectable. */
    data class Raw(val node: MdNode, val indent: Int = 0) : TvSeg
}

/** One focusable block: its segments, its links in reading order, the heading anchor (`#x` links). */
@Immutable
data class TvBlock(val node: MdNode, val segs: List<TvSeg>, val links: List<String>, val anchor: String? = null)

object TvMarkdown {
    private val BULLETS = listOf("•", "◦", "▪")

    /** [nodes] → blocks; printed paths become internal links first (LNK-5, [ctx] = the server origin). */
    fun blocks(nodes: List<MdNode>, ctx: InternalLinks.Context = InternalLinks.Context()): List<TvBlock> = nodes.map { block(it, ctx) }

    fun block(node: MdNode, ctx: InternalLinks.Context = InternalLinks.Context()): TvBlock {
        val segs = ArrayList<TvSeg>()
        val links = ArrayList<String>()
        fun text(inlines: List<MdInline>, heading: Int = 0, quote: Boolean = false, indent: Int = 0, marker: String? = null) {
            val linked = autoLinkPaths(inlines, ctx)
            segs += TvSeg.Text(linked, heading, quote, indent, marker, links.size)
            collectLinks(linked, links)
        }
        fun walk(n: MdNode, indent: Int, quote: Boolean) {
            when (n) {
                is MdNode.Paragraph -> text(n.inlines, quote = quote, indent = indent)
                is MdNode.Heading -> text(n.inlines, heading = n.level, quote = quote, indent = indent)
                is MdNode.ListBlock -> n.items.forEachIndexed { i, item ->
                    val marker = when {
                        item.task == true -> "☑"
                        item.task == false -> "☐"
                        n.ordered -> "${n.start + i}${n.marker}"
                        else -> BULLETS[n.level.coerceIn(0, BULLETS.lastIndex)]
                    }
                    item.children.forEachIndexed { j, child ->
                        if (j == 0 && child is MdNode.Paragraph) text(child.inlines, quote = quote, indent = indent, marker = marker)
                        else if (j == 0) {
                            segs += TvSeg.Text(listOf(MdInline.Text("")), quote = quote, indent = indent, marker = marker, linkStart = links.size)
                            walk(child, indent + 1, quote)
                        } else walk(child, indent + 1, quote)
                    }
                    if (item.children.isEmpty()) segs += TvSeg.Text(emptyList(), quote = quote, indent = indent, marker = marker, linkStart = links.size)
                }
                is MdNode.Quote -> n.children.forEach { walk(it, indent + 1, quote = true) }
                is MdNode.CodeBlock -> segs += TvSeg.Code(n.lang, n.code, indent)
                is MdNode.CodeTail -> segs += TvSeg.Code(n.lang, n.text, indent)
                else -> segs += TvSeg.Raw(n, indent)
            }
        }
        walk(node, 0, false)
        return TvBlock(node, segs, links, (node as? MdNode.Heading)?.anchor)
    }

    /** Link targets in reading order (an image counts: its alt text links to the picture). */
    fun collectLinks(inlines: List<MdInline>, out: MutableList<String>) {
        for (i in inlines) when (i) {
            is MdInline.Link -> out += i.href
            is MdInline.Image -> out += i.src
            is MdInline.Emphasis -> collectLinks(i.children, out)
            is MdInline.Strong -> collectLinks(i.children, out)
            is MdInline.Strike -> collectLinks(i.children, out)
            else -> Unit
        }
    }

    /** Plain text of a block (previews, tests). */
    fun plain(block: TvBlock): String = block.segs.joinToString("\n") { s ->
        when (s) {
            is TvSeg.Text -> (s.marker?.let { "$it " } ?: "") + plainInline(s.inlines)
            is TvSeg.Code -> s.code
            is TvSeg.Raw -> ""
        }
    }

    private fun plainInline(inlines: List<MdInline>): String = buildString {
        for (i in inlines) when (i) {
            is MdInline.Text -> append(i.text)
            is MdInline.Code -> append(i.code)
            is MdInline.Emphasis -> append(plainInline(i.children))
            is MdInline.Strong -> append(plainInline(i.children))
            is MdInline.Strike -> append(plainInline(i.children))
            is MdInline.Link -> append(plainInline(i.children))
            is MdInline.Image -> append(i.alt)
            MdInline.SoftBreak -> append(' ')
            MdInline.HardBreak -> append('\n')
        }
    }
}

/** Colors of the inline renderer. */
@Immutable
data class TvInlineColors(
    val link: Color,
    val selectedLink: Color,
    val selectedLinkBackground: Color,
    val codeBackground: Color,
)

/**
 * Inline markdown → [AnnotatedString]. Link number [selected] (counted from [linkStart] in reading
 * order, as [TvMarkdown.collectLinks]) is drawn as the focused link.
 */
fun buildTvInline(
    inlines: List<MdInline>,
    style: MarkdownStyle,
    colors: TvInlineColors,
    linkStart: Int = 0,
    selected: Int = -1,
): AnnotatedString = buildAnnotatedString {
    var n = linkStart
    fun emit(list: List<MdInline>) {
        for (i in list) when (i) {
            is MdInline.Text -> append(i.text)
            is MdInline.Code -> withStyle(SpanStyle(fontFamily = style.code.fontFamily, background = colors.codeBackground)) { append(" ${i.code} ") }
            is MdInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { emit(i.children) }
            is MdInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.W600)) { emit(i.children) }
            is MdInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { emit(i.children) }
            is MdInline.Link, is MdInline.Image -> {
                val mine = n++
                val span = if (mine == selected) {
                    SpanStyle(color = colors.selectedLink, background = colors.selectedLinkBackground, textDecoration = TextDecoration.Underline)
                } else {
                    SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)
                }
                withStyle(span) {
                    if (i is MdInline.Link) emit(i.children) else append((i as MdInline.Image).alt.ifEmpty { "image" })
                }
            }
            MdInline.SoftBreak -> append(' ')
            MdInline.HardBreak -> append('\n')
        }
    }
    emit(inlines)
}

/** Draws one [TvBlock]; [selectedLink] is the focused link (−1 = none). */
@Composable
fun TvMarkdownBlock(
    block: TvBlock,
    style: MarkdownStyle,
    colors: TvInlineColors,
    modifier: Modifier = Modifier,
    selectedLink: Int = -1,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(style.itemSpacing)) {
        for (seg in block.segs) {
            when (seg) {
                is TvSeg.Text -> {
                    val textStyle = if (seg.heading > 0) style.heading(seg.heading) else style.body
                    val color = if (seg.quote || seg.heading == 6) style.mutedColor else style.textColor
                    val text = remember(seg, selectedLink, colors) { buildTvInline(seg.inlines, style, colors, seg.linkStart, selectedLink) }
                    Row(Modifier.padding(start = style.listIndent * seg.indent)) {
                        if (seg.marker != null) {
                            Box(Modifier.widthIn(min = style.listIndent)) { Text(seg.marker, style = style.body, color = color) }
                        }
                        Text(text, style = textStyle, color = color)
                    }
                }
                is TvSeg.Code -> TvCodeBlock(seg, style)
                is TvSeg.Raw -> MarkdownBlock(seg.node, Modifier.padding(start = style.listIndent * seg.indent), style)
            }
        }
    }
}

/**
 * A code block soft-wrapped in full (nothing to scroll sideways, no buttons to land on: the block is
 * one focus stop; a tall one scrolls inside the reading list like any other block).
 */
@Composable
private fun TvCodeBlock(seg: TvSeg.Code, style: MarkdownStyle) {
    Column(
        Modifier
            .padding(start = style.listIndent * seg.indent)
            .fillMaxWidth()
            .background(style.codeSurface, RoundedCornerShape(style.codeCorner))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!seg.lang.isNullOrBlank()) Text(seg.lang, style = style.codeHeaderText, color = style.mutedColor)
        Text(seg.code.trimEnd('\n'), style = style.code, color = style.codeColor, softWrap = true)
    }
}

/** :core:markdown's style at TV sizes (TvTheme maps the M3 type to the TV scale); code never collapses. */
@Composable
fun tvMarkdownStyle(): MarkdownStyle = MarkdownStyle.fromTheme().copy(codeMaxHeight = 100_000.dp, listIndent = 28.dp)
