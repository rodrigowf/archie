package com.assistant.core.audio.parity

import com.assistant.core.testing.OldConstants
import org.junit.Assert.assertTrue
import org.junit.Test

/** spec 14 §6.2: every NEW `AudioTuning` value equals the OLD one extracted at e871d05. */
class OldConstantsCrossCheckTest {
    @Test
    fun everyAudioTuningValueEqualsTheOldExtractedValue() {
        val rows = OldConstants.forModule("audio").filter { it.newRef != null }
        assertTrue(rows.isNotEmpty())
        rows.forEach { OldConstants.assertNewMatchesOld(it, OWNER) }
    }
}
