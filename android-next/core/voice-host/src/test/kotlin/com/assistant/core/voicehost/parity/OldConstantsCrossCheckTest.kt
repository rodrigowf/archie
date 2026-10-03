package com.assistant.core.voicehost.parity

import com.assistant.core.testing.OldConstants
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** spec 14 §6.2: every NEW `HostTuning` value equals the OLD one extracted at e871d05. */
@Ignore("A-08")
class OldConstantsCrossCheckTest {
    @Test
    fun everyHostTuningValueEqualsTheOldExtractedValue() {
        val rows = OldConstants.forModule("voice-host").filter { it.newClass == HOST_TUNING }
        assertTrue(rows.isNotEmpty())
        rows.forEach { OldConstants.assertNewMatchesOld(it, OWNER) }
    }
}
