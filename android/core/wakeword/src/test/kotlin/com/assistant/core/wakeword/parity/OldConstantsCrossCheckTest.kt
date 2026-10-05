package com.assistant.core.wakeword.parity

import com.assistant.core.testing.OldConstants
import org.junit.Assert.assertTrue
import org.junit.Test

/** spec 14 §6.2: every NEW `WakeTuning` value equals the OLD one extracted at e871d05. */
class OldConstantsCrossCheckTest {
    @Test
    fun everyWakeTuningValueEqualsTheOldExtractedValue() {
        val rows = OldConstants.forModule("wakeword").filter { it.newClass == WAKE_TUNING }
        assertTrue(rows.size > 40)
        rows.forEach { OldConstants.assertNewMatchesOld(it, OWNER) }
    }
}
