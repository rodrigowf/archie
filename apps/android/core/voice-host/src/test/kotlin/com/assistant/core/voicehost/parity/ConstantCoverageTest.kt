package com.assistant.core.voicehost.parity

import com.assistant.core.testing.ArchieRoot
import com.assistant.core.testing.OldConstants
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec 14 A-04 DoD: every inv04 §4 **LB** / *wire* constant has an assertion. Each row of
 * `old_constants.json` with label LB or wire must be referenced as a quoted id (a `TuningPins` call
 * or `@PinsConstant`) by a test in the voice modules. Runs now (source scan).
 */
class ConstantCoverageTest {
    private val excluded = setOf("ConstantCoverageTest.kt", "OldConstantsSnapshotTest.kt")

    @Test
    fun everyLoadBearingAndWireConstantIsAsserted() {
        val sources = ArchieRoot.testSources(*ArchieRoot.voiceModules)
            .filter { it.name !in excluded }
            .joinToString("\n") { it.readText() }
        val pinned = OldConstants.all.filter { it.isPinned }
        assertTrue("expected the full inv04 §4 table, got ${pinned.size} rows", pinned.size >= 100)
        val missing = pinned.filter { "\"${it.id}\"" !in sources }.map { it.id }
        assertTrue("LB/wire constants without an assertion: $missing", missing.isEmpty())
    }

    @Test
    fun everyScalarRowNamesItsNewTuningConstant() {
        val scalar = setOf("int", "long", "double", "float", "string", "long_list", "string_set")
        val unnamed = OldConstants.all
            .filter { it.isPinned && it.type in scalar && it.newRef == null && it.id != "vosk.no_compress" }
            .map { it.id }
        assertTrue("scalar rows without a new Tuning name: $unnamed", unnamed.isEmpty())
    }
}
