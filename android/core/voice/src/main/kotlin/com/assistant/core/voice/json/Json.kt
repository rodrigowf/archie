package com.assistant.core.voice.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * Small readers over the provider JSON trees (recursive `JsonObject`s from :core:protocol, which
 * is what fixes inv04 B1: nested arrays stay `JsonArray`s instead of the old shallow map's
 * `JSONArray` that failed `is List<*>`).
 */

/** `type` when it is a JSON string, else null (Gemini envelopes have none). */
internal fun typeOf(o: JsonObject): String? = o.string("type")

/** The value when it is a JSON string. */
internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * `org.json.JSONObject.optString(key, default)` semantics: any primitive as text (numbers and
 * booleans included), [default] when absent or not a primitive.
 */
internal fun JsonObject.optString(key: String, default: String = ""): String =
    when (val v = this[key]) {
        null -> default
        is JsonNull -> "null"
        is JsonPrimitive -> v.content
        else -> default
    }

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.isTrue(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

internal val EMPTY_OBJECT = JsonObject(emptyMap())

private val lenient = Json { ignoreUnknownKeys = true }

/** Parses [text] as a JSON object, or null when it is not valid JSON or not an object. */
internal fun parseObjectOrNull(text: String): JsonObject? =
    try {
        lenient.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    }

internal fun JsonElement?.asObjectOrEmpty(): JsonObject = this as? JsonObject ?: EMPTY_OBJECT
