package com.assistant.core.voice.ports

import com.assistant.core.audio.ports.MicSourceType
import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.audio.ports.VoiceLog
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope

/*
 * Thin port over `org.webrtc` (stream-webrtc-android 1.1.1) so the OpenAI transport is testable on
 * the JVM with a fake PeerConnection factory (inv04 §2.2; RS-06, RS-07, RS-08). Interface-only (A-04).
 *
 * Threading rule (`0196e2a`, `91a5df5`): NEVER close/dispose a WebRTC object from a WebRTC
 * callback thread. ICE DISCONNECTED is transient (observe only); FAILED and an OpenAI `error`
 * event tear down on the transport's own scope. Teardown order: data channel close → send-track
 * disable + dispose → PeerConnection close → PeerConnection dispose → factory dispose; reentrant
 * cleanup disposes each object exactly once.
 */

enum class IceState { NEW, CHECKING, CONNECTED, COMPLETED, DISCONNECTED, FAILED, CLOSED }

/** JavaAudioDeviceModule + WebRtcAudioUtils switches (inv04 §4.9, **LB**). */
data class RtcAudioOptions(
    val useHardwareAec: Boolean,
    val useHardwareNs: Boolean,
    val webRtcBasedAec: Boolean,
    val webRtcBasedNs: Boolean,
    val webRtcBasedAgc: Boolean,
    val micSource: MicSourceType,
    /** `MediaConstraints.mandatory` of the audio source (the goog* set). */
    val mandatoryConstraints: Map<String, String>,
)

data class RtcPeerOptions(
    val unifiedPlan: Boolean,
    val maxBundle: Boolean,
    val sendTrack: Boolean,
    val recvOnlyAudioTransceiver: Boolean,
    val dataChannelLabel: String,
    val dataChannelOrdered: Boolean,
)

/** Callbacks arrive on WebRTC's native threads. */
interface RtcPeerObserver {
    fun onIceConnectionState(state: IceState)
    fun onDataChannelOpen()
    fun onDataChannelMessage(text: String)
}

interface RtcPlatform {
    /** `PeerConnectionFactory.initialize(...)`: at most once per process (`2a33e8d`). */
    fun initializeGlobals()

    /** [micHook] runs on the ADM record thread before WebRTC processing (gain is applied here). */
    fun createFactory(audio: RtcAudioOptions, micHook: (ByteBuffer) -> Unit): RtcFactory
}

interface RtcFactory {
    fun createPeer(options: RtcPeerOptions, observer: RtcPeerObserver): RtcPeer
    fun dispose()
}

interface RtcPeer {
    /** createOffer + setLocalDescription; returns the local SDP. */
    suspend fun createOffer(): String
    suspend fun setRemoteAnswer(sdp: String)
    val isDataChannelOpen: Boolean
    fun sendOnDataChannel(text: String): Boolean
    fun setSendTrackEnabled(enabled: Boolean)
    fun closeDataChannel()
    fun disposeSendTrack()
    fun close()
    fun dispose()
}

/** `POST <endpoint>` `Content-Type: application/sdp`, `Authorization: Bearer <ephemeral>` → answer SDP. */
fun interface SdpExchange {
    suspend fun exchange(endpoint: String, ephemeralToken: String, offerSdp: String): String?
}

class WebRtcDeps(
    val scope: CoroutineScope,
    val clock: MonotonicClock,
    val log: VoiceLog,
    val sdkInt: Int,
    val platform: RtcPlatform,
    val sdp: SdpExchange,
)
