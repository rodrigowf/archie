package com.assistant.archie.feature.visuals

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class VisualUrlsTest {
    @Test fun `href encodes every segment (VZ-1)`() {
        assertEquals("http://h/music1%20mix/sync%20report.html", VisualUrls.href("http://h/", "music1 mix/sync report.html", "/music1 mix/sync report.html"))
        assertEquals("http://h/a/b%23c%3F.html", VisualUrls.href("http://h", "a/b#c?.html", null))
        assertEquals("http://h/dashboard.html", VisualUrls.href("http://h", "dashboard.html", ""))
        assertEquals("http://h/x/T%C3%A2r%C3%B4.html", VisualUrls.href("http://h", "x/Târô.html", "/x/Târô.html"))
    }

    @Test fun `folder is the parent or public (VZ-5)`() {
        assertEquals("public", VisualUrls.folder("dashboard.html"))
        assertEquals("charts", VisualUrls.folder("charts/index.html"))
        assertEquals("b", VisualUrls.folder("a/b/c.html"))
    }

    @Test fun `ages are computed from the UTC offset`() {
        val now = Instant.parse("2026-10-03T15:00:00Z")
        assertEquals("2h", shortAge("2026-10-03T13:00:00+00:00", now, ZoneOffset.UTC))
        assertEquals("2h", shortAge("2026-10-03T10:00:00-03:00", now, ZoneOffset.UTC))
        assertEquals("2d", shortAge("2026-10-01T10:00:00+00:00", now, ZoneOffset.UTC))
        assertEquals("", shortAge(null, now))
        assertEquals("2 h ago", relativeTime("2026-10-03T13:00:00+00:00", now, ZoneOffset.UTC))
        assertEquals("yesterday", relativeTime("2026-10-02T13:00:00+00:00", now, ZoneOffset.UTC))
    }
}
