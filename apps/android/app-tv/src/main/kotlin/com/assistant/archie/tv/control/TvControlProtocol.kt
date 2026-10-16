package com.assistant.archie.tv.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * The TV control wire (spec 15 §5.1, spec 12 §9.5): JSON text frames on /api/tv/ws. Pure, so the
 * client and the command handler are JVM-tested against the exact frames the backend
 * (`backend/api/routes/tv.py`) sends and expects.
 */

/** The commands this app implements (sent as `capabilities` in the hello). */
object TvCommands {
    const val OPEN_URL = "open_url"
    const val OPEN_VISUALIZATION = "open_visualization"
    const val OPEN_MEMORY = "open_memory"
    const val OPEN_CONVERSATION = "open_conversation"
    const val HOME = "home"
    const val BACK = "back"
    const val RELOAD = "reload"
    const val SCROLL = "scroll"
    const val MEDIA = "media"
    const val NOTIFY = "notify"
    const val GET_STATE = "get_state"

    val ALL: List<String> = listOf(OPEN_URL, OPEN_VISUALIZATION, OPEN_MEMORY, OPEN_CONVERSATION, HOME, BACK, RELOAD, SCROLL, MEDIA, NOTIFY, GET_STATE)

    /** Commands that change what the screen shows; refused while "Allow agents to open pages" is off. */
    val DISPLAY: Set<String> = setOf(OPEN_URL, OPEN_VISUALIZATION, OPEN_MEMORY, OPEN_CONVERSATION, HOME, BACK, RELOAD, SCROLL, MEDIA)
}

/** One `{"id","type":"command","command","params"}` frame. */
data class TvCommand(val id: String, val name: String, val params: JsonObject = JsonObject(emptyMap())) {
    fun string(key: String): String? = (params[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    fun int(key: String): Int? = (params[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
}

/** What the handler answers: `ok` with a result object, or an error string (never closes the socket, RD-3). */
sealed interface TvReply {
    data class Ok(val result: JsonObject = JsonObject(emptyMap())) : TvReply
    data class Error(val message: String) : TvReply
}

/** The hello's fields (RD-1). */
data class TvHello(
    val deviceId: String,
    val deviceName: String,
    val version: String,
    val capabilities: List<String> = TvCommands.ALL,
    val token: String? = null,
    val client: String = "archie-tv",
)

/** Inbound frames the client acts on. */
sealed interface TvInbound {
    data object Ready : TvInbound
    data class Error(val reason: String) : TvInbound
    data class Command(val command: TvCommand) : TvInbound
    data object Ping : TvInbound
    data object Pong : TvInbound
    data object Other : TvInbound
}

object TvControlProtocol {
    const val PATH = "/api/tv/ws"

    /** Close codes (backend `WS_CLOSE_*`). */
    const val CLOSE_UNAUTHORIZED = 4401
    const val CLOSE_REPLACED = 4409

    private val json = Json { ignoreUnknownKeys = true }

    fun hello(h: TvHello): String = buildJsonObject {
        put("type", "hello")
        put("device_id", h.deviceId)
        put("device_name", h.deviceName)
        put("client", h.client)
        put("version", h.version)
        put("capabilities", kotlinx.serialization.json.JsonArray(h.capabilities.map(::JsonPrimitive)))
        if (!h.token.isNullOrBlank()) put("token", h.token)
    }.toString()

    fun result(id: String, reply: TvReply): String = buildJsonObject {
        put("id", id)
        put("type", "result")
        when (reply) {
            is TvReply.Ok -> {
                put("ok", true)
                put("result", reply.result)
            }
            is TvReply.Error -> {
                put("ok", false)
                put("error", reply.message)
            }
        }
    }.toString()

    const val PONG = "{\"type\":\"pong\"}"
    const val PING = "{\"type\":\"ping\"}"

    /** A `state` frame (RD-5): [fields] plus `"type":"state"`. */
    fun state(fields: JsonObject): String = JsonObject(mapOf("type" to JsonPrimitive("state")) + fields).toString()

    fun parse(text: String): TvInbound {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return TvInbound.Other
        return when (o.str("type")) {
            "ready" -> TvInbound.Ready
            "error" -> TvInbound.Error(o.str("error") ?: "error")
            "ping" -> TvInbound.Ping
            "pong" -> TvInbound.Pong
            "command" -> {
                val id = o.str("id") ?: return TvInbound.Other
                val name = o.str("command") ?: return TvInbound.Other
                val params = (o["params"] as? JsonObject) ?: JsonObject(emptyMap())
                TvInbound.Command(TvCommand(id, name, params))
            }
            else -> TvInbound.Other
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** `{k: v, …}` for results; null values are left out. */
    fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
        pairs.mapNotNull { (k, v) -> toJson(v)?.let { k to it } }.toMap(),
    )

    private fun toJson(v: Any?): JsonElement? = when (v) {
        null -> null
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    /** Reads a string field of an inbound result-like object (tests). */
    fun field(o: JsonObject, key: String): String? = o[key]?.jsonPrimitive?.contentOrNull
}
