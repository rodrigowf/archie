package com.assistant.core.conversation.fixtures

import com.assistant.core.conversation.AssistantEntry
import com.assistant.core.conversation.ConversationInput
import com.assistant.core.conversation.ConversationReducer
import com.assistant.core.conversation.ConversationState
import com.assistant.core.conversation.NoticeEntry
import com.assistant.core.conversation.PageMode
import com.assistant.core.conversation.PermissionBlock
import com.assistant.core.conversation.TextBlock
import com.assistant.core.conversation.ThinkingBlock
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.UserEntry
import com.assistant.core.model.ConnectionState
import com.assistant.core.model.HarnessProvider
import com.assistant.core.model.LiveStatus
import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
import com.assistant.core.model.SessionStatus
import com.assistant.core.protocol.PaginatedMessagesDto
import com.assistant.core.protocol.ProtocolCodec
import com.assistant.core.protocol.RestJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal

/**
 * Loader, runner and normaliser for `apps/protocol-fixtures` (format: its README). The fixture
 * format is owned by spec 12; Android owns only this runner (spec 14 §6.1).
 */
object ProtocolFixtures {
    private val json = Json { ignoreUnknownKeys = true }

    /** File names from the generated `fixtures-index.txt` (no classpath scanning). */
    fun names(): List<String> {
        val stream = javaClass.classLoader.getResourceAsStream("fixtures-index.txt")
            ?: error("fixtures-index.txt missing: the generateFixtureIndex wiring is broken")
        return stream.bufferedReader().readLines().map { it.trim() }.filter { it.endsWith(".json") }
    }

    fun load(fileName: String): JsonObject {
        val stream = javaClass.classLoader.getResourceAsStream(fileName) ?: error("fixture $fileName not on the test classpath")
        return json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    // ───────────────────────── runner (README "Runner algorithm") ─────────────────────────

    /** The conversation of `fixture.session`: spec §2.3 initial values, live_status per ST-2, already subscribed. */
    fun initialState(session: JsonObject): ConversationState {
        val kind = SessionKind.fromWire(session.str("kind")) ?: error("bad session.kind")
        val live = LiveStatus.fromWire(session.str("live_status"))
        val ref = SessionRef(
            localId = session.str("local_id")!!,
            sdkId = session.str("sdk_id"),
            kind = kind,
            provider = HarnessProvider.fromWire(session.str("provider")),
            live = live != null,
            liveStatus = live,
        )
        var s = ConversationState.initial(ref)
        if (s.status == SessionStatus.CONNECTING) s = s.copy(status = SessionStatus.IDLE)   // "the socket is already subscribed"
        return s.copy(
            connection = ConnectionState.SUBSCRIBED,
            voiceActive = (session["voice_active"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    /** Steps of one fixture, in order, as reducer inputs (for invariant checks after every step). */
    fun inputs(fixture: JsonObject): List<ConversationInput?> {
        val out = ArrayList<ConversationInput?>()
        val events = fixture["events"]?.jsonArray ?: JsonArray(emptyList())
        val actions = (fixture["client_actions"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
        for (i in 0..events.size) {
            for (a in actions) if (a["at"]!!.jsonPrimitive.int == i) out += action(a)
            if (i < events.size) {
                val frame = ProtocolCodec.decodeServer(events[i]) ?: error("event $i does not decode: ${events[i]}")
                out += ConversationInput.Frame(frame)
            }
        }
        return out
    }

    /** `null` = an action with no reducer effect (`permission_response`). */
    private fun action(a: JsonObject): ConversationInput? = when (val t = a.str("type")) {
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
        "rest_page" -> ConversationInput.HistoryPage(pageMode(a.str("mode")!!), page(a["response"]!!.jsonObject))
        else -> error("unknown client action $t")
    }

    private fun pageMode(m: String) = when (m) {
        "replace" -> PageMode.REPLACE
        "prepend" -> PageMode.PREPEND
        "reconcile" -> PageMode.RECONCILE
        else -> error("bad mode $m")
    }

    fun page(o: JsonObject): PaginatedMessagesDto = RestJson.decodeFromJsonElement(o)

    /**
     * Runs the fixture. [onStep] sees the state after `initial_history` and after every input
     * (used by the invariant checks).
     */
    fun run(fixture: JsonObject, onStep: (ConversationInput?, ConversationState) -> Unit = { _, _ -> }): ConversationState {
        var s = initialState(fixture["session"]!!.jsonObject)
        val history = fixture["initial_history"]
        if (history != null && history !is JsonNull) {
            s = ConversationReducer.reduce(s, ConversationInput.HistoryPage(PageMode.REPLACE, page(history.jsonObject)))
        }
        s = s.copy(history = s.history.copy(loaded = true))
        onStep(null, s)
        for (input in inputs(fixture)) {
            if (input != null) s = ConversationReducer.reduce(s, input)
            onStep(input, s)
        }
        return s
    }

    // ───────────────────────── normalisation (README "Normalisation") ─────────────────────────

    fun normaliseEntries(s: ConversationState): JsonArray = buildJsonArray {
        for (e in s.entries) add(
            when (e) {
                is UserEntry -> buildJsonObject {
                    put("kind", "user"); put("text", e.text); put("origin", e.origin.wire); put("state", e.state.wire)
                    if (e.streaming) put("streaming", true)
                }
                is NoticeEntry -> buildJsonObject {
                    put("kind", "notice"); put("notice", e.notice.wire); put("text", e.text)
                }
                is AssistantEntry -> buildJsonObject {
                    put("kind", "assistant")
                    put("blocks", buildJsonArray {
                        for (b in e.blocks) add(
                            when (b) {
                                is TextBlock -> buildJsonObject { put("type", "text"); put("text", b.text); put("streaming", b.streaming) }
                                is ThinkingBlock -> buildJsonObject { put("type", "thinking"); put("text", b.text); put("streaming", b.streaming) }
                                is ToolBlock -> buildJsonObject {
                                    put("type", "tool"); put("tool_use_id", b.toolUseId); put("tool_name", b.toolName)
                                    put("tool_input", b.toolInput); put("status", b.status.wire)
                                    put("output", b.output?.let { JsonPrimitive(it) } ?: JsonNull)
                                    if (b.inferred) put("inferred", true)
                                }
                                is PermissionBlock -> buildJsonObject {
                                    put("type", "permission"); put("request_id", b.requestId); put("tool_name", b.toolName)
                                    put("state", b.state.wire)
                                    put("responder", b.responder?.let { JsonPrimitive(it) } ?: JsonNull)
                                    put("message", b.message?.let { JsonPrimitive(it) } ?: JsonNull)
                                }
                            },
                        )
                    })
                }
            },
        )
    }

    fun normaliseOrphans(s: ConversationState): JsonArray = buildJsonArray {
        for ((id, r) in s.orphanResults) add(buildJsonObject { put("tool_use_id", id); put("output", r.output); put("is_error", r.isError) })
    }

    fun normaliseUnattributed(s: ConversationState): JsonArray = buildJsonArray {
        for (r in s.unattributed) add(buildJsonObject { put("output", r.output); put("is_error", r.isError) })
    }

    fun normaliseQueue(s: ConversationState): JsonArray = buildJsonArray {
        for (q in s.queue) add(buildJsonObject { put("text", q.text); put("owner", q.owner.wire) })
    }

    /** One `expected.state` key. */
    fun stateValue(s: ConversationState, key: String): JsonElement = when (key) {
        "status" -> JsonPrimitive(s.status.wire)
        "in_turn" -> JsonPrimitive(s.inTurn)
        "stall" -> s.stall?.let {
            buildJsonObject {
                put("elapsed_seconds", it.elapsedSeconds); put("last_tool_name", it.lastToolName); put("last_tool_use_id", it.lastToolUseId)
            }
        } ?: JsonNull
        "termination" -> s.termination?.let {
            buildJsonObject { put("reason", it.reason); put("detail", it.detail); put("sdk_session_id", it.sdkSessionId) }
        } ?: JsonNull
        "checkpoint" -> s.checkpoint?.let { buildJsonObject { put("stream_id", it.streamId); put("seq", it.seq) } } ?: JsonNull
        "context_tokens" -> s.counters.contextTokens?.let { JsonPrimitive(it) } ?: JsonNull
        "cost" -> JsonPrimitive(s.counters.cost)
        "turns" -> JsonPrimitive(s.counters.turns)
        "sdk_id" -> s.ref.sdkId?.let { JsonPrimitive(it) } ?: JsonNull
        "local_id" -> JsonPrimitive(s.ref.localId)
        "voice_active" -> JsonPrimitive(s.voiceActive)
        "gap_possible" -> JsonPrimitive(s.gapPossible)
        "reloading" -> JsonPrimitive(s.reloading)
        "history_has_more" -> JsonPrimitive(s.history.hasMore)
        "history_start_index" -> JsonPrimitive(s.history.startIndex)
        "connection_banner" -> s.connectionBanner?.let { buildJsonObject { put("code", it.code); put("detail", it.detail) } } ?: JsonNull
        "agent_approvals" -> buildJsonArray {
            for (a in s.agentApprovals) add(buildJsonObject {
                put("local_id", a.localId); put("request_id", a.requestId); put("tool_name", a.toolName); put("tool_input", a.toolInput)
            })
        }
        "last_start" -> s.startRequest?.let { ProtocolCodec.encodeClientJson(it) } ?: JsonNull
        else -> error("unknown expected.state key '$key' (README §expected.state)")
    }

    /**
     * Compares [actual] with the fixture's `expected`. Returns a list of human-readable mismatches
     * (empty = pass). `controller` is ignored here (voice-controller harness, README).
     */
    fun compare(fixture: JsonObject, actual: ConversationState): List<String> {
        val expected = fixture["expected"]!!.jsonObject
        val problems = ArrayList<String>()
        diff("entries", expected["entries"] ?: JsonArray(emptyList()), normaliseEntries(actual), problems)
        diff("orphan_results", expected["orphan_results"] ?: JsonArray(emptyList()), normaliseOrphans(actual), problems)
        diff("unattributed_results", expected["unattributed_results"] ?: JsonArray(emptyList()), normaliseUnattributed(actual), problems)
        expected["queue"]?.let { diff("queue", it, normaliseQueue(actual), problems) }
        (expected["state"] as? JsonObject)?.forEach { (k, v) -> diff("state.$k", v, stateValue(actual, k), problems) }
        return problems
    }

    /** Deep JSON comparison: objects by key set (order irrelevant), arrays by position, numbers numerically. */
    fun diff(path: String, expected: JsonElement, actual: JsonElement, out: MutableList<String>) {
        when {
            expected is JsonObject && actual is JsonObject -> {
                for (k in expected.keys + actual.keys) {
                    val e = expected[k]; val a = actual[k]
                    if (e == null) out += "$path.$k: unexpected ${a}"
                    else if (a == null) out += "$path.$k: missing (expected $e)"
                    else diff("$path.$k", e, a, out)
                }
            }
            expected is JsonArray && actual is JsonArray -> {
                if (expected.size != actual.size) {
                    out += "$path: expected ${expected.size} items, got ${actual.size}\n  expected: $expected\n  actual:   $actual"
                    return
                }
                for (i in expected.indices) diff("$path[$i]", expected[i], actual[i], out)
            }
            expected is JsonPrimitive && actual is JsonPrimitive && !expected.isString && !actual.isString &&
                expected !is JsonNull && actual !is JsonNull && expected.booleanOrNull == null && actual.booleanOrNull == null -> {
                if (BigDecimal(expected.content).compareTo(BigDecimal(actual.content)) != 0) out += "$path: expected $expected, got $actual"
            }
            else -> if (expected != actual) out += "$path: expected $expected, got $actual"
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
