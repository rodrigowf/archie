package com.assistant.archie.tv.graph

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.assistant.archie.tv.theme.TvTextSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** TV-only device preferences (Settings → Display, first run). Shared settings stay in `SettingsStore`. */
data class TvDisplayPrefs(
    val textSize: TvTextSize = TvTextSize.NORMAL,
    val showKeyHints: Boolean = true,
    val reduceMotion: Boolean = false,
)

/**
 * A tiny SharedPreferences store (synchronous reads: the first frame needs `setupDone` to pick the
 * back stack root). Everything the phone apps share lives in `:core:settings`, not here.
 */
class TvPrefs(private val prefs: SharedPreferences) {
    private val _display = MutableStateFlow(readDisplay())
    val display: StateFlow<TvDisplayPrefs> = _display.asStateFlow()

    /** First run finished (Connect → Home), spec 15 §3.6. */
    var setupDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(v) { prefs.edit { putBoolean(KEY_SETUP_DONE, v) } }

    fun setTextSize(v: TvTextSize) = write { putString(KEY_TEXT_SIZE, v.name) }
    fun setShowKeyHints(v: Boolean) = write { putBoolean(KEY_SHOW_HINTS, v) }
    fun setReduceMotion(v: Boolean) = write { putBoolean(KEY_REDUCE_MOTION, v) }

    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit(action = block)
        _display.value = readDisplay()
    }

    private fun readDisplay() = TvDisplayPrefs(
        textSize = prefs.getString(KEY_TEXT_SIZE, null)?.let { n -> TvTextSize.entries.firstOrNull { it.name == n } } ?: TvTextSize.NORMAL,
        showKeyHints = prefs.getBoolean(KEY_SHOW_HINTS, true),
        reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, false),
    )

    companion object {
        const val FILE = "archie_tv"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_SHOW_HINTS = "show_key_hints"
        private const val KEY_REDUCE_MOTION = "reduce_motion"

        fun create(context: Context) = TvPrefs(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
