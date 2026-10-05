package com.assistant.core.testing

import com.assistant.core.audio.ports.AppliedRoute
import com.assistant.core.audio.ports.AudioDeviceRef
import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.audio.ports.OutputChoice
import com.assistant.core.audio.ports.ProviderKind
import com.assistant.core.audio.ports.Route
import com.assistant.core.audio.ports.SpeakerMode
import com.assistant.core.voice.ports.AudioSessionPort
import com.assistant.core.voice.ports.ConnectionSignal
import com.assistant.core.voice.ports.ConnectionType
import com.assistant.core.voice.ports.OrchestratorContext
import com.assistant.core.voice.ports.ProviderPhase
import com.assistant.core.voice.ports.ProviderPhaseState
import com.assistant.core.voice.ports.ProviderSignal
import com.assistant.core.voice.ports.TranscriptSink
import com.assistant.core.voice.ports.VoiceBackendApi
import com.assistant.core.voice.ports.VoiceConnection
import com.assistant.core.voice.ports.VoiceCues
import com.assistant.core.voice.ports.VoiceInbound
import com.assistant.core.voice.ports.VoiceSessionController
import com.assistant.core.voice.ports.VoiceSessionEvent
import com.assistant.core.voice.ports.VoiceSessionState
import com.assistant.core.voice.ports.VoiceStartConfig
import com.assistant.core.voice.ports.VoiceStartRequest
import com.assistant.core.voice.ports.VoiceTransport
import com.assistant.core.voice.ports.VoiceTransportFactory
import com.assistant.core.voice.ports.VoiceWire
import com.assistant.core.voice.ports.WakeHandoff
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** Records every orchestrator-socket write the voice core makes. */
class FakeVoiceWire(private val clock: MonotonicClock, private val order: OrderLog? = null) : VoiceWire {
    sealed interface Sent {
        val atMs: Long
        data class Start(val request: VoiceStartRequest, override val atMs: Long) : Sent
        data class Stop(override val atMs: Long) : Sent
        data class Event(val event: JsonObject, override val atMs: Long) : Sent
        data class AudioIn(val audioB64: String, override val atMs: Long) : Sent
        data class ResumeStart(val localId: String, val sdkSessionId: String?, override val atMs: Long) : Sent
    }

    val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
    val voiceStarts: List<Sent.Start> get() = sent.filterIsInstance<Sent.Start>()
    val voiceStops: List<Sent.Stop> get() = sent.filterIsInstance<Sent.Stop>()
    val resumeStarts: List<Sent.ResumeStart> get() = sent.filterIsInstance<Sent.ResumeStart>()
    val events: List<Sent.Event> get() = sent.filterIsInstance<Sent.Event>()
    val audioIn: List<Sent.AudioIn> get() = sent.filterIsInstance<Sent.AudioIn>()

    override fun sendVoiceStart(request: VoiceStartRequest) { order?.add("wire.voiceStart"); sent += Sent.Start(request, clock.nowMs()) }
    override fun sendVoiceStop() { order?.add("wire.voiceStop"); sent += Sent.Stop(clock.nowMs()) }
    override fun sendVoiceEvent(event: JsonObject) { sent += Sent.Event(event, clock.nowMs()) }
    override fun sendVoiceAudioIn(audioB64: String) { sent += Sent.AudioIn(audioB64, clock.nowMs()) }
    override fun sendResumeStart(localId: String, sdkSessionId: String?) { sent += Sent.ResumeStart(localId, sdkSessionId, clock.nowMs()) }
}

class FakeVoiceApi(
    private val clock: MonotonicClock,
    var config: VoiceStartConfig? = DEFAULT_CONFIG,
    var connection: VoiceConnection? = WEBRTC_CONNECTION,
    var latencyMs: Long = 50,
    private val order: OrderLog? = null,
) : VoiceBackendApi {
    val configCalls: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    val sessionCalls: MutableList<Pair<VoiceStartConfig, Long>> = Collections.synchronizedList(mutableListOf())

    override suspend fun getVoiceConfig(): VoiceStartConfig? {
        order?.add("api.getVoiceConfig")
        configCalls += clock.nowMs()
        delay(latencyMs)
        return config
    }

    override suspend fun startVoiceSession(config: VoiceStartConfig): VoiceConnection? {
        order?.add("api.startVoiceSession")
        sessionCalls += config to clock.nowMs()
        delay(latencyMs)
        return connection
    }

    companion object {
        val DEFAULT_CONFIG = VoiceStartConfig("openai", "gpt-realtime", "cedar", "", "")
        val WEBRTC_CONNECTION = VoiceConnection(
            ConnectionType.WEBRTC, "https://api.openai.com/v1/realtime/calls?model=gpt-realtime",
            "ek_test", "gpt-realtime", "cedar", 24000, 24000,
        )
        val WS_CONNECTION = VoiceConnection(ConnectionType.WEBSOCKET, "", "", "gemini-live", "Puck", 16000, 24000)
    }
}

data class FakeOrchestratorContext(
    override var isOrchestratorSession: Boolean = true,
    override var localId: String = "local-1",
    override var jsonlSessionId: String? = "jsonl-1",
    override var currentSessionId: String? = "sdk-1",
) : OrchestratorContext

/**
 * Wake hand-off. By default acks complete immediately; set [autoAck] false to hold them (the
 * controller must proceed after `WAKE_WORD_ACK_TIMEOUT_MS`).
 */
class FakeWakeHandoff(private val clock: MonotonicClock, var autoAck: Boolean = true, private val order: OrderLog? = null) : WakeHandoff {
    val pauses: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    val resumes: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    val pendingAcks: MutableList<CompletableDeferred<Unit>> = Collections.synchronizedList(mutableListOf())

    private fun ack(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { if (autoAck) it.complete(Unit) else pendingAcks += it }

    override fun pauseWake(): Deferred<Unit> { order?.add("wake.pause"); pauses += clock.nowMs(); return ack() }
    override fun resumeWake(): Deferred<Unit> { order?.add("wake.resume"); resumes += clock.nowMs(); return ack() }
}

class RecordingTranscriptSink : TranscriptSink {
    val entries: MutableList<String> = Collections.synchronizedList(mutableListOf())
    override fun userTranscript(text: String, final: Boolean) { entries += "user:$text" }
    override fun assistantTranscript(text: String, final: Boolean) { entries += "assistant:$text" }
    override fun system(text: String) { entries += "system:$text" }
    override fun voiceMessageSent() { entries += "voiceMessageSent" }
    override fun turnComplete() { entries += "turnComplete" }
    override fun voiceEnded() { entries += "voiceEnded" }
}

class RecordingCues(private val order: OrderLog? = null) : VoiceCues {
    val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
    override fun wakeAck() { played += "wakeAck"; order?.add("cue.wakeAck") }
    override fun talkAck() { played += "talkAck"; order?.add("cue.talkAck") }
    override fun reconnect() { played += "reconnect"; order?.add("cue.reconnect") }
}

class FakeAudioSession(private val clock: MonotonicClock) : AudioSessionPort {
    data class Apply(val provider: ProviderKind, val desired: OutputChoice, val atMs: Long)

    var result: (ProviderKind, OutputChoice) -> AppliedRoute = { _, _ -> AppliedRoute(Route.SystemDefault, SpeakerMode.CALL, null) }
    var focusAcquired = 0
    var released = 0
    val applies: MutableList<Apply> = Collections.synchronizedList(mutableListOf())

    override fun acquireFocus() { focusAcquired++ }
    override fun applyRoute(provider: ProviderKind, desired: OutputChoice): AppliedRoute {
        applies += Apply(provider, desired, clock.nowMs())
        return result(provider, desired)
    }
    override fun release() { released++ }
}

/** Provider transport stand-in. Tests drive phase/signals; every call is recorded. */
class FakeVoiceTransport(
    override val providerId: String,
    override val kind: ProviderKind,
    private val clock: MonotonicClock,
) : VoiceTransport {
    val phaseFlow = MutableStateFlow(ProviderPhaseState(ProviderPhase.OFF))
    private val signalFlow = MutableSharedFlow<ProviderSignal>(extraBufferCapacity = 256)
    override val phase: StateFlow<ProviderPhaseState> get() = phaseFlow
    override val signals: SharedFlow<ProviderSignal> get() = signalFlow

    val commands: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    val providerEvents: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    val speakerChunks: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val speakerModes: MutableList<SpeakerMode> = Collections.synchronizedList(mutableListOf())
    var connectedAtMs: Long? = null
    var connectInfo: VoiceConnection? = null
    var disconnects = 0
    var micGain: Float? = null
    var duckGain: Float? = null
    var muted = false
    var fallback: (() -> JsonObject?)? = null
    var commandsBeforeConnect = 0
    var mirror: ((JsonObject) -> Unit)? = null
    var micSink: ((String) -> Unit)? = null

    /** What [connect] does; default: CONNECTING then ACTIVE + SessionCreated. */
    var onConnect: suspend FakeVoiceTransport.() -> Unit = {
        phaseFlow.value = ProviderPhaseState(ProviderPhase.CONNECTING)
        phaseFlow.value = ProviderPhaseState(ProviderPhase.ACTIVE)
        emit(ProviderSignal.SessionCreated)
    }

    fun emit(signal: ProviderSignal) { check(signalFlow.tryEmit(signal)) }
    fun setPhase(phase: ProviderPhase, message: String? = null) { phaseFlow.value = ProviderPhaseState(phase, message) }

    override fun setSessionUpdateFallback(fallback: () -> JsonObject?) { this.fallback = fallback }
    override fun setMicGain(level: Float) { micGain = level }
    override fun setEchoDuckingGain(gain: Float) { duckGain = gain }
    override fun toggleMute(): Boolean { muted = !muted; return muted }
    override fun setSpeakerMode(mode: SpeakerMode, preferredDevice: AudioDeviceRef?) { speakerModes += mode }

    override suspend fun connect(info: VoiceConnection, mirrorEvent: (JsonObject) -> Unit, sendMicChunk: (String) -> Unit) {
        connectInfo = info
        connectedAtMs = clock.nowMs()
        commandsBeforeConnect = commands.size
        mirror = mirrorEvent
        micSink = sendMicChunk
        onConnect()
    }

    override fun handleBackendCommand(command: JsonObject) { commands += command }
    override fun handleProviderEvent(event: JsonObject) { providerEvents += event }
    override fun pushSpeakerChunk(audioB64: String) { speakerChunks += audioB64 }
    override suspend fun disconnect() {
        disconnects++
        phaseFlow.value = ProviderPhaseState(ProviderPhase.OFF)
        emit(ProviderSignal.SessionEnded)
    }
}

class FakeVoiceTransportFactory(private val clock: MonotonicClock) : VoiceTransportFactory {
    val created: MutableList<FakeVoiceTransport> = Collections.synchronizedList(mutableListOf())
    val requests: MutableList<Pair<String, ConnectionType>> = Collections.synchronizedList(mutableListOf())
    var configure: (FakeVoiceTransport) -> Unit = {}

    val last: FakeVoiceTransport get() = created.last()

    override fun create(providerId: String, connectionType: ConnectionType): VoiceTransport {
        requests += providerId to connectionType
        val kind = if (connectionType == ConnectionType.WEBRTC) ProviderKind.WEBRTC else ProviderKind.WEBSOCKET
        return FakeVoiceTransport(providerId, kind, clock).also { configure(it); created += it }
    }
}

/** Stand-in for the voice controller in host/trigger tests. Records calls in [order]. */
class FakeVoiceSessionController(private val order: OrderLog) : VoiceSessionController {
    val stateFlow = MutableStateFlow(VoiceSessionState())
    override val state: StateFlow<VoiceSessionState> get() = stateFlow
    override val events: SharedFlow<VoiceSessionEvent> = MutableSharedFlow()
    val inbound: MutableList<VoiceInbound> = Collections.synchronizedList(mutableListOf())
    val connection: MutableList<ConnectionSignal> = Collections.synchronizedList(mutableListOf())

    override fun markConnecting() { order.add("voice.markConnecting") }
    override fun startVoice() { order.add("voice.startVoice") }
    override fun stopVoice() { order.add("voice.stopVoice") }
    override fun toggleMute() { order.add("voice.toggleMute") }
    override fun setMicGain(level: Float) { order.add("voice.setMicGain") }
    override fun setEchoDuckingGain(gain: Float) { order.add("voice.setEchoDuckingGain") }
    override fun setAudioOutput(output: OutputChoice) { order.add("voice.setAudioOutput") }
    override fun onInbound(frame: VoiceInbound) { inbound += frame }
    override fun onConnection(signal: ConnectionSignal) { connection += signal }
    override fun release() { order.add("voice.release") }
}
