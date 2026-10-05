package com.assistant.peripheral.face

/**
 * Markdown → plain text for the face captions (spec 14 §5.2): a regex pass, no parser on the
 * A300M. Handles fences, inline code, emphasis, headings, list/quote markers, links and images.
 */
object PlainText {
    private val FENCE = Regex("```[^\\n]*\\n?([\\s\\S]*?)(```|$)")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val AUTOLINK = Regex("<(https?://[^>]+)>")
    private val HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s+")
    private val QUOTE = Regex("(?m)^\\s*>\\s?")
    private val BULLET = Regex("(?m)^\\s*[-*+]\\s+(\\[[ xX]]\\s+)?")
    private val ORDERED = Regex("(?m)^\\s*\\d+[.)]\\s+")
    private val RULE = Regex("(?m)^\\s*([-*_]\\s*){3,}$")
    private val BOLD = Regex("(\\*\\*|__)(.+?)\\1")
    private val ITALIC = Regex("(?<![\\w*])([*_])(?!\\s)(.+?)(?<!\\s)\\1(?![\\w*])")
    private val STRIKE = Regex("~~(.+?)~~")
    private val INLINE_CODE = Regex("`+([^`]*)`+")
    private val SPACES = Regex("[ \\t]+")
    private val BLANK_LINES = Regex("\\n{2,}")

    fun strip(markdown: String, maxChars: Int = Int.MAX_VALUE): String {
        var t = markdown.replace("\r\n", "\n")
        t = FENCE.replace(t) { it.groupValues[1].trimEnd() }
        t = IMAGE.replace(t) { it.groupValues[1] }
        t = LINK.replace(t) { it.groupValues[1] }
        t = AUTOLINK.replace(t) { it.groupValues[1] }
        t = RULE.replace(t, "")
        t = HEADING.replace(t, "")
        t = QUOTE.replace(t, "")
        t = BULLET.replace(t, "")
        t = ORDERED.replace(t, "")
        t = INLINE_CODE.replace(t) { it.groupValues[1] }
        t = BOLD.replace(t) { it.groupValues[2] }
        t = STRIKE.replace(t) { it.groupValues[1] }
        t = ITALIC.replace(t) { it.groupValues[2] }
        t = SPACES.replace(t, " ")
        t = BLANK_LINES.replace(t, "\n").trim()
        return if (t.length > maxChars) t.take(maxChars - 1).trimEnd() + "…" else t
    }
}
