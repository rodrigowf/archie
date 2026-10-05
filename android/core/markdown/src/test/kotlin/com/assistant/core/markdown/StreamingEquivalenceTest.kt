package com.assistant.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Spec 14 §3.2 proof obligation `StreamingEquivalenceTest`: for 40 recorded messages, feeding the
 * text in random-sized deltas yields the same final [MdNode] list as parsing it all at once.
 *
 * Stronger than required, it also proves that a commit is never wrong: every node committed while
 * streaming equals the final node at the same index (so the UI never shows a committed block that
 * later changes), and once committed a node instance is never replaced.
 */
class StreamingEquivalenceTest {

    @Test
    fun corpusHasFortyMessagesWithoutReferenceDefinitions() {
        assertEquals(40, Corpus.docs.size)
        val refDef = Regex("(?m)^ {0,3}\\[[^\\]^][^\\]]*\\]:\\s")
        Corpus.docs.forEach { (name, text) -> assertTrue("$name defines a reference link", !refDef.containsMatchIn(text)) }
    }

    @Test
    fun randomDeltasEqualOneShotParse() {
        for ((name, text) in Corpus.docs) {
            val expected = MarkdownDocument.parse(text)
            for (seed in 1..4) {
                val maxDelta = listOf(3, 12, 40, 400)[seed - 1]
                val committed = ArrayList<MdNode>()
                val doc = stream(text, Random(seed * 7919 + name.hashCode()), maxDelta) { snap ->
                    // Committed nodes are append-only: same instances, never changed.
                    for (i in committed.indices) assertSame("$name seed $seed: committed node $i replaced", committed[i], snap.stable[i])
                    for (i in committed.size until snap.stable.size) {
                        committed += snap.stable[i]
                        assertEquals("$name seed $seed: node $i committed too early", expected.getOrNull(i), snap.stable[i])
                    }
                }
                val final = doc.finish()
                assertEquals("$name seed $seed (maxDelta $maxDelta)", expected, final.stable)
                assertTrue(final.tail.isEmpty())
            }
        }
    }

    @Test
    fun charByCharEqualsOneShotParse() {
        for ((name, text) in Corpus.docs) {
            if (text.length > 12_000) continue
            val expected = MarkdownDocument.parse(text)
            val doc = MarkdownDocument()
            for (ch in text) doc.append(ch.toString())
            assertEquals(name, expected, doc.finish().stable)
        }
    }

    /**
     * The block split itself does not change meaning: the one-shot document has the same outline
     * as one commonmark parse of the whole text (lists split per item read the same).
     */
    @Test
    fun blockSplitMatchesWholeDocumentParse() {
        for ((name, text) in Corpus.docs) {
            assertEquals(name, Outline.ofWholeParse(text), Outline.of(MarkdownDocument.parse(text)))
        }
    }

    /** Old `MarkdownParserTest`: prefix+tail equals the whole parse at every streaming length. */
    @Test
    fun everyPrefixFinishesLikeItsOneShotParse() {
        val full = "Intro line.\n\n- item one\n- item two\n\nClosing remark here.\n\n```kt\nval x = 1\n```\n| a | b |\n|---|---|\n| 1 | 2 |\n"
        for (n in 1..full.length) {
            val prefix = full.substring(0, n)
            val streamed = MarkdownDocument()
            prefix.chunked(3).forEach { streamed.append(it) }
            assertEquals("len=$n", MarkdownDocument.parse(prefix), streamed.finish().stable)
        }
    }
}
