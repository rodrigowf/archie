package com.assistant.core.audio.routing

import com.assistant.core.audio.ports.AudioMode
import com.assistant.core.audio.ports.AvailableOutputs
import com.assistant.core.audio.ports.FallbackReason
import com.assistant.core.audio.ports.OutputChoice
import com.assistant.core.audio.ports.ProviderKind
import com.assistant.core.audio.ports.Route
import com.assistant.core.audio.ports.RouteDecider
import com.assistant.core.audio.ports.SpeakerMode

/** Old `AudioRouter.pickRoute` (:153-186), `Route.speakerMode` (:115-127) and `apply`'s mode choice (:380-383). */
object DefaultRouteDecider : RouteDecider {

    override fun pickRoute(desired: OutputChoice, provider: ProviderKind, available: AvailableOutputs): Route = when (desired) {
        OutputChoice.AUTO -> Route.SystemDefault
        OutputChoice.EARPIECE -> Route.Earpiece
        OutputChoice.LOUDSPEAKER -> Route.Loudspeaker
        OutputChoice.WIRED -> Route.WiredHeadphone
        OutputChoice.BLUETOOTH -> when {
            available.bluetoothCallAudio -> Route.BluetoothCallAudio
            available.bluetoothMedia ->
                if (provider == ProviderKind.WEBSOCKET) {
                    Route.BluetoothMedia
                } else {
                    // WebRTC's JavaAudioDeviceModule is pinned to the communication plane (RS-29, `b974756`).
                    Route.BluetoothUnsupported(FallbackReason.BT_A2DP_REQUIRES_WS_PROVIDER)
                }
            else -> Route.BluetoothUnsupported(FallbackReason.BT_NOT_AVAILABLE)
        }
    }

    /** NORMAL hands routing back to the OS (SystemDefault) or keeps the media plane (BluetoothMedia). */
    override fun audioModeFor(route: Route): AudioMode =
        if (route is Route.BluetoothMedia || route is Route.SystemDefault) AudioMode.NORMAL else AudioMode.IN_COMMUNICATION

    /**
     * MEDIA only for BluetoothMedia. SystemDefault stays CALL (`c14c837`): Samsung's MSM8916 policy
     * gates the tfa9895 amp to volume 0 for STREAM_MUSIC in MODE_NORMAL.
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
        is Route.BluetoothUnsupported -> "bluetooth-unsupported(${route.reason.name})"
        Route.WiredHeadphone -> "wired(${device ?: "unknown"})"
    }
}
