package com.assistant.core.audio.policy

import com.assistant.core.audio.AudioTuning
import com.assistant.core.audio.ports.AudioFocusPolicy
import com.assistant.core.audio.ports.AudioStream
import com.assistant.core.audio.ports.BufferSizing
import com.assistant.core.audio.ports.CallVolumePolicy
import com.assistant.core.audio.ports.FocusContentType
import com.assistant.core.audio.ports.FocusGain
import com.assistant.core.audio.ports.FocusRequestSpec
import com.assistant.core.audio.ports.FocusUsage
import com.assistant.core.audio.ports.MicSourcePolicy
import com.assistant.core.audio.ports.MicSourceType
import com.assistant.core.audio.ports.PlaybackApi
import com.assistant.core.audio.ports.PlaybackPolicy
import com.assistant.core.audio.ports.SpeakerMode
import com.assistant.core.audio.ports.TrackConstructor
import com.assistant.core.audio.ports.TrackWriteMode
import kotlin.math.max

/*
 * API-level branches as pure policies on `sdkInt` (inv04 §5.1, spec 14 §6.1). The Android adapters
 * consult these instead of reading Build.VERSION themselves, so every branch runs on the JVM.
 */

/** Old `MicCapture.kt:79-82` / `WakeWordDetector.kt:656-659` / `OpenAIVoiceProvider.kt:354-357`. */
object DefaultMicSourcePolicy : MicSourcePolicy {
    override fun sourceFor(sdkInt: Int): MicSourceType =
        if (sdkInt < AudioTuning.MIC_SOURCE_SWITCH_SDK) MicSourceType.VOICE_RECOGNITION else MicSourceType.VOICE_COMMUNICATION
}

/** Old `PcmPlayback.kt:154, 194-208, 316-351`; `VoiceManager.kt:612` (device callback). */
object DefaultPlaybackPolicy : PlaybackPolicy {
    private val modern = PlaybackApi(
        writeMode = TrackWriteMode.NON_BLOCKING_4ARG,
        constructor = TrackConstructor.BUILDER_WITH_ATTRIBUTES,
        canSetPreferredDevice = true,
        hasDeviceCallback = true,
    )
    private val lollipop = PlaybackApi(
        writeMode = TrackWriteMode.BLOCKING_3ARG,
        constructor = TrackConstructor.LEGACY_STREAM_TYPE,
        canSetPreferredDevice = false,
        hasDeviceCallback = false,
    )

    override fun forSdk(sdkInt: Int): PlaybackApi = if (sdkInt >= AudioTuning.SDK_M) modern else lollipop

    override fun legacyStreamFor(mode: SpeakerMode): AudioStream = when (mode) {
        SpeakerMode.CALL -> AudioStream.VOICE_CALL
        SpeakerMode.MEDIA -> AudioStream.MUSIC
    }
}

/** Old `VoiceManager.requestAudioFocus` (:487-512). */
object DefaultAudioFocusPolicy : AudioFocusPolicy {
    override fun forSdk(sdkInt: Int): FocusRequestSpec = FocusRequestSpec(
        gain = FocusGain.GAIN_TRANSIENT_EXCLUSIVE,
        usage = FocusUsage.VOICE_COMMUNICATION,
        contentType = FocusContentType.SPEECH,
        useAudioFocusRequest = sdkInt >= AudioTuning.SDK_O,
        legacyStream = AudioStream.VOICE_CALL,
    )
}

/** Old `MicCapture.kt:74` and `PcmPlayback.kt:148-149`. */
object DefaultBufferSizing : BufferSizing {
    override fun micBufferBytes(minBufferBytes: Int, sampleRateHz: Int): Int = max(
        minBufferBytes * AudioTuning.BUFFER_MIN_MULTIPLIER,
        sampleRateHz * AudioTuning.MIC_BUFFER_FLOOR_NUMERATOR / AudioTuning.MIC_BUFFER_FLOOR_DENOMINATOR,
    )

    override fun speakerBufferBytes(minBufferBytes: Int, sampleRateHz: Int): Int {
        val bytesPerSecond = sampleRateHz * 2 // mono PCM16
        return max(minBufferBytes * AudioTuning.BUFFER_MIN_MULTIPLIER, (bytesPerSecond * AudioTuning.SPEAKER_BUFFER_SECONDS).toInt())
    }
}

/** Old `VoiceManager.ensureCallStreamAudible` (:519-528). */
object DefaultCallVolumePolicy : CallVolumePolicy {
    override fun raisedVolume(current: Int, max: Int): Int? =
        if (current == 0) (max * AudioTuning.CALL_VOLUME_RAISE_FRACTION).toInt().coerceAtLeast(1) else null
}

/**
 * What the [com.assistant.core.audio.routing.PolicyRouteApplier] may call on a given API level
 * (old `AudioRouter.kt:199-347`, `20217b1`, `6fb1e54`).
 */
data class RouteApi(
    /** `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` — API 23+. Below it: BT profiles + sticky HEADSET_PLUG. */
    val canListOutputDevices: Boolean,
    /** `setCommunicationDevice` / `clearCommunicationDevice` / `availableCommunicationDevices` — API 31+. */
    val hasCommunicationDevice: Boolean,
    /** `TYPE_BLE_HEADSET` / `TYPE_BLE_SPEAKER` count as Bluetooth — API 31+. */
    val bleDeviceTypes: Boolean,
    /** `TYPE_USB_HEADSET` counts as a wired headphone — API 26+. */
    val usbHeadsetIsWired: Boolean,
) {
    companion object {
        fun forSdk(sdkInt: Int) = RouteApi(
            canListOutputDevices = sdkInt >= AudioTuning.SDK_M,
            hasCommunicationDevice = sdkInt >= AudioTuning.SDK_S,
            bleDeviceTypes = sdkInt >= AudioTuning.SDK_S,
            usbHeadsetIsWired = sdkInt >= AudioTuning.SDK_O,
        )
    }
}
