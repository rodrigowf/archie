package com.assistant.core.voicehost.cue

import com.assistant.core.voicehost.HostTuning
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped cue WAVs (`src/main/assets/voicehost/cues/`) are exactly the [ToneSynth] output of
 * each [CueKind], and the old cues keep the old tone parameters (inv04 §4.10).
 *
 * Regenerate after changing a tone: `ARCHIE_REGEN_CUES=1 ./gradlew :core:voice-host:testDebugUnitTest --tests '*CueAssetsTest*'`.
 */
class CueAssetsTest {
    private val dir = File("src/main/assets/voicehost/cues")

    @Test
    fun shippedAssetsEqualTheSynthesis() {
        val regen = System.getenv("ARCHIE_REGEN_CUES") == "1"
        if (regen) dir.mkdirs()
        for (kind in CueKind.entries) {
            val expected = ToneSynth.wav(ToneSynth.pcm(kind.tone))
            val file = File("src/main/assets", kind.assetPath)
            if (regen) file.writeBytes(expected)
            assertTrue("missing ${file.path} — run with ARCHIE_REGEN_CUES=1", file.isFile)
            assertArrayEquals("${kind.name} asset differs from the synthesis", expected, file.readBytes())
        }
    }

    /** `3c4dbba` / `b586e4b`: the old cues, parameter for parameter. */
    @Test
    fun oldCueParametersArePreserved() {
        assertEquals(ToneCue(listOf(660.0, 880.0), 90, 30, 0.45), CueKind.WAKE_ACK.tone)
        assertEquals(ToneCue(listOf(440.0), 150, 0, 0.45), CueKind.TALK_ACK.tone)
        assertEquals(ToneCue(listOf(880.0, 660.0), 130, 40, 0.50), CueKind.RECONNECT_WARNING.tone)
        assertEquals(22_050, HostTuning.CUE_SAMPLE_RATE_HZ)
        assertEquals("MUSIC", HostTuning.CUE_STREAM)
    }

    /** Frame counts of the old synthesis: tone + gap math with integer division at 22 050 Hz. */
    @Test
    fun synthesisLengthsMatchTheOldMath() {
        assertEquals(1984 * 2 + 661, ToneSynth.pcm(CueKind.WAKE_ACK.tone).size)
        assertEquals(3307, ToneSynth.pcm(CueKind.TALK_ACK.tone).size)
        assertEquals(2866 * 2 + 882, ToneSynth.pcm(CueKind.RECONNECT_WARNING.tone).size)
    }

    @Test
    fun theFadeStartsAndEndsAtSilence() {
        for (kind in CueKind.entries) {
            val pcm = ToneSynth.pcm(kind.tone)
            assertEquals(kind.name, 0, pcm.first().toInt())
            val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue(kind.name, peak <= (kind.tone.amplitude * Short.MAX_VALUE).toInt())
        }
    }

    /** P-2: the reconnecting cue is soft, the three link cues are audibly distinct from each other and from the old ones. */
    @Test
    fun p2CuesAreSoftAndDistinct() {
        val link = listOf(CueKind.LINK_RECONNECTING, CueKind.LINK_RESTORED, CueKind.LINK_FAILED)
        assertTrue(CueKind.LINK_RECONNECTING.tone.amplitude < CueKind.entries.filter { it != CueKind.LINK_RECONNECTING }.minOf { it.tone.amplitude })
        val signatures = CueKind.entries.map { it.tone.freqsHz }
        assertEquals("every cue has its own pitch sequence", signatures.size, signatures.toSet().size)
        assertNotEquals(CueKind.LINK_RESTORED.tone.freqsHz.size, CueKind.WAKE_ACK.tone.freqsHz.size)
        // Each pattern fits inside the repeat interval (no overlap with the next one).
        assertTrue(ToneSynth.durationMs(CueKind.LINK_RECONNECTING.tone) < HostTuning.LINK_CUE_REPEAT_MS)
        link.forEach { assertTrue(it.name, ToneSynth.durationMs(it.tone) < 1_000) }
    }

    @Test
    fun wavRoundTrip() {
        val pcm = ToneSynth.pcm(CueKind.WAKE_ACK.tone)
        val (back, rate) = ToneSynth.parseWav(ToneSynth.wav(pcm))!!
        assertEquals(HostTuning.CUE_SAMPLE_RATE_HZ, rate)
        assertArrayEquals(pcm, back)
    }
}
