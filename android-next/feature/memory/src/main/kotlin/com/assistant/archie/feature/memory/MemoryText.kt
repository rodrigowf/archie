package com.assistant.archie.feature.memory

import com.assistant.core.markdown.Frontmatter
import com.assistant.core.network.Timestamps
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Copy of the memory document (web parity: frontend-next features/memory/MemoryDocument.tsx and
 * platform/time.ts). Pure, so the tests pin the exact strings.
 */

/** "Frontmatter · architecture · 4 refs" (mockup g2): the category's last segment and the reference count. */
fun frontmatterChipLabel(frontmatter: String): String {
    val s = Frontmatter.summarize(frontmatter)
    val parts = mutableListOf("Frontmatter")
    val category = s.scalar("category") ?: s.scalar("type")
    // `assistant/architecture` → `architecture` (the chip is one short line; the full text is inside).
    if (!category.isNullOrBlank()) parts += category.split('/').lastOrNull { it.isNotEmpty() } ?: category
    val refs = s.references.size
    if (refs > 0) parts += "$refs ${if (refs == 1) "ref" else "refs"}"
    return parts.joinToString(" · ")
}

/** "Modified 2 days ago" from the frontmatter's `modified` (a local date or a timestamp); null without one. */
fun modifiedLine(frontmatter: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
    if (frontmatter == null) return null
    val raw = Frontmatter.summarize(frontmatter).modified?.trim().orEmpty()
    if (raw.isEmpty()) return null
    if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(raw)) {
        val date = try {
            LocalDate.parse(raw)
        } catch (_: DateTimeParseException) {
            return null
        }
        val days = ChronoUnit.DAYS.between(date, now.atZone(zone).toLocalDate())
        return when {
            days <= 0 -> "Modified today"
            days == 1L -> "Modified yesterday"
            days < 7 -> "Modified $days days ago"
            else -> "Modified ${shortDate(date, now.atZone(zone).toLocalDate())}"
        }
    }
    val t = Timestamps.parse(raw) ?: return null
    return "Modified ${relativeTime(t, now, zone)}"
}

/** Web `formatRelativeTime`: just now · N min ago · N h ago · yesterday · N days ago · 12 Mar [2025]. */
fun relativeTime(then: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    val diff = Duration.between(then, now)
    val ms = diff.toMillis()
    if (ms < 45_000) return "just now"
    if (ms < 3_600_000) return "${maxOf(1L, Math.round(ms / 60_000.0))} min ago"
    val thenDay = then.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(thenDay, today)
    return when {
        days <= 0L -> "${ms / 3_600_000} h ago"
        days == 1L -> "yesterday"
        days < 7 -> "$days days ago"
        else -> shortDate(thenDay, today)
    }
}

private fun shortDate(date: LocalDate, today: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "d MMM" else "d MMM yyyy", Locale.getDefault()))

/** A leading `# Title` line, split off so the modified line can sit under it (mockup g2). */
data class TitleSplit(val heading: String?, val rest: String)

fun splitTitle(body: String): TitleSplit {
    val m = Regex("^\\s*(#[ \\t][^\\n]*)(\\r?\\n|$)").find(body) ?: return TitleSplit(null, body)
    return TitleSplit(m.groupValues[1], body.substring(m.range.last + 1))
}
