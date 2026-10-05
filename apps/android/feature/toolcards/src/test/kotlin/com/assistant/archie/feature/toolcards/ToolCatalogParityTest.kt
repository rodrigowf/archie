package com.assistant.archie.feature.toolcards

import com.assistant.core.conversation.BlockOrigin
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.ToolStatus
import com.assistant.core.design.ToolCategory
import com.assistant.core.model.SessionKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-05 DoD (spec 14 §7): the Android catalog matches the web's tool cards (W-10) card for card.
 *
 * `web-tool-oracle.json` is produced by running the committed web registry and renderers on 164 tool
 * calls (`parity/run-web-oracle.sh`): every row of inv02 F-05, every W-10 registry entry and chrome
 * phrasing, the Qwen / Gemini names and argument keys, and hostile inputs. This test replays the
 * same calls through [ToolCatalog] and [ToolBodies] and expects the same category, icon, label,
 * summary, renderer, default expansion, running text, header meta, field rows, code views, diffs,
 * todo lines and output-region state; then the output-region rules (TC-1/TC-2, R7) per state.
 */
class ToolCatalogParityTest {
    private val oracle: JsonObject by lazy {
        val text = requireNotNull(javaClass.classLoader?.getResource("web-tool-oracle.json")) { "web-tool-oracle.json missing" }.readText()
        Json.parseToJsonElement(text).jsonObject
    }
    private val tools get() = oracle.getValue("tools").jsonArray.map { it.jsonObject }

    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun kindOf(row: JsonObject) = if (row.s("kind") == "orchestrator") SessionKind.ORCHESTRATOR else SessionKind.AGENT

    private fun block(row: JsonObject, status: ToolStatus = ToolStatus.DONE, output: String? = row.s("output"), origin: BlockOrigin = BlockOrigin.LIVE) =
        ToolBlock(
            id = "b1",
            toolUseId = "toolu_1",
            toolName = row.s("name")!!,
            toolInput = row.getValue("input").jsonObject,
            status = status,
            output = output,
            origin = origin,
        )

    private fun label(row: JsonObject) = "${row.s("name")} ${row["input"]} (${row.s("kind")})"

    @Test
    fun headerMatchesWebForEveryRow() {
        val failures = mutableListOf<String>()
        for (row in tools) {
            val w = row.getValue("resolved").jsonObject
            val t = ToolCatalog.resolve(row.s("name")!!, row.getValue("input").jsonObject, kindOf(row))
            fun check(what: String, web: String?, android: String?) {
                if (web != android) failures += "${label(row)}: $what web=<$web> android=<$android>"
            }
            check("name", w.s("name"), t.name)
            check("category", w.s("category"), t.spec.category.name.lowercase())
            check("icon", w.s("icon"), t.spec.icon)
            check("label", w.s("label"), t.label)
            check("summary", w.s("summary"), t.summary)
            check("renderer", w.s("body"), "${t.spec.body.name}Body")
            check("defaultOpen", w.s("defaultOpen"), t.spec.defaultOpen.name.lowercase())
            check("runningText", w.s("runningText"), t.spec.runningText)
            check("meta", w.s("meta"), if (t.spec.diffMeta) "EditMeta" else null)
        }
        assertTrue(failures.joinToString("\n", "${failures.size} mismatches:\n"), failures.isEmpty())
    }

    @Test
    fun bodyMatchesWebForEveryRow() {
        val failures = mutableListOf<String>()
        for (row in tools) {
            val w = row.getValue("body").jsonObject
            val t = ToolCatalog.resolve(row.s("name")!!, row.getValue("input").jsonObject, kindOf(row))
            val b = block(row)
            val parts = ToolBodies.parts(t, b)
            fun check(what: String, web: Any?, android: Any?) {
                if (web != android) failures += "${label(row)}: $what\n   web=$web\n   android=$android"
            }
            val webFields = w.getValue("fields").jsonArray.map { r -> r.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.content } }
            val fields = parts.filterIsInstance<BodyPart.Fields>().flatMap { it.rows }.map { it.label to it.value }
            check("fields", webFields, fields)
            val webCodes = w.getValue("codes").jsonArray.map { it.jsonPrimitive.content }
            check("code views", webCodes, parts.filterIsInstance<BodyPart.Code>().map { it.label })
            check("diffs", w.getValue("diffs").jsonPrimitive.content.toInt(), parts.count { it is BodyPart.Diff })
            val webLi = w.getValue("todos").jsonArray.map { it.jsonPrimitive.content }
            val li = parts.flatMap { p ->
                when (p) {
                    is BodyPart.Todos -> p.items.map { "${it.status.spoken}: ${it.text}" }
                    is BodyPart.Questions -> p.items.flatMap { q -> q.options.map { o -> o.label + (o.description?.let { d -> " — $d" } ?: "") } }
                    else -> emptyList()
                }
            }
            // `li` of the checklist / options only; markdown lists (plan, report) are the markdown engine's.
            if (t.spec.body == ToolBody.Todo || t.spec.body == ToolBody.AskUserQuestion) check("list items", webLi, li)
            check("output region", w.s("outputKind"), webKind(ToolOutputRules.kind(b)))
            check("output present", true, parts.last() is BodyPart.Output)
        }
        assertTrue(failures.joinToString("\n", "${failures.size} mismatches:\n"), failures.isEmpty())
    }

    @Test
    fun outputRegionRulesMatchWeb() {
        val failures = mutableListOf<String>()
        for (r in oracle.getValue("outputRules").jsonArray.map { it.jsonObject }) {
            val status = ToolStatus.entries.first { it.wire == r.s("status") }
            val origin = if (r.s("origin") == "history") BlockOrigin.HISTORY else BlockOrigin.LIVE
            val b = block(r, status, r.s("output"), origin)
            val t = ToolCatalog.resolve(b, kindOf(r))
            val kind = ToolOutputRules.kind(b)
            val tag = "${r.s("name")} ${r.s("state")}"
            if (webKind(kind) != r.s("outputKind")) failures += "$tag: kind web=${r.s("outputKind")} android=$kind"
            val text = r.s("text")!!
            val placeholder = ToolOutputRules.placeholder(b, t.spec.runningText ?: "Running…")
            when (kind) {
                OutputKind.Running -> if (!text.startsWith(placeholder!!)) failures += "$tag: running text web=<$text> android=<$placeholder>"
                OutputKind.NoResult, OutputKind.Empty -> if (!text.startsWith(placeholder!!)) failures += "$tag: placeholder web=<$text> android=<$placeholder>"
                OutputKind.Output -> if (!text.startsWith(b.output!!)) failures += "$tag: output web=<$text>"
                OutputKind.Feed -> failures += "$tag: unexpected feed"
            }
            if (r.s("name") == "Bash") {
                val footer = ToolBodies.parts(t, b).filterIsInstance<BodyPart.Output>().single().footer?.text
                if (footer != r.s("exit")) failures += "$tag: exit web=${r.s("exit")} android=$footer"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun webKind(k: OutputKind): String = when (k) {
        OutputKind.Running -> "running"
        OutputKind.Feed -> "feed"
        OutputKind.NoResult -> "no-result"
        OutputKind.Empty -> "empty"
        OutputKind.Output -> "output"
    }

    /** Every W-10 registry entry, chrome phrasing, renderer and category is exercised by the oracle. */
    @Test
    fun oracleCoversTheWholeCatalog() {
        val resolved = tools.map { it.getValue("resolved").jsonObject }
        val names = resolved.map { it.s("name")!! }.toSet()
        val missingSpecs = ToolCatalog.TOOL_SPECS.keys - names
        assertTrue("registry entries without an oracle row: $missingSpecs", missingSpecs.isEmpty())
        val missingChrome = ToolCatalog.CHROME_ACTIONS.map { ToolCatalog.CHROME + it }.toSet() - names
        assertTrue("chrome phrasings without an oracle row: $missingChrome", missingChrome.isEmpty())
        val bodies = resolved.map { it.s("body")!! }.toSet()
        val missingBodies = ToolBody.entries.map { "${it.name}Body" }.toSet() - bodies
        assertTrue("renderers without an oracle row: $missingBodies", missingBodies.isEmpty())
        val categories = resolved.map { it.s("category")!! }.toSet()
        assertEquals(ToolCategory.entries.map { it.name.lowercase() }.toSet(), categories)
    }

    /** inv02 F-05: every tool of the category, summary and renderer tables has a row. */
    @Test
    fun everyF05RowIsCovered() {
        val raw = tools.map { it.s("name")!! }.toSet()
        val f05 = listOf(
            "Read", "Write", "Edit", "NotebookEdit", "Bash", "Glob", "Grep", "WebFetch", "WebSearch", "Task", "TodoWrite",
            "AskUserQuestion", "Skill", "EnterPlanMode", "ExitPlanMode",
            "list_agent_sessions", "open_agent_session", "close_agent_session", "read_agent_session", "send_to_agent_session",
            "interrupt_agent_session", "list_history", "search_history", "search_memory", "read_file", "write_file",
        ) + listOf(
            "list_console_messages", "get_console_message", "list_network_requests", "get_network_request", "list_pages", "emulate",
            "navigate_page", "click", "hover", "drag", "fill", "fill_form", "press_key", "handle_dialog", "new_page", "close_page",
            "select_page", "resize_page", "wait_for", "take_screenshot", "take_snapshot", "performance_start_trace",
            "performance_stop_trace", "performance_analyze_insight", "evaluate_script",
        ).map { ToolCatalog.CHROME + it }
        val missing = f05.toSet() - raw
        assertTrue("F-05 rows without a parity row: $missing", missing.isEmpty())
        assertTrue("generic MCP row", raw.any { it.startsWith("mcp__") && !it.startsWith(ToolCatalog.CHROME) })
        assertTrue("unknown-tool row", "SomethingNew" in raw)
        // Qwen / Gemini names (F-05 normalisation) are rows too.
        assertTrue(raw.containsAll(listOf("run_shell_command", "todo_write", "agent", "exit_plan_mode", "grep_search", "list_directory")))
    }

    /** Live-check finding (Jetson): the Bash summary is the tool's `description`, else the command. */
    @Test
    fun bashSummaryUsesDescription() {
        val withDesc = buildJsonObject {
            put("command", "/home/rodrigo/assistant/context/scripts/run.sh -m pytest tests/ -q")
            put("description", "Run the test suite")
        }
        assertEquals("Run the test suite", ToolCatalog.resolve("Bash", withDesc).summary)
        val bare = buildJsonObject { put("command", "git status --short\ngit log -1") }
        assertEquals("git status --short …", ToolCatalog.resolve("Bash", bare).summary)
        assertEquals("Check current date", ToolCatalog.resolve("run_shell_command", buildJsonObject { put("command", "date"); put("description", "Check current date") }).summary)
    }

    @Test
    fun orchestratorToolsResolveBeforeQwenMapping() {
        val input = buildJsonObject { put("path", "context/memory/MEMORY.md"); put("start_line", 1); put("end_line", 40) }
        val orch = ToolCatalog.resolve("read_file", input, SessionKind.ORCHESTRATOR)
        assertEquals("read_file", orch.name)
        assertEquals(ToolBody.OrchestratorRead, orch.spec.body)
        assertEquals("context/memory/MEMORY.md", orch.summary)
        val agent = ToolCatalog.resolve("read_file", buildJsonObject { put("path", "/a/b/c/d.md") }, SessionKind.AGENT)
        assertEquals("Read", agent.name)
        assertEquals("…/c/d.md", agent.summary)
        assertEquals("/a/b/c/d.md", agent.input.str("file_path"))
    }

    @Test
    fun normalizeInputReturnsSameInstanceWhenUnchanged() {
        val input = buildJsonObject { put("file_path", "/x") }
        assertTrue(ToolNormalize.input("Read", input) === input)
        assertTrue(normalizeTool("Bash", input).input === input)
        val todos = ToolNormalize.input("TodoWrite", buildJsonObject { put("todos", JsonArray(listOf(buildJsonObject { put("description", "a"); put("status", "pending") }))) })
        assertEquals("a", todos.getValue("todos").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content)
        // The orchestrator tool set is the same one :core:conversation knows (A-02).
        assertEquals(com.assistant.core.conversation.ToolNameNormalizer.ORCHESTRATOR_TOOLS, ToolNormalize.ORCHESTRATOR_TOOLS)
    }

    @Test
    fun hostileInputsNeverThrow() {
        val weird = buildJsonObject { put("command", 42); put("todos", "nope"); put("questions", JsonPrimitive(3)); put("edits", JsonNull) }
        for (name in ToolCatalog.TOOL_SPECS.keys + ToolCatalog.CHROME_ACTIONS.map { ToolCatalog.CHROME + it }) {
            val t = ToolCatalog.resolve(name, weird, SessionKind.ORCHESTRATOR)
            ToolBodies.parts(t, ToolBlock("b", "t", name, weird, ToolStatus.RUNNING))
        }
        assertEquals("42", ToolCatalog.resolve("Bash", weird).summary)
        assertEquals("No todos", ToolCatalog.resolve("TodoWrite", weird).summary)
    }
}
