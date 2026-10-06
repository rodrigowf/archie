package com.assistant.core.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-01 DoD: no color literals outside TokenAdapter.kt, and no direct use of the generated token
 * package (com.assistant.design) outside it either, so the tokens stay the single source and the
 * adapter stays the single seam (spec 14 §7, risk X14).
 */
class NoHexColorLiteralsTest {
    private val sources = File("src/main/kotlin")
    private val allowed = setOf("TokenAdapter.kt")

    private val colorLiteral = listOf(
        Regex("""0[xX][0-9a-fA-F]{6,8}\b"""), // Color(0xFF112233), 0x112233
        Regex("""#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?\b"""), // "#112233"
        Regex("""Color\(\s*(red\s*=\s*)?[0-9.]+f?\s*,"""), // Color(0.1f, 0.2f, 0.3f)
        Regex("""parseColor\("""),
    )

    private fun kotlinFiles() = sources.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in allowed }.toList()

    @Test
    fun sourcesExist() {
        assertTrue("run from the module dir; ${sources.absolutePath} missing", kotlinFiles().isNotEmpty())
    }

    @Test
    fun noColorLiteralsOutsideTokenAdapter() {
        val hits = kotlinFiles().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (colorLiteral.any { it.containsMatchIn(line) }) "${f.path}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("Color literals outside TokenAdapter.kt:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun generatedTokensOnlyThroughAdapter() {
        val hits = kotlinFiles().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (line.contains("com.assistant.design.")) "${f.path}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("Use com.assistant.core.design (TokenAdapter) instead:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
