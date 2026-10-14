package com.assistant.core.audio.parity

import com.assistant.core.audio.ports.AudioMode
import com.assistant.core.audio.ports.AvailableOutputs
import com.assistant.core.audio.ports.FallbackReason
import com.assistant.core.audio.ports.InputChoice
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

    // ── Input choice (NEW, 2026-10-14: Bluetooth speaker + wired mic on the A300M) ──

    private val ws = ProviderKind.WEBSOCKET
    private val hfpAndWired = AvailableOutputs(bluetoothCallAudio = true, bluetoothMedia = true, wired = true)
    private val wiredOnly = AvailableOutputs(bluetoothCallAudio = false, bluetoothMedia = false, wired = true)

    @Test
    fun inputAutoKeepsTheOldTable() {
        for (provider in ProviderKind.entries) for (available in all + hfpAndWired) for (out in OutputChoice.entries) {
            assertEquals(decider.pickRoute(out, provider, available), decider.pickRoute(out, provider, available, InputChoice.AUTO))
        }
    }

    /** The JBL plays over A2DP and the wired mic records: call audio (SCO) would take over the mic. */
    @Test
    fun bluetoothOutputWithAPhoneOrWiredMicUsesMediaAudioNotCallAudio() {
        for (input in listOf(InputChoice.WIRED, InputChoice.PHONE)) {
            assertEquals(Route.BluetoothMediaBesideHeadset, decider.pickRoute(OutputChoice.BLUETOOTH, ws, hfpAndWired, input))
            assertEquals("no plug: the media plane", Route.BluetoothMedia, decider.pickRoute(OutputChoice.BLUETOOTH, ws, hfp, input))
            assertEquals(
                Route.BluetoothUnsupported(FallbackReason.BT_A2DP_REQUIRES_WS_PROVIDER),
                decider.pickRoute(OutputChoice.BLUETOOTH, ProviderKind.WEBRTC, hfpAndWired, input),
            )
            assertEquals(
                Route.BluetoothUnsupported(FallbackReason.BT_NOT_AVAILABLE),
                decider.pickRoute(OutputChoice.BLUETOOTH, ws, AvailableOutputs(bluetoothCallAudio = true, bluetoothMedia = false, wired = false), input),
            )
        }
    }

    /** A headset-style mic makes the OS play into the jack, which has no speaker. */
    @Test
    fun autoOutputWithAPluggedWiredMicPlaysOnBluetoothMediaElseTheLoudspeaker() {
        assertEquals(Route.BluetoothMediaBesideHeadset, decider.pickRoute(OutputChoice.AUTO, ws, hfpAndWired, InputChoice.WIRED))
        assertEquals(Route.Loudspeaker, decider.pickRoute(OutputChoice.AUTO, ProviderKind.WEBRTC, hfpAndWired, InputChoice.WIRED))
        assertEquals(Route.Loudspeaker, decider.pickRoute(OutputChoice.AUTO, ws, wiredOnly, InputChoice.WIRED))
        // Nothing plugged in: the OS default is fine.
        assertEquals(Route.SystemDefault, decider.pickRoute(OutputChoice.AUTO, ws, hfp, InputChoice.WIRED))
        assertEquals(Route.SystemDefault, decider.pickRoute(OutputChoice.AUTO, ws, hfpAndWired, InputChoice.PHONE))
    }

    @Test
    fun explicitOutputsAreKeptWithAnyNonBluetoothMic() {
        for (input in listOf(InputChoice.WIRED, InputChoice.PHONE)) for (available in all + hfpAndWired) {
            assertEquals(Route.Earpiece, decider.pickRoute(OutputChoice.EARPIECE, ws, available, input))
            assertEquals(Route.Loudspeaker, decider.pickRoute(OutputChoice.LOUDSPEAKER, ws, available, input))
            assertEquals(Route.WiredHeadphone, decider.pickRoute(OutputChoice.WIRED, ws, available, input))
        }
    }

    /** Measured on the A300M: with a headset plugged, only the call stream in NORMAL mode reaches A2DP. */
    @Test
    fun bluetoothBesideAHeadsetPlaysOnTheCallPlaneInNormalMode() {
        assertEquals(SpeakerMode.CALL, decider.speakerModeFor(Route.BluetoothMediaBesideHeadset))
        assertEquals(AudioMode.NORMAL, decider.audioModeFor(Route.BluetoothMediaBesideHeadset))
    }

    /** The Bluetooth mic only exists on call audio, which carries both directions. */
    @Test
    fun bluetoothMicForcesBluetoothCallAudioWhenConnected() {
        for (provider in ProviderKind.entries) for (out in OutputChoice.entries) {
            assertEquals(Route.BluetoothCallAudio, decider.pickRoute(out, provider, hfpAndWired, InputChoice.BLUETOOTH))
        }
        // Without HFP it behaves as AUTO.
        assertEquals(Route.Loudspeaker, decider.pickRoute(OutputChoice.LOUDSPEAKER, ws, a2dpOnly, InputChoice.BLUETOOTH))
        assertEquals(Route.BluetoothMedia, decider.pickRoute(OutputChoice.BLUETOOTH, ws, a2dpOnly, InputChoice.BLUETOOTH))
    }

    /** A plugged headset forces media off A2DP; these settings must take it back (MediaRouter). */
    @Test
    fun a2dpMediaIsReclaimedOnlyForBluetoothOutputWithAWiredOrPhoneMicWhileAPlugIsIn() {
        val d = com.assistant.core.audio.routing.DefaultRouteDecider
        assertEquals(true, d.wantsA2dpMedia(OutputChoice.BLUETOOTH, InputChoice.WIRED, hfpAndWired))
        assertEquals(true, d.wantsA2dpMedia(OutputChoice.BLUETOOTH, InputChoice.PHONE, hfpAndWired))
        assertEquals(true, d.wantsA2dpMedia(OutputChoice.AUTO, InputChoice.WIRED, hfpAndWired))
        assertEquals(false, d.wantsA2dpMedia(OutputChoice.AUTO, InputChoice.PHONE, hfpAndWired))
        assertEquals("nothing plugged in: nothing to take back", false, d.wantsA2dpMedia(OutputChoice.BLUETOOTH, InputChoice.WIRED, hfp))
        assertEquals(false, d.wantsA2dpMedia(OutputChoice.BLUETOOTH, InputChoice.WIRED, wiredOnly))
        for (input in InputChoice.entries) {
            assertEquals(false, d.wantsA2dpMedia(OutputChoice.LOUDSPEAKER, input, hfpAndWired))
        }
        assertEquals("the old behaviour", false, d.wantsA2dpMedia(OutputChoice.BLUETOOTH, InputChoice.AUTO, hfpAndWired))
        assertEquals(false, d.wantsA2dpMedia(OutputChoice.BLUETOOTH, InputChoice.BLUETOOTH, hfpAndWired))
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
