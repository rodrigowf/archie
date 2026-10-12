package com.assistant.archie.feature.toolcards

import com.assistant.core.model.SessionKind
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 12 §4.8 agent card text (plan §4.4 item 1) and the Gemini `invoke_agent` → `Task` mapping. */
class AgentRunTest {
    @Test
    fun lineSaysWhatItDoesOrHowItEnded() {
        assertEquals("Starting…", AgentRun("t", AgentRunStatus.Running).line())
        assertEquals("Running Grep", AgentRun("t", AgentRunStatus.Running, activity = "Grep").line())
        assertEquals("Found it", AgentRun("t", AgentRunStatus.Done, summary = "\nFound it\nmore").line())
        assertEquals("Failed · timeout", AgentRun("t", AgentRunStatus.Failed, summary = "timeout").line())
        assertEquals("Stopped", AgentRun("t", AgentRunStatus.Stopped).line())
    }

    @Test
    fun statsShowOnlyKnownParts() {
        val r = AgentRun("t", AgentRunStatus.Running, toolUses = 3, tokens = 12_400, startedAtMs = 1_000, background = true)
        assertEquals("3 tools · 12.4k tokens · 1:05 · background", r.stats(nowMs = 66_000))
        assertEquals("1 tool · 0:03", AgentRun("t", AgentRunStatus.Done, toolUses = 1, durationMs = 3_000).stats(0))
        assertEquals("", AgentRun("t", AgentRunStatus.Done).stats(0))
    }

    @Test
    fun geminiInvokeAgentIsATaskCard() {
        val t = ToolCatalog.resolve("invoke_agent", buildJsonObject { put("agent_name", "codebase_investigator"); put("objective", "Map the router") }, SessionKind.AGENT)
        assertEquals("Task", t.name)
        assertEquals(ToolBody.Task, t.spec.body)
        assertEquals("codebase_investigator: Map the router", t.summary)
    }
}
