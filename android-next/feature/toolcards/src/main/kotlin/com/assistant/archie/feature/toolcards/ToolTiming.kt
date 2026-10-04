package com.assistant.archie.feature.toolcards

import com.assistant.core.conversation.BlockOrigin
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.ToolStatus

/**
 * Client-side tool timing for the card's status area (mockups: "0:12" while running, "3.8s" once
 * done), ported from the web's `timing.ts`. The protocol carries no timestamps, so a live card is
 * timed from the moment this client first saw it running; `tool_progress.elapsed_seconds`
 * (orchestrator) wins when it is larger. History cards and cards first seen finished show no
 * duration (never fabricated).
 *
 * Keyed by the block's stable client id in a bounded process map, so recompositions, scrolling a
 * card out of the lazy list and back, and group collapse keep the clock.
 */
object ToolTiming {
    private class Span(val start: Long, var end: Long? = null)

    private const val MAX_SPANS = 1000
    private val spans = LinkedHashMap<String, Span>()

    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    /** Test hook. */
    fun reset() = synchronized(spans) { spans.clear() }

    data class Timing(
        /** Seconds since the card started, while running. */
        val runningSeconds: Double?,
        /** Total duration in ms, once finished. */
        val durationMs: Long?,
    ) {
        /** The status-area text: running clock from 1 s on, or the finished duration. */
        val label: String?
            get() = when {
                runningSeconds != null -> if (runningSeconds >= 1) formatClock(runningSeconds) else null
                durationMs != null -> formatDuration(durationMs)
                else -> null
            }
    }

    private fun track(block: ToolBlock, now: Long): Span? = synchronized(spans) {
        var span = spans[block.id]
        if (span == null) {
            if (block.status != ToolStatus.RUNNING || block.origin != BlockOrigin.LIVE) return null
            span = Span(now)
            spans[block.id] = span
            if (spans.size > MAX_SPANS) spans.remove(spans.keys.first())
        }
        if (block.status != ToolStatus.RUNNING && span.end == null) span.end = now
        span
    }

    fun of(block: ToolBlock): Timing {
        val now = clock()
        val span = track(block, now)
        val progress = block.progress?.elapsedSeconds
        if (block.status == ToolStatus.RUNNING) {
            val local = span?.let { (now - it.start) / 1000.0 }
            val best = if (progress != null && (local == null || progress > local)) progress else local
            return Timing(best, null)
        }
        val end = span?.end
        return Timing(null, if (span != null && end != null) end - span.start else null)
    }
}
