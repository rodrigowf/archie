package com.assistant.core.voicehost.parity

import com.assistant.core.testing.PinsConstant
import com.assistant.core.voicehost.ports.WakeStartKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wake start dedupe (inv04 §3.5, §4.5; ports `StartWakeWordParityTest`; RS-34). */
class WakeStartDedupeParityTest {
    private val key = WakeStartKey("my friend", "wake up", 1.0f)

    @Test
    fun theFirstCallIsNeverDeduped() = assertFalse(hostCore.wakeStartDeduper().shouldDedupe(key, 10_000))

    /** RS-34 (`0b2cbb5`): a duplicate start within 3 s is deduped (redelivery gaps of 20 ms and 1.3 s). */
    @Test
    fun rs34_duplicateStartWithinThreeSecondsIsDeduped() {
        val d = hostCore.wakeStartDeduper()
        d.shouldDedupe(key, 10_000)
        assertTrue(d.shouldDedupe(key, 10_020))
        assertTrue(d.shouldDedupe(key, 11_300))
    }

    @Test
    @PinsConstant("service.dedupe_strict_lt")
    fun theWindowIsStrictlyLessThanThreeSeconds() {
        val d = hostCore.wakeStartDeduper()
        d.shouldDedupe(key, 10_000)
        assertTrue(d.shouldDedupe(key, 12_999))
        val e = hostCore.wakeStartDeduper()
        e.shouldDedupe(key, 10_000)
        assertFalse("exactly 3000 ms is allowed through", e.shouldDedupe(key, 13_000))
    }

    @Test
    fun outsideTheWindowIsAllowed() {
        val d = hostCore.wakeStartDeduper()
        d.shouldDedupe(key, 10_000)
        assertFalse(d.shouldDedupe(key, 20_000))
    }

    @Test
    @PinsConstant("service.dedupe_key")
    fun theKeyIsTalkWakeAndGainOnly() {
        val d = hostCore.wakeStartDeduper()
        d.shouldDedupe(key, 10_000)
        assertFalse(d.shouldDedupe(key.copy(wakeWord = "hey archie"), 10_010))
        assertFalse(d.shouldDedupe(WakeStartKey("hello", "hey archie", 1.0f), 10_020))
        assertFalse(d.shouldDedupe(WakeStartKey("hello", "hey archie", 1.3f), 10_030))
    }

    @Test
    fun clearForgetsTheLastKey() {
        val d = hostCore.wakeStartDeduper()
        d.shouldDedupe(key, 10_000)
        d.clear()
        assertFalse(d.shouldDedupe(key, 10_100))
    }
}
