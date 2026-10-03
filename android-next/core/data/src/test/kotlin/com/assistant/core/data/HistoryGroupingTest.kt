package com.assistant.core.data

import com.assistant.core.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Drawer / list-pane history grouping with UTC-correct times (fixes inv03 §1.5 / §8 bug 4: the old
 * app read the first 19 characters of `last_activity` as device-local time, +3 h off in Rio).
 */
class HistoryGroupingTest {
    private val rio = ZoneId.of("America/Sao_Paulo")      // UTC-3, no DST since 2019
    private val tokyo = ZoneId.of("Asia/Tokyo")           // UTC+9

    private fun s(id: String, last: String?, orch: Boolean = false) =
        SessionSummary(id, null, last, "t-$id", 1, orch, null, null)

    @Test fun realBackendTimestamp_isShownInLocalTime_notAsUtcWallClock() {
        // Verified Jetson shape (inv03 §1.5).
        val rows = listOf(s("a", "2026-08-28T21:49:03.550000+00:00"))
        val now = Instant.parse("2026-08-28T23:00:00Z")
        val g = HistoryGrouping.group(rows, now, rio, Locale.US)
        assertEquals(HistoryBucket.TODAY, g.single().bucket)
        assertEquals("18:49", g.single().rows.single().meta)               // 21:49 UTC = 18:49 in Rio, not "21:49"
        assertEquals(Instant.parse("2026-08-28T21:49:03.550Z"), g.single().rows.single().at)
    }

    @Test fun sameInstant_differentZones_differentDayBuckets() {
        val rows = listOf(s("a", "2026-10-03T02:00:00+00:00"))
        val now = Instant.parse("2026-10-03T12:00:00Z")
        // Rio: 2 Oct 23:00 → yesterday. Tokyo: 3 Oct 11:00 → today.
        assertEquals(HistoryBucket.YESTERDAY, HistoryGrouping.group(rows, now, rio, Locale.US).single().bucket)
        assertEquals("23:00", HistoryGrouping.group(rows, now, rio, Locale.US).single().rows.single().meta)
        assertEquals(HistoryBucket.TODAY, HistoryGrouping.group(rows, now, tokyo, Locale.US).single().bucket)
        assertEquals("11:00", HistoryGrouping.group(rows, now, tokyo, Locale.US).single().rows.single().meta)
    }

    @Test fun nonUtcOffsetsAreHonoured() {
        val a = s("a", "2026-10-03T09:00:00-03:00")   // = 12:00Z
        val g = HistoryGrouping.group(listOf(a), Instant.parse("2026-10-03T13:00:00Z"), tokyo, Locale.US)
        assertEquals("21:00", g.single().rows.single().meta)
    }

    @Test fun bucketsOrderAndMeta() {
        val now = Instant.parse("2026-10-03T15:00:00Z")      // Sat 3 Oct, 12:00 in Rio
        val rows = listOf(
            s("old", "2026-08-28T21:49:03+00:00"),
            s("today", "2026-10-03T14:20:00+00:00"),
            s("yday", "2026-10-02T11:12:00+00:00"),
            s("week", "2026-09-29T15:00:00+00:00"),
            s("lastyear", "2025-12-01T15:00:00+00:00"),
            s("none", null),
        )
        val g = HistoryGrouping.group(rows, now, rio, Locale.US)
        assertEquals(listOf(HistoryBucket.TODAY, HistoryBucket.YESTERDAY, HistoryBucket.PREVIOUS_7_DAYS, HistoryBucket.EARLIER), g.map { it.bucket })
        assertEquals("11:20", g[0].rows.single().meta)
        assertEquals("08:12", g[1].rows.single().meta)
        assertEquals("Tue", g[2].rows.single().meta)
        assertEquals(listOf("old", "lastyear", "none"), g[3].rows.map { it.summary.sdkId })   // newest first, unparseable last
        assertEquals(listOf("28 Aug", "1 Dec 2025", ""), g[3].rows.map { it.meta })
    }

    @Test fun localTimestampWithoutOffset_isTakenAsUtc() {
        val g = HistoryGrouping.group(listOf(s("a", "2026-10-03T14:20:00")), Instant.parse("2026-10-03T15:00:00Z"), rio, Locale.US)
        assertEquals("11:20", g.single().rows.single().meta)
    }

    @Test fun searchFiltersByTitle() {
        val rows = listOf(s("a", null).copy(title = "Living-room TV"), s("b", null).copy(title = "Energy"))
        assertEquals(listOf("a"), HistoryGrouping.filter(rows, " living ").map { it.sdkId })
        assertEquals(2, HistoryGrouping.filter(rows, "").size)
    }
}
