package com.assistant.core.network

import com.assistant.core.model.UploadResult
import com.assistant.core.protocol.RestJson
import com.assistant.core.protocol.UploadResultDto
import com.assistant.core.protocol.toModel
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.Source

/** A file to upload, opened lazily and streamed (never read whole into memory, inv03 §3.4). */
class UploadSource(
    val fileName: String,
    val contentType: String?,
    /** Bytes, or -1 when unknown (chunked). */
    val contentLength: Long,
    val open: () -> Source,
)

/** `POST /api/uploads` (multipart field `file`, inv01 §3.5; spec 12 §6.15). */
class UploadClient(private val rest: RestCaller) {
    constructor(stack: HttpStack, serverUrl: () -> String) : this(RestCaller(stack, serverUrl))

    /**
     * Streams [source] with progress callbacks (bytes written, total or -1). nginx's HTML 413 above
     * 1 MiB comes back as `HttpError(413, html = true)` → "File is larger than the server's 1 MB upload limit".
     */
    suspend fun upload(source: UploadSource, onProgress: (Long, Long) -> Unit = { _, _ -> }): ApiResult<UploadResult> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", source.fileName, StreamingBody(source, onProgress))
            .build()
        val req = Request.Builder().url(rest.url("/api/uploads")).post(body).build()
        return rest.call(req) { RestJson.decodeFromString<UploadResultDto>(it.body!!.string()).toModel() }
    }

    private class StreamingBody(private val src: UploadSource, private val onProgress: (Long, Long) -> Unit) : RequestBody() {
        override fun contentType(): MediaType? = (src.contentType ?: "application/octet-stream").toMediaTypeOrNull()
        override fun contentLength(): Long = src.contentLength

        override fun writeTo(sink: BufferedSink) {
            src.open().use { input ->
                val buf = Buffer()
                var written = 0L
                while (true) {
                    val n = input.read(buf, CHUNK)
                    if (n == -1L) break
                    sink.write(buf, n)
                    written += n
                    onProgress(written, src.contentLength)
                }
            }
        }
    }

    private companion object {
        const val CHUNK = 64L * 1024
    }
}
