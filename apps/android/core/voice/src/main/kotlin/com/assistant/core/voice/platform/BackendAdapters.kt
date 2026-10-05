package com.assistant.core.voice.platform

import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.model.VoiceConfig
import com.assistant.core.network.ApiResult
import com.assistant.core.network.VoiceApi
import com.assistant.core.protocol.ServerFrame
import com.assistant.core.protocol.VoiceEventFilter
import com.assistant.core.voice.VoiceTuning
import com.assistant.core.voice.json.optString
import com.assistant.core.voice.json.string
import com.assistant.core.voice.ports.ConnectionType
import com.assistant.core.voice.ports.SdpExchange
import com.assistant.core.voice.ports.VoiceBackendApi
import com.assistant.core.voice.ports.VoiceConnection
import com.assistant.core.voice.ports.VoiceInbound
import com.assistant.core.voice.ports.VoiceStartConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** [VoiceBackendApi] over `:core:network`'s [VoiceApi] (the old `ApiClient` voice calls). */
class VoiceApiBackend(private val api: VoiceApi, private val log: VoiceLog) : VoiceBackendApi {
    /** On any error: openai / gpt-realtime / cedar, like the old `getVoiceConfig` (inv04 §2.1). */
    override suspend fun getVoiceConfig(): VoiceStartConfig {
        val cfg = when (val r = api.voiceConfig()) {
            is ApiResult.Ok -> r.value
            else -> {
                log.w(TAG, "GET /api/config failed (${r.errorMessage()}); using the default voice config")
                null
            }
        }
        return VoiceStartConfig(
            provider = cfg?.provider ?: VoiceTuning.DEFAULT_PROVIDER,
            model = cfg?.model ?: VoiceTuning.DEFAULT_MODEL,
            voice = cfg?.voice ?: VoiceTuning.DEFAULT_VOICE,
            transcriptionLanguage = cfg?.transcriptionLanguage.orEmpty(),
            endpoint = cfg?.endpoint.orEmpty(),
        )
    }

    override suspend fun startVoiceSession(config: VoiceStartConfig): VoiceConnection? {
        val request = VoiceConfig(
            provider = config.provider,
            model = config.model,
            voice = config.voice,
            transcriptionLanguage = config.transcriptionLanguage,
            endpoint = config.endpoint.takeIf { it.isNotBlank() },
        )
        return when (val r = api.startVoiceSession(request)) {
            is ApiResult.Ok -> r.value.let { info ->
                VoiceConnection(
                    connectionType = if (info.connectionType?.wire == "websocket") ConnectionType.WEBSOCKET else ConnectionType.WEBRTC,
                    endpoint = info.endpoint.orEmpty(),
                    ephemeralToken = info.ephemeralToken.orEmpty(),
                    model = info.model.orEmpty(),
                    voice = info.voice.orEmpty(),
                    inSampleRateHz = info.audioIn.sampleRate.takeIf { it > 0 } ?: VoiceTuning.DEFAULT_SAMPLE_RATE_HZ,
                    outSampleRateHz = info.audioOut.sampleRate.takeIf { it > 0 } ?: VoiceTuning.DEFAULT_SAMPLE_RATE_HZ,
                )
            }
            else -> {
                log.e(TAG, "POST /api/orchestrator/voice/session failed: ${r.errorMessage()}")
                null
            }
        }
    }

    private companion object {
        const val TAG = "VoiceApiBackend"
    }
}

/**
 * `POST <endpoint>`, `Content-Type: application/sdp`, `Authorization: Bearer <ephemeral>` → the
 * answer SDP, or null (logged with the status code) on any failure.
 */
class OkHttpSdpExchange(private val client: OkHttpClient, private val log: VoiceLog) : SdpExchange {
    override suspend fun exchange(endpoint: String, ephemeralToken: String, offerSdp: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .post(offerSdp.toRequestBody("application/sdp".toMediaType()))
                    .header("Authorization", "Bearer $ephemeralToken")
                    .header("Content-Type", "application/sdp")
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful) {
                        log.e(TAG, "SDP exchange failed: ${response.code} - ${body ?: "no body"}")
                        null
                    } else {
                        body
                    }
                }
            } catch (e: Exception) {
                log.e(TAG, "SDP exchange error", e)
                null
            }
        }

    private companion object {
        const val TAG = "OpenAIVoiceProvider"
    }
}

/**
 * Orchestrator [ServerFrame] → [VoiceInbound] (what the host feeds `VoiceSessionController.onInbound`).
 * Returns null for non-voice frames and for the high-rate deltas the voice core never needs
 * ([VoiceEventFilter], old `WebSocketManager.kt:465-472`); the conversation reducer gets those
 * frames separately.
 */
object VoiceFrames {
    fun toInbound(frame: ServerFrame): VoiceInbound? = when (frame) {
        // `voice_initiator` missing ⇒ false (`9b24d1a`, RS-11).
        is ServerFrame.SessionStarted -> VoiceInbound.SessionStarted(
            voice = frame.voice == true,
            voiceInitiator = frame.voiceInitiator == true,
            voiceSessionUpdate = frame.voiceSessionUpdate,
        )
        is ServerFrame.VoiceCommand -> frame.command?.let { VoiceInbound.Command(it) }
        is ServerFrame.VoiceEvent -> when {
            VoiceEventFilter.typeOf(frame.event) == VAD_STATE -> vad(frame.event)
            VoiceEventFilter.isDropped(frame.event) -> null
            else -> VoiceInbound.ProviderEvent(frame.event)
        }
        is ServerFrame.VoiceAudioOut -> VoiceInbound.AudioOut(frame.audio)
        is ServerFrame.VoiceEnding -> VoiceInbound.Ending(frame.reason)
        is ServerFrame.VoiceEnded -> VoiceInbound.Ended(frame.reason)
        is ServerFrame.VoiceStopped -> VoiceInbound.Stopped
        is ServerFrame.VoiceOwnerActive -> VoiceInbound.OwnerActive(frame.active, frame.ownerLocalId)
        is ServerFrame.Unknown -> if (frame.type == VAD_STATE) vad(frame.raw) else null
        else -> null
    }

    private fun vad(o: JsonObject) = VoiceInbound.VadState(
        state = o.string("state") ?: o.optString("state", "idle"),
        durationMs = (o["duration_ms"] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() } ?: 0L,
    )

    private const val VAD_STATE = "voice_vad_state"
}
