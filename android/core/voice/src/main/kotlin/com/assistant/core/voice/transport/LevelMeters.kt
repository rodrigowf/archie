package com.assistant.core.voice.transport

import com.assistant.core.voice.ports.RtcStat
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Level meters behind `VoiceTransport.levels` (the dock orb). Observation only: they read audio
 * the transports already have in hand and never touch gain, ducking or timing.
 */

private const val FULL_SCALE = 32768.0

/**
 * Mic side: the audio thread folds each buffer's RMS into a peak-hold that the level poller takes
 * and clears. One volatile float, no allocation; a lost update between offer and take is harmless.
 */
internal class MicLevel {
    @Volatile private var peak = 0f

    /** [rms] in PCM16 units (0..32768). */
    fun offer(rms: Double) {
        val v = (rms / FULL_SCALE).toFloat()
        if (v > peak) peak = v
    }

    fun take(): Float {
        val v = peak
        peak = 0f
        return v.coerceIn(0f, 1f)
    }
}

/**
 * WS speaker side: each enqueued chunk's RMS laid out on a playout timeline. Chunks arrive faster
 * than real time and queue in the player, so a chunk plays from `max(now, end of the previous)`
 * for its own duration; [levelAt] answers what is playing now. A barge-in flush or cleanup
 * [clear]s it. Fixed arrays; when full, a new chunk extends the last entry instead of allocating.
 */
internal class PlayoutLevels(private val capacity: Int = 256) {
    private val starts = LongArray(capacity)
    private val ends = LongArray(capacity)
    private val levels = FloatArray(capacity)
    private var head = 0
    private var size = 0
    private var endMs = 0L

    @Synchronized
    fun add(nowMs: Long, durationMs: Long, rms: Float) {
        if (durationMs <= 0) return
        val start = max(nowMs, endMs)
        endMs = start + durationMs
        if (size == capacity) {
            val last = (head + size - 1) % capacity
            ends[last] = endMs
            levels[last] = max(levels[last], rms)
            return
        }
        val i = (head + size) % capacity
        starts[i] = start
        ends[i] = endMs
        levels[i] = rms
        size++
    }

    @Synchronized
    fun levelAt(nowMs: Long): Float {
        while (size > 0 && ends[head] <= nowMs) {
            head = (head + 1) % capacity
            size--
        }
        return if (size > 0 && starts[head] <= nowMs) levels[head] else 0f
    }

    @Synchronized
    fun clear() {
        head = 0
        size = 0
        endMs = 0L
    }

    companion object {
        /** Mono PCM16 little-endian RMS, 0..1 (no allocation). */
        fun rmsPcm16Le(bytes: ByteArray): Float {
            val n = bytes.size / 2
            if (n == 0) return 0f
            var sum = 0.0
            for (k in 0 until n) {
                val s = ((bytes[2 * k].toInt() and 0xff) or (bytes[2 * k + 1].toInt() shl 8)).toShort().toDouble()
                sum += s * s
            }
            return (sqrt(sum / n) / FULL_SCALE).toFloat()
        }
    }
}

/**
 * WebRTC speaker side. stream-webrtc-android 1.1.1 exposes no playout samples, so the level comes
 * from `getStats()`: the inbound audio RTP stream (`track` with `remoteSource` on older libwebrtc).
 * Preferred: the mean over the poll interval, `sqrt(ΔtotalAudioEnergy / ΔtotalSamplesDuration)`;
 * else the instantaneous `audioLevel`. Both are libwebrtc's peak-style level (1 = 0 dBov), scaled
 * by [PEAK_TO_RMS] so it sits on the same RMS scale as the mic.
 */
internal class StatsSpeakerLevel {
    private var lastEnergy = -1.0
    private var lastDuration = -1.0

    fun update(stats: List<RtcStat>): Float {
        val s = stats.firstOrNull { it.type == "inbound-rtp" && isInboundAudio(it) }
            ?: stats.firstOrNull(::isInboundAudio)
            ?: return 0f
        val energy = (s.members["totalAudioEnergy"] as? Number)?.toDouble()
        val duration = (s.members["totalSamplesDuration"] as? Number)?.toDouble()
        val level: Double = if (energy != null && duration != null) {
            val hadPrevious = lastDuration >= 0.0
            val dE = energy - lastEnergy
            val dD = duration - lastDuration
            lastEnergy = energy
            lastDuration = duration
            when {
                hadPrevious && dD > 0.0 && dE >= 0.0 -> sqrt(dE / dD)
                hadPrevious && dD == 0.0 -> 0.0 // nothing played out since the last poll
                else -> audioLevel(s)
            }
        } else {
            audioLevel(s)
        }
        return (level.coerceIn(0.0, 1.0) * PEAK_TO_RMS).toFloat()
    }

    private fun audioLevel(s: RtcStat) = (s.members["audioLevel"] as? Number)?.toDouble() ?: 0.0

    companion object {
        /** Speech peaks run ~3× its RMS; puts the stats level on the mic's RMS scale. */
        const val PEAK_TO_RMS = 0.3

        fun isInboundAudio(s: RtcStat): Boolean {
            val audio = s.members["kind"] == "audio" || s.members["mediaType"] == "audio"
            return when (s.type) {
                "inbound-rtp" -> audio
                "track" -> audio && s.members["remoteSource"] == true
                else -> false
            }
        }
    }
}
