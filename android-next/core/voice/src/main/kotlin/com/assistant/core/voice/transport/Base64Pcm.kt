package com.assistant.core.voice.transport

/**
 * RFC 4648 base64 decoding for `voice_audio_out` chunks. API-21 safe (`java.util.Base64` is API 26+)
 * and JVM-testable (`android.util.Base64` is not). Tolerates whitespace and missing padding, like
 * `android.util.Base64.decode(…, NO_WRAP)`; throws [IllegalArgumentException] on bad input.
 */
internal object Base64Pcm {
    private val DECODE = IntArray(128) { -1 }.also { t ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c -> t[c.code] = i }
        t['-'.code] = 62 // URL-safe alphabet, accepted like android.util.Base64 DEFAULT
        t['_'.code] = 63
    }

    fun decode(text: String): ByteArray {
        val out = ByteArray(text.length * 3 / 4 + 3)
        var o = 0
        var acc = 0
        var bits = 0
        for (ch in text) {
            if (ch == '=') break
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') continue
            val v = if (ch.code < 128) DECODE[ch.code] else -1
            require(v >= 0) { "bad base64 character '$ch'" }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = ((acc shr bits) and 0xff).toByte()
            }
        }
        return if (o == out.size) out else out.copyOf(o)
    }
}
