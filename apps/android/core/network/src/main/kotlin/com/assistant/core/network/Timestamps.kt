package com.assistant.core.network

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Backend timestamps are ISO-8601 **with an offset** (`"2026-08-28T21:49:03.550000+00:00"`).
 * The old app parsed the first 19 characters as device-local time, so every age was skewed by
 * the UTC offset (inv03 §1.5, A-8.4). Here the offset is honoured; a timestamp without one is
 * taken as UTC (the backend's zone). `java.time` reaches API 21 through core library desugaring.
 */
object Timestamps {
    /** Epoch instant, or `null` for blank/garbage input. */
    fun parse(iso: String?): Instant? {
        val s = iso?.trim().orEmpty()
        if (s.isEmpty()) return null
        return try {
            OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    fun parseMillis(iso: String?): Long? = parse(iso)?.toEpochMilli()

    /**
     * Compact relative age: `now`, `5m`, `3h`, `2d`, `6w`. Pure (no zone): the same instant gives
     * the same age in every time zone. Future instants (clock skew) read as `now`.
     */
    fun relativeAge(instant: Instant, now: Instant): String {
        val d = Duration.between(instant, now)
        if (d.isNegative || d.seconds < 60) return "now"
        val minutes = d.toMinutes()
        return when {
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${minutes / 60}h"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d"
            else -> "${minutes / (60 * 24 * 7)}w"
        }
    }
}
