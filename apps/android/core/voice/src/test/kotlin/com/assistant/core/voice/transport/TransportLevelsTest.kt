package com.assistant.core.voice.transport

import com.assistant.core.audio.ports.AudioCore
import com.assistant.core.testing.FakeAudioTrackFactory
import com.assistant.core.testing.FakeClock
import com.assistant.core.testing.FakeMicFactory
import com.assistant.core.testing.FakeSdpExchange
import com.assistant.core.testing.FakeVoiceApi
import com.assistant.core.testing.MicScript
import com.assistant.core.testing.Parity
import com.assistant.core.testing.RecordingLog
import com.assistant.core.voice.ports.ParserKind
import com.assistant.core.voice.ports.RtcAudioOptions
import com.assistant.core.voice.ports.RtcFactory
import com.assistant.core.voice.ports.RtcPeer
import com.assistant.core.voice.ports.RtcPeerObserver
import com.assistant.core.voice.ports.RtcPeerOptions
import com.assistant.core.voice.ports.RtcPlatform
import com.assistant.core.voice.ports.RtcStat
import com.assistant.core.voice.ports.VoiceCore
import com.assistant.core.voice.ports.VoiceLevels
import com.assistant.core.voice.ports.WebRtcDeps
import com.assistant.core.voice.ports.WsPcmDeps
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `VoiceTransport.levels` of both transports against fakes: what each side measures, the ~15 Hz
 * cadence, and that nothing is metered while nobody collects. Observation only — the parity
 * suites cover that the audio behaviour is unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportLevelsTest {

    private val voiceCore: VoiceCore by lazy { Parity.load<VoiceCore>("A-06") }

    private class Collector(private val ts: TestScope, private val clock: FakeClock) {
        val got: MutableList<Pair<Long, VoiceLevels>> = Collections.synchronizedList(mutableListOf())
        var job: Job? = null
        fun start(levels: kotlinx.coroutines.flow.Flow<VoiceLevels>) {
            job = ts.backgroundScope.launch(UnconfinedTestDispatcher(ts.testScheduler)) { levels.collect { got += clock.nowMs() to it } }
        }
        val last get() = got.last().second
    }

    private fun near(expected: Float, actual: Float, msg: String = "") = assertEquals(msg, expected, actual, 1e-3f)

    private fun pcm(amplitude: Int, frames: Int): String {
        val b = ByteArray(frames * 2)
        for (k in 0 until frames) {
            val s = if (k % 2 == 0) amplitude else -amplitude
            b[2 * k] = (s and 0xff).toByte()
            b[2 * k + 1] = ((s shr 8) and 0xff).toByte()
        }
        return Base64.getEncoder().encodeToString(b)
    }

    // ── WebSocket PCM (Qwen / Gemini) ──────────────────────────────────────────────────────────

    private inner class WsRig(val ts: TestScope) {
        val clock = FakeClock.boundTo(ts.testScheduler)
        val mics = FakeMicFactory(clock, MicScript.constant(1000))
        val tracks = FakeAudioTrackFactory(22, minBuffer = 4000, clock = clock)
        val audio: AudioCore = Parity.load("A-05")
        val transport = WsPcmTransport(
            WsPcmDeps(ts.backgroundScope, clock, RecordingLog(), 22, mics, tracks, audio) { true },
            voiceCore.parser(ParserKind.GEMINI),
            "google",
        )
        val levels = Collector(ts, clock)

        fun connect() {
            ts.backgroundScope.launch { transport.connect(FakeVoiceApi.WS_CONNECTION, {}) {} }
            advance(300) // 200 ms HAL settle, then mic + speaker
        }

        fun advance(ms: Long) { ts.testScheduler.advanceTimeBy(ms); ts.runCurrent() }
    }

    @Test
    fun wsPublishesTheMicRmsEvery66ms() = runTest {
        val r = WsRig(this)
        r.connect()
        r.levels.start(r.transport.levels)
        r.advance(660)
        val times = r.levels.got.map { it.first }
        assertTrue("≈15 Hz, got ${times.size}", times.size in 10..11)
        assertTrue("never faster than 66 ms", times.zipWithNext().all { (a, b) -> b - a >= VoiceLevels.PERIOD_MS })
        near(1000f / 32768f, r.levels.last.mic, "mic = pre-gain RMS of the fake mic")
        near(0f, r.levels.last.speaker)
    }

    @Test
    fun wsMutedMicReadsZero() = runTest {
        val r = WsRig(this)
        r.connect()
        r.levels.start(r.transport.levels)
        r.advance(200)
        r.transport.toggleMute()
        r.advance(200)
        near(0f, r.levels.last.mic)
    }

    @Test
    fun wsSpeakerFollowsThePlayoutTimelineAndBargeInClearsIt() = runTest {
        val r = WsRig(this)
        r.connect()
        r.levels.start(r.transport.levels)
        // Two 100 ms chunks (2400 frames at 24 kHz) arriving together: they play back to back.
        r.transport.pushSpeakerChunk(pcm(8192, 2400))
        r.transport.pushSpeakerChunk(pcm(4096, 2400))
        r.advance(66)
        near(0.25f, r.levels.last.speaker, "first chunk playing")
        r.advance(66)
        near(0.125f, r.levels.last.speaker, "second chunk playing")
        r.advance(132)
        near(0f, r.levels.last.speaker, "played out")

        r.transport.pushSpeakerChunk(pcm(8192, 24000)) // 1 s
        r.advance(66)
        near(0.25f, r.levels.last.speaker)
        r.transport.handleProviderEvent(buildJsonObject { putJsonObject("serverContent") { put("interrupted", true) } })
        r.advance(66)
        near(0f, r.levels.last.speaker, "barge-in flush drops the queued audio")
    }

    @Test
    fun wsSpeakerIsNotMeteredWhileNobodyCollects() = runTest {
        val r = WsRig(this)
        r.connect()
        r.transport.pushSpeakerChunk(pcm(8192, 24000)) // 1 s, unobserved
        r.levels.start(r.transport.levels)
        r.advance(66)
        near(0f, r.levels.last.speaker)
        r.levels.job?.cancel()
        r.transport.disconnect()
    }

    // ── WebRTC (OpenAI) ────────────────────────────────────────────────────────────────────────

    /** Minimal RtcPlatform whose peer answers `getStats()` with [stats] (or never, when null). */
    private class StatsPlatform : RtcPlatform, RtcFactory, RtcPeer {
        var micHook: ((ByteBuffer) -> Unit)? = null
        var observer: RtcPeerObserver? = null
        var stats: List<RtcStat>? = emptyList()
        val statsRequests = AtomicInteger(0)
        override var isDataChannelOpen = false

        override fun initializeGlobals() = Unit
        override fun createFactory(audio: RtcAudioOptions, micHook: (ByteBuffer) -> Unit): RtcFactory = also { this.micHook = micHook }
        override fun createPeer(options: RtcPeerOptions, observer: RtcPeerObserver): RtcPeer = also { this.observer = observer }
        override fun dispose() = Unit
        override suspend fun createOffer() = "v=0 offer"
        override suspend fun setRemoteAnswer(sdp: String) = Unit
        override fun sendOnDataChannel(text: String) = isDataChannelOpen
        override fun setSendTrackEnabled(enabled: Boolean) = Unit
        override fun closeDataChannel() { isDataChannelOpen = false }
        override fun disposeSendTrack() = Unit
        override fun close() = Unit

        override fun requestStats(onResult: (List<RtcStat>) -> Unit): Boolean {
            statsRequests.incrementAndGet()
            stats?.let(onResult)
            return true
        }

        fun openDataChannel() { isDataChannelOpen = true; observer!!.onDataChannelOpen() }

        fun record(amplitude: Int) {
            val b = ByteBuffer.allocate(960).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until 480) b.putShort((if (k % 2 == 0) amplitude else -amplitude).toShort())
            b.flip()
            micHook!!(b)
        }
    }

    private inner class RtcRig(val ts: TestScope) {
        val clock = FakeClock.boundTo(ts.testScheduler)
        val platform = StatsPlatform()
        val transport = WebRtcTransport(WebRtcDeps(ts.backgroundScope, clock, RecordingLog(), 26, platform, FakeSdpExchange()))
        val levels = Collector(ts, clock)

        fun connect() {
            ts.backgroundScope.launch { transport.connect(FakeVoiceApi.WEBRTC_CONNECTION, {}, {}) }
            ts.runCurrent()
        }

        fun advance(ms: Long) { ts.testScheduler.advanceTimeBy(ms); ts.runCurrent() }
    }

    private fun inbound(level: Double) = RtcStat("inbound-rtp", mapOf("kind" to "audio", "audioLevel" to level))

    @Test
    fun webRtcPollsStatsOnlyOnceTheSessionIsOpen() = runTest {
        val r = RtcRig(this)
        r.platform.stats = listOf(inbound(0.5))
        r.connect()
        r.levels.start(r.transport.levels)
        r.advance(132)
        assertEquals("no getStats before the data channel opens", 0, r.platform.statsRequests.get())
        near(0f, r.levels.last.speaker)

        r.platform.openDataChannel()
        r.advance(66)
        assertTrue(r.platform.statsRequests.get() >= 1)
        near(0.5f * 0.3f, r.levels.last.speaker)

        r.transport.disconnect()
        val asked = r.platform.statsRequests.get()
        r.advance(198)
        assertEquals("never asks a torn-down peer", asked, r.platform.statsRequests.get())
        near(0f, r.levels.last.speaker)
    }

    @Test
    fun webRtcMicIsThePreGainRecordRmsAndMutedReadsZero() = runTest {
        val r = RtcRig(this)
        r.connect()
        r.platform.openDataChannel()
        r.platform.record(2000) // not collected yet: not metered
        r.levels.start(r.transport.levels)
        near(0f, r.levels.last.mic)
        r.platform.record(2000)
        r.platform.record(1000)
        r.advance(66)
        near(2000f / 32768f, r.levels.last.mic, "peak RMS of the window")
        r.advance(66)
        near(0f, r.levels.last.mic, "no buffers in this window")
        r.transport.toggleMute()
        r.platform.record(2000)
        r.advance(66)
        near(0f, r.levels.last.mic)
        r.transport.disconnect()
    }

    @Test
    fun webRtcStatsThatNeverAnswerDoNotStallTheLevels() = runTest {
        val r = RtcRig(this)
        r.platform.stats = null
        r.connect()
        r.platform.openDataChannel()
        r.levels.start(r.transport.levels)
        r.advance(1_000)
        assertTrue("still publishing, got ${r.levels.got.size}", r.levels.got.size >= 3)
        near(0f, r.levels.last.speaker)
        r.transport.disconnect()
    }
}
