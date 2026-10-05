package com.assistant.core.markdown

import androidx.compose.runtime.Immutable

/*
 * The renderable markdown model (spec 14 §3.1–§3.3). Every top-level node is one lazy list item
 * (`MdBlock` in :feature:chat), so a streaming delta invalidates exactly one item.
 *
 * Nodes are immutable data classes. A committed node is never rebuilt: the same instance stays in
 * the document, so Compose skips its item on every later delta (equality short-circuits on
 * identity). Inline depth is capped at [MAX_INLINE_DEPTH] and block nesting at [MAX_BLOCK_DEPTH]
 * when the commonmark AST is converted (MdConverter): deeper content is flattened to plain text,
 * which bounds both the composable tree and the RenderNode tree (the libhwui stack overflow of
 * commit 31c2fbf).
 */

/** Port of the old renderer's `MAX_INLINE_DEPTH` (old/ui/components/markdown/MarkdownText.kt). */
const val MAX_INLINE_DEPTH: Int = 8

/** Nested lists / quotes deeper than this are flattened to plain text. */
const val MAX_BLOCK_DEPTH: Int = 8

@Immutable
sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Code(val code: String) : MdInline
    data class Emphasis(val children: List<MdInline>) : MdInline
    data class Strong(val children: List<MdInline>) : MdInline
    data class Strike(val children: List<MdInline>) : MdInline
    data class Link(val href: String, val title: String?, val children: List<MdInline>) : MdInline

    /** Images are not rendered in v1: the alt text shows as a link to [src] (spec 14 §3.3). */
    data class Image(val src: String, val alt: String) : MdInline
    data object SoftBreak : MdInline
    data object HardBreak : MdInline
}

enum class TableAlign { None, Left, Center, Right }

@Immutable
data class MdListItem(
    /** `true`/`false` for a GFM task item (`- [x]`), `null` for a plain item. */
    val task: Boolean?,
    val children: List<MdNode>,
)

@Immutable
sealed interface MdNode {
    /** LazyColumn `contentType` (spec 14 §3.1). Tail items use [CONTENT_TYPE_TAIL] instead. */
    val contentType: String

    data class Paragraph(val inlines: List<MdInline>) : MdNode {
        override val contentType: String get() = "md-paragraph"
    }

    /** [anchor] is the GitHub slug, de-duplicated within the document (`#x`, `#x-1`). */
    data class Heading(val level: Int, val inlines: List<MdInline>, val anchor: String) : MdNode {
        override val contentType: String get() = "md-heading"
    }

    /**
     * A list. At the top level of a document every item is its own node (one lazy item per list
     * item, so long lists stream in O(item)); [continuation] is true for every item after the
     * first of the same source list, and [start] is that item's own number. Nested lists keep
     * all their items in one node.
     */
    data class ListBlock(
        val ordered: Boolean,
        /** Bullet char (`-`, `*`, `+`) or ordered delimiter (`.`, `)`). */
        val marker: Char,
        val start: Int,
        val items: List<MdListItem>,
        val continuation: Boolean = false,
        /** Nesting level (0 = top), picks the bullet glyph. */
        val level: Int = 0,
    ) : MdNode {
        override val contentType: String get() = "md-list"
    }

    data class Quote(val children: List<MdNode>) : MdNode {
        override val contentType: String get() = "md-quote"
    }

    /** A closed code block. [lang] is `null` for a fence without info string (still a block, F-03). */
    data class CodeBlock(val lang: String?, val code: String) : MdNode {
        override val contentType: String get() = "md-code"
    }

    /**
     * One chunk of a fence that is still open while streaming: plain monospace, soft-wrapped, never
     * highlighted, never horizontally scrolled (spec 14 §3.2 step 3). A long open fence is split
     * into chunks of bounded size, each its own lazy item; only the last one changes per delta.
     */
    data class CodeTail(val lang: String?, val text: String, val chunk: Int, val open: Boolean) : MdNode {
        override val contentType: String get() = CONTENT_TYPE_TAIL
    }

    data class Table(
        val aligns: List<TableAlign>,
        val header: List<List<MdInline>>,
        val rows: List<List<List<MdInline>>>,
    ) : MdNode {
        override val contentType: String get() = "md-table"
    }

    /** A table that is still streaming: its raw rows as plain monospace (spec 14 §3.2 step 3). */
    data class TableTail(val raw: String) : MdNode {
        override val contentType: String get() = CONTENT_TYPE_TAIL
    }

    data object Rule : MdNode {
        override val contentType: String get() = "md-rule"
    }

    companion object {
        const val CONTENT_TYPE_TAIL: String = "md-tail"
    }
}
