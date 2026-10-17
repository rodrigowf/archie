package com.assistant.archie.tv.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * `open_url` resolution reuses the shared internal-link resolver: every case of
 * `apps/protocol-fixtures/links/internal-links.json` that is internal opens the same in-app view on
 * the TV; everything else is a web page for the Viewer (or refused).
 */
class TvLinksTest {
    private val corpus: JsonObject = Json.parseToJsonElement(corpusFile().readText()).jsonObject
    private val origin = corpus["origin"]!!.jsonPrimitive.content

    private fun corpusFile(): File {
        System.getProperty("archie.internalLinks")?.let { return File(it) }
        // Gradle runs unit tests in the module directory (apps/android/app-tv).
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "apps/protocol-fixtures/links/internal-links.json")
            if (f.exists()) return f
            val g = File(dir, "protocol-fixtures/links/internal-links.json")
            if (g.exists()) return g
            dir = dir.parentFile
        }
        error("internal-links.json not found")
    }

    @Test fun internalCasesOfTheSharedCorpusOpenTheirInAppView() {
        val failures = corpus["cases"]!!.jsonArray.mapNotNull { el ->
            val c = el.jsonObject
            val href = c["href"]!!.jsonPrimitive.content
            val visuals = c["context"]?.jsonObject?.get("visuals")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val got = TvLinks.resolve(href, origin) { it in visuals }
            val e = c["expect"]
            val want: TvLinkTarget? = if (e == null || e is JsonNull) null else {
                val o = e.jsonObject
                val path = o["path"]!!.jsonPrimitive.content
                if (o["kind"]!!.jsonPrimitive.content == "visual") TvLinkTarget.Visual(path)
                else TvLinkTarget.Memory(path, o["fragment"]?.jsonPrimitive?.contentOrNull)
            }
            val bareRelative = !href.startsWith("/") && !href.startsWith("#") && ':' !in href && href.isNotEmpty()
            when {
                want != null && got != want -> "${c["note"]}: $href → $got, expected $want"
                // Not internal: never a memory / visual view (a bare relative path is tried as a server path on the TV).
                want == null && !bareRelative && (got is TvLinkTarget.Visual || got is TvLinkTarget.Memory) -> "${c["note"]}: $href → $got, expected no in-app view"
                else -> null
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun webPagesSchemesAndTheWebAppsHashRoutes() {
        val o = "https://192.168.0.200"
        assertEquals(TvLinkTarget.Web("https://example.com/a?b=1"), TvLinks.resolve("https://example.com/a?b=1", o))
        assertEquals(TvLinkTarget.Web("https://192.168.0.200/api/visualizations"), TvLinks.resolve("/api/visualizations", o))
        assertEquals(TvLinkTarget.Web("https://cdn.example.com/x.html"), TvLinks.resolve("//cdn.example.com/x.html", o))
        assertEquals(TvLinkTarget.Visual("visualizations/foo.html"), TvLinks.resolve("visualizations/foo.html", o))
        assertEquals(TvLinkTarget.Memory("people/a b.md"), TvLinks.resolve("https://192.168.0.200/#/memory/people/a%20b.md", o))
        assertEquals(TvLinkTarget.Visual("tv-app/index.html"), TvLinks.resolve("/#/visuals/tv-app/index.html", o))
        assertEquals(TvLinkTarget.Web("https://192.168.0.200/#/memory/../x.md"), TvLinks.resolve("/#/memory/../x.md", o)) // never a memory view
        for (bad in listOf("javascript:alert(1)", "file:///etc/passwd", "intent://x#Intent;end", "mailto:a@b.co", "", "#top")) {
            assertNull(bad, TvLinks.resolve(bad, o))
        }
    }

    @Test
    fun defaultPortsAreDroppedLikeChromiumReportsThem() {
        assertEquals("http://192.168.0.200/visualizations/a.html", TvLinks.withoutDefaultPort("http://192.168.0.200:80/visualizations/a.html"))
        assertEquals("https://h/x?y=1", TvLinks.withoutDefaultPort("https://h:443/x?y=1"))
        assertEquals("http://h", TvLinks.withoutDefaultPort("http://h:80"))
        assertEquals("http://h:8765/x", TvLinks.withoutDefaultPort("http://h:8765/x"))
        assertEquals("http://h:443/x", TvLinks.withoutDefaultPort("http://h:443/x"))
        assertEquals("https://h:80/x", TvLinks.withoutDefaultPort("https://h:80/x"))
        assertEquals("http://h:8080/x", TvLinks.withoutDefaultPort("http://h:8080/x"))
        assertEquals("ftp://h:80/x", TvLinks.withoutDefaultPort("ftp://h:80/x"))
    }
}
