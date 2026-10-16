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

    // ── T3: TV control channel (spec 15 §3.5 Settings → TV control, §5). The Settings UI comes with T5. ──
    /** Stable per-install id the control socket registers with (spec 12 RD-1); created on first read. */
    val controlDeviceId: String
        @Synchronized get() = prefs.getString(T3_DEVICE_ID, null)
            ?: java.util.UUID.randomUUID().toString().also { id -> prefs.edit { putString(T3_DEVICE_ID, id) } }

    /** "Device name (shown to agents)"; null = the system device name. */
    var controlDeviceName: String?
        get() = prefs.getString(T3_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
        set(v) { prefs.edit { putString(T3_DEVICE_NAME, v?.trim()) } }

    /** The server's TV_CONTROL_TOKEN, when it requires one; null = none. */
    var controlToken: String?
        get() = prefs.getString(T3_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(v) { prefs.edit { putString(T3_TOKEN, v?.trim()) } }

    /** "Allow agents to open pages" (default on). */
    var allowAgentsToOpen: Boolean
        get() = prefs.getBoolean(T3_ALLOW_OPEN, true)
        set(v) { prefs.edit { putBoolean(T3_ALLOW_OPEN, v) } }

    /** "Show 'Opened by Archie' toasts" (default on). */
    var showOpenedToasts: Boolean
        get() = prefs.getBoolean(T3_OPENED_TOASTS, true)
        set(v) { prefs.edit { putBoolean(T3_OPENED_TOASTS, v) } }
    // ── end T3 ──

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

// ── T3: TV control preference keys ──
private const val T3_DEVICE_ID = "tv_control_device_id"
private const val T3_DEVICE_NAME = "tv_control_device_name"
private const val T3_TOKEN = "tv_control_token"
private const val T3_ALLOW_OPEN = "tv_control_allow_open"
private const val T3_OPENED_TOASTS = "tv_control_opened_toasts"
// ── end T3 ──
