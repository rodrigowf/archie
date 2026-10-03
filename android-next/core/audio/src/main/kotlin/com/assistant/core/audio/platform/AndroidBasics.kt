package com.assistant.core.audio.platform

import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import com.assistant.core.audio.ports.AudioMode
import com.assistant.core.audio.ports.AudioStream
import com.assistant.core.audio.ports.DeviceType
import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.audio.ports.VoiceLog

/*
 * Thin Android bindings shared by the :core:audio adapters. No decisions live here.
 */

/** Production [MonotonicClock]: `SystemClock.elapsedRealtime()`. */
object ElapsedRealtimeClock : MonotonicClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

/** Production [VoiceLog] over logcat, so the inv04 §10.3 markers stay greppable. */
object LogcatVoiceLog : VoiceLog {
    override fun d(tag: String, message: String) { Log.d(tag, message) }
    override fun i(tag: String, message: String) { Log.i(tag, message) }
    override fun w(tag: String, message: String) { Log.w(tag, message) }
    override fun e(tag: String, message: String, error: Throwable?) { Log.e(tag, message, error) }
}

internal object AndroidAudioTypes {
    fun streamType(stream: AudioStream): Int = when (stream) {
        AudioStream.VOICE_CALL -> AudioManager.STREAM_VOICE_CALL
        AudioStream.MUSIC -> AudioManager.STREAM_MUSIC
        AudioStream.RING -> AudioManager.STREAM_RING
        AudioStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        AudioStream.SYSTEM -> AudioManager.STREAM_SYSTEM
    }

    /**
     * Only NORMAL and IN_COMMUNICATION are ever written. Any other platform mode (ringtone, a real
     * phone call) reads as IN_COMMUNICATION, so a NORMAL target is still written over it.
     */
    fun audioMode(platform: Int): AudioMode =
        if (platform == AudioManager.MODE_NORMAL) AudioMode.NORMAL else AudioMode.IN_COMMUNICATION

    fun platformMode(mode: AudioMode): Int = when (mode) {
        AudioMode.NORMAL -> AudioManager.MODE_NORMAL
        AudioMode.IN_COMMUNICATION -> AudioManager.MODE_IN_COMMUNICATION
    }

    /** `AudioDeviceInfo.TYPE_*` values (literal so the mapping is API-independent; they never change). */
    fun deviceType(platformType: Int): DeviceType = when (platformType) {
        1 -> DeviceType.BUILTIN_EARPIECE // TYPE_BUILTIN_EARPIECE
        2 -> DeviceType.BUILTIN_SPEAKER // TYPE_BUILTIN_SPEAKER
        3 -> DeviceType.WIRED_HEADSET // TYPE_WIRED_HEADSET
        4 -> DeviceType.WIRED_HEADPHONES // TYPE_WIRED_HEADPHONES
        7 -> DeviceType.BLUETOOTH_SCO // TYPE_BLUETOOTH_SCO
        8 -> DeviceType.BLUETOOTH_A2DP // TYPE_BLUETOOTH_A2DP
        22 -> DeviceType.USB_HEADSET // TYPE_USB_HEADSET (API 26)
        26 -> DeviceType.BLE_HEADSET // TYPE_BLE_HEADSET (API 31)
        27 -> DeviceType.BLE_SPEAKER // TYPE_BLE_SPEAKER (API 31)
        else -> DeviceType.OTHER
    }
}
