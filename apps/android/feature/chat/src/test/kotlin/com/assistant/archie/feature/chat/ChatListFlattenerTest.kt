package com.assistant.archie.feature.chat

import com.assistant.archie.feature.chat.model.ChatItem
import com.assistant.archie.feature.chat.model.ToolStatusUi
import com.assistant.archie.feature.chat.support.Fixtures
import com.assistant.archie.feature.chat.support.Frames
import com.assistant.archie.feature.chat.ui.DefaultToolCardRenderer
import com.assistant.core.conversation.ConversationInput
import com.assistant.core.conversation.ConversationReducer
import com.assistant.core.conversation.ConversationState
import com.assistant.core.conversation.PageMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 14 §6.1 `ChatListFlattenerTest`: R4 ordering, stable keys, "N steps" transitions. */
class ChatListFlattenerTest {
    private val store = MarkdownStore()
    private val flattener = ChatListFlattener(DefaultToolCardRenderer::describe) { id, t, s -> store.snapshot(id, t, s) }

    private fun items(s: ConversationState, o: FlattenOptions = FlattenOptions()) = flattener.flatten(s, o).items

    // ───────────────────────── R4: exactly the reducer's order ─────────────────────────

    @Test
    fun r4FixturesRenderInFixtureOrder() {
        for (name in Fixtures.R4 + Fixtures.R7) {
            val fx = Fixtures.load(name)
            val s = Fixtures.states(fx).last()
            val rendered = Fixtures.tokens(items(s, FlattenOptions(grouping = false)), s)
            assertEquals("render order of $name", Fixtures.expectedTokens(fx), rendered)
        }
    }

    @Test
    fun orderHoldsAtEveryStepOfTheVoiceOrderingBug() {
        // The Android bug (inv03 §4.3): tool calls bunched under the first message. At every step, the
        // second tool and the text must render after the second transcript, never inside the first run.
        val fx = Fixtures.load("android_voice_ordering_bug.json")
        for (s in Fixtures.states(fx)) {
            val t = Fixtures.tokens(items(s, FlattenOptions(grouping = false)), s)
            val second = t.indexOf("u:Also check the weather.")
            val b = t.indexOf("t:call_B")
            if (b >= 0) assertTrue("call_B after the 2nd transcript in $t", second in 0 until b)
            val a = t.indexOf("t:call_A")
            if (a >= 0 && second >= 0) assertTrue("call_A before the 2nd transcript in $t", a < second)
        }
    }

    @Test
    fun groupingNeverReordersOnlyHides() {
        for (name in Fixtures.R4) {
            val s = Fixtures.run(name)
            val flat = items(s, FlattenOptions(grouping = false)).filterIsInstance<ChatItem.ToolCard>().map { it.key }
            val all = com.assistant.archie.feature.chat.support.UiStates.expandAll(s)
            val grouped = items(s, all).filterIsInstance<ChatItem.ToolCard>().map { it.key }
            assertEquals(name, flat, grouped)
        }
    }

    // ───────────────────────── keys ─────────────────────────

    @Test
    fun keysAreUniqueAndUseSpecIds() {
        for (name in Fixtures.R4 + Fixtures.R7) {
            val s = Fixtures.run(name)
            val keys = items(s, FlattenOptions(grouping = false)).map { it.key }
            assertEquals("unique keys in $name", keys.size, keys.toSet().size)
            items(s).filterIsInstance<ChatItem.ToolCard>().filter { it.block.toolUseId.isNotEmpty() }.forEach {
                assertEquals("t:${it.block.toolUseId}", it.key)
            }
        }
    }

    @Test
    fun keysStableAcrossCanonicalRefetch() {
        // Same REST page twice (cold open, then a canonical reload): identical keys, so the list does
        // not re-key and the scroll anchor holds (inv03 §4.2).
        val fx = Fixtures.load("tool_result_on_next_history_page.json")
        val page = Fixtures.page(fx["initial_history"]!!.jsonObject)
        val s0 = Fixtures.initialState(fx["session"]!!.jsonObject)
        val a = ConversationReducer.reduce(s0, ConversationInput.HistoryPage(PageMode.REPLACE, page))
        val b = ConversationReducer.reduceAll(a, listOf(ConversationInput.BeginReload, ConversationInput.HistoryPage(PageMode.REPLACE, page)))
        assertEquals(items(a, FlattenOptions(grouping = false)).map { it.key }, items(b, FlattenOptions(grouping = false)).map { it.key })
    }

    @Test
    fun toolKeysSurviveLiveToRestReconcile() {
        val live = Frames.reduce(
            Frames.agent(),
            """{"type":"status","status":"processing"}""",
            """{"type":"tool_use","tool_use_id":"t1","tool_name":"Bash","tool_input":{"command":"ls"}}""",
            """{"type":"tool_use","tool_use_id":"t2","tool_name":"Read","tool_input":{"file_path":"/a"}}""",
            """{"type":"turn_complete"}""",
        )
        val page = Fixtures.page(
            Json.parseToJsonElement(
                """{"messages":[
                  {"role":"user","text":"go","blocks":[]},
                  {"role":"assistant","text":"","blocks":[
                    {"type":"tool_use","tool_use_id":"t1","tool_name":"Bash","tool_input":{"command":"ls"}},
                    {"type":"tool_use","tool_use_id":"t2","tool_name":"Read","tool_input":{"file_path":"/a"}}]},
                  {"role":"user","text":"","blocks":[
                    {"type":"tool_result","tool_use_id":"t1","output":"a.txt","is_error":false},
                    {"type":"tool_result","tool_use_id":"t2","output":"hello","is_error":false}]}
                ],"total_count":3,"has_more":false,"start_index":0}""",
            ).jsonObject,
        )
        val after = ConversationReducer.reduce(live, ConversationInput.HistoryPage(PageMode.RECONCILE, page))
        val before = items(live, FlattenOptions(grouping = false)).filterIsInstance<ChatItem.ToolCard>().map { it.key }
        val now = items(after, FlattenOptions(grouping = false)).filterIsInstance<ChatItem.ToolCard>()
        assertEquals(before, now.map { it.key })
        assertEquals(listOf("a.txt", "hello"), now.map { it.block.output })
    }

    // ───────────────────────── "N steps" grouping (IA §9.3) ─────────────────────────

    private val twoTools = arrayOf(
        """{"type":"status","status":"processing"}""",
        """{"type":"tool_use","tool_use_id":"t1","tool_name":"Bash","tool_input":{"command":"ls"}}""",
        """{"type":"tool_use","tool_use_id":"t2","tool_name":"Bash","tool_input":{"command":"pwd"}}""",
    )

    @Test
    fun liveRunIsExpandedThenCollapsesWhenTextFollows() {
        val live = Frames.reduce(Frames.agent(), *twoTools)
        val g = items(live).filterIsInstance<ChatItem.ToolGroup>().single()
        assertTrue(g.expanded)
        assertEquals(2, g.count)
        assertEquals(ToolStatusUi.Running, g.status)
        assertEquals(2, items(live).count { it is ChatItem.ToolCard })
        assertTrue(items(live).filterIsInstance<ChatItem.ToolCard>().all { it.expanded })

        val texted = Frames.reduce(live, """{"type":"text_delta","text":"Done."}""")
        val g2 = items(texted).filterIsInstance<ChatItem.ToolGroup>().single()
        assertFalse(g2.expanded)
        assertEquals(g.key, g2.key)
        assertEquals(0, items(texted).count { it is ChatItem.ToolCard })
    }

    @Test
    fun runCollapsesWhenTheTurnCompletes() {
        val done = Frames.reduce(
            Frames.agent(), *twoTools,
            """{"type":"tool_result","tool_use_id":"t1","output":"a","is_error":false}""",
            """{"type":"tool_result","tool_use_id":"t2","output":"oops","is_error":true}""",
            """{"type":"turn_complete"}""",
        )
        val g = items(done).filterIsInstance<ChatItem.ToolGroup>().single()
        assertFalse(g.expanded)
        assertEquals("Bash ×2 · 1 failed", g.summary)
        assertEquals(ToolStatusUi.Error, g.status)
    }

    @Test
    fun userToggleWinsOverAutomaticState() {
        val live = Frames.reduce(Frames.agent(), *twoTools)
        val key = items(live).filterIsInstance<ChatItem.ToolGroup>().single().key
        val collapsed = items(live, FlattenOptions(groupToggles = mapOf(key to false)))
        assertFalse(collapsed.filterIsInstance<ChatItem.ToolGroup>().single().expanded)
        assertEquals(0, collapsed.count { it is ChatItem.ToolCard })

        val texted = Frames.reduce(live, """{"type":"text_delta","text":"Done."}""")
        val open = items(texted, FlattenOptions(groupToggles = mapOf(key to true)))
        assertEquals(2, open.count { it is ChatItem.ToolCard })
    }

    @Test
    fun historyGroupsStartCollapsedAndSingleToolsAreNeverGrouped() {
        val s = Fixtures.run("text_tool_interleaving.json")   // text / tool / text / tool / text
        assertTrue(items(s).none { it is ChatItem.ToolGroup })
        assertEquals(2, items(s).count { it is ChatItem.ToolCard })
        // A completed solo card is collapsed (mockups d, k); a live one is open (mockup j).
        assertTrue(items(s).filterIsInstance<ChatItem.ToolCard>().none { it.expanded })
        val live = Frames.reduce(Frames.agent(), twoTools[0], twoTools[1])
        assertTrue(items(live).filterIsInstance<ChatItem.ToolCard>().single().expanded)
    }

    @Test
    fun streamingTailIsTheOnlyChangingItem() {
        // A delta must change only the tail markdown item(s) (spec 14 §3.1).
        var s = Frames.reduce(Frames.agent(), """{"type":"status","status":"processing"}""")
        s = Frames.reduce(s, """{"type":"text_delta","text":"First paragraph.\n\nSecond"}""")
        val before = items(s)
        s = Frames.reduce(s, """{"type":"text_delta","text":" paragraph grows"}""")
        val after = items(s)
        val changed = after.filter { a -> before.none { it.key == a.key && it == a } }
        assertTrue("only the tail changed: $changed", changed.all { (it as? ChatItem.MdBlock)?.tail == true })
    }
}
