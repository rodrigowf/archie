package com.assistant.peripheral.settings

import com.assistant.core.model.AudioOutput
import com.assistant.core.model.DeviceSettings
import com.assistant.core.settings.SettingsStore
import com.assistant.core.voicehost.VoiceHost
import com.assistant.core.voicehost.VoiceHostSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Settings actions of the lite Settings view (spec 14 §5.3). Every change goes through
 * [VoiceHost.updateSettings] first (single ingress, inv04 RS-33: gains always explicit), then is
 * persisted in [SettingsStore]; the store's re-emission is a no-op for the host (equal settings).
 * Nothing here runs before the real settings loaded ([current] null → ignored, fixes inv04 B4).
 */
class LiteSettings(
    private val scope: CoroutineScope,
    private val store: SettingsStore,
    private val host: VoiceHost,
    private val current: () -> DeviceSettings? = { store.settings.value },
) {
    fun setServer(url: String) = change({ it.copy(serverUrl = url.trim()) }) { setServerUrl(url) }
    fun setAutoConnect(v: Boolean) = change({ it.copy(autoConnect = v) }) { setAutoConnect(v) }
    fun setWakeEnabled(v: Boolean) = change({ it.copy(enableWakeWord = v) }) { setEnableWakeWord(v) }

    /** Phrases commit on IME Done / focus loss (no Save buttons, inv03 §8). Blank is ignored. */
    fun setWakePhrases(v: String) {
        val t = normalizePhrases(v) ?: return
        change({ it.copy(wakeWord = t) }) { setWakeWord(t) }
    }

    fun setTalkPhrases(v: String) {
        val t = normalizePhrases(v) ?: return
        change({ it.copy(talkWord = t) }) { setTalkWord(t) }
    }

    fun setMicGainStep(step: Int) = (Steps.gainOf(step)).let { v -> change({ it.copy(micGainLevel = v) }) { setMicGainLevel(v) } }
    fun setWakeSensitivityStep(step: Int) = Steps.gainOf(step).let { v -> change({ it.copy(wakeWordMicGainLevel = v) }) { setWakeWordMicGainLevel(v) } }
    fun setEchoDuckStep(step: Int) = Steps.duckOf(step).let { v -> change({ it.copy(echoDuckingGain = v) }) { setEchoDuckingGain(v) } }
    fun setTalkStopStep(step: Int) = Steps.talkStopOf(step).let { v -> change({ it.copy(talkSilenceSensitivity = v) }) { setTalkSilenceSensitivity(v) } }
    fun setOutput(v: AudioOutput) = change({ it.copy(audioOutput = v) }) { setAudioOutput(v) }
    fun setRecentsTrigger(v: Boolean) = change({ it.copy(enableButtonTrigger = v) }) { setEnableButtonTrigger(v) }

    fun addServer(label: String, url: String) {
        if (label.isBlank() || url.isBlank()) return
        scope.launch { store.addSavedServer(label, url) }
    }

    fun removeServer(url: String) {
        scope.launch { store.removeSavedServer(url) }
    }

    private val lock = Any()
    private var inFlight = 0

    /** What the host was last given while writes are still in flight (the store lags behind them). */
    private var overlay: DeviceSettings? = null

    private fun change(transform: (DeviceSettings) -> DeviceSettings, persist: suspend SettingsStore.() -> Unit) {
        val next = synchronized(lock) {
            // Base on the newest values: a quick second change must not resurrect the first one's
            // old value in the host (the persisted store catches up asynchronously).
            val cur = (if (inFlight > 0) overlay else null) ?: current() ?: return
            val n = transform(cur)
            if (n == cur) return
            overlay = n
            inFlight++
            n
        }
        host.updateSettings(VoiceHostSettings.from(next))
        scope.launch {
            try {
                store.persist() // each setter writes only its own key, so concurrent writes compose
            } finally {
                synchronized(lock) { if (--inFlight == 0) overlay = null }
            }
        }
    }

    companion object {
        fun normalizePhrases(raw: String): String? {
            val parts = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            return if (parts.isEmpty()) null else parts.joinToString(", ")
        }
    }
}

/** The old slider grids (`SettingsScreen.kt:311, 342, 515`). */
object Steps {
    /** Mic gain / wake sensitivity: 0–150 % in 10 % steps → 16 positions. */
    const val GAIN_MAX = 15
    /** Echo ducking: 0.0–10.0 % in 0.5 % steps → 21 positions. */
    const val DUCK_MAX = 20
    /** Talk auto-stop: 1.0–4.0 × in 0.5 steps → 7 positions. */
    const val TALK_STOP_MAX = 6

    fun gainOf(step: Int) = step.coerceIn(0, GAIN_MAX) / 10f
    fun gainStep(v: Float) = (v * 10f).roundToInt().coerceIn(0, GAIN_MAX)
    fun duckOf(step: Int) = step.coerceIn(0, DUCK_MAX) * 0.005f
    fun duckStep(v: Float) = (v / 0.005f).roundToInt().coerceIn(0, DUCK_MAX)
    fun talkStopOf(step: Int) = 1f + step.coerceIn(0, TALK_STOP_MAX) * 0.5f
    fun talkStopStep(v: Float) = ((v - 1f) / 0.5f).roundToInt().coerceIn(0, TALK_STOP_MAX)

    fun gainLabel(step: Int) = "${step.coerceIn(0, GAIN_MAX) * 10}%"
    fun duckLabel(step: Int) = "%.1f%%".format(step.coerceIn(0, DUCK_MAX) * 0.5f)
    fun talkStopLabel(step: Int) = "%.1f×".format(talkStopOf(step))
}
