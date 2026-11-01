package com.assistant.archie.tv.voice

import com.assistant.core.audio.pcm.Pcm
import com.assistant.core.data.ConversationRepository
import com.assistant.core.network.ApiResult
import com.assistant.core.network.RestCaller
import com.assistant.core.network.SendResult
import com.assistant.core.network.SocketState
import com.assistant.core.protocol.ClientFrame
import com.assistant.core.session.OrchestratorChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Outcome of a voice message. */
sealed interface VoiceSendResult {
    data object Sent : VoiceSendResult
    data class Failed(val message: String) : VoiceSendResult
}

/** Sends a voice message (WAV) to the current Archie conversation. */
fun interface VoiceMessagePort {
    suspend fun send(wav: ByteArray): VoiceSendResult
}

/** Outcome of a dictation transcription. */
sealed interface TranscribeResult {
    data class Text(val text: String) : TranscribeResult
    data class Failed(val message: String) : TranscribeResult
}

/** `POST /api/transcribe` (wav → text, the server's Whisper key). */
fun interface Transcriber {
    suspend fun transcribe(wav: ByteArray): TranscribeResult
}

/**
 * Voice message to Archie (spec 15 §4.1): the same frame the phone's push-to-talk sends — the voice
 * host's `talkSender`: `ClientFrame.SendAudio(base64 WAV, "wav")` on the app's one orchestrator socket,
 * then `voiceMessageSent()` so the Archie timeline shows it as this device's voice message. Other
 * devices render it as `user_message{source:"voice_message"}`.
 *
 * With no Archie conversation open it first starts a new one — the client-minted id the phone's
 * voice start arms (`ConversationRepository.newArchie`) — and waits for its `session_started`.
 */
class OrchestratorVoiceMessages(
    private val channel: OrchestratorChannel,
    private val conversations: ConversationRepository,
    private val log: (String) -> Unit = {},
) : VoiceMessagePort {
    override suspend fun send(wav: ByteArray): VoiceSendResult {
        val st = channel.state.value
        if (st.socket != SocketState.Open) return VoiceSendResult.Failed("Not connected — voice message not sent")
        if (st.orchestrator == null) {
            log("no Archie conversation open — starting a new one for the voice message")
            conversations.newArchie()
        }
        val ready = withTimeoutOrNull(TvVoiceTuning.NEW_CONVERSATION_WAIT_MS) {
            channel.state.first { it.subscribed && it.orchestrator != null }
        }
        if (ready == null) return VoiceSendResult.Failed("Archie isn't ready — voice message not sent")
        val sent = channel.send(ClientFrame.SendAudio(Pcm.base64(wav), "wav"))
        return if (sent == SendResult.SENT) {
            conversations.voiceMessageSent()
            VoiceSendResult.Sent
        } else {
            log("voice message not sent ($sent)")
            VoiceSendResult.Failed("Voice message not sent — not connected")
        }
    }
}

/**
 * `POST /api/transcribe` (spec 15 §5.3) over the app's REST stack (same TLS pins / trust as every
 * other call). Multipart field `audio` = the WAV; returns `{text}`.
 */
class TranscribeClient(private val rest: RestCaller) : Transcriber {
    override suspend fun transcribe(wav: ByteArray): TranscribeResult {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", "dictation.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder().url(rest.url("/api/transcribe")).post(body).build()
        val r = rest.call(req) { resp ->
            Json.parseToJsonElement(resp.body!!.string()).jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
        }
        return when (r) {
            is ApiResult.Ok -> TranscribeResult.Text(r.value.trim())
            is ApiResult.HttpError -> TranscribeResult.Failed(
                when (r.code) {
                    404 -> "This server can't transcribe yet — update the backend"
                    413 -> "Too long to transcribe — keep dictation under 30 s"
                    503 -> "The server has no OpenAI key for transcription"
                    else -> "Transcription failed (${r.errorMessage()})"
                },
            )
            is ApiResult.NetworkError -> TranscribeResult.Failed("Not connected — transcription failed")
            else -> TranscribeResult.Failed("Transcription failed (${r.errorMessage()})")
        }
    }
}
