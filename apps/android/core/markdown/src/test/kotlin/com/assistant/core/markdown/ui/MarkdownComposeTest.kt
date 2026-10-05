package com.assistant.core.markdown.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.markdown.Corpus
import com.assistant.core.markdown.MarkdownDocument
import com.assistant.core.markdown.MdNode
import com.assistant.core.markdown.MdSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.IdentityHashMap

/**
 * Compose proofs of spec 14 §3.2: a streaming delta recomposes O(1) list items (only the items
 * whose node changed), and a 5,000-line unterminated fence renders as wrapped chunks with no
 * horizontal-scroll modifier anywhere (`OpenFenceTest`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w443dp-h2000dp-xxhdpi")
class MarkdownComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val noHorizontalScroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)

    @Test
    fun aStreamingDeltaRecomposesOnlyTheChangedItems() {
        val text = Corpus.text("39-syn-assistant") + "\n" + Corpus.text("29-syn-lists")
        val doc = MarkdownDocument()
        var snap by mutableStateOf(doc.snapshot)
        val renders = IdentityHashMap<MdNode, Int>()
        var rendersThisFrame = 0
        compose.setContent {
            ArchieTheme(reduceMotion = true) {
                val style = MarkdownStyle.fromTheme()
                CompositionLocalProvider(
                    LocalMarkdownRenderProbe provides { n ->
                        renders[n] = (renders[n] ?: 0) + 1
                        rendersThisFrame++
                    },
                ) {
                    LazyColumn(Modifier.fillMaxSize()) { markdownItems(snap, "m:1/b:1", style, onLinkClick = {}) }
                }
            }
        }
        var maxPerDelta = 0
        var deltas = 0
        for (delta in text.chunked(5)) {
            val previous = snap
            rendersThisFrame = 0
            snap = doc.append(delta)
            compose.waitForIdle()
            val changed = changedIndices(previous, snap)
            assertTrue(
                "delta $deltas rendered $rendersThisFrame blocks but only ${changed.size} changed",
                rendersThisFrame <= changed.size,
            )
            maxPerDelta = maxOf(maxPerDelta, rendersThisFrame)
            deltas++
        }
        assertTrue("O(1) per delta, max was $maxPerDelta", maxPerDelta <= 3)
        // Every committed node was composed at most once, however many deltas followed it.
        val stable = snap.stable
        val recomposedStable = stable.filter { (renders[it] ?: 0) > 1 }
        assertTrue("committed nodes recomposed: $recomposedStable", recomposedStable.isEmpty())
        assertTrue(deltas > 200)
        // The probe really runs: the tail re-renders on (almost) every delta.
        assertTrue("renders ${renders.values.sum()} for $deltas deltas", renders.values.sum() >= deltas / 2)
    }

    private fun changedIndices(a: MdSnapshot, b: MdSnapshot): List<Int> =
        (0 until b.size).filter { i -> i >= a.size || a[i] !== b[i] }

    @Test
    fun aFiveThousandLineOpenFenceRendersWrappedWithNoHorizontalScroll() {
        val doc = MarkdownDocument()
        doc.append("Here is the dump:\n\n```kotlin\n")
        repeat(5_000) { doc.append("val v$it = \"${"x".repeat(it % 150)}\" // a long line that must wrap, never scroll\n") }
        val snap = doc.snapshot

        // Model: the open fence is bounded chunks; only the last one is open.
        val chunks = snap.tail.map { it as MdNode.CodeTail }
        assertTrue("${chunks.size} chunks", chunks.size >= 5_000 / MarkdownDocument.CHUNK_LINES)
        assertTrue(chunks.dropLast(1).none { it.open } && chunks.last().open)
        assertTrue(chunks.all { it.text.length <= MarkdownDocument.HARD_CHARS })
        assertTrue(chunks.all { it.text.count { c -> c == '\n' } < MarkdownDocument.CHUNK_LINES })
        assertEquals((0 until chunks.size).toList(), chunks.map { it.chunk })

        lateinit var scope: CoroutineScope
        lateinit var listState: LazyListState
        compose.setContent {
            ArchieTheme(reduceMotion = true) {
                val style = MarkdownStyle.fromTheme()
                listState = rememberLazyListState()
                scope = rememberCoroutineScope()
                LazyColumn(Modifier.fillMaxSize(), state = listState) { markdownItems(snap, "m", style, onLinkClick = {}) }
            }
        }
        assertTrue(compose.onAllNodesWithTag(CODE_TAIL_TAG).fetchSemanticsNodes().isNotEmpty())
        compose.onAllNodes(noHorizontalScroll).assertCountEquals(0)
        // The same at the end of the fence (the newest, open chunk).
        compose.runOnIdle { scope.launch { listState.scrollToItem(snap.size - 1) } }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag(CODE_TAIL_TAG).fetchSemanticsNodes().isNotEmpty())
        compose.onAllNodes(noHorizontalScroll).assertCountEquals(0)
    }

    /** Positive control for the matcher above: a closed block scrolls only after "No wrap". */
    @Test
    fun closedBlockScrollsHorizontallyOnlyWhenNoWrapIsChosen() {
        compose.setContent {
            ArchieTheme(reduceMotion = true) {
                CodeBlockView("kotlin", "val long = \"" + "y".repeat(400) + "\"", MarkdownStyle.fromTheme())
            }
        }
        compose.onAllNodes(noHorizontalScroll).assertCountEquals(0)
        compose.onNodeWithText("No wrap").performClick()
        compose.onAllNodes(noHorizontalScroll).assertCountEquals(1)
    }

    @Test
    fun longCodeBlocksCollapseWithShowAll() {
        val code = (1..100).joinToString("\n") { "line $it" }
        compose.setContent {
            ArchieTheme(reduceMotion = true) { CodeBlockView(null, code, MarkdownStyle.fromTheme()) }
        }
        compose.onNodeWithText("line 24", substring = true).assertExists()
        compose.onNodeWithText("line 100", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Show all (100 lines)").performClick()
        compose.onNodeWithText("line 100", substring = true).assertExists()
    }
}
