package com.assistant.core.voice.parity

import com.assistant.core.testing.FakeClock
import com.assistant.core.testing.RecordingLog
import com.assistant.core.testing.driveTicks
import com.assistant.core.voice.ports.OpenAiDuckPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** OpenAI WebRTC ducking (inv04 §3.3 WebRTC table; RS-23). */
@Ignore("A-06")
class OpenAiDuckParityTest {
    private val clock = FakeClock()
    private val log = RecordingLog()
    private val duck: OpenAiDuckPolicy = voiceCore.openAiDuckPolicy(clock, log).apply { setMicGain(0.8f); setEchoDuckingGain(0.05f) }

    /** Advances [ms] running due ticks; returns the relative time the mic was restored, or null. */
    private fun run(ms: Long): Long? {
        val start = clock.nowMs()
        var restoredAt: Long? = null
        driveTicks(clock, start + ms, { duck.nextTickDelayMs }, { duck.tick() },
            afterTick = { now -> if (restoredAt == null && !duck.isDucked) restoredAt = now - start })
        return restoredAt
    }

    @Test
    fun responseCreatedDucks() {
        duck.onDcEvent("response.created")
        assertTrue(duck.isDucked)
        assertEquals(0.05f, duck.currentMicGain, 0f)
        assertTrue(log.contains("[MIC_STATE]"))
    }

    /** RS-23 (`b9e6352`/`687442e`): restore waits for `output_audio_buffer.stopped` + 2000 ms, not `response.done`. */
    @Test
    fun rs23_restoreWaitsForBufferStoppedNotResponseDone() {
        duck.onDcEvent("response.created")
        duck.onDcEvent("output_audio_buffer.started")
        assertTrue(duck.agentPlaying)
        duck.onDcEvent("response.done")
        assertEquals("response.done must not restore (audio plays 6 s+ after it)", null, run(10_000))
        assertTrue(duck.isDucked)
        duck.onDcEvent("output_audio_buffer.stopped")
        assertFalse(duck.agentPlaying)
        assertEquals(2_000L, run(5_000))
        assertEquals(0.8f, duck.currentMicGain, 0f)
    }

    @Test
    fun clearedDucksThenRestoresAfterTwoSeconds() {
        duck.onDcEvent("output_audio_buffer.cleared")
        assertTrue(duck.isDucked)
        assertFalse(duck.agentPlaying)
        assertEquals(2_000L, run(5_000))
    }

    /** RS-23: a new response cancels the pending restore timer. */
    @Test
    fun rs23_responseCreatedCancelsThePendingRestore() {
        duck.onDcEvent("output_audio_buffer.started")
        duck.onDcEvent("output_audio_buffer.stopped")
        run(1_000)
        duck.onDcEvent("response.created")
        assertEquals(null, run(5_000))
        assertTrue(duck.isDucked)
    }

    @Test
    fun userSpeechWhileTheAgentIsSilentRestoresImmediately() {
        duck.onDcEvent("response.created")
        assertFalse("not suppressed", duck.onDcEvent("input_audio_buffer.speech_started"))
        assertFalse(duck.isDucked)
        assertEquals(0.8f, duck.currentMicGain, 0f)
    }

    @Test
    fun speechStartedWhileTheAgentPlaysIsSuppressedAsEcho() {
        duck.onDcEvent("output_audio_buffer.started")
        assertTrue("suppressed", duck.onDcEvent("input_audio_buffer.speech_started"))
        assertTrue(duck.isDucked)
    }

    @Test
    fun cleanupRestoresTheSavedGain() {
        duck.onDcEvent("output_audio_buffer.started")
        duck.cleanup()
        assertFalse(duck.isDucked)
        assertFalse(duck.agentPlaying)
        assertEquals(0.8f, duck.currentMicGain, 0f)
    }
}
