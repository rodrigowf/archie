package com.assistant.core.markdown

import androidx.compose.runtime.Immutable
import org.commonmark.ext.front.matter.YamlFrontMatterExtension
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor
import org.commonmark.parser.Parser

/**
 * Memory-file frontmatter (spec 14 §4.1, inv02 F-37; web parity: frontend `frontmatter.ts`).
 *
 * Every file under context/memory/ starts with a YAML block. Rendered as markdown it would become
 * a run-on paragraph between two rules, so it is split off and shown as a collapsed card; the body
 * is rendered by [MarkdownDocument.parse].
 */
@Immutable
data class FrontmatterSplit(
    /** The YAML between the fences, verbatim; null when the file has none. */
    val frontmatter: String?,
    /** The markdown after the closing fence (the whole input when there is no frontmatter). */
    val body: String,
)

/**
 * A lenient reading of the frontmatter for the card's summary line: scalars and lists only
 * (`commonmark-ext-yaml-front-matter`, plus inline `[a, b]` lists). Nested maps, anchors and
 * multi-line strings are ignored. A parse failure leaves [fields] empty; the card still shows the
 * raw text.
 */
@Immutable
data class FrontmatterSummary(val fields: Map<String, List<String>>) {
    fun scalar(key: String): String? = fields[key]?.singleOrNull()

    val category: String? get() = scalar("category")
    val modified: String? get() = scalar("modified")
    val tags: List<String> get() = fields["tags"].orEmpty()
    val references: List<String> get() = fields["references"].orEmpty()

    /** "architecture · 2026-10-01 · voice, webrtc · 4 refs" (absent parts are skipped). */
    fun line(maxTags: Int = 3): String = buildList {
        category?.let { add(it) }
        modified?.let { add(it) }
        if (tags.isNotEmpty()) add(tags.take(maxTags).joinToString(", "))
        if (references.isNotEmpty()) add("${references.size} ref" + if (references.size == 1) "" else "s")
    }.joinToString(" · ")
}

object Frontmatter {
    // Leading BOM, `---` (trailing spaces ok), content, `---`; CRLF tolerant; an empty block ok.
    private val FRONTMATTER = Regex("^\uFEFF?---[ \\t]*\\r?\\n(?:([\\s\\S]*?)\\r?\\n)?---[ \\t]*(?:\\r?\\n|$)")

    private val yamlParser: Parser by lazy {
        Parser.builder().extensions(listOf(YamlFrontMatterExtension.create())).build()
    }

    fun split(raw: String): FrontmatterSplit {
        val m = FRONTMATTER.find(raw) ?: return FrontmatterSplit(null, raw)
        return FrontmatterSplit(m.groupValues[1], raw.substring(m.range.last + 1))
    }

    fun summarize(frontmatter: String): FrontmatterSummary = try {
        val doc = yamlParser.parse("---\n" + frontmatter.replace("\r\n", "\n") + "\n---\n")
        val v = YamlFrontMatterVisitor()
        doc.accept(v)
        FrontmatterSummary(
            v.data.mapValues { (_, values) ->
                values.flatMap { value ->
                    val t = value.trim()
                    if (t.startsWith("[") && t.endsWith("]")) {
                        t.substring(1, t.length - 1).split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }
                    } else {
                        listOf(unquote(t))
                    }
                }
            },
        )
    } catch (_: RuntimeException) {
        FrontmatterSummary(emptyMap())
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && (s[0] == '"' || s[0] == '\'') && s.last() == s[0]) s.substring(1, s.length - 1) else s
}
