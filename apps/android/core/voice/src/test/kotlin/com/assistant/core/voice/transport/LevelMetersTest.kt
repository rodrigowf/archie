package com.assistant.core.voice.transport

import com.assistant.core.voice.ports.RtcStat
import com.assistant.core.voice.ports.VoiceLevels
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure level meters behind `VoiceTransport.levels` (the dock orb; observation only). */
class LevelMetersTest {

    private fun near(expected: Float, actual: Float, msg: String = "") = assertEquals(msg, expected, actual, 1e-4f)

    @Test
    fun micLevelHoldsThePeakUntilTakenThenClears() {
        val m = MicLevel()
        m.offer(1000.0)
        m.offer(3000.0)
        m.offer(2000.0)
        near(3000f / 32768f, m.take())
        near(0f, m.take(), "cleared by take")
    }

    @Test
    fun pcmRmsIsNormalisedToFullScale() {
        val bytes = ByteArray(960)
        for (k in 0 until 480) {
            val s = if (k % 2 == 0) 4096 else -4096
            bytes[2 * k] = (s and 0xff).toByte()
            bytes[2 * k + 1] = ((s shr 8) and 0xff).toByte()
        }
        near(0.125f, PlayoutLevels.rmsPcm16Le(bytes))
        near(0f, PlayoutLevels.rmsPcm16Le(ByteArray(0)))
    }

    @Test
    fun playoutQueuesChunksBackToBackFromWhenTheyArrive() {
        val p = PlayoutLevels()
        p.add(nowMs = 1_000, durationMs = 100, rms = 0.2f)
        p.add(nowMs = 1_010, durationMs = 100, rms = 0.5f) // queued: plays 1100..1200
        near(0.2f, p.levelAt(1_050))
        near(0.5f, p.levelAt(1_150))
        near(0f, p.levelAt(1_200), "drained")
        p.add(nowMs = 1_500, durationMs = 50, rms = 0.3f) // after a gap: plays from now
        near(0f, p.levelAt(1_499))
        near(0.3f, p.levelAt(1_520))
    }

    @Test
    fun playoutClearDropsQueuedAudio() {
        val p = PlayoutLevels()
        p.add(0, 1_000, 0.4f)
        p.add(0, 1_000, 0.6f)
        p.clear()
        near(0f, p.levelAt(500))
        p.add(600, 100, 0.1f) // the timeline restarts at now, not after the dropped audio
        near(0.1f, p.levelAt(650))
    }

    @Test
    fun playoutFullExtendsTheLastEntryInsteadOfAllocating() {
        val p = PlayoutLevels(capacity = 2)
        p.add(0, 100, 0.1f)
        p.add(0, 100, 0.2f)
        p.add(0, 100, 0.7f) // merged into the second: 100..300 at max(0.2, 0.7)
        near(0.1f, p.levelAt(50))
        near(0.7f, p.levelAt(250))
        near(0f, p.levelAt(300))
    }

    private fun inbound(level: Double? = null, energy: Double? = null, duration: Double? = null, kind: String = "audio") =
        RtcStat(
            "inbound-rtp",
            buildMap {
                put("kind", kind)
                level?.let { put("audioLevel", it) }
                energy?.let { put("totalAudioEnergy", it) }
                duration?.let { put("totalSamplesDuration", it) }
            },
        )

    @Test
    fun statsSpeakerUsesTheIntervalEnergyOnceItHasTwoSamples() {
        val s = StatsSpeakerLevel()
        // First report: no interval yet → the instantaneous audioLevel.
        near(0.5f * 0.3f, s.update(listOf(inbound(level = 0.5, energy = 1.0, duration = 10.0))))
        // 66 ms at level 0.4 → ΔE = 0.4² × 0.066, ΔD = 0.066 → sqrt = 0.4 (audioLevel is ignored).
        near(0.4f * 0.3f, s.update(listOf(inbound(level = 0.9, energy = 1.0 + 0.16 * 0.066, duration = 10.066))))
        // Nothing played out since the last poll.
        near(0f, s.update(listOf(inbound(level = 0.9, energy = 1.0 + 0.16 * 0.066, duration = 10.066))))
    }

    @Test
    fun statsSpeakerFallsBackToAudioLevelAndIgnoresOtherEntries() {
        val s = StatsSpeakerLevel()
        near(0.2f * 0.3f, s.update(listOf(RtcStat("outbound-rtp", mapOf("kind" to "audio")), inbound(level = 0.2))))
        near(0f, s.update(listOf(inbound(level = 0.8, kind = "video"))), "video stream")
        near(0f, s.update(emptyList()))
        near(1f * 0.3f, s.update(listOf(inbound(level = 3.0))), "clamped")
    }

    @Test
    fun statsSpeakerReadsTheLegacyRemoteTrackAndPrefersInboundRtp() {
        val local = RtcStat("track", mapOf("kind" to "audio", "remoteSource" to false, "audioLevel" to 0.9))
        val remote = RtcStat("track", mapOf("kind" to "audio", "remoteSource" to true, "audioLevel" to 0.6))
        near(0.6f * 0.3f, StatsSpeakerLevel().update(listOf(local, remote)))
        near(0.1f * 0.3f, StatsSpeakerLevel().update(listOf(remote, inbound(level = 0.1))))
        near(0f, StatsSpeakerLevel().update(listOf(local)))
    }

    @Test
    fun visualLevelMatchesTheWebMapping() {
        near(0f, VoiceLevels.visual(0f))
        near(0.4f, VoiceLevels.visual(0.04f))
        near(1f, VoiceLevels.visual(0.25f))
        near(1f, VoiceLevels.visual(0.9f))
    }
}
