package com.assistant.core.voice.transport

import com.assistant.core.audio.policy.FixedMicSourcePolicy
import com.assistant.core.audio.ports.MicSourceType
import com.assistant.core.testing.FakeClock
import com.assistant.core.testing.FakeSdpExchange
import com.assistant.core.testing.FakeVoiceApi
import com.assistant.core.testing.RecordingLog
import com.assistant.core.voice.ports.RtcAudioOptions
import com.assistant.core.voice.ports.RtcFactory
import com.assistant.core.voice.ports.RtcMicFeed
import com.assistant.core.voice.ports.RtcPeer
import com.assistant.core.voice.ports.RtcPeerObserver
import com.assistant.core.voice.ports.RtcPeerOptions
import com.assistant.core.voice.ports.RtcPlatform
import com.assistant.core.voice.ports.WebRtcDeps
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 15 TV-9 / §4.2: the WebRTC additions (ADM source override, mic feed, media playback) are
 * additive. A transport built the phones' way records with the API-level source, keeps the
 * recorded audio and the voice-communication attributes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebRtcMicOverrideTest {

    private class Platform : RtcPlatform, RtcFactory, RtcPeer {
        var options: RtcAudioOptions? = null
        var micHook: ((ByteBuffer) -> Unit)? = null
        override var isDataChannelOpen = false
        override fun initializeGlobals() = Unit
        override fun createFactory(audio: RtcAudioOptions, micHook: (ByteBuffer) -> Unit): RtcFactory = also {
            options = audio
            this.micHook = micHook
        }
        override fun createPeer(options: RtcPeerOptions, observer: RtcPeerObserver): RtcPeer = this
        override fun dispose() = Unit
        override suspend fun createOffer() = "v=0 offer"
        override suspend fun setRemoteAnswer(sdp: String) = Unit
        override fun sendOnDataChannel(text: String) = false
        override fun setSendTrackEnabled(enabled: Boolean) = Unit
        override fun closeDataChannel() = Unit
        override fun disposeSendTrack() = Unit
        override fun close() = Unit

        fun record(amplitude: Short): ByteBuffer {
            val b = ByteBuffer.allocate(320).order(ByteOrder.LITTLE_ENDIAN)
            repeat(160) { b.putShort(amplitude) }
            b.flip()
            micHook!!(b)
            return b
        }
    }

    private fun connect(ts: TestScope, deps: (Platform) -> WebRtcDeps): Platform {
        val p = Platform()
        val t = WebRtcTransport(deps(p))
        ts.backgroundScope.launch { t.connect(FakeVoiceApi.WEBRTC_CONNECTION, {}, { _, _ -> }) }
        ts.testScheduler.advanceTimeBy(500)
        ts.runCurrent()
        return p
    }

    @Test
    fun phoneDefaultsAreUnchanged() {
        assertEquals(MicSourceType.VOICE_COMMUNICATION, WebRtcTransport.audioOptions(26).micSource)
        assertEquals(MicSourceType.VOICE_COMMUNICATION, WebRtcTransport.audioOptions(36).micSource)
        assertEquals(MicSourceType.VOICE_RECOGNITION, WebRtcTransport.audioOptions(22).micSource)
        assertFalse(WebRtcTransport.audioOptions(30).mediaPlayback)
    }

    @Test
    fun phoneTransportKeepsTheRecordedAudio() = runTest {
        val clock = FakeClock.boundTo(testScheduler)
        val p = connect(this) { WebRtcDeps(backgroundScope, clock, RecordingLog(), 30, it, FakeSdpExchange()) }
        assertEquals(MicSourceType.VOICE_COMMUNICATION, p.options!!.micSource)
        assertFalse(p.options!!.mediaPlayback)
        val b = p.record(1234)
        assertEquals(1234.toShort(), b.getShort(0))
    }

    @Test
    fun tvOverridesReachTheAdmAndTheFeedReplacesTheBuffer() = runTest {
        val clock = FakeClock.boundTo(testScheduler)
        val feed = RtcMicFeed { buf -> var i = buf.position(); while (i < buf.limit() - 1) { buf.putShort(i, 7); i += 2 } }
        val p = connect(this) {
            WebRtcDeps(
                backgroundScope, clock, RecordingLog(), 30, it, FakeSdpExchange(),
                micSourcePolicy = FixedMicSourcePolicy(MicSourceType.VOICE_RECOGNITION),
                micFeed = feed,
                mediaPlayback = true,
            )
        }
        assertEquals(MicSourceType.VOICE_RECOGNITION, p.options!!.micSource)
        assertTrue(p.options!!.mediaPlayback)
        val b = p.record(1234)
        assertEquals("the feed wrote the buffer before WebRTC saw it", 7.toShort(), b.order(ByteOrder.LITTLE_ENDIAN).getShort(0))
    }
}
