package com.assistant.archie.feature.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.assistant.archie.feature.chat.model.ChatItem
import com.assistant.archie.feature.chat.support.Fixtures
import com.assistant.archie.feature.chat.support.Frames
import com.assistant.archie.feature.chat.support.UiStates
import com.assistant.archie.feature.chat.ui.ConversationContent
import com.assistant.archie.feature.chat.ui.capOutput
import com.assistant.core.conversation.AssistantEntry
import com.assistant.core.conversation.ConversationState
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.design.theme.ArchieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val itemTag = SemanticsMatcher("test tag starts with item:") {
    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("item:") == true
}

private fun SemanticsNode.itemKey() = config[SemanticsProperties.TestTag].removePrefix("item:")

/**
 * Spec 14 §6.3 `ToolOrderingFixtureUiTest` (R4): for each ordering fixture, the order the list
 * actually draws (top to bottom on screen, through `reverseLayout` and the reversed items) equals the
 * fixture's expected entry/block order. A tall window keeps every item composed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w443dp-h4000dp", application = Application::class)
class ToolOrderingFixtureUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun renderedOrderEqualsFixtureOrder() {
        var ui by mutableStateOf(ConversationUiState())
        compose.setContent { ArchieTheme(reduceMotion = true) { ConversationContent(ui, "", {}) } }
        for (name in Fixtures.R4) {
            val fx = Fixtures.load(name)
            val s = Fixtures.states(fx).last()
            ui = UiStates.of(s, UiStates.expandAll(s))
            compose.waitForIdle()
            val byKey = ui.items.associateBy { it.key }
            val drawn = compose.onAllNodes(itemTag).fetchSemanticsNodes()
                .sortedBy { it.boundsInRoot.top }
                .mapNotNull { byKey[it.itemKey()] }
            assertEquals("all items drawn for $name", ui.items.size, drawn.size)
            assertEquals("drawn order of $name", Fixtures.expectedTokens(fx), Fixtures.tokens(drawn, s))
        }
    }

    @Test
    fun liveStreamingTurnKeepsOrderAtEveryStep() {
        // The reducer's order holds while streaming too, not only at the end (R4 is a live bug).
        var ui by mutableStateOf(ConversationUiState())
        compose.setContent { ArchieTheme(reduceMotion = true) { ConversationContent(ui, "", {}) } }
        val fx = Fixtures.load("android_voice_ordering_bug.json")
        for (s in Fixtures.states(fx)) {
            ui = UiStates.of(s, UiStates.expandAll(s))
            compose.waitForIdle()
            val byKey = ui.items.associateBy { it.key }
            val drawn = compose.onAllNodes(itemTag).fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }.mapNotNull { byKey[it.itemKey()] }
            assertEquals(Fixtures.tokens(ui.items, s), Fixtures.tokens(drawn, s))
        }
    }
}

/**
 * Spec 14 §6.3 `ToolResultVisibleUiTest` (R7): every card with a result shows it — at every step of
 * the R7 fixtures (live), after their REST refetches, and reactively while the card is open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w443dp-h4000dp", application = Application::class)
class ToolResultVisibleUiTest {
    @get:Rule val compose = createComposeRule()

    private fun tools(s: ConversationState) = s.entries.filterIsInstance<AssistantEntry>().flatMap { it.blocks }.filterIsInstance<ToolBlock>()

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun everyCompletedCardShowsItsOutputLiveAndAfterRefetch() {
        var ui by mutableStateOf(ConversationUiState())
        compose.setContent { ArchieTheme(reduceMotion = true) { ConversationContent(ui, "", {}) } }
        var checked = 0
        for (name in Fixtures.R7) {
            for ((step, s) in Fixtures.states(Fixtures.load(name)).withIndex()) {
                ui = UiStates.of(s, UiStates.expandAll(s))
                compose.waitForIdle()
                for (t in tools(s)) {
                    val out = t.output ?: continue
                    val expected = if (out.isEmpty()) "No output" else capOutput(out)
                    assertTrue("$name step $step: ${t.toolUseId} shows its output '$expected'", shown(expected))
                    checked++
                }
            }
        }
        assertTrue("checked some outputs", checked > 20)
    }

    @Test
    fun openRunningCardPicksUpItsResultWithoutReToggling() {
        val running = Frames.reduce(
            Frames.agent(),
            """{"type":"status","status":"processing"}""",
            """{"type":"tool_use","tool_use_id":"t1","tool_name":"Bash","tool_input":{"command":"npm run build"}}""",
        )
        var ui by mutableStateOf(UiStates.of(running))
        compose.setContent { ArchieTheme(reduceMotion = true) { ConversationContent(ui, "", {}) } }
        compose.onNodeWithText("Running…").assertExists()   // never empty while running (spec 14 §3.4)

        val done = Frames.reduce(running, """{"type":"tool_result","tool_use_id":"t1","output":"412 modules transformed","is_error":false}""")
        ui = UiStates.of(done)
        compose.waitForIdle()
        compose.onNodeWithText("412 modules transformed").assertExists()
        compose.onNodeWithText("Running…").assertDoesNotExist()
        assertTrue(ui.items.filterIsInstance<ChatItem.ToolCard>().single().expanded)
    }

    @Test
    fun collapsedCardShowsTheResultWhenOpened() {
        val s = Fixtures.run("tool_result_after_turn_ended.json")
        var ui by mutableStateOf(UiStates.of(s))
        compose.setContent {
            ArchieTheme(reduceMotion = true) {
                ConversationContent(ui, "", { a ->
                    if (a is ChatAction.ToggleCard) ui = UiStates.of(s, FlattenOptions(cardToggles = mapOf(a.key to a.expanded)))
                })
            }
        }
        compose.onNodeWithText("sleep 30 && echo done").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("done").assertExists()
    }
}
