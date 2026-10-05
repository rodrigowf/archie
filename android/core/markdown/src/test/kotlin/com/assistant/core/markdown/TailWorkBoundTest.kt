package com.assistant.core.markdown

import com.assistant.core.markdown.internal.ParseStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Spec 14 §3.2 proof obligation `TailWorkBoundTest`: with a 20 KB stable prefix, appending one
 * character parses ≤ 1 block (instrumented parser counter), and never touches the prefix.
 */
class TailWorkBoundTest {

    private fun stablePrefix(minChars: Int = 20_000): String = buildString {
        var i = 0
        while (length < minChars) {
            append("## Section $i\n\nParagraph $i with **bold**, `code` and a [link](https://example.com/$i).\n\n")
            append("- item a$i\n- item b$i\n\n")
            append("```kotlin\nval x$i = $i\n```\n\n")
            append("| k | v |\n|---|---|\n| $i | ${i * 2} |\n\n")
            i++
        }
    }

    @Before
    fun reset() = ParseStats.reset()

    @Test
    fun appendingOneCharAfterTwentyKbParsesOnlyTheTail() {
        val prefix = stablePrefix()
        assertTrue(prefix.length >= 20_000)
        val doc = MarkdownDocument()
        doc.append(prefix)
        val before = doc.snapshot
        assertTrue("whole prefix committed", before.tail.isEmpty())

        ParseStats.reset()
        val after = doc.append("x")
        assertTrue("≤ 1 parse, was ${ParseStats.parseCount}", ParseStats.parseCount <= 1)
        assertEquals("only the 1-char tail is parsed", 1L, ParseStats.parsedChars)
        // The committed prefix is the same list (same node instances): nothing upstream recomposes.
        assertEquals(before.stable.size, after.stable.size)
        for (i in before.stable.indices) assertSame(before.stable[i], after.stable[i])
        assertEquals(1, after.tail.size)

        // Keep streaming a paragraph: each append parses only the paragraph so far.
        val sentence = "more words in the streaming tail paragraph"
        var tailLen = 1
        for (ch in sentence) {
            ParseStats.reset()
            doc.append(ch.toString())
            tailLen++
            assertEquals(1L, ParseStats.parseCount)
            assertEquals(tailLen.toLong(), ParseStats.parsedChars)
        }
    }

    @Test
    fun appendingInsideAnOpenFenceParsesNothing() {
        val doc = MarkdownDocument()
        doc.append(stablePrefix())
        doc.append("```python\n")
        repeat(1_000) { doc.append("print($it)\n") }
        ParseStats.reset()
        repeat(100) { doc.append("x") }
        assertEquals("open fence: no commonmark parse per delta", 0L, ParseStats.parseCount)
        // Closing the fence parses the fence once.
        doc.append("\n```\n")
        assertEquals(1L, ParseStats.parseCount)
        assertTrue(doc.snapshot.stable.last() is MdNode.CodeBlock)
    }

    @Test
    fun aLongListCommitsItemByItem() {
        val doc = MarkdownDocument()
        doc.append("Intro\n\n")
        var maxParsed = 0L
        for (i in 0 until 500) {
            ParseStats.reset()
            doc.append("- list item number $i with some words\n")
            maxParsed = maxOf(maxParsed, ParseStats.parsedChars)
        }
        // Each append parses the previous item + the new one, never the whole 18 KB list.
        assertTrue("max parsed per append was $maxParsed", maxParsed < 100)
        assertEquals(499, doc.snapshot.stable.count { it is MdNode.ListBlock })
    }
}
