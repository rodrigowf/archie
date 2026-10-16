package com.assistant.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A picture a tool returned (spec 12 R-10), e.g. a Claude `Read` of a `.png`. [url] is server-relative
 * (`/api/tool-images/<sha256>.<ext>`, immutable): resolve it against the backend the app talks to.
 */
data class ToolImage(val url: String, val mediaType: String = "")

/**
 * `normalizeImages` (spec 12 §4.3, R-10): only well-formed entries (an object with a non-empty string
 * `url`) survive; a missing or non-string `media_type` reads as `""`. Anything that is not an array
 * (absent, `null` from REST, a wrong type) is no images.
 */
fun normalizeImages(x: JsonElement?): List<ToolImage> {
    val arr = x as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val url = o.str("url")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        ToolImage(url, o.str("media_type") ?: "")
    }
}

/** The wire shape `[{url, media_type}]` (the encoder's inverse of [normalizeImages]). */
fun List<ToolImage>.toWireJson(): JsonArray = buildJsonArray {
    for (i in this@toWireJson) add(buildJsonObject { put("url", i.url); put("media_type", i.mediaType) })
}

