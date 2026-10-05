package com.assistant.archie.feature.chat.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.ToolNameNormalizer
import com.assistant.core.design.ToolCategory
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.model.SessionKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the list needs to know about a tool call before it renders it: the category tile, the display
 * name, a one-line summary, and the [renderer] id that becomes the item's `contentType`
 * (`tool-bash`, `tool-edit`, `tool-generic`, spec 14 §3.1). Pure data; built off the main thread.
 */
@Immutable
data class ToolDescriptor(
    val category: ToolCategory,
    val icon: ImageVector,
    val name: String,
    val summary: String,
    val renderer: String,
    /** Open by default even outside the live tail (TodoWrite, web `defaultOpen: 'always'`); a user toggle still wins. */
    val defaultOpen: Boolean = false,
)

/**
 * The small built-in catalog behind [com.assistant.archie.feature.chat.ui.DefaultToolCardRenderer].
 * B-05's `ToolCatalog` (`:feature:toolcards`, the full port of `ToolUseBlock.tsx`) replaces it through
 * the [com.assistant.archie.feature.chat.ui.ToolCardRenderer] slot; this one only has to be honest:
 * a sensible tile, name and summary for the common tools, raw name otherwise.
 */
object BasicToolCatalog {
    fun describe(block: ToolBlock, kind: SessionKind): ToolDescriptor {
        val raw = block.toolName
        val mcp = raw.startsWith("mcp__")
        val base = if (mcp) raw.substringAfterLast("__") else raw
        val name = ToolNameNormalizer.normalize(base, kind)
        val input = block.toolInput
        val (category, renderer) = classify(name, raw, kind)
        return ToolDescriptor(category, iconFor(category), name, summary(name, input), renderer)
    }

    private fun classify(name: String, raw: String, kind: SessionKind): Pair<ToolCategory, String> = when {
        name == "Bash" || name == "BashOutput" || name == "KillShell" -> ToolCategory.Execute to "bash"
        name == "Edit" || name == "MultiEdit" || name == "replace" || name == "NotebookEdit" -> ToolCategory.Write to "edit"
        name == "Write" || (name == "write_file" && kind == SessionKind.ORCHESTRATOR) -> ToolCategory.Write to "write"
        name == "Read" || name == "ReadManyFiles" || name == "ListFiles" ||
            (name == "read_file" && kind == SessionKind.ORCHESTRATOR) -> ToolCategory.Read to "read"
        name == "Grep" || name == "Glob" || name == "WebSearch" || name == "search_history" ||
            name == "search_memory" || name == "list_history" -> ToolCategory.Search to "search"
        name == "WebFetch" || name == "navigate_page" || name == "new_page" -> ToolCategory.Navigate to "fetch"
        name == "TodoWrite" -> ToolCategory.Todo to "todo"
        name == "Task" || name == "Agent" -> ToolCategory.Task to "task"
        name == "ExitPlanMode" || name == "EnterPlanMode" -> ToolCategory.System to "plan"
        name == "run_script" || name == "evaluate_script" -> ToolCategory.Script to "script"
        name.contains("screenshot", ignoreCase = true) || name.contains("snapshot", ignoreCase = true) ->
            ToolCategory.Capture to "generic"
        name in INTERACT || raw.contains("chrome-devtools") -> ToolCategory.Interact to "generic"
        name.endsWith("agent_session") || name.endsWith("agent_sessions") || name == "respond_to_agent_permission" ->
            ToolCategory.Agent to "agent"
        else -> ToolCategory.System to "generic"
    }

    private val INTERACT = setOf("click", "fill", "fill_form", "hover", "drag", "press_key", "type_text", "upload_file", "handle_dialog")

    fun iconFor(category: ToolCategory): ImageVector = when (category) {
        ToolCategory.Read -> ArchieIcons.Description
        ToolCategory.Write -> ArchieIcons.EditDocument
        ToolCategory.Execute -> ArchieIcons.Terminal
        ToolCategory.Script -> ArchieIcons.Code
        ToolCategory.Navigate -> ArchieIcons.Explore
        ToolCategory.Capture -> ArchieIcons.ScreenshotMonitor
        ToolCategory.Interact -> ArchieIcons.TouchApp
        ToolCategory.Todo -> ArchieIcons.Checklist
        ToolCategory.Task -> ArchieIcons.Assignment
        ToolCategory.System -> ArchieIcons.Settings
        ToolCategory.Agent -> ArchieIcons.SmartToy
        ToolCategory.Search -> ArchieIcons.Search
    }

    private fun summary(name: String, input: JsonObject): String {
        fun s(key: String) = (input[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun path(key: String) = s(key)?.let(::shortPath)
        return when (name) {
            "Bash" -> s("command")?.lineSequence()?.firstOrNull()
            "Read", "Write", "Edit", "MultiEdit", "NotebookEdit", "read_file", "write_file" -> path("file_path") ?: path("path")
            "Grep" -> s("pattern")?.let { p -> "\"$p\"" + (path("path")?.let { " in $it" } ?: "") }
            "Glob" -> s("pattern")
            "WebFetch", "navigate_page", "new_page" -> s("url")?.removePrefix("https://")?.removePrefix("http://")
            "WebSearch", "search_history", "search_memory" -> s("query")
            "Task", "Agent" -> s("description")
            "TodoWrite" -> (input["todos"] as? JsonArray)?.let { todos ->
                val done = todos.count { (it as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull == "completed" }
                "$done of ${todos.size} done"
            }
            "run_script" -> listOfNotNull(s("name") ?: s("script"), s("args")).joinToString(" ").ifEmpty { null }
            "send_to_agent_session" -> s("message")?.lineSequence()?.firstOrNull()
            else -> null
        } ?: firstString(input) ?: ""
    }

    private fun firstString(input: JsonObject): String? =
        input.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.lineSequence()?.firstOrNull() }

    /** The last two path segments ("…/a/b.kt" → "a/b.kt"). */
    fun shortPath(p: String): String {
        val parts = p.trimEnd('/').split('/').filter { it.isNotEmpty() }
        return if (parts.size <= 2) p else parts.takeLast(2).joinToString("/")
    }
}
