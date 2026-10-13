package com.assistant.archie.feature.chat

import com.assistant.archie.feature.chat.model.ChatItem
import com.assistant.archie.feature.chat.support.Frames
import com.assistant.archie.feature.chat.ui.DefaultToolCardRenderer
import com.assistant.core.conversation.ConversationInput
import com.assistant.core.conversation.ConversationReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Subagent list rules (spec 12 §4.8, web parity, 2026-10-12 device freeze): agent cards never fold
 * into "N steps" groups and never open by themselves; an agent tab opens only its running card;
 * subagent traffic only patches the agent cards.
 */
class AgentListTest {
    private val store = MarkdownStore()
    private val flattener = ChatListFlattener(DefaultToolCardRenderer::describe) { id, t, s -> store.snapshot(id, t, s) }

    private fun spawn(id: String, desc: String) =
        """{"type":"tool_use","tool_use_id":"$id","tool_name":"Agent","tool_input":{"subagent_type":"Explore","description":"$desc","prompt":"p"}}"""

    private fun started(id: String) =
        """{"type":"subagent_started","tool_use_id":"$id","parent_tool_use_id":null,"agent_type":"Explore","description":"x","background":false}"""

    private fun liveTwoAgents() = Frames.reduce(
        ConversationReducer.reduce(Frames.agent(), ConversationInput.LocalSend("go")),
        """{"type":"status","status":"processing"}""",
        """{"type":"tool_use","tool_use_id":"b0","tool_name":"Bash","tool_input":{"command":"ls"}}""",
        spawn("t1", "One"), started("t1"),
        spawn("t2", "Two"), started("t2"),
        """{"type":"tool_use","tool_use_id":"b1","tool_name":"Bash","tool_input":{"command":"pwd"}}""",
    )

    @Test
    fun agentCardsNeverGroupAndNeverAutoExpand() {
        val items = flattener.flatten(liveTwoAgents()).items
        val cards = items.filterIsInstance<ChatItem.ToolCard>()
        assertTrue("no step group around agent cards", items.none { it is ChatItem.ToolGroup })
        assertEquals(listOf("b0", "t1", "t2", "b1"), cards.map { it.block.toolUseId })
        val agents = cards.filter { it.agent != null }
        assertEquals(listOf("t1", "t2"), agents.map { it.block.toolUseId })
        assertTrue(agents.all { it.position == ChatItem.GroupPosition.Solo && !it.expanded })
        assertTrue("other live cards still open", cards.first { it.block.toolUseId == "b1" }.expanded)
    }

    @Test
    fun anAgentTabOpensOnlyTheRunningCard() {
        val s = Frames.reduce(
            Frames.agent(),
            """{"type":"status","status":"processing"}""",
            """{"type":"tool_use","tool_use_id":"g1","tool_name":"Grep","tool_input":{"pattern":"x"}}""",
            """{"type":"tool_result","tool_use_id":"g1","output":"a","is_error":false}""",
            """{"type":"tool_use","tool_use_id":"g2","tool_name":"Read","tool_input":{"file_path":"a"}}""",
        )
        val o = FlattenOptions(expandLiveRun = false, groupToggles = emptyMap())
        val items = flattener.flatten(s, o).items
        val group = items.filterIsInstance<ChatItem.ToolGroup>().single()
        assertTrue(group.expanded)
        val cards = items.filterIsInstance<ChatItem.ToolCard>().associateBy { it.block.toolUseId }
        assertFalse(cards.getValue("g1").expanded)
        assertTrue(cards.getValue("g2").expanded)
    }

    @Test
    fun subagentTrafficOnlyPatchesAgentCards() {
        val s = liveTwoAgents()
        val before = flattener.flatten(s).items
        val after = Frames.reduce(s, """{"type":"subagent_progress","tool_use_id":"t1","activity":"Grep","usage":null}""")
        val patched = ChatListFlattener.patchAgents(before, after.subagents)
        assertEquals(flattener.flatten(after).items, patched)
        val i = patched.indexOfFirst { it is ChatItem.ToolCard && it.block.toolUseId == "t2" }
        assertSame("untouched items keep their instances", before[i], patched[i])
        assertSame(before, ChatListFlattener.patchAgents(before, s.subagents))
    }
}
