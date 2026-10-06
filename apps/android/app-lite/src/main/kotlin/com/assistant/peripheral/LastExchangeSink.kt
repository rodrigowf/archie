package com.assistant.peripheral

import com.assistant.core.voice.ports.TranscriptSink
import com.assistant.core.voicehost.LastExchange
import com.assistant.peripheral.face.PlainText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The lite [TranscriptSink] (spec 14 §5.2, §5.4): keeps only the last exchange, as plain text
 * (markdown stripped), each side capped at [LastExchange.MAX_CHARS]. No conversation reducer on
 * the A300M (no `:core:conversation`).
 *
 * Two writers: the voice core (transcripts of this device's voice session, [voiceMessageSent]) and
 * [ConversationExchangeFeed] (the orchestrator's text turns, other devices' prompts, history on
 * attach). Every write bumps a version, so a history page fetched before a live write is dropped
 * ([applyHistory]).
 */
class LastExchangeSink(private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) : TranscriptSink {
    private val _exchange = MutableStateFlow(LastExchange())
    val exchange: StateFlow<LastExchange> = _exchange.asStateFlow()
    private var version = 0L

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
        showUser(text)
    }

    override fun assistantTranscript(text: String, final: Boolean) {
        if (!final) return
        val plain = PlainText.strip(text, LastExchange.MAX_CHARS)
        if (plain.isEmpty()) return
        write { it.copy(assistant = plain) }
    }

    override fun system(text: String) {
        if (text.startsWith(VOICE_ERROR_PREFIX)) _error.value = VoiceError(text.removePrefix(VOICE_ERROR_PREFIX).trim(), clock())
    }

    /** A confirmed talk capture went out as `send_audio` (the reply arrives in the conversation, not here). */
    override fun voiceMessageSent() {
        write { LastExchange(user = VOICE_MESSAGE, assistant = null) }
    }

    override fun turnComplete() = Unit
    override fun voiceEnded() = Unit

    /** A new user turn (markdown allowed) starts a new exchange. Blank text is ignored. */
    fun showUser(text: String) {
        val plain = PlainText.strip(text, LastExchange.MAX_CHARS)
        if (plain.isEmpty()) return
        write { LastExchange(user = plain, assistant = null) }
    }

    /**
     * Assistant text of an orchestrator turn. [firstOfTurn] with an exchange that already has its
     * reply (no prompt since, e.g. a background wake turn) starts an exchange of its own, so the
     * reply is never paired with an older prompt; otherwise it fills / replaces the Archie line.
     */
    fun showAssistant(text: String, firstOfTurn: Boolean) {
        val plain = PlainText.strip(text, LastExchange.MAX_CHARS)
        if (plain.isEmpty()) return
        write { if (firstOfTurn && it.assistant != null) LastExchange(user = null, assistant = plain) else it.copy(assistant = plain) }
    }

    /** The conversation changed or closed: nothing to show. */
    fun clear() = write { LastExchange() }

    /** The current write version, taken before a history fetch starts. */
    @Synchronized fun version(): Long = version

    /**
     * Applies a history exchange (raw text: stripped and capped here) only if nothing was written
     * since [ifVersion]: a live write always wins over a late history page.
     */
    @Synchronized fun applyHistory(e: LastExchange, ifVersion: Long): Boolean {
        if (version != ifVersion) return false
        val user = e.user?.let { PlainText.strip(it, LastExchange.MAX_CHARS) }?.takeIf { it.isNotEmpty() }
        val assistant = e.assistant?.let { PlainText.strip(it, LastExchange.MAX_CHARS) }?.takeIf { it.isNotEmpty() }
        if (user == null && assistant == null) return false
        version++
        _exchange.value = LastExchange(user, assistant)
        return true
    }

    @Synchronized private fun write(f: (LastExchange) -> LastExchange) {
        version++
        _exchange.value = f(_exchange.value)
    }

    companion object {
        const val VOICE_MESSAGE = "(voice message sent)"

        /** Another device's voice message (VM-1), live or from history. */
        const val REMOTE_VOICE_MESSAGE = "(voice message)"
        const val VOICE_ERROR_PREFIX = "Voice error:"

        /** How long the Error face stays after a failure before falling back to Ready. */
        const val ERROR_HOLD_MS = 10_000L
    }
}
