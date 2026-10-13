package com.assistant.archie.feature.chat

import android.app.Application
import com.assistant.archie.feature.chat.model.AgentUiMapper
import com.assistant.archie.feature.chat.model.AgentViewUi
import com.assistant.archie.feature.chat.support.Frames
import com.assistant.archie.feature.chat.support.UiStates
import com.assistant.archie.feature.chat.ui.DefaultToolCardRenderer
import com.assistant.core.conversation.ConversationInput
import com.assistant.core.conversation.ConversationReducer
import com.assistant.core.conversation.ConversationState
import com.assistant.core.protocol.ContentBlockDto
import com.assistant.core.protocol.MessagePreviewDto
import com.assistant.core.protocol.PaginatedMessagesDto
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of the subagent view (spec 12 §4.8, plan §4.4): the Main tab of a session with three
 * agents (tab row, agent cards, Agents button), and a nested agent's tab (breadcrumb, transcript,
 * read-only bar). Running agents carry `duration_ms` and no `started_at`, so no clock is involved.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w443dp-h986dp-xxhdpi", application = Application::class)
class AgentConversationGoldens : ConversationGoldenBase("compact") {

    private fun session(): ConversationState {
        var s = ConversationReducer.reduce(Frames.agent(), ConversationInput.LocalSend("Find where the chat list jumps and dig through the logs"))
        s = Frames.reduce(
            s,
            """{"type":"status","status":"processing"}""",
            """{"type":"text_complete","text":"Two agents are on it."}""",
            """{"type":"tool_use","tool_use_id":"t1","tool_name":"Agent","tool_input":{"subagent_type":"Explore","description":"Find scroll logic","prompt":"Find the chat scroll logic."}}""",
            """{"type":"subagent_started","tool_use_id":"t1","parent_tool_use_id":null,"agent_type":"Explore","description":"Find scroll logic","background":false}""",
            """{"type":"subagent_progress","tool_use_id":"t1","activity":"Grep","usage":{"tokens":12400,"tool_uses":3,"duration_ms":42000}}""",
            """{"type":"subagent_started","tool_use_id":"n1","parent_tool_use_id":"t1","agent_type":"worker","description":"Check the list keys","background":false}""",
            """{"type":"subagent_progress","tool_use_id":"n1","activity":"Read","usage":{"tokens":3100,"tool_uses":1,"duration_ms":9000}}""",
            """{"type":"text_complete","text":"The log digest runs in the background."}""",
            """{"type":"tool_use","tool_use_id":"t2","tool_name":"Agent","tool_input":{"subagent_type":"general-purpose","description":"Digest logs","prompt":"Read the logs.","run_in_background":true}}""",
            """{"type":"subagent_started","tool_use_id":"t2","parent_tool_use_id":null,"agent_type":"general-purpose","description":"Digest logs","background":true}""",
            """{"type":"tool_result","tool_use_id":"t2","output":"Async agent launched successfully.","is_error":false}""",
            """{"type":"subagent_finished","tool_use_id":"t2","status":"done","summary":"Logs are clean: no errors since Monday.","usage":{"tokens":8800,"tool_uses":5,"duration_ms":71000}}""",
        )
        return s
    }

    @Test fun agentsMain() {
        val s = session()
        val ui = UiStates.of(s).copy(agents = AgentUiMapper.agents(s, selected = null, hidden = emptySet()))
        scene("chat-agents-main", ui)
    }

    @Test fun agentsNestedTab() {
        var s = session()
        s = ConversationReducer.reduceAll(
            s,
            listOf(
                ConversationInput.OpenSubagent("n1"),
                ConversationInput.SubagentPage(
                    "n1",
                    PaginatedMessagesDto(
                        listOf(
                            MessagePreviewDto("user", "Check which keys the chat list uses.", listOf(ContentBlockDto("text", text = "Check which keys the chat list uses."))),
                        ),
                        1, false, 0,
                    ),
                ),
            ),
        )
        s = Frames.reduce(
            s,
            """{"type":"subagent_event","tool_use_id":"n1","frame":{"type":"tool_use","tool_use_id":"g1","tool_name":"Grep","tool_input":{"pattern":"key =","path":"feature/chat"}}}""",
            """{"type":"subagent_event","tool_use_id":"n1","frame":{"type":"tool_result","tool_use_id":"g1","output":"ChatList.kt:132: key = { reversed[it].key }","is_error":false}}""",
            """{"type":"subagent_event","tool_use_id":"n1","frame":{"type":"text_complete","text":"Keys come from the spec-12 ids, so they are stable."}}""",
        )
        val sub = s.subagents["n1"]!!
        val store = MarkdownStore()
        val items = ChatListFlattener(DefaultToolCardRenderer::describe) { id, t, st -> store.snapshot(id, t, st) }.flatten(sub.conv!!, subagents = s.subagents).items
        val view = AgentViewUi(
            agent = AgentUiMapper.agent(sub, s.subagents),
            path = AgentUiMapper.path(sub, s.subagents).toImmutableList(),
            items = items.toImmutableList(),
            loaded = true,
            error = null,
            empty = false,
        )
        val ui = UiStates.of(s).copy(agents = AgentUiMapper.agents(s, selected = "n1", hidden = emptySet()), agentView = view)
        scene("chat-agents-nested-tab", ui)
    }
}
