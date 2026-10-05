package com.assistant.core.wakeword.policy

import com.assistant.core.audio.pcm.Pcm
import com.assistant.core.wakeword.WakeTuning
import com.assistant.core.wakeword.ports.ClipPolicy
import com.assistant.core.wakeword.ports.CycleOutcome
import com.assistant.core.wakeword.ports.NoiseFloorTracker
import com.assistant.core.wakeword.ports.RearmPolicy
import com.assistant.core.wakeword.ports.RmsGate
import com.assistant.core.wakeword.ports.TalkVad
import com.assistant.core.wakeword.ports.TalkVadStep

/*
 * Pure wake-loop policies (inv04 §3.1, §4.1, §4.2). No clock, no thread, no Android: the loop feeds
 * them readings and timestamps.
 */

/**
 * Stage-1 activity gate (old `startSilenceMonitor` read loop): a read at/above
 * `RMS_THRESHOLD / wakeGain` starts the hold timer; a later loud read at least [WakeTuning.ACTIVITY_HOLD_MS]
 * after it starts recognition; any quieter read resets the timer.
 *
 * The gain is taken at its decimal value (`1.3f` → 1.3). The old code promoted the Float directly
 * (`70.0 / 1.2999999523f`), which differs by < 2e-6 RMS units; the parity test pins `70.0 / 1.3`.
 */
class ActivityGate(wakeGain: Float) : RmsGate {
    override val effectiveThreshold: Double =
        if (wakeGain > 0f) WakeTuning.RMS_THRESHOLD / wakeGain.toString().toDouble() else WakeTuning.RMS_THRESHOLD

    private var activityStartMs: Long? = null

    override fun onFrame(rms: Double, nowMs: Long): Boolean {
        if (rms < effectiveThreshold) {
            activityStartMs = null
            return false
        }
        val start = activityStartMs
        if (start == null) {
            activityStartMs = nowMs
            return false
        }
        return nowMs - start >= WakeTuning.ACTIVITY_HOLD_MS
    }

    override fun reset() {
        activityStartMs = null
    }
}

/**
 * Asymmetric ambient-floor tracker of the talk-command VAD (old `NoiseFloorTracker`): adapts DOWN
 * fast ([WakeTuning.SILENCE_FLOOR_ATTACK]) and UP slowly ([WakeTuning.SILENCE_FLOOR_RELEASE]) so a
 * loud command word cannot inflate the floor and swallow the following silence. A negative seed
 * means unseeded: the first reading becomes the floor.
 */
class AmbientFloor(seed: Double) : NoiseFloorTracker {
    override var floor: Double = seed
        private set

    override fun update(rms: Double): Double {
        floor = when {
            floor < 0.0 -> rms
            rms < floor -> rms * WakeTuning.SILENCE_FLOOR_ATTACK + floor * (1 - WakeTuning.SILENCE_FLOOR_ATTACK)
            else -> rms * WakeTuning.SILENCE_FLOOR_RELEASE + floor * (1 - WakeTuning.SILENCE_FLOOR_RELEASE)
        }
        return floor
    }
}

/** `max(floor × K, ADAPTIVE_VOICE_FLOOR_MIN)` (old `adaptiveVoiceThreshold`). */
fun adaptiveVoiceThreshold(noiseFloor: Double, sensitivity: Double): Double =
    (noiseFloor * sensitivity).coerceAtLeast(WakeTuning.ADAPTIVE_VOICE_FLOOR_MIN)

/** The user's K (`talkSilenceSensitivity`), or [WakeTuning.DEFAULT_TALK_SILENCE_SENSITIVITY] when ≤ 0. */
fun effectiveSensitivity(sensitivity: Float): Double =
    sensitivity.toDouble().takeIf { it > 0.0 } ?: WakeTuning.DEFAULT_TALK_SILENCE_SENSITIVITY

/**
 * The talk-command end-of-utterance VAD, frame for frame as the old `captureTalkCommand` (see the
 * [TalkVad] port for the step order). [limitAt] exposes steps 1–2 alone so the loop can stop BEFORE
 * reading another frame, exactly as the old loop checked the limits at the top of each iteration.
 */
class AdaptiveTalkVad(
    private val startedAtMs: Long,
    seedFloor: Double,
    sensitivity: Float,
) : TalkVad {
    val sensitivity: Double = effectiveSensitivity(sensitivity)
    private val tracker = AmbientFloor(seedFloor)
    private var previousFrameMs = startedAtMs

    var lastVoiceMs: Long = startedAtMs
        private set
    var sustainedVoiceMs: Long = 0L
        private set
    var lastThreshold: Double = WakeTuning.ADAPTIVE_VOICE_FLOOR_MIN
        private set
    val floor: Double get() = tracker.floor

    override var onsetFired: Boolean = false
        private set

    /** Steps 1–2: END_MAX at the hard cap, ABORT_NO_ONSET at the onset timeout; null to keep going. */
    fun limitAt(nowMs: Long): TalkVadStep? = when {
        nowMs - startedAtMs >= WakeTuning.COMMAND_MAX_MS -> TalkVadStep.END_MAX
        !onsetFired && nowMs - startedAtMs >= WakeTuning.COMMAND_SPEECH_ONSET_TIMEOUT_MS -> TalkVadStep.ABORT_NO_ONSET
        else -> null
    }

    override fun onFrame(rms: Double, nowMs: Long): TalkVadStep {
        limitAt(nowMs)?.let { return it }
        val frameDtMs = nowMs - previousFrameMs
        previousFrameMs = nowMs
        val threshold = adaptiveVoiceThreshold(tracker.update(rms), sensitivity)
        lastThreshold = threshold
        if (rms >= threshold) {
            sustainedVoiceMs += frameDtMs
            lastVoiceMs = nowMs
        } else {
            sustainedVoiceMs = 0L
        }
        if (!onsetFired && sustainedVoiceMs >= WakeTuning.ONSET_SUSTAIN_MS) {
            onsetFired = true
            lastVoiceMs = nowMs
            return TalkVadStep.ONSET
        }
        if (onsetFired && nowMs - lastVoiceMs >= WakeTuning.COMMAND_SILENCE_MS) return TalkVadStep.END_SILENCE
        return TalkVadStep.CONTINUE
    }
}

/**
 * Re-arm delays (old `runRecognitionCycle` / `runVoskRecognitionInline` `restartDelay`). A Vosk
 * NoMatch is the normal idle outcome and never accrues backoff (`043340a`, RS-43); only the
 * SpeechRecognizer misses / errors back off.
 */
class WakeRearmPolicy : RearmPolicy {
    override var consecutiveMisses: Int = 0
        private set

    override fun delayAfter(outcome: CycleOutcome): Long? = when (outcome) {
        CycleOutcome.MATCHED -> {
            consecutiveMisses = 0
            WakeTuning.POST_WAKEWORD_DELAY_MS
        }
        CycleOutcome.VOSK_NO_MATCH -> {
            consecutiveMisses = 0
            WakeTuning.POST_VOSK_NOMATCH_MS
        }
        CycleOutcome.NO_SPEECH, CycleOutcome.ERROR_FLAT -> WakeTuning.SR_CLIENT_ERROR_DELAY_MS
        CycleOutcome.SR_NO_MATCH, CycleOutcome.ERROR -> backoff().also { consecutiveMisses++ }
        CycleOutcome.CANCELLED -> null
    }

    /**
     * `min(1000 shl misses, 30000)`. The shift is bounded so a very long miss run cannot overflow
     * the Long (the old code's `1000L shl 54` went negative); every reachable value is unchanged.
     */
    private fun backoff(): Long {
        val shift = consecutiveMisses.coerceAtMost(MAX_SHIFT)
        return (WakeTuning.POST_RECOGNITION_BASE_MS shl shift).coerceAtMost(WakeTuning.POST_RECOGNITION_MAX_MS)
    }

    override fun reset() {
        consecutiveMisses = 0
    }

    private companion object {
        const val MAX_SHIFT = 16
    }
}

/** Clip helpers (old `computePreBufferCapacity`, `trimToTrailingWindow`, `clipHasSpeech`, seed floor). */
object Clips : ClipPolicy {
    override fun monitorBufferBytes(minBufferBytes: Int): Int = maxOf(minBufferBytes, WakeTuning.MONITOR_BUFFER_MIN_BYTES)

    /** Rounds DOWN, as the old code (errata #3). */
    override fun preBufferCapacity(readSamples: Int): Int {
        val targetSamples = WakeTuning.SAMPLE_RATE_HZ * WakeTuning.PRE_BUFFER_MS / 1000
        return (targetSamples / readSamples).coerceAtLeast(1)
    }

    override fun trimToTrailingWindow(frames: List<ShortArray>, windowMs: Long, sampleRateHz: Int): List<ShortArray> {
        val maxSamples = (sampleRateHz.toLong() * windowMs / 1000).toInt()
        var running = 0
        var startIdx = frames.size
        for (i in frames.indices.reversed()) {
            running += frames[i].size
            startIdx = i
            if (running >= maxSamples) break
        }
        return if (startIdx == 0) frames.toList() else frames.subList(startIdx, frames.size).toList()
    }

    /** Loudest per-frame RMS of a clip (0 when empty). */
    fun peakFrameRms(frames: List<ShortArray>): Double = frames.maxOfOrNull { Pcm.rms(it, it.size) } ?: 0.0

    override fun hasSpeech(frames: List<ShortArray>): Boolean =
        frames.isNotEmpty() && peakFrameRms(frames) >= WakeTuning.SPEECH_FLOOR_RMS

    override fun seedFloor(preRoll: List<ShortArray>): Double =
        preRoll.map { Pcm.rms(it, it.size) }.filter { it > 0.0 }.minOrNull() ?: -1.0
}

/** The rolling ≈500 ms ring of monitor reads replayed into Vosk before the live stream. */
class PreBuffer(private val capacity: Int) {
    private val frames = ArrayDeque<ShortArray>(capacity + 1)

    fun add(frame: ShortArray) {
        frames.addLast(frame)
        while (frames.size > capacity) frames.removeFirst()
    }

    fun snapshot(): List<ShortArray> = frames.toList()
}
