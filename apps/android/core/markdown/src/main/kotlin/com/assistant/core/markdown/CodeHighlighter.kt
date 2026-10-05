package com.assistant.core.markdown

import androidx.compose.runtime.Immutable
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.PhraseLocation
import dev.snipme.highlights.model.SyntaxLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Token classes, mapped to theme roles by the renderer (no colors here: spec 14 §3.3). */
enum class CodeTokenKind { Keyword, String, Literal, Comment, Annotation }

@Immutable
data class CodeSpan(val start: Int, val end: Int, val kind: CodeTokenKind)

@Immutable
data class CodeHighlight(val spans: List<CodeSpan>)

/**
 * Syntax highlighting with `dev.snipme:highlights` (pure Kotlin). Only for closed code blocks,
 * off the main thread, cached by (language, text). Unknown languages and blocks over
 * [MAX_CHARS] stay plain (spec 14 §3.3).
 */
object CodeHighlighter {
    const val MAX_CHARS: Int = 16 * 1024
    private const val CACHE_SIZE = 128

    private val ALIASES: Map<String, SyntaxLanguage> = buildMap {
        fun put(lang: SyntaxLanguage, vararg names: String) = names.forEach { put(it, lang) }
        put(SyntaxLanguage.KOTLIN, "kotlin", "kt", "kts")
        put(SyntaxLanguage.JAVA, "java")
        put(SyntaxLanguage.JAVASCRIPT, "javascript", "js", "jsx", "mjs", "cjs", "json", "jsonc", "json5")
        put(SyntaxLanguage.TYPESCRIPT, "typescript", "ts", "tsx", "mts")
        put(SyntaxLanguage.PYTHON, "python", "py", "python3", "py3")
        put(SyntaxLanguage.SHELL, "shell", "sh", "bash", "zsh", "console", "shellsession", "fish")
        put(SyntaxLanguage.RUBY, "ruby", "rb")
        put(SyntaxLanguage.RUST, "rust", "rs")
        put(SyntaxLanguage.GO, "go", "golang")
        put(SyntaxLanguage.C, "c", "h")
        put(SyntaxLanguage.CPP, "cpp", "c++", "cc", "cxx", "hpp", "hh")
        put(SyntaxLanguage.CSHARP, "csharp", "cs", "c#")
        put(SyntaxLanguage.SWIFT, "swift")
        put(SyntaxLanguage.DART, "dart")
        put(SyntaxLanguage.PHP, "php")
        put(SyntaxLanguage.PERL, "perl", "pl")
        put(SyntaxLanguage.COFFEESCRIPT, "coffeescript", "coffee")
    }

    private data class Key(val lang: SyntaxLanguage, val length: Int, val hash: Int)

    private val cache = object : LinkedHashMap<Key, CodeHighlight>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, CodeHighlight>?) = size > CACHE_SIZE
    }

    fun languageOf(lang: String?): SyntaxLanguage? = lang?.trim()?.lowercase()?.let { ALIASES[it] }

    fun isHighlightable(lang: String?, code: String): Boolean = languageOf(lang) != null && code.length <= MAX_CHARS

    /** The cached result, or null (never computes). */
    fun cached(lang: String?, code: String): CodeHighlight? {
        val l = languageOf(lang) ?: return null
        return synchronized(cache) { cache[Key(l, code.length, code.hashCode())] }
    }

    /** Highlights on the calling thread (cached). Null when the block stays plain. */
    fun highlight(lang: String?, code: String): CodeHighlight? {
        if (!isHighlightable(lang, code)) return null
        val l = languageOf(lang)!!
        val key = Key(l, code.length, code.hashCode())
        synchronized(cache) { cache[key] }?.let { return it }
        val result = compute(l, code)
        synchronized(cache) { cache[key] = result }
        return result
    }

    suspend fun highlightAsync(lang: String?, code: String): CodeHighlight? =
        cached(lang, code) ?: if (isHighlightable(lang, code)) withContext(Dispatchers.Default) { highlight(lang, code) } else null

    private fun compute(lang: SyntaxLanguage, code: String): CodeHighlight {
        val structure = try {
            Highlights.Builder().code(code).language(lang).build().getCodeStructure()
        } catch (_: RuntimeException) {
            return CodeHighlight(emptyList())
        }
        // Paint low → high priority so overlaps resolve (a keyword inside a string is a string).
        val kinds = arrayOfNulls<CodeTokenKind>(code.length)
        fun paint(set: Set<PhraseLocation>, kind: CodeTokenKind) {
            for (p in set) {
                val s = p.start.coerceIn(0, code.length)
                val e = p.end.coerceIn(s, code.length)
                for (i in s until e) kinds[i] = kind
            }
        }
        paint(structure.literals, CodeTokenKind.Literal)
        paint(structure.keywords, CodeTokenKind.Keyword)
        paint(structure.annotations, CodeTokenKind.Annotation)
        paint(structure.strings, CodeTokenKind.String)
        paint(structure.comments, CodeTokenKind.Comment)
        paint(structure.multilineComments, CodeTokenKind.Comment)
        val spans = ArrayList<CodeSpan>()
        var i = 0
        while (i < kinds.size) {
            val k = kinds[i]
            if (k == null) {
                i++
                continue
            }
            var j = i + 1
            while (j < kinds.size && kinds[j] == k) j++
            spans += CodeSpan(i, j, k)
            i = j
        }
        return CodeHighlight(spans)
    }
}
