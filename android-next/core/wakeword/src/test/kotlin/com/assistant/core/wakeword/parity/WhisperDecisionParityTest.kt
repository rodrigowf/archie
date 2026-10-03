package com.assistant.core.wakeword.parity

import com.assistant.core.testing.PinsConstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** Whisper gate decision (inv04 §4.4; ports the old `WhisperConfirmerDecideTest`; RS-37). */
@Ignore("A-07")
class WhisperDecisionParityTest {
    private val d = wakeCore.whisperDecision

    @Test fun confirmsWakeOnExactPhrase() = d.decide("wake up", TALK, WAKE).let { assertTrue(it.confirmed); assertTrue(it.isRealtime) }

    @Test fun confirmsTalkOnExactPhrase() = d.decide("my friend", TALK, WAKE).let { assertTrue(it.confirmed); assertFalse(it.isRealtime) }

    /** The real-world reject that was fixed: "Hello, my friend." vs the bare variant. */
    @Test fun confirmsThroughPunctuationAndCasing() = d.decide("Hello, my friend.", TALK, WAKE).let { assertTrue(it.confirmed); assertFalse(it.isRealtime) }

    @Test fun confirmsWakeWithConversationalLeadIn() = d.decide("Hey, wake up please!", TALK, WAKE).let { assertTrue(it.confirmed); assertTrue(it.isRealtime) }

    @Test fun prefersWakeWhenBothPresent() = d.decide("wake up my friend", TALK, WAKE).let { assertTrue(it.confirmed); assertTrue(it.isRealtime) }

    @Test fun rejectsUnrelatedSpeech() = assertFalse(d.decide("what time is it", TALK, WAKE).confirmed)

    @Test fun rejectsEmptyTranscript() = assertFalse(d.decide("", TALK, WAKE).confirmed)

    /** RS-37 (`70283e3`): whisper-1's canned silence outputs never confirm. */
    @Test
    fun rs37_whisperSilenceHallucinationsAreRejected() {
        listOf("you", "Thank you.", "Thanks for watching!", "Bye.", "So.", "Okay", "I'm sorry.", "Please subscribe", "The").forEach {
            assertFalse("\"$it\" must be rejected", d.decide(it, TALK, WAKE).confirmed)
        }
    }

    @Test fun boilerplateRejectIsWholeTranscriptOnly() = d.decide("thank you my friend", TALK, WAKE).let { assertTrue(it.confirmed); assertFalse(it.isRealtime) }

    @Test fun boilerplateWordInsideRealPhraseDoesNotBlockWake() = d.decide("okay wake up", TALK, WAKE).let { assertTrue(it.confirmed); assertTrue(it.isRealtime) }

    @Test
    @PinsConstant("whisper.normalize")
    fun normalizeStripsPunctuationLowercasesCollapsesSpace() {
        assertEquals("hello my friend", d.normalize("  Hello,   MY  friend!! "))
        assertEquals("agent 7", d.normalize("Agent-7"))
        assertEquals("i m sorry", d.normalize("I'm sorry."))
    }

    @Test
    fun boilerplateSetIsExact() {
        assertEquals(
            setOf("you", "thank you", "thank you very much", "thanks for watching", "thanks for watching the video",
                "please subscribe", "bye", "bye bye", "so", "the", "okay", "i m sorry"),
            d.hallucinationBoilerplate,
        )
    }
}
