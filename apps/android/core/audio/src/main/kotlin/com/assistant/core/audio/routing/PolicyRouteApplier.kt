package com.assistant.core.audio.routing

import com.assistant.core.audio.policy.RouteApi
import com.assistant.core.audio.ports.AppliedRoute
import com.assistant.core.audio.ports.AudioDeviceRef
import com.assistant.core.audio.ports.AudioManagerPort
import com.assistant.core.audio.ports.AudioMode
import com.assistant.core.audio.ports.AvailableOutputs
import com.assistant.core.audio.ports.DeviceType
import com.assistant.core.audio.ports.Route
import com.assistant.core.audio.ports.RouteApplier
import com.assistant.core.audio.ports.RouteDecider
import com.assistant.core.audio.ports.VoiceLog

/**
 * Old `AudioRouter` detection (:199-347), `apply` (:373-550) and `release` (:556-572), with every
 * `Build.VERSION` branch replaced by [RouteApi.forSdk] so it runs at any API level on the JVM.
 *
 * Kept from the old code: what is called, in which order, and which steps are wrapped in
 * try/catch. Added: a `SecurityException` from an S+ communication-device call (missing
 * BLUETOOTH_CONNECT) is logged instead of escaping (port contract, `ef2aaae`).
 */
class PolicyRouteApplier(
    private val am: AudioManagerPort,
    sdkInt: Int,
    private val log: VoiceLog,
    private val decider: RouteDecider = DefaultRouteDecider,
) : RouteApplier {
    private val api = RouteApi.forSdk(sdkInt)

    // ---------------------------------------------------------------- detection

    override fun availableOutputs(): AvailableOutputs = AvailableOutputs(
        bluetoothCallAudio = hasBluetoothCallAudio(),
        bluetoothMedia = hasBluetoothMedia(),
        wired = hasWiredHeadphone(),
    )

    private fun headsetProfileConnected(): Boolean =
        try { am.isBluetoothHeadsetProfileConnected() } catch (_: SecurityException) { false }

    private fun a2dpProfileConnected(): Boolean =
        try { am.isBluetoothA2dpProfileConnected() } catch (_: SecurityException) { false }

    private fun communicationDevices(): List<AudioDeviceRef> =
        try { am.availableCommunicationDevices() } catch (e: SecurityException) {
            log.w(TAG, "availableCommunicationDevices denied: ${e.message}")
            emptyList()
        }

    private fun isBluetoothType(type: DeviceType): Boolean = when (type) {
        DeviceType.BLUETOOTH_A2DP, DeviceType.BLUETOOTH_SCO -> true
        DeviceType.BLE_HEADSET, DeviceType.BLE_SPEAKER -> api.bleDeviceTypes
        else -> false
    }

    /** null before M even when a headset is connected: there is no AudioDeviceInfo to hand out. */
    private fun findBluetoothCallAudioDevice(): AudioDeviceRef? {
        if (api.hasCommunicationDevice) return communicationDevices().firstOrNull { isBluetoothType(it.type) }
        if (!api.canListOutputDevices) return null
        // API 23..30: SCO is the only call-audio path.
        if (!headsetProfileConnected()) return null
        return am.outputDevices().firstOrNull { it.type == DeviceType.BLUETOOTH_SCO }
    }

    private fun findBluetoothMediaDevice(): AudioDeviceRef? {
        if (!api.canListOutputDevices) return null
        return am.outputDevices().firstOrNull {
            it.type == DeviceType.BLUETOOTH_A2DP ||
                (api.bleDeviceTypes && (it.type == DeviceType.BLE_HEADSET || it.type == DeviceType.BLE_SPEAKER))
        }
    }

    private fun findWiredHeadphoneDevice(): AudioDeviceRef? {
        if (!api.canListOutputDevices) return null
        return am.outputDevices().firstOrNull {
            it.type == DeviceType.WIRED_HEADSET || it.type == DeviceType.WIRED_HEADPHONES ||
                (api.usbHeadsetIsWired && it.type == DeviceType.USB_HEADSET)
        }
    }

    private fun hasBluetoothCallAudio(): Boolean =
        if (api.canListOutputDevices) {
            findBluetoothCallAudioDevice() != null || (!api.hasCommunicationDevice && headsetProfileConnected())
        } else {
            headsetProfileConnected()
        }

    private fun hasBluetoothMedia(): Boolean =
        if (api.canListOutputDevices) findBluetoothMediaDevice() != null || a2dpProfileConnected() else a2dpProfileConnected()

    private fun hasWiredHeadphone(): Boolean =
        if (api.canListOutputDevices) findWiredHeadphoneDevice() != null || am.isWiredHeadsetPlugged() else am.isWiredHeadsetPlugged()

    // ---------------------------------------------------------------- apply

    override fun apply(route: Route): AppliedRoute {
        val device: AudioDeviceRef? = when (route) {
            Route.BluetoothCallAudio -> findBluetoothCallAudioDevice()
            Route.BluetoothMedia -> findBluetoothMediaDevice()
            Route.WiredHeadphone -> findWiredHeadphoneDevice()
            else -> null
        }
        val label = DefaultRouteDecider.label(route, device?.name)

        val targetMode = decider.audioModeFor(route)
        try {
            if (am.mode != targetMode) {
                am.mode = targetMode
                log.d(TAG, "audio mode → $targetMode for $label")
            }
        } catch (e: Exception) {
            log.w(TAG, "set audio mode failed: ${e.message}")
        }

        var pinned: AudioDeviceRef? = null
        when (route) {
            Route.SystemDefault -> applySystemDefault()
            Route.Earpiece -> pinned = applyCommunicationRoute(DeviceType.BUILTIN_EARPIECE, speakerphone = false)
            Route.Loudspeaker -> pinned = applyCommunicationRoute(DeviceType.BUILTIN_SPEAKER, speakerphone = true)
            Route.BluetoothCallAudio -> pinned = applyBluetoothCallAudio(device)
            Route.BluetoothMedia -> applyBluetoothMedia()
            // Fallback: loudspeaker so SOMETHING plays; the caller toasts the reason.
            is Route.BluetoothUnsupported -> pinned = applyCommunicationRoute(DeviceType.BUILTIN_SPEAKER, speakerphone = true)
            Route.WiredHeadphone -> pinned = applyWiredHeadphone(device)
        }

        log.d(TAG, "[ROUTE] applied=$label speakerOn=${am.isSpeakerphoneOn} scoOn=${am.isBluetoothScoOn} mode=${am.mode}")
        if (api.hasCommunicationDevice) log.d(TAG, "[ROUTE] communicationDevice type=${pinned?.type} name=${pinned?.name}")

        val preferred = when (route) {
            Route.BluetoothMedia, Route.BluetoothCallAudio, Route.WiredHeadphone -> device
            else -> null
        }
        return AppliedRoute(route, decider.speakerModeFor(route), preferred)
    }

    /** Undo every pin a previous route applied so the OS policy (MODE_NORMAL) picks the output. */
    private fun applySystemDefault() {
        if (api.hasCommunicationDevice) {
            try { am.clearCommunicationDevice() } catch (_: Exception) {}
        }
        try { am.isSpeakerphoneOn = false } catch (_: Exception) {}
        try { stopScoIfOn() } catch (_: Exception) {}
    }

    private fun applyCommunicationRoute(target: DeviceType, speakerphone: Boolean): AudioDeviceRef? {
        if (api.hasCommunicationDevice) {
            val device = communicationDevices().firstOrNull { it.type == target }
            if (device != null) {
                val ok = setCommunicationDevice(device)
                log.d(TAG, "setCommunicationDevice(type=$target) → $ok")
                return device.takeIf { ok }
            }
            log.w(TAG, "no communication device of type=$target; clearing + speakerphone=$speakerphone")
            clearCommunicationDevice()
        }
        am.isSpeakerphoneOn = speakerphone
        stopScoIfOn()
        return null
    }

    private fun applyBluetoothCallAudio(device: AudioDeviceRef?): AudioDeviceRef? {
        if (api.hasCommunicationDevice) {
            if (device == null) {
                log.w(TAG, "applyBluetoothCallAudio: device is null on S+, skipping")
                return null
            }
            val ok = setCommunicationDevice(device)
            log.d(TAG, "setCommunicationDevice(BT call, type=${device.type}) → $ok")
            return device.takeIf { ok }
        }
        am.isSpeakerphoneOn = false
        am.startBluetoothSco()
        am.isBluetoothScoOn = true
        return null
    }

    /** Android routes USAGE_MEDIA to an active A2DP sink by itself; just drop conflicting pins. */
    private fun applyBluetoothMedia() {
        if (api.hasCommunicationDevice) clearCommunicationDevice()
        am.isSpeakerphoneOn = false
        stopScoIfOn()
    }

    /** Hardware priority snaps the call plane to the plug unless something else is pinned. */
    private fun applyWiredHeadphone(device: AudioDeviceRef?): AudioDeviceRef? {
        if (api.hasCommunicationDevice) {
            if (device != null) {
                val ok = setCommunicationDevice(device)
                log.d(TAG, "setCommunicationDevice(wired, type=${device.type}) → $ok")
                if (ok) return device
            }
            clearCommunicationDevice()
        }
        am.isSpeakerphoneOn = false
        stopScoIfOn()
        return null
    }

    private fun stopScoIfOn() {
        if (am.isBluetoothScoOn) {
            am.stopBluetoothSco()
            am.isBluetoothScoOn = false
        }
    }

    private fun setCommunicationDevice(device: AudioDeviceRef): Boolean =
        try { am.setCommunicationDevice(device) } catch (e: SecurityException) {
            log.w(TAG, "setCommunicationDevice denied: ${e.message}")
            false
        }

    private fun clearCommunicationDevice() {
        try { am.clearCommunicationDevice() } catch (e: SecurityException) {
            log.w(TAG, "clearCommunicationDevice denied: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- release

    override fun release() {
        if (api.hasCommunicationDevice) {
            try { am.clearCommunicationDevice() } catch (_: Exception) {}
        }
        try { stopScoIfOn() } catch (_: Exception) {}
        try {
            if (am.mode != AudioMode.NORMAL) am.mode = AudioMode.NORMAL
        } catch (_: Exception) {}
    }

    private companion object {
        const val TAG = "AudioRouter"
    }
}
