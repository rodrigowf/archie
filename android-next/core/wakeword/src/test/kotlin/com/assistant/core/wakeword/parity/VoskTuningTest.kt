package com.assistant.core.wakeword.parity

import com.assistant.core.testing.PinsConstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** inv04 §10.1 `VoskTuningTest` (inv04 §4.2). */
class VoskTuningTest {
    private val pins = wakePins()

    @Test fun recognitionWindowIs5s() = pins.long("vosk.recognition_timeout_ms", "VOSK_RECOGNITION_TIMEOUT_MS", 5_000L)

    @Test fun recognitionStartedRmsIs30() = pins.double("vosk.rms_started_threshold", "VOSK_RMS_STARTED_THRESHOLD", 30.0)

    @Test fun recognitionReadIs6400Samples() = pins.int("vosk.read_samples", "VOSK_READ_SAMPLES", 6400)

    /** `dd5567f`: Vosk fires mid-word; Whisper needs the whole phrase. */
    @Test fun matchTailIs400ms() = pins.long("vosk.match_tail_ms", "MATCH_TAIL_MS", 400L)

    /** `54463e1`: 6 s+ uploads were slow and buried the phrase. */
    @Test fun confirmWindowIs2s() = pins.long("vosk.max_confirm_window_ms", "MAX_CONFIRM_WINDOW_MS", 2_000L)

    /** `ae1d958`: a lone "hello" from noise must not fire. */
    @Test fun minPrefixWordsIs2() = pins.int("vosk.min_prefix_words", "MIN_PREFIX_WORDS", 2)

    @Test fun modelAssetRoot() = pins.string("vosk.model_asset_root", "VOSK_MODEL_ASSET_ROOT", "vosk-model-small-en-us-0.15")

    /** Same extract dir and stamp as the old app so the lite upgrade does not re-extract 68 MB (spec 14 §1.2). */
    @Test fun modelExtractDir() = pins.string("vosk.model_extract_dir", "VOSK_MODEL_DIR", "vosk-model")

    @Test fun modelStamp() = pins.string("vosk.model_stamp", "VOSK_MODEL_STAMP", "vosk-model-small-en-us-0.15")

    @Test fun stampFile() = pins.string("vosk.stamp_file", "VOSK_STAMP_FILE", ".stamp")

    /** Without `[unk]` the constrained recognizer hangs. */
    @Test
    @PinsConstant("vosk.grammar_unk")
    fun grammarEndsWithUnk() {
        val arr = Json.parseToJsonElement(wakeCore.variants.keywordGrammar(TALK, WAKE)).jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("my friend", "wake up", "[unk]"), arr)
    }
}
