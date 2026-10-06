package com.assistant.core.voicehost

import android.app.Activity
import com.assistant.core.audio.DefaultAudioCore
import com.assistant.core.model.AudioOutput
import com.assistant.core.model.PoolSession
import com.assistant.core.network.FrameSocket
import com.assistant.core.network.SendResult
import com.assistant.core.network.SocketEvent
import com.assistant.core.network.SocketState
import com.assistant.core.protocol.ClientFrame
import com.assistant.core.protocol.ServerFrame
import com.assistant.core.session.OrchestratorChannel
import com.assistant.core.session.OrchestratorIdStore
import com.assistant.core.session.PoolApi
import com.assistant.core.testing.FakeAudioSession
import com.assistant.core.testing.FakeClock
import com.assistant.core.testing.FakeVoiceApi
import com.assistant.core.testing.FakeVoiceTransportFactory
import com.assistant.core.testing.FakeWakeConfigStore
import com.assistant.core.testing.RecordingLog
import com.assistant.core.testing.RecordingTranscriptSink
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voicehost.cue.CueKind
import com.assistant.core.voicehost.cue.CuePlayer
import com.assistant.core.voicehost.runtime.RuntimeDeps
import com.assistant.core.voicehost.runtime.ServiceControl
import com.assistant.core.voicehost.runtime.VoiceHostRuntime
import com.assistant.core.voicehost.ports.WakeServiceConfig
import com.assistant.core.voicehost.trigger.ForegroundBringer
import com.assistant.core.wakeword.ports.EngineKind
import com.assistant.core.wakeword.ports.WakeLoopEvent
import com.assistant.core.wakeword.ports.WakePhase
import com.assistant.core.wakeword.ports.WakeWordEngine
import java.util.Collections
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** Scripted orchestrator socket (the channel's only I/O). */
class FakeFrameSocket : FrameSocket {
    val sent: MutableList<ClientFrame> = Collections.synchronizedList(mutableListOf())
    val connects = mutableListOf<String>()
    override val state = MutableStateFlow<SocketState>(SocketState.Idle)
    private val ch = Channel<SocketEvent>(Channel.UNLIMITED)
    override val events: Flow<SocketEvent> = ch.receiveAsFlow()

    fun open() { state.value = SocketState.Open; ch.trySend(SocketEvent.Opened) }
    fun drop(willReconnect: Boolean = true) {
        state.value = SocketState.Disconnected(willReconnect, "drop")
        ch.trySend(SocketEvent.Closed(willReconnect, 1006, "drop"))
    }
    fun frame(f: ServerFrame) { ch.trySend(SocketEvent.Frame(f)) }

    override fun connect(url: String) { connects += url }
    override fun disconnect() { state.value = SocketState.Disconnected(false, "client") }
    override fun send(frame: ClientFrame): SendResult =
        if (state.value == SocketState.Open) { sent += frame; SendResult.SENT } else SendResult.NOT_CONNECTED
    override fun reconnectNow() = Unit
    override fun resetBackoff() = Unit
    override fun setReconnectAllowed(allowed: Boolean) = Unit
}

class FixedPool(var sessions: List<PoolSession>?) : PoolApi {
    override suspend fun livePool(): List<PoolSession>? = sessions
    override suspend fun close(localId: String): Boolean = true
}

class MemoryIds : OrchestratorIdStore {
    var value: String? = null
    override suspend fun load() = value
    override suspend fun save(localId: String) { value = localId }
    override suspend fun clear() { value = null }
}

/** A wake engine driven by the test: phases and events are set directly. */
class FakeWakeEngine(val config: WakeServiceConfig) : WakeWordEngine {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    override val phase = MutableStateFlow(WakePhase.STOPPED)
    private val _events = MutableSharedFlow<WakeLoopEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<WakeLoopEvent> = _events
    override val engineKind: EngineKind? = EngineKind.VOSK

    fun emit(e: WakeLoopEvent) = check(_events.tryEmit(e))

    override fun start() { calls += "start"; if (phase.value == WakePhase.STOPPED) phase.value = WakePhase.MONITORING }
    override fun pause() { calls += "pause"; phase.value = WakePhase.PAUSED }
    override fun resume() { calls += "resume"; phase.value = WakePhase.MONITORING }
    override fun stop() { calls += "stop"; phase.value = WakePhase.STOPPED }
    override fun release() { calls += "release" }
}

class RecordingCuePlayer : CuePlayer {
    data class Played(val kind: CueKind, val atMs: Long)

    var clock: FakeClock? = null
    val played: MutableList<Played> = Collections.synchronizedList(mutableListOf())
    override fun play(kind: CueKind) { played += Played(kind, clock?.nowMs() ?: 0L) }
    fun kinds() = played.map { it.kind }
    fun count(kind: CueKind) = played.count { it.kind == kind }
}

class FakePushToTalk(var opens: Boolean = true) : com.assistant.core.voicehost.runtime.PushToTalkRecorder {
    val calls = mutableListOf<String>()
    override fun start(): Boolean { calls += "start"; return opens }
    override suspend fun stop(): ByteArray? { calls += "stop"; return ByteArray(44) { 2 } }
}

class RecordingServiceControl(var allowed: Boolean = true) : ServiceControl {
    var starts = 0
    var stops = 0
    override fun startFromForeground(): Boolean { starts++; return allowed }
    override fun stop() { stops++ }
}

/** A full runtime over fakes: real channel, real voice session controller, real wake-service logic. */
class HostRig(
    val ts: TestScope,
    val config: HostConfig = HostConfig.lite(Activity::class.java),
    pool: List<PoolSession>? = listOf(ORCH),
) {
    val clock = FakeClock.boundTo(ts.testScheduler)
    val log = RecordingLog()
    val socket = FakeFrameSocket()
    val pool = FixedPool(pool)
    val channel = OrchestratorChannel(socket, this.pool, MemoryIds(), ts.backgroundScope, OrchestratorChannel.Config(autoStart = true))
    val settings = MutableStateFlow<VoiceHostSettings?>(null)
    val store = FakeWakeConfigStore()
    val engines: MutableList<FakeWakeEngine> = Collections.synchronizedList(mutableListOf())
    val api = FakeVoiceApi(clock)
    val transports = FakeVoiceTransportFactory(clock)
    val audio = FakeAudioSession(clock)
    val transcripts = RecordingTranscriptSink()
    val cues = RecordingCuePlayer().also { it.clock = clock }
    var brought = 0
    val service = RecordingServiceControl()
    val buttonPrefs = mutableListOf<Boolean>()
    val ptt = FakePushToTalk()

    val runtime = VoiceHostRuntime(
        RuntimeDeps(
            scope = ts.backgroundScope,
            clock = clock,
            log = log,
            config = config,
            channel = channel,
            settings = settings,
            wakeStore = store,
            wakeEngines = { cfg -> FakeWakeEngine(cfg).also { engines += it } },
            voiceApi = api,
            transports = transports,
            audio = audio,
            transcripts = transcripts,
            cuePlayer = cues,
            bringer = ForegroundBringer { brought++ },
            pcm = DefaultAudioCore().pcm,
            service = service,
            buttonTriggerPref = { buttonPrefs += it },
            pushToTalk = ptt,
        ),
    )

    fun advance(ms: Long) { ts.testScheduler.advanceTimeBy(ms); ts.runCurrent() }
    fun settle() = ts.runCurrent()

    val state get() = runtime.state.value
    val phase get() = runtime.session.state.value.phase

    /** Settings loaded → connect → socket open → probe adopts ORCH → session_started. */
    fun connectAndAdopt(s: VoiceHostSettings = SETTINGS) {
        settings.value = s
        settle()
        socket.open()
        settle()
        socket.frame(ServerFrame.SessionStarted(sessionId = ORCH.localId, jsonlId = ORCH.sdkId))
        settle()
    }

    /** Voice on, ACTIVE (the fake transport goes ACTIVE on connect). */
    fun startActiveVoice() {
        runtime.startVoice(com.assistant.core.voicehost.ports.Trigger.BUTTON)
        advance(1_000)
        check(phase == SessionPhase.ACTIVE) { "voice not active: $phase\n${log.dump()}" }
    }

    companion object {
        val ORCH = PoolSession("orch-1", "sdk-1", null, 0.0, 0, "Archie", isOrchestrator = true)

        val SETTINGS = VoiceHostSettings(
            serverUrl = "ws://192.168.0.200:80",
            enableWakeWord = true,
            talkWord = "my friend",
            wakeWord = "wake up",
            wakeGain = 1.3f,
            talkSilenceSensitivity = 2.5f,
            micGain = 1.0f,
            echoDuckingGain = 0.05f,
            audioOutput = AudioOutput.AUTO,
            buttonTriggerEnabled = true,
        )
    }
}
