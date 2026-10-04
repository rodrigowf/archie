package com.assistant.archie.feature.chat

import com.assistant.archie.feature.chat.ui.CatalogToolCardRenderer
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.design.ToolCategory
import com.assistant.core.model.SessionKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The B-05 plug-in: descriptors come from `:feature:toolcards`' catalog (web W-10 parity). */
class CatalogToolCardRendererTest {
    private fun block(name: String, input: String) = ToolBlock("b", "t", name, Json.parseToJsonElement(input).jsonObject)

    @Test fun bashSummaryIsTheDescription() {
        val d = CatalogToolCardRenderer.describe(block("Bash", """{"command":"/home/rodrigo/assistant/context/scripts/run.sh -m pytest","description":"Run the tests"}"""), SessionKind.AGENT)
        assertEquals("Bash", d.name)
        assertEquals("Run the tests", d.summary)
        assertEquals(ToolCategory.Execute, d.category)
        assertEquals("bash", d.renderer)
        assertFalse(d.defaultOpen)
    }

    @Test fun todoWriteOpensByDefaultAndOrchestratorToolsKeepTheirNames() {
        assertTrue(CatalogToolCardRenderer.describe(block("TodoWrite", """{"todos":[]}"""), SessionKind.AGENT).defaultOpen)
        val orch = CatalogToolCardRenderer.describe(block("read_file", """{"path":"context/memory/MEMORY.md"}"""), SessionKind.ORCHESTRATOR)
        assertEquals("read_file", orch.name)
        assertEquals("orchestrator-read", orch.renderer)
        assertEquals(ToolCategory.Navigate, CatalogToolCardRenderer.describe(block("WebFetch", """{"url":"https://a.dev"}"""), SessionKind.AGENT).category)
    }
}
