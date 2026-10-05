package com.assistant.core.audio.pcm

import com.assistant.core.audio.ports.PcmMath
import kotlin.math.sqrt

/**
 * Pure mono-PCM16 utilities (old `MicCapture.computeRmsAndPeakPcm16` / `applyGainPcm16`,
 * `WakeWordDetector.computeRms`, `audio/WavUtils`). No Android types, so the wake loop, the WS mic
 * path and the JVM tests share one implementation.
 */
object Pcm : PcmMath {

    override fun rms(samples: ShortArray, count: Int): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / count)
    }

    override fun rmsAndPeakPcm16Le(bytes: ByteArray, offset: Int, length: Int): Pair<Double, Int> {
        var i = offset
        val end = offset + length
        var sumSq = 0.0
        var peak = 0
        var n = 0
        while (i < end - 1) {
            val sample = sampleAt(bytes, i)
            sumSq += (sample * sample).toDouble()
            val abs = if (sample < 0) -sample else sample
            if (abs > peak) peak = abs
            n++
            i += 2
        }
        val rms = if (n > 0) sqrt(sumSq / n) else 0.0
        return Pair(rms, peak)
    }

    override fun applyGainPcm16Le(bytes: ByteArray, offset: Int, length: Int, gain: Float) {
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val sample = sampleAt(bytes, i)
            val amplified = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[i] = (amplified and 0xff).toByte()
            bytes[i + 1] = ((amplified shr 8) and 0xff).toByte()
            i += 2
        }
    }

    override fun wav(frames: List<ShortArray>, sampleRateHz: Int): ByteArray {
        val samples = frames.sumOf { it.size }
        val pcm = ByteArray(samples * 2)
        var o = 0
        for (frame in frames) {
            for (s in frame) {
                pcm[o++] = (s.toInt() and 0xff).toByte()
                pcm[o++] = ((s.toInt() shr 8) and 0xff).toByte()
            }
        }
        return pcmToWav(pcm, sampleRateHz)
    }

    /** Wraps little-endian PCM16 mono bytes in the standard 44-byte RIFF/WAVE header. */
    fun pcmToWav(pcmData: ByteArray, sampleRateHz: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRateHz * channels * bitsPerSample / 8
        val out = ByteArray(WAV_HEADER_BYTES + pcmData.size)
        fun ascii(at: Int, s: String) = s.forEachIndexed { k, c -> out[at + k] = c.code.toByte() }
        fun le32(at: Int, v: Int) { for (k in 0 until 4) out[at + k] = ((v shr (8 * k)) and 0xff).toByte() }
        fun le16(at: Int, v: Int) { for (k in 0 until 2) out[at + k] = ((v shr (8 * k)) and 0xff).toByte() }
        ascii(0, "RIFF")
        le32(4, pcmData.size + 36)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16) // PCM fmt chunk size
        le16(20, 1) // PCM
        le16(22, channels)
        le32(24, sampleRateHz)
        le32(28, byteRate)
        le16(32, channels * bitsPerSample / 8)
        le16(34, bitsPerSample)
        ascii(36, "data")
        le32(40, pcmData.size)
        pcmData.copyInto(out, WAV_HEADER_BYTES)
        return out
    }

    /** RFC 4648 base64 with padding and no line wraps (`android.util.Base64.NO_WRAP`). API-21 safe. */
    override fun base64(bytes: ByteArray): String = base64(bytes, 0, bytes.size)

    fun base64(bytes: ByteArray, offset: Int, length: Int): String {
        val out = CharArray((length + 2) / 3 * 4)
        var i = offset
        val end = offset + length
        var o = 0
        while (end - i >= 3) {
            val v = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8) or (bytes[i + 2].toInt() and 0xff)
            out[o++] = ALPHABET[(v ushr 18) and 0x3f]
            out[o++] = ALPHABET[(v ushr 12) and 0x3f]
            out[o++] = ALPHABET[(v ushr 6) and 0x3f]
            out[o++] = ALPHABET[v and 0x3f]
            i += 3
        }
        when (end - i) {
            1 -> {
                val v = (bytes[i].toInt() and 0xff) shl 16
                out[o++] = ALPHABET[(v ushr 18) and 0x3f]
                out[o++] = ALPHABET[(v ushr 12) and 0x3f]
                out[o++] = '='
                out[o++] = '='
            }
            2 -> {
                val v = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8)
                out[o++] = ALPHABET[(v ushr 18) and 0x3f]
                out[o++] = ALPHABET[(v ushr 12) and 0x3f]
                out[o++] = ALPHABET[(v ushr 6) and 0x3f]
                out[o++] = '='
            }
        }
        return String(out)
    }

    private fun sampleAt(buf: ByteArray, i: Int): Int {
        val lo = buf[i].toInt() and 0xff
        val hi = buf[i + 1].toInt()
        return ((hi shl 8) or lo).toShort().toInt()
    }

    const val WAV_HEADER_BYTES = 44
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
}
