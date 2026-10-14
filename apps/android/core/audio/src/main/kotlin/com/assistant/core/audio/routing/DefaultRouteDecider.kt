package com.assistant.core.audio.routing

import com.assistant.core.audio.ports.AudioMode
import com.assistant.core.audio.ports.AvailableOutputs
import com.assistant.core.audio.ports.FallbackReason
import com.assistant.core.audio.ports.InputChoice
import com.assistant.core.audio.ports.OutputChoice
import com.assistant.core.audio.ports.ProviderKind
import com.assistant.core.audio.ports.Route
import com.assistant.core.audio.ports.RouteDecider
import com.assistant.core.audio.ports.SpeakerMode

/** Old `AudioRouter.pickRoute` (:153-186), `Route.speakerMode` (:115-127) and `apply`'s mode choice (:380-383). */
object DefaultRouteDecider : RouteDecider {

    override fun pickRoute(desired: OutputChoice, provider: ProviderKind, available: AvailableOutputs, input: InputChoice): Route {
        // The Bluetooth mic lives on the call profile only, which carries both directions.
        if (input == InputChoice.BLUETOOTH && available.bluetoothCallAudio) return Route.BluetoothCallAudio
        val micOffBluetooth = input == InputChoice.PHONE || input == InputChoice.WIRED
        return when (desired) {
            OutputChoice.AUTO ->
                if (input == InputChoice.WIRED && available.wired) awayFromTheJack(provider, available) else Route.SystemDefault
            OutputChoice.EARPIECE -> Route.Earpiece
            OutputChoice.LOUDSPEAKER -> Route.Loudspeaker
            OutputChoice.WIRED -> Route.WiredHeadphone
            OutputChoice.BLUETOOTH -> when {
                !micOffBluetooth && available.bluetoothCallAudio -> Route.BluetoothCallAudio
                available.bluetoothMedia -> bluetoothMedia(provider, besideHeadset = micOffBluetooth && available.wired)
                else -> Route.BluetoothUnsupported(FallbackReason.BT_NOT_AVAILABLE)
            }
        }
    }

    private fun bluetoothMedia(provider: ProviderKind, besideHeadset: Boolean): Route =
        if (provider == ProviderKind.WEBSOCKET) {
            if (besideHeadset) Route.BluetoothMediaBesideHeadset else Route.BluetoothMedia
        } else {
            // WebRTC's JavaAudioDeviceModule is pinned to the communication plane (RS-29, `b974756`).
            Route.BluetoothUnsupported(FallbackReason.BT_A2DP_REQUIRES_WS_PROVIDER)
        }

    /**
     * Plugging a headset makes Android force media off A2DP (`FORCE_NO_BT_A2DP`, so headphones
     * win). When the user wants Bluetooth output with a wired/phone mic (the rows above that pick
     * [Route.BluetoothMedia]), media must be moved back to A2DP; the platform does it through
     * `MediaRouter` (the system's own Bluetooth route). Provider-independent: the wake cues play
     * on the media stream too.
     */
    fun wantsA2dpMedia(desired: OutputChoice, input: InputChoice, available: AvailableOutputs): Boolean {
        if (!available.bluetoothMedia || !available.wired) return false
        return when (desired) {
            OutputChoice.BLUETOOTH -> input == InputChoice.WIRED || input == InputChoice.PHONE
            OutputChoice.AUTO -> input == InputChoice.WIRED
            else -> false
        }
    }

    /** A headset-style wired mic is plugged in: the OS would play into the jack, which has no speaker. */
    private fun awayFromTheJack(provider: ProviderKind, available: AvailableOutputs): Route =
        if (available.bluetoothMedia && provider == ProviderKind.WEBSOCKET) Route.BluetoothMediaBesideHeadset else Route.Loudspeaker

    /** NORMAL hands routing back to the OS (SystemDefault) or keeps the media plane (BluetoothMedia). */
    override fun audioModeFor(route: Route): AudioMode =
        if (route is Route.BluetoothMedia || route is Route.BluetoothMediaBesideHeadset || route is Route.SystemDefault) {
            AudioMode.NORMAL
        } else {
            AudioMode.IN_COMMUNICATION
        }

    /**
     * MEDIA only for BluetoothMedia. SystemDefault stays CALL (`c14c837`): Samsung's MSM8916 policy
     * gates the tfa9895 amp to volume 0 for STREAM_MUSIC in MODE_NORMAL. BluetoothMediaBesideHeadset
     * is CALL too: that policy keeps STREAM_MUSIC on a plugged headset (measured 2026-10-14).
     */
    override fun speakerModeFor(route: Route): SpeakerMode =
        if (route is Route.BluetoothMedia) SpeakerMode.MEDIA else SpeakerMode.CALL

    /** Old `Route.label`, for logs. */
    fun label(route: Route, device: String? = null): String = when (route) {
        Route.SystemDefault -> "system-default"
        Route.Earpiece -> "earpiece"
        Route.Loudspeaker -> "loudspeaker"
        Route.BluetoothCallAudio -> "bluetooth-call(${device ?: "unknown"})"
        Route.BluetoothMedia -> "bluetooth-media(${device ?: "unknown"})"
        Route.BluetoothMediaBesideHeadset -> "bluetooth-media-beside-headset(${device ?: "unknown"})"
        is Route.BluetoothUnsupported -> "bluetooth-unsupported(${route.reason.name})"
        Route.WiredHeadphone -> "wired(${device ?: "unknown"})"
    }
}
