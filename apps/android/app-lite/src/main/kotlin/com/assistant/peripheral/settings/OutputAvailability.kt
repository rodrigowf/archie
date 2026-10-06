package com.assistant.peripheral.settings

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import com.assistant.core.audio.DefaultAudioCore
import com.assistant.core.audio.platform.AndroidAudioManagerPort
import com.assistant.core.audio.platform.LogcatVoiceLog
import com.assistant.core.audio.ports.AvailableOutputs

/**
 * Which outputs exist right now, for the Output choice (spec 14 §5.3). Detection is
 * `:core:audio`'s (API 21-safe: no `getDevices` below M, RS-25); it is refreshed live while the
 * Settings view is shown by the headset-plug and Bluetooth broadcasts, because Lollipop has no
 * `AudioDeviceCallback`.
 */
class OutputAvailability(private val context: Context) {
    private val applier = DefaultAudioCore().routeApplier(AndroidAudioManagerPort(context.applicationContext), Build.VERSION.SDK_INT, LogcatVoiceLog)
    private var receiver: BroadcastReceiver? = null

    fun current(): AvailableOutputs = try {
        applier.availableOutputs()
    } catch (_: Exception) {
        EMPTY
    }

    fun watch(onChange: (AvailableOutputs) -> Unit) {
        unwatch()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = onChange(current())
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        }
        @Suppress("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED) else context.registerReceiver(r, filter)
        receiver = r
        onChange(current())
    }

    fun unwatch() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    companion object {
        val EMPTY = AvailableOutputs(bluetoothCallAudio = false, bluetoothMedia = false, wired = false)
    }
}
