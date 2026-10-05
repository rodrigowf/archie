package com.assistant.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * High-frequency provider deltas that the **voice core** does not need (ported from
 * `old/network/WebSocketManager.kt:465-472`): Qwen streams ~50–100 per response and the
 * provider parsers only use the matching `*.done` events.
 *
 * This filter is for the voice-engine path only. The conversation reducer MUST still receive the
 * transcript deltas (spec 12 §4.7 streams voice text from them), so never apply it before
 * `ConversationReducer`.
 */
object VoiceEventFilter {
    val DROPPED_TYPES: Set<String> = setOf(
        "response.output_audio_transcript.delta",
        "response.audio_transcript.delta",
        "response.text.delta",
        "response.function_call_arguments.delta",
    )

    /** `type` of a provider event, or `null` for Gemini's type-less objects. */
    fun typeOf(event: JsonObject): String? = (event["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun isDropped(event: JsonObject): Boolean = typeOf(event) in DROPPED_TYPES
}
