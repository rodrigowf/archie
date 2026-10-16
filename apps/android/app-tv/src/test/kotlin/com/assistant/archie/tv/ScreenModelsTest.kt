package com.assistant.archie.tv

import com.assistant.archie.tv.home.HomeModel
import com.assistant.archie.tv.memory.MemoryTree
import com.assistant.archie.tv.memory.paragraphsOf
import com.assistant.archie.tv.theme.TvFormat
import com.assistant.archie.tv.theme.TvTypeScale
import com.assistant.archie.tv.viewer.viewerUrl
import com.assistant.core.model.MemoryNode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Pure helpers behind the T0 placeholder screens and the TV type scale. */
class ScreenModelsTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z")

    @Test fun ago() {
        assertEquals("5 min ago", TvFormat.ago("2026-10-16T11:55:00+00:00", now))
        assertEquals("3 h ago", TvFormat.ago("2026-10-16T09:00:00", now))
        assertEquals("yesterday", TvFormat.ago("2026-10-15T10:00:00Z", now))
        assertEquals(null, TvFormat.ago("not a date", now))
        assertEquals("solar dashboard", TvFormat.titleOf("visualizations/solar-dashboard.html"))
        assertEquals("people/rodrigo", TvFormat.folderOf("people/rodrigo/x.md"))
    }

    @Test fun memoryTreeFlattensOnlyExpandedFoldersDirsFirst() {
        val tree = listOf(
            MemoryNode("MEMORY.md", "MEMORY.md", false, null),
            MemoryNode("people", "people", true, listOf(MemoryNode("a.md", "people/a.md", false, null), MemoryNode("INDEX.md", "people/INDEX.md", false, null))),
        )
        assertEquals(listOf("people", "MEMORY.md"), MemoryTree.rows(tree, emptySet()).map { it.node.path })
        assertEquals(listOf("people", "people/a.md", "people/INDEX.md", "MEMORY.md"), MemoryTree.rows(tree, setOf("people")).map { it.node.path })
        assertEquals(listOf("MEMORY.md", "people/a.md"), HomeModel.files(tree).map { it.path })
    }

    @Test fun paragraphsSkipFrontmatter() {
        assertEquals(listOf("# Title", "Body line."), paragraphsOf("---\ntitle: x\n---\n# Title\n\nBody line.\n"))
    }

    @Test fun viewerUrls() {
        assertEquals("https://192.168.0.200/visualizations/x.html", viewerUrl("visualizations/x.html", "wss://192.168.0.200"))
        assertEquals("http://192.168.0.200:80/a/", viewerUrl("/a/", "ws://192.168.0.200:80"))
        assertEquals("https://example.com/", viewerUrl("https://example.com/", "ws://192.168.0.200:80"))
    }

    @Test fun textSizeScalesTheWholeTypeScale() {
        val normal = TvTypeScale.of(1f)
        val large = TvTypeScale.of(1.3f)
        assertEquals(20f, normal.bodyLarge.fontSize.value, 0.01f)
        assertEquals(30f, normal.bodyLarge.lineHeight.value, 0.01f)
        assertEquals(14f, normal.labelMedium.fontSize.value, 0.01f)
        assertEquals(26f, large.bodyLarge.fontSize.value, 0.01f)
        assertEquals(14f, normal.toM3().labelSmall.fontSize.value, 0.01f) // nothing below the TV minimum
    }
}
