package com.assistant.core.markdown

import com.assistant.core.markdown.internal.MdConverter
import com.assistant.core.markdown.internal.MdParser
import java.io.File
import kotlin.random.Random

/**
 * The 40-message corpus (spec 14 §3.2 `StreamingEquivalenceTest`): the web's markdown fixtures,
 * assistant-written sections of this repo's refactor docs, and synthetic edge cases
 * (`src/test/resources/corpus/`). No document defines a reference-style link: those only resolve
 * inside their own parse (documented limitation, spec 14 §3.2 item 6).
 */
object Corpus {
    val dir: File = File(requireNotNull(Corpus::class.java.classLoader!!.getResource("corpus")).toURI())

    val docs: List<Pair<String, String>> by lazy {
        dir.listFiles()!!.filter { it.extension == "md" }.sortedBy { it.name }.map { it.name to it.readText() }
    }

    fun text(name: String): String = docs.first { it.first.contains(name) }.second
}

/** Streams [text] in random-sized deltas (1..[maxDelta]); calls [onSnapshot] after each one. */
fun stream(
    text: String,
    random: Random,
    maxDelta: Int = 40,
    onSnapshot: (MdSnapshot) -> Unit = {},
): MarkdownDocument {
    val doc = MarkdownDocument()
    var i = 0
    while (i < text.length) {
        val n = 1 + random.nextInt(maxDelta)
        val end = minOf(text.length, i + n)
        onSnapshot(doc.append(text.substring(i, end)))
        i = end
    }
    return doc
}

/**
 * A meaning-level outline (one line per paragraph, heading, list item, …) that is the same
 * whether a list is one node or one node per item. Used to compare the block-split document
 * against a single whole-document commonmark parse.
 */
object Outline {
    fun of(nodes: List<MdNode>): List<String> = buildList { nodes.forEach { add(this, it, "") } }

    /** The outline of one commonmark parse of the whole text (no splitting at all). */
    fun ofWholeParse(text: String): List<String> {
        val doc = MdParser.parse(text)
        val slugger = Slugger()
        val nodes = ArrayList<MdNode>()
        var b = doc.firstChild
        while (b != null) {
            MdConverter.block(b, MdConverter.Slugger { slugger.slug(it) })?.let { nodes += it }
            b = b.next
        }
        return of(nodes)
    }

    private fun add(out: MutableList<String>, n: MdNode, indent: String) {
        when (n) {
            is MdNode.Paragraph -> out += "${indent}P ${n.inlines}"
            is MdNode.Heading -> out += "${indent}H${n.level}#${n.anchor} ${n.inlines}"
            is MdNode.ListBlock -> n.items.forEachIndexed { i, item ->
                out += "${indent}LI ${if (n.ordered) "${n.start + i}${n.marker}" else n.marker} task=${item.task}"
                item.children.forEach { add(out, it, "$indent  ") }
            }
            is MdNode.Quote -> {
                out += "${indent}Q"
                n.children.forEach { add(out, it, "$indent  ") }
            }
            is MdNode.CodeBlock -> out += "${indent}CODE ${n.lang} ${n.code}"
            is MdNode.Table -> out += "${indent}T ${n.aligns} ${n.header} ${n.rows}"
            MdNode.Rule -> out += "${indent}HR"
            is MdNode.CodeTail -> out += "${indent}CODETAIL ${n.text}"
            is MdNode.TableTail -> out += "${indent}TABLETAIL ${n.raw}"
        }
    }
}

/** Deepest inline nesting in [nodes]. */
fun maxInlineDepth(nodes: List<MdNode>): Int {
    fun inl(list: List<MdInline>): Int = list.maxOfOrNull {
        when (it) {
            is MdInline.Emphasis -> 1 + inl(it.children)
            is MdInline.Strong -> 1 + inl(it.children)
            is MdInline.Strike -> 1 + inl(it.children)
            is MdInline.Link -> 1 + inl(it.children)
            else -> 0
        }
    } ?: 0
    fun blk(n: MdNode): Int = when (n) {
        is MdNode.Paragraph -> inl(n.inlines)
        is MdNode.Heading -> inl(n.inlines)
        is MdNode.ListBlock -> n.items.flatMap { it.children }.maxOfOrNull { blk(it) } ?: 0
        is MdNode.Quote -> n.children.maxOfOrNull { blk(it) } ?: 0
        else -> 0
    }
    return nodes.maxOfOrNull { blk(it) } ?: 0
}

/** Deepest block nesting (lists and quotes) in [nodes]; a top-level paragraph is 0. */
fun maxBlockDepth(nodes: List<MdNode>): Int {
    fun blk(n: MdNode): Int = when (n) {
        is MdNode.ListBlock -> 1 + (n.items.flatMap { it.children }.maxOfOrNull { blk(it) } ?: 0)
        is MdNode.Quote -> 1 + (n.children.maxOfOrNull { blk(it) } ?: 0)
        else -> 0
    }
    return nodes.maxOfOrNull { blk(it) } ?: 0
}
