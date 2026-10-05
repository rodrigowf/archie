package com.assistant.peripheral

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.audio.platform.LogcatVoiceLog
import com.assistant.core.audio.ports.MicSourceType
import com.assistant.core.protocol.ClientFrame
import com.assistant.core.protocol.ProtocolCodec
import com.assistant.core.protocol.ServerFrame
import com.assistant.core.voice.platform.AndroidRtcPlatform
import com.assistant.core.voice.ports.RtcAudioOptions
import com.assistant.core.wakeword.vosk.AndroidVoskModelSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The release smoke of spec 14 §1.10 (part of G-01), lite side: the native pieces load on API 21
 * (patched libvosk + stderr shim, WebRTC 1.1.1 JNI) and the codec round-trips. Run it against the
 * debug and the minified release build.
 */
@RunWith(AndroidJUnit4::class)
class NativeSmokeTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun voskLoads_andARecognizerAcceptsOneSecondOfSilence() = runBlocking {
        val factory = AndroidVoskModelSource.get(ctx, LogcatVoiceLog).load()
        assertNotNull("Vosk model failed to load (patched libvosk / shim on API 21?)", factory)
        val rec = factory!!.create("[\"wake up\", \"my friend\", \"[unk]\"]", 16_000)
        val silence = ShortArray(16_000)
        val out = rec.accept(silence, silence.size)
        assertTrue("silence must not match a phrase: '${out.text}'", out.text.isBlank() || out.text == "[unk]")
        rec.close()
    }

    @Test fun peerConnectionFactory_initializesOnce_andCreates() {
        val rtc = AndroidRtcPlatform(ctx, LogcatVoiceLog)
        rtc.initializeGlobals()
        val factory = rtc.createFactory(
            RtcAudioOptions(
                useHardwareAec = false, useHardwareNs = false, webRtcBasedAec = true, webRtcBasedNs = true,
                webRtcBasedAgc = true, micSource = MicSourceType.VOICE_RECOGNITION, mandatoryConstraints = emptyMap(),
            ),
        ) { }
        factory.dispose()
    }

    @Test fun protocolCodec_roundTrip() {
        val text = ProtocolCodec.encodeClient(ClientFrame.Start(localId = "abc", resumeSdkId = "sdk"))
        assertEquals(ClientFrame.Start(localId = "abc", resumeSdkId = "sdk"), ProtocolCodec.decodeClient(text))
        val s = ProtocolCodec.decodeServer("""{"type":"session_started","session_id":"abc","voice":false}""")
        assertEquals("abc", (s as ServerFrame.SessionStarted).sessionId)
    }
}
