package com.assistant.core.data

import com.assistant.core.model.SessionSummary
import com.assistant.core.network.Timestamps
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Date buckets of the history list (IA §3: Today / Yesterday / Previous 7 days / Earlier). */
enum class HistoryBucket(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    PREVIOUS_7_DAYS("Previous 7 days"),
    EARLIER("Earlier"),
}

/** One history row: the summary plus its parsed instant and the trailing meta text. */
data class HistoryRow(
    val summary: SessionSummary,
    /** `last_activity` (or `started_at`) parsed **with its offset** (A-8.4, inv03 §1.5); `null` when unparseable. */
    val at: Instant?,
    /** `14:20` today and yesterday, `Tue` within the week, `12 Sep` earlier (local time). */
    val meta: String,
)

data class HistoryGroup(val bucket: HistoryBucket, val rows: List<HistoryRow>)

/**
 * Groups `GET /api/sessions` by local calendar day (pure; [now] and [zone] are inputs so tests pin
 * them). The backend sends ISO-8601 with an offset; parsing honours it, then the instant is shown in
 * [zone] — the fix for the old app's "first 19 characters as local time" skew (inv03 §8 bug 4).
 * Rows inside a bucket are newest first; rows with no timestamp go to [HistoryBucket.EARLIER].
 */
object HistoryGrouping {
    fun group(
        sessions: List<SessionSummary>,
        now: Instant,
        zone: ZoneId,
        locale: Locale = Locale.getDefault(),
    ): List<HistoryGroup> {
        val today = now.atZone(zone).toLocalDate()
        val time = DateTimeFormatter.ofPattern("HH:mm", locale)
        val weekday = DateTimeFormatter.ofPattern("EEE", locale)
        val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
        val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
        val rows = sessions
            .map { s -> s to Timestamps.parse(s.lastActivity ?: s.startedAt) }
            .sortedWith(compareByDescending<Pair<SessionSummary, Instant?>> { it.second ?: Instant.MIN })
        val buckets = linkedMapOf<HistoryBucket, MutableList<HistoryRow>>()
        for ((s, at) in rows) {
            val day: LocalDate? = at?.atZone(zone)?.toLocalDate()
            val bucket = bucketOf(day, today)
            val local = at?.atZone(zone)
            val meta = when {
                local == null -> ""
                bucket == HistoryBucket.TODAY || bucket == HistoryBucket.YESTERDAY -> time.format(local)
                bucket == HistoryBucket.PREVIOUS_7_DAYS -> weekday.format(local)
                local.year == today.year -> dayMonth.format(local)
                else -> dayMonthYear.format(local)
            }
            buckets.getOrPut(bucket) { mutableListOf() } += HistoryRow(s, at, meta)
        }
        return HistoryBucket.entries.mapNotNull { b -> buckets[b]?.let { HistoryGroup(b, it) } }
    }

    fun bucketOf(day: LocalDate?, today: LocalDate): HistoryBucket = when {
        day == null -> HistoryBucket.EARLIER
        !day.isBefore(today) -> HistoryBucket.TODAY          // today, or a future instant (clock skew)
        day == today.minusDays(1) -> HistoryBucket.YESTERDAY
        day.isAfter(today.minusDays(8)) -> HistoryBucket.PREVIOUS_7_DAYS
        else -> HistoryBucket.EARLIER
    }

    /** Case-insensitive title filter (drawer / list-pane search). */
    fun filter(sessions: List<SessionSummary>, query: String): List<SessionSummary> {
        val q = query.trim()
        if (q.isEmpty()) return sessions
        return sessions.filter { it.title.contains(q, ignoreCase = true) }
    }
}
