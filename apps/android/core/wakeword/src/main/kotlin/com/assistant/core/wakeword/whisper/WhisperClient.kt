package com.assistant.core.wakeword.whisper

import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.wakeword.WakeTuning
import com.assistant.core.wakeword.ports.OpenAiKeyProvider
import com.assistant.core.wakeword.ports.WhisperOutcome
import com.assistant.core.wakeword.ports.WhisperTranscriber
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * whisper-1 transcription straight to OpenAI (old `WhisperConfirmer.transcribe`, inv04 §4.4): the
 * device POSTs the clip directly for the lowest latency, with the key from
 * `GET /api/config/openai-key` cached for the process lifetime. A 401 clears the cache so the next
 * call re-fetches. Every failure is reported as a non-transcript outcome; the caller fails closed.
 *
 * The call is cancellable (okhttp `enqueue` + `Call.cancel`), so the caller's 10 s budget
 * ([WakeTuning.WHISPER_TIMEOUT_MS]) really ends the request.
 */
class WhisperClient(
    private val http: OkHttpClient,
    private val endpointUrl: String = WakeTuning.WHISPER_TRANSCRIPTIONS_URL,
    private val keys: OpenAiKeyProvider,
    private val log: VoiceLog,
) : WhisperTranscriber {
    @Volatile private var cachedKey: String? = null

    override suspend fun transcribe(wav: ByteArray): WhisperOutcome {
        val t0 = System.nanoTime()
        val key = ensureKey() ?: run {
            log.w(TAG, "No OpenAI key available — rejecting (fail-closed)")
            return WhisperOutcome.NoKey
        }
        val tKey = System.nanoTime()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", WakeTuning.WHISPER_UPLOAD_FILENAME, wav.toRequestBody("audio/wav".toMediaTypeOrNull()))
            .addFormDataPart("model", WakeTuning.WHISPER_MODEL)
            .addFormDataPart("response_format", WakeTuning.WHISPER_RESPONSE_FORMAT)
            .addFormDataPart("language", WakeTuning.WHISPER_LANGUAGE)
            .addFormDataPart("temperature", WakeTuning.WHISPER_TEMPERATURE)
            .build()
        val request = Request.Builder()
            .url(endpointUrl)
            .addHeader("Authorization", "Bearer $key")
            .post(body)
            .build()
        return try {
            http.newCall(request).await().use { response ->
                when {
                    response.code == 401 -> {
                        log.w(TAG, "Whisper 401 — clearing cached key")
                        cachedKey = null
                        WhisperOutcome.Unauthorized
                    }
                    !response.isSuccessful -> {
                        log.w(TAG, "Whisper HTTP ${response.code} — rejecting")
                        WhisperOutcome.HttpError(response.code)
                    }
                    else -> {
                        val text = parseText(response.body?.string())
                        val tHttp = System.nanoTime()
                        log.d(
                            TAG,
                            "Whisper timing: key=${ms(t0, tKey)}ms http=${ms(tKey, tHttp)}ms total=${ms(t0, tHttp)}ms (wav ${wav.size}B)",
                        )
                        text?.let { WhisperOutcome.Transcript(it) } ?: run {
                            log.w(TAG, "Whisper response was not a JSON object — rejecting")
                            WhisperOutcome.Failure("malformed response")
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(TAG, "Whisper call failed: ${e.message} — rejecting")
            WhisperOutcome.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun ensureKey(): String? {
        cachedKey?.let { return it }
        val fetched = try {
            keys.fetchKey()?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(TAG, "OpenAI key fetch failed: ${e.message}")
            null
        }
        if (fetched != null) cachedKey = fetched
        return fetched
    }

    /** `optString("text", "").trim()` of a JSON object; null when the body is not a JSON object. */
    private fun parseText(body: String?): String? {
        val obj = body?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        return ((obj["text"] as? JsonPrimitive)?.content ?: "").trim()
    }

    private fun ms(a: Long, b: Long) = (b - a) / 1_000_000

    private companion object {
        const val TAG = "WhisperConfirmer"
    }
}

/** Suspends until the response arrives; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        },
    )
}
