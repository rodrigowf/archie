package com.assistant.core.wakeword.parity

import com.assistant.core.testing.FakeClock
import com.assistant.core.testing.FakeMicFactory
import com.assistant.core.testing.FakeRecognizerFactory
import com.assistant.core.testing.FakeSrRunner
import com.assistant.core.testing.FakeVoskModelSource
import com.assistant.core.testing.FakeWhisper
import com.assistant.core.testing.MicScript
import com.assistant.core.testing.RecordingLog
import com.assistant.core.testing.Seg
import com.assistant.core.wakeword.ports.WakeConfig
import com.assistant.core.wakeword.ports.WakeEngineDeps
import com.assistant.core.wakeword.ports.WakeLoopEvent
import com.assistant.core.wakeword.ports.WakePhase
import com.assistant.core.wakeword.ports.WakeWordEngine
import com.assistant.core.wakeword.ports.WhisperOutcome
import java.util.Collections
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent

/**
 * Black-box rig for the wake-word engine on virtual time. All times in the helpers are relative to
 * [t0] (the clock when the rig was built). The room sits at RMS 10 (the A300M floor is 4–11);
 * `say()` overlays speech; `hear()` scripts what the recognizer decodes over time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WakeLoopRig(
    private val ts: TestScope,
    talk: String = "my friend",
    wake: String = "wake up",
    gain: Float = 1.0f,
    sensitivity: Float = 2.0f,
    voskAvailable: Boolean = true,
    val sr: FakeSrRunner? = FakeSrRunner(),
    whisperConfigured: Boolean = true,
    sdkInt: Int = 22,
) {
    val clock = FakeClock.boundTo(ts.testScheduler)
    val t0 = clock.nowMs()
    val log = RecordingLog()
    val mics = FakeMicFactory(clock, MicScript.constant(ROOM))
    val recognizers = FakeRecognizerFactory(clock)
    val vosk = FakeVoskModelSource(if (voskAvailable) recognizers else null)
    var whisperText = "wake up"
    var whisperOutcome: (Int) -> WhisperOutcome = { WhisperOutcome.Transcript(whisperText) }
    val whisper = FakeWhisper(clock, latencyMs = 500) { whisperOutcome(it) }
    val events: MutableList<Pair<Long, WakeLoopEvent>> = Collections.synchronizedList(mutableListOf())
    val engine: WakeWordEngine = wakeCore.engine(
        WakeEngineDeps(ts.backgroundScope, clock, log, sdkInt, mics, vosk, sr, if (whisperConfigured) whisper else null),
        WakeConfig(talk, wake, gain, sensitivity),
    )

    init {
        ts.backgroundScope.launch(UnconfinedTestDispatcher(ts.testScheduler)) {
            engine.events.collect { events += clock.nowMs() to it }
        }
    }

    fun at(ms: Long) = t0 + ms
    fun rel(abs: Long) = abs - t0

    /** Speech at [amplitude] over [fromMs, toMs) on top of the room floor. */
    fun say(fromMs: Long, toMs: Long, amplitude: Int = 2000) {
        mics.script = MicScript.segments(ROOM, Seg(at(fromMs), at(toMs), amplitude))
    }

    /** Recognizer schedule: (relative ms, text); the latest started entry wins. */
    fun hear(vararg entries: Pair<Long, String>) {
        recognizers.script = FakeRecognizerFactory.schedule(*entries.map { at(it.first) to it.second }.toTypedArray())
    }

    fun advanceTo(ms: Long) {
        val target = at(ms)
        if (target > clock.nowMs()) ts.testScheduler.advanceTimeBy(target - clock.nowMs())
        ts.runCurrent()
    }

    fun phaseAt(ms: Long): WakePhase { advanceTo(ms); return engine.phase.value }

    /** Samples the phase every [stepMs] over [fromMs, toMs]; returns (relative time, phase) on each change. */
    fun phaseTimeline(fromMs: Long, toMs: Long, stepMs: Long = 50): List<Pair<Long, WakePhase>> {
        val out = mutableListOf<Pair<Long, WakePhase>>()
        var t = fromMs
        while (t <= toMs) {
            val p = phaseAt(t)
            if (out.isEmpty() || out.last().second != p) out += t to p
            t += stepMs
        }
        return out
    }

    val eventTypes: List<WakeLoopEvent> get() = events.map { it.second }

    fun timeOf(event: WakeLoopEvent): Long = rel(events.first { it.second == event }.first)

    inline fun <reified T : WakeLoopEvent> timeOfFirst(): Long = rel(events.first { it.second is T }.first)

    companion object {
        const val ROOM = 10
    }
}

/** 16-bit little-endian samples of a 44-byte-header WAV. */
internal fun wavSamples(wav: ByteArray): ShortArray =
    ShortArray((wav.size - 44) / 2) { i -> ((wav[44 + 2 * i].toInt() and 0xff) or (wav[45 + 2 * i].toInt() shl 8)).toShort() }
