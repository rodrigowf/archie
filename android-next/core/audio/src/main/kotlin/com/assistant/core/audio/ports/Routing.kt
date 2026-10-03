package com.assistant.core.audio.ports

/*
 * Audio routing (inv04 §3.4, §4.10, §5.1). Interface-only (A-04).
 *
 * Note: [OutputChoice] mirrors `:core:model`'s `AudioOutput` (A-02). It is declared here so the
 * port compiles before A-02 lands; the coordinator may replace it with a typealias later.
 */

enum class OutputChoice { AUTO, EARPIECE, LOUDSPEAKER, WIRED, BLUETOOTH }

enum class ProviderKind { WEBSOCKET, WEBRTC }

/** Which audio plane the provider's AudioTrack uses (CALL = USAGE_VOICE_COMMUNICATION / STREAM_VOICE_CALL). */
enum class SpeakerMode { CALL, MEDIA }

enum class AudioMode { NORMAL, IN_COMMUNICATION }

enum class FallbackReason { BT_NOT_AVAILABLE, BT_A2DP_REQUIRES_WS_PROVIDER }

enum class AudioStream { VOICE_CALL, MUSIC, RING, NOTIFICATION, SYSTEM }

/** What is connected right now, as detected by the [RouteApplier] for the device's API level. */
data class AvailableOutputs(
    val bluetoothCallAudio: Boolean,
    val bluetoothMedia: Boolean,
    val wired: Boolean,
)

/** A routing decision. Devices are resolved by the [RouteApplier], not by the decision. */
sealed interface Route {
    data object SystemDefault : Route
    data object Earpiece : Route
    data object Loudspeaker : Route
    data object BluetoothCallAudio : Route
    data object BluetoothMedia : Route
    data object WiredHeadphone : Route
    data class BluetoothUnsupported(val reason: FallbackReason) : Route
}

/**
 * Pure decision table (old `AudioRouter.pickRoute` + `Route.speakerMode` + `apply`'s mode choice).
 *  - AUTO → SystemDefault; EARPIECE → Earpiece; LOUDSPEAKER → Loudspeaker; WIRED → WiredHeadphone.
 *  - BLUETOOTH: HFP → BluetoothCallAudio; else A2DP → BluetoothMedia (WS) or
 *    BluetoothUnsupported(BT_A2DP_REQUIRES_WS_PROVIDER) (WebRTC); else BluetoothUnsupported(BT_NOT_AVAILABLE).
 *  - Mode: NORMAL for SystemDefault and BluetoothMedia, IN_COMMUNICATION otherwise.
 *  - Speaker mode: MEDIA only for BluetoothMedia; CALL for everything else INCLUDING SystemDefault
 *    (`c14c837`: the A300M tfa9895 amp gate silences STREAM_MUSIC in MODE_NORMAL).
 */
interface RouteDecider {
    fun pickRoute(desired: OutputChoice, provider: ProviderKind, available: AvailableOutputs): Route
    fun audioModeFor(route: Route): AudioMode
    fun speakerModeFor(route: Route): SpeakerMode
}

enum class DeviceType {
    BUILTIN_EARPIECE, BUILTIN_SPEAKER, WIRED_HEADSET, WIRED_HEADPHONES, USB_HEADSET,
    BLUETOOTH_SCO, BLUETOOTH_A2DP, BLE_HEADSET, BLE_SPEAKER, OTHER,
}

data class AudioDeviceRef(val id: Int, val type: DeviceType, val name: String = "")

/**
 * Thin port over `android.media.AudioManager` (+ `BluetoothAdapter` profile state and the sticky
 * `ACTION_HEADSET_PLUG` for API < 23). The production adapter is a 1:1 delegation; all branching
 * lives in the [RouteApplier] so it can be tested at every API level on the JVM.
 *
 * API availability (callers MUST respect it; the test fake throws `NoSuchMethodError` otherwise,
 * which is what Lollipop does — `20217b1`):
 *  - [outputDevices]: API 23+ (`getDevices(GET_DEVICES_OUTPUTS)`).
 *  - [availableCommunicationDevices], [setCommunicationDevice], [clearCommunicationDevice]: API 31+.
 *  - [isBluetoothHeadsetProfileConnected], [isBluetoothA2dpProfileConnected]
 *    (`BluetoothAdapter.getProfileConnectionState`): on API 31+ they throw `SecurityException`
 *    when BLUETOOTH_CONNECT is not granted (`ef2aaae`); callers treat that as "not connected".
 */
interface AudioManagerPort {
    var mode: AudioMode
    var isSpeakerphoneOn: Boolean
    var isBluetoothScoOn: Boolean
    fun startBluetoothSco()
    fun stopBluetoothSco()
    fun outputDevices(): List<AudioDeviceRef>
    fun availableCommunicationDevices(): List<AudioDeviceRef>
    fun setCommunicationDevice(device: AudioDeviceRef): Boolean
    fun clearCommunicationDevice()
    fun isBluetoothHeadsetProfileConnected(): Boolean
    fun isBluetoothA2dpProfileConnected(): Boolean
    fun isWiredHeadsetPlugged(): Boolean
    fun streamVolume(stream: AudioStream): Int
    fun streamMaxVolume(stream: AudioStream): Int
    fun setStreamVolume(stream: AudioStream, index: Int)
}

data class AppliedRoute(
    val route: Route,
    val speakerMode: SpeakerMode,
    /** Device the provider should pin its AudioTrack to (BT media / BT call / wired), else null. */
    val preferredDevice: AudioDeviceRef?,
)

/**
 * Applies a [Route] to the [AudioManagerPort] (old `AudioRouter.apply` / `release`, inv04 §3.4):
 *  1. mode per [RouteDecider.audioModeFor] (only written when it differs);
 *  2. SystemDefault: clear comm device (S+), speakerphone off, stop SCO;
 *     Earpiece/Loudspeaker: `setCommunicationDevice(type)` on S+, else (or when no such device)
 *     clear + `isSpeakerphoneOn = loudspeaker` + stop SCO;
 *     BluetoothCallAudio: S+ `setCommunicationDevice(bt)`, else speakerphone off + `startBluetoothSco` + scoOn;
 *     BluetoothMedia: clear + speakerphone off + stop SCO; BluetoothUnsupported → as Loudspeaker;
 *     WiredHeadphone: S+ pin if listed, else clear + speakerphone off + stop SCO.
 *  3. A `SecurityException` from any S+ call never escapes (missing BLUETOOTH_CONNECT).
 * [release]: clear comm device (S+), stop SCO, mode → NORMAL.
 */
interface RouteApplier {
    fun availableOutputs(): AvailableOutputs
    fun apply(route: Route): AppliedRoute
    fun release()
}
