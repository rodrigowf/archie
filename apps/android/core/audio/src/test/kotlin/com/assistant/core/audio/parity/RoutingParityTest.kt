package com.assistant.core.audio.parity

import com.assistant.core.audio.ports.AudioMode
import com.assistant.core.audio.ports.AvailableOutputs
import com.assistant.core.audio.ports.FallbackReason
import com.assistant.core.audio.ports.OutputChoice
import com.assistant.core.audio.ports.ProviderKind
import com.assistant.core.audio.ports.Route
import com.assistant.core.audio.ports.SpeakerMode
import com.assistant.core.testing.PinsConstant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Routing decision table (inv04 §3.4 "Decision", §4.10) for every output × provider × BT availability. */
class RoutingParityTest {
    private val decider = audioCore.routeDecider

    private val none = AvailableOutputs(bluetoothCallAudio = false, bluetoothMedia = false, wired = false)
    private val hfp = AvailableOutputs(bluetoothCallAudio = true, bluetoothMedia = true, wired = false)
    private val a2dpOnly = AvailableOutputs(bluetoothCallAudio = false, bluetoothMedia = true, wired = false)
    private val all = listOf(none, hfp, a2dpOnly, AvailableOutputs(false, false, true))

    @Test
    fun nonBluetoothChoicesIgnoreAvailabilityAndProvider() {
        for (provider in ProviderKind.entries) for (available in all) {
            assertEquals(Route.SystemDefault, decider.pickRoute(OutputChoice.AUTO, provider, available))
            assertEquals(Route.Earpiece, decider.pickRoute(OutputChoice.EARPIECE, provider, available))
            assertEquals(Route.Loudspeaker, decider.pickRoute(OutputChoice.LOUDSPEAKER, provider, available))
            assertEquals(Route.WiredHeadphone, decider.pickRoute(OutputChoice.WIRED, provider, available))
        }
    }

    @Test
    fun bluetoothWithHfpIsCallAudioForBothProviders() {
        for (provider in ProviderKind.entries) {
            assertEquals(Route.BluetoothCallAudio, decider.pickRoute(OutputChoice.BLUETOOTH, provider, hfp))
        }
    }

    /** RS-29 (`b974756`): A2DP-only + WS → media plane; + WebRTC → loudspeaker fallback (toast). */
    @Test
    fun rs29_a2dpOnlyRoutesMediaForWsAndFallsBackForWebRtc() {
        assertEquals(Route.BluetoothMedia, decider.pickRoute(OutputChoice.BLUETOOTH, ProviderKind.WEBSOCKET, a2dpOnly))
        assertEquals(
            Route.BluetoothUnsupported(FallbackReason.BT_A2DP_REQUIRES_WS_PROVIDER),
            decider.pickRoute(OutputChoice.BLUETOOTH, ProviderKind.WEBRTC, a2dpOnly),
        )
        assertEquals(SpeakerMode.MEDIA, decider.speakerModeFor(Route.BluetoothMedia))
        assertEquals(AudioMode.NORMAL, decider.audioModeFor(Route.BluetoothMedia))
    }

    @Test
    fun bluetoothWithNothingConnectedFallsBack() {
        for (provider in ProviderKind.entries) {
            assertEquals(
                Route.BluetoothUnsupported(FallbackReason.BT_NOT_AVAILABLE),
                decider.pickRoute(OutputChoice.BLUETOOTH, provider, none),
            )
        }
    }

    /** RS-26 (`01d30ec` + `c14c837`): AUTO = MODE_NORMAL but the track is tagged CALL (tfa9895 amp gate). */
    @Test
    @PinsConstant("route.system_default_normal_call")
    fun rs26_autoRouteTagsTrackAsCall() {
        val route = decider.pickRoute(OutputChoice.AUTO, ProviderKind.WEBSOCKET, none)
        assertEquals(Route.SystemDefault, route)
        assertEquals(AudioMode.NORMAL, decider.audioModeFor(route))
        assertEquals(SpeakerMode.CALL, decider.speakerModeFor(route))
    }

    @Test
    fun modeAndSpeakerModeForEveryRoute() {
        val inComm = listOf(
            Route.Earpiece, Route.Loudspeaker, Route.BluetoothCallAudio, Route.WiredHeadphone,
            Route.BluetoothUnsupported(FallbackReason.BT_NOT_AVAILABLE),
            Route.BluetoothUnsupported(FallbackReason.BT_A2DP_REQUIRES_WS_PROVIDER),
        )
        for (r in inComm) {
            assertEquals("$r mode", AudioMode.IN_COMMUNICATION, decider.audioModeFor(r))
            assertEquals("$r speaker", SpeakerMode.CALL, decider.speakerModeFor(r))
        }
        assertEquals(AudioMode.NORMAL, decider.audioModeFor(Route.SystemDefault))
        assertEquals(SpeakerMode.CALL, decider.speakerModeFor(Route.SystemDefault))
    }
}
