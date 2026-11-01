package com.assistant.archie.tv.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** One top-center toast (spec 15 §3.4). */
data class TvToast(val id: Long, val text: String, val error: Boolean = false, val seconds: Int = DEFAULT_SECONDS) {
    companion object {
        const val DEFAULT_SECONDS = 3
    }
}

/**
 * The app's toast queue (process-scoped, in the graph): the newest toast replaces the one on screen.
 * `TvToastHost` shows [current] and calls [dismiss] after its time. Any thread may call [show].
 */
class TvToasts {
    private val ids = AtomicLong()
    private val _current = MutableStateFlow<TvToast?>(null)
    val current: StateFlow<TvToast?> = _current.asStateFlow()

    fun show(text: String, error: Boolean = false, seconds: Int = TvToast.DEFAULT_SECONDS): TvToast =
        TvToast(ids.incrementAndGet(), text, error, seconds).also { _current.value = it }

    /** Removes [id] if it is still the one showing (a newer toast is left alone). */
    fun dismiss(id: Long) = _current.update { if (it?.id == id) null else it }
}
