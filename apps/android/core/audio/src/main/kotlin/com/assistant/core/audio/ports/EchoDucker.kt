package com.assistant.core.audio.ports

/*
 * Echo ducking for the WebSocket PCM providers (inv04 §3.3, §4.8). Interface-only (A-04).
 *
 * Non-negotiable (memory `feedback_dont_shortcut_echo_ducking.md`): the mic is restored only
 * after the speaker has DRAINED, then a tail wait. Never restore early on raw-mic VAD.
 */

/**
 * Read side of the speaker, used by the drain-restore loop.
 *
 * [headPositionFrames] is `AudioTrack.getPlaybackHeadPosition()` widened to Long WITHOUT masking
 * (it is a signed 32-bit counter that goes negative after 2^31 frames); `null` when the track is
 * gone. The [EchoDucker] reads it as unsigned (`and 0xFFFFFFFF`, inv04 §4.8).
 * [totalFramesWritten] is the writer's cumulative frame counter; a flush resets it to 0.
 */
interface PlaybackClock {
    fun headPositionFrames(): Long?
    fun totalFramesWritten(): Long
}

/**
 * Pure drain-then-restore FSM (inv04 §3.3 WS table). It owns the capture gain.
 *
 * Timing is driven by the owner, not by coroutines inside the ducker (`:core:audio` stays pure):
 * whenever [nextTickDelayMs] is non-null the owner waits that long and calls [tick]. Every
 * decision uses `clock.nowMs()` at tick time, so a late tick never shortens the 400 ms quiet
 * window or the 1000 ms tail. Contract for the drain loop (old `EchoDuckController.scheduleRestore`):
 *  - right after [scheduleRestore] the first poll is due immediately (`nextTickDelayMs == 0`);
 *  - polls are due every `MIC_RESTORE_DRAIN_POLL_MS` (80) while draining;
 *  - "drained" = writes unchanged for ≥ `MIC_RESTORE_WRITES_QUIET_MS` (400) AND
 *    (head ≥ written OR head unchanged for ≥ 400 ms — the Lollipop post-underrun head freeze);
 *  - once drained, the next tick is due after `MIC_RESTORE_TAIL_MS` (1000) and restores with
 *    reason `drained:<reason>`;
 *  - head == null (track gone) restores on that tick;
 *  - there is NO overall drain timeout (`2ccee40`).
 *
 * Log markers (exact prefixes, inv04 §10.3): `[MIC_STATE] DUCK`, `[MIC_STATE] RESTORE_DRAIN(<reason>)`,
 * `[MIC_STATE] RESTORE_IMMEDIATE(<reason>)`, and the drained line containing `AudioTrack drained at head=`
 * or `head stuck at <head> (written=<written>)`.
 */
interface EchoDucker {
    /** Gain applied to each captured chunk right now (duck gain while ducked). */
    val currentMicGain: Float

    /** The user gain a restore returns to; null when not ducked. */
    val savedGain: Float?
    val isDucked: Boolean
    val isRestorePending: Boolean

    /** What the settings UI shows: the saved gain while ducked, else the current gain. */
    fun effectiveMicGain(): Float

    /** Clamped to [0, 2]. While ducked it updates only the restore target. */
    fun setMicGain(level: Float)

    /** Clamped to [0, 1]. While ducked it applies immediately. */
    fun setEchoDuckingGain(gain: Float)

    /** Idempotent rising edge: saves the gain, applies the duck gain, cancels a pending restore. */
    fun duck()

    /** A new speaker chunk arrived while draining: the staleness signal was false. */
    fun cancelPendingRestore()

    /** Restore now (barge-in flush, or the end of a drain). No-op when not ducked. */
    fun restoreImmediately(reason: String)

    /** Start the drain-then-restore. No-op when not ducked. */
    fun scheduleRestore(reason: String)

    /** Delay until [tick] is due, or null when no restore is pending. */
    val nextTickDelayMs: Long?

    /** One step of the drain loop (see the class contract). */
    fun tick()

    /** Before a new session: forget the saved gain, cancel any restore. */
    fun resetForNewSession()

    /** Session teardown: cancel any restore and return to the saved gain. */
    fun cleanup()
}
