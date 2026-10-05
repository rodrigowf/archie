package com.assistant.core.conversation

import com.assistant.core.model.ConnectionState
import com.assistant.core.model.HarnessProvider
import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
import com.assistant.core.model.SessionStatus
import com.assistant.core.protocol.ContentBlockDto
import com.assistant.core.protocol.MessagePreviewDto
import com.assistant.core.protocol.PaginatedMessagesDto
import com.assistant.core.protocol.ServerFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals

/** A subscribed agent view (Claude by default) with history loaded. */
fun agent(sdkId: String? = "sdk-1", provider: HarnessProvider = HarnessProvider.CLAUDE) =
    ConversationState.initial(SessionRef("L1", sdkId, SessionKind.AGENT, provider)).copy(
        status = SessionStatus.IDLE,
        connection = ConnectionState.SUBSCRIBED,
        history = HistoryState(loaded = true),
    )

/** A subscribed orchestrator view. */
fun orchestrator(voiceActive: Boolean = false) =
    ConversationState.initial(SessionRef("O1", "O1", SessionKind.ORCHESTRATOR, null)).copy(
        status = SessionStatus.IDLE,
        connection = ConnectionState.SUBSCRIBED,
        voiceActive = voiceActive,
        history = HistoryState(loaded = true),
    )

fun ConversationState.on(vararg frames: ServerFrame): ConversationState =
    frames.fold(this) { s, f -> ConversationReducer.reduce(s, ConversationInput.Frame(f)) }

fun ConversationState.input(vararg inputs: ConversationInput): ConversationState =
    inputs.fold(this) { s, i -> ConversationReducer.reduce(s, i) }

fun ConversationState.send(text: String) = input(ConversationInput.LocalSend(text))

fun ConversationState.effectsOf(i: ConversationInput) = ConversationReducer.step(this, i).effects

fun ConversationState.effectsOf(f: ServerFrame) = effectsOf(ConversationInput.Frame(f))

fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

fun voice(text: String) = ServerFrame.VoiceEvent(obj(text))

fun processing() = ServerFrame.Status("processing")

fun turnComplete(sessionId: String? = "sdk-1") = ServerFrame.TurnComplete(cost = 0.01, inputTokens = 1000, numTurns = 1, sessionId = sessionId)

fun toolUse(id: String, name: String = "Bash", input: String = "{}") = ServerFrame.ToolUse(id, name, obj(input))

fun toolResult(id: String, output: String, isError: Boolean = false) = ServerFrame.ToolResult(id, JsonPrimitive(output), isError)

/** A REST page of [messages] starting at [start]. */
fun page(start: Int, total: Int, vararg messages: MessagePreviewDto) =
    PaginatedMessagesDto(messages.toList(), total, start > 0, start)

fun userLine(text: String) = MessagePreviewDto("user", text, listOf(ContentBlockDto("text", text = text)))

fun assistantText(text: String) = MessagePreviewDto("assistant", text, listOf(ContentBlockDto("text", text = text)))

fun assistantTool(id: String, name: String = "Bash", input: String = "{}") =
    MessagePreviewDto("assistant", "", listOf(ContentBlockDto("tool_use", toolUseId = id, toolName = name, toolInput = obj(input))))

fun resultLine(id: String, output: String, isError: Boolean = false) =
    MessagePreviewDto("user", "", listOf(ContentBlockDto("tool_result", toolUseId = id, output = JsonPrimitive(output), isError = isError)))

/**
 * A compact rendering of the timeline for assertions:
 * `U(text)` user (origin suffix for non-local), `N:kind(text)`, `A[...]` with `T(text)` / `K(text)` (thinking,
 * `~` = streaming), `X(id:status=output)` tools (`?` = inferred), `P(id:state)`.
 */
fun shape(s: ConversationState): String = s.entries.joinToString(" ") { e ->
    when (e) {
        is UserEntry -> "U(${e.text})" + (if (e.origin != UserOrigin.LOCAL) ":${e.origin.wire}" else "") +
            (if (e.state == UserState.PENDING) "!" else "") + (if (e.streaming) "~" else "")
        is NoticeEntry -> "N:${e.notice.wire}(${e.text})"
        is AssistantEntry -> "A[" + e.blocks.joinToString(" ") { b ->
            when (b) {
                is TextBlock -> "T(${b.text})" + (if (b.streaming) "~" else "") + (if (b.scope == BlockScope.VOICE) "v" else "")
                is ThinkingBlock -> "K(${b.text})" + (if (b.streaming) "~" else "")
                is ToolBlock -> "X(${b.toolUseId}:${b.status.wire}" + (b.output?.let { "=$it" } ?: "") + ")" + (if (b.inferred) "?" else "")
                is PermissionBlock -> "P(${b.requestId}:${b.state.wire})"
            }
        } + "]"
    }
}

fun assertShape(expected: String, s: ConversationState) {
    assertEquals(expected, shape(s))
    val v = InvariantChecker.check(s)
    assertEquals("invariant violations", emptyList<String>(), v)
}
