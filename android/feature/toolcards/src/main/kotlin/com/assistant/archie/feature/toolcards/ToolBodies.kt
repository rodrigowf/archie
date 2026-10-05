package com.assistant.archie.feature.toolcards

import com.assistant.core.conversation.BlockOrigin
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.ToolStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * What each renderer shows, as data. A port of the web bodies (`renderers/{exec,files,other,tasks}.tsx`)
 * split into a pure model ([ToolBodies.parts]) and the Compose views (`ui/ToolBodyView.kt`), so the
 * catalog parity test can check every renderer's fields on the JVM.
 */

/** One label/value row of the field list (web `Fields`). [href] makes the value a link. */
data class Field(val label: String, val value: String, val mono: Boolean = false, val href: String? = null)

/** How the output region draws a non-empty output. */
enum class OutputRender { Plain, Markdown, Script }

/** A line under the output ("exit 0"). */
data class OutputFooter(val text: String, val error: Boolean)

sealed interface BodyPart {
    data class Fields(val rows: List<Field>) : BodyPart
    /** A highlighted code view capped at [maxHeightDp] (Write 400, evaluate_script 200; spec 14 §3.4). */
    data class Code(val code: String, val lang: String?, val label: String, val maxHeightDp: Int = 400) : BodyPart
    /** Pre-formatted prose input (prompts, messages). */
    data class Pre(val text: String) : BodyPart
    /** The generic input view: pretty JSON. */
    data class Json(val text: String) : BodyPart
    data class Markdown(val text: String) : BodyPart
    data class Diff(val old: String, val new: String) : BodyPart
    data class Todos(val items: List<TodoItem>) : BodyPart
    data class Questions(val items: List<Question>) : BodyPart
    /** A dim one-line note ("No todos"). */
    data class Note(val text: String) : BodyPart
    /** The output region every card has (R7). */
    data class Output(val label: String = "Output", val render: OutputRender = OutputRender.Plain, val footer: OutputFooter? = null) : BodyPart
}

enum class TodoStatus(val wire: String, val spoken: String) {
    Pending("pending", "to do"),
    InProgress("in_progress", "in progress"),
    Completed("completed", "done"),
    Cancelled("cancelled", "cancelled"),
}

data class TodoItem(val content: String, val status: TodoStatus, val activeForm: String? = null) {
    /** In progress shows `activeForm` (falling back to the content). */
    val text: String get() = if (status == TodoStatus.InProgress && !activeForm.isNullOrEmpty()) activeForm else content
}

data class QuestionOption(val label: String, val description: String? = null)

data class Question(val question: String, val header: String?, val options: List<QuestionOption>, val multiSelect: Boolean)

fun parseTodos(input: ToolInput): List<TodoItem> =
    (input.arr("todos") ?: JsonArray(emptyList())).filterIsInstance<JsonObject>().map { t ->
        val s = (t["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val status = when (s) {
            "in_progress" -> TodoStatus.InProgress
            "completed" -> TodoStatus.Completed
            "cancelled" -> TodoStatus.Cancelled
            else -> TodoStatus.Pending
        }
        TodoItem(t.strictStr("content") ?: "", status, t.strictStr("activeForm"))
    }

/** "3 of 5 done" (spec 13 §3.6 progress), "No todos" when empty. */
fun todoProgress(input: ToolInput): String {
    val todos = parseTodos(input)
    if (todos.isEmpty()) return "No todos"
    return "${todos.count { it.status == TodoStatus.Completed }} of ${todos.size} done"
}

fun parseQuestions(input: ToolInput): List<Question> =
    (input.arr("questions") ?: JsonArray(emptyList())).filterIsInstance<JsonObject>().map { q ->
        Question(
            question = q.strictStr("question") ?: "",
            header = q.strictStr("header"),
            options = ((q["options"] as? JsonArray) ?: JsonArray(emptyList())).filterIsInstance<JsonObject>().map { o ->
                val l = o["label"]
                QuestionOption(
                    label = if (l == null || l is JsonNull) "" else jsString(l),
                    description = o.strictStr("description"),
                )
            },
            multiSelect = (q["multiSelect"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true,
        )
    }

/** `args.map(String).join(' ')`. */
fun scriptArgs(input: ToolInput): String = (input.arr("args") ?: JsonArray(emptyList())).joinToString(" ") { jsString(it) }

/** A field only when it is a JSON string (web `typeof v === 'string'`). */
private fun JsonObject.strictStr(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** "exit 0" / "exit 2" under a shell output; nothing while running or without a result. */
fun exitLabel(status: ToolStatus, output: String?): String? {
    if (status != ToolStatus.DONE && status != ToolStatus.ERROR) return null
    val code = if (!output.isNullOrEmpty()) parseExitCode(output) else null
    if (code != null) return "exit $code"
    return if (status == ToolStatus.DONE) "exit 0" else null
}

/** "lines a–b", "from line a", "first N lines" (`offset` is 0-based like Claude's Read). */
fun readRange(offset: Double?, limit: Double?): String? = when {
    offset != null && limit != null -> "lines ${jsNumber(offset + 1)}–${jsNumber(offset + limit)}"
    offset != null -> "from line ${jsNumber(offset + 1)}"
    limit != null -> "first ${jsNumber(limit)} lines"
    else -> null
}

/** Orchestrator read_file: 1-based inclusive start_line/end_line. */
fun lineRange(start: Double?, end: Double?): String? = when {
    start != null && end != null -> "lines ${jsNumber(start)}–${jsNumber(end)}"
    start != null -> "from line ${jsNumber(start)}"
    end != null -> "first ${jsNumber(end)} lines"
    else -> null
}

/** Grep options line (inv02 F-05): output mode unless the default, "line numbers", "±N context". */
fun grepOptions(input: ToolInput): String? {
    val mode = input.str("output_mode")
    val n = input.bool("-n")
    val ctx = input.num("context") ?: input.num("-C")
    if (mode == null && n == null && ctx == null) return null
    val parts = listOfNotNull(
        mode?.takeIf { it.isNotEmpty() && it != "files_with_matches" },
        if (n == true) "line numbers" else null,
        ctx?.let { "±${jsNumber(it)} context" },
    )
    return parts.joinToString(", ").ifEmpty { "default" }
}

/** run_script returns JSON `{exit_code, stdout, stderr}` (orchestrator/tools/run_script.py). */
data class ScriptResult(val exitCode: Int?, val stdout: String, val stderr: String)

fun parseScriptResult(output: String): ScriptResult? {
    val t = output.trim()
    if (!t.startsWith("{")) return null
    val v = runCatching { Json.parseToJsonElement(t) }.getOrNull() as? JsonObject ?: return null
    if ("stdout" !in v && "exit_code" !in v && "stderr" !in v) return null
    val code = (v["exit_code"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toInt()
    return ScriptResult(code, v.strictStr("stdout") ?: "", v.strictStr("stderr") ?: "")
}

private fun safeHref(url: String): String? = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) url else null

/** What the output region shows for a block (spec 12 TC-1/TC-2, R7). */
enum class OutputKind { Running, Feed, NoResult, Empty, Output }

object ToolOutputRules {
    fun kind(block: ToolBlock, hasFeed: Boolean = false): OutputKind {
        val has = !block.output.isNullOrEmpty()
        return when {
            has -> OutputKind.Output
            block.status == ToolStatus.RUNNING -> if (hasFeed) OutputKind.Feed else OutputKind.Running
            block.status == ToolStatus.NO_RESULT -> OutputKind.NoResult
            else -> OutputKind.Empty
        }
    }

    /** The placeholder text of a block without output, or null when it has output. */
    fun placeholder(block: ToolBlock, runningText: String = "Running…"): String? = when (kind(block)) {
        OutputKind.Output, OutputKind.Feed -> null
        OutputKind.Running -> runningText
        OutputKind.NoResult -> if (block.origin == BlockOrigin.HISTORY) "No output recorded" else "No output received"
        OutputKind.Empty -> if (block.status == ToolStatus.ERROR) "Failed with no output" else "No output"
    }
}

object ToolBodies {
    private fun fields(vararg rows: Field?): BodyPart.Fields = BodyPart.Fields(rows.filterNotNull().filter { it.value.isNotEmpty() })
    private fun f(label: String, value: String?, mono: Boolean = false): Field? = value?.let { Field(label, it, mono) }

    /** The parts of [tool]'s expanded body for [block] (the footer depends on its status/output). */
    fun parts(tool: ResolvedTool, block: ToolBlock): List<BodyPart> {
        val i = tool.input
        val out = BodyPart.Output()
        return when (tool.spec.body) {
            ToolBody.Bash -> {
                val command = i.str("command") ?: ""
                val exit = exitLabel(block.status, block.output)
                listOfNotNull(
                    fields(
                        f("Description", i.str("description")),
                        f("Directory", i.str("directory"), mono = true),
                        if (i.bool("run_in_background") == true || i.bool("is_background") == true) Field("Mode", "Background") else null,
                    ),
                    if (command.isNotEmpty()) BodyPart.Code(command, "bash", "Command") else null,
                    BodyPart.Output(footer = exit?.let { OutputFooter(it, block.status == ToolStatus.ERROR) }),
                )
            }
            ToolBody.ShellId -> listOf(fields(f("Shell", i.str("bash_id") ?: i.str("shell_id"), true), f("Filter", i.str("filter"), true)), out)
            ToolBody.RunScript -> listOf(
                fields(f("Script", i.str("script"), true), f("Args", scriptArgs(i), true)),
                BodyPart.Output(render = OutputRender.Script),
            )
            ToolBody.EvaluateScript -> {
                val fn = i.str("function")
                val args = (i.arr("args") ?: JsonArray(emptyList())).filterIsInstance<JsonObject>()
                    .map { a -> a["uid"]?.takeIf { it !is JsonNull }?.let(::jsString) ?: "" }
                    .filter { it.isNotEmpty() }
                listOfNotNull(
                    if (!fn.isNullOrEmpty()) BodyPart.Code(fn, "javascript", "Script", maxHeightDp = 200) else null,
                    fields(f("Args", args.joinToString(", "), true)),
                    out,
                )
            }
            ToolBody.Skill -> listOf(fields(f("Skill", i.str("skill") ?: i.str("command"), true), f("Args", i.str("args"), true)), out)
            ToolBody.Plan -> listOfNotNull(i.str("plan")?.takeIf { it.isNotEmpty() }?.let { BodyPart.Markdown(it) }, out)
            ToolBody.Read -> listOf(
                fields(
                    f("File", i.str("file_path"), true),
                    f("Range", readRange(i.num("offset"), i.num("limit"))),
                    f("Pages", i.str("pages")),
                ),
                out,
            )
            ToolBody.OrchestratorRead -> listOf(
                fields(f("File", i.str("path"), true), f("Range", lineRange(i.num("start_line"), i.num("end_line")))),
                out,
            )
            ToolBody.Write -> writeParts(i.str("file_path") ?: "", i.str("content")) + out
            ToolBody.OrchestratorWrite -> writeParts(i.str("path") ?: "", i.str("content")) + out
            ToolBody.Edit -> {
                val old = i.str("old_string")
                val new = i.str("new_string")
                listOfNotNull(
                    fields(f("File", i.str("file_path"), true), if (i.bool("replace_all") == true) Field("Mode", "Replace all occurrences") else null),
                    if (old != null && new != null) BodyPart.Diff(old, new) else null,
                    out,
                )
            }
            ToolBody.MultiEdit -> {
                val edits = (i.arr("edits") ?: JsonArray(emptyList())).filterIsInstance<JsonObject>()
                buildList {
                    add(fields(f("File", i.str("file_path"), true), Field("Edits", edits.size.toString())))
                    edits.forEach { e ->
                        val o = e.str("old_string")
                        val n = e.str("new_string")
                        if (o != null && n != null) add(BodyPart.Diff(o, n))
                    }
                    add(out)
                }
            }
            ToolBody.NotebookEdit -> {
                val source = i.str("new_source")
                listOfNotNull(
                    fields(
                        f("Notebook", i.str("notebook_path"), true),
                        f("Cell", i.str("cell_id"), true),
                        f("Mode", i.str("edit_mode")),
                        f("Type", i.str("cell_type")),
                    ),
                    source?.let { BodyPart.Code(it, if (i.str("cell_type") == "markdown") "markdown" else "python", "Source") },
                    out,
                )
            }
            ToolBody.Glob -> listOf(fields(f("Pattern", i.str("pattern"), true), f("Path", i.str("path"), true)), out)
            ToolBody.Grep -> listOf(
                fields(
                    f("Pattern", i.str("pattern"), true),
                    f("Path", i.str("path"), true),
                    f("Glob", i.str("glob"), true),
                    f("Type", i.str("type")),
                    f("Options", grepOptions(i)),
                ),
                out,
            )
            ToolBody.ListFiles -> listOf(
                fields(f("Path", i.str("path"), true), f("Files", i.arr("paths")?.joinToString("\n") { jsString(it) }, true)),
                out,
            )
            ToolBody.WebFetch -> {
                val url = i.str("url") ?: ""
                listOf(BodyPart.Fields(listOfNotNull(Field("URL", url, true, safeHref(url)), f("Prompt", i.str("prompt"))).filter { it.value.isNotEmpty() }), out)
            }
            ToolBody.WebSearch -> {
                val allowed = i.arr("allowed_domains")?.takeIf { it.isNotEmpty() }?.joinToString(", ") { jsString(it) }
                val blocked = i.arr("blocked_domains")?.takeIf { it.isNotEmpty() }?.joinToString(", ") { jsString(it) }
                listOf(fields(f("Query", i.str("query")), f("Only", allowed), f("Except", blocked)), out)
            }
            ToolBody.SendToAgent -> listOfNotNull(
                fields(f("Session", i.str("session_id"), true)),
                i.str("message")?.takeIf { it.isNotEmpty() }?.let { BodyPart.Pre(it) },
                out,
            )
            ToolBody.AgentSession -> {
                val max = i.num("max_messages")
                listOf(
                    fields(
                        f("Session", i.str("session_id"), true),
                        f("Resume", i.str("resume_sdk_id"), true),
                        f("Title", i.str("title")),
                        f("Directory", i.str("working_directory") ?: i.str("working_dir"), true),
                        f("Request", i.str("request_id"), true),
                        f("Decision", i.str("decision")),
                        f("Message", i.str("message")),
                        f("Max", max?.let { "${jsNumber(it)} messages" }),
                        f("Limit", i.str("limit")),
                    ),
                    out,
                )
            }
            ToolBody.Search -> listOf(fields(f("Query", i.str("query")), f("Max", i.str("max_results"))), out)
            ToolBody.Generic -> listOfNotNull(if (i.isEmpty()) null else BodyPart.Json(prettyJson(i)), out)
            ToolBody.Task -> listOfNotNull(
                fields(f("Agent", i.str("subagent_type")), f("Task", i.str("description")), f("Model", i.str("model"))),
                i.str("prompt")?.takeIf { it.isNotEmpty() }?.let { BodyPart.Pre(it) },
                BodyPart.Output(label = "Report", render = OutputRender.Markdown),
            )
            ToolBody.Todo -> {
                val todos = parseTodos(i)
                listOf(if (todos.isEmpty()) BodyPart.Note("No todos") else BodyPart.Todos(todos), out)
            }
            ToolBody.AskUserQuestion -> listOf(BodyPart.Questions(parseQuestions(i)), BodyPart.Output(label = "Answer"))
        }.filterNot { it is BodyPart.Fields && it.rows.isEmpty() }
    }

    private fun writeParts(path: String, content: String?): List<BodyPart> = listOfNotNull(
        fields(f("File", path, true)),
        content?.let { BodyPart.Code(it, languageFromPath(path), "Content") },
    )
}
