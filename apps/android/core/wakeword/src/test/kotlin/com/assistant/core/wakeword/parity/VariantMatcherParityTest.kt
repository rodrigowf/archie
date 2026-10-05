package com.assistant.core.wakeword.parity

import com.assistant.core.testing.PinsConstant
import com.assistant.core.wakeword.ports.VariantMatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phrase parsing and matching (inv04 §4.1 "variant parse", §4.2). Ports the intent of the old
 * `BuildVariantsParityTest` and `VoskEngineParityTest`.
 */
class VariantMatcherParityTest {
    private val v = wakeCore.variants

    // ── parseVariants (old buildVariants + comma fan-out, `e31d4fc` Inc 5) ──────────────────────

    @Test
    @PinsConstant("wake.variant_parse")
    fun parseIsCommaSplitTrimLowercaseDistinct() {
        assertEquals(listOf("my friend", "hello my friend"), v.parseVariants(" My Friend , hello my friend,my friend,, "))
        assertEquals(emptyList<String>(), v.parseVariants(""))
        assertEquals(emptyList<String>(), v.parseVariants(" , "))
    }

    @Test
    fun parseDoesNoPhoneticSubstitution() {
        // Inc 5 dropped the wordSubs expansion table: variants are the phrases verbatim.
        assertEquals(listOf("wake up"), v.parseVariants("wake up"))
        assertEquals(listOf("hey assistant"), v.parseVariants("Hey Assistant"))
    }

    // ── findMatch (realtime-first, substring) ─────────────────────────────────────────────────

    @Test
    fun findMatchNullForBlankOrNoVariant() {
        assertNull(v.findMatch("", TALK, WAKE))
        assertNull(v.findMatch("   ", TALK, WAKE))
        assertNull(v.findMatch("what time is it", TALK, WAKE))
        assertNull(v.findMatch("wake up", emptyList(), emptyList()))
    }

    @Test
    fun findMatchWakeOnExactAndSubstring() {
        assertEquals(VariantMatch("wake up", true, "wake up"), v.findMatch("wake up", TALK, WAKE))
        assertEquals(VariantMatch("wake up", true, "hey wake up please"), v.findMatch("hey wake up please", TALK, WAKE))
    }

    @Test
    fun findMatchTalk() {
        assertEquals(VariantMatch("my friend", false, "hello my friend"), v.findMatch("hello my friend", TALK, WAKE))
    }

    @Test
    @PinsConstant("vosk.match_precedence")
    fun findMatchPrefersWakeWhenBothMatch() {
        val m = v.findMatch("my friend wake up", TALK, WAKE)!!
        assertEquals("wake up", m.variant)
        assertEquals(true, m.isRealtime)
    }

    @Test
    fun findMatchIsCaseInsensitiveAndKeepsTrimmedRawText() {
        assertEquals(VariantMatch("wake up", true, "WAKE UP"), v.findMatch("  WAKE UP ", TALK, WAKE))
    }

    // ── findTalkPrefixMatch (`ae1d958`, MIN_PREFIX_WORDS = 2) ──────────────────────────────────

    private val hello = listOf("hello my friend")

    @Test
    fun prefixRequiresTwoLeadingWords() {
        assertNull("a lone stray 'hello' must not fire", v.findTalkPrefixMatch("hello", hello))
    }

    @Test
    fun prefixMatchesTwoLeadingWords() {
        assertEquals(VariantMatch("hello my friend", false, "hello my"), v.findTalkPrefixMatch("hello my", hello))
    }

    @Test
    fun prefixMatchesTheWholeVariantAndTrailingCommandWords() {
        assertEquals("hello my friend", v.findTalkPrefixMatch("hello my friend what time is it", hello)?.variant)
    }

    @Test
    fun prefixMatchesATwoWordVariantInFull() {
        assertEquals("my friend", v.findTalkPrefixMatch("my friend", TALK)?.variant)
        assertNull(v.findTalkPrefixMatch("my", TALK))
    }

    @Test
    fun prefixRejectsWrongWordOrOrder() {
        assertNull(v.findTalkPrefixMatch("hello your friend", hello))
        assertNull(v.findTalkPrefixMatch("my hello", hello))
        assertNull(v.findTalkPrefixMatch("hell my", hello))
    }

    @Test
    fun prefixSkipsSingleWordVariantsAndNeverFiresForWake() {
        assertNull(v.findTalkPrefixMatch("computer", listOf("computer")))
        assertNull("wake variants are never prefix-matched", v.findTalkPrefixMatch("wake up", emptyList()))
    }

    @Test
    fun prefixNullForBlank() {
        assertNull(v.findTalkPrefixMatch("", hello))
        assertNull(v.findTalkPrefixMatch("   ", hello))
    }

    // ── keywordGrammar ────────────────────────────────────────────────────────────────────────

    private fun grammar(talk: List<String>, wake: List<String>) =
        Json.parseToJsonElement(v.keywordGrammar(talk, wake)).jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun grammarIsAllPhrasesPlusUnk() {
        assertEquals(listOf("my friend", "hello", "wake up", "[unk]"), grammar(listOf("my friend", "hello"), WAKE))
    }

    @Test
    fun grammarDeduplicates() {
        assertEquals(listOf("wake up", "[unk]"), grammar(WAKE, WAKE))
    }

    @Test
    fun grammarWithNoPhrasesIsJustUnk() {
        assertEquals(listOf("[unk]"), grammar(emptyList(), emptyList()))
    }

    // ── Vosk result JSON (old VoskWakeWordEngine.extractText) ──────────────────────────────────

    @Test
    fun voskResultTextReadsTextOrPartial() {
        assertEquals("wake up", wakeCore.voskResultText("""{"text": " wake up "}"""))
        assertEquals("wake", wakeCore.voskResultText("""{"partial": "wake"}"""))
        assertEquals("", wakeCore.voskResultText("""{"text": ""}"""))
        assertEquals("", wakeCore.voskResultText("""{"partial": "   "}"""))
        assertEquals("", wakeCore.voskResultText("""{"result": []}"""))
        assertEquals("", wakeCore.voskResultText("not json"))
        assertEquals("", wakeCore.voskResultText(""))
    }
}
