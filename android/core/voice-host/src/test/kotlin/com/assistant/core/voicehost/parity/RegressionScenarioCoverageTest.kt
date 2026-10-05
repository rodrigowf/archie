package com.assistant.core.voicehost.parity

import com.assistant.core.testing.ArchieRoot
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec 14 §6.1: every regression scenario RS-01…RS-46 of inv04 §11.1 has at least one named `@Test`
 * (`fun rsNN_…`) somewhere in the voice modules' test sources. Runs now (source scan), so a scenario
 * can never silently lose its test.
 */
class RegressionScenarioCoverageTest {
    private val testFun = Regex("""fun\s+`?rs(\d{2})_[A-Za-z0-9_]+""")

    private fun scenarioTests(): Map<Int, List<String>> {
        val found = mutableMapOf<Int, MutableList<String>>()
        for (file in ArchieRoot.testSources(*ArchieRoot.voiceModules)) {
            val lines = file.readLines()
            lines.forEachIndexed { i, line ->
                val m = testFun.find(line) ?: return@forEachIndexed
                val annotated = (maxOf(0, i - 4) until i).any { lines[it].contains("@Test") }
                if (annotated) found.getOrPut(m.groupValues[1].toInt()) { mutableListOf() } += "${file.name}:${i + 1}"
            }
        }
        return found
    }

    @Test
    fun everyRegressionScenarioHasANamedTest() {
        val found = scenarioTests()
        val missing = (1..46).filter { it !in found }
        assertTrue("RS without a named @Test: ${missing.joinToString { "RS-%02d".format(it) }}", missing.isEmpty())
    }

    @Test
    fun noTestClaimsAScenarioThatDoesNotExist() {
        val unknown = scenarioTests().keys.filter { it !in 1..46 }
        assertTrue("unknown RS numbers: $unknown", unknown.isEmpty())
    }
}
