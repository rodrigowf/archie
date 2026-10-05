package com.assistant.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 5 regression cases of the old `android/app/src/test/.../ui/markdown/MarkdownParserTest.kt`
 * (commit 31c2fbf, the libhwui RenderThread stack overflow), ported to the new engine
 * (spec 14 §3.2). The prefix/tail equivalence cases live in [StreamingEquivalenceTest].
 */
class MarkdownParserRegressionTest {

    private fun paragraphInlines(md: String): List<MdInline> =
        (MarkdownDocument.parse(md).single() as MdNode.Paragraph).inlines

    /** Old case 1: 200 levels of nested emphasis can't blow the stack, and the model stays ≤ 8 deep. */
    @Test
    fun deeplyNestedEmphasisDoesNotOverflowTheStack() {
        val deep = "*".repeat(200) + "x" + "*".repeat(200)
        val nodes = MarkdownDocument.parse(deep)
        assertTrue(nodes.isNotEmpty())
        assertTrue("inline depth ${maxInlineDepth(nodes)}", maxInlineDepth(nodes) <= MAX_INLINE_DEPTH)

        // Same for links and strike nested far past the cap, and for streaming.
        val links = "[".repeat(300) + "a" + "](u)".repeat(300)
        assertTrue(maxInlineDepth(MarkdownDocument.parse(links)) <= MAX_INLINE_DEPTH)
        val doc = MarkdownDocument()
        deep.chunked(7).forEach { doc.append(it) }
        assertTrue(maxInlineDepth(doc.finish().stable) <= MAX_INLINE_DEPTH)
    }

    /** Old case 2: mid-stream fragments (unbalanced markers, em-dashes, open link) never throw. */
    @Test
    fun malformedStreamingMarkdownParsesWithoutThrowing() {
        listOf(
            "All the lamps in your office—about 70",
            "**bold that never clos",
            "here is `code and _italic and [link](",
            "***",
            "____",
            "text — more — text",
            "| a | b",
            "| a | b |\n|--",
            "```",
            "~~~~\nunclosed tilde",
            "- [",
            "1)",
            "<div",
            "> > >",
        ).forEach { frag ->
            MarkdownDocument.parse(frag)
            val doc = MarkdownDocument()
            frag.forEach { doc.append(it.toString()) }
            doc.finish()
        }
    }

    /** Old case 3: simple bold and italic still parse correctly. */
    @Test
    fun simpleBoldAndItalicStillParse() {
        val spans = paragraphInlines("a **b** c")
        assertEquals(listOf(MdInline.Text("a "), MdInline.Strong(listOf(MdInline.Text("b"))), MdInline.Text(" c")), spans)
        assertEquals(
            listOf(MdInline.Emphasis(listOf(MdInline.Text("i"))), MdInline.Text(" "), MdInline.Emphasis(listOf(MdInline.Strong(listOf(MdInline.Text("bi")))))),
            paragraphInlines("*i* ***bi***"),
        )
    }

    /**
     * Old case 4 (the `hrRegex` backreference fix): thematic breaks of every marker, with spaces;
     * a dash line under a paragraph is a setext heading, not a rule; two markers are not a rule.
     */
    @Test
    fun thematicBreaksAndTheHrBackreferenceCase() {
        for (hr in listOf("---", "***", "___", "* * *", "- - -", "_ _ _   ", "*****")) {
            assertEquals(hr, listOf(MdNode.Rule), MarkdownDocument.parse(hr))
        }
        assertTrue(MarkdownDocument.parse("--").single() is MdNode.Paragraph)
        val setext = MarkdownDocument.parse("Title\n---").single()
        assertTrue(setext is MdNode.Heading && setext.level == 2)
        // Mixed markers are not a rule (the old regex accepted "-*-").
        assertTrue(MarkdownDocument.parse("-*-").single() !is MdNode.Rule)
    }

    /** Old case 5: prefix + tail == whole at every streaming length (blank-line split). */
    @Test
    fun incrementalSplitEqualsWholeAcrossStreamingLengths() {
        val full = "Intro line.\n\n- item one\n- item two\n\nClosing remark here."
        for (n in 1..full.length) {
            val text = full.substring(0, n)
            val doc = MarkdownDocument()
            for (ch in text) doc.append(ch.toString())
            assertEquals("len=$n", MarkdownDocument.parse(text), doc.finish().stable)
        }
    }

    /** Block nesting far past the cap is flattened, never rendered as a deeper tree. */
    @Test
    fun deepBlockNestingIsCapped() {
        val deepQuote = ">".repeat(500) + " deep"
        assertTrue(maxBlockDepth(MarkdownDocument.parse(deepQuote)) <= MAX_BLOCK_DEPTH + 1)
        val deepList = (0 until 40).joinToString("\n") { "  ".repeat(it) + "- level $it" }
        assertTrue(maxBlockDepth(MarkdownDocument.parse(deepList)) <= MAX_BLOCK_DEPTH + 1)
        assertTrue(maxBlockDepth(MarkdownDocument.parse(Corpus.text("38-syn-deep"))) <= MAX_BLOCK_DEPTH + 1)
    }
}
