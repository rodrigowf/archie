package com.assistant.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The backend sends `Last-Modified` but no `Cache-Control`: OkHttp's heuristic freshness would
 * serve a week-old file from disk for ~17 h. A Reload must always reach the server.
 */
class HttpCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: ArchieApi
    private lateinit var stack: HttpStack

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        stack = HttpStack(cacheDir = tmp.newFolder("http"))
        val base = server.url("/").toString().trimEnd('/')
        api = ArchieApi(stack) { base }
    }

    @After fun tearDown() = server.shutdown()

    private fun httpDate(millis: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date(millis))

    private fun file(body: String, lastModified: Long = System.currentTimeMillis() - 7L * 24 * 3600 * 1000) =
        MockResponse()
            .setHeader("Date", httpDate(System.currentTimeMillis()))
            .setHeader("Last-Modified", httpDate(lastModified))
            .setBody(body)

    @Test fun reloadFetchesFreshContentDespiteLastModified() = runBlocking {
        server.enqueue(file("# v1"))
        server.enqueue(file("# v2"))
        assertEquals("# v1", api.memoryDocument("notes.md").getOrNull())
        assertEquals("# v2", api.memoryDocument("notes.md").getOrNull())   // Reload
        assertEquals(2, server.requestCount)
    }

    @Test fun anUnchangedFileRevalidatesAndReusesTheStoredBody() = runBlocking {
        val lastModified = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        server.enqueue(file("# v1", lastModified).setHeader("ETag", "\"a\""))
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"a\""))
        assertEquals("# v1", api.memoryDocument("notes.md").getOrNull())
        assertNull(server.takeRequest().getHeader("If-None-Match"))
        assertEquals("# v1", api.memoryDocument("notes.md").getOrNull())
        assertEquals("\"a\"", server.takeRequest().getHeader("If-None-Match"))   // still a network round trip
    }

    @Test fun explicitServerCachePolicyIsKept() = runBlocking {
        server.enqueue(file("# v1").setHeader("Cache-Control", "max-age=3600"))
        server.enqueue(file("# v2"))
        assertEquals("# v1", api.memoryDocument("notes.md").getOrNull())
        assertEquals("# v1", api.memoryDocument("notes.md").getOrNull())
        assertEquals(1, server.requestCount)
    }
}
