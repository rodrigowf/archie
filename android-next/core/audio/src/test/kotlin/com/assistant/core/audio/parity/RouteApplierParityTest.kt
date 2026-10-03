package com.assistant.core.audio.parity

import com.assistant.core.audio.ports.AudioMode
import com.assistant.core.audio.ports.DeviceType
import com.assistant.core.audio.ports.FallbackReason
import com.assistant.core.audio.ports.Route
import com.assistant.core.audio.ports.SpeakerMode
import com.assistant.core.testing.FakeAudioManagerPort
import com.assistant.core.testing.FakeAudioManagerPort.Companion.BT_A2DP
import com.assistant.core.testing.FakeAudioManagerPort.Companion.BT_SCO
import com.assistant.core.testing.FakeAudioManagerPort.Companion.EARPIECE
import com.assistant.core.testing.FakeAudioManagerPort.Companion.SPEAKER
import com.assistant.core.testing.FakeAudioManagerPort.Companion.WIRED
import com.assistant.core.testing.RecordingLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Applying routes against a fake AudioManager at each API level (inv04 §3.4 "Apply", §5.1). The fake
 * throws `NoSuchMethodError` for an API the level lacks, so an unguarded call fails the test the
 * way it crashed Lollipop (`20217b1`).
 */
@Ignore("A-05")
class RouteApplierParityTest {
    private val allRoutes = listOf(
        Route.SystemDefault, Route.Earpiece, Route.Loudspeaker, Route.BluetoothCallAudio, Route.BluetoothMedia,
        Route.WiredHeadphone, Route.BluetoothUnsupported(FallbackReason.BT_NOT_AVAILABLE),
    )

    private fun applier(am: FakeAudioManagerPort) = audioCore.routeApplier(am, am.sdkInt, RecordingLog())

    /** RS-25 (`20217b1`): API 21/22 never call getDevices / communication-device APIs. */
    @Test
    fun rs25_lollipopNeverUsesGetDevicesOrCommunicationDeviceApis() {
        for (sdk in listOf(21, 22)) {
            val am = FakeAudioManagerPort(sdk, headsetProfileConnected = true, a2dpProfileConnected = true, wiredPlugged = true)
            val a = applier(am)
            a.availableOutputs()
            for (r in allRoutes) a.apply(r)
            a.release()
            assertTrue("API $sdk violations: ${am.apiViolations}", am.apiViolations.isEmpty())
        }
    }

    @Test
    fun lollipopDetectsBluetoothByProfileAndWiredByStickyIntent() {
        val am = FakeAudioManagerPort(22, headsetProfileConnected = true, a2dpProfileConnected = false, wiredPlugged = true)
        val out = applier(am).availableOutputs()
        assertTrue(out.bluetoothCallAudio)
        assertFalse(out.bluetoothMedia)
        assertTrue(out.wired)
    }

    @Test
    fun api23to30DetectsDevicesThroughGetDevices() {
        for (sdk in listOf(23, 26, 30)) {
            val am = FakeAudioManagerPort(sdk, devices = listOf(EARPIECE, SPEAKER, BT_SCO, BT_A2DP, WIRED), headsetProfileConnected = true)
            val out = applier(am).availableOutputs()
            assertTrue("API $sdk", out.bluetoothCallAudio)
            assertTrue("API $sdk", out.bluetoothMedia)
            assertTrue("API $sdk", out.wired)
            assertTrue(am.apiViolations.isEmpty())
        }
    }

    @Test
    fun legacyLoudspeakerUsesSpeakerphone() {
        for (sdk in listOf(21, 22, 23, 26, 30)) {
            val am = FakeAudioManagerPort(sdk)
            val applied = applier(am).apply(Route.Loudspeaker)
            assertTrue("API $sdk", am.isSpeakerphoneOn)
            assertEquals(AudioMode.IN_COMMUNICATION, am.mode)
            assertEquals(SpeakerMode.CALL, applied.speakerMode)
        }
    }

    @Test
    fun legacyBluetoothCallAudioStartsSco() {
        val am = FakeAudioManagerPort(22, headsetProfileConnected = true)
        applier(am).apply(Route.BluetoothCallAudio)
        assertTrue(am.scoStarted)
        assertTrue(am.isBluetoothScoOn)
        assertFalse(am.isSpeakerphoneOn)
    }

    /** RS-28 (`6fb1e54`): API 31+ pins earpiece/speaker with setCommunicationDevice. */
    @Test
    fun rs28_api31UsesCommunicationDevice() {
        for (sdk in listOf(31, 34, 36)) {
            val am = FakeAudioManagerPort(sdk)
            val a = applier(am)
            a.apply(Route.Earpiece)
            assertEquals("API $sdk", DeviceType.BUILTIN_EARPIECE, am.communicationDevice?.type)
            a.apply(Route.Loudspeaker)
            assertEquals("API $sdk", DeviceType.BUILTIN_SPEAKER, am.communicationDevice?.type)
            a.apply(Route.SystemDefault)
            assertNull("API $sdk SystemDefault clears the pin", am.communicationDevice)
            assertEquals(AudioMode.NORMAL, am.mode)
        }
    }

    /** RS-28 (`ef2aaae`): a missing BLUETOOTH_CONNECT grant never crashes detection or routing. */
    @Test
    fun rs28_missingBluetoothConnectNeverCrashes() {
        for (sdk in listOf(31, 34, 36)) {
            val am = FakeAudioManagerPort(sdk, devices = listOf(EARPIECE, SPEAKER, BT_SCO), headsetProfileConnected = true, bluetoothConnectGranted = false)
            val a = applier(am)
            a.availableOutputs()
            for (r in allRoutes) a.apply(r)
            a.release()
        }
    }

    @Test
    fun unsupportedBluetoothFallsBackToLoudspeaker() {
        val am = FakeAudioManagerPort(26)
        applier(am).apply(Route.BluetoothUnsupported(FallbackReason.BT_A2DP_REQUIRES_WS_PROVIDER))
        assertTrue(am.isSpeakerphoneOn)
        assertEquals(AudioMode.IN_COMMUNICATION, am.mode)
    }

    @Test
    fun systemDefaultReleasesSpeakerphoneAndSco() {
        val am = FakeAudioManagerPort(22, headsetProfileConnected = true)
        val a = applier(am)
        a.apply(Route.BluetoothCallAudio)
        a.apply(Route.SystemDefault)
        assertFalse(am.isSpeakerphoneOn)
        assertFalse(am.isBluetoothScoOn)
        assertEquals(AudioMode.NORMAL, am.mode)
    }

    @Test
    fun releaseReturnsToNormalAndStopsSco() {
        for (sdk in listOf(22, 31)) {
            val am = FakeAudioManagerPort(sdk, devices = listOf(EARPIECE, SPEAKER, BT_SCO), headsetProfileConnected = true)
            val a = applier(am)
            a.apply(Route.BluetoothCallAudio)
            a.release()
            assertEquals("API $sdk", AudioMode.NORMAL, am.mode)
            assertFalse("API $sdk", am.isBluetoothScoOn)
            assertNull("API $sdk", am.communicationDevice)
        }
    }

    @Test
    fun bluetoothMediaReportsTheA2dpDeviceForTrackPinning() {
        val am = FakeAudioManagerPort(26, devices = listOf(EARPIECE, SPEAKER, BT_A2DP), a2dpProfileConnected = true)
        val applied = applier(am).apply(Route.BluetoothMedia)
        assertEquals(SpeakerMode.MEDIA, applied.speakerMode)
        assertEquals(DeviceType.BLUETOOTH_A2DP, applied.preferredDevice?.type)
        assertEquals(AudioMode.NORMAL, am.mode)
    }
}
