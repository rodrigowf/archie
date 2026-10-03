package com.assistant.core.network

import com.assistant.core.protocol.ErrorDetailDto
import com.assistant.core.protocol.RestJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/** Shared plumbing of [ArchieApi], [VoiceApi], [UploadClient]: URL building, execution, error mapping. */
class RestCaller(
    private val stack: HttpStack,
    /** Current server URL as stored (any scheme); read on every call so a server switch applies at once. */
    private val serverUrl: () -> String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    fun url(path: String, extra: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        UrlScheme.httpBase(serverUrl()).toHttpUrl().newBuilder().apply {
            path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
            extra()
        }.build()

    suspend fun <T> call(request: Request, decode: (Response) -> T): ApiResult<T> = withContext(io) {
        val call = stack.restClient(serverUrl()).newCall(request)
        try {
            call.await().use { resp ->
                if (!resp.isSuccessful) return@withContext httpError(resp)
                try {
                    ApiResult.Ok(decode(resp))
                } catch (e: Exception) {
                    ApiResult.DecodeError(e)
                }
            }
        } catch (e: IOException) {
            e.untrustedCertificate()?.let { ApiResult.Untrusted(it) } ?: ApiResult.NetworkError(e)
        }
    }

    suspend inline fun <reified T> getJson(url: HttpUrl): ApiResult<T> =
        call(Request.Builder().url(url).get().build()) { RestJson.decodeFromString<T>(it.body!!.string()) }

    suspend inline fun <reified T> sendJson(method: String, url: HttpUrl, body: JsonElement?): ApiResult<T> =
        call(Request.Builder().url(url).method(method, jsonBody(body)).build()) { resp ->
            val text = resp.body?.string().orEmpty()
            if (T::class == Unit::class) Unit as T else RestJson.decodeFromString<T>(text)
        }

    fun jsonBody(body: JsonElement?): RequestBody =
        (body?.toString() ?: "").toRequestBody(JSON)

    fun emptyBody(): RequestBody = ByteArray(0).toRequestBody(null)

    private fun httpError(resp: Response): ApiResult.HttpError {
        val type = resp.header("Content-Type").orEmpty()
        val text = try {
            resp.body?.string().orEmpty()
        } catch (_: IOException) {
            ""
        }
        val isJson = type.contains("json", ignoreCase = true) || text.trimStart().startsWith("{")
        val detail = if (isJson) {
            try {
                RestJson.decodeFromString<ErrorDetailDto>(text).message()
            } catch (_: Exception) {
                null
            }
        } else null
        return ApiResult.HttpError(resp.code, detail, html = !isJson)
    }

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Cancellable OkHttp call: cancelling the coroutine cancels the HTTP call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resumeWith(Result.success(response))
        }
    })
}
