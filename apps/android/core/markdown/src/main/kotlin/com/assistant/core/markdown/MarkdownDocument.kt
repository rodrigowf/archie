package com.assistant.core.markdown

import androidx.compose.runtime.Immutable
import com.assistant.core.markdown.internal.MdConverter
import com.assistant.core.markdown.internal.MdParser
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.ThematicBreak

/**
 * Incremental markdown for streaming assistant text (spec 14 §3.2).
 *
 * The text is append-only. [stableEnd] is the offset where every top-level block before it is
 * **closed**: its nodes are committed to an immutable list and never parsed again. Everything
 * after it (the tail) is the only text processed per [append]:
 *
 * - Normal tail: one commonmark parse of `text[stableEnd..]`. Its top-level blocks are walked in
 *   order; a block is committed when it is closed, i.e. when
 *   (a) the next block starts on a complete line (block structure is decided line by line, so a
 *       block that a complete later line has closed can never change again), or
 *   (b) it closes on its own: an ATX/setext heading or thematic break whose line is complete, a
 *       fence whose closing line is complete, or a paragraph/table followed by a complete blank
 *       line.
 *   Decisions never rest on the unfinished last line (a lone `#` could become `#hello`).
 *   A top-level list is committed **per item** (an item is closed once the next item starts on a
 *   complete line), so a long list streams in O(item). Each item becomes its own [MdNode.ListBlock]
 *   with its real number and a `continuation` flag, which renders like one list.
 * - Open fence: once a top-level fence's opening line is complete and no closing fence has been
 *   seen, commonmark is not called at all. New complete lines are scanned only for a closing
 *   fence, and the content is cut into bounded [MdNode.CodeTail] chunks (≤ [CHUNK_LINES] lines /
 *   [CHUNK_CHARS] chars, hard cut at [HARD_CHARS] inside one line). Only the last chunk changes
 *   per delta, so a 5,000-line unterminated fence costs O(chunk) per delta, renders soft-wrapped
 *   with no horizontal scroll, and never becomes one giant RenderNode (commit 31c2fbf).
 *
 * After a closed block, the parser state at the next line start is a fresh document, so parsing
 * from [stableEnd] gives the same blocks as parsing the whole text. The one exception (documented
 * in spec 14 §3.2 item 6): a reference-style link definition only resolves inside the parse that
 * contains it.
 *
 * Not thread-safe: one writer. [snapshot] is immutable and safe to hand to the UI thread.
 */
class MarkdownDocument {
    private val text = StringBuilder()
    private var stableEnd = 0
    private var stable: PersistentList<MdNode> = persistentListOf()
    private val slugger = Slugger()
    private var pendingList: ListContinuation? = null
    private var fence: OpenFence? = null
    private var finished = false

    /** The latest state. A new instance after every [append] that changed something. */
    var snapshot: MdSnapshot = MdSnapshot.EMPTY_STREAMING
        private set

    val length: Int get() = text.length

    /** Appends a streaming delta and returns the new snapshot. */
    fun append(delta: String): MdSnapshot {
        check(!finished) { "append after finish" }
        if (delta.isEmpty()) return snapshot
        text.append(delta)
        val tail = advance(final = false)
        snapshot = MdSnapshot(stable, tail, streaming = true)
        return snapshot
    }

    /** The message completed: parses the tail one last time and freezes the document. */
    fun finish(): MdSnapshot {
        if (!finished) {
            finished = true
            advance(final = true)
            snapshot = MdSnapshot(stable, persistentListOf(), streaming = false)
        }
        return snapshot
    }

    // ---- engine ----

    private fun advance(final: Boolean): ImmutableList<MdNode> {
        while (true) {
            val f = fence
            if (f != null) {
                val closeEnd = f.scan(text)
                if (closeEnd < 0 && !final) return f.tail(text)
                fence = null
                val end = if (closeEnd >= 0) closeEnd else text.length
                val doc = MdParser.parse(text.substring(stableEnd, end))
                commitAll(doc)
                stableEnd = end
                if (end == text.length) return persistentListOf()
                continue
            }
            return processRemainder(final)
        }
    }

    private fun commitAll(doc: Node) {
        var b = doc.firstChild
        var first = true
        while (b != null) {
            commitBlock(b, first)
            first = false
            b = b.next
        }
    }

    private fun processRemainder(final: Boolean): ImmutableList<MdNode> {
        val remStart = stableEnd
        val rem = text.substring(remStart)
        if (rem.isBlank()) {
            if (final) stableEnd = text.length else stableEnd += rem.lastIndexOf('\n') + 1
            return persistentListOf()
        }
        val doc = MdParser.parse(rem)
        val blocks = ArrayList<Node>()
        run {
            var b = doc.firstChild
            while (b != null) {
                blocks += b
                b = b.next
            }
        }
        if (final) {
            blocks.forEachIndexed { i, b -> commitBlock(b, i == 0) }
            stableEnd = text.length
            return persistentListOf()
        }
        val completeEnd = rem.lastIndexOf('\n') + 1
        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            val next = blocks.getOrNull(i + 1)
            val nextStart = next?.let { lineStart(rem, it) }
            val closed = (nextStart != null && nextStart < completeEnd) || closedOnItsOwn(rem, b, completeEnd)
            if (!closed || lineStart(rem, b) == null) break
            commitBlock(b, i == 0)
            i++
        }
        if (i == blocks.size) {
            stableEnd = remStart + completeEnd
            return persistentListOf()
        }

        // blocks[i] is the first open block: commit the closed items of a top-level list.
        val open = blocks[i]
        var tailFromItem = 0
        if (open is ListBlock) {
            val items = MdConverter.itemsOf(open)
            val applyPending = i == 0
            var k = 0
            while (k < items.size - 1) {
                val ns = lineStart(rem, items[k + 1]) ?: break
                if (ns >= completeEnd) break
                k++
            }
            if (k > 0) commitListItems(open, items, 0, k, applyPending)
            tailFromItem = k
        } else if (i == 0) {
            pendingList = null
        }
        val openStart = if (open is ListBlock && tailFromItem > 0) {
            lineStart(rem, MdConverter.itemsOf(open)[tailFromItem])
        } else {
            lineStart(rem, open)
        } ?: 0
        stableEnd = remStart + openStart

        // An open top-level fence whose opening line is complete → fence mode (no more parsing).
        if (open is FencedCodeBlock && open.closingFenceLength == null && i == blocks.size - 1) {
            val nl = rem.indexOf('\n', openStart)
            if (nl >= 0) {
                val contentStart = remStart + nl + 1
                val f = OpenFence(
                    fenceChar = open.fenceCharacter?.firstOrNull() ?: '`',
                    fenceLength = open.openingFenceLength ?: 3,
                    indent = open.fenceIndent,
                    lang = MdConverter.langOf(open.info),
                    contentStart = contentStart,
                )
                // commonmark just said there is no closing line yet; the scan only chunks the
                // content. If the scanner disagrees, stay conservative and keep parsing.
                if (f.scan(text) < 0) {
                    fence = f
                    return f.tail(text)
                }
            }
        }
        return tailNodes(rem, blocks, i, tailFromItem)
    }

    private fun tailNodes(rem: String, blocks: List<Node>, from: Int, fromItem: Int): ImmutableList<MdNode> {
        val peek = MdConverter.Slugger { slugger.peek(it) }
        val out = ArrayList<MdNode>(2)
        for (j in from until blocks.size) {
            val b = blocks[j]
            when {
                b is ListBlock && j == from -> {
                    val items = MdConverter.itemsOf(b)
                    // After a partial commit, pendingList numbers item [fromItem]; otherwise it
                    // only applies when this list continues one split by an earlier parse.
                    val pending = pendingList.takeIf { from == 0 || fromItem > 0 }
                    val base = pending?.nextNumber?.minus(fromItem) ?: MdConverter.startOf(b)
                    for (k in fromItem until items.size) {
                        out += MdConverter.list(b, peek, 0, listOf(items[k]), base + k, k > 0 || pending != null)
                    }
                }
                b is FencedCodeBlock -> out += MdNode.CodeTail(MdConverter.langOf(b.info), b.literal.orEmpty().removeSuffix("\n"), 0, open = true)
                b is TableBlock -> {
                    val s = lineStart(rem, b) ?: 0
                    out += MdNode.TableTail(rem.substring(s).trimEnd())
                }
                else -> MdConverter.block(b, peek)?.let { out += it }
            }
        }
        return out.toImmutableList()
    }

    private fun commitBlock(b: Node, firstInParse: Boolean) {
        if (b is ListBlock) {
            val items = MdConverter.itemsOf(b)
            commitListItems(b, items, 0, items.size, applyPending = firstInParse)
            pendingList = null
            return
        }
        pendingList = null
        MdConverter.block(b, MdConverter.Slugger { slugger.slug(it) })?.let { stable = stable.add(it) }
    }

    private fun commitListItems(list: ListBlock, items: List<ListItem>, from: Int, to: Int, applyPending: Boolean) {
        val pending = pendingList.takeIf { applyPending }
        val base = pending?.nextNumber ?: MdConverter.startOf(list)
        val slug = MdConverter.Slugger { slugger.slug(it) }
        for (k in from until to) {
            stable = stable.add(MdConverter.list(list, slug, 0, listOf(items[k]), base + k, k > 0 || pending != null))
        }
        pendingList = ListContinuation(base + to)
    }

    private class ListContinuation(val nextNumber: Int)

    companion object {
        const val CHUNK_LINES: Int = 40
        const val CHUNK_CHARS: Int = 2048
        const val HARD_CHARS: Int = 4096

        /** One-shot parse of a complete text (history, memory documents): a single commonmark run. */
        fun parse(markdown: String): ImmutableList<MdNode> {
            val d = MarkdownDocument()
            d.text.append(markdown)
            d.finished = true
            d.advance(final = true)
            return d.stable
        }

        /** Offset of the start of the line where [node] begins, or null when it has no span. */
        private fun lineStart(rem: String, node: Node): Int? {
            val span = node.sourceSpans.firstOrNull() ?: return null
            return rem.lastIndexOf('\n', span.inputIndex - 1) + 1
        }

        private fun blockEnd(node: Node): Int? {
            val span = node.sourceSpans.lastOrNull() ?: return null
            return span.inputIndex + span.length
        }

        private fun closedOnItsOwn(rem: String, b: Node, completeEnd: Int): Boolean {
            val end = blockEnd(b) ?: return false
            return when (b) {
                is Heading, is ThematicBreak -> end < completeEnd
                is FencedCodeBlock -> b.closingFenceLength != null && end < completeEnd
                is Paragraph, is TableBlock -> {
                    val p = rem.indexOf('\n', end)
                    if (p < 0) return false
                    val q = rem.indexOf('\n', p + 1)
                    q >= 0 && rem.substring(p + 1, q).isBlank()
                }
                else -> false
            }
        }
    }
}

/** An open top-level fence while streaming (see [MarkdownDocument]). */
private class OpenFence(
    val fenceChar: Char,
    val fenceLength: Int,
    val indent: Int,
    val lang: String?,
    val contentStart: Int,
) {
    /** Start of the next line not yet checked for a closing fence. */
    private var scanPos = contentStart
    private var chunkStart = contentStart
    private var chunkLines = 0
    private var chunkAtLineStart = true
    private var chunks: PersistentList<MdNode> = persistentListOf()

    /** Scans new complete lines. Returns the end offset of the closing fence line, or -1. */
    fun scan(text: StringBuilder): Int {
        while (true) {
            val nl = text.indexOf("\n", scanPos)
            if (nl < 0) break
            if (isClosing(text, scanPos, nl)) return nl + 1
            hardSplit(text, nl)
            chunkLines++
            scanPos = nl + 1
            if (scanPos - chunkStart >= MarkdownDocument.CHUNK_CHARS || chunkLines >= MarkdownDocument.CHUNK_LINES) {
                cut(text, scanPos, atLineStart = true)
            }
        }
        // The unfinished line: cut it only when it can no longer be a closing fence.
        if (text.length - chunkStart > MarkdownDocument.HARD_CHARS && !couldClose(text, scanPos, text.length)) {
            hardSplit(text, text.length)
        }
        return -1
    }

    fun tail(text: StringBuilder): ImmutableList<MdNode> {
        val current = MdNode.CodeTail(lang, content(text, chunkStart, text.length, chunkAtLineStart), chunks.size, open = true)
        return chunks.add(current)
    }

    private fun hardSplit(text: StringBuilder, lineEnd: Int) {
        while (lineEnd - chunkStart > MarkdownDocument.HARD_CHARS) {
            cut(text, chunkStart + MarkdownDocument.HARD_CHARS, atLineStart = false)
        }
    }

    private fun cut(text: StringBuilder, at: Int, atLineStart: Boolean) {
        chunks = chunks.add(MdNode.CodeTail(lang, content(text, chunkStart, at, chunkAtLineStart), chunks.size, open = false))
        chunkStart = at
        chunkLines = 0
        chunkAtLineStart = atLineStart
    }

    /** The code text of `[from, to)`: up to [indent] leading spaces removed per line, no final newline. */
    private fun content(text: StringBuilder, from: Int, to: Int, fromLineStart: Boolean): String {
        var end = to
        if (end > from && text[end - 1] == '\n') end--
        if (indent == 0) return text.substring(from, end)
        val sb = StringBuilder(end - from)
        var i = from
        var lineStart = fromLineStart
        while (i < end) {
            if (lineStart) {
                var n = 0
                while (n < indent && i < end && text[i] == ' ') {
                    i++
                    n++
                }
                lineStart = false
                continue
            }
            val c = text[i]
            sb.append(c)
            if (c == '\n') lineStart = true
            i++
        }
        return sb.toString()
    }

    /** CommonMark closing fence: ≤ 3 spaces, ≥ [fenceLength] × [fenceChar], then only spaces/tabs. */
    private fun isClosing(text: StringBuilder, from: Int, to: Int): Boolean {
        var i = from
        var spaces = 0
        while (i < to && text[i] == ' ') {
            i++
            spaces++
        }
        if (spaces > 3) return false
        var n = 0
        while (i < to && text[i] == fenceChar) {
            i++
            n++
        }
        if (n < fenceLength) return false
        while (i < to) {
            val c = text[i]
            if (c != ' ' && c != '\t' && c != '\r') return false
            i++
        }
        return true
    }

    private fun couldClose(text: StringBuilder, from: Int, to: Int): Boolean {
        for (i in from until to) {
            val c = text[i]
            if (c != ' ' && c != '\t' && c != fenceChar) return false
        }
        return true
    }
}

/**
 * An immutable view of a [MarkdownDocument]: committed nodes then tail nodes. Index `i` is the
 * node's stable position (`md:$i` in the item key, spec 14 §3.1): a tail node keeps its index
 * when it is committed, so its lazy item keeps its key and scroll position.
 */
@Immutable
class MdSnapshot(
    val stable: ImmutableList<MdNode>,
    val tail: ImmutableList<MdNode>,
    val streaming: Boolean,
) {
    val size: Int get() = stable.size + tail.size

    operator fun get(index: Int): MdNode = if (index < stable.size) stable[index] else tail[index - stable.size]

    fun isTail(index: Int): Boolean = index >= stable.size

    /** LazyColumn content type: the node's own, or `md-tail` for the unfinished tail. */
    fun contentType(index: Int): String = if (isTail(index)) MdNode.CONTENT_TYPE_TAIL else get(index).contentType

    val nodes: List<MdNode> get() = stable + tail

    companion object {
        val EMPTY_STREAMING = MdSnapshot(persistentListOf(), persistentListOf(), streaming = true)

        /** A finished document (history, memory). */
        fun of(nodes: ImmutableList<MdNode>) = MdSnapshot(nodes, persistentListOf(), streaming = false)
    }
}
