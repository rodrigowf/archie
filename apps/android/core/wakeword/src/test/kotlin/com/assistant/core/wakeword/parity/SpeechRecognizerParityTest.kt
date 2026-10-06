package com.assistant.core.wakeword.parity

import com.assistant.core.testing.PinsConstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SpeechRecognizer fallback book-keeping (inv04 §4.3; ports `NoSpeechHealthParityTest`; RS-35, RS-27).
 * The fallback stays until Vosk is validated on every device (V6 deferred).
 */
class SpeechRecognizerParityTest {

    /** RS-35 (`39b1cec`): NO_SPEECH × 8 → unhealthy (≥, not ==); refresh at 2 in a row (`b753ac5`). */
    @Test
    fun rs35_noSpeechRunMarksRefreshAtTwoAndUnhealthyAtEight() {
        val h = wakeCore.srHealth()
        val actions = (1..9).map { h.onNoSpeech() }
        assertFalse(actions[0].markRefresh)
        assertTrue(actions[1].markRefresh)
        assertEquals(List(7) { false } + listOf(true, true), actions.map { it.broadcastUnhealthy })
        assertEquals(9, h.consecutiveNoSpeech)
    }

    @Test
    fun anotherErrorOrAResultResetsTheNoSpeechRun() {
        val h = wakeCore.srHealth()
        repeat(5) { h.onNoSpeech() }
        assertTrue("ERROR_CLIENT (7) is a flat delay", h.onError(7))
        assertEquals(0, h.consecutiveNoSpeech)
        assertFalse("other codes back off", h.onError(5))
        repeat(3) { h.onNoSpeech() }
        h.onResults()
        assertEquals(0, h.consecutiveNoSpeech)
    }

    /** RS-35 (`b753ac5` Detour 6): refresh the warm recognizer after 20 cycles. */
    @Test
    fun rs35_recognizerIsRefreshedAfterTwentyCycles() {
        val h = wakeCore.srHealth()
        assertFalse(h.onWarm())
        repeat(19) { h.onCycleCompleted() }
        assertFalse(h.onWarm())
        h.onCycleCompleted()
        assertTrue(h.onWarm())
        assertFalse("the rebuild resets the cycle count", h.onWarm())
    }

    /** RS-35 (`187b419`): a hang-watchdog fire forces a fresh recognizer. */
    @Test
    fun rs35_watchdogFireForcesARefresh() {
        val h = wakeCore.srHealth()
        h.onWatchdogFired()
        assertTrue(h.onWarm())
    }

    @Test
    fun twoNoSpeechInARowForceARefreshAtTheNextWarm() {
        val h = wakeCore.srHealth()
        h.onNoSpeech(); h.onNoSpeech()
        assertTrue(h.onWarm())
    }

    /** RS-27 (`d36d31b`): the SR fallback never reverts an audio mode it didn't set. */
    @Test
    @PinsConstant("sr.audio_mode_single_owner")
    fun rs27_srNeverRevertsAnAudioModeItDidNotSet() {
        val o = wakeCore.srAudioModeOwnership()
        assertFalse("never set → never revert", o.revertIfOurs())
        o.onCycleStartSetInCommunication()
        assertTrue("miss → revert our own mode", o.onCycleEnd(matched = false))
        assertFalse(o.revertIfOurs())
        o.onCycleStartSetInCommunication()
        assertFalse("match → the call owns the mode now", o.onCycleEnd(matched = true))
        assertFalse("a late teardown must not flip the live call's mode", o.revertIfOurs())
    }
}
