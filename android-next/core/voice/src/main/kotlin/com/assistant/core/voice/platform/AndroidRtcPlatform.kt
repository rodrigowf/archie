package com.assistant.core.voice.platform

import android.content.Context
import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.voice.ports.IceState
import com.assistant.core.voice.ports.RtcAudioOptions
import com.assistant.core.voice.ports.RtcFactory
import com.assistant.core.voice.ports.RtcPeer
import com.assistant.core.voice.ports.RtcPeerObserver
import com.assistant.core.voice.ports.RtcPeerOptions
import com.assistant.core.voice.ports.RtcPlatform
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.AudioRecordDataCallback
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.voiceengine.WebRtcAudioUtils

/**
 * The production [RtcPlatform] over `org.webrtc` (stream-webrtc-android 1.1.1, pinned for voice
 * parity). A 1:1 port of the old `OpenAIVoiceProvider.initializeWebRTC` / `cleanup` plumbing; all
 * policy (options, threading, teardown order) lives in `WebRtcTransport`. Not JVM-testable (JNI):
 * covered by the instrumented `NativeSmokeTest` of G-01 (spec 14 §1.10).
 */
class AndroidRtcPlatform(
    private val context: Context,
    private val log: VoiceLog,
) : RtcPlatform {

    override fun initializeGlobals() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
    }

    override fun createFactory(audio: RtcAudioOptions, micHook: (ByteBuffer) -> Unit): RtcFactory {
        // SOFTWARE-ONLY AEC (field choice): WebRTC's own AEC / NS / AGC, the hardware effects off.
        WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(audio.webRtcBasedAec)
        WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(audio.webRtcBasedNs)
        WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(audio.webRtcBasedAgc)
        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(audio.useHardwareAec)
            .setUseHardwareNoiseSuppressor(audio.useHardwareNs)
            .setAudioRecordDataCallback(AudioRecordDataCallback { _, _, _, buffer -> micHook(buffer) })
            .setAudioSource(audio.micSource.androidValue)
            .createAudioDeviceModule()
        log.d(TAG, "Audio source: ${audio.micSource} (HW AEC=${audio.useHardwareAec}, HW NS=${audio.useHardwareNs})")
        val factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        // The factory holds its own reference to the ADM (fixes the old ADM leak, inv04 R9).
        adm.release()
        val constraints = MediaConstraints().apply {
            audio.mandatoryConstraints.forEach { (k, v) -> mandatory.add(MediaConstraints.KeyValuePair(k, v)) }
        }
        return AndroidRtcFactory(factory, constraints, log)
    }

    private class AndroidRtcFactory(
        private val factory: PeerConnectionFactory,
        private val constraints: MediaConstraints,
        private val log: VoiceLog,
    ) : RtcFactory {
        override fun createPeer(options: RtcPeerOptions, observer: RtcPeerObserver): RtcPeer {
            val source = factory.createAudioSource(constraints)
            val track = factory.createAudioTrack("audio0", source)
            track.setEnabled(true)
            val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                if (options.unifiedPlan) sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                if (options.maxBundle) bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            }
            val pc = factory.createPeerConnection(config, PcObserver(observer, log))
                ?: throw IllegalStateException("createPeerConnection returned null")
            if (options.sendTrack) pc.addTrack(track, listOf("stream0"))
            if (options.recvOnlyAudioTransceiver) {
                pc.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
                )
            }
            val dc = pc.createDataChannel(options.dataChannelLabel, DataChannel.Init().apply { ordered = options.dataChannelOrdered })
                ?: throw IllegalStateException("createDataChannel returned null")
            dc.registerObserver(DcObserver(dc, observer))
            return AndroidRtcPeer(pc, dc, track, source)
        }

        override fun dispose() = factory.dispose()
    }

    private class AndroidRtcPeer(
        private val pc: PeerConnection,
        private val dc: DataChannel,
        private val track: AudioTrack,
        private val source: AudioSource,
    ) : RtcPeer {
        override suspend fun createOffer(): String = suspendCancellableCoroutine { cont ->
            pc.createOffer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    pc.setLocalDescription(object : SdpObserverAdapter() {
                        override fun onSetSuccess() { if (cont.isActive) cont.resume(sdp.description) }
                        override fun onSetFailure(error: String?) { fail("set local description", error) }
                    }, sdp)
                }
                override fun onCreateFailure(error: String?) { fail("create offer", error) }
                private fun fail(what: String, error: String?) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Failed to $what: $error"))
                }
            }, MediaConstraints())
        }

        override suspend fun setRemoteAnswer(sdp: String): Unit = suspendCancellableCoroutine { cont ->
            pc.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
                override fun onSetFailure(error: String?) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Failed to set remote description: $error"))
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        }

        override val isDataChannelOpen: Boolean get() = dc.state() == DataChannel.State.OPEN

        override fun sendOnDataChannel(text: String): Boolean {
            if (dc.state() != DataChannel.State.OPEN) return false
            return dc.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
        }

        override fun setSendTrackEnabled(enabled: Boolean) { track.setEnabled(enabled) }
        override fun closeDataChannel() = dc.close()
        override fun disposeSendTrack() = track.dispose()
        override fun close() = pc.close()

        override fun dispose() {
            pc.dispose()
            source.dispose()
        }
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    /** Native signalling-thread callbacks: forwarded, nothing else (the transport posts them to its own scope). */
    private class PcObserver(private val observer: RtcPeerObserver, private val log: VoiceLog) : PeerConnection.Observer {
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            observer.onIceConnectionState(
                when (state) {
                    PeerConnection.IceConnectionState.NEW -> IceState.NEW
                    PeerConnection.IceConnectionState.CHECKING -> IceState.CHECKING
                    PeerConnection.IceConnectionState.CONNECTED -> IceState.CONNECTED
                    PeerConnection.IceConnectionState.COMPLETED -> IceState.COMPLETED
                    PeerConnection.IceConnectionState.DISCONNECTED -> IceState.DISCONNECTED
                    PeerConnection.IceConnectionState.FAILED -> IceState.FAILED
                    PeerConnection.IceConnectionState.CLOSED -> IceState.CLOSED
                },
            )
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = log.d(TAG, "Signaling state: $state")
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = log.d(TAG, "ICE gathering state: $state")
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit
        override fun onAddStream(stream: MediaStream) = log.d(TAG, "Remote stream added with ${stream.audioTracks.size} audio tracks")
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = log.d(TAG, "Data channel opened from remote: ${channel.label()}")
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private class DcObserver(private val dc: DataChannel, private val observer: RtcPeerObserver) : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            if (dc.state() == DataChannel.State.OPEN) observer.onDataChannelOpen()
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            observer.onDataChannelMessage(String(bytes, Charsets.UTF_8))
        }
    }

    private companion object {
        const val TAG = "OpenAIVoiceProvider"
    }
}
