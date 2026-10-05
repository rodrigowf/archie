package com.assistant.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature coverage (spec 14 §3.3, inv03 §5 gaps vs web): tables with alignment and the full inline
 * AST in cells, task lists, nested lists, h1–h6 with anchors, strikethrough, autolinks, images,
 * fenced code without a language, raw HTML as text.
 */
class MarkdownFeatureTest {

    private fun parse(md: String) = MarkdownDocument.parse(md)

    @Test
    fun tablesKeepAlignmentAndTheFullInlineAstInCells() {
        val t = parse(Corpus.text("03-web-tables")).filterIsInstance<MdNode.Table>()
        assertEquals(2, t.size)
        val first = t[0]
        assertEquals(listOf(TableAlign.Left, TableAlign.Center, TableAlign.Right), first.aligns)
        assertEquals(3, first.rows.size)
        // **Read** | `ok` | Opens [the file](…): bold, code and link survive in cells (never blank).
        assertEquals(listOf(MdInline.Strong(listOf(MdInline.Text("Read")))), first.rows[0][0])
        assertEquals(listOf(MdInline.Code("ok")), first.rows[0][1])
        assertTrue(first.rows[0][2].any { it is MdInline.Link && it.href == "https://example.com/read" })
        // Escaped pipe inside code.
        assertTrue(first.rows[1][2].any { it is MdInline.Code && it.code == "a|b" })
        assertEquals(12, t[1].header.size)
    }

    @Test
    fun raggedRowsArePaddedToTheHeader() {
        val t = parse(Corpus.text("35-syn-tables")).first() as MdNode.Table
        assertTrue(t.rows.all { it.size == 3 })
    }

    @Test
    fun taskListsAndNestedLists() {
        val nodes = parse(Corpus.text("04-web-tasklists"))
        val items = nodes.filterIsInstance<MdNode.ListBlock>()
        assertEquals(listOf(true, false, false), items.map { it.items.single().task })
        assertEquals(listOf(false, true, true), items.map { it.continuation })
        val nested = items[2].items.single().children.filterIsInstance<MdNode.ListBlock>().single()
        assertEquals(true, nested.items.single().task)
        assertEquals(1, nested.level)
    }

    @Test
    fun orderedListsKeepNumbersAcrossItemNodes() {
        val lists = parse("3. c\n4. d\n5. e\n\n1) x\n2) y\n").filterIsInstance<MdNode.ListBlock>()
        assertEquals(listOf(3, 4, 5, 1, 2), lists.map { it.start })
        assertEquals(listOf('.', '.', '.', ')', ')'), lists.map { it.marker })
        assertEquals(listOf(false, true, true, false, true), lists.map { it.continuation })
        // The same while streaming item by item.
        val doc = MarkdownDocument()
        "3. c\n4. d\n5. e\n\n1) x\n2) y\n".chunked(2).forEach { doc.append(it) }
        assertEquals(lists, doc.finish().stable.filterIsInstance<MdNode.ListBlock>())
    }

    @Test
    fun headingsOneToSixWithDedupedAnchors() {
        val h = parse(Corpus.text("31-syn-headings")).filterIsInstance<MdNode.Heading>()
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 1, 2, 1, 2, 2), h.map { it.level })
        assertEquals(listOf("duplicate", "duplicate-1"), h.takeLast(2).map { it.anchor })
        assertEquals("setext-one", h[6].anchor)
    }

    @Test
    fun inlineVariety() {
        val p = parse(Corpus.text("34-syn-inline")).first() as MdNode.Paragraph
        val flat = p.inlines
        assertTrue(flat.any { it is MdInline.Strike }) // single and double tilde (remark-gfm parity)
        assertTrue(flat.any { it is MdInline.Link && it.href == "https://example.com/a" }) // <autolink>
        assertTrue(flat.any { it is MdInline.Link && it.href == "https://example.com/b" }) // bare URL (GFM)
        assertTrue(flat.any { it is MdInline.Image && it.src == "https://example.com/d.png" && it.alt == "diagram" })
        assertTrue(flat.contains(MdInline.HardBreak))
        assertTrue(flat.any { it is MdInline.Code && it.code == "code with `` backticks" })
    }

    @Test
    fun fencedCodeWithoutLanguageIsStillABlock() {
        val code = parse(Corpus.text("02-web-code")).filterIsInstance<MdNode.CodeBlock>()
        assertEquals(listOf("python", null, "ts", "markdown", null, "unknown-lang"), code.map { it.lang })
        assertEquals("plain fence without a language\nsecond line", code[1].code)
        assertEquals("indented code block\nkeeps its spaces", code[4].code)
    }

    @Test
    fun rawHtmlRendersAsText() {
        val nodes = parse(Corpus.text("37-syn-html"))
        assertTrue((nodes.first() as MdNode.Paragraph).inlines.single().let { it is MdInline.Text && it.text.startsWith("<details>") })
    }

    @Test
    fun linkTargets() {
        val memory = LinkContext.MemoryDocument("assistant/architecture/voice.md")
        assertEquals(LinkTarget.Memory("assistant/infra/ssh.md", "setup"), LinkTarget.classify("../infra/ssh.md#setup", memory))
        assertEquals(LinkTarget.Memory("assistant/architecture/wake word.md"), LinkTarget.classify("wake%20word.md", memory))
        assertEquals(LinkTarget.Memory("MEMORY.md"), LinkTarget.classify("/memory/MEMORY.md"))
        assertEquals(LinkTarget.BackendPath("/../../../etc.md"), LinkTarget.classify("../../../etc.md", memory))
        assertEquals(LinkTarget.External("https://x.io/a"), LinkTarget.classify("https://x.io/a", memory))
        assertEquals(LinkTarget.External("mailto:a@b.co"), LinkTarget.classify("mailto:a@b.co"))
        assertEquals(LinkTarget.External("//cdn.example.com/x"), LinkTarget.classify("//cdn.example.com/x"))
        assertEquals(LinkTarget.Anchor("lifecycle"), LinkTarget.classify("#lifecycle"))
        assertEquals(LinkTarget.BackendPath("/uploads/a.png"), LinkTarget.classify("/uploads/a.png"))
        assertEquals(LinkTarget.BackendPath("/viz.html"), LinkTarget.classify("viz.html"))
        // In chat, a relative .md is not a memory link (no document to resolve against).
        assertEquals(LinkTarget.BackendPath("/x.md"), LinkTarget.classify("x.md"))
    }

    @Test
    fun githubSlugs() {
        assertEquals("hello-world", Slugger.slugOf("Hello, World!"))
        assertEquals("api_v2--notes", Slugger.slugOf("API_v2 — notes"))
        assertEquals("café-ü", Slugger.slugOf("Café Ü"))
    }

    @Test
    fun frontmatterSplit() {
        val split = Frontmatter.split(Corpus.text("06-web-memory-doc"))
        assertTrue(split.frontmatter!!.startsWith("category: architecture"))
        assertTrue(split.body.startsWith("# Voice subsystem"))
        assertEquals(FrontmatterSplit("a: 1", "body"), Frontmatter.split("---\r\na: 1\r\n---\r\nbody"))
        assertEquals(FrontmatterSplit("a: 1", "body"), Frontmatter.split("\uFEFF---\na: 1\n---\nbody"))
        assertEquals(FrontmatterSplit("", "body"), Frontmatter.split("---\n---\nbody"))
        assertEquals(FrontmatterSplit(null, "no frontmatter\n---\n"), Frontmatter.split("no frontmatter\n---\n"))
        assertEquals(FrontmatterSplit(null, "---\nunclosed"), Frontmatter.split("---\nunclosed"))
        // The body never contains the YAML (it used to render as a run-on paragraph between rules).
        assertTrue(MarkdownDocument.parse(split.body).none { it is MdNode.Rule })
    }

    @Test
    fun frontmatterSummary() {
        val s = Frontmatter.summarize(Frontmatter.split(Corpus.text("06-web-memory-doc")).frontmatter!!)
        assertEquals("architecture", s.category)
        assertEquals("2026-10-01", s.modified)
        assertEquals(listOf("voice", "webrtc"), s.tags)
        assertEquals(2, s.references.size)
        assertEquals("architecture · 2026-10-01 · voice, webrtc · 2 refs", s.line())
        // Garbage never throws (the card still shows the raw text).
        Frontmatter.summarize(": : :\n  - [\n\t|")
    }

    @Test
    fun highlighter() {
        val code = "fun main() {\n    // greet\n    val s = \"hi\" + 42\n}"
        val h = CodeHighlighter.highlight("kotlin", code)!!
        fun kindAt(word: String) = h.spans.firstOrNull { code.indexOf(word) in it.start until it.end }?.kind
        assertEquals(CodeTokenKind.Keyword, kindAt("fun"))
        assertEquals(CodeTokenKind.Comment, kindAt("// greet"))
        assertEquals(CodeTokenKind.String, kindAt("\"hi\""))
        assertEquals(CodeTokenKind.Literal, kindAt("42"))
        assertTrue(CodeHighlighter.highlight("kt", code) === CodeHighlighter.highlight("kotlin", code)) // cached
        assertTrue(CodeHighlighter.cached("kotlin", code) != null)
        assertNull(CodeHighlighter.highlight(null, code))
        assertNull(CodeHighlighter.highlight("unknown-lang", code))
        assertNull(CodeHighlighter.highlight("python", "x = 1\n".repeat(3_000))) // > 16 KB stays plain
        for (alias in listOf("py", "sh", "bash", "ts", "js", "json", "rs", "go")) {
            assertTrue(alias, CodeHighlighter.languageOf(alias) != null)
        }
    }

    @Test
    fun cacheIsAnLruKeyedByIdLengthAndHash() {
        val cache = MarkdownCache(capacity = 3)
        val a = cache.getBlocking("a", "# A")
        assertTrue(a === cache.getBlocking("a", "# A"))
        assertTrue(cache.peek("a", "# A changed") == null)
        cache.getBlocking("b", "b")
        cache.getBlocking("c", "c")
        cache.getBlocking("a", "# A") // touch a
        cache.getBlocking("d", "d") // evicts b
        assertEquals(3, cache.size)
        assertNull(cache.peek("b", "b"))
        assertTrue(cache.peek("a", "# A") != null)
    }
}
