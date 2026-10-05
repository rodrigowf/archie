package com.assistant.core.audio.parity

import com.assistant.core.testing.WavReader
import com.assistant.core.testing.frame
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** PCM utilities used by the wake loop (RMS, WAV for Whisper / talk messages) and the WS mic path. */
class PcmMathParityTest {
    private val pcm = audioCore.pcm

    @Test
    fun rmsOfAConstantAmplitudeFrameIsTheAmplitude() {
        assertEquals(70.0, pcm.rms(frame(1600, 70)), 1e-9)
        assertEquals(0.0, pcm.rms(ShortArray(0), 0), 0.0)
        assertEquals(30.0, pcm.rms(frame(3200, 30), 3200), 1e-9)
    }

    @Test
    fun rmsAndPeakOfLittleEndianBytes() {
        val bytes = byteArrayOf(0x10, 0x00, 0xF0.toByte(), 0xFF.toByte()) // +16, -16
        val (rms, peak) = pcm.rmsAndPeakPcm16Le(bytes, 0, bytes.size)
        assertEquals(16.0, rms, 1e-9)
        assertEquals(16, peak)
    }

    @Test
    fun gainIsAppliedAndClampedToTheShortRange() {
        val bytes = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte()) // +16384, -16384
        pcm.applyGainPcm16Le(bytes, 0, bytes.size, 2.0f)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte()), bytes) // +32767, -32768
    }

    @Test
    fun wavIsMono16BitWithTheGivenRate() {
        val wav = pcm.wav(listOf(frame(3200, 100), frame(1600, 100)), 16000)
        val info = WavReader.read(wav)
        assertEquals(16000, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(4800, info.samples)
        assertEquals(44 + 9600, wav.size)
    }

    @Test
    fun base64HasNoLineWraps() {
        val bytes = ByteArray(3000) { it.toByte() }
        val b64 = pcm.base64(bytes)
        assertFalse(b64.contains('\n'))
        assertArrayEquals(bytes, Base64.getDecoder().decode(b64))
    }
}
