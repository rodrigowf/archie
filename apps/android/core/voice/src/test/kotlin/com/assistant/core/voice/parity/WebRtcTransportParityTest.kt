package com.assistant.core.voice.parity

import com.assistant.core.audio.ports.MicSourceType
import com.assistant.core.testing.FakeClock
import com.assistant.core.testing.FakeRtcPlatform
import com.assistant.core.testing.FakeSdpExchange
import com.assistant.core.testing.FakeVoiceApi
import com.assistant.core.testing.PinsConstant
import com.assistant.core.testing.RecordingLog
import com.assistant.core.testing.obj
import com.assistant.core.voice.ports.IceState
import com.assistant.core.voice.ports.ProviderPhase
import com.assistant.core.voice.ports.ProviderSignal
import com.assistant.core.voice.ports.VoiceTransport
import com.assistant.core.voice.ports.WebRtcDeps
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenAI Realtime over WebRTC with a fake PeerConnection factory (inv04 §2.2, §4.9; RS-02, RS-03,
 * RS-06, RS-07, RS-08, RS-23). Teardown order and threading are asserted on the fake's op log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebRtcTransportParityTest {

    private class Rig(private val ts: TestScope, sdkInt: Int = 26) {
        val clock = FakeClock.boundTo(ts.testScheduler)
        val log = RecordingLog()
        val platform = FakeRtcPlatform()
        val sdp = FakeSdpExchange()
        val transport: VoiceTransport = voiceCore.webRtcTransport(WebRtcDeps(ts.backgroundScope, clock, log, sdkInt, platform, sdp))
        val mirrored: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
        val signals: MutableList<ProviderSignal> = Collections.synchronizedList(mutableListOf())

        init {
            ts.backgroundScope.launch(UnconfinedTestDispatcher(ts.testScheduler)) { transport.signals.collect { signals += it } }
        }

        fun connect() {
            ts.backgroundScope.launch { transport.connect(FakeVoiceApi.WEBRTC_CONNECTION, { mirrored += it }, {}) }
            ts.runCurrent()
        }

        fun advance(ms: Long) { ts.testScheduler.advanceTimeBy(ms); ts.runCurrent() }
        val phase get() = transport.phase.value.phase
        val teardownOps get() = platform.opNames.filter { it in TEARDOWN }

        /** Waits (real time) for asynchronous teardown on the transport's scope. */
        fun awaitOps(n: Int) {
            repeat(200) { if (teardownOps.size >= n) return; advance(10); Thread.sleep(5) }
        }
    }

    companion object {
        val TEARDOWN = setOf("dc.close", "track.dispose", "pc.close", "pc.dispose", "factory.dispose")
        val ORDER = listOf("dc.close", "track.dispose", "pc.close", "pc.dispose", "factory.dispose")
        val CONSTRAINTS = listOf(
            "echoCancellation", "noiseSuppression", "autoGainControl", "googEchoCancellation", "googEchoCancellation2",
            "googDAEchoCancellation", "googAutoGainControl", "googAutoGainControl2", "googNoiseSuppression",
            "googNoiseSuppression2", "googHighpassFilter", "googTypingNoiseDetection",
        ).associateWith { "true" }
    }

    @Test
    @PinsConstant(
        "webrtc.sw_aec_ns_agc", "webrtc.hw_aec_ns_off", "webrtc.goog_constraints",
        "webrtc.data_channel_ordered", "webrtc.unified_plan_max_bundle",
    )
    fun connectUsesTheTunedAudioAndPeerConfiguration() = runTest {
        val r = Rig(this, sdkInt = 22)
        r.connect()
        r.advance(100)
        val audio = r.platform.factories.single().audio
        assertFalse(audio.useHardwareAec)
        assertFalse(audio.useHardwareNs)
        assertTrue(audio.webRtcBasedAec && audio.webRtcBasedNs && audio.webRtcBasedAgc)
        assertEquals(MicSourceType.VOICE_RECOGNITION, audio.micSource)
        assertEquals(CONSTRAINTS, audio.mandatoryConstraints)
        val peer = r.platform.lastPeer.options
        assertTrue(peer.unifiedPlan && peer.maxBundle && peer.sendTrack && peer.recvOnlyAudioTransceiver)
        assertEquals("oai-events", peer.dataChannelLabel)
        assertTrue(peer.dataChannelOrdered)
        val call = r.sdp.calls.single()
        assertEquals(FakeVoiceApi.WEBRTC_CONNECTION.endpoint, call.endpoint)
        assertEquals("ek_test", call.token)
        assertEquals("v=0 answer", r.platform.lastPeer.remoteAnswer)
        assertEquals(ProviderPhase.ACTIVE, r.phase)
        assertTrue(ProviderSignal.SessionCreated in r.signals)
    }

    @Test
    fun micSourceIsVoiceCommunicationFromApi24() = runTest {
        val r = Rig(this, sdkInt = 26)
        r.connect()
        r.advance(100)
        assertEquals(MicSourceType.VOICE_COMMUNICATION, r.platform.factories.single().audio.micSource)
    }

    /** RS-08 (`2a33e8d`): `PeerConnectionFactory.initialize` at most once per process. */
    @Test
    @PinsConstant("webrtc.initialize_once")
    fun rs08_peerConnectionFactoryInitializedOncePerProcess() = runTest {
        val r = Rig(this)
        r.connect(); r.advance(100)
        r.transport.disconnect(); r.advance(100)
        r.connect(); r.advance(100)
        assertTrue("initialize calls: ${r.platform.initializeCalls.get()}", r.platform.initializeCalls.get() <= 1)
        assertEquals(2, r.platform.factories.size)
    }

    /** RS-02 (`9515576`): commands handed over before connect are not cleared by connect. */
    @Test
    fun rs02_commandsDrainedBeforeConnectAreSentAtOpen() = runTest {
        val r = Rig(this)
        val update = obj("""{"type":"session.update","session":{"voice":"cedar"}}""")
        r.transport.handleBackendCommand(update)
        r.connect()
        r.advance(100)
        assertEquals(listOf(update.toString()), r.platform.lastPeer.sent.map { obj(it).toString() })
    }

    /** RS-03 (`d4bc698`): nothing pending at open → the cached update is re-asserted. */
    @Test
    fun rs03_cachedSessionUpdateReassertedAtDcOpen() = runTest {
        val r = Rig(this)
        val update = obj("""{"type":"session.update","session":{"voice":"cedar"}}""")
        r.transport.setSessionUpdateFallback { update }
        r.connect()
        r.advance(100)
        assertEquals(listOf(update), r.platform.lastPeer.sent.map { obj(it) })
        assertTrue(r.log.dump(), r.log.contains("session.update missing at DC_OPEN"))
    }

    /** RS-03: the `session.updated` echo self-heals once when the open had nothing to send. */
    @Test
    fun rs03_sessionUpdatedEchoSelfHealsOnce() = runTest {
        val r = Rig(this)
        var cache: JsonObject? = null
        r.transport.setSessionUpdateFallback { cache }
        r.connect()
        r.advance(100)
        assertTrue(r.platform.lastPeer.sent.isEmpty())
        cache = obj("""{"type":"session.update","session":{"voice":"cedar"}}""")
        r.platform.lastPeer.messageFromThread("""{"type":"session.updated"}""")
        r.platform.lastPeer.messageFromThread("""{"type":"session.updated"}""")
        r.advance(10)
        assertEquals(1, r.platform.lastPeer.sent.size)
    }

    @Test
    fun everyDataChannelEventIsMirroredToTheBackend() = runTest {
        val r = Rig(this)
        r.connect()
        r.advance(100)
        r.platform.lastPeer.messageFromThread("""{"type":"response.created"}""")
        r.platform.lastPeer.messageFromThread("""{"type":"response.done"}""")
        r.advance(10)
        assertEquals(listOf("response.created", "response.done"), r.mirrored.map { it["type"].toString().trim('"') })
        assertTrue(ProviderSignal.TurnComplete in r.signals)
    }

    /** RS-06 (`0196e2a`): ICE DISCONNECTED is transient — no teardown. */
    @Test
    fun rs06_iceDisconnectedDoesNotTearDown() = runTest {
        val r = Rig(this)
        r.connect()
        r.advance(100)
        r.platform.lastPeer.iceStateFromThread(IceState.DISCONNECTED)
        r.advance(1_000)
        assertTrue(r.teardownOps.isEmpty())
        assertEquals(ProviderPhase.ACTIVE, r.phase)
    }

    /** RS-06: ICE FAILED tears down — never on the signalling thread, in order. */
    @Test
    fun rs06_iceFailedTearsDownOffTheSignallingThread() = runTest {
        val r = Rig(this)
        r.connect()
        r.advance(100)
        r.platform.lastPeer.iceStateFromThread(IceState.FAILED, "signaling_thread")
        r.awaitOps(5)
        assertEquals(ORDER, r.teardownOps)
        assertTrue(r.platform.ops.toString(), r.platform.ops.none { it.endsWith("@signaling_thread") })
        assertEquals(ProviderPhase.ERROR, r.phase)
        assertTrue(ProviderSignal.Error("Connection failed") in r.signals)
    }

    /** RS-07 (`91a5df5`): an OpenAI `error` event tears down off the data-channel thread, in order. */
    @Test
    fun rs07_openAiErrorTearsDownOffTheDataChannelThreadInOrder() = runTest {
        val r = Rig(this)
        r.connect()
        r.advance(100)
        r.platform.lastPeer.messageFromThread("""{"type":"error","error":{"code":"invalid_value","message":"too long"}}""", "network_thread")
        r.awaitOps(5)
        assertEquals(ORDER, r.teardownOps)
        assertTrue(r.platform.ops.none { it.endsWith("@network_thread") })
        assertEquals(ProviderPhase.ERROR, r.phase)
        assertEquals("Voice error: invalid_value", r.transport.phase.value.message)
    }

    /** RS-07: reentrant / concurrent cleanup disposes each native object exactly once. */
    @Test
    fun rs07_reentrantCleanupDisposesOnce() = runTest {
        val r = Rig(this)
        r.connect()
        r.advance(100)
        val peer = r.platform.lastPeer
        val threads = (0 until 3).map { thread(name = "stopper-$it") { runBlocking { r.transport.disconnect() } } }
        peer.iceStateFromThread(IceState.FAILED)
        threads.forEach { it.join(5_000) }
        r.awaitOps(5)
        assertEquals(1, peer.dcCloses.get())
        assertEquals(1, peer.trackDisposes.get())
        assertEquals(1, peer.closes.get())
        assertEquals(1, peer.disposes.get())
        assertEquals(1, r.platform.factories.single().disposes.get())
    }

    @Test
    fun aDataChannelThatNeverOpensTimesOutAfterFifteenSeconds() = runTest {
        val r = Rig(this)
        r.platform.openDataChannelOnAnswer = false
        r.connect()
        r.advance(14_999)
        assertEquals(ProviderPhase.CONNECTING, r.phase)
        r.advance(1)
        r.awaitOps(5)
        assertEquals(ProviderPhase.ERROR, r.phase)
        assertEquals(ORDER, r.teardownOps)
    }

    @Test
    fun anSdpFailureIsAnErrorWithTeardown() = runTest {
        val r = Rig(this)
        r.sdp.answer = null
        r.connect()
        r.advance(1_000)
        r.awaitOps(5)
        assertEquals(ProviderPhase.ERROR, r.phase)
        assertTrue(r.signals.any { it is ProviderSignal.Error })
    }

    /** Gain applied in the ADM record callback; RS-23 ducking through the hook. */
    @Test
    fun micGainAndDuckingAreAppliedInTheRecordHook() = runTest {
        val r = Rig(this)
        r.transport.setMicGain(0.5f)
        r.connect()
        r.advance(100)
        val hook = r.platform.factories.single().micHook
        fun sample(): Int {
            val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(0, 1000)
            hook(b)
            return b.order(ByteOrder.LITTLE_ENDIAN).getShort(0).toInt()
        }
        assertEquals(500, sample())
        r.platform.lastPeer.messageFromThread("""{"type":"response.created"}""")
        r.advance(0)
        assertEquals(50, sample()) // duck gain 0.05
        r.platform.lastPeer.messageFromThread("""{"type":"output_audio_buffer.stopped"}""")
        r.advance(1_999)
        assertEquals(50, sample())
        r.advance(1)
        assertEquals(500, sample())
    }

    @Test
    fun muteDisablesTheSendTrack() = runTest {
        val r = Rig(this)
        r.connect()
        r.advance(100)
        assertTrue(r.transport.toggleMute())
        assertFalse(r.platform.lastPeer.trackEnabled)
        assertFalse(r.transport.toggleMute())
        assertTrue(r.platform.lastPeer.trackEnabled)
    }
}
