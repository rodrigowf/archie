package com.assistant.core.voice.parity

import com.assistant.core.audio.ports.AudioCore
import com.assistant.core.audio.ports.MicSourceType
import com.assistant.core.testing.FakeAudioTrackFactory
import com.assistant.core.testing.FakeClock
import com.assistant.core.testing.FakeMicFactory
import com.assistant.core.testing.FakeVoiceApi
import com.assistant.core.testing.MicScript
import com.assistant.core.testing.OrderLog
import com.assistant.core.testing.Parity
import com.assistant.core.testing.PinsConstant
import com.assistant.core.testing.RecordingLog
import com.assistant.core.testing.obj
import com.assistant.core.voice.ports.ParserKind
import com.assistant.core.voice.ports.ProviderPhase
import com.assistant.core.voice.ports.ProviderSignal
import com.assistant.core.voice.ports.VoiceTransport
import com.assistant.core.voice.ports.WsPcmDeps
import java.util.Base64
import java.util.Collections
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebSocket PCM transport (Qwen / Gemini) end to end with the real `:core:audio` pieces and fake
 * mic/track (inv04 §2.3, §3.3 WS table, §4.7; RS-15, RS-18, RS-21, RS-22, RS-24). Needs A-05 too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WsPcmTransportParityTest {

    private class Rig(private val ts: TestScope, kind: ParserKind = ParserKind.GEMINI, permission: Boolean = true, sdkInt: Int = 22) {
        val clock = FakeClock.boundTo(ts.testScheduler)
        val t0 = clock.nowMs()
        val log = RecordingLog()
        val order = OrderLog()
        val mics = FakeMicFactory(clock, MicScript.constant(1000)).apply { onOpen = { order.add("mic") } }
        val tracks = FakeAudioTrackFactory(sdkInt, minBuffer = 4000, clock = clock).apply { configure = { order.add("track") } }
        val audio: AudioCore = Parity.load("A-05")
        val transport: VoiceTransport = voiceCore.wsPcmTransport(
            WsPcmDeps(ts.backgroundScope, clock, log, sdkInt, mics, tracks, audio) { permission },
            kind, if (kind == ParserKind.GEMINI) "google" else "qwen",
        )
        val chunks: MutableList<Pair<Long, ByteArray>> = Collections.synchronizedList(mutableListOf())
        val signals: MutableList<ProviderSignal> = Collections.synchronizedList(mutableListOf())

        init {
            ts.backgroundScope.launch(UnconfinedTestDispatcher(ts.testScheduler)) { transport.signals.collect { signals += it } }
        }

        fun connect() {
            ts.backgroundScope.launch {
                transport.connect(FakeVoiceApi.WS_CONNECTION, {}) { b64 -> chunks += (clock.nowMs() - t0) to Base64.getDecoder().decode(b64) }
            }
            ts.runCurrent()
        }

        fun advanceTo(rel: Long) { val d = t0 + rel - clock.nowMs(); if (d > 0) ts.testScheduler.advanceTimeBy(d); ts.runCurrent() }
        val phase get() = transport.phase.value.phase

        /** Peak absolute sample of the mic chunk sent at or after [rel]. */
        fun chunkPeakAt(rel: Long): Int {
            val c = chunks.first { it.first >= rel }.second
            return (0 until c.size / 2).maxOf { i -> abs(((c[2 * i].toInt() and 0xff) or (c[2 * i + 1].toInt() shl 8)).toShort().toInt()) }
        }

        fun speakerChunk() = transport.pushSpeakerChunk(Base64.getEncoder().encodeToString(ByteArray(960)))
    }

    /** RS-24 (`55037c2`): 200 ms HAL settle, then mic BEFORE speaker; same source as the wake mic. */
    @Test
    @PinsConstant("audio.mic_before_speaker")
    fun rs24_halSettlesBeforeMicAndMicBeforeSpeaker() = runTest {
        val r = Rig(this)
        r.connect()
        r.advanceTo(100)
        assertEquals(ProviderPhase.CONNECTING, r.phase)
        assertTrue(r.mics.opens.isEmpty())
        r.advanceTo(200)
        assertEquals(200L, r.mics.successfulOpens.single().atMs - r.t0)
        assertEquals(listOf("mic", "track"), r.order.all)
        val spec = r.mics.successfulOpens.single().spec
        assertEquals(16000, spec.sampleRateHz)
        assertEquals(MicSourceType.VOICE_RECOGNITION, spec.source)
        assertEquals(6400, spec.bufferBytes)
        assertEquals(ProviderPhase.ACTIVE, r.phase)
        assertTrue(ProviderSignal.SessionCreated in r.signals)
        assertTrue(r.log.dump(), r.log.contains("Mic started: rate=16000Hz source=6 bufSize=6400"))
        assertTrue(r.log.dump(), r.log.contains("Speaker started: rate=24000Hz bufSize=72000"))
    }

    @Test
    fun micChunksAre480FramesAndProbeLogsEveryFiftyChunks() = runTest {
        val r = Rig(this)
        r.connect()
        r.advanceTo(2_000)
        assertTrue(r.chunks.size >= 50)
        assertTrue(r.chunks.all { it.second.size == 960 })
        assertEquals(1000, r.chunkPeakAt(300))
        assertTrue(r.log.dump(), r.log.contains("[MIC_PROBE]"))
    }

    @Test
    fun mutedChunksAreDropped() = runTest {
        val r = Rig(this)
        r.connect()
        r.advanceTo(500)
        r.transport.toggleMute()
        val before = r.chunks.size
        r.advanceTo(1_500)
        assertEquals(before, r.chunks.size)
    }

    /** RS-22 (`20217b1`): a speaker chunk ducks the mic and the duck gain is applied to what is sent. */
    @Test
    fun rs22_duckedMicChunksAreScaled() = runTest {
        val r = Rig(this)
        r.connect()
        r.advanceTo(500)
        r.speakerChunk()
        r.advanceTo(520)
        // Chunks are stamped at send time, after FakeMic's 30 ms read (480 frames @ 16 kHz): advance past the queried chunk.
        r.advanceTo(560)
        assertEquals(50, r.chunkPeakAt(530))
        assertTrue(r.log.contains("[MIC_STATE] DUCK"))
        r.transport.setEchoDuckingGain(0f)
        r.advanceTo(600)
        // Chunks are stamped at send time, after FakeMic's 30 ms read (480 frames @ 16 kHz): advance past the queried chunk.
        r.advanceTo(640)
        assertEquals(0, r.chunkPeakAt(610))
    }

    /**
     * RS-21 (`2ccee40`/`cffec38`): chunks stop → 800 ms staleness → drain (writes quiet 400 ms, head
     * caught up) → 1000 ms tail → restore. Never earlier.
     */
    @Test
    fun rs21_restoreOnlyAfterStalenessDrainAndTail() = runTest {
        val r = Rig(this)
        r.connect()
        r.advanceTo(300)
        var t = 300L
        while (t < 2_300) { r.speakerChunk(); t += 20; r.advanceTo(t) }
        val last = t - 20
        r.tracks.last.head = 10_000_000 // the DAC has played everything written
        // stale at last + 800, writes quiet for 400 ms of drain polling, then the 1000 ms tail:
        // the earliest possible restore is last + 2200.
        r.advanceTo(last + 2_150)
        assertEquals("still ducked before stale(800) + quiet(400) + tail(1000)", 50, r.chunkPeakAt(last + 2_120))
        r.advanceTo(last + 2_600)
        assertEquals(1000, r.chunkPeakAt(last + 2_450))
        assertTrue(r.log.contains("RESTORE_DRAIN(stale)"))
        assertTrue(r.log.contains("RESTORE_IMMEDIATE(drained:stale)"))
    }

    /** Barge-in (Gemini `interrupted`): flush the speaker and restore the mic at once. */
    @Test
    fun bargeInFlushesTheSpeakerAndRestoresTheMicImmediately() = runTest {
        val r = Rig(this)
        r.connect()
        r.advanceTo(300)
        repeat(5) { r.speakerChunk() }
        r.advanceTo(400)
        r.tracks.last.ops.clear()
        r.transport.handleProviderEvent(obj("""{"serverContent":{"interrupted":true}}"""))
        r.advanceTo(410)
        assertEquals(listOf("pause", "flush", "play"), r.tracks.last.ops.filter { it in setOf("pause", "flush", "play") })
        // Chunks are stamped at send time, after FakeMic's 30 ms read (480 frames @ 16 kHz): advance past the queried chunk.
        r.advanceTo(450)
        assertEquals(1000, r.chunkPeakAt(420))
        assertTrue(r.log.contains("RESTORE_IMMEDIATE(flush)"))
    }

    @Test
    fun qwenSpeechStartedIsABargeIn() = runTest {
        val r = Rig(this, kind = ParserKind.QWEN)
        r.connect()
        r.advanceTo(300)
        r.speakerChunk()
        r.advanceTo(320)
        r.transport.handleProviderEvent(obj("""{"type":"input_audio_buffer.speech_started"}"""))
        r.advanceTo(340)
        // Chunks are stamped at send time, after FakeMic's 30 ms read (480 frames @ 16 kHz): advance past the queried chunk.
        r.advanceTo(380)
        assertEquals(1000, r.chunkPeakAt(350))
    }

    /** RS-18 (`94e3e4a`): a relay error tears mic and speaker down (no silent "connected"). */
    @Test
    fun rs18_backendRelayErrorTearsDownMicAndSpeaker() = runTest {
        val r = Rig(this)
        r.connect()
        r.advanceTo(500)
        r.transport.handleProviderEvent(obj("""{"type":"error","error":{"message":"instructions too long"}}"""))
        r.advanceTo(600)
        assertEquals(ProviderPhase.ERROR, r.phase)
        assertEquals("instructions too long", r.transport.phase.value.message)
        assertTrue(r.mics.mics.single().released)
        assertTrue(r.tracks.last.released)
        assertTrue(ProviderSignal.SessionEnded in r.signals)
        val sent = r.chunks.size
        r.advanceTo(2_000)
        assertEquals("no chunks after teardown", sent, r.chunks.size)
    }

    /** RS-15 (`40ce856`): a mid-call `summarizing` is ignored. */
    @Test
    fun rs15_midCallSummarizingIsIgnored() = runTest {
        val r = Rig(this)
        r.connect()
        r.advanceTo(500)
        r.transport.handleProviderEvent(obj("""{"type":"voice_status","status":"summarizing"}"""))
        r.advanceTo(510)
        assertEquals(ProviderPhase.ACTIVE, r.phase)
    }

    @Test
    fun goAwayRaisesAReconnectWarning() = runTest {
        val r = Rig(this)
        r.connect()
        r.advanceTo(500)
        r.transport.handleProviderEvent(obj("""{"type":"voice_status","status":"reconnect_warning","time_left":"30m0s"}"""))
        r.advanceTo(510)
        assertTrue(ProviderSignal.ReconnectWarning(1800) in r.signals)
    }

    @Test
    fun withoutRecordPermissionTheSessionErrorsWithoutOpeningTheMic() = runTest {
        val r = Rig(this, permission = false)
        r.connect()
        r.advanceTo(1_000)
        assertEquals(ProviderPhase.ERROR, r.phase)
        assertEquals("RECORD_AUDIO permission not granted", r.transport.phase.value.message)
        assertTrue(r.mics.opens.isEmpty())
    }

    @Test
    fun disconnectReleasesEverything() = runTest {
        val r = Rig(this)
        r.connect()
        r.advanceTo(500)
        r.transport.disconnect()
        r.advanceTo(600)
        assertEquals(ProviderPhase.OFF, r.phase)
        assertTrue(r.mics.mics.single().released)
        assertTrue(r.tracks.last.released)
        assertTrue(ProviderSignal.SessionEnded in r.signals)
    }
}
