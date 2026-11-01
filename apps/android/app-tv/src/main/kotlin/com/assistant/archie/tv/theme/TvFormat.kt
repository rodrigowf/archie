package com.assistant.archie.tv.theme

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Small text helpers shared by the screens (pure; JVM-testable). */
object TvFormat {
    /** ISO-8601 with or without offset (no offset = UTC, as the backend writes) → instant, or null. */
    fun parse(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    /** "just now", "5 min ago", "3 h ago", "2 days ago". */
    fun ago(iso: String?, now: Instant = Instant.now()): String? {
        val at = parse(iso) ?: return null
        val d = Duration.between(at, now).coerceAtLeast(Duration.ZERO)
        return when {
            d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
            d.toHours() < 24 -> "${d.toHours()} h ago"
            d.toDays() == 1L -> "yesterday"
            else -> "${d.toDays()} days ago"
        }
    }

    /** The folder part of a memory / visual path ("people/rodrigo" of "people/rodrigo/x.md"), or "". */
    fun folderOf(path: String): String = path.substringBeforeLast('/', "")

    /** A file title from its path ("solar-dashboard" of "visualizations/solar-dashboard.html"). */
    fun titleOf(path: String): String = path.substringAfterLast('/').substringBeforeLast('.').replace('-', ' ').replace('_', ' ')
}
