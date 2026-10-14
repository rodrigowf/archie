package com.assistant.core.audio.platform

import android.content.Context
import android.media.MediaRouter
import android.os.Handler
import android.os.Looper
import com.assistant.core.audio.ports.VoiceLog

/**
 * Moves media playback back to Bluetooth A2DP while a headset is plugged in.
 *
 * Plugging a headset makes Android force media off A2DP (`FORCE_NO_BT_A2DP`, so headphones win);
 * a headset-style wired MIC then pulls Archie's voice and the cues into a jack with no speaker.
 * Selecting the system's Bluetooth route in [MediaRouter] (what the volume panel does) clears that
 * force (`AudioService.setBluetoothA2dpOn(true)`). [prefer] `false` gives the choice back to the
 * system, but only when this class took it.
 *
 * Runs on the main looper (MediaRouter's callbacks live there); callers may be on any thread.
 */
class AndroidA2dpMediaRoute(context: Context, private val log: VoiceLog) {
    private val router = context.applicationContext.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
    private val main = Handler(Looper.getMainLooper())
    private var pinned = false

    fun prefer(on: Boolean) {
        main.post { if (on) pin() else unpin() }
    }

    private fun pin() {
        val bt = bluetoothRoute()
        if (bt == null) {
            log.d(TAG, "[ROUTE] A2DP media: no Bluetooth route in MediaRouter")
            return
        }
        // MediaRouter ignores selecting the selected route, and a plug can reset Android's A2DP flag
        // while the Bluetooth route still shows as selected: step through the default route.
        if (router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO) == bt) {
            router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, router.defaultRoute)
        }
        router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, bt)
        log.i(TAG, "[ROUTE] A2DP media: selected \"${bt.name}\" (media back on Bluetooth while a headset is plugged)")
        pinned = true
    }

    private fun unpin() {
        if (!pinned) return
        pinned = false
        router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, router.defaultRoute)
        log.i(TAG, "[ROUTE] A2DP media: back to the system default route")
    }

    /** The system Bluetooth route: local live audio that is not the default (phone/headphones) route. */
    private fun bluetoothRoute(): MediaRouter.RouteInfo? {
        val default = router.defaultRoute
        return (0 until router.routeCount).map { router.getRouteAt(it) }.firstOrNull {
            it != default &&
                it.supportedTypes and MediaRouter.ROUTE_TYPE_LIVE_AUDIO != 0 &&
                it.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_LOCAL
        }
    }

    private companion object {
        const val TAG = "AudioRouter"
    }
}
