package com.assistant.core.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/** UTC-correct parsing: fixes the inv03 §1.5 / A-8.4 skew (+3 h in Rio). */
class TimestampsTest {
    private val original = TimeZone.getDefault()

    @After fun restore() = TimeZone.setDefault(original)

    @Test fun backendOffsetIsHonouredInEveryDeviceZone() {
        val expected = Instant.parse("2026-08-28T21:49:03.550Z")
        for (zone in listOf("America/Sao_Paulo", "UTC", "Asia/Tokyo", "America/Los_Angeles")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            // Real shape from GET /api/sessions on the Jetson (inv03 §1.5).
            assertEquals(zone, expected, Timestamps.parse("2026-08-28T21:49:03.550000+00:00"))
            assertEquals(zone, "5m", Timestamps.relativeAge(Timestamps.parse("2026-08-28T21:49:03.550000+00:00")!!, expected.plusSeconds(300)))
        }
    }

    @Test fun otherShapes() {
        assertEquals(Instant.parse("2026-08-28T21:49:03Z"), Timestamps.parse("2026-08-28T21:49:03Z"))
        assertEquals(Instant.parse("2026-08-29T00:49:03Z"), Timestamps.parse("2026-08-28T21:49:03-03:00"))
        // No offset: the backend's zone is UTC, never the device's.
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"))
        assertEquals(Instant.parse("2026-08-28T21:49:03.123Z"), Timestamps.parse("2026-08-28T21:49:03.123"))
        assertNull(Timestamps.parse("yesterday")); assertNull(Timestamps.parse("")); assertNull(Timestamps.parse(null))
    }

    @Test fun relativeAgeBuckets() {
        val t = Instant.parse("2026-01-01T00:00:00Z")
        assertEquals("now", Timestamps.relativeAge(t, t.plusSeconds(30)))
        assertEquals("now", Timestamps.relativeAge(t, t.minusSeconds(30)))
        assertEquals("3h", Timestamps.relativeAge(t, t.plusSeconds(3 * 3600 + 10)))
        assertEquals("2d", Timestamps.relativeAge(t, t.plusSeconds(2 * 86400 + 10)))
        assertEquals("3w", Timestamps.relativeAge(t, t.plusSeconds(21 * 86400 + 10)))
    }
}
