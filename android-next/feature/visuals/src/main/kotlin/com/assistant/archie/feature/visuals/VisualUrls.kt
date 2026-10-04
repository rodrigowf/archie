package com.assistant.archie.feature.visuals

import com.assistant.core.network.Timestamps
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Visualization helpers (spec 12 §9.1, spec 14 §4.2; web parity: frontend-next features/visuals/viz.ts).
 */
object VisualUrls {
    /** The backend URL of a visualization as the list sends it (unencoded, G-38), or `/<path>`. */
    fun visualUrl(path: String, url: String?): String = url?.takeIf { it.isNotEmpty() } ?: "/$path"

    /**
     * VZ-1: `<origin>/<seg>/<seg>` with every path segment percent-encoded. The list's `url` is a
     * file path, so `#`/`?` are part of a name and encoded too (web `encodePath`).
     */
    fun href(origin: String, path: String, url: String? = null): String =
        origin.trimEnd('/') + visualUrl(path, url).split('/').filter { it.isNotEmpty() }.joinToString("/", prefix = "/") { encodeSegment(it) }

    /** RFC 3986 path-segment encoding (spaces as %20, never '+'). */
    fun encodeSegment(segment: String): String =
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20").replace("%7E", "~")

    /** VZ-5: the parent folder name, or "public" for files at the root of context/public/. */
    fun folder(path: String): String {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return if (parts.size < 2) "public" else parts[parts.size - 2]
    }
}

/** Web `shortAge` (history list): now · 5m · 3h · 2d · 6w · 12 Mar [2025]. Empty for an unparsable time. */
fun shortAge(iso: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Timestamps.parse(iso) ?: return ""
    val min = maxOf(0L, Duration.between(t, now).toMinutes())
    if (min < 1) return "now"
    if (min < 60) return "${min}m"
    val h = min / 60
    if (h < 24) return "${h}h"
    val days = h / 24
    if (days < 7) return "${days}d"
    if (days < 56) return "${days / 7}w"
    return shortDate(t, now, zone)
}

/** Web `formatRelativeTime`: just now · N min ago · N h ago · yesterday · N days ago · 12 Mar [2025]. */
fun relativeTime(iso: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
    val then = Timestamps.parse(iso) ?: return null
    val ms = Duration.between(then, now).toMillis()
    if (ms < 45_000) return "just now"
    if (ms < 3_600_000) return "${maxOf(1L, Math.round(ms / 60_000.0))} min ago"
    val days = ChronoUnit.DAYS.between(then.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
    return when {
        days <= 0L -> "${ms / 3_600_000} h ago"
        days == 1L -> "yesterday"
        days < 7 -> "$days days ago"
        else -> shortDate(then, now, zone)
    }
}

private fun shortDate(t: Instant, now: Instant, zone: ZoneId): String {
    val d = t.atZone(zone).toLocalDate()
    val sameYear = d.year == now.atZone(zone).year
    return d.format(DateTimeFormatter.ofPattern(if (sameYear) "d MMM" else "d MMM yyyy", Locale.getDefault()))
}
