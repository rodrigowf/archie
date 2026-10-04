package com.assistant.archie.feature.toolcards

import com.assistant.core.model.SessionKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tool name and input normalisation (spec 12 TC-4, inv02 F-05), ported from the web's
 * `frontend-next/src/features/tools/normalize.ts` (W-10).
 *
 * Qwen Code and Gemini CLI use snake_case names for their built-in tools; Claude Code uses
 * PascalCase. Names map to Claude's so the catalog handles one canonical set. **Context-aware:** in
 * an orchestrator conversation the orchestrator's own tools (`read_file`, `write_file`, …) are
 * resolved *before* the Qwen mapping, so they keep their own renderers (fixes inv02 §6.3 #15).
 */
object ToolNormalize {
    /** Qwen Code (packages/core/src/tools/tool-names.ts) and Gemini CLI built-ins → Claude names. */
    val SNAKE_TO_CLAUDE: Map<String, String> = mapOf(
        "read_file" to "Read",
        "write_file" to "Write",
        "edit" to "Edit",
        "replace" to "Edit",
        "run_shell_command" to "Bash",
        "grep_search" to "Grep",
        "search_file_content" to "Grep",
        "glob" to "Glob",
        "web_fetch" to "WebFetch",
        "google_web_search" to "WebSearch",
        "web_search" to "WebSearch",
        "todo_write" to "TodoWrite",
        "write_todos" to "TodoWrite",
        "agent" to "Task",
        "task" to "Task",
        "ask_user_question" to "AskUserQuestion",
        "exit_plan_mode" to "ExitPlanMode",
        "save_memory" to "SaveMemory",
        "tool_search" to "ToolSearch",
        "list_directory" to "ListFiles",
        "read_many_files" to "ReadManyFiles",
        "skill" to "Skill",
    )

    /** Claude Code aliases (newer CLIs call the subagent tool "Agent"). */
    val CLAUDE_ALIASES: Map<String, String> = mapOf("Agent" to "Task", "KillBash" to "KillShell")

    /** The orchestrator's own tool names (orchestrator/tools/\*.py). */
    val ORCHESTRATOR_TOOLS: Set<String> = setOf(
        "read_file", "write_file", "run_script",
        "list_agent_sessions", "open_agent_session", "close_agent_session", "read_agent_session",
        "send_to_agent_session", "interrupt_agent_session", "respond_to_agent_permission",
        "list_history", "search_history", "search_memory",
        "get_assistant_config", "update_assistant_config", "end_voice_session", "listen_recording",
    )

    /** Canonical tool name for a raw `tool_name` in a conversation of [kind]. */
    fun name(raw: String, kind: SessionKind = SessionKind.AGENT): String {
        if (kind == SessionKind.ORCHESTRATOR && raw in ORCHESTRATOR_TOOLS) return raw
        return SNAKE_TO_CLAUDE[raw] ?: CLAUDE_ALIASES[raw] ?: raw
    }

    /**
     * Maps provider-specific argument keys onto Claude's, so renderers read one shape. Returns the
     * same instance when nothing changes.
     */
    fun input(name: String, input: ToolInput): ToolInput {
        var out: LinkedHashMap<String, JsonElement>? = null
        fun cur(): Map<String, JsonElement> = out ?: input
        fun move(from: String, to: String) {
            val m = cur()
            if (m[to] == null && m[from] != null) {
                val next = LinkedHashMap(m)
                next[to] = m.getValue(from)
                next.remove(from)
                out = next
            }
        }
        when (name) {
            "Read", "Write", "Edit" -> {
                move("absolute_path", "file_path")
                move("path", "file_path") // an orchestrator read_file/write_file seen without context
            }
            "Grep" -> {
                move("include", "glob")
                move("dir_path", "path")
            }
            "Glob", "ListFiles" -> move("dir_path", "path")
            "TodoWrite" -> {
                val todos = cur()["todos"] as? JsonArray
                if (todos != null && todos.any { it is JsonObject && it["content"] == null && (it["description"] as? JsonPrimitive)?.isString == true }) {
                    val next = LinkedHashMap(cur())
                    next["todos"] = JsonArray(
                        todos.map { t ->
                            if (t is JsonObject && t["content"] == null) JsonObject(t + ("content" to (t["description"] ?: JsonPrimitive("")))) else t
                        },
                    )
                    out = next
                }
            }
        }
        return out?.let { JsonObject(it) } ?: input
    }
}

/** A tool call after normalisation: canonical [name] (catalog key), Claude-shaped [input]. */
data class NormalizedTool(val name: String, val input: ToolInput, val rawName: String)

fun normalizeTool(rawName: String, rawInput: ToolInput, kind: SessionKind = SessionKind.AGENT): NormalizedTool {
    val name = ToolNormalize.name(rawName, kind)
    return NormalizedTool(name, ToolNormalize.input(name, rawInput), rawName)
}
