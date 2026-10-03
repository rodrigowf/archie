package com.assistant.core.audio.parity

import com.assistant.core.audio.ports.EchoDucker
import com.assistant.core.testing.FakeClock
import com.assistant.core.testing.FakePlaybackClock
import com.assistant.core.testing.PinsConstant
import com.assistant.core.testing.RecordingLog
import com.assistant.core.testing.driveTicks
import com.assistant.core.testing.frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Echo ducking, WS providers (inv04 §3.3 table, §4.8; ports the intent of the old
 * `EchoDuckControllerParityTest`). Drain-then-restore is non-negotiable
 * (memory `feedback_dont_shortcut_echo_ducking.md`).
 */
@Ignore("A-05")
class EchoDuckerParityTest {
    private val clock = FakeClock()
    private val playback = FakePlaybackClock(head = 0L, written = 0L)
    private val log = RecordingLog()
    private val ducker: EchoDucker = audioCore.echoDucker(clock, playback, log)
    private val t0 get() = clock.nowMs()

    /** Runs the drain loop until [untilMs] (absolute); returns the time the mic was restored, or null. */
    private fun drive(untilMs: Long, beforeTick: (Long) -> Unit = {}): Long? {
        var restoredAt: Long? = null
        driveTicks(
            clock, untilMs,
            nextDelay = { ducker.nextTickDelayMs },
            tick = { ducker.tick() },
            beforeTick = beforeTick,
            afterTick = { now -> if (restoredAt == null && !ducker.isDucked) restoredAt = now },
        )
        return restoredAt
    }

    @Test
    fun duck_savesGain_appliesDuckGain_logsDuckOnce() {
        ducker.setMicGain(0.8f)
        ducker.setEchoDuckingGain(0.05f)
        log.clear()
        ducker.duck()
        assertEquals(0.05f, ducker.currentMicGain, 0f)
        assertEquals(0.8f, ducker.savedGain!!, 0f)
        assertTrue(ducker.isDucked)
        assertEquals(1, log.count("[MIC_STATE] DUCK"))
    }

    @Test
    fun duck_whileAlreadyDucked_isNoOp() {
        ducker.setMicGain(1.0f)
        ducker.duck()
        log.clear()
        ducker.duck()
        assertEquals(0, log.count("[MIC_STATE] DUCK"))
        assertEquals(0.05f, ducker.currentMicGain, 0f)
        assertEquals(1.0f, ducker.savedGain!!, 0f)
    }

    @Test
    fun restoreImmediately_restoresSavedGain_andLogsReason() {
        ducker.setMicGain(0.7f)
        ducker.duck()
        log.clear()
        ducker.restoreImmediately("flush")
        assertEquals(0.7f, ducker.currentMicGain, 0f)
        assertNull(ducker.savedGain)
        assertTrue(log.dump(), log.contains("[MIC_STATE] RESTORE_IMMEDIATE(flush)"))
    }

    @Test
    fun restoreImmediately_whenNotDucked_isNoOp() {
        ducker.setMicGain(1.0f)
        log.clear()
        ducker.restoreImmediately("flush")
        assertFalse(log.contains("RESTORE_IMMEDIATE"))
        assertEquals(1.0f, ducker.currentMicGain, 0f)
    }

    @Test
    fun scheduleRestore_whenNotDucked_isNoOp() {
        ducker.scheduleRestore("stale")
        assertNull(ducker.nextTickDelayMs)
        assertFalse(ducker.isRestorePending)
    }

    /** Primary path: writes quiet ≥ 400 ms AND head ≥ written, then a 1000 ms tail. */
    @Test
    @PinsConstant("duck.no_drain_timeout")
    fun drain_primary_restoresOnlyAfterWritesQuietHeadCaughtUpAndTail() {
        ducker.setMicGain(1.0f)
        ducker.duck()
        val start = t0
        playback.written = 24_000L
        playback.head = 0L
        ducker.scheduleRestore("stale")
        assertTrue(log.contains("[MIC_STATE] RESTORE_DRAIN(stale)"))
        assertTrue(ducker.isRestorePending)
        // Writes keep flowing for 1 s (24 frames/ms); the DAC plays 24 frames/ms from 0.
        val restoredAt = drive(start + 5_000) { now ->
            val e = now - start
            playback.written = 24_000L + 24L * minOf(e, 1_000L)
            playback.head = minOf(playback.written, 24L * e)
        }
        // head reaches written (48000) at +2000; quiet since +1000 → drained at the +2000 tick → +3000.
        assertNotNull("never restored:\n${log.dump()}", restoredAt)
        assertTrue("restored too early at +${restoredAt!! - start}", restoredAt - start >= 3_000)
        assertTrue("restored too late at +${restoredAt - start}", restoredAt - start <= 3_000 + 80)
        assertEquals(1.0f, ducker.currentMicGain, 0f)
        assertTrue(log.dump(), log.contains("AudioTrack drained at head="))
        assertTrue(log.dump(), log.contains("[MIC_STATE] RESTORE_IMMEDIATE(drained:stale)"))
    }

    /** Lollipop post-underrun: head frozen below written; writes quiet + head unchanged 400 ms = drained. */
    @Test
    @PinsConstant("duck.head_stuck_fallback")
    fun drain_fallback_headStuckCountsAsDrained() {
        ducker.setMicGain(1.0f)
        ducker.duck()
        val start = t0
        playback.written = 100_000L
        playback.head = 90_000L
        ducker.scheduleRestore("stale")
        val restoredAt = drive(start + 5_000)
        assertNotNull(restoredAt)
        assertTrue(restoredAt!! - start >= 400 + 1_000)
        assertTrue(restoredAt - start <= 400 + 1_000 + 80)
        assertTrue(log.dump(), log.contains("head stuck at 90000 (written=100000)"))
    }

    @Test
    fun drain_audioTrackGone_restoresOnThatTick() {
        ducker.setMicGain(1.0f)
        ducker.duck()
        val start = t0
        playback.written = 1_000L
        ducker.scheduleRestore("stale")
        val restoredAt = drive(start + 1_000) { now -> if (now - start >= 160) playback.head = null }
        assertNotNull(restoredAt)
        assertTrue(restoredAt!! - start in 160..240)
        assertTrue(log.contains("AudioTrack gone"))
    }

    /** RS-21 (`2ccee40`/`cffec38`): a 60 s answer never restores mid-speech — there is no drain timeout. */
    @Test
    fun rs21_longAnswerNeverRestoresBeforeDrainAndTail() {
        ducker.setMicGain(1.0f)
        ducker.duck()
        val start = t0
        ducker.scheduleRestore("stale")
        val speechEnds = start + 60_000
        val restoredAt = drive(start + 70_000) { now ->
            val e = minOf(now, speechEnds) - start
            playback.written = 24L * e
            playback.head = maxOf(0L, 24L * (minOf(now, speechEnds + 500) - start) - 12_000L) // DAC lags 500 ms
        }
        assertNotNull(restoredAt)
        // Head catches up at +60.5 s; writes quiet since +60 s → drained ≈ +60.5 s, + 1000 ms tail.
        assertTrue("restored at +${restoredAt!! - start}", restoredAt - start >= 61_500)
        assertTrue("restored at +${restoredAt - start}", restoredAt - start <= 61_500 + 160)
    }

    /** A speaker chunk during the drain cancels the restore (staleness was a false signal). */
    @Test
    fun chunkDuringDrainCancelsRestore() {
        ducker.setMicGain(1.0f)
        ducker.duck()
        ducker.scheduleRestore("stale")
        ducker.cancelPendingRestore()
        assertNull(ducker.nextTickDelayMs)
        drive(clock.nowMs() + 5_000)
        assertTrue(ducker.isDucked)
        assertEquals(0.05f, ducker.currentMicGain, 0f)
    }

    /** …and during the tail wait too. */
    @Test
    fun chunkDuringTailCancelsRestore() {
        ducker.setMicGain(1.0f)
        ducker.duck()
        val start = t0
        playback.written = 100_000L
        playback.head = 100_000L
        ducker.scheduleRestore("stale")
        drive(start + 600) // drained at +400 → now in the tail
        assertTrue(ducker.isDucked)
        ducker.cancelPendingRestore()
        drive(start + 3_000)
        assertTrue("a chunk in the tail must keep the mic ducked", ducker.isDucked)
    }

    @Test
    fun setMicGain_whileDucked_updatesRestoreTargetOnly() {
        ducker.setMicGain(0.5f)
        ducker.duck()
        ducker.setMicGain(0.9f)
        assertEquals(0.05f, ducker.currentMicGain, 0f)
        assertEquals(0.9f, ducker.savedGain!!, 0f)
        assertEquals(0.9f, ducker.effectiveMicGain(), 0f)
        ducker.restoreImmediately("user")
        assertEquals(0.9f, ducker.currentMicGain, 0f)
    }

    @Test
    fun setEchoDuckingGain_whileDucked_appliesImmediately() {
        ducker.setMicGain(1.0f)
        ducker.duck()
        ducker.setEchoDuckingGain(0.10f)
        assertEquals(0.10f, ducker.currentMicGain, 0f)
        assertEquals(1.0f, ducker.savedGain!!, 0f)
    }

    @Test
    fun gainsAreClamped() {
        ducker.setMicGain(5f)
        assertEquals(2.0f, ducker.currentMicGain, 0f)
        ducker.setMicGain(-1f)
        assertEquals(0.0f, ducker.currentMicGain, 0f)
        ducker.setMicGain(1f)
        ducker.setEchoDuckingGain(3f)
        ducker.duck()
        assertEquals(1.0f, ducker.currentMicGain, 0f)
    }

    @Test
    fun cleanup_restoresSavedGain_andCancelsRestore() {
        ducker.setMicGain(0.6f)
        ducker.duck()
        ducker.scheduleRestore("stale")
        ducker.cleanup()
        assertFalse(ducker.isDucked)
        assertFalse(ducker.isRestorePending)
        assertEquals(0.6f, ducker.currentMicGain, 0f)
    }

    @Test
    fun resetForNewSession_forgetsDuckAndRestore() {
        ducker.duck()
        ducker.scheduleRestore("stale")
        ducker.resetForNewSession()
        assertFalse(ducker.isDucked)
        assertNull(ducker.nextTickDelayMs)
    }

    /** Head read as unsigned 32-bit: a wrapped (negative) head beyond 2^31 frames still drains. */
    @Test
    @PinsConstant("duck.unsigned_head")
    fun headIsReadAsUnsigned32Bit() {
        ducker.duck()
        val start = t0
        val written = 3_000_000_000L
        playback.written = written
        playback.head = written.toInt().toLong() // what AudioTrack returns after wrapping: negative
        assertTrue(playback.head!! < 0)
        ducker.scheduleRestore("stale")
        val restoredAt = drive(start + 3_000)
        assertNotNull(restoredAt)
        assertTrue(log.dump(), log.contains("AudioTrack drained at head=3000000000"))
    }

    /** Every DUCK is matched by exactly one RESTORE over complete cycles (no stuck or double restores). */
    @Test
    fun duckAndRestoreAreOneToOne() {
        ducker.setMicGain(1.0f)
        repeat(5) { cycle ->
            ducker.duck()
            ducker.duck()
            if (cycle % 2 == 0) {
                ducker.restoreImmediately("flush")
            } else {
                playback.written = 1_000L * cycle
                playback.head = playback.written
                ducker.scheduleRestore("stale")
                drive(clock.nowMs() + 3_000)
            }
            ducker.restoreImmediately("extra")
        }
        assertEquals(5, log.count("[MIC_STATE] DUCK"))
        assertEquals(5, log.count("[MIC_STATE] RESTORE_IMMEDIATE("))
    }

    /** RS-22 (`20217b1`): the duck gain is applied to captured chunks; duck gain 0 = silence. */
    @Test
    fun rs22_duckGainIsAppliedToCapturedChunks() {
        ducker.setMicGain(1.0f)
        ducker.setEchoDuckingGain(0.0f)
        ducker.duck()
        val shorts = frame(480, 8_000)
        val bytes = ByteArray(shorts.size * 2)
        shorts.forEachIndexed { i, s -> bytes[2 * i] = (s.toInt() and 0xff).toByte(); bytes[2 * i + 1] = (s.toInt() shr 8).toByte() }
        audioCore.pcm.applyGainPcm16Le(bytes, 0, bytes.size, ducker.currentMicGain)
        assertTrue("ducked chunk must be silent", bytes.all { it == 0.toByte() })
        assertEquals(0.0, audioCore.pcm.rmsAndPeakPcm16Le(bytes, 0, bytes.size).first, 0.0)
    }
}
