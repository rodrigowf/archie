package com.assistant.core.markdown.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.markdown.MarkdownCache
import com.assistant.core.markdown.MdNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [rememberMarkdown] re-parses when its text changes (it used to keep the first parse forever, so
 * a reloaded memory document never updated). Streaming appends do not use it: they stay on
 * [com.assistant.core.markdown.MarkdownDocument]'s tail-only path (TailWorkBoundTest).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RememberMarkdownTest {
    @get:Rule
    val compose = createComposeRule()

    private val cache = MarkdownCache()
    private var id by mutableStateOf("doc:a")
    private var text by mutableStateOf("# Alpha\n\nFirst body.\n")
    private var nodes: List<MdNode>? = null
    private var nullAfterLoad = false

    private fun show() {
        compose.setContent {
            ArchieTheme(reduceMotion = true) {
                val n = rememberMarkdown(id, text, cache)
                if (n == null && nodes != null && nodes !== NO_NODES) nullAfterLoad = true
                nodes = n
                MarkdownNodes(n ?: emptyList())
            }
        }
    }

    private fun waitForText(t: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun anchors() = nodes.orEmpty().filterIsInstance<MdNode.Heading>().map { it.anchor }

    @Test
    fun replacedTextRendersTheNewDocument() {
        show()
        waitForText("First body.")
        text = "# Beta\n\nSecond body.\n"
        waitForText("Second body.")
        compose.onNodeWithText("First body.", substring = true).assertDoesNotExist()
        assertEquals(listOf("beta"), anchors())
        // Same id: the old parse stayed on screen while the new one ran (no loading flash).
        assertFalse(nullAfterLoad)
    }

    @Test
    fun appendedTextRendersTheWholeText() {
        show()
        waitForText("First body.")
        text += "\n## Gamma\n\nMore.\n"
        waitForText("More.")
        compose.onNodeWithText("First body.", substring = true).assertExists()
        assertEquals(listOf("alpha", "gamma"), anchors())
        assertFalse(nullAfterLoad)
    }

    @Test
    fun anotherDocumentNeverShowsThePreviousOne() {
        show()
        waitForText("First body.")
        nodes = NO_NODES
        id = "doc:b"
        text = "# Delta\n\nOther file.\n"
        compose.runOnIdle { assertFalse("doc:a nodes shown for doc:b", anchors() == listOf("alpha")) }
        waitForText("Other file.")
        assertEquals(listOf("delta"), anchors())
    }

    @Test
    fun aCachedDocumentIsServedInTheSameComposition() {
        cache.getBlocking("doc:b", "# Epsilon\n")
        show()
        waitForText("First body.")
        Snapshot.withMutableSnapshot {
            id = "doc:b"
            text = "# Epsilon\n"
        }
        compose.runOnIdle { assertEquals(listOf("epsilon"), anchors()) }
        assertFalse("a cache hit never returns null", nullAfterLoad)
    }

    private companion object {
        val NO_NODES: List<MdNode> = emptyList()
    }
}
