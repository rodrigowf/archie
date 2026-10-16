package com.assistant.archie.tv.voice

/** What the remote microphone produced (spec 15 §4.1). Frames are 16 kHz mono PCM16. */
sealed interface MicBurst {
    /** The first frame of a burst arrived (the mic button went down). */
    data class Start(val atMs: Long) : MicBurst

    /** [level] = RMS of [pcm] in 0..1 (dBFS mapped −60…0). */
    class Frame(val pcm: ShortArray, val level: Float) : MicBurst

    /**
     * The burst is over: no frame for [TvVoiceTuning.BURST_END_GAP_MS] (released), or the
     * [TvVoiceTuning.MAX_BURST_MS] cap was reached ([capped]). [tooShort] bursts are accidental taps.
     */
    data class End(val durationMs: Long, val tooShort: Boolean, val capped: Boolean = false) : MicBurst
}

/**
 * Pure burst detection over a monotonic clock (spec 15 §2: frames arrive only while the button is
 * held). Not thread-safe: [RemoteMicSource] calls it under its lock.
 *
 * - Start on the first frame; End after [endGapMs] without a frame, measured by [onTick] (the
 *   reader blocks while the button is up) or by a late frame that finds the gap already exceeded.
 * - Duration is audio time (samples), so a slow watchdog never stretches it.
 * - At [maxMs] of audio the burst ends (capped) and later frames are ignored until a gap.
 */
class BurstDetector(
    private val endGapMs: Long = TvVoiceTuning.BURST_END_GAP_MS,
    private val minMs: Long = TvVoiceTuning.MIN_BURST_MS,
    private val maxMs: Long = TvVoiceTuning.MAX_BURST_MS,
    private val sampleRateHz: Int = TvVoiceTuning.SAMPLE_RATE_HZ,
) {
    private enum class Phase { IDLE, ACTIVE, CAPPED }

    private var phase = Phase.IDLE
    private var lastFrameAt = 0L
    private var samples = 0L

    val active: Boolean get() = phase == Phase.ACTIVE

    fun onFrame(nowMs: Long, pcm: ShortArray, emit: (MicBurst) -> Unit) {
        if (phase != Phase.IDLE && nowMs - lastFrameAt >= endGapMs) closeBurst(emit)
        when (phase) {
            Phase.IDLE -> {
                phase = Phase.ACTIVE
                samples = 0
                lastFrameAt = nowMs
                emit(MicBurst.Start(nowMs))
                addFrame(pcm, emit)
            }
            Phase.ACTIVE -> {
                lastFrameAt = nowMs
                addFrame(pcm, emit)
            }
            Phase.CAPPED -> lastFrameAt = nowMs
        }
    }

    fun onTick(nowMs: Long, emit: (MicBurst) -> Unit) {
        if (phase != Phase.IDLE && nowMs - lastFrameAt >= endGapMs) closeBurst(emit)
    }

    /** The source stopped (app in the background): a burst in progress ends now. */
    fun flush(emit: (MicBurst) -> Unit) {
        if (phase != Phase.IDLE) closeBurst(emit)
    }

    private fun addFrame(pcm: ShortArray, emit: (MicBurst) -> Unit) {
        val room = maxSamples() - samples
        val take = minOf(room, pcm.size.toLong()).toInt()
        if (take > 0) {
            val frame = if (take == pcm.size) pcm else pcm.copyOf(take)
            samples += take
            emit(MicBurst.Frame(frame, level(frame)))
        }
        if (samples >= maxSamples()) {
            emit(MicBurst.End(durationMs(), tooShort = false, capped = true))
            phase = Phase.CAPPED
        }
    }

    private fun closeBurst(emit: (MicBurst) -> Unit) {
        if (phase == Phase.ACTIVE) {
            val d = durationMs()
            emit(MicBurst.End(d, tooShort = d < minMs))
        }
        phase = Phase.IDLE
    }

    private fun maxSamples() = maxMs * sampleRateHz / 1000
    private fun durationMs() = samples * 1000 / sampleRateHz

    companion object {
        /** RMS of [pcm] as a 0..1 meter value (−60 dBFS → 0, 0 dBFS → 1). */
        fun level(pcm: ShortArray): Float {
            if (pcm.isEmpty()) return 0f
            var sum = 0.0
            for (s in pcm) sum += s.toDouble() * s
            val rms = kotlin.math.sqrt(sum / pcm.size) / 32768.0
            if (rms <= 0.0) return 0f
            val db = 20 * kotlin.math.log10(rms)
            return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
        }
    }
}
