package com.assistant.archie.feature.toolcards

import com.assistant.core.conversation.ToolBlock
import com.assistant.core.design.ToolCategory
import com.assistant.core.model.SessionKind
import java.util.concurrent.ConcurrentHashMap

/*
 * Tool catalog (spec 14 §3.4): canonical tool name → category (token colour), icon, header label +
 * summary, expanded body, default expansion. A card-for-card port of the web registry
 * (`frontend/src/features/tools/registry.ts`, W-10), including its deviations from inv02 F-05:
 * WebFetch is a navigation; Grep/Glob/WebSearch are searches; chrome input actions are
 * interactions; run_script is a script; orchestrator `read_file`/`write_file` resolve before the
 * Qwen/Gemini mapping.
 *
 * The header reads `<label> <summary>`: the label is the tool (category-coloured), the summary its
 * argument in mono, e.g. `Grep  "_voice" in orchestrator/`. [ResolvedTool.headline] joins them.
 */

/** The expanded part of a card. One per web renderer (`renderers/` in the web tools feature). */
enum class ToolBody(val id: String) {
    Read("read"),
    OrchestratorRead("orchestrator-read"),
    Write("write"),
    OrchestratorWrite("orchestrator-write"),
    Edit("edit"),
    MultiEdit("multi-edit"),
    NotebookEdit("notebook-edit"),
    Glob("glob"),
    Grep("grep"),
    ListFiles("list-files"),
    WebFetch("web-fetch"),
    WebSearch("web-search"),
    Bash("bash"),
    ShellId("shell-id"),
    Skill("skill"),
    Plan("plan"),
    Todo("todo"),
    Task("task"),
    AskUserQuestion("ask-user-question"),
    RunScript("run-script"),
    EvaluateScript("evaluate-script"),
    SendToAgent("send-to-agent"),
    AgentSession("agent-session"),
    Search("search"),
    Generic("generic"),
}

/** `Live`: open while the card is in the live tail; `Always`: open by default, still collapsible. */
enum class DefaultOpen { Live, Always }

class ToolSpec(
    val category: ToolCategory,
    /** Material Symbols name (web parity), resolved by `ArchieIcons.named`. */
    val icon: String,
    /** Header name, category-coloured ("Read", "Click", "send_to_agent_session"). */
    val label: (ToolInput) -> String,
    /** Header argument, mono ("…/src/a.ts", `"query"`); may be empty. */
    val summary: (ToolInput) -> String,
    val body: ToolBody = ToolBody.Generic,
    val defaultOpen: DefaultOpen = DefaultOpen.Live,
    /** Output-region text while running without output (default "Running…"). */
    val runningText: String? = null,
    /** The header shows the diff stat ("+4 −1") before the status (Edit). */
    val diffMeta: Boolean = false,
)

/** A block's tool after normalisation and lookup. */
data class ResolvedTool(
    val name: String,
    val rawName: String,
    val input: ToolInput,
    val spec: ToolSpec,
    val label: String,
    val summary: String,
) {
    /** One-line sentence for accessible names and group summaries: `Grep "x" in src/`. */
    val headline: String get() = if (summary.isNotEmpty()) "$label $summary" else label
}

object ToolCatalog {
    private fun fixed(s: String): (ToolInput) -> String = { s }
    private fun path(key: String): (ToolInput) -> String = { i -> i.str(key)?.let(::shortPath) ?: "" }
    private fun quoted(key: String): (ToolInput) -> String = { i -> i.str(key)?.takeIf { it.isNotEmpty() }?.let { "\"${firstLine(it, 80)}\"" } ?: "" }
    private val session: (ToolInput) -> String = { i -> i.str("session_id")?.takeIf { it.isNotEmpty() }?.let { "session ${shortId(it)}" } ?: "" }

    private fun spec(
        category: ToolCategory,
        icon: String,
        label: String,
        summary: (ToolInput) -> String,
        body: ToolBody = ToolBody.Generic,
        defaultOpen: DefaultOpen = DefaultOpen.Live,
        runningText: String? = null,
        diffMeta: Boolean = false,
    ) = ToolSpec(category, icon, fixed(label), summary, body, defaultOpen, runningText, diffMeta)

    private fun grepSummary(i: ToolInput): String {
        val pattern = quoted("pattern")(i)
        val where = i.str("path")
        return if (!where.isNullOrEmpty()) "$pattern in ${shortPath(where)}" else pattern
    }

    private fun globSummary(i: ToolInput): String {
        val pattern = i.str("pattern") ?: ""
        val where = i.str("path")
        return if (!where.isNullOrEmpty()) "$pattern in ${shortPath(where)}" else pattern
    }

    private fun taskSummary(i: ToolInput): String {
        val d = i.str("description") ?: ""
        val agent = i.str("subagent_type")
        return if (!agent.isNullOrEmpty() && agent != "general-purpose") "$agent: $d" else d
    }

    private fun openSessionSummary(i: ToolInput): String {
        val resume = i.str("resume_sdk_id")
        if (!resume.isNullOrEmpty()) return "resume ${shortId(resume)}"
        return i.str("title") ?: ""
    }

    /** `Bash`: the tool's own `description` when present (the live-check finding), else the command. */
    private fun bashSummary(i: ToolInput): String = i.str("description") ?: firstLine(i.str("command") ?: "")

    private fun skillSummary(i: ToolInput): String {
        val s = i.str("skill") ?: i.str("command")
        return if (!s.isNullOrEmpty()) "/${s.removePrefix("/")}" else ""
    }

    private fun listenSummary(i: ToolInput): String {
        val s = i.num("start_ms")
        val e = i.num("end_ms")
        return if (s != null && e != null) "${jsRound(s / 1000)}–${jsRound(e / 1000)} s" else ""
    }

    private fun scriptSummary(i: ToolInput): String = "${baseName(i.str("script") ?: "")} ${scriptArgs(i)}".trim()

    /** Built-in and orchestrator tools by canonical name (web `TOOL_SPECS`). */
    val TOOL_SPECS: Map<String, ToolSpec> = linkedMapOf(
        // read
        "Read" to spec(ToolCategory.Read, "description", "Read", path("file_path"), ToolBody.Read),
        "Glob" to spec(ToolCategory.Search, "search", "Glob", ::globSummary, ToolBody.Glob),
        "Grep" to spec(ToolCategory.Search, "search", "Grep", ::grepSummary, ToolBody.Grep),
        "WebSearch" to spec(ToolCategory.Search, "search", "WebSearch", quoted("query"), ToolBody.WebSearch),
        "ListFiles" to spec(ToolCategory.Read, "folder_open", "ListFiles", path("path"), ToolBody.ListFiles),
        "ReadManyFiles" to spec(ToolCategory.Read, "description", "ReadManyFiles", { i -> "${i.arr("paths")?.size ?: 0} files" }, ToolBody.ListFiles),
        // navigate (mockups: WebFetch is a navigation, "explore")
        "WebFetch" to spec(ToolCategory.Navigate, "explore", "WebFetch", { i -> stripScheme(i.str("url") ?: "") }, ToolBody.WebFetch, runningText = "Waiting for response…"),
        // write
        "Write" to spec(ToolCategory.Write, "code", "Write", path("file_path"), ToolBody.Write),
        "Edit" to spec(ToolCategory.Write, "edit_document", "Edit", path("file_path"), ToolBody.Edit, diffMeta = true),
        "MultiEdit" to spec(ToolCategory.Write, "edit_document", "MultiEdit", path("file_path"), ToolBody.MultiEdit),
        "NotebookEdit" to spec(ToolCategory.Write, "book", "NotebookEdit", path("notebook_path"), ToolBody.NotebookEdit),
        // todo / task
        "TodoWrite" to spec(ToolCategory.Todo, "checklist", "TodoWrite", ::todoProgress, ToolBody.Todo, defaultOpen = DefaultOpen.Always),
        "Task" to spec(ToolCategory.Task, "assignment", "Task", ::taskSummary, ToolBody.Task),
        // execute
        "Bash" to spec(ToolCategory.Execute, "terminal", "Bash", ::bashSummary, ToolBody.Bash),
        "BashOutput" to spec(ToolCategory.Execute, "terminal", "BashOutput", { i -> i.str("bash_id") ?: "" }, ToolBody.ShellId),
        "KillShell" to spec(ToolCategory.Execute, "stop", "KillShell", { i -> i.str("shell_id") ?: i.str("bash_id") ?: "" }, ToolBody.ShellId),
        "Skill" to spec(ToolCategory.Execute, "star_shine", "Skill", ::skillSummary, ToolBody.Skill),
        "EnterPlanMode" to spec(ToolCategory.Execute, "edit_note", "EnterPlanMode", fixed("Enter plan mode")),
        "ExitPlanMode" to spec(ToolCategory.Execute, "edit_note", "ExitPlanMode", { i -> firstLine(i.str("plan") ?: "Exit plan mode") }, ToolBody.Plan),
        // interact
        "AskUserQuestion" to spec(
            ToolCategory.Interact, "help", "AskUserQuestion",
            { i -> firstLine(parseQuestions(i).firstOrNull()?.question ?: "Ask user", 80) }, ToolBody.AskUserQuestion,
        ),
        // system (Qwen / Gemini extras)
        "SaveMemory" to spec(ToolCategory.System, "book_2", "SaveMemory", { i -> firstLine(i.str("fact") ?: "") }),
        "ToolSearch" to spec(ToolCategory.Search, "search", "ToolSearch", quoted("query")),

        // Orchestrator tools (resolved before the Qwen mapping in orchestrator conversations, TC-4)
        "read_file" to spec(ToolCategory.Read, "description", "read_file", path("path"), ToolBody.OrchestratorRead),
        "write_file" to spec(ToolCategory.Write, "code", "write_file", path("path"), ToolBody.OrchestratorWrite),
        "run_script" to spec(ToolCategory.Script, "code", "run_script", ::scriptSummary, ToolBody.RunScript),
        "list_agent_sessions" to spec(ToolCategory.Agent, "list", "list_agent_sessions", fixed("List active sessions"), ToolBody.AgentSession),
        "open_agent_session" to spec(ToolCategory.Agent, "open_in_new", "open_agent_session", ::openSessionSummary, ToolBody.AgentSession),
        "close_agent_session" to spec(ToolCategory.Agent, "close", "close_agent_session", session, ToolBody.AgentSession),
        "read_agent_session" to spec(ToolCategory.Agent, "visibility", "read_agent_session", session, ToolBody.AgentSession),
        "send_to_agent_session" to spec(ToolCategory.Agent, "smart_toy", "send_to_agent_session", { i -> firstLine(i.str("message") ?: "") }, ToolBody.SendToAgent),
        "interrupt_agent_session" to spec(ToolCategory.Agent, "stop", "interrupt_agent_session", session, ToolBody.AgentSession),
        "respond_to_agent_permission" to spec(ToolCategory.Agent, "smart_toy", "respond_to_agent_permission", { i -> i.str("decision") ?: "" }, ToolBody.AgentSession),
        "list_history" to spec(ToolCategory.Agent, "history", "list_history", fixed("List session history"), ToolBody.AgentSession),
        "search_history" to spec(ToolCategory.Search, "search", "search_history", quoted("query"), ToolBody.Search),
        "search_memory" to spec(ToolCategory.Search, "search", "search_memory", quoted("query"), ToolBody.Search),
        "get_assistant_config" to spec(ToolCategory.System, "tune", "get_assistant_config", fixed("")),
        "update_assistant_config" to spec(ToolCategory.System, "tune", "update_assistant_config", { i -> i.keys.joinToString(", ") }),
        "end_voice_session" to spec(ToolCategory.System, "call_end", "end_voice_session", { i -> i.str("reason") ?: "" }),
        "listen_recording" to spec(ToolCategory.System, "hearing", "listen_recording", ::listenSummary),
    )

    /* ---------- chrome-devtools MCP (~25 phrasings, inv02 F-05) ---------- */

    const val CHROME: String = "mcp__chrome-devtools__"

    private class ChromeSpec(val category: ToolCategory, val icon: String, val label: (ToolInput) -> String, val summary: (ToolInput) -> String)

    private val ref: (ToolInput) -> String = { i -> i.str("uid")?.takeIf { it.isNotEmpty() }?.let { "ref $it" } ?: "" }
    private val none: (ToolInput) -> String = { "" }
    private fun hashId(key: String): (ToolInput) -> String = { i -> i.str(key)?.takeIf { it.isNotEmpty() }?.let { "#$it" } ?: "" }
    private fun chrome(category: ToolCategory, icon: String, label: String, summary: (ToolInput) -> String) = ChromeSpec(category, icon, fixed(label), summary)
    private fun isHistoryNav(i: ToolInput) = i.isStr("type", "reload") || i.isStr("type", "back") || i.isStr("type", "forward")

    private val CHROME_SPECS: Map<String, ChromeSpec> = linkedMapOf(
        "navigate_page" to ChromeSpec(
            ToolCategory.Navigate, "explore",
            { i ->
                when {
                    i.isStr("type", "reload") -> "Reload page"
                    i.isStr("type", "back") -> "Go back"
                    i.isStr("type", "forward") -> "Go forward"
                    else -> "Navigate"
                }
            },
            { i -> if (isHistoryNav(i)) "" else stripScheme(i.str("url") ?: "") },
        ),
        "new_page" to chrome(ToolCategory.Navigate, "open_in_new", "New page") { i -> stripScheme(i.str("url") ?: "") },
        "close_page" to chrome(ToolCategory.Navigate, "close", "Close page", hashId("pageId")),
        "select_page" to chrome(ToolCategory.Navigate, "explore", "Select page", hashId("pageId")),
        "resize_page" to chrome(ToolCategory.Navigate, "explore", "Resize") { i -> "${i.str("width") ?: "?"}×${i.str("height") ?: "?"}" },
        "wait_for" to chrome(ToolCategory.Navigate, "hourglass_top", "Wait for", quoted("text")),
        // mockups: Click is an interaction ("touch_app")
        "click" to ChromeSpec(ToolCategory.Interact, "touch_app", { i -> if (i.bool("dblClick") == true) "Double click" else "Click" }, ref),
        "hover" to chrome(ToolCategory.Interact, "touch_app", "Hover", ref),
        "drag" to chrome(ToolCategory.Interact, "touch_app", "Drag") { i ->
            val from = i.str("from_uid")
            val to = i.str("to_uid")
            if (!from.isNullOrEmpty() && !to.isNullOrEmpty()) "ref $from → ref $to" else ""
        },
        "fill" to chrome(ToolCategory.Interact, "keyboard", "Fill input", ref),
        "fill_form" to chrome(ToolCategory.Interact, "keyboard", "Fill form") { i -> "${i.arr("elements")?.size ?: 0} fields" },
        "press_key" to chrome(ToolCategory.Interact, "keyboard", "Press") { i -> i.str("key") ?: "" },
        "handle_dialog" to ChromeSpec(ToolCategory.Interact, "touch_app", { i -> if (i.isStr("action", "accept")) "Accept dialog" else "Dismiss dialog" }, none),
        "upload_file" to chrome(ToolCategory.Interact, "upload_file", "Upload file") { i -> baseName(i.str("filePath") ?: "") },
        "take_screenshot" to chrome(ToolCategory.Capture, "screenshot_monitor", "Screenshot") { i ->
            val file = i.str("filePath")
            when {
                !file.isNullOrEmpty() -> baseName(file)
                i.bool("fullPage") == true -> "full page"
                else -> ref(i)
            }
        },
        "take_snapshot" to chrome(ToolCategory.Capture, "content_copy", "Snapshot", none),
        "performance_start_trace" to chrome(ToolCategory.Capture, "speed", "Start trace", none),
        "performance_stop_trace" to chrome(ToolCategory.Capture, "speed", "Stop trace", none),
        "performance_analyze_insight" to chrome(ToolCategory.Capture, "speed", "Analyze insight") { i -> i.str("insightName") ?: "" },
        "evaluate_script" to chrome(ToolCategory.Script, "bolt", "Run script") { i -> firstLine(i.str("function") ?: "") },
        "list_console_messages" to chrome(ToolCategory.Read, "list", "List console", none),
        "get_console_message" to chrome(ToolCategory.Read, "list", "Get console message", hashId("msgid")),
        "list_network_requests" to chrome(ToolCategory.Read, "network_check", "List network", none),
        "get_network_request" to chrome(ToolCategory.Read, "network_check", "Get network request", hashId("reqid")),
        "list_pages" to chrome(ToolCategory.Read, "list", "List pages", none),
        "emulate" to chrome(ToolCategory.Read, "mobile", "Emulate device") { i -> firstStringArg(i) },
    )

    /** The chrome-devtools actions with a dedicated phrasing (parity tests enumerate them). */
    val CHROME_ACTIONS: Set<String> get() = CHROME_SPECS.keys

    private fun chromeSpec(action: String): ToolSpec {
        val c = CHROME_SPECS[action] ?: return spec(ToolCategory.System, "build", action.replace('_', ' '), { i -> firstStringArg(i) })
        return ToolSpec(c.category, c.icon, c.label, c.summary, if (action == "evaluate_script") ToolBody.EvaluateScript else ToolBody.Generic)
    }

    private val cache = ConcurrentHashMap<String, ToolSpec>()

    /** The spec of a canonical tool name: built-ins, orchestrator tools, chrome-devtools, generic MCP, default. */
    fun spec(name: String): ToolSpec {
        TOOL_SPECS[name]?.let { return it }
        cache[name]?.let { return it }
        val s = when {
            name.startsWith(CHROME) -> chromeSpec(name.substring(CHROME.length))
            name.startsWith("mcp__") -> {
                val parts = name.split("__")
                val tool = if (parts.size >= 3) parts.drop(2).joinToString("__") else name
                spec(ToolCategory.System, "build", tool.replace('_', ' '), { i -> firstStringArg(i) })
            }
            else -> spec(ToolCategory.System, "build", name, { i -> firstStringArg(i) })
        }
        if (cache.size > 512) cache.clear()
        cache[name] = s
        return s
    }

    /** Normalise + look up a tool call (context-aware for orchestrator conversations). */
    fun resolve(rawName: String, rawInput: ToolInput, kind: SessionKind = SessionKind.AGENT): ResolvedTool {
        val (name, input) = normalizeTool(rawName, rawInput, kind)
        val s = spec(name)
        var label: String
        var summary: String
        try {
            label = s.label(input).ifEmpty { name }
            summary = s.summary(input)
        } catch (_: RuntimeException) {
            label = name
            summary = ""
        }
        return ResolvedTool(name, rawName, input, s, label, summary)
    }

    fun resolve(block: ToolBlock, kind: SessionKind = SessionKind.AGENT): ResolvedTool = resolve(block.toolName, block.toolInput, kind)
}
