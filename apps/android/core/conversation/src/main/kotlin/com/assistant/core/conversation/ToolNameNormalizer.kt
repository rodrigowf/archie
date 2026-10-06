package com.assistant.core.conversation

import com.assistant.core.model.SessionKind

/**
 * Display names for tool cards (inv02 F-05, `frontend/src/components/ToolUseBlock.tsx:58-93`).
 *
 * Qwen reports snake_case tool names; they map to the Claude names so one renderer serves both.
 * TC-4 / W-15: orchestrator conversations are resolved **before** that mapping, so the
 * orchestrator's own `read_file` / `write_file` keep their own renderers instead of becoming
 * `Read` / `Write` (which need `file_path` and would fall back to generic JSON).
 *
 * The reducer keeps the raw wire name on [ToolBlock.toolName]; only presentation normalises.
 */
object ToolNameNormalizer {
    /** Source: `packages/core/src/tools/tool-names.ts` in `@qwen-code/qwen-code`. */
    val QWEN_TO_CLAUDE: Map<String, String> = mapOf(
        "read_file" to "Read",
        "write_file" to "Write",
        "edit" to "Edit",
        "run_shell_command" to "Bash",
        "grep_search" to "Grep",
        "glob" to "Glob",
        "web_fetch" to "WebFetch",
        "todo_write" to "TodoWrite",
        "agent" to "Task",
        "ask_user_question" to "AskUserQuestion",
        "exit_plan_mode" to "ExitPlanMode",
        "save_memory" to "SaveMemory",
        "tool_search" to "ToolSearch",
        "list_directory" to "ListFiles",
        "read_many_files" to "ReadManyFiles",
    )

    /** The orchestrator's own tools (`orchestrator/tools/` modules). */
    val ORCHESTRATOR_TOOLS: Set<String> = setOf(
        "list_agent_sessions", "open_agent_session", "close_agent_session", "read_agent_session",
        "send_to_agent_session", "interrupt_agent_session", "respond_to_agent_permission",
        "list_history", "search_history", "read_conversation", "search_memory",
        "read_file", "write_file", "run_script",
        "get_assistant_config", "update_assistant_config",
        "end_voice_session", "listen_recording",
    )

    /** The name a renderer should dispatch on, for a tool shown in a conversation of [kind]. */
    fun normalize(name: String, kind: SessionKind): String =
        if (kind == SessionKind.ORCHESTRATOR) name else QWEN_TO_CLAUDE[name] ?: name

    /**
     * Qwen inputs that use different keys than Claude's equivalents. All current mappings share the
     * Claude keys (`grep_search` = Grep's `pattern`/`path`; `web_fetch` only adds `prompt`), so this
     * is the identity, kept as the single place to add one.
     */
    fun <T> normalizeInput(@Suppress("UNUSED_PARAMETER") originalName: String, input: T): T = input
}
