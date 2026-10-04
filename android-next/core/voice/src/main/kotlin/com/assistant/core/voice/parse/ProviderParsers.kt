package com.assistant.core.voice.parse

import com.assistant.core.voice.json.EMPTY_OBJECT
import com.assistant.core.voice.json.array
import com.assistant.core.voice.json.isTrue
import com.assistant.core.voice.json.obj
import com.assistant.core.voice.json.optString
import com.assistant.core.voice.json.parseObjectOrNull
import com.assistant.core.voice.json.string
import com.assistant.core.voice.json.typeOf
import com.assistant.core.voice.ports.ConnectionType
import com.assistant.core.voice.ports.ParserKind
import com.assistant.core.voice.ports.ProviderEventParser
import com.assistant.core.voice.ports.ProviderPhase
import com.assistant.core.voice.ports.ProviderSignal
import com.assistant.core.voice.ports.ProviderSignal.Error
import com.assistant.core.voice.ports.ProviderSignal.FlushSpeaker
import com.assistant.core.voice.ports.ProviderSignal.Phase
import com.assistant.core.voice.ports.ProviderSignal.ReconnectWarning
import com.assistant.core.voice.ports.ProviderSignal.Reconnecting
import com.assistant.core.voice.ports.ProviderSignal.SessionEnded
import com.assistant.core.voice.ports.ProviderSignal.SpeechStarted
import com.assistant.core.voice.ports.ProviderSignal.SpeechStopped
import com.assistant.core.voice.ports.ProviderSignal.Teardown
import com.assistant.core.voice.ports.ProviderSignal.TextComplete
import com.assistant.core.voice.ports.ProviderSignal.TextDelta
import com.assistant.core.voice.ports.ProviderSignal.ToolUse
import com.assistant.core.voice.ports.ProviderSignal.TurnComplete
import com.assistant.core.voice.ports.ProviderSignal.UserTranscript
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Provider event parsers (inv04 §2.2 table, §2.3). Pure: event in, ordered signals out. Signal
 * order within one event follows the old code: phase first, then the event. Transport actions
 * (`FlushSpeaker`, `Teardown`) are signals too, so the transports stay thin.
 */

object ProviderParsers {
    /** "openai" → OPENAI, "qwen" → QWEN, "google" → GEMINI; unknown → by connection type (`VoiceManager.providerFor`). */
    fun kindFor(providerId: String, connectionType: ConnectionType): ParserKind = when (providerId) {
        "openai" -> ParserKind.OPENAI
        "qwen" -> ParserKind.QWEN
        "google" -> ParserKind.GEMINI
        // OpenAI-Realtime's event shape is the de-facto standard among third-party realtime APIs.
        else -> if (connectionType == ConnectionType.WEBRTC) ParserKind.OPENAI else ParserKind.QWEN
    }

    /** A fresh parser (Gemini is stateful: one per session). */
    fun create(kind: ParserKind): ProviderEventParser = when (kind) {
        ParserKind.OPENAI -> OpenAiEventParser()
        ParserKind.QWEN -> QwenEventParser()
        ParserKind.GEMINI -> GeminiEventParser()
    }
}

/** OpenAI Realtime data-channel events (`OpenAIVoiceProvider.handleDataChannelMessage`). */
class OpenAiEventParser : ProviderEventParser {
    override fun parse(event: JsonObject, current: ProviderPhase): List<ProviderSignal> =
        when (typeOf(event) ?: "") {
            "error" -> {
                val err = event.obj("error")
                val code = err?.optString("code") ?: "unknown"
                val message = err?.optString("message") ?: "Unknown error"
                val phaseMessage = if (code == "session_expired") "Voice session expired — please restart" else "Voice error: $code"
                listOf(Phase(ProviderPhase.ERROR, phaseMessage), Error(message), Teardown)
            }
            "response.created" -> listOf(Phase(ProviderPhase.SPEAKING))
            "response.done" -> listOf(Phase(ProviderPhase.ACTIVE), TurnComplete)
            "response.output_item.added" ->
                if (event.obj("item")?.optString("type") == "function_call") listOf(Phase(ProviderPhase.TOOL_USE)) else emptyList()
            "response.function_call_arguments.done" -> listOf(Phase(ProviderPhase.THINKING), toolUse(event))
            "input_audio_buffer.speech_started" -> listOf(Phase(ProviderPhase.ACTIVE), SpeechStarted)
            "input_audio_buffer.speech_stopped" -> listOf(Phase(ProviderPhase.THINKING), SpeechStopped)
            "conversation.item.input_audio_transcription.completed" -> userTranscript(event)
            // GA gpt-realtime emits `response.output_audio_transcript.*`; legacy betas `response.audio_transcript.*` (`304aa80`).
            "response.output_audio_transcript.delta", "response.audio_transcript.delta" ->
                listOf(TextDelta(event.optString("delta")))
            "response.output_audio_transcript.done", "response.audio_transcript.done" ->
                listOf(TextComplete(event.optString("transcript")))
            "output_audio_buffer.started" ->
                if (current != ProviderPhase.SPEAKING) listOf(Phase(ProviderPhase.SPEAKING)) else emptyList()
            "output_audio_buffer.stopped", "output_audio_buffer.cleared" -> listOf(Phase(ProviderPhase.ACTIVE))
            // `session.updated` (the self-heal echo) is handled by the transport's command gate.
            else -> emptyList()
        }
}

/**
 * The relay envelopes every WebSocket provider shares (`WebSocketPcmProvider.handleProviderEvent`):
 * backend-synthesised `voice_status` and the backend relay `error`.
 */
abstract class WsEnvelopeParser : ProviderEventParser {
    final override fun parse(event: JsonObject, current: ProviderPhase): List<ProviderSignal> =
        when (typeOf(event)) {
            "voice_status" -> voiceStatus(event, current)
            // The upstream died and the relay gave up: a silent "connected" session otherwise (`94e3e4a`, RS-18).
            "error" -> {
                val message = event.obj("error")?.string("message") ?: "Voice relay closed by backend"
                listOf(Phase(ProviderPhase.ERROR, message), Error(message), Teardown, SessionEnded)
            }
            else -> parseProvider(event)
        }

    protected abstract fun parseProvider(event: JsonObject): List<ProviderSignal>

    private fun voiceStatus(event: JsonObject, current: ProviderPhase): List<ProviderSignal> =
        when (event.string("status")) {
            "preparing" -> listOf(Phase(ProviderPhase.CONNECTING))
            // A pre-connect status: mid-call it is a stale broadcast from a reconnect probe (`40ce856`, RS-15).
            "summarizing" -> if (current in PRE_CONNECT) listOf(Phase(ProviderPhase.SUMMARIZING)) else emptyList()
            "ready" -> listOf(Phase(ProviderPhase.ACTIVE))
            "reconnect_warning" -> listOf(ReconnectWarning(timeLeftSeconds(event["time_left"] as? JsonPrimitive)))
            "reconnecting" -> listOf(Reconnecting)
            else -> emptyList()
        }

    private fun timeLeftSeconds(v: JsonPrimitive?): Int? = when {
        v == null -> null
        v.isString -> GoDuration.parseSeconds(v.content)
        else -> v.intOrNull ?: v.doubleOrNull?.toInt()
    }

    private companion object {
        val PRE_CONNECT = setOf(ProviderPhase.OFF, ProviderPhase.ERROR, ProviderPhase.CONNECTING, ProviderPhase.SUMMARIZING)
    }
}

/** Qwen-Omni Realtime: OpenAI-Realtime-shaped, legacy transcript names only (inv04 §12 errata 4). */
class QwenEventParser : WsEnvelopeParser() {
    override fun parseProvider(event: JsonObject): List<ProviderSignal> =
        when (typeOf(event)) {
            "response.created" -> listOf(Phase(ProviderPhase.SPEAKING))
            "response.done" -> listOf(Phase(ProviderPhase.ACTIVE), TurnComplete)
            "response.output_item.added" ->
                if (event.obj("item")?.string("type") == "function_call") listOf(Phase(ProviderPhase.TOOL_USE)) else emptyList()
            "response.function_call_arguments.done" -> listOf(Phase(ProviderPhase.THINKING), toolUse(event))
            // Server VAD barge-in: drop the buffered speaker audio so the previous turn cuts now.
            "input_audio_buffer.speech_started" -> listOf(FlushSpeaker, Phase(ProviderPhase.ACTIVE), SpeechStarted)
            "input_audio_buffer.speech_stopped" -> listOf(Phase(ProviderPhase.THINKING), SpeechStopped)
            "conversation.item.input_audio_transcription.completed" -> userTranscript(event)
            "response.audio_transcript.delta" -> event.string("delta").orEmpty().let { if (it.isNotEmpty()) listOf(TextDelta(it)) else emptyList() }
            "response.audio_transcript.done" -> listOf(TextComplete(event.string("transcript").orEmpty()))
            else -> emptyList()
        }
}

/**
 * Gemini Live envelopes (`serverContent`, `toolCall`). Stateful: input-transcription deltas are
 * staged into ONE user transcript per turn (`ea96ce2`, RS-20) and assistant text into one
 * `TextComplete` at `turnComplete`. `modelTurn.parts` and `toolCall.functionCalls` are read from
 * the JSON arrays (fixes B1).
 */
class GeminiEventParser : WsEnvelopeParser() {
    private val assistantStaged = StringBuilder()
    private val userStaged = StringBuilder()

    @Synchronized
    override fun parseProvider(event: JsonObject): List<ProviderSignal> {
        val out = ArrayList<ProviderSignal>(4)
        event.obj("serverContent")?.let { serverContent(it, out) }
        event.obj("toolCall")?.let { toolCall(it, out) }
        return out
    }

    private fun flushUser(out: MutableList<ProviderSignal>) {
        if (userStaged.isEmpty()) return
        out += UserTranscript(userStaged.toString())
        userStaged.setLength(0)
    }

    private fun serverContent(sc: JsonObject, out: MutableList<ProviderSignal>) {
        sc.obj("inputTranscription")?.string("text")?.takeIf { it.isNotEmpty() }?.let { userStaged.append(it) }
        sc.obj("outputTranscription")?.string("text")?.takeIf { it.isNotEmpty() }?.let { text ->
            flushUser(out) // the model started replying: the user's turn ended
            assistantStaged.append(text)
            out += TextDelta(text)
        }
        // Half-cascade Live previews stream text via modelTurn.parts[].text.
        sc.obj("modelTurn")?.array("parts")?.let { parts ->
            flushUser(out)
            for (p in parts) {
                val text = (p as? JsonObject)?.string("text")
                if (!text.isNullOrEmpty()) {
                    assistantStaged.append(text)
                    out += TextDelta(text)
                }
            }
        }
        if (sc.isTrue("interrupted")) {
            out += FlushSpeaker
            out += Phase(ProviderPhase.ACTIVE)
            assistantStaged.setLength(0) // barge-in cuts the turn
        }
        if (sc.isTrue("turnComplete")) {
            out += Phase(ProviderPhase.ACTIVE)
            flushUser(out) // audio-only turns where neither output path fired
            out += TextComplete(assistantStaged.toString())
            assistantStaged.setLength(0)
            out += TurnComplete
        }
    }

    private fun toolCall(tc: JsonObject, out: MutableList<ProviderSignal>) {
        out += Phase(ProviderPhase.TOOL_USE)
        val calls = tc.array("functionCalls") ?: return
        for (c in calls) {
            val call = c as? JsonObject ?: continue
            val id = call.string("id").orEmpty()
            val name = call.string("name").orEmpty()
            if (id.isNotEmpty() && name.isNotEmpty()) out += ToolUse(id, name, call.obj("args") ?: EMPTY_OBJECT)
        }
    }
}

private fun toolUse(event: JsonObject): ToolUse {
    val args = parseObjectOrNull(event.optString("arguments", "{}")) ?: EMPTY_OBJECT
    return ToolUse(event.optString("call_id"), event.optString("name"), args)
}

private fun userTranscript(event: JsonObject): List<ProviderSignal> {
    val transcript = event.optString("transcript")
    return if (transcript.isNotEmpty()) listOf(UserTranscript(transcript)) else emptyList()
}

/** Gemini's goAway `timeLeft` is a Go duration ("50s", "30m0s", "1h30m0s", "500ms"). */
object GoDuration {
    fun parseSeconds(s: String): Int? {
        if (s.isBlank()) return null
        var total = 0.0
        var i = 0
        while (i < s.length) {
            val numStart = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (numStart == i) return null
            val num = s.substring(numStart, i).toDoubleOrNull() ?: return null
            val unitStart = i
            while (i < s.length && s[i].isLetter()) i++
            val mult = when (s.substring(unitStart, i)) {
                "ns" -> 1e-9
                "us", "µs" -> 1e-6
                "ms" -> 1e-3
                "s" -> 1.0
                "m" -> 60.0
                "h" -> 3600.0
                else -> return null
            }
            total += num * mult
        }
        return total.toInt()
    }
}
