package com.assistant.peripheral

import com.assistant.core.voice.ports.TranscriptSink
import com.assistant.core.voicehost.LastExchange
import com.assistant.peripheral.face.PlainText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The lite [TranscriptSink] (spec 14 §5.2, §5.4): keeps only the last exchange, as plain text
 * (markdown stripped), each side capped at [LastExchange.MAX_CHARS]. No conversation reducer on
 * the A300M (no `:core:conversation`).
 */
class LastExchangeSink(private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) : TranscriptSink {
    private val _exchange = MutableStateFlow(LastExchange())
    val exchange: StateFlow<LastExchange> = _exchange.asStateFlow()

    /**
     * The last voice failure. The voice core reports a fatal error as a toast + a system line and
     * finalizes straight to OFF (RS-09), so without this the face would never show it (spec 14
     * §5.2: "Error + the message (shown, fixes inv03 §8 bug 7)").
     */
    data class VoiceError(val message: String, val atMs: Long)

    private val _error = MutableStateFlow<VoiceError?>(null)
    val error: StateFlow<VoiceError?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    override fun userTranscript(text: String, final: Boolean) {
        if (!final) return
        val plain = PlainText.strip(text, LastExchange.MAX_CHARS)
        if (plain.isEmpty()) return
        // A new user turn starts a new exchange.
        _exchange.value = LastExchange(user = plain, assistant = null)
    }

    override fun assistantTranscript(text: String, final: Boolean) {
        if (!final) return
        val plain = PlainText.strip(text, LastExchange.MAX_CHARS)
        if (plain.isEmpty()) return
        _exchange.update { it.copy(assistant = plain) }
    }

    override fun system(text: String) {
        if (text.startsWith(VOICE_ERROR_PREFIX)) _error.value = VoiceError(text.removePrefix(VOICE_ERROR_PREFIX).trim(), clock())
    }

    /** A confirmed talk capture went out as `send_audio` (the reply arrives in the conversation, not here). */
    override fun voiceMessageSent() {
        _exchange.value = LastExchange(user = VOICE_MESSAGE, assistant = null)
    }

    override fun turnComplete() = Unit
    override fun voiceEnded() = Unit

    companion object {
        const val VOICE_MESSAGE = "(voice message sent)"
        const val VOICE_ERROR_PREFIX = "Voice error:"

        /** How long the Error face stays after a failure before falling back to Ready. */
        const val ERROR_HOLD_MS = 10_000L
    }
}
