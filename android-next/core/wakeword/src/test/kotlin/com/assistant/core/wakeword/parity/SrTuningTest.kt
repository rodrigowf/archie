package com.assistant.core.wakeword.parity

import com.assistant.core.audio.ports.AudioStream
import com.assistant.core.testing.PinsConstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** inv04 §10.1 `SrTuningTest` (inv04 §4.3). The SpeechRecognizer fallback stays until Vosk is validated everywhere (V6). */
@Ignore("A-07")
class SrTuningTest {
    private val pins = wakePins()

    /** `187b419`: SR hangs after onBeginningOfSpeech. */
    @Test fun hangWatchdogIs10s() = pins.long("sr.hang_watchdog_ms", "SR_HANG_WATCHDOG_MS", 10_000L)

    @Test fun refreshAfter20Cycles() = pins.int("sr.refresh_after_n", "SR_REFRESH_AFTER_N", 20)

    @Test fun refreshAfter2NoSpeech() = pins.int("sr.refresh_no_speech_spike", "SR_REFRESH_NO_SPEECH_SPIKE", 2)

    /** `39b1cec`: replaced the 2 h rebuild watchdog. */
    @Test fun unhealthyAt8NoSpeech() = pins.int("sr.no_speech_health_threshold", "SR_NO_SPEECH_HEALTH_THRESHOLD", 8)

    /** `d93f7d7`: Google's recognition service restarts in ~1 s. */
    @Test fun clientErrorDelayIs1s() = pins.long("sr.client_error_delay_ms", "SR_CLIENT_ERROR_DELAY_MS", 1000L)

    /** The literal 6 (ERROR_NO_SPEECH is not a named constant before API 23). */
    @Test fun noSpeechErrorCodeIs6() = pins.int("sr.no_speech_error_code", "SR_ERROR_NO_SPEECH", 6)

    @Test
    @PinsConstant("sr.recognizer_extras")
    fun recognizerExtrasAreTheTunedSet() {
        val extras = wakeCore.srRecognizerExtras("com.assistant.peripheral")
        val expected: Map<String, Any> = mapOf(
            "android.speech.extra.LANGUAGE_MODEL" to "free_form",
            "calling_package" to "com.assistant.peripheral",
            "android.speech.extra.MAX_RESULTS" to 5,
            "android.speech.extra.PARTIAL_RESULTS" to true,
            "android.speech.extra.LANGUAGE" to "en-US",
            "android.speech.extra.LANGUAGE_PREFERENCE" to "en-US",
            "android.speech.extra.PREFER_OFFLINE" to true,
            "android.speech.extra.DICTATION_MODE" to true,
            "android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS" to 200L,
            "android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS" to 1500L,
            "android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS" to 1000L,
        )
        assertEquals(expected, extras)
    }

    @Test
    @PinsConstant("sr.beep_streams")
    fun beepStreamsMutedAroundStartListening() {
        assertEquals(listOf(AudioStream.RING, AudioStream.NOTIFICATION, AudioStream.SYSTEM, AudioStream.MUSIC), wakeCore.srBeepStreams)
    }

    @Test
    @PinsConstant("sr.beep_mute_method")
    fun beepMuteUsesAdjustStreamVolumeFromApi23() {
        assertFalse(wakeCore.srBeepMuteUsesAdjustStreamVolume(21))
        assertFalse(wakeCore.srBeepMuteUsesAdjustStreamVolume(22))
        assertTrue(wakeCore.srBeepMuteUsesAdjustStreamVolume(23))
        assertTrue(wakeCore.srBeepMuteUsesAdjustStreamVolume(36))
    }
}
