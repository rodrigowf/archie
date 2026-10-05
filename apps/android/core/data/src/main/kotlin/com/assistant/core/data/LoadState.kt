package com.assistant.core.data

import com.assistant.core.network.ApiResult

/**
 * A pull-only resource (session list, memory tree, visuals, server config). [value] survives a
 * failed refresh so the UI keeps showing the last good copy; [error] is the user-facing one-liner
 * of the last failure (backend `detail` verbatim, CFG-2).
 */
data class LoadState<out T>(
    val value: T? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val loaded: Boolean get() = value != null

    fun loading(): LoadState<T> = copy(loading = true)

    companion object {
        fun <T> of(result: ApiResult<T>, previous: LoadState<T>): LoadState<T> = when (result) {
            is ApiResult.Ok -> LoadState(result.value, loading = false, error = null)
            else -> previous.copy(loading = false, error = result.errorMessage())
        }
    }
}
