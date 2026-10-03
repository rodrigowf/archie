package com.assistant.core.voice.parity

import com.assistant.core.testing.PinsConstant
import com.assistant.core.testing.obj
import com.assistant.core.voice.ports.ConnectionType
import com.assistant.core.voice.ports.ParserKind
import com.assistant.core.voice.ports.ProviderPhase
import com.assistant.core.voice.ports.ProviderPhase.ACTIVE
import com.assistant.core.voice.ports.ProviderPhase.CONNECTING
import com.assistant.core.voice.ports.ProviderPhase.ERROR
import com.assistant.core.voice.ports.ProviderPhase.OFF
import com.assistant.core.voice.ports.ProviderPhase.SPEAKING
import com.assistant.core.voice.ports.ProviderPhase.SUMMARIZING
import com.assistant.core.voice.ports.ProviderPhase.THINKING
import com.assistant.core.voice.ports.ProviderPhase.TOOL_USE
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Ignore
import org.junit.Test

/**
 * Provider event parsers (inv04 §2.2 table, §2.3; RS-05, RS-15, RS-18, RS-19, RS-20; fixes B1).
 * Signal order within one event follows the old code: phase first, then the event.
 */
@Ignore("A-06")
class ProviderParserParityTest {
    private fun parse(kind: ParserKind, json: String, current: ProviderPhase = ACTIVE): List<ProviderSignal> =
        voiceCore.parser(kind).parse(obj(json), current)

    private fun openai(json: String, current: ProviderPhase = ACTIVE) = parse(ParserKind.OPENAI, json, current)

    // ── provider selection ────────────────────────────────────────────────────────────────────

    @Test
    @PinsConstant("voice.provider_selection")
    fun providerSelectionByIdThenByConnectionType() {
        assertEquals(ParserKind.OPENAI, voiceCore.parserKindFor("openai", ConnectionType.WEBRTC))
        assertEquals(ParserKind.QWEN, voiceCore.parserKindFor("qwen", ConnectionType.WEBSOCKET))
        assertEquals(ParserKind.GEMINI, voiceCore.parserKindFor("google", ConnectionType.WEBSOCKET))
        assertEquals(ParserKind.OPENAI, voiceCore.parserKindFor("mystery", ConnectionType.WEBRTC))
        assertEquals("unknown WS → OpenAI-Realtime-shaped Qwen parser", ParserKind.QWEN, voiceCore.parserKindFor("mystery", ConnectionType.WEBSOCKET))
    }

    // ── OpenAI data-channel events (inv04 §2.2 table) ──────────────────────────────────────────

    @Test
    fun openAiResponseLifecycle() {
        assertEquals(listOf(Phase(SPEAKING)), openai("""{"type":"response.created"}"""))
        assertEquals(listOf(Phase(ACTIVE), TurnComplete), openai("""{"type":"response.done"}"""))
        assertEquals(listOf(Phase(TOOL_USE)), openai("""{"type":"response.output_item.added","item":{"type":"function_call"}}"""))
        assertEquals(emptyList<ProviderSignal>(), openai("""{"type":"response.output_item.added","item":{"type":"message"}}"""))
    }

    @Test
    fun openAiToolCallArgumentsAreParsed() {
        assertEquals(
            listOf(Phase(THINKING), ToolUse("c1", "lookup", obj("""{"q":"x","n":2}"""))),
            openai("""{"type":"response.function_call_arguments.done","call_id":"c1","name":"lookup","arguments":"{\"q\":\"x\",\"n\":2}"}"""),
        )
        assertEquals(
            listOf(Phase(THINKING), ToolUse("c2", "bad", JsonObject(emptyMap()))),
            openai("""{"type":"response.function_call_arguments.done","call_id":"c2","name":"bad","arguments":"{not json"}"""),
        )
    }

    @Test
    fun openAiSpeechEventsAndTranscripts() {
        assertEquals(listOf(Phase(ACTIVE), SpeechStarted), openai("""{"type":"input_audio_buffer.speech_started"}"""))
        assertEquals(listOf(Phase(THINKING), SpeechStopped), openai("""{"type":"input_audio_buffer.speech_stopped"}"""))
        assertEquals(listOf(UserTranscript("hi there")), openai("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"hi there"}"""))
        assertEquals(emptyList<ProviderSignal>(), openai("""{"type":"conversation.item.input_audio_transcription.completed","transcript":""}"""))
    }

    /** RS-05 (`304aa80`): GA `response.output_audio_transcript.*` and legacy `response.audio_transcript.*` both work. */
    @Test
    fun rs05_gaAndLegacyTranscriptEventNamesProduceTranscripts() {
        for (prefix in listOf("response.output_audio_transcript", "response.audio_transcript")) {
            assertEquals(prefix, listOf(TextDelta("Hel")), openai("""{"type":"$prefix.delta","delta":"Hel"}"""))
            assertEquals(prefix, listOf(TextComplete("Hello.")), openai("""{"type":"$prefix.done","transcript":"Hello."}"""))
        }
    }

    @Test
    fun openAiAudioBufferEventsDrivePhase() {
        assertEquals(listOf(Phase(SPEAKING)), openai("""{"type":"output_audio_buffer.started"}""", current = ACTIVE))
        assertEquals(listOf(Phase(ACTIVE)), openai("""{"type":"output_audio_buffer.stopped"}""", current = SPEAKING))
        assertEquals(listOf(Phase(ACTIVE)), openai("""{"type":"output_audio_buffer.cleared"}""", current = SPEAKING))
        assertEquals(emptyList<ProviderSignal>(), openai("""{"type":"session.updated"}"""))
    }

    @Test
    fun openAiErrorIsTerminal() {
        assertEquals(
            listOf(Phase(ERROR, "Voice error: invalid_value"), Error("Instructions too long"), Teardown),
            openai("""{"type":"error","error":{"code":"invalid_value","message":"Instructions too long"}}"""),
        )
        assertEquals(
            Phase(ERROR, "Voice session expired — please restart"),
            openai("""{"type":"error","error":{"code":"session_expired","message":"expired"}}""").first(),
        )
    }

    // ── WebSocket relay envelopes (Qwen + Gemini; `WebSocketPcmProvider.handleProviderEvent`) ──

    @Test
    fun voiceStatusPreparingReadyReconnecting() {
        for (k in listOf(ParserKind.QWEN, ParserKind.GEMINI)) {
            assertEquals(listOf(Phase(CONNECTING)), parse(k, """{"type":"voice_status","status":"preparing"}"""))
            assertEquals(listOf(Phase(ACTIVE)), parse(k, """{"type":"voice_status","status":"ready"}""", CONNECTING))
            assertEquals(listOf(Reconnecting), parse(k, """{"type":"voice_status","status":"reconnecting"}"""))
        }
    }

    /** RS-15 (`40ce856`): a mid-call `summarizing` does not regress the UI. */
    @Test
    fun rs15_midCallSummarizingDoesNotRegress() {
        for (k in listOf(ParserKind.QWEN, ParserKind.GEMINI)) {
            for (pre in listOf(OFF, ERROR, CONNECTING, SUMMARIZING)) {
                assertEquals("$k $pre", listOf(Phase(SUMMARIZING)), parse(k, """{"type":"voice_status","status":"summarizing"}""", pre))
            }
            for (mid in listOf(ACTIVE, SPEAKING, THINKING, TOOL_USE)) {
                assertEquals("$k $mid", emptyList<ProviderSignal>(), parse(k, """{"type":"voice_status","status":"summarizing"}""", mid))
            }
        }
    }

    /** RS-19 (`b586e4b`/`ff517c2`): goAway `time_left` as a Go duration or a number. */
    @Test
    fun rs19_goAwayTimeLeftIsParsed() {
        val k = ParserKind.GEMINI
        assertEquals(listOf(ReconnectWarning(50)), parse(k, """{"type":"voice_status","status":"reconnect_warning","time_left":"50s"}"""))
        assertEquals(listOf(ReconnectWarning(1800)), parse(k, """{"type":"voice_status","status":"reconnect_warning","time_left":"30m0s"}"""))
        assertEquals(listOf(ReconnectWarning(42)), parse(k, """{"type":"voice_status","status":"reconnect_warning","time_left":42}"""))
        assertEquals(listOf(ReconnectWarning(null)), parse(k, """{"type":"voice_status","status":"reconnect_warning","time_left":"soon"}"""))
        assertEquals(listOf(ReconnectWarning(null)), parse(k, """{"type":"voice_status","status":"reconnect_warning"}"""))
    }

    @Test
    fun rs19_goDurations() {
        assertEquals(50, voiceCore.parseGoDurationSeconds("50s"))
        assertEquals(1800, voiceCore.parseGoDurationSeconds("30m0s"))
        assertEquals(5400, voiceCore.parseGoDurationSeconds("1h30m0s"))
        assertEquals(0, voiceCore.parseGoDurationSeconds("500ms"))
        assertEquals(1, voiceCore.parseGoDurationSeconds("1.5s"))
        assertNull(voiceCore.parseGoDurationSeconds(""))
        assertNull(voiceCore.parseGoDurationSeconds("junk"))
        assertNull(voiceCore.parseGoDurationSeconds("10"))
        assertNull(voiceCore.parseGoDurationSeconds("5x"))
    }

    /** RS-18 (`94e3e4a`): a backend relay `error` is terminal (Error state, teardown, SessionEnded). */
    @Test
    fun rs18_relayErrorMapsToErrorTeardownAndSessionEnded() {
        for (k in listOf(ParserKind.QWEN, ParserKind.GEMINI)) {
            assertEquals(
                listOf(Phase(ERROR, "upstream closed"), Error("upstream closed"), Teardown, SessionEnded),
                parse(k, """{"type":"error","error":{"code":"1011","message":"upstream closed"}}"""),
            )
            assertEquals(
                listOf(Phase(ERROR, "Voice relay closed by backend"), Error("Voice relay closed by backend"), Teardown, SessionEnded),
                parse(k, """{"type":"error"}"""),
            )
        }
    }

    // ── Qwen (OpenAI-Realtime-shaped) ─────────────────────────────────────────────────────────

    @Test
    fun qwenSpeechStartedIsABargeIn() {
        assertEquals(listOf(FlushSpeaker, Phase(ACTIVE), SpeechStarted), parse(ParserKind.QWEN, """{"type":"input_audio_buffer.speech_started"}""", SPEAKING))
    }

    @Test
    fun qwenTranscriptsAndTurns() {
        val q = ParserKind.QWEN
        assertEquals(listOf(Phase(SPEAKING)), parse(q, """{"type":"response.created"}"""))
        assertEquals(listOf(Phase(ACTIVE), TurnComplete), parse(q, """{"type":"response.done"}"""))
        assertEquals(listOf(TextDelta("he")), parse(q, """{"type":"response.audio_transcript.delta","delta":"he"}"""))
        assertEquals(emptyList<ProviderSignal>(), parse(q, """{"type":"response.audio_transcript.delta","delta":""}"""))
        assertEquals(listOf(TextComplete("hello")), parse(q, """{"type":"response.audio_transcript.done","transcript":"hello"}"""))
        assertEquals(listOf(UserTranscript("hi")), parse(q, """{"type":"conversation.item.input_audio_transcription.completed","transcript":"hi"}"""))
        assertEquals(listOf(Phase(THINKING), SpeechStopped), parse(q, """{"type":"input_audio_buffer.speech_stopped"}"""))
    }

    // ── Gemini Live envelopes ─────────────────────────────────────────────────────────────────

    /** RS-20 (`ea96ce2`): input-transcription deltas become ONE user transcript per turn. */
    @Test
    fun rs20_geminiUserDeltasCoalesceIntoOneTranscriptPerTurn() {
        val g = voiceCore.parser(ParserKind.GEMINI)
        assertEquals(emptyList<ProviderSignal>(), g.parse(obj("""{"serverContent":{"inputTranscription":{"text":"What "}}}"""), ACTIVE))
        assertEquals(emptyList<ProviderSignal>(), g.parse(obj("""{"serverContent":{"inputTranscription":{"text":"time is it"}}}"""), ACTIVE))
        assertEquals(
            listOf(UserTranscript("What time is it"), TextDelta("It's")),
            g.parse(obj("""{"serverContent":{"outputTranscription":{"text":"It's"}}}"""), ACTIVE),
        )
        assertEquals(
            listOf(Phase(ACTIVE), TextComplete("It's"), TurnComplete),
            g.parse(obj("""{"serverContent":{"turnComplete":true}}"""), SPEAKING),
        )
    }

    @Test
    fun geminiTurnCompleteFlushesAPendingUserTranscript() {
        val g = voiceCore.parser(ParserKind.GEMINI)
        g.parse(obj("""{"serverContent":{"inputTranscription":{"text":"hello there"}}}"""), ACTIVE)
        assertEquals(
            listOf(Phase(ACTIVE), UserTranscript("hello there"), TextComplete(""), TurnComplete),
            g.parse(obj("""{"serverContent":{"turnComplete":true}}"""), ACTIVE),
        )
    }

    @Test
    fun geminiInterruptedFlushesTheSpeakerAndDropsStagedText() {
        val g = voiceCore.parser(ParserKind.GEMINI)
        g.parse(obj("""{"serverContent":{"outputTranscription":{"text":"Long answer"}}}"""), SPEAKING)
        assertEquals(listOf(FlushSpeaker, Phase(ACTIVE)), g.parse(obj("""{"serverContent":{"interrupted":true}}"""), SPEAKING))
        assertEquals(listOf(Phase(ACTIVE), TextComplete(""), TurnComplete), g.parse(obj("""{"serverContent":{"turnComplete":true}}"""), ACTIVE))
    }

    /** B1: `modelTurn.parts` (half-cascade text) is parsed from the JSON array. */
    @Test
    fun b1_geminiModelTurnPartsAreParsed() {
        val g = voiceCore.parser(ParserKind.GEMINI)
        g.parse(obj("""{"serverContent":{"inputTranscription":{"text":"hi"}}}"""), ACTIVE)
        assertEquals(
            listOf(UserTranscript("hi"), TextDelta("Hel"), TextDelta("lo")),
            g.parse(obj("""{"serverContent":{"modelTurn":{"parts":[{"text":"Hel"},{"inlineData":{}},{"text":"lo"}]}}}"""), ACTIVE),
        )
        assertEquals(listOf(Phase(ACTIVE), TextComplete("Hello"), TurnComplete), g.parse(obj("""{"serverContent":{"turnComplete":true}}"""), ACTIVE))
    }

    /** B1: `toolCall.functionCalls` is parsed from the JSON array; calls without id or name are skipped. */
    @Test
    fun b1_geminiToolCallsAreParsed() {
        assertEquals(
            listOf(Phase(TOOL_USE), ToolUse("f1", "search", obj("""{"q":"news"}"""))),
            parse(ParserKind.GEMINI, """{"toolCall":{"functionCalls":[{"id":"f1","name":"search","args":{"q":"news"}},{"name":"noid"}]}}"""),
        )
    }
}
