package com.assistant.core.settings

import android.content.Context
import android.content.SharedPreferences

/** Minimal key-value seam over `SharedPreferences` (JVM tests use [MemoryKeyValue]). */
interface KeyValue {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getString(key: String, default: String): String
    fun getFloat(key: String, default: Float): Float
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putBoolean(key: String, v: Boolean)
        fun putString(key: String, v: String)
        fun putFloat(key: String, v: Float)
    }
}

class SharedPreferencesKeyValue(private val prefs: SharedPreferences) : KeyValue {
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun getString(key: String, default: String) = prefs.getString(key, default) ?: default
    override fun getFloat(key: String, default: Float) = prefs.getFloat(key, default)
    override fun edit(block: KeyValue.Editor.() -> Unit) {
        val e = prefs.edit()
        object : KeyValue.Editor {
            override fun putBoolean(key: String, v: Boolean) { e.putBoolean(key, v) }
            override fun putString(key: String, v: String) { e.putString(key, v) }
            override fun putFloat(key: String, v: Float) { e.putFloat(key, v) }
        }.block()
        e.apply()
    }
}

class MemoryKeyValue(val values: MutableMap<String, Any> = linkedMapOf()) : KeyValue {
    override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    override fun getString(key: String, default: String) = values[key] as? String ?: default
    override fun getFloat(key: String, default: Float) = values[key] as? Float ?: default
    override fun edit(block: KeyValue.Editor.() -> Unit) {
        object : KeyValue.Editor {
            override fun putBoolean(key: String, v: Boolean) { values[key] = v }
            override fun putString(key: String, v: String) { values[key] = v }
            override fun putFloat(key: String, v: Float) { values[key] = v }
        }.block()
    }
}

/** What the wake-word service persists so it survives process death (inv04 §4.5). */
data class WakeServicePrefs(
    val enabled: Boolean = false,
    val talkWord: String = "my friend",
    val wakeWord: String = "wake up",
    val wakeGain: Float = 1.0f,
    val talkSilenceSensitivity: Float = 2.0f,
    val serverUrl: String = "",
)

/**
 * Mirror of SharedPreferences `assistant_service_prefs` (inv04 §4.5, `AssistantService.kt:99-105`,
 * `ButtonAccessibilityService.kt:45-47`): same file, same keys, same defaults for absent keys.
 */
class ServicePrefs(private val kv: KeyValue) {
    fun loadWake(): WakeServicePrefs = WakeServicePrefs(
        enabled = kv.getBoolean(KEY_ENABLED, false),
        talkWord = kv.getString(KEY_TALK_WORD, "my friend"),
        wakeWord = kv.getString(KEY_WAKE_WORD, "wake up"),
        wakeGain = kv.getFloat(KEY_WAKE_MIC_GAIN, 1.0f),
        talkSilenceSensitivity = kv.getFloat(KEY_TALK_SILENCE_SENSITIVITY, 2.0f),
        serverUrl = kv.getString(KEY_SERVER_URL, ""),
    )

    fun saveWake(p: WakeServicePrefs) = kv.edit {
        putBoolean(KEY_ENABLED, p.enabled)
        putString(KEY_TALK_WORD, p.talkWord)
        putString(KEY_WAKE_WORD, p.wakeWord)
        putFloat(KEY_WAKE_MIC_GAIN, p.wakeGain)
        putFloat(KEY_TALK_SILENCE_SENSITIVITY, p.talkSilenceSensitivity)
        putString(KEY_SERVER_URL, p.serverUrl)
    }

    var buttonTriggerEnabled: Boolean
        get() = kv.getBoolean(KEY_BUTTON_TRIGGER, false)
        set(v) = kv.edit { putBoolean(KEY_BUTTON_TRIGGER, v) }

    companion object {
        const val FILE_NAME = "assistant_service_prefs"
        const val KEY_ENABLED = "wake_word_enabled"
        const val KEY_TALK_WORD = "turn_talk_word"
        const val KEY_WAKE_WORD = "realtime_wake_word"
        const val KEY_WAKE_MIC_GAIN = "wake_word_mic_gain"
        const val KEY_TALK_SILENCE_SENSITIVITY = "talk_silence_sensitivity"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_BUTTON_TRIGGER = "button_trigger_enabled"

        fun create(context: Context) = ServicePrefs(
            SharedPreferencesKeyValue(context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)),
        )
    }
}
