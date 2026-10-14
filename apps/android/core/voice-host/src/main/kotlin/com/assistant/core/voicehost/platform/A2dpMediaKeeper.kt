package com.assistant.core.voicehost.platform

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.assistant.core.audio.DefaultAudioCore
import com.assistant.core.audio.platform.AndroidA2dpMediaRoute
import com.assistant.core.audio.platform.AndroidAudioManagerPort
import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.audio.routing.DefaultRouteDecider
import com.assistant.core.voicehost.VoiceHostSettings
import com.assistant.core.voicehost.toChoice

/**
 * Keeps media (Archie's voice on the WS providers, and the cues) on the Bluetooth speaker while a
 * headset-style wired mic is plugged in, when the settings ask for it
 * ([DefaultRouteDecider.wantsA2dpMedia]). Plugging the headset makes Android move media to the
 * jack, so this re-checks on every plug / Bluetooth change, not only when a call starts.
 */
class A2dpMediaKeeper(context: Context, sdkInt: Int, private val log: VoiceLog) {
    private val app = context.applicationContext
    private val applier = DefaultAudioCore().routeApplier(AndroidAudioManagerPort(app), sdkInt, log)
    private val route = AndroidA2dpMediaRoute(app, log)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var settings: VoiceHostSettings? = null
    private var receiver: BroadcastReceiver? = null
    private var lastWant: Boolean? = null // main thread only

    /** True while media is held on Bluetooth beside a plugged headset (the cues follow it). */
    @Volatile var holdingBluetooth: Boolean = false
        private set

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            // AudioService applies its own plug policy around this broadcast: decide a moment later.
            override fun onReceive(c: Context?, intent: Intent?) {
                main.removeCallbacks(evaluate)
                main.postDelayed(evaluate, SETTLE_MS)
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        @Suppress("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, filter, Context.RECEIVER_EXPORTED) else app.registerReceiver(r, filter)
        receiver = r
    }

    fun update(s: VoiceHostSettings) {
        settings = s
        main.post(evaluate)
    }

    private val evaluate = Runnable {
        val s = settings ?: return@Runnable
        val available = try { applier.availableOutputs() } catch (e: Exception) {
            log.w(TAG, "A2DP media: output detection failed: ${e.message}")
            return@Runnable
        }
        val want = DefaultRouteDecider.wantsA2dpMedia(s.audioOutput.toChoice(), s.audioInput.toChoice(), available)
        if (want != lastWant) {
            lastWant = want
            log.i(TAG, "[ROUTE] A2DP media wanted=$want (output=${s.audioOutput} input=${s.audioInput} a2dp=${available.bluetoothMedia} plug=${available.wired})")
        }
        holdingBluetooth = want
        route.prefer(want)
    }

    private companion object {
        const val TAG = "AudioRouter"
        const val SETTLE_MS = 500L
    }
}
