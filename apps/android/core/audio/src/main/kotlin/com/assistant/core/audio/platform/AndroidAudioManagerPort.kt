package com.assistant.core.audio.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import com.assistant.core.audio.ports.AudioDeviceRef
import com.assistant.core.audio.ports.AudioManagerPort
import com.assistant.core.audio.ports.AudioMode
import com.assistant.core.audio.ports.AudioStream

/**
 * 1:1 delegation to `AudioManager` + `BluetoothAdapter` profile state + the sticky
 * `ACTION_HEADSET_PLUG` (old `AudioRouter.kt:260-347`). All branching lives in
 * [com.assistant.core.audio.routing.PolicyRouteApplier]; the `Build.VERSION` checks here only
 * make an out-of-contract call harmless (empty list / false / no-op) instead of a
 * `NoSuchMethodError`, and keep lint's NewApi check satisfied.
 *
 * The Bluetooth profile queries deliberately let `SecurityException` through (missing
 * BLUETOOTH_CONNECT on API 31+, `ef2aaae`); the applier treats it as "not connected".
 */
@Suppress("DEPRECATION")
class AndroidAudioManagerPort(private val context: Context) : AudioManagerPort {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override var mode: AudioMode
        get() = AndroidAudioTypes.audioMode(audioManager.mode)
        set(value) { audioManager.mode = AndroidAudioTypes.platformMode(value) }

    override var isSpeakerphoneOn: Boolean
        get() = audioManager.isSpeakerphoneOn
        set(value) { audioManager.isSpeakerphoneOn = value }

    override var isBluetoothScoOn: Boolean
        get() = audioManager.isBluetoothScoOn
        set(value) { audioManager.isBluetoothScoOn = value }

    override fun startBluetoothSco() = audioManager.startBluetoothSco()

    override fun stopBluetoothSco() = audioManager.stopBluetoothSco()

    override fun outputDevices(): List<AudioDeviceRef> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map {
            AudioDeviceRef(it.id, AndroidAudioTypes.deviceType(it.type), it.productName?.toString().orEmpty())
        }
    }

    override fun availableCommunicationDevices(): List<AudioDeviceRef> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return audioManager.availableCommunicationDevices.map {
            AudioDeviceRef(it.id, AndroidAudioTypes.deviceType(it.type), it.productName?.toString().orEmpty())
        }
    }

    override fun setCommunicationDevice(device: AudioDeviceRef): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val info = audioManager.availableCommunicationDevices.firstOrNull { it.id == device.id } ?: return false
        return audioManager.setCommunicationDevice(info)
    }

    override fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        audioManager.clearCommunicationDevice()
    }

    override fun isBluetoothHeadsetProfileConnected(): Boolean = profileConnected(BluetoothProfile.HEADSET)

    override fun isBluetoothA2dpProfileConnected(): Boolean = profileConnected(BluetoothProfile.A2DP)

    @SuppressLint("MissingPermission") // SecurityException is part of the port contract (API 31+).
    private fun profileConnected(profile: Int): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        if (!adapter.isEnabled) return false
        return adapter.getProfileConnectionState(profile) == BluetoothAdapter.STATE_CONNECTED
    }

    /** The OS retains the last HEADSET_PLUG; `registerReceiver(null, …)` returns it without subscribing. */
    override fun isWiredHeadsetPlugged(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_HEADSET_PLUG)) ?: return false
        return intent.getIntExtra("state", 0) == 1
    }

    override fun streamVolume(stream: AudioStream): Int = audioManager.getStreamVolume(AndroidAudioTypes.streamType(stream))

    override fun streamMaxVolume(stream: AudioStream): Int = audioManager.getStreamMaxVolume(AndroidAudioTypes.streamType(stream))

    override fun setStreamVolume(stream: AudioStream, index: Int) =
        audioManager.setStreamVolume(AndroidAudioTypes.streamType(stream), index, 0)

    /** For [AndroidAudioFocus] and [AndroidDeviceWatcher], which need the platform object. */
    internal val platform: AudioManager get() = audioManager
}
