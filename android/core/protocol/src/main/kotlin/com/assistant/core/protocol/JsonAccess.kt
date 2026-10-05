package com.assistant.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Lenient field access on decoded frames. A wrong type never throws: it reads as `null`
 * (the reducer then applies its documented default). This is what keeps one malformed
 * field from dropping a whole frame.
 */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(key: String): Long? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p.isString) return null
    return p.longOrNull ?: p.doubleOrNull?.takeIf { it % 1.0 == 0.0 }?.toLong()
}

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

internal fun JsonObject.double(key: String): Double? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p.isString) return null
    return p.doubleOrNull
}

internal fun JsonObject.bool(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p.isString) return null
    return p.booleanOrNull
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** The element if present and not JSON `null`. */
internal fun JsonObject.present(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

/** Builder used by the encoders: skips `null` values so absent and null read the same. */
internal class JsonObjectBuilder {
    private val map = LinkedHashMap<String, JsonElement>()

    fun put(key: String, value: String?) { if (value != null) map[key] = JsonPrimitive(value) }
    fun put(key: String, value: Number?) { if (value != null) map[key] = JsonPrimitive(value) }
    fun put(key: String, value: Boolean?) { if (value != null) map[key] = JsonPrimitive(value) }
    fun put(key: String, value: JsonElement?) { if (value != null) map[key] = value }

    /** Writes an explicit JSON `null` (only where the wire needs one). */
    fun putNull(key: String) { map[key] = JsonNull }

    fun build() = JsonObject(map)
}

internal inline fun jsonObject(block: JsonObjectBuilder.() -> Unit): JsonObject =
    JsonObjectBuilder().apply(block).build()
