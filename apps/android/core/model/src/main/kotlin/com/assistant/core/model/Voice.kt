package com.assistant.core.model

/**
 * Voice target (`voice_start` fields / `assistant_config.json` `default_voice_*`, inv01 §7.1).
 * `null` fields are omitted on the wire so the server applies its defaults (V-1, V-2).
 */
data class VoiceConfig(
    val provider: String? = null,
    val model: String? = null,
    val voice: String? = null,
    /** `""` = auto-detect; `null` = server default. */
    val transcriptionLanguage: String? = null,
    /** Google only: `vertex` | `aistudio`. */
    val endpoint: String? = null,
) {
    companion object {
        /** Client fallback when the config cannot be read (inv04 §2.1: openai / gpt-realtime / cedar). */
        val DEFAULT = VoiceConfig(provider = "openai", model = "gpt-realtime", voice = "cedar")
    }
}

enum class VoiceConnectionType(val wire: String) {
    WEBRTC("webrtc"),
    WEBSOCKET("websocket");

    companion object {
        fun fromWire(value: String?): VoiceConnectionType? = entries.firstOrNull { it.wire == value }
    }
}

/** `{sample_rate, encoding}`. The encoding labels are inconsistent; always PCM16 LE mono (inv01 §7.3). */
data class AudioFormat(val sampleRate: Int, val encoding: String?) {
    companion object {
        val DEFAULT_24K = AudioFormat(24_000, "pcm16")
    }
}

/** `ConnectionInfo` (inv01 §7.2). */
data class ConnectionInfo(
    val connectionType: VoiceConnectionType?,
    val endpoint: String?,
    val ephemeralToken: String?,
    val expiresAt: Long?,
    val audioIn: AudioFormat,
    val audioOut: AudioFormat,
    val model: String?,
    val voice: String?,
    /** `"backend"` for WS providers whose audio is relayed by the backend. */
    val audioRelay: String?,
)

/** User-selected playback route (inv04 §3.4). */
enum class AudioOutput { AUTO, EARPIECE, LOUDSPEAKER, WIRED, BLUETOOTH }
