package com.assistant.archie.feature.toolcards

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/*
 * Pure text helpers for tool cards: a line-for-line port of the web's
 * `frontend/src/features/tools/format.ts` (W-10). Input coercion follows JavaScript's rules
 * (a number is accepted where a string is expected), so summaries match the web card for card.
 */

/** A tool's input object (`tool_input`). */
typealias ToolInput = JsonObject

/** Output shown before "Show all" (spec 13 §3.6): the first 200 lines or 20 KB. */
const val OUTPUT_MAX_LINES: Int = 200
const val OUTPUT_MAX_CHARS: Int = 20 * 1024

/** A string field, or null for anything else (numbers are stringified, like JS `String(n)`). */
fun ToolInput.str(key: String): String? {
    val v = this[key] as? JsonPrimitive ?: return null
    if (v is JsonNull) return null
    if (v.isString) return v.content
    if (v.booleanOrNull != null) return null
    val d = v.content.toDoubleOrNull() ?: return null
    if (!d.isFinite()) return null
    return jsNumber(d)
}

/** A finite number field (numeric strings accepted), or null. */
fun ToolInput.num(key: String): Double? {
    val v = this[key] as? JsonPrimitive ?: return null
    if (v is JsonNull) return null
    if (v.isString) {
        if (v.content.isBlank()) return null
        return v.content.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    }
    if (v.booleanOrNull != null) return null
    return v.content.toDoubleOrNull()?.takeIf { it.isFinite() }
}

fun ToolInput.bool(key: String): Boolean? {
    val v = this[key] as? JsonPrimitive ?: return null
    if (v is JsonNull || v.isString) return null
    return v.booleanOrNull
}

fun ToolInput.arr(key: String): JsonArray? = this[key] as? JsonArray

/** `input.key === value` for a string value. */
fun ToolInput.isStr(key: String, value: String): Boolean {
    val v = this[key] as? JsonPrimitive ?: return false
    return v.isString && v.content == value
}

/** JS `String(n)`: integral values print without a fraction. */
internal fun jsNumber(d: Double): String = if (d == floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()

/** JS `String(v)` for an array element (`args.map(String)`). */
internal fun jsString(e: JsonElement): String = when (e) {
    is JsonNull -> "null"
    is JsonPrimitive -> if (e.isString) e.content else e.content.toDoubleOrNull()?.let { jsNumber(it) } ?: e.content
    is JsonObject -> "[object Object]"
    is JsonArray -> e.joinToString(",") { if (it is JsonNull) "" else jsString(it) }
}

/** Truncates to [max] characters with a trailing ellipsis. */
fun clip(text: String, max: Int = 60): String = if (text.length > max) "${text.substring(0, max).trimEnd()}…" else text

/** First non-empty line, clipped; " …" marks that more lines follow. */
fun firstLine(text: String, max: Int = 60): String {
    val line = text.split('\n').firstOrNull { it.trim().isNotEmpty() } ?: ""
    val more = text.trim().indexOf('\n') >= 0
    val clipped = clip(line.trim(), max)
    return if (more && !clipped.endsWith("…")) "$clipped …" else clipped
}

/**
 * Shortens deep paths to their last two segments (inv02 F-05 `formatFilePath`):
 * `/home/u/proj/src/a.ts` → `…/src/a.ts`; paths with ≤ 3 segments are unchanged.
 */
fun shortPath(path: String): String {
    val parts = path.split('/')
    return if (parts.size > 3) "…/${parts.takeLast(2).joinToString("/")}" else path
}

fun baseName(path: String): String = path.split('/').last().ifEmpty { path }

private val SCHEME = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)

/** `https://example.com/a?b` → `example.com/a?b` (header summaries only). */
fun stripScheme(url: String): String = url.replaceFirst(SCHEME, "")

fun shortId(id: String): String = id.take(8)

/** Language id for a file path (inv02 F-05 extension map), or null for plain text. */
fun languageFromPath(path: String): String? {
    val name = baseName(path).lowercase(Locale.ROOT)
    if (name == "dockerfile") return "dockerfile"
    val dot = name.lastIndexOf('.')
    val ext = if (dot >= 0) name.substring(dot + 1) else ""
    return LANG_BY_EXT[ext]
}

private val LANG_BY_EXT: Map<String, String> = mapOf(
    "ts" to "typescript", "tsx" to "tsx", "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript",
    "jsx" to "jsx", "py" to "python", "rs" to "rust", "go" to "go", "rb" to "ruby", "java" to "java",
    "kt" to "kotlin", "kts" to "kotlin", "c" to "c", "cpp" to "cpp", "h" to "c", "hpp" to "cpp",
    "css" to "css", "scss" to "scss", "html" to "html", "json" to "json", "yaml" to "yaml", "yml" to "yaml",
    "md" to "markdown", "sh" to "bash", "bash" to "bash", "zsh" to "bash", "sql" to "sql",
    "graphql" to "graphql", "dockerfile" to "dockerfile", "toml" to "toml", "xml" to "xml",
)

/* ANSI: CSI sequences (colours, cursor moves) and OSC sequences (titles, hyperlinks). */
private const val ESC = '\u001b'
private val ANSI_CSI = Regex("$ESC\\[[0-9;?]*[ -/]*[@-~]")
private val ANSI_OSC = Regex("$ESC\\][^\u0007$ESC]*(?:\u0007|$ESC\\\\)")
private val ANSI_LONE = Regex("$ESC[@-Z\\\\-_]")
private val CRLF = Regex("\r\n?")

/** Removes terminal escape sequences and normalises CRLF (spec 13 §3.6: "ANSI stripped"). */
fun stripAnsi(text: String): String {
    if (text.indexOf(ESC) < 0) return if (text.indexOf('\r') < 0) text else text.replace(CRLF, "\n")
    return text.replace(ANSI_OSC, "").replace(ANSI_CSI, "").replace(ANSI_LONE, "").replace(CRLF, "\n")
}

data class TruncatedText(val text: String, val truncated: Boolean, val totalLines: Int, val shownLines: Int)

/** The first [maxLines] lines and at most [maxChars] characters (cut at a line end when possible). */
fun truncateOutput(text: String, maxLines: Int = OUTPUT_MAX_LINES, maxChars: Int = OUTPUT_MAX_CHARS): TruncatedText {
    val totalLines = countLines(text)
    var end = text.length
    var truncated = false
    if (totalLines > maxLines) {
        var idx = -1
        repeat(maxLines) { idx = text.indexOf('\n', idx + 1) }
        end = idx
        truncated = true
    }
    if (end > maxChars) {
        val nl = text.lastIndexOf('\n', maxChars)
        end = if (nl > maxChars / 2) nl else maxChars
        truncated = true
    }
    val shown = if (truncated) text.substring(0, end) else text
    return TruncatedText(shown, truncated, totalLines, countLines(shown))
}

fun countLines(text: String): Int {
    if (text.isEmpty()) return 0
    var n = 1
    for (ch in text) if (ch == '\n') n += 1
    return if (text.endsWith('\n')) n - 1 else n
}

/** Running clock: `0:12`, `2:14`, `1:02:03`. */
fun formatClock(seconds: Double): String {
    val s = floor(seconds.coerceAtLeast(0.0)).toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
}

/** Finished duration: `0.2s`, `3.8s`, `42s`, `1:05`. */
fun formatDuration(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000.0
    if (s < 10) return String.format(Locale.US, "%.1fs", s)
    if (s < 60) return "${jsRound(s)}s"
    return formatClock(s)
}

/** JS `Math.round` (halves round up). */
fun jsRound(x: Double): Long = floor(x + 0.5).toLong()

private val EXIT_CODE = Regex("exit[ _]code:?\\s*(-?\\d+)", RegexOption.IGNORE_CASE)

/** Exit code printed in a shell result (Claude "Exit code 2", Qwen "Exit Code: 2"), or null. */
fun parseExitCode(output: String): Int? = EXIT_CODE.find(output)?.groupValues?.get(1)?.toIntOrNull()

fun plural(n: Int, one: String, many: String = "${one}s"): String = "$n ${if (n == 1) one else many}"

@OptIn(ExperimentalSerializationApi::class)
private val PRETTY = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** Pretty JSON for the generic input view (JSON.stringify(v, null, 2)); never throws. */
fun prettyJson(value: JsonElement): String = runCatching { PRETTY.encodeToString(JsonElement.serializer(), value) }.getOrElse { value.toString() }

/** The first short string value of an input, for summaries of unknown tools. */
fun firstStringArg(input: ToolInput, max: Int = 60): String {
    for ((_, v) in input) {
        if (v is JsonPrimitive && v.isString && v.content.trim().isNotEmpty()) return firstLine(v.content, max)
    }
    return ""
}

/** `en-US` grouping: 12345 → "12,345". */
internal fun groupDigits(n: Int): String = String.format(Locale.US, "%,d", n)
