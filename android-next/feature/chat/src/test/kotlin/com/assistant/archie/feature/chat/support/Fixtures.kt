package com.assistant.archie.feature.chat.support

import com.assistant.archie.feature.chat.ChatListFlattener
import com.assistant.archie.feature.chat.ConversationUiMapper
import com.assistant.archie.feature.chat.ConversationUiState
import com.assistant.archie.feature.chat.CountersUi
import com.assistant.archie.feature.chat.FlattenOptions
import com.assistant.archie.feature.chat.MarkdownStore
import com.assistant.archie.feature.chat.VoiceUi
import com.assistant.archie.feature.chat.model.ChatItem
import com.assistant.archie.feature.chat.ui.DefaultToolCardRenderer
import com.assistant.core.conversation.AssistantEntry
import com.assistant.core.conversation.ConversationInput
import com.assistant.core.conversation.ConversationReducer
import com.assistant.core.conversation.ConversationState
import com.assistant.core.conversation.PageMode
import com.assistant.core.conversation.TextBlock
import com.assistant.core.model.ConnectionState
import com.assistant.core.model.HarnessProvider
import com.assistant.core.model.LiveStatus
import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
import com.assistant.core.model.SessionStatus
import com.assistant.core.protocol.PaginatedMessagesDto
import com.assistant.core.protocol.ProtocolCodec
import com.assistant.core.protocol.RestJson
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Replays `shared/protocol-fixtures` (format: its README) through the real reducer, the same way
 * `:core:conversation`'s conformance runner does, so the UI tests render exactly the state the
 * reducer produces (spec 14 §6.3).
 */
object Fixtures {
    private val json = Json { ignoreUnknownKeys = true }

    /** Text/tool interleaving in orchestrator voice and text turns (R4). */
    val R4 = listOf(
        "android_voice_ordering_bug.json",
        "text_tool_interleaving.json",
        "tool_result_after_voice_transcript.json",
        "voice_mode_tool_result_gemini.json",
        "parallel_tools_reverse_results.json",
        "thinking_and_text.json",
        "background_notification_wake_turn.json",
        "voice_late_user_transcript_anchor.json",
    )

    /** Tool results that arrive late, out of order, by position or on a REST page (R7). */
    val R7 = listOf(
        "tool_result_after_interleaved_user_message.json",
        "tool_result_after_turn_ended.json",
        "tool_result_before_tool_use.json",
        "tool_result_on_next_history_page.json",
        "tool_result_string_shape_empty_id.json",
        "tool_result_empty_id_parallel_reconcile.json",
        "web_bug5_permission_feedback_then_result.json",
        "interrupt_mid_tool.json",
        "parallel_tools_reverse_results.json",
    )

    fun load(name: String): JsonObject {
        val stream = javaClass.classLoader!!.getResourceAsStream(name) ?: error("fixture $name not on the test classpath")
        return json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    fun initialState(session: JsonObject): ConversationState {
        val kind = SessionKind.fromWire(session.str("kind"))!!
        val live = LiveStatus.fromWire(session.str("live_status"))
        val ref = SessionRef(session.str("local_id")!!, session.str("sdk_id"), kind, HarnessProvider.fromWire(session.str("provider")), live != null, live)
        var s = ConversationState.initial(ref)
        if (s.status == SessionStatus.CONNECTING) s = s.copy(status = SessionStatus.IDLE)
        return s.copy(
            connection = ConnectionState.SUBSCRIBED,
            voiceActive = (session["voice_active"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    fun inputs(fixture: JsonObject): List<ConversationInput> {
        val out = ArrayList<ConversationInput>()
        val events = fixture["events"]?.jsonArray ?: JsonArray(emptyList())
        val actions = (fixture["client_actions"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
        for (i in 0..events.size) {
            for (a in actions) if (a["at"]!!.jsonPrimitive.int == i) action(a)?.let { out += it }
            if (i < events.size) out += ConversationInput.Frame(ProtocolCodec.decodeServer(events[i])!!)
        }
        return out
    }

    private fun action(a: JsonObject): ConversationInput? = when (a.str("type")) {
        "send" -> ConversationInput.LocalSend(a.str("text")!!)
        "send_audio" -> ConversationInput.LocalSendAudio
        "inject" -> ConversationInput.LocalInject(a.str("text")!!)
        "interrupt" -> ConversationInput.LocalInterrupt
        "compact" -> ConversationInput.LocalCompact
        "stop" -> ConversationInput.LocalStop
        "permission_response" -> null
        "datachannel_event" -> ConversationInput.DataChannelEvent(a["event"]!!.jsonObject)
        "voice_local_end" -> ConversationInput.VoiceLocalEnd
        "ws_closed" -> ConversationInput.SocketClosed
        "ws_open" -> ConversationInput.SocketOpened
        "rest_page" -> ConversationInput.HistoryPage(
            when (a.str("mode")) { "prepend" -> PageMode.PREPEND; "reconcile" -> PageMode.RECONCILE; else -> PageMode.REPLACE },
            page(a["response"]!!.jsonObject),
        )
        else -> error("unknown client action ${a.str("type")}")
    }

    fun page(o: JsonObject): PaginatedMessagesDto = RestJson.decodeFromJsonElement(o)

    /** Every state of the replay: after the initial history, then after each input. */
    fun states(fixture: JsonObject): List<ConversationState> {
        var s = initialState(fixture["session"]!!.jsonObject)
        val h = fixture["initial_history"]
        if (h != null && h !is JsonNull) s = ConversationReducer.reduce(s, ConversationInput.HistoryPage(PageMode.REPLACE, page(h.jsonObject)))
        s = s.copy(history = s.history.copy(loaded = true))
        val out = arrayListOf(s)
        for (i in inputs(fixture)) { s = ConversationReducer.reduce(s, i); out += s }
        return out
    }

    fun run(name: String): ConversationState = states(load(name)).last()

    /**
     * The fixture's expected timeline as order tokens: `u:<text>`, `n:<notice>`, `x:<text>`,
     * `k:<text>` (thinking), `t:<tool_use_id>`, `p:<request_id>`. Empty text blocks are not rendered.
     */
    fun expectedTokens(fixture: JsonObject): List<String> {
        val out = ArrayList<String>()
        for (e in fixture["expected"]!!.jsonObject["entries"]!!.jsonArray.map { it.jsonObject }) {
            when (e.str("kind")) {
                "user" -> out += "u:${e.str("text")}"
                "notice" -> out += "n:${e.str("notice")}"
                "assistant" -> for (b in e["blocks"]!!.jsonArray.map { it.jsonObject }) when (b.str("type")) {
                    "text" -> if (!b.str("text").isNullOrEmpty()) out += "x:${b.str("text")}"
                    "thinking" -> if (!b.str("text").isNullOrBlank() || b["streaming"]?.jsonPrimitive?.booleanOrNull == true) out += "k:${b.str("text")}"
                    "tool" -> out += "t:${b.str("tool_use_id")}"
                    "permission" -> out += "p:${b.str("request_id")}"
                }
            }
        }
        return out
    }

    /** The same tokens from rendered items (markdown nodes of one block collapse to one token). */
    fun tokens(items: List<ChatItem>, state: ConversationState): List<String> {
        val texts = state.entries.filterIsInstance<AssistantEntry>().flatMap { it.blocks }.filterIsInstance<TextBlock>().associate { it.id to it.text }
        val out = ArrayList<String>()
        var lastBlock: String? = null
        for (it in items) {
            when (it) {
                is ChatItem.UserBubble -> out += "u:${it.text}"
                is ChatItem.Notice -> out += "n:${it.kind.wire}"
                is ChatItem.CompactDivider -> out += "n:compaction"
                is ChatItem.MdBlock -> if (it.blockId != lastBlock) out += "x:${texts[it.blockId]}"
                is ChatItem.Thinking -> out += "k:${it.text}"
                is ChatItem.ToolCard -> out += "t:${it.block.toolUseId}"
                is ChatItem.Permission -> out += "p:${it.block.requestId}"
                else -> Unit
            }
            lastBlock = (it as? ChatItem.MdBlock)?.blockId
        }
        return out
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** Builds a [ConversationUiState] from a reducer state, as the ViewModel does (no coroutines). */
object UiStates {
    fun of(
        s: ConversationState,
        options: FlattenOptions = FlattenOptions(),
        voice: VoiceUi = VoiceUi.Off,
        draft: String = "",
        store: MarkdownStore = MarkdownStore(),
        title: String? = null,
    ): ConversationUiState {
        val f = ChatListFlattener(DefaultToolCardRenderer::describe) { id, t, st -> store.snapshot(id, t, st) }
        val r = f.flatten(s, options)
        val vu = if (s.kind == SessionKind.ORCHESTRATOR) voice else VoiceUi.Off
        return ConversationUiState(
            kind = s.kind,
            loaded = s.history.loaded,
            items = r.items.toImmutableList(),
            oldestEntryId = s.entries.firstOrNull()?.id,
            hasMore = s.history.hasMore,
            empty = s.entries.isEmpty(),
            composer = ConversationUiMapper.composer(s, draft.isBlank(), vu, false, title),
            cards = ConversationUiMapper.cards(s, emptySet(), emptySet(), { true }, emptyList()),
            queue = s.queue.toImmutableList(),
            voice = vu,
            counters = CountersUi(s.counters.cost, s.counters.turns, s.counters.contextTokens, s.counters.contextWindow),
        )
    }

    /** Every tool card and group expanded (to inspect outputs and full order). */
    fun expandAll(s: ConversationState, store: MarkdownStore = MarkdownStore()): FlattenOptions {
        val f = ChatListFlattener(DefaultToolCardRenderer::describe) { id, t, st -> store.snapshot(id, t, st) }
        val all = f.flatten(s, FlattenOptions(grouping = true)).items
        val groups = all.filterIsInstance<ChatItem.ToolGroup>().associate { it.key to true }
        val first = f.flatten(s, FlattenOptions(groupToggles = groups)).items
        return FlattenOptions(groupToggles = groups, cardToggles = first.filterIsInstance<ChatItem.ToolCard>().associate { it.key to true })
    }
}

/** Decodes inline JSON frames for hand-built scenarios. */
object Frames {
    fun of(vararg json: String): List<ConversationInput> = json.map { ConversationInput.Frame(ProtocolCodec.decodeServer(it) ?: error("bad frame $it")) }

    fun reduce(s: ConversationState, vararg json: String): ConversationState = ConversationReducer.reduceAll(s, of(*json))

    fun agent(localId: String = "L1", sdkId: String? = "sdk-1"): ConversationState =
        ConversationState.initial(SessionRef(localId, sdkId, SessionKind.AGENT, HarnessProvider.CLAUDE)).copy(
            status = SessionStatus.IDLE,
            connection = ConnectionState.SUBSCRIBED,
            history = com.assistant.core.conversation.HistoryState(loaded = true),
        )

    fun archie(localId: String = "O1", sdkId: String? = "orch-1"): ConversationState =
        ConversationState.initial(SessionRef(localId, sdkId, SessionKind.ORCHESTRATOR, null)).copy(
            status = SessionStatus.IDLE,
            connection = ConnectionState.SUBSCRIBED,
            history = com.assistant.core.conversation.HistoryState(loaded = true),
        )
}
