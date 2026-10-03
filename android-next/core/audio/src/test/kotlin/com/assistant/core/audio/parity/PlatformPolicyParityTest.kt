package com.assistant.core.audio.parity

import com.assistant.core.audio.ports.AudioStream
import com.assistant.core.audio.ports.FocusContentType
import com.assistant.core.audio.ports.FocusGain
import com.assistant.core.audio.ports.FocusUsage
import com.assistant.core.audio.ports.MicSourceType
import com.assistant.core.audio.ports.SpeakerMode
import com.assistant.core.audio.ports.TrackConstructor
import com.assistant.core.audio.ports.TrackWriteMode
import com.assistant.core.testing.PinsConstant
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

/** API-level branches as pure policies, at 21/22/23/26/30/31/34/36 (inv04 §5.1, spec 14 §6.1). */
@Ignore("A-05")
class PlatformPolicyParityTest {

    /** RS-24 (`55037c2`): wake mic, WS mic and WebRTC ADM share one source per API level. */
    @Test
    @PinsConstant("wake.mic_source_by_sdk")
    fun rs24_wakeAndCallShareTheMicSourceAtEveryApiLevel() {
        val p = audioCore.micSourcePolicy
        for (sdk in SDK_LEVELS) {
            val expected = if (sdk < 24) MicSourceType.VOICE_RECOGNITION else MicSourceType.VOICE_COMMUNICATION
            assertEquals("API $sdk", expected, p.sourceFor(sdk))
        }
        assertEquals(MicSourceType.VOICE_RECOGNITION, p.sourceFor(23))
        assertEquals(MicSourceType.VOICE_COMMUNICATION, p.sourceFor(24))
        assertEquals(6, MicSourceType.VOICE_RECOGNITION.androidValue)
        assertEquals(7, MicSourceType.VOICE_COMMUNICATION.androidValue)
    }

    /** RS-25 (`20217b1`): API 21/22 use the blocking 3-arg write and the legacy stream constructor. */
    @Test
    @PinsConstant("audio.write_policy", "audio.legacy_track_streams")
    fun rs25_lollipopUsesBlockingWriteAndLegacyConstructor() {
        val p = audioCore.playbackPolicy
        for (sdk in SDK_LEVELS) {
            val api = p.forSdk(sdk)
            if (sdk < 23) {
                assertEquals("API $sdk", TrackWriteMode.BLOCKING_3ARG, api.writeMode)
                assertEquals("API $sdk", TrackConstructor.LEGACY_STREAM_TYPE, api.constructor)
                assertEquals("API $sdk", false, api.canSetPreferredDevice)
                assertEquals("API $sdk", false, api.hasDeviceCallback)
            } else {
                assertEquals("API $sdk", TrackWriteMode.NON_BLOCKING_4ARG, api.writeMode)
                assertEquals("API $sdk", TrackConstructor.BUILDER_WITH_ATTRIBUTES, api.constructor)
                assertEquals("API $sdk", true, api.canSetPreferredDevice)
                assertEquals("API $sdk", true, api.hasDeviceCallback)
            }
        }
        assertEquals(AudioStream.VOICE_CALL, p.legacyStreamFor(SpeakerMode.CALL))
        assertEquals(AudioStream.MUSIC, p.legacyStreamFor(SpeakerMode.MEDIA))
    }

    @Test
    @PinsConstant("session.audio_focus")
    fun audioFocusIsTransientExclusiveVoiceCommunication() {
        for (sdk in SDK_LEVELS) {
            val f = audioCore.audioFocusPolicy.forSdk(sdk)
            assertEquals(FocusGain.GAIN_TRANSIENT_EXCLUSIVE, f.gain)
            assertEquals(FocusUsage.VOICE_COMMUNICATION, f.usage)
            assertEquals(FocusContentType.SPEECH, f.contentType)
            assertEquals("API $sdk", sdk >= 26, f.useAudioFocusRequest)
            assertEquals(AudioStream.VOICE_CALL, f.legacyStream)
        }
    }
}
