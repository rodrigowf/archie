package com.assistant.core.wakeword.parity

import org.junit.Ignore
import org.junit.Test

/** inv04 §10.1 `WhisperTuningTest` (inv04 §4.4). */
@Ignore("A-07")
class WhisperTuningTest {
    private val pins = wakePins()

    /** `2f5ecd7`: A300M round trip ≈3.7 s; 2.5 s failed ~15/17. */
    @Test fun timeoutIs10s() = pins.long("whisper.timeout_ms", "WHISPER_TIMEOUT_MS", 10_000L)

    @Test fun modelIsWhisper1() = pins.string("whisper.model", "WHISPER_MODEL", "whisper-1")

    /** `70283e3`: temperature 0 kills silence hallucination. */
    @Test fun temperatureIsZero() = pins.string("whisper.temperature", "WHISPER_TEMPERATURE", "0")

    @Test fun languageIsEnglish() = pins.string("whisper.language", "WHISPER_LANGUAGE", "en")

    @Test fun responseFormatIsJson() = pins.string("whisper.response_format", "WHISPER_RESPONSE_FORMAT", "json")

    @Test fun uploadFileName() = pins.string("whisper.upload_filename", "WHISPER_UPLOAD_FILENAME", "wake.wav")

    @Test fun endpoint() = pins.string("whisper.endpoint", "WHISPER_TRANSCRIPTIONS_URL", "https://api.openai.com/v1/audio/transcriptions")

    @Test
    fun hallucinationBoilerplateIsTheExactSet() = pins.stringSet(
        "whisper.boilerplate", "WHISPER_HALLUCINATION_BOILERPLATE",
        setOf(
            "you", "thank you", "thank you very much", "thanks for watching", "thanks for watching the video",
            "please subscribe", "bye", "bye bye", "so", "the", "okay", "i m sorry",
        ),
    )
}
