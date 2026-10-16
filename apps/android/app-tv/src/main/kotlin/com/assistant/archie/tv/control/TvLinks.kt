package com.assistant.archie.tv.control

import com.assistant.core.markdown.InternalLinks
import java.net.URLDecoder

/** Where a URL opens on the TV (spec 12 §9.4 resolved for a screen with no browser). */
sealed interface TvLinkTarget {
    /** A visualization (path relative to context/public/) → the Viewer. */
    data class Visual(val path: String) : TvLinkTarget

    /** A memory note (path relative to context/memory/) → MemoryDoc. */
    data class Memory(val path: String, val fragment: String? = null) : TvLinkTarget

    /** Anything else on the web → the Viewer on that URL (the TV has no browser to hand it to). */
    data class Web(val url: String) : TvLinkTarget
}

/**
 * `open_url` and links inside the TV's own views (RD-3: "internal links resolve as in §9.4"). The
 * shared resolver ([InternalLinks], checked against `apps/protocol-fixtures/links/internal-links.json`)
 * decides visual vs memory; on top of it:
 *
 * - the web app's own deep links on the server (`/#/memory/<path>`, `/#/visuals/<path>`) open the
 *   same in-app views;
 * - a root-relative path that is not internal (`/api/…`, `/avatar-pipeline`) and a path with no
 *   leading slash (`visualizations/x.html`) are server pages → the Viewer at `<origin>/<path>`;
 * - other `http(s)` URLs → the Viewer; every other scheme (`file:`, `intent:`, `javascript:` …) is
 *   refused (null), never opened.
 */
object TvLinks {
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
    private val APP_HASH = Regex("^/?#/(memory|visuals)/(.+)$")

    fun resolve(href: String, origin: String, isVisual: (String) -> Boolean = { false }): TvLinkTarget? {
        val h = href.trim()
        if (h.isEmpty()) return null
        val base = origin.trimEnd('/')
        val ctx = InternalLinks.Context(base, isVisual)

        // The web app's hash routes on this server (`https://server/#/memory/x.md`, `/#/visuals/x.html`).
        val local = when {
            h.startsWith(base, ignoreCase = true) -> h.substring(base.length).ifEmpty { "/" }
            h.startsWith("/") || h.startsWith("#") -> h
            else -> null
        }
        if (local != null) {
            APP_HASH.find(local)?.let { m ->
                val path = decodePath(m.groupValues[2])
                if (path != null) return if (m.groupValues[1] == "memory") TvLinkTarget.Memory(path) else TvLinkTarget.Visual(path)
            }
        }

        if (h.startsWith("#")) return null // an in-page anchor of nothing
        when (val t = InternalLinks.resolve(h, ctx)) {
            is InternalLinks.Target.Visual -> return TvLinkTarget.Visual(t.path)
            is InternalLinks.Target.Memory -> return TvLinkTarget.Memory(t.path, t.fragment)
            null -> Unit
        }

        val scheme = SCHEME.find(h)?.groupValues?.get(1)?.lowercase()
        return when {
            scheme == "http" || scheme == "https" -> TvLinkTarget.Web(h)
            scheme != null -> null
            h.startsWith("//") -> TvLinkTarget.Web(base.substringBefore("://") + ":" + h)
            h.startsWith("/") -> TvLinkTarget.Web(base + h)
            // A bare server path as an agent may print it: try it as root-relative (visual / memory first).
            else -> resolve("/$h", base, isVisual)
        }
    }

    private fun decodePath(raw: String): String? {
        val parts = raw.split('/').filter { it.isNotEmpty() }.map {
            try {
                URLDecoder.decode(it.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                it
            }
        }
        if (parts.isEmpty() || parts.any { it == ".." || '/' in it }) return null
        return parts.joinToString("/")
    }
}
