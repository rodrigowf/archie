package com.assistant.core.audio.duck

import com.assistant.core.audio.AudioTuning
import com.assistant.core.audio.ports.EchoDucker
import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.audio.ports.PlaybackClock
import com.assistant.core.audio.ports.VoiceLog

/**
 * Drain-then-restore echo ducking for the WS PCM providers (inv04 §3.3 WS table, §4.8): the old
 * `EchoDuckController` as a pure, tick-driven FSM.
 *
 * ```
 * UNDUCKED ──duck()──► DUCKED ──scheduleRestore()──► DRAINING ──drained──► TAIL ──+1000 ms──► UNDUCKED
 *                        ▲                              │  (head null ─────────────────────► UNDUCKED)
 *                        └── cancelPendingRestore() / duck() ──┴── (from DRAINING or TAIL)
 * ```
 *
 * The old controller ran the drain in a coroutine (`delay(80)` / `delay(1000)`); here the owner
 * waits [nextTickDelayMs] and calls [tick]. Every decision reads `clock.nowMs()` at tick time, so a
 * late tick never shortens the 400 ms quiet window or the 1000 ms tail, and an early tick in the
 * tail is a no-op. There is deliberately NO overall drain timeout (`2ccee40`), and never a restore
 * on raw-mic VAD (memory `feedback_dont_shortcut_echo_ducking.md`).
 *
 * Log lines are the old ones verbatim (inv04 §10.3 markers). Thread-safe: every entry point is
 * serialised on this instance, because the capture loop, the speaker-chunk path and the tick
 * driver run on different threads.
 */
class DrainEchoDucker(
    private val clock: MonotonicClock,
    private val playback: PlaybackClock,
    private val log: VoiceLog,
    private val tag: String = TAG,
) : EchoDucker {

    private sealed interface Restore {
        /** Polling the speaker until it drains. [lastPollMs] is null before the first poll. */
        class Draining(
            val reason: String,
            var lastWritten: Long,
            var lastWrittenAtMs: Long,
            var lastHead: Long,
            var lastHeadAtMs: Long,
            var lastPollMs: Long? = null,
            var pollCount: Int = 0,
            var writesQuietLogged: Boolean = false,
        ) : Restore

        /** Drained at [drainedAtMs]; restore once the tail has elapsed. */
        class Tail(val reason: String, val drainedAtMs: Long) : Restore
    }

    private val lock = Any()
    private var micGainLevel: Float = AudioTuning.DEFAULT_MIC_GAIN
    private var echoDuckingGain: Float = AudioTuning.DEFAULT_ECHO_DUCKING_GAIN
    private var gainBeforeSpeaking: Float? = null
    private var restore: Restore? = null

    override val currentMicGain: Float get() = synchronized(lock) { micGainLevel }
    override val savedGain: Float? get() = synchronized(lock) { gainBeforeSpeaking }
    override val isDucked: Boolean get() = synchronized(lock) { gainBeforeSpeaking != null }
    override val isRestorePending: Boolean get() = synchronized(lock) { restore != null }

    override fun effectiveMicGain(): Float = synchronized(lock) { gainBeforeSpeaking ?: micGainLevel }

    override fun setMicGain(level: Float): Unit = synchronized(lock) {
        val clamped = level.coerceIn(AudioTuning.MIC_GAIN_MIN, AudioTuning.MIC_GAIN_MAX)
        if (gainBeforeSpeaking != null) {
            gainBeforeSpeaking = clamped
            log.d(tag, "Mic gain set to: $clamped (deferred — applies on restore)")
        } else {
            micGainLevel = clamped
            log.d(tag, "Mic gain set to: $clamped")
        }
    }

    override fun setEchoDuckingGain(gain: Float): Unit = synchronized(lock) {
        val clamped = gain.coerceIn(AudioTuning.ECHO_DUCKING_GAIN_MIN, AudioTuning.ECHO_DUCKING_GAIN_MAX)
        echoDuckingGain = clamped
        if (gainBeforeSpeaking != null) {
            micGainLevel = clamped
            log.d(tag, "Echo ducking gain set to: $clamped (applied immediately, ducking active)")
        } else {
            log.d(tag, "Echo ducking gain set to: $clamped")
        }
    }

    override fun duck(): Unit = synchronized(lock) {
        if (gainBeforeSpeaking != null) return // already ducked
        gainBeforeSpeaking = micGainLevel
        micGainLevel = echoDuckingGain
        restore = null
        log.i(tag, "[MIC_STATE] DUCK → gain: ${gainBeforeSpeaking}→$echoDuckingGain")
    }

    override fun cancelPendingRestore(): Unit = synchronized(lock) { restore = null }

    override fun restoreImmediately(reason: String): Unit = synchronized(lock) { restoreLocked(reason) }

    private fun restoreLocked(reason: String) {
        restore = null
        val saved = gainBeforeSpeaking ?: return
        micGainLevel = saved
        gainBeforeSpeaking = null
        log.i(tag, "[MIC_STATE] RESTORE_IMMEDIATE($reason) → gain: $echoDuckingGain→$micGainLevel")
    }

    override fun scheduleRestore(reason: String): Unit = synchronized(lock) {
        if (gainBeforeSpeaking == null) return
        val startedHead = unsigned(playback.headPositionFrames() ?: 0L)
        val written = playback.totalFramesWritten()
        log.i(tag, "[MIC_STATE] RESTORE_DRAIN($reason) waiting; written=$written head=$startedHead")
        val now = clock.nowMs()
        restore = Restore.Draining(
            reason = reason,
            lastWritten = written,
            lastWrittenAtMs = now,
            lastHead = startedHead,
            lastHeadAtMs = now,
        )
    }

    override val nextTickDelayMs: Long?
        get() = synchronized(lock) {
            when (val r = restore) {
                null -> null
                is Restore.Draining -> r.lastPollMs?.let { (it + AudioTuning.MIC_RESTORE_DRAIN_POLL_MS - clock.nowMs()).coerceAtLeast(0L) } ?: 0L
                is Restore.Tail -> (r.drainedAtMs + AudioTuning.MIC_RESTORE_TAIL_MS - clock.nowMs()).coerceAtLeast(0L)
            }
        }

    override fun tick(): Unit = synchronized(lock) {
        when (val r = restore) {
            null -> Unit
            is Restore.Draining -> poll(r)
            is Restore.Tail ->
                if (clock.nowMs() - r.drainedAtMs >= AudioTuning.MIC_RESTORE_TAIL_MS) restoreLocked("drained:${r.reason}")
        }
    }

    /** One iteration of the old drain loop (`EchoDuckController.kt:200-265`). */
    private fun poll(d: Restore.Draining) {
        val nowMs = clock.nowMs()
        d.lastPollMs = nowMs
        val maybeHead = playback.headPositionFrames()
        if (maybeHead == null) {
            log.d(tag, "[MIC_STATE] RESTORE_DRAIN(${d.reason}) AudioTrack gone; restoring")
            restoreLocked("drained:${d.reason}")
            return
        }
        val written = playback.totalFramesWritten()
        val head = unsigned(maybeHead)
        d.pollCount++

        if (written != d.lastWritten) {
            d.lastWritten = written
            d.lastWrittenAtMs = nowMs
        }
        if (head != d.lastHead) {
            d.lastHead = head
            d.lastHeadAtMs = nowMs
        }

        if (d.pollCount % AudioTuning.MIC_RESTORE_DRAIN_LOG_EVERY_POLLS == 0) {
            log.d(tag, "[MIC_STATE] RESTORE_DRAIN(${d.reason}) poll=${d.pollCount} head=$head written=$written remaining=${written - head} writesQuiet=${nowMs - d.lastWrittenAtMs}ms")
        }

        val writesAreQuiet = nowMs - d.lastWrittenAtMs >= AudioTuning.MIC_RESTORE_WRITES_QUIET_MS
        if (writesAreQuiet && !d.writesQuietLogged) {
            d.writesQuietLogged = true
            log.d(tag, "[MIC_STATE] RESTORE_DRAIN(${d.reason}) writes quiet at written=$written head=$head remaining=${written - head}")
        }

        // (a) writes quiet AND (b) primary: the DAC has played everything.
        if (writesAreQuiet && head >= written) {
            log.i(tag, "[MIC_STATE] RESTORE_DRAIN(${d.reason}) AudioTrack drained at head=$head poll=${d.pollCount}; tail wait ${AudioTuning.MIC_RESTORE_TAIL_MS}ms")
            restore = Restore.Tail(d.reason, nowMs)
            return
        }
        // (a) AND (b) fallback: Lollipop post-underrun head freeze.
        if (writesAreQuiet && nowMs - d.lastHeadAtMs >= AudioTuning.MIC_RESTORE_WRITES_QUIET_MS) {
            log.i(tag, "[MIC_STATE] RESTORE_DRAIN(${d.reason}) head stuck at $head (written=$written) AND writes quiet; treating as drained; tail wait ${AudioTuning.MIC_RESTORE_TAIL_MS}ms")
            restore = Restore.Tail(d.reason, nowMs)
        }
    }

    override fun resetForNewSession(): Unit = synchronized(lock) {
        // Verbatim old behaviour: forgets the saved gain without touching the applied one.
        gainBeforeSpeaking = null
        restore = null
    }

    override fun cleanup(): Unit = synchronized(lock) {
        restore = null
        gainBeforeSpeaking?.let { saved ->
            micGainLevel = saved
            gainBeforeSpeaking = null
        }
    }

    private companion object {
        const val TAG = "EchoDuck"

        /** `getPlaybackHeadPosition()` is a signed 32-bit counter; read it as unsigned (inv04 §4.8). */
        fun unsigned(head: Long): Long = head and 0xFFFFFFFFL
    }
}
