package com.assistant.archie.feature.toolcards

import androidx.compose.runtime.Immutable
import java.util.Locale

/** A subagent's state as the cards show it (spec 12 §4.8 `Subagent.status`). */
enum class AgentRunStatus { Running, Done, Failed, Stopped }

/**
 * The live summary of the subagent a `Task` card spawned (spec 12 §4.8, plan §4.4 item 1): status
 * (the subagent's lifecycle wins over the card's own result: a background agent's card completes at
 * launch while it keeps running), the one-line activity, usage and the report. Pure data, built by
 * the conversation list; the card never holds the child transcript.
 */
@Immutable
data class AgentRun(
    val id: String,
    val status: AgentRunStatus,
    val activity: String? = null,
    val toolUses: Int? = null,
    val tokens: Long? = null,
    /** Epoch millis; with [endedAtMs] or the clock gives the elapsed time. */
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val durationMs: Long? = null,
    val summary: String? = null,
    val background: Boolean = false,
) {
    /** The line under the header: what it is doing, or how it ended. */
    fun line(): String = when (status) {
        AgentRunStatus.Running -> activity?.takeIf { it.isNotBlank() }?.let { "Running $it" } ?: "Starting…"
        AgentRunStatus.Done -> firstLine(summary) ?: "Done"
        AgentRunStatus.Failed -> firstLine(summary)?.let { "Failed · $it" } ?: "Failed"
        AgentRunStatus.Stopped -> firstLine(summary)?.let { "Stopped · $it" } ?: "Stopped"
    }

    /** `3 tools · 12.4k tokens · 1:05`, only the parts that are known. */
    fun stats(nowMs: Long): String = listOfNotNull(
        toolUses?.let { if (it == 1) "1 tool" else "$it tools" },
        tokens?.let { "${compactCount(it)} tokens" },
        elapsedMs(nowMs)?.let(::clock),
        if (background) "background" else null,
    ).joinToString(" · ")

    fun elapsedMs(nowMs: Long): Long? {
        val start = startedAtMs
        return when {
            status != AgentRunStatus.Running && durationMs != null -> durationMs
            start != null && endedAtMs != null -> (endedAtMs - start).coerceAtLeast(0)
            start != null && status == AgentRunStatus.Running -> (nowMs - start).coerceAtLeast(0)
            else -> durationMs
        }
    }

    /** Spoken status, for content descriptions and the polite live region. */
    val spoken: String get() = when (status) {
        AgentRunStatus.Running -> "running"
        AgentRunStatus.Done -> "done"
        AgentRunStatus.Failed -> "failed"
        AgentRunStatus.Stopped -> "stopped"
    }

    companion object {
        fun clock(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }

        fun compactCount(n: Long): String = when {
            n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
            n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
            else -> n.toString()
        }

        private fun firstLine(s: String?): String? = s?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
    }
}
