package com.assistant.core.network

import com.assistant.core.model.ConnectionInfo
import com.assistant.core.model.VoiceConfig
import com.assistant.core.protocol.ConfigDto
import com.assistant.core.protocol.OpenAiKeyDto
import com.assistant.core.protocol.VoiceSessionDto
import com.assistant.core.protocol.toModel

/** The three voice REST calls (spec 14 §1.2), used by the voice core and the Whisper gate. */
class VoiceApi(private val rest: RestCaller) {
    constructor(stack: HttpStack, serverUrl: () -> String) : this(RestCaller(stack, serverUrl))

    /** `default_voice_*` from `GET /api/config` (inv03 §3.4 `getVoiceConfig`). */
    suspend fun voiceConfig(): ApiResult<VoiceConfig> =
        rest.getJson<ConfigDto>(rest.url("/api/config")).map { it.toModel().voice }

    /**
     * `POST /api/orchestrator/voice/session` with **query** parameters (not a body). Null fields are
     * omitted so the server applies its defaults (V-1).
     */
    suspend fun startVoiceSession(config: VoiceConfig): ApiResult<ConnectionInfo> {
        val url = rest.url("/api/orchestrator/voice/session") {
            config.provider?.let { addQueryParameter("provider", it) }
            config.model?.let { addQueryParameter("model", it) }
            config.voice?.let { addQueryParameter("voice", it) }
            config.transcriptionLanguage?.let { addQueryParameter("transcription_language", it) }
            config.endpoint?.let { addQueryParameter("endpoint", it) }
        }
        return when (val r = rest.sendJson<VoiceSessionDto>("POST", url, null)) {
            is ApiResult.Ok -> r.value.connectionInfo?.let { ApiResult.Ok(it.toModel()) }
                ?: ApiResult.DecodeError(IllegalStateException("connection_info missing"))
            else -> r.map { error("unreachable") }
        }
    }

    /** `GET /api/config/openai-key` (Whisper confirm on the device, inv04 §4.4). */
    suspend fun openAiKey(): ApiResult<String> =
        rest.getJson<OpenAiKeyDto>(rest.url("/api/config/openai-key")).map { it.apiKey }
}
