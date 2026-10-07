package com.assistant.archie.feature.toolcards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryResultsTest {
    private val search = """
        {"query":"turin","sessions":[
          {"session_id":"96a377c7-8910-450b-88b9-5fa4889a94c7","title":"qvcm and bicameral mind",
           "started_at":"2026-06-20T10:00:00Z","relevance":"strong","copies":["a","b"],
           "hits":[{"turn":40,"role":"user","date":"2026-06-21T12:04:00Z","match":"keyword","text":"ancient rituals"}]},
          {"session_id":"x","title":null,"relevance":"weak","note":"mostly about searching","hits":[]}
        ]}
    """.trimIndent()

    @Test
    fun parsesSessionGroupedSearch() {
        val r = parseHistorySearch(search)!!
        assertEquals(listOf("qvcm and bicameral mind", null), r.sessions.map { it.title })
        assertEquals("2026-06-20 · strong · 96a377c7 · +2 copies", historySessionMeta(r.sessions[0]))
        assertEquals("#40 user · 2026-06-21 12:04", historyTurnHead(r.sessions[0].hits[0]))
        assertEquals("mostly about searching", r.sessions[1].note)
    }

    @Test
    fun otherShapesAreNotParsed() {
        assertNull(parseHistorySearch("""{"results": []}"""))
        assertNull(parseHistorySearch("3 matches"))
        assertNull(parseConversationRead("nope"))
    }

    @Test
    fun parsesConversationWindowAndErrors() {
        val r = parseConversationRead("""{"title":"t","total_turns":9,"turns":[{"turn":3,"role":"assistant","text":"hi"}]}""")!!
        assertEquals(listOf(HistoryTurn(3, "assistant", null, "hi")), r.turns)
        assertEquals(9, r.total)
        assertEquals("No conversation file", parseConversationRead("""{"error":"No conversation file"}""")!!.error)
    }
}
