package com.assistant.core.markdown.internal

import com.assistant.core.markdown.MAX_BLOCK_DEPTH
import com.assistant.core.markdown.MAX_INLINE_DEPTH
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Node
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import java.util.concurrent.atomic.AtomicLong

/**
 * The one configured commonmark-java parser (thread-safe, shared). GFM tables, strikethrough
 * (single or double tilde, as remark-gfm on the web), task list items and autolinks. Block source
 * spans are on: the streaming document uses them to find where each top-level block starts.
 *
 * YAML front matter is deliberately NOT enabled here: every streaming segment is parsed as a
 * fresh document, so a later `---` block would be swallowed as front matter. Memory files split
 * it off first ([com.assistant.core.markdown.Frontmatter]).
 *
 * Nesting limits: commonmark stops opening containers past [MAX_OPEN_BLOCK_PARSERS] (the line
 * becomes text), which bounds its own recursive visitors on hostile input, and inline nesting
 * stops at [MAX_INLINE_DEPTH]. The block limit is far above [MAX_BLOCK_DEPTH] on purpose: commonmark
 * counts still-open containers of the *previous* block against it, so a low limit would make a
 * segment parse depend on what preceded it and break the streaming split. The renderable depth
 * cap is the converter's ([MAX_BLOCK_DEPTH], [MAX_INLINE_DEPTH]).
 */
internal object MdParser {
    const val MAX_OPEN_BLOCK_PARSERS = 64

    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListItemsExtension.create(),
                AutolinkExtension.create(),
            ),
        )
        .includeSourceSpans(IncludeSourceSpans.BLOCKS)
        .maxOpenBlockParsers(MAX_OPEN_BLOCK_PARSERS)
        .maxInlineNesting(MAX_INLINE_DEPTH)
        .build()

    fun parse(text: String): Node {
        ParseStats.record(text.length)
        return parser.parse(text)
    }
}

/**
 * Instrumentation for the work-bound proofs (spec 14 §3.2 `TailWorkBoundTest`): how many times
 * commonmark ran and over how many characters. Two atomic adds per parse; always on.
 */
object ParseStats {
    private val parses = AtomicLong()
    private val chars = AtomicLong()

    val parseCount: Long get() = parses.get()
    val parsedChars: Long get() = chars.get()

    internal fun record(length: Int) {
        parses.incrementAndGet()
        chars.addAndGet(length.toLong())
    }

    fun reset() {
        parses.set(0)
        chars.set(0)
    }
}
