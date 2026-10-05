package com.assistant.core.network

/**
 * Outcome of every REST call. Errors are values, never swallowed into empty lists (inv03 §3.4).
 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /**
     * Non-2xx. [detail] is the backend `detail` text when the body was JSON (CFG-2: show verbatim).
     * [html] = the body was not JSON (e.g. nginx's 413 page, inv01 G-1).
     */
    data class HttpError(val code: Int, val detail: String?, val html: Boolean = false) : ApiResult<Nothing> {
        /** nginx rejected the body before the app saw it (1 MiB limit until BF-3 lands). */
        val uploadTooLarge: Boolean get() = code == 413
    }

    /** The server's certificate needs the user's trust (TOFU, spec 14 §4.3). */
    data class Untrusted(val error: UntrustedServerCertificateException) : ApiResult<Nothing>
    data class NetworkError(val cause: Throwable) : ApiResult<Nothing>
    data class DecodeError(val cause: Throwable) : ApiResult<Nothing>

    val isOk: Boolean get() = this is Ok

    fun getOrNull(): T? = (this as? Ok)?.value

    /** One-line, user-facing description. */
    fun errorMessage(): String? = when (this) {
        is Ok -> null
        is HttpError -> when {
            uploadTooLarge && html -> "File is larger than the server's 1 MB upload limit"
            detail != null -> detail
            else -> "Server error $code"
        }
        is Untrusted -> "The server's certificate is not trusted"
        is NetworkError -> cause.message ?: "Network error"
        is DecodeError -> "Unexpected response from the server"
    }
}

inline fun <T, R> ApiResult<T>.map(f: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(f(value))
    is ApiResult.HttpError -> this
    is ApiResult.Untrusted -> this
    is ApiResult.NetworkError -> this
    is ApiResult.DecodeError -> this
}
