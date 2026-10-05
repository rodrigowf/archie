package com.assistant.core.wakeword.policy

import com.assistant.core.wakeword.WakeTuning
import com.assistant.core.wakeword.ports.VariantMatch
import com.assistant.core.wakeword.ports.VariantMatcher
import com.assistant.core.wakeword.ports.WhisperDecision
import com.assistant.core.wakeword.ports.WhisperVerdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Phrase parsing and matching shared by Vosk, the SpeechRecognizer fallback and the grammar (old
 * `WakeWordDetector.buildVariants` + comma fan-out, `VoskWakeWordEngine.findMatch` /
 * `findTalkPrefixMatch` / `buildKeywordGrammar`).
 */
object PhraseMatcher : VariantMatcher {
    private val whitespace = Regex("\\s+")

    override fun parseVariants(csv: String): List<String> =
        csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { it.lowercase().trim() }.distinct()

    override fun findMatch(text: String, talk: List<String>, wake: List<String>): VariantMatch? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        wake.firstOrNull { lower.contains(it) }?.let { return VariantMatch(it, isRealtime = true, rawText = trimmed) }
        talk.firstOrNull { lower.contains(it) }?.let { return VariantMatch(it, isRealtime = false, rawText = trimmed) }
        return null
    }

    override fun findTalkPrefixMatch(text: String, talk: List<String>): VariantMatch? {
        val words = text.trim().lowercase().split(whitespace).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        for (variant in talk) {
            val variantWords = variant.split(whitespace).filter { it.isNotEmpty() }
            if (variantWords.size <= 1) continue // a single-word variant is matched in full by findMatch
            val required = minOf(WakeTuning.MIN_PREFIX_WORDS, variantWords.size)
            if (words.size < required) continue
            val n = minOf(words.size, variantWords.size)
            if ((0 until n).all { words[it] == variantWords[it] }) {
                return VariantMatch(variant, isRealtime = false, rawText = text.trim())
            }
        }
        return null
    }

    override fun keywordGrammar(talk: List<String>, wake: List<String>): String =
        JsonArray(((talk + wake).distinct() + UNK).map { JsonPrimitive(it) }).toString()

    /** Vosk's out-of-grammar token; without it the constrained recognizer hangs. */
    const val UNK = "[unk]"
}

/** Text of a Vosk result (`{"text":…}` final or `{"partial":…}`), trimmed; "" when absent or malformed. */
fun voskResultText(json: String): String {
    if (json.isBlank()) return ""
    val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return ""
    val value = when {
        "text" in obj -> obj["text"]
        "partial" in obj -> obj["partial"]
        else -> null
    }
    return ((value as? JsonPrimitive)?.content ?: "").trim()
}

/**
 * The Whisper verdict (old `WhisperConfirmer.decide` / `normalize`): normalize, reject a transcript
 * that is entirely whisper-1 silence boilerplate, then wake variants first, then talk, by substring.
 */
object WhisperGateDecision : WhisperDecision {
    private val nonAlphanumeric = Regex("[^a-z0-9\\s]")
    private val whitespace = Regex("\\s+")

    override val hallucinationBoilerplate: Set<String> get() = WakeTuning.WHISPER_HALLUCINATION_BOILERPLATE

    override fun normalize(text: String): String =
        text.lowercase().replace(nonAlphanumeric, " ").replace(whitespace, " ").trim()

    override fun decide(transcript: String, talk: List<String>, wake: List<String>): WhisperVerdict {
        val norm = normalize(transcript)
        if (norm in hallucinationBoilerplate) return WhisperVerdict(confirmed = false, isRealtime = false)
        if (wake.any { norm.contains(normalize(it)) }) return WhisperVerdict(confirmed = true, isRealtime = true)
        if (talk.any { norm.contains(normalize(it)) }) return WhisperVerdict(confirmed = true, isRealtime = false)
        return WhisperVerdict(confirmed = false, isRealtime = false)
    }
}
