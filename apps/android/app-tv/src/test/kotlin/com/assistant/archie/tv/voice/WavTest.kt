package com.assistant.archie.tv.voice

import com.assistant.core.audio.pcm.Pcm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** WAV round trips (voice messages, dictation, the debug injection's reader). */
class WavTest {
    @Test
    fun pcmWavRoundTrip() {
        val frames = listOf(ShortArray(320) { (it * 7).toShort() }, ShortArray(160) { (-it).toShort() })
        val wav = Pcm.wav(frames, 16_000)
        val (pcm, rate) = Wav.parse(wav)!!
        assertEquals(16_000, rate)
        assertArrayEquals(frames[0] + frames[1], pcm)
    }

    @Test
    fun stereoIsMixedDownAndJunkIsRejected() {
        val pcm = ShortArray(4) { 100 }
        val wav = Pcm.wav(listOf(pcm), 8_000)
        // rewrite as stereo: channels = 2, byte rate / block align accordingly
        wav[22] = 2; wav[32] = 4
        val (mono, _) = Wav.parse(wav)!!
        assertEquals(2, mono.size)
        assertEquals(100, mono[0].toInt())
        assertNull(Wav.parse(ByteArray(10)))
        assertNull(Wav.parse("RIFF....WAVEjunk".toByteArray() + ByteArray(40)))
    }

    @Test
    fun resampleKeepsTheDuration() {
        val one = ShortArray(48_000) { 1_000 }
        val out = Wav.resample(one, 48_000, 16_000)
        assertEquals(16_000, out.size)
        assertEquals(1_000, out[8_000].toInt())
    }
}
