package com.assistant.archie.tv.memory

import com.assistant.archie.feature.memory.MemoryRow
import com.assistant.core.markdown.InternalLinks
import com.assistant.core.markdown.MarkdownDocument
import com.assistant.core.model.MemoryNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reading model's blocks: one per top-level node, links numbered in reading order (spec 15 §3.5 MemoryDoc). */
class TvMarkdownTest {
    private val ctx = InternalLinks.Context("https://192.168.0.200")

    private fun blocks(md: String) = TvMarkdown.blocks(MarkdownDocument.parse(md), ctx)

    @Test fun oneBlockPerTopLevelNodeWithLinksInReadingOrder() {
        val b = blocks(
            """
            # Fire TV Stick 4K

            The stick runs Archie TV; see [devices.md](../devices.md) and **[the probe](https://x.org/p)**.

            - Agents use `tv_cmd.py viz <path>`.
            - adb is the fallback; see [connect-tv](/memory/archie/skills/connect-tv.md).

            ```bash
            adb connect 192.168.0.15
            ```
            """.trimIndent(),
        )
        assertEquals(5, b.size) // heading, paragraph, 2 list items (one node each), code
        assertEquals("fire-tv-stick-4k", b[0].anchor)
        assertEquals(listOf("../devices.md", "https://x.org/p"), b[1].links)
        assertEquals(emptyList<String>(), b[2].links)
        assertEquals(listOf("/memory/archie/skills/connect-tv.md"), b[3].links)
        assertTrue(b[4].segs.single() is TvSeg.Code)
        assertEquals("• adb is the fallback; see connect-tv.", TvMarkdown.plain(b[3]))
    }

    @Test fun nestedListsQuotesAndAutoLinkedPathsKeepTheLinkOrder() {
        val b = blocks(
            """
            1. First [a](a.md)
               - nested `context/public/x/index.html`
               - and [b](b.md)
            2. Second

            > quoted [c](c.md)
            """.trimIndent(),
        )
        val first = b[0]
        assertEquals(listOf("a.md", "/x/index.html", "b.md"), first.links)
        val texts = first.segs.filterIsInstance<TvSeg.Text>()
        assertEquals(listOf("1.", "◦", "◦"), texts.map { it.marker }) // nested bullets use the level-1 glyph
        assertEquals(listOf(0, 1, 1), texts.map { it.indent })
        assertEquals(listOf(0, 1, 2), texts.map { it.linkStart })
        val quote = b.last().segs.single() as TvSeg.Text
        assertTrue(quote.quote)
        assertEquals(listOf("c.md"), b.last().links)
    }

    @Test fun treeRowsAndPreviews() {
        val tree = listOf(
            MemoryNode("people", "people", true, listOf(
                MemoryNode("INDEX.md", "people/INDEX.md", false, null),
                MemoryNode("rodrigo", "people/rodrigo", true, listOf(MemoryNode("ctx.md", "people/rodrigo/ctx.md", false, null))),
            )),
            MemoryNode("MEMORY.md", "MEMORY.md", false, null),
        )
        assertEquals(listOf("MEMORY.md", "people"), MemoryTree.visible(tree, emptySet(), "").map { it.path })
        assertEquals(listOf("people", "people/rodrigo", "people/rodrigo/ctx.md"), MemoryTree.visible(tree, emptySet(), "ctx").map { it.path })
        val people = MemoryRow("people", "people", 0, isDir = true, count = 2)
        assertEquals("people/INDEX.md", MemoryTree.previewPath(people, tree))
        assertEquals(null, MemoryTree.previewPath(MemoryRow("people/rodrigo", "rodrigo", 1, isDir = true), tree))

        val md = "---\ncategory: people\nmodified: 2026-10-14\n---\n# Rodrigo · personal context\n\n## Work\n\nSenior developer.\n"
        val p = MemoryPreviews.of("people/rodrigo/ctx.md", md, blocks(md.substringAfter("---\n").substringAfter("---\n")))
        assertEquals("Rodrigo · personal context", p.title)
        assertEquals("people / rodrigo", p.chips.first())
        assertTrue(p.chips.last().startsWith("Modified"))
        assertEquals(2, p.blocks.size) // the h1 became the title
    }
}
