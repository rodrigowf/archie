package com.assistant.archie.feature.memory

import com.assistant.core.markdown.LinkContext
import com.assistant.core.markdown.LinkTarget
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Where a link in a memory document goes (spec 14 §4.1, MEM-2). */
sealed interface MemoryLink {
    /** Another memory file, opened in the app. [path] is relative to context/memory/; [fragment] without '#'. */
    data class Document(val path: String, val fragment: String? = null) : MemoryLink

    /** `#heading` in the same document: scroll to the heading with that GitHub slug. */
    data class Anchor(val id: String) : MemoryLink

    /** An absolute URL (`https:`, `mailto:` …): Custom Tab / the system handler. */
    data class External(val url: String) : MemoryLink

    /**
     * A path on the backend that is not a memory document (`/markdown_reader.html?…`, a raw
     * `/memory/x.mp4`, `../../api/app.py`): opened externally at `<origin><path>`. [path] starts with '/'.
     */
    data class Server(val path: String) : MemoryLink
}

/**
 * `MemoryLinkResolver.resolve(currentPath, href)` (spec 14 §4.1). Web parity with frontend-next
 * `features/markdown/links.ts` (`resolveMemoryHref`), built on `:core:markdown`'s [LinkTarget]:
 *
 * - `#x` → [MemoryLink.Anchor];
 * - any scheme or `//host` → [MemoryLink.External];
 * - `/memory/…​.md` → [MemoryLink.Document];
 * - a relative `….md` → resolved against the current file's folder, `.` and `..` normalised, `#fragment`
 *   kept apart → [MemoryLink.Document];
 * - a relative path that escapes the memory root, or any other relative target (a script, a folder,
 *   an `.html`) → [MemoryLink.Server] at the URL a browser would resolve against `/memory/<currentPath>`
 *   (MEM-2: "a result escaping the root … opens externally");
 * - any other `/path` (including non-markdown files under `/memory/`) → [MemoryLink.Server].
 */
object MemoryLinkResolver {
    fun resolve(currentPath: String, href: String): MemoryLink {
        val h = href.trim()
        return when (val t = LinkTarget.classify(h, LinkContext.MemoryDocument(currentPath))) {
            is LinkTarget.Anchor -> MemoryLink.Anchor(t.id)
            is LinkTarget.External -> MemoryLink.External(t.url)
            is LinkTarget.Memory ->
                if (t.path.endsWith(".md", ignoreCase = true)) MemoryLink.Document(t.path, t.fragment)
                else MemoryLink.Server(h.takeIf { it.startsWith("/") } ?: browserResolve(currentPath, h))
            is LinkTarget.BackendPath ->
                if (h.startsWith("/")) MemoryLink.Server(h) else MemoryLink.Server(browserResolve(currentPath, h))
        }
    }

    /** RFC 3986 resolution of [href] against `/memory/<currentPath>` (excess `..` are dropped, like a browser). */
    private fun browserResolve(currentPath: String, href: String): String {
        val base = "http://h/memory/".toHttpUrlOrNull()!!.newBuilder().addPathSegments(currentPath.trim('/')).build()
        val u = base.resolve(href) ?: return "/memory/"
        return buildString {
            append(u.encodedPath)
            u.encodedQuery?.let { append('?').append(it) }
            u.encodedFragment?.let { append('#').append(it) }
        }
    }

    /** `/memory/<path>` with every segment percent-encoded (MEM-3). */
    fun memoryFileUrl(path: String): String =
        "/memory/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }

    /** RFC 3986 path-segment encoding (spaces as %20, never '+'). */
    fun encodeSegment(segment: String): String =
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20").replace("%7E", "~")
}
