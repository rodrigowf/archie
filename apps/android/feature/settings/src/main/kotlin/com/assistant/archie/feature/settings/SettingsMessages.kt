package com.assistant.archie.feature.settings

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One snackbar (IA §7: every save shows "Saved", or the server's error **verbatim** + Retry).
 * [retry] re-runs the failed save.
 */
data class SettingsMessage(
    val text: String,
    val error: Boolean = false,
    val actionLabel: String? = null,
    val retry: (() -> Unit)? = null,
) {
    companion object {
        const val SAVED = "Saved"
        fun saved() = SettingsMessage(SAVED)
        fun failed(detail: String, retry: () -> Unit) = SettingsMessage(detail, error = true, actionLabel = "Retry", retry = retry)
    }
}

/** The settings snackbar stream; the visible settings surface shows them. */
class SettingsMessages {
    private val flow = MutableSharedFlow<SettingsMessage>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<SettingsMessage> = flow.asSharedFlow()

    fun post(m: SettingsMessage) { flow.tryEmit(m) }
    fun saved() = post(SettingsMessage.saved())
}
