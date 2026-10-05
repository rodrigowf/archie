package com.assistant.archie.feature.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** Tree model (counts, MEMORY.md first, search, flattening) and the document copy (web parity). */
class MemoryTreeTextTest {
    @Test fun `counts, index pinned first, top-level folders open`() {
        val nodes = pinIndexFirst(MemoryFixtures.mockTree)
        assertEquals("MEMORY.md", nodes.first().name)
        assertEquals(138, countAll(nodes))
        assertEquals(64, countFiles(nodes[1]))
        val rows = flatten(nodes, topFolderIds(nodes))
        // MEMORY.md, assistant (open) + its 5 folders + 23 files, home, projects.
        assertEquals(listOf("MEMORY.md", "assistant", "architecture", "devices"), rows.take(4).map { it.name })
        assertEquals(1, rows.first { it.name == "architecture" }.depth)
        assertTrue(rows.none { it.path.startsWith("assistant/architecture/") })
    }

    @Test fun `live tree has 138 files and pins its index`() {
        assertEquals(138, countAll(MemoryFixtures.liveTree))
        assertEquals("MEMORY.md", pinIndexFirst(MemoryFixtures.liveTree).first().path)
    }

    @Test fun `search matches every word in any order and keeps the folders on the way`() {
        val hits = filterMemory(MemoryFixtures.mockTree, "subsystem VOICE")
        val rows = flatten(hits, folderIds(hits))
        assertEquals(listOf("assistant", "architecture", "voice_subsystem.md"), rows.map { it.name })
        // A folder whose own path matches keeps all its files.
        assertEquals(5, countAll(filterMemory(MemoryFixtures.mockTree, "architecture")))
        assertTrue(filterMemory(MemoryFixtures.mockTree, "nothing-like-this").isEmpty())
        assertEquals(MemoryFixtures.mockTree, filterMemory(MemoryFixtures.mockTree, "   "))
    }

    @Test fun `null children count as zero`() {
        assertEquals(0, countFiles(com.assistant.core.model.MemoryNode("x", "x", true, null)))
    }

    @Test fun `crumbs and names`() {
        assertEquals("assistant / architecture", folderCrumb("assistant/architecture/voice_subsystem.md"))
        assertEquals("", folderCrumb("MEMORY.md"))
        assertEquals("voice_subsystem.md", fileName("assistant/architecture/voice_subsystem.md"))
    }

    @Test fun `frontmatter chip label is category leaf and refs`() {
        val fm = com.assistant.core.markdown.Frontmatter.split(MemoryFixtures.VOICE_DOC).frontmatter!!
        assertEquals("Frontmatter · architecture · 4 refs", frontmatterChipLabel(fm))
        assertEquals("Frontmatter · 1 ref", frontmatterChipLabel("references: [a.md]"))
        assertEquals("Frontmatter", frontmatterChipLabel("not: [valid"))
    }

    @Test fun `modified line`() {
        val now = Instant.parse("2026-10-04T12:00:00Z")
        val z = ZoneOffset.UTC
        assertEquals("Modified 2 days ago", modifiedLine("modified: 2026-10-02", now, z))
        assertEquals("Modified today", modifiedLine("modified: 2026-10-04", now, z))
        assertEquals("Modified yesterday", modifiedLine("modified: 2026-10-03", now, z))
        assertEquals("Modified 3 h ago", modifiedLine("modified: 2026-10-04T09:00:00+00:00", now, z))
        assertEquals("Modified 12 Sep", modifiedLine("modified: 2026-09-12", now, z).let { it?.replace("Sept", "Sep") })
        assertNull(modifiedLine("category: x", now, z))
        assertNull(modifiedLine(null, now, z))
    }

    @Test fun `title split`() {
        val s = splitTitle("# Voice subsystem\n\nBody")
        assertEquals("# Voice subsystem", s.heading)
        assertEquals("\nBody", s.rest)
        assertNull(splitTitle("Body\n# Late").heading)
    }
}
