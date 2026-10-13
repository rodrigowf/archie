package com.assistant.archie.feature.chat

import android.app.Application
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.assistant.archie.feature.chat.model.AgentUiMapper
import com.assistant.archie.feature.chat.support.Frames
import com.assistant.core.conversation.ConversationInput
import com.assistant.core.conversation.SubagentStatus
import com.assistant.core.protocol.MessagePreviewDto
import com.assistant.core.protocol.ContentBlockDto
import com.assistant.core.protocol.PaginatedMessagesDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec 12 §4.8 UI (plan §4.4): the agent tab row, the Agents button and sheet, the inline agent card
 * and an agent tab with its read-only bar, driven through the real reducer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w443dp-h986dp-xxhdpi", application = Application::class)
class AgentsUiTest : ScreenTestBase() {
    private val spawn = """{"type":"tool_use","tool_use_id":"t1","tool_name":"Agent","tool_input":{"subagent_type":"Explore","description":"Find scroll logic","prompt":"p"}}"""
    private val started = """{"type":"subagent_started","tool_use_id":"t1","parent_tool_use_id":null,"agent_type":"Explore","description":"Find scroll logic","background":false,"started_at":"2026-10-12T12:00:01Z"}"""

    private fun runningAgent() {
        show(Frames.agent())
        backend.apply(ConversationInput.LocalSend("go"))
        backend.frames("""{"type":"status","status":"processing"}""", spawn, started)
        compose.waitForIdle()
    }

    @Test
    fun noSubagents_noTabsAndNoAgentsButton() {
        show(Frames.agent())
        compose.onNodeWithTag("agent-tabs").assertDoesNotExist()
        compose.onNodeWithTag("agents-button").assertDoesNotExist()
    }

    @Test
    fun aNewAgentGetsATabButNeverTakesFocus() {
        runningAgent()
        compose.onNodeWithTag("agent-tabs").assertExists()
        compose.onNodeWithTag("agent-tab:main").assertIsSelected()
        compose.onNodeWithTag("agent-tab:t1").assertIsNotSelected()
        compose.onNodeWithContentDescription("Agents, 1 running of 1").assertExists()
        assertNull("placeholder only until opened (SA-5)", backend.state.value!!.subagents["t1"]!!.conv)
        compose.onNodeWithTag("composer").assertExists()
    }

    @Test
    fun enteringATabMaterialisesItAndShowsTheReadOnlyBar() {
        runningAgent()
        compose.onNodeWithTag("agent-tab:t1").performClick()
        compose.waitForIdle()
        assertNotNull(backend.state.value!!.subagents["t1"]!!.conv)
        compose.onNodeWithTag("agent-loading").assertExists()
        compose.onNodeWithTag("agent-bar").assertExists()
        compose.onNodeWithTag("composer").assertDoesNotExist()
        backend.apply(
            ConversationInput.SubagentPage(
                "t1",
                PaginatedMessagesDto(listOf(MessagePreviewDto("user", "Find it", listOf(ContentBlockDto("text", text = "Find it")))), 1, false, 0),
            ),
        )
        backend.frames("""{"type":"subagent_event","tool_use_id":"t1","frame":{"type":"text_complete","text":"Found it in a.ts."}}""")
        compose.waitForIdle()
        compose.onNodeWithText("Found it in a.ts.").assertExists()
        compose.onNodeWithTag("agent-stop").performClick()
        assertEquals(listOf("t1"), backend.stoppedAgents)
        compose.onNodeWithTag("agent-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assertExists()
        compose.onNodeWithTag("agent-tab:main").assertIsSelected()
    }

    @Test
    fun theTaskCardShowsTheAgentAndOpensItsTab() {
        runningAgent()
        backend.frames("""{"type":"subagent_progress","tool_use_id":"t1","activity":"Grep","usage":{"tokens":1200,"tool_uses":3,"duration_ms":null}}""")
        compose.waitForIdle()
        compose.onNodeWithText("Running Grep").assertExists()
        compose.onNodeWithTag("agent-open:t1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("agent-tab:t1").assertIsSelected()
        compose.onNodeWithTag("agent-view:t1").assertExists()
    }

    @Test
    fun theAgentsSheetListsAndOpensAgents_andCloseFinishedHidesTheirTabs() {
        runningAgent()
        backend.frames("""{"type":"subagent_finished","tool_use_id":"t1","status":"done","summary":"Found it.","usage":null,"ended_at":"2026-10-12T12:00:09Z"}""")
        compose.waitForIdle()
        compose.onNodeWithTag("agents-button").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("agents-sheet").assertExists()
        compose.onNodeWithTag("agent-row:t1").assertExists()
        compose.onNodeWithTag("agents-close-finished").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("agent-tab:t1").assertDoesNotExist()
        compose.onNodeWithTag("agent-row:t1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("agent-tab:t1").assertIsSelected()
    }

    @Test
    fun mapperNestsAndNamesAgents() {
        val s = Frames.reduce(
            Frames.agent(),
            started,
            """{"type":"subagent_started","tool_use_id":"n1","parent_tool_use_id":"t1","agent_type":"worker","description":"","background":true}""",
        )
        val ui = AgentUiMapper.agents(s, selected = null, hidden = emptySet())
        assertEquals(listOf(0, 1), ui.all.map { it.depth })
        assertEquals("Explore: Find scroll logic", ui.all[0].title)
        assertEquals("worker", ui.all[1].tabLabel)
        assertEquals(listOf("Main", "Explore", "worker"), AgentUiMapper.path(s.subagents["n1"]!!, s.subagents))
        assertEquals(2, ui.running)
        assertEquals(SubagentStatus.RUNNING, ui.all[1].status)
    }

    @Test
    fun anAgentTabNeverPagesMainHistory() {
        val actions = mutableListOf<ChatAction>()
        val s = Frames.reduce(Frames.agent(), started)
        val sub = s.subagents["t1"]!!
        val view = com.assistant.archie.feature.chat.model.AgentViewUi(
            agent = AgentUiMapper.agent(sub, s.subagents),
            path = kotlinx.collections.immutable.persistentListOf(),
            items = kotlinx.collections.immutable.persistentListOf(
                com.assistant.archie.feature.chat.model.ChatItem.Notice("e1", com.assistant.core.conversation.NoticeKind.BACKGROUND, "short", com.assistant.archie.feature.chat.model.ChatItem.Gap.None),
            ),
            loaded = true, error = null, empty = false,
        )
        compose.setContent {
            com.assistant.core.design.theme.ArchieTheme(reduceMotion = true) {
                com.assistant.archie.feature.chat.ui.AgentTabBody(
                    view, com.assistant.core.model.SessionKind.AGENT, com.assistant.archie.feature.chat.ui.DefaultToolCardRenderer,
                    { actions += it }, {}, null,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("short").assertExists()
        assertTrue("no LoadOlder from an agent tab: $actions", actions.none { it is ChatAction.LoadOlder })
    }
}
