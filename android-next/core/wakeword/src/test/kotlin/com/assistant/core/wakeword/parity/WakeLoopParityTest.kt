package com.assistant.core.wakeword.parity

import com.assistant.core.audio.ports.MicSourceType
import com.assistant.core.testing.FakeSrRunner
import com.assistant.core.testing.MicScript
import com.assistant.core.testing.PinsConstant
import com.assistant.core.testing.WavReader
import com.assistant.core.wakeword.ports.EngineKind
import com.assistant.core.wakeword.ports.SrOutcome
import com.assistant.core.wakeword.ports.WakeLoopEvent
import com.assistant.core.wakeword.ports.WakeLoopEvent.Confirming
import com.assistant.core.wakeword.ports.WakeLoopEvent.ConfirmFailed
import com.assistant.core.wakeword.ports.WakeLoopEvent.MicAvailable
import com.assistant.core.wakeword.ports.WakeLoopEvent.MicUnavailable
import com.assistant.core.wakeword.ports.WakeLoopEvent.TalkDetected
import com.assistant.core.wakeword.ports.WakeLoopEvent.TalkMessageCaptured
import com.assistant.core.wakeword.ports.WakeLoopEvent.WakeDetected
import com.assistant.core.wakeword.ports.WakePhase
import com.assistant.core.wakeword.ports.WhisperOutcome
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * The wake loop end to end on virtual time (inv04 §3.1 FSM table; §10.2 "Wake loop"; RS-32, RS-36…
 * RS-44; R3, R4, R6). Black-box: a fake mic paced in virtual time, a scripted recognizer, a fake
 * Whisper. Timing windows allow one read of slack (100 ms monitor, 400 ms recognition, 200 ms
 * capture) so the implementation may differ in read granularity but not in behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Ignore("A-07")
class WakeLoopParityTest {

    private fun assertIn(what: String, value: Long, range: LongRange) =
        assertTrue("$what = $value, expected $range", value in range)

    // ── realtime wake path ────────────────────────────────────────────────────────────────────

    @Test
    fun wakePhraseIsConfirmedByWhisperThenDetected() = runTest {
        val r = WakeLoopRig(this)
        r.say(1000, 1800, 200)
        r.hear(1300L to "wake", 1500L to "wake up")
        r.engine.start()
        r.advanceTo(3_000)
        assertEquals(listOf(Confirming(true), WakeDetected), r.eventTypes)
        val call = r.whisper.calls.single()
        assertIn("whisper call", r.rel(call.atMs), 1_600L..2_600L)
        assertEquals("detected right after Whisper answers", r.rel(call.atMs) + 500, r.timeOf(WakeDetected))
        assertTrue(r.log.dump(), r.log.contains("Vosk match"))
        assertTrue(r.log.dump(), r.log.contains("Whisper CONFIRMED"))
        assertEquals(EngineKind.VOSK, r.engine.engineKind)
    }

    /** R3: confirm is an explicit phase, then a 3000 ms cooldown before monitoring again. */
    @Test
    @PinsConstant("vosk.fresh_recognizer_per_cycle", "vosk.reset_after_match")
    fun confirmingAndCooldownAreExplicitPhases() = runTest {
        val r = WakeLoopRig(this)
        r.say(1000, 1800, 200)
        r.hear(1500L to "wake up")
        r.whisper.latencyMs = 1_000
        r.engine.start()
        var guard = 0
        while (r.whisper.calls.isEmpty() && guard++ < 200) r.advanceTo(r.rel(r.clock.nowMs()) + 50)
        val callAt = r.rel(r.whisper.calls.single().atMs)
        assertEquals(WakePhase.CONFIRMING_WAKE, r.phaseAt(callAt + 500))
        val detected = r.timeOf(WakeDetected)
        assertEquals(WakePhase.COOLDOWN, r.phaseAt(detected + 2_900))
        val rearm = r.phaseTimeline(detected + 2_900, detected + 3_400).firstOrNull { it.second != WakePhase.COOLDOWN }
        assertTrue("re-armed after the 3000 ms cooldown", rearm != null && rearm.first >= detected + 3_000)
        val rec = r.recognizers.created.single()
        assertTrue("recognizer closed at the end of the cycle", rec.closed)
        assertTrue("recognizer reset after the match", rec.resets >= 1)
    }

    /** The pre-buffer (last ⌊8000/read⌋ reads ≈ 500 ms) is fed to Vosk before the live reads. */
    @Test
    @PinsConstant("wake.pre_buffer_ms", "vosk.read_samples")
    fun preBufferIsFedFirstThenFourHundredMsReads() = runTest {
        val r = WakeLoopRig(this)
        r.say(1000, 6000, 200)
        r.engine.start()
        r.advanceTo(3_000)
        val sizes = r.recognizers.created.single().acceptSizes
        assertEquals(List(5) { 1600 }, sizes.take(5))
        assertEquals(6400, sizes[5])
        assertEquals(r.variantsGrammar(), r.recognizers.created.single().grammar)
    }

    private fun WakeLoopRig.variantsGrammar() = wakeCore.variants.keywordGrammar(listOf("my friend"), listOf("wake up"))

    @Test
    fun whisperRoundTripOfTenSecondsTimesOutClosed() = runTest {
        val r = WakeLoopRig(this)
        r.say(1000, 1800, 200)
        r.hear(1500L to "wake up")
        r.whisper.latencyMs = 10_001
        r.engine.start()
        r.advanceTo(20_000)
        val call = r.rel(r.whisper.calls.first().atMs)
        assertEquals(listOf(Confirming(true), ConfirmFailed(true)), r.eventTypes.take(2))
        assertEquals("fail-closed at the 10 s budget", call + 10_000, r.timeOfFirst<ConfirmFailed>())
        assertFalse(WakeDetected in r.eventTypes)
    }

    /** RS-40 (`2f5ecd7`): an A300M-like ≈4 s round trip still confirms. */
    @Test
    fun rs40_whisperRoundTripOfFourSecondsStillConfirms() = runTest {
        val r = WakeLoopRig(this)
        r.say(1000, 1800, 200)
        r.hear(1500L to "wake up")
        r.whisper.latencyMs = 4_000
        r.engine.start()
        r.advanceTo(8_000)
        assertEquals(listOf(Confirming(true), WakeDetected), r.eventTypes)
        assertEquals(r.rel(r.whisper.calls.single().atMs) + 4_000, r.timeOf(WakeDetected))
    }

    @Test
    fun whisperErrorsAndBoilerplateRejectClosed() = runTest {
        val outcomes = listOf(
            WhisperOutcome.Unauthorized, WhisperOutcome.HttpError(500), WhisperOutcome.Failure("io"),
            WhisperOutcome.NoKey, WhisperOutcome.Transcript("Thank you."), WhisperOutcome.Transcript("what time is it"),
        )
        for (o in outcomes) {
            val r = WakeLoopRig(this)
            r.whisperOutcome = { o }
            r.say(1000, 1800, 200)
            r.hear(1500L to "wake up")
            r.engine.start()
            r.advanceTo(4_000)
            assertEquals("$o", listOf(Confirming(true), ConfirmFailed(true)), r.eventTypes)
            r.engine.stop()
        }
    }

    /** RS-37 (`70283e3`): a phantom match on a clip below the speech floor never reaches Whisper. */
    @Test
    fun rs37_silentClipNeverReachesWhisper() = runTest {
        val r = WakeLoopRig(this, gain = 3.0f) // gate 23.3 lets RMS 25 in; the speech floor (30) must not
        r.say(1000, 3000, 25)
        r.hear(1500L to "wake up")
        r.engine.start()
        r.advanceTo(4_000)
        assertEquals(listOf(ConfirmFailed(true)), r.eventTypes)
        assertTrue(r.whisper.calls.isEmpty())
        assertTrue(r.log.dump(), r.log.contains("Pre-Whisper gate"))
    }

    /** RS-41 (`54463e1`): at most the trailing 2 s go to Whisper (plus < one 400 ms read). */
    @Test
    fun rs41_whisperUploadIsAtMostTwoSeconds() = runTest {
        val r = WakeLoopRig(this)
        r.say(1000, 4800, 300)
        r.hear(4000L to "wake up")
        r.engine.start()
        r.advanceTo(6_000)
        val wav = WavReader.read(r.whisper.calls.single().wav)
        assertEquals(16000, wav.sampleRate)
        assertEquals(1, wav.channels)
        assertTrue("samples ${wav.samples}", wav.samples in 32_000 until 32_000 + 6_400)
    }

    @Test
    fun whisperNotConfiguredFiresUnconfirmed() = runTest {
        val r = WakeLoopRig(this, whisperConfigured = false)
        r.say(1000, 1800, 200)
        r.hear(1500L to "wake up")
        r.engine.start()
        r.advanceTo(3_000)
        assertEquals(listOf(WakeDetected), r.eventTypes)
    }

    // ── talk path ─────────────────────────────────────────────────────────────────────────────

    /** RS-39 (`ecd3a43`): "hello my friend what time is it" in one breath, on the same mic. */
    @Test
    @PinsConstant("wake.capture_frame_samples")
    fun rs39_oneBreathTalkCapturedOnTheSameMic() = runTest {
        val r = WakeLoopRig(this, talk = "hello my friend")
        r.whisperText = "Hello, my friend. What time is it?"
        r.say(1000, 3500, 2000)
        r.hear(1300L to "hello", 1700L to "hello my", 2100L to "hello my friend", 2600L to "hello my friend what")
        r.engine.start()
        r.advanceTo(8_000)
        assertEquals(2, r.eventTypes.size)
        assertEquals(TalkDetected, r.eventTypes[0])
        val msg = r.eventTypes[1] as TalkMessageCaptured
        assertIn("onset", r.timeOf(TalkDetected), 2_600L..3_400L)
        val call = r.rel(r.whisper.calls.single().atMs)
        assertIn("capture ended ~1 s after the speech", call, 4_300L..5_000L)
        assertEquals("one mic for wake phrase + command", 1, r.mics.successfulOpens.count { r.rel(it.atMs) < call })
        assertTrue(msg.wav.contentEquals(r.whisper.calls.single().wav))
        val wav = WavReader.read(msg.wav)
        assertEquals(16000, wav.sampleRate)
        assertTrue("duration ${wav.samples / 16} ms", wav.samples >= 2_500 * 16)
        val firstLoud = wavSamples(msg.wav).indexOfFirst { abs(it.toInt()) >= 1000 }
        assertTrue("leading edge kept: first speech sample at ${firstLoud / 16} ms", firstLoud in 0..8_000)
        assertTrue(r.log.dump(), r.log.contains("Vosk talk-prefix trigger"))
        assertTrue(r.log.dump(), r.log.contains("Talk command speech onset confirmed"))
        assertTrue(r.log.dump(), r.log.contains("Command ended on 1000ms silence"))
    }

    /** R3: capture and talk confirm are explicit phases (the host must not restart them). */
    @Test
    fun talkCaptureAndConfirmAreExplicitPhases() = runTest {
        val r = WakeLoopRig(this, talk = "hello my friend")
        r.whisperText = "hello my friend what time is it"
        r.whisper.latencyMs = 1_000
        r.say(1000, 3500, 2000)
        r.hear(1300L to "hello", 1700L to "hello my")
        r.engine.start()
        val phases = r.phaseTimeline(0, 7_000).map { it.second }
        val i = phases.indexOf(WakePhase.TALK_PRE_ONSET)
        assertTrue(phases.toString(), i >= 0)
        assertTrue(phases.toString(), phases.indexOf(WakePhase.TALK_CAPTURING) > i)
        assertTrue(phases.toString(), phases.indexOf(WakePhase.CONFIRMING_TALK) > phases.indexOf(WakePhase.TALK_CAPTURING))
        assertTrue(phases.toString(), phases.indexOf(WakePhase.COOLDOWN) > phases.indexOf(WakePhase.CONFIRMING_TALK))
    }

    /** RS-38 (`ae1d958`): a prefix trigger with no command after it shows no UI and calls nothing. */
    @Test
    fun rs38_phantomTalkTriggerShowsNoUi() = runTest {
        val r = WakeLoopRig(this, talk = "hello my friend")
        r.say(1000, 2000, 2000)
        r.hear(1300L to "hello", 1700L to "hello my")
        r.engine.start()
        r.advanceTo(8_000)
        assertTrue("no events: ${r.eventTypes}", r.eventTypes.isEmpty())
        assertTrue(r.whisper.calls.isEmpty())
        val rearm = r.phaseTimeline(8_000, 11_000).firstOrNull { it.second == WakePhase.MONITORING }
        assertTrue("re-armed 3000 ms after the silent abort (~6.4 s): $rearm", rearm != null && rearm.first in 9_200L..10_000L)
    }

    /** RS-38: a lone stray "hello" never fires (prefix needs two words); the window ends in NoMatch. */
    @Test
    fun rs38_strayHelloDoesNotTrigger() = runTest {
        val r = WakeLoopRig(this, talk = "hello my friend")
        r.say(1000, 1500, 2000)
        r.hear(1300L to "hello")
        r.engine.start()
        r.advanceTo(6_000)
        assertTrue(r.eventTypes.isEmpty())
        assertTrue(r.whisper.calls.isEmpty())
        val rearm = r.phaseTimeline(6_000, 8_000).firstOrNull { it.second == WakePhase.MONITORING }
        assertTrue("NoMatch re-arms after 500 ms (window ≈ 1.2 s + 5 s): $rearm", rearm != null && rearm.first in 6_500L..7_300L)
    }

    /** RS-42 (`b710c8b`/`3bee23a`): the talk path never emits Confirming; a rejected talk clears via ConfirmFailed(false). */
    @Test
    fun rs42_rejectedTalkEmitsConfirmFailedAndNeverConfirming() = runTest {
        val r = WakeLoopRig(this, talk = "hello my friend")
        r.whisperText = "hey my friend what time is it"
        r.say(1000, 3500, 2000)
        r.hear(1300L to "hello", 1700L to "hello my")
        r.engine.start()
        r.advanceTo(8_000)
        assertEquals(listOf(TalkDetected, ConfirmFailed(false)), r.eventTypes)
    }

    @Test
    fun talkWithoutWhisperIsSentUnconfirmed() = runTest {
        val r = WakeLoopRig(this, talk = "hello my friend", whisperConfigured = false)
        r.say(1000, 3500, 2000)
        r.hear(1300L to "hello", 1700L to "hello my")
        r.engine.start()
        r.advanceTo(8_000)
        assertEquals(TalkDetected, r.eventTypes.first())
        assertTrue(r.eventTypes[1] is TalkMessageCaptured)
    }

    // ── gate, re-arm, mic acquisition ─────────────────────────────────────────────────────────

    /** RS-36: RMS 69 never wakes the recognizer at gain 1; RMS 70 does. */
    @Test
    @PinsConstant("wake.mic_source_by_sdk")
    fun rs36_threshold70DetectsNormalSpeech() = runTest {
        val r = WakeLoopRig(this)
        r.mics.script = MicScript.constant(69)
        r.engine.start()
        r.advanceTo(10_000)
        assertTrue("RMS 69 must not start recognition", r.recognizers.created.isEmpty())
        r.mics.script = MicScript.constant(70)
        r.advanceTo(10_500)
        assertEquals(1, r.recognizers.created.size)
        assertEquals("wake mic source on API 22", MicSourceType.VOICE_RECOGNITION, r.mics.successfulOpens.first().spec.source)
        assertEquals(16000, r.mics.successfulOpens.first().spec.sampleRateHz)
        assertEquals(3200, r.mics.successfulOpens.first().spec.bufferBytes)
    }

    @Test
    fun theWakeGainScalesTheGate() = runTest {
        val r = WakeLoopRig(this, gain = 1.5f)
        r.mics.script = MicScript.constant(47)
        r.engine.start()
        r.advanceTo(1_000)
        assertEquals("70 / 1.5 ≈ 46.7 lets RMS 47 in", 1, r.recognizers.created.size)
    }

    /** RS-43 (`043340a`): ten NoMatch windows in a row re-arm at a constant cadence (no 30 s backoff). */
    @Test
    fun rs43_noMatchWindowsDoNotAccrueBackoff() = runTest {
        val r = WakeLoopRig(this)
        r.mics.script = MicScript.constant(200)
        r.engine.start()
        val starts = r.phaseTimeline(0, 62_000, stepMs = 100).filter { it.second == WakePhase.RECOGNIZING }.map { it.first }
        assertTrue("windows: $starts", starts.size >= 10)
        starts.zipWithNext { a, b -> b - a }.forEach { gap -> assertTrue("gap $gap ms", gap in 5_400L..6_100L) }
        assertTrue(r.recognizers.created.all { it.closed || it === r.recognizers.created.last() })
    }

    /** RS-32 (`5bde0d1`/`95201b5`): a busy mic is retried every 500 ms; MicUnavailable once at 8 failures. */
    @Test
    fun rs32_micBusyRetriesEvery500msUntilFree() = runTest {
        val r = WakeLoopRig(this)
        r.mics.busyUntilMs = r.at(5_000)
        r.engine.start()
        r.advanceTo(6_000)
        val attempts = r.mics.opens.map { r.rel(it.atMs) }.filter { it <= 5_000 }
        assertEquals((0..10).map { it * 500L }, attempts)
        assertEquals(listOf(MicUnavailable, MicAvailable), r.eventTypes)
        assertEquals(3_500L, r.timeOf(MicUnavailable))
        assertEquals(5_000L, r.timeOf(MicAvailable))
        assertEquals(WakePhase.MONITORING, r.engine.phase.value)
    }

    @Test
    fun fewerThanEightFailuresRaiseNoMicWarning() = runTest {
        val r = WakeLoopRig(this)
        r.mics.busyUntilMs = r.at(3_000)
        r.engine.start()
        r.advanceTo(4_000)
        assertTrue(r.eventTypes.isEmpty())
        assertEquals(WakePhase.MONITORING, r.engine.phase.value)
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun startIsIdempotentWhileArmed() = runTest {
        val r = WakeLoopRig(this)
        r.engine.start()
        r.advanceTo(500)
        r.engine.start()
        r.advanceTo(2_000)
        assertEquals(1, r.mics.successfulOpens.size)
    }

    /** R4: pause during recognition releases the mic and closes Vosk on the loop thread, no events, no re-arm. */
    @Test
    fun pauseDuringRecognitionTearsDownOnTheLoopThreadWithoutRearm() = runTest {
        val r = WakeLoopRig(this)
        r.say(1000, 9000, 200)
        r.engine.start()
        assertEquals(WakePhase.RECOGNIZING, r.phaseAt(2_000))
        r.engine.pause()
        r.advanceTo(32_000)
        assertEquals(WakePhase.PAUSED, r.engine.phase.value)
        assertTrue(r.eventTypes.isEmpty())
        assertEquals("no re-arm while paused", 1, r.mics.successfulOpens.size)
        val mic = r.mics.mics.single()
        val rec = r.recognizers.created.single()
        assertTrue(mic.released)
        assertTrue(rec.closed)
        assertTrue("mic: ${mic.violations}", mic.violations.isEmpty())
        assertTrue("vosk: ${rec.violations}", rec.violations.isEmpty())
        assertEquals("Vosk closed on its own thread", rec.createThread, rec.closeThread)
        assertTrue("mic released on the reading thread", mic.releaseThread in mic.readThreads)
    }

    /** R3/R4: pause during a talk capture drops it silently (no send, no Whisper) and does not re-arm. */
    @Test
    fun pauseDuringTalkCaptureDropsItSilently() = runTest {
        val r = WakeLoopRig(this, talk = "hello my friend")
        r.say(1000, 6000, 2000)
        r.hear(1300L to "hello", 1700L to "hello my")
        r.engine.start()
        assertEquals(WakePhase.TALK_CAPTURING, r.phaseAt(3_400))
        r.engine.pause()
        r.advanceTo(30_000)
        assertEquals(listOf(TalkDetected), r.eventTypes)
        assertTrue(r.whisper.calls.isEmpty())
        assertTrue(r.mics.mics.all { it.released && it.violations.isEmpty() })
        assertEquals(1, r.mics.successfulOpens.size)
    }

    @Test
    fun resumeReopensTheMicWithAFreshRecognizer() = runTest {
        val r = WakeLoopRig(this)
        r.say(1000, 9000, 200)
        r.engine.start()
        r.advanceTo(2_000)
        r.engine.pause()
        r.advanceTo(5_000)
        r.engine.resume()
        r.advanceTo(12_000)
        assertEquals(2, r.mics.successfulOpens.size)
        assertEquals(5_000L, r.rel(r.mics.successfulOpens[1].atMs))
        assertEquals(2, r.recognizers.created.size)
        assertNotEquals(WakePhase.PAUSED, r.engine.phase.value)
    }

    @Test
    fun stopEndsEverything() = runTest {
        val r = WakeLoopRig(this)
        r.mics.script = MicScript.constant(200)
        r.engine.start()
        r.advanceTo(3_000)
        r.engine.stop()
        r.advanceTo(30_000)
        assertEquals(WakePhase.STOPPED, r.engine.phase.value)
        assertTrue(r.mics.mics.all { it.released })
        assertTrue(r.recognizers.created.all { it.closed })
        assertEquals(1, r.mics.successfulOpens.size)
    }

    // ── engine selection / SpeechRecognizer fallback ──────────────────────────────────────────

    /** R6: Vosk runs even when the device has no SpeechRecognizer service. */
    @Test
    fun voskRunsWithoutASpeechRecognizerService() = runTest {
        val r = WakeLoopRig(this, sr = null)
        r.say(1000, 1800, 200)
        r.hear(1500L to "wake up")
        r.engine.start()
        r.advanceTo(3_000)
        assertEquals(EngineKind.VOSK, r.engine.engineKind)
        assertTrue(WakeDetected in r.eventTypes)
    }

    @Test
    fun srFallbackReleasesTheMicAndFiresWithoutWhisper() = runTest {
        val sr = FakeSrRunner(cycleMs = 1_500, outcomes = listOf(SrOutcome.Matched("wake up", isRealtime = true)))
        val r = WakeLoopRig(this, voskAvailable = false, sr = sr)
        r.say(1000, 2000, 200)
        r.engine.start()
        r.advanceTo(1_800)
        assertEquals(EngineKind.SPEECH_RECOGNIZER, r.engine.engineKind)
        assertEquals("SR opens its own mic", 0, r.mics.openNow)
        r.advanceTo(4_000)
        assertEquals(listOf(WakeDetected), r.eventTypes)
        assertTrue("an SR match carries no PCM: no Whisper", r.whisper.calls.isEmpty())
        assertEquals(1, sr.cycles.get())
    }

    @Test
    fun srUnhealthyNoSpeechIsForwarded() = runTest {
        val sr = FakeSrRunner(cycleMs = 1_000, outcomes = listOf(SrOutcome.NoSpeech(unhealthy = true)))
        val r = WakeLoopRig(this, voskAvailable = false, sr = sr)
        r.say(1000, 1500, 200)
        r.engine.start()
        r.advanceTo(4_000)
        assertEquals(listOf(WakeLoopEvent.RecognizerUnhealthy), r.eventTypes)
    }
}
