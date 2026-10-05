package com.assistant.core.markdown

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Spec 14 §3.3: code and markdown colors come from the theme tokens only (fixes inv03 §8 bug 10,
 * the hard-coded syntax palette of the old renderer). No color literal in this module.
 */
class NoColorLiteralsTest {
    private val colorLiteral = listOf(
        Regex("""0[xX][0-9a-fA-F]{6,8}\b"""),
        Regex("""#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?\b"""),
        Regex("""Color\(\s*(red\s*=\s*)?[0-9.]+f?\s*,"""),
        Regex("""parseColor\("""),
    )

    @Test
    fun noColorLiterals() {
        val files = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty())
        val hits = files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (colorLiteral.any { it.containsMatchIn(line) }) "${f.path}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("Color literals:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
