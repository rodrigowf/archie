package com.assistant.core.markdown

/**
 * Where a markdown link goes (spec 14 §3.3). The host decides how to open each kind:
 * [Memory] → in-app memory document, [BackendPath] → in-app visual or Custom Tab against the
 * current server origin, [External] → Custom Tab, [Anchor] → scroll to a heading of the same
 * document ([MdNode.Heading.anchor]).
 */
sealed interface LinkTarget {
    /** [path] is relative to context/memory/ (POSIX); [fragment] without '#'. */
    data class Memory(val path: String, val fragment: String? = null) : LinkTarget
    data class BackendPath(val path: String) : LinkTarget
    data class External(val url: String) : LinkTarget
    data class Anchor(val id: String) : LinkTarget

    companion object {
        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        private const val MEMORY_PREFIX = "/memory/"

        /**
         * Classifies [href]. [context] is the memory file the link appears in (relative to
         * context/memory/), or null in chat. Rules (web parity, frontend `links.ts`):
         * `#x` → anchor; any scheme or `//host` → external; `/memory/…` → memory; a relative
         * `.md` inside a memory document → memory, resolved against the document's folder (a path
         * that climbs out of the root is not a memory link and stays a backend path); any other
         * `/path` (or relative path in chat) → backend path.
         */
        fun classify(href: String, context: LinkContext = LinkContext.Chat): LinkTarget {
            val h = href.trim()
            if (h.startsWith("#")) return Anchor(h.substring(1))
            if (h.startsWith("//") || SCHEME.containsMatchIn(h)) return External(h)
            val fragment = h.substringAfter('#', "").takeIf { it.isNotEmpty() }
            val pathPart = h.substringBefore('#').substringBefore('?')
            if (pathPart.startsWith(MEMORY_PREFIX)) {
                val p = normalize(pathPart.removePrefix(MEMORY_PREFIX).split('/'))
                if (p != null) return Memory(p, fragment)
            }
            val memoryDoc = (context as? LinkContext.MemoryDocument)?.path
            if (memoryDoc != null && !pathPart.startsWith("/") && decode(pathPart).endsWith(".md", ignoreCase = true)) {
                val base = memoryDoc.split('/').filter { it.isNotEmpty() }.dropLast(1)
                val p = normalize(base + pathPart.split('/'))
                if (p != null) return Memory(p, fragment)
            }
            return BackendPath(if (h.startsWith("/")) h else "/$h")
        }

        private fun normalize(segments: List<String>): String? {
            val out = ArrayList<String>()
            for (raw in segments) {
                val s = decode(raw)
                when (s) {
                    "", "." -> Unit
                    ".." -> if (out.isEmpty()) return null else out.removeAt(out.size - 1)
                    else -> out += s
                }
            }
            return out.takeIf { it.isNotEmpty() }?.joinToString("/")
        }

        private fun decode(s: String): String = try {
            java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            s
        }
    }
}

sealed interface LinkContext {
    data object Chat : LinkContext
    data class MemoryDocument(val path: String) : LinkContext
}

/** GitHub heading slugs (`## Hello, World!` → `hello-world`), de-duplicated per document. */
class Slugger {
    private val seen = HashMap<String, Int>()

    fun slug(text: String): String {
        val base = slugOf(text)
        val n = seen[base]
        seen[base] = (n ?: 0) + 1
        return if (n == null) base else "$base-$n"
    }

    /** The slug [slug] would return next, without recording it (for the streaming tail). */
    fun peek(text: String): String {
        val base = slugOf(text)
        val n = seen[base]
        return if (n == null) base else "$base-$n"
    }

    companion object {
        fun slugOf(text: String): String {
            val sb = StringBuilder(text.length)
            for (ch in text.trim().lowercase()) {
                when {
                    ch == ' ' -> sb.append('-')
                    ch == '-' || ch == '_' -> sb.append(ch)
                    Character.isLetterOrDigit(ch) -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
