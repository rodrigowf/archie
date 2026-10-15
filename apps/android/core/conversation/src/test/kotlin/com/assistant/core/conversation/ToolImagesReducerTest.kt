package com.assistant.core.conversation

import com.assistant.core.protocol.ContentBlockDto
import com.assistant.core.protocol.MessagePreviewDto
import com.assistant.core.protocol.ServerFrame
import com.assistant.core.protocol.ToolImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-10 paths the `tool_result_images` fixture does not reach: orphans, reconcile, subagent transcripts. */
class ToolImagesReducerTest {
    private val png = ToolImage("/api/tool-images/a.png", "image/png")
    private val imagesJson = Json.parseToJsonElement("""[{"url":"/api/tool-images/a.png","media_type":"image/png"}]""")

    private fun imageResult(id: String) = ServerFrame.ToolResult(id, JsonPrimitive(""), false, images = imagesJson)

    private fun imageResultLine(id: String) = MessagePreviewDto(
        "user", "", listOf(ContentBlockDto("tool_result", toolUseId = id, output = JsonPrimitive(""), images = imagesJson)),
    )

    private fun ConversationState.card(id: String): ToolBlock =
        entries.filterIsInstance<AssistantEntry>().flatMap { it.blocks }.filterIsInstance<ToolBlock>().single { it.toolUseId == id }

    @Test
    fun orphanImageResultIsNotReplacedByALaterEmptyOne() {
        val s = agent().send("go").on(processing(), imageResult("t1"), toolResult("t1", ""), toolUse("t1", "Read"))
        val card = s.card("t1")
        assertEquals(ToolStatus.DONE, card.status)
        assertEquals("", card.output)
        assertEquals(listOf(png), card.images)
    }

    @Test
    fun emptyLiveResultDoesNotWipeImagesButTextReplacesThemTogether() {
        val base = agent().send("go").on(processing(), toolUse("t1", "Read"), imageResult("t1"))
        assertEquals(listOf(png), base.on(toolResult("t1", "")).card("t1").images)
        val replaced = base.input(ConversationInput.HistoryPage(PageMode.RECONCILE, page(0, 1, resultLine("t1", "text"))))
        assertEquals("a live id-matched image result is not overridden by reconcile (R-5)", listOf(png), replaced.card("t1").images)
    }

    @Test
    fun reconcileFillsImagesOfANoResultCard() {
        val s = agent().send("go").on(processing(), toolUse("t1", "Read"), turnComplete())
        assertEquals(ToolStatus.NO_RESULT, s.card("t1").status)
        val r = s.input(ConversationInput.HistoryPage(PageMode.RECONCILE, page(0, 1, imageResultLine("t1"))))
        assertEquals(ToolStatus.DONE, r.card("t1").status)
        assertEquals(listOf(png), r.card("t1").images)
    }

    @Test
    fun subagentTranscriptCardsGetImagesLiveAndFromHistory() {
        val spawn = """{"subagent_type":"Explore","description":"Find it","prompt":"p"}"""
        var s = agent().send("go").on(processing(), toolUse("a1", "Agent", spawn))
            .on(ServerFrame.SubagentStarted("a1", null, "Explore", "Find it", false, "2026-10-12T12:00:01Z"))
        s = s.input(
            ConversationInput.OpenSubagent("a1"),
            ConversationInput.SubagentPage("a1", page(0, 2, assistantTool("h1", "Read"), imageResultLine("h1"))),
        )
        s = s.on(ServerFrame.SubagentEvent("a1", toolUse("g1", "Read")), ServerFrame.SubagentEvent("a1", imageResult("g1")))
        val conv = s.subagents["a1"]!!.conv!!
        assertEquals(listOf(png), conv.card("h1").images)
        assertEquals(listOf(png), conv.card("g1").images)
        assertTrue("the parent's own cards are untouched", s.card("a1").images.isEmpty())
    }
}
