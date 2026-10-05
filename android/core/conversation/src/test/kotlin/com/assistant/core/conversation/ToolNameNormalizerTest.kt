package com.assistant.core.conversation

import com.assistant.core.model.SessionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolNameNormalizerTest {
    @Test
    fun qwenNamesMapToClaudeNamesInAgentSessions() {
        val expected = mapOf(
            "read_file" to "Read", "write_file" to "Write", "edit" to "Edit", "run_shell_command" to "Bash",
            "grep_search" to "Grep", "glob" to "Glob", "web_fetch" to "WebFetch", "todo_write" to "TodoWrite",
            "agent" to "Task", "ask_user_question" to "AskUserQuestion", "exit_plan_mode" to "ExitPlanMode",
            "save_memory" to "SaveMemory", "tool_search" to "ToolSearch", "list_directory" to "ListFiles",
            "read_many_files" to "ReadManyFiles",
        )
        assertEquals(expected, ToolNameNormalizer.QWEN_TO_CLAUDE)
        for ((q, c) in expected) assertEquals(c, ToolNameNormalizer.normalize(q, SessionKind.AGENT))
    }

    @Test
    fun claudeAndUnknownNamesPassThrough() {
        for (n in listOf("Bash", "Read", "mcp__chrome-devtools__click", "Task", "something_new")) {
            assertEquals(n, ToolNameNormalizer.normalize(n, SessionKind.AGENT))
        }
    }

    @Test
    fun tc4_orchestratorToolsAreResolvedBeforeTheQwenMapping() {
        // W-15: the orchestrator's read_file must keep its own renderer, not become Read
        assertEquals("read_file", ToolNameNormalizer.normalize("read_file", SessionKind.ORCHESTRATOR))
        assertEquals("write_file", ToolNameNormalizer.normalize("write_file", SessionKind.ORCHESTRATOR))
        assertTrue("read_file" in ToolNameNormalizer.ORCHESTRATOR_TOOLS)
        for (t in ToolNameNormalizer.ORCHESTRATOR_TOOLS) assertEquals(t, ToolNameNormalizer.normalize(t, SessionKind.ORCHESTRATOR))
    }

    @Test
    fun theReducerKeepsTheRawWireName() {
        val s = agent(provider = com.assistant.core.model.HarnessProvider.QWEN).send("q").on(processing(), toolUse("t1", "run_shell_command"))
        assertEquals("run_shell_command", ((s.entries[1] as AssistantEntry).blocks[0] as ToolBlock).toolName)
    }
}
