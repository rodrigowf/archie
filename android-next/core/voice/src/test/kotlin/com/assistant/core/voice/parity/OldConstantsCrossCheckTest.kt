package com.assistant.core.voice.parity

import com.assistant.core.testing.OldConstants
import org.junit.Assert.assertTrue
import org.junit.Test

/** spec 14 §6.2: every NEW `VoiceTuning` value equals the OLD one extracted at e871d05. */
class OldConstantsCrossCheckTest {
    @Test
    fun everyVoiceTuningValueEqualsTheOldExtractedValue() {
        val rows = OldConstants.forModule("voice").filter { it.newClass == VOICE_TUNING }
        assertTrue(rows.isNotEmpty())
        rows.forEach { OldConstants.assertNewMatchesOld(it, OWNER) }
    }
}
