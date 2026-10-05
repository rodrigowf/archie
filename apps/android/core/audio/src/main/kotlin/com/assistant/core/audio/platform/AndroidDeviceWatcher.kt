package com.assistant.core.audio.platform

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Build
import com.assistant.core.audio.policy.DefaultPlaybackPolicy
import com.assistant.core.audio.ports.PlaybackPolicy
import com.assistant.core.audio.ports.VoiceLog

/**
 * Re-runs routing when an output device is added or removed mid-session (old
 * `VoiceManager.registerDeviceCallback`, :611-635). API 23+ only ([PlaybackPolicy] `hasDeviceCallback`);
 * on Lollipop there is no callback and routing is only re-applied on the session timers.
 */
class AndroidDeviceWatcher(
    private val audioManager: AndroidAudioManagerPort,
    private val sdkInt: Int,
    private val log: VoiceLog,
    private val playbackPolicy: PlaybackPolicy = DefaultPlaybackPolicy,
) {
    private var callback: Any? = null // AudioDeviceCallback on M+

    fun start(onChange: () -> Unit) {
        if (!playbackPolicy.forSdk(sdkInt).hasDeviceCallback || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (callback != null) return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                log.d(TAG, "[ROUTE] device added — re-applying routing")
                onChange()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                log.d(TAG, "[ROUTE] device removed — re-applying routing")
                onChange()
            }
        }
        audioManager.platform.registerAudioDeviceCallback(cb, null)
        callback = cb
    }

    fun stop() {
        val cb = callback ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && cb is AudioDeviceCallback) {
                audioManager.platform.unregisterAudioDeviceCallback(cb)
            }
        } catch (_: Exception) {}
        callback = null
    }

    private companion object {
        const val TAG = "VoiceManager"
    }
}
