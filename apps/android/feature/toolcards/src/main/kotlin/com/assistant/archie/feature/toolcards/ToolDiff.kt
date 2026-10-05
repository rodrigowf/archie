package com.assistant.archie.feature.toolcards

import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType

/*
 * Edit diffs (inv02 F-06, spec 14 §3.5). The line differ is java-diff-utils (Myers), producing the
 * same add/del/context sequence as the web's `Diff.diffLines`; folding mirrors the web's
 * `computeDiff` (frontend/src/features/tools/diff.ts): 3 lines of context around each change,
 * longer unchanged runs fold into one "N unchanged lines" row. The Android view lets that row
 * expand in place (spec 14 §3.5 improvement), so a fold keeps the lines it hides.
 */

enum class DiffLineKind { Add, Del, Ctx, Fold }

data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    /** The unchanged lines a [DiffLineKind.Fold] row hides (empty otherwise). */
    val hidden: List<String> = emptyList(),
)

data class DiffResult(val lines: List<DiffLine>, val added: Int, val removed: Int) {
    /** Header meta: "+4 −1". */
    val stat: String get() = "+$added −$removed"
}

/** Unchanged lines kept around each change; longer unchanged runs fold into one line. */
const val DIFF_CONTEXT: Int = 3

object ToolDiff {
    private const val CACHE_SIZE = 64

    private val cache = object : LinkedHashMap<Pair<String, String>, DiffResult>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, DiffResult>?) = size > CACHE_SIZE
    }

    /** Inputs this small diff synchronously during composition (no placeholder frame). */
    const val SYNC_LIMIT_CHARS: Int = 24_000

    fun splitLines(value: String): List<String> {
        val lines = value.split('\n').toMutableList()
        if (lines.isNotEmpty() && lines.last() == "") lines.removeAt(lines.lastIndex) // trailing newline (F-06)
        return lines
    }

    fun cached(old: String, new: String): DiffResult? = synchronized(cache) { cache[old to new] }

    /** The diff of two strings, cached by content (cheap to call again for the header meta). */
    fun diff(old: String, new: String, context: Int = DIFF_CONTEXT): DiffResult {
        if (context == DIFF_CONTEXT) cached(old, new)?.let { return it }
        val result = compute(old, new, context)
        if (context == DIFF_CONTEXT) synchronized(cache) { cache[old to new] = result }
        return result
    }

    fun compute(old: String, new: String, context: Int = DIFF_CONTEXT): DiffResult {
        val a = splitLines(old)
        val b = splitLines(new)
        val raw = ArrayList<DiffLine>(a.size + b.size)
        var added = 0
        var removed = 0
        var pos = 0
        val patch = DiffUtils.diff(a, b)
        for (delta in patch.deltas) {
            val src = delta.source
            while (pos < src.position) raw += DiffLine(DiffLineKind.Ctx, a[pos++])
            if (delta.type == DeltaType.DELETE || delta.type == DeltaType.CHANGE) {
                src.lines.forEach { raw += DiffLine(DiffLineKind.Del, it) }
                removed += src.lines.size
            }
            if (delta.type == DeltaType.INSERT || delta.type == DeltaType.CHANGE) {
                delta.target.lines.forEach { raw += DiffLine(DiffLineKind.Add, it) }
                added += delta.target.lines.size
            }
            pos = src.position + src.lines.size
        }
        while (pos < a.size) raw += DiffLine(DiffLineKind.Ctx, a[pos++])
        return DiffResult(fold(raw, context), added, removed)
    }

    private fun fold(raw: List<DiffLine>, context: Int): List<DiffLine> {
        val lines = ArrayList<DiffLine>(raw.size)
        var i = 0
        while (i < raw.size) {
            if (raw[i].kind != DiffLineKind.Ctx) {
                lines += raw[i]
                i += 1
                continue
            }
            var j = i
            while (j < raw.size && raw[j].kind == DiffLineKind.Ctx) j += 1
            val run = raw.subList(i, j)
            val head = if (i == 0) 0 else context
            val tail = if (j == raw.size) 0 else context
            if (run.size > head + tail + 1) {
                lines += run.subList(0, head)
                val hidden = run.size - head - tail
                lines += DiffLine(
                    DiffLineKind.Fold,
                    "$hidden unchanged ${if (hidden == 1) "line" else "lines"}",
                    run.subList(head, run.size - tail).map { it.text },
                )
                lines += run.subList(run.size - tail, run.size)
            } else {
                lines += run
            }
            i = j
        }
        return lines
    }

    /** Until the differ ran: the old text as removed lines, then the new one as added (web placeholder). */
    fun plain(old: String, new: String): List<DiffLine> =
        splitLines(old).map { DiffLine(DiffLineKind.Del, it) } + splitLines(new).map { DiffLine(DiffLineKind.Add, it) }
}
