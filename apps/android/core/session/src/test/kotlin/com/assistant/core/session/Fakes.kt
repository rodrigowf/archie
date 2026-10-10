package com.assistant.core.session

import com.assistant.core.model.LivePoolSnapshot
import com.assistant.core.model.PoolSession
import com.assistant.core.network.FrameSocket
import com.assistant.core.network.SendResult
import com.assistant.core.network.SocketEvent
import com.assistant.core.network.SocketState
import com.assistant.core.protocol.ClientFrame
import com.assistant.core.protocol.ServerFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class FakeFrameSocket : FrameSocket {
    val sent = mutableListOf<ClientFrame>()
    val connects = mutableListOf<String>()
    var disconnects = 0
    var reconnectNows = 0
    var backoffResets = 0
    val reconnectAllowed = mutableListOf<Boolean>()
    override val state = MutableStateFlow<SocketState>(SocketState.Idle)
    private val ch = Channel<SocketEvent>(Channel.UNLIMITED)
    override val events: Flow<SocketEvent> = ch.receiveAsFlow()

    fun open() { state.value = SocketState.Open; ch.trySend(SocketEvent.Opened) }
    fun drop(willReconnect: Boolean = true) {
        state.value = SocketState.Disconnected(willReconnect, "drop"); ch.trySend(SocketEvent.Closed(willReconnect, 1006, "drop"))
    }
    fun frame(f: ServerFrame) { ch.trySend(SocketEvent.Frame(f)) }

    override fun connect(url: String) { connects += url }
    override fun disconnect() { disconnects++; state.value = SocketState.Disconnected(false, "client") }
    override fun send(frame: ClientFrame): SendResult =
        if (state.value == SocketState.Open) { sent += frame; SendResult.SENT } else SendResult.NOT_CONNECTED
    override fun reconnectNow() { reconnectNows++ }
    override fun resetBackoff() { backoffResets++ }
    override fun setReconnectAllowed(allowed: Boolean) { reconnectAllowed += allowed }

    /** Frames that would end or detach a session: never allowed from lifecycle paths (P-1). */
    fun stopLikeFrames() = sent.filter { it == ClientFrame.Stop || it == ClientFrame.VoiceStop }
}

class FakePool(private val now: () -> Long) : PoolApi {
    /** Responses in order; the last one repeats. */
    val script = ArrayDeque<List<PoolSession>?>()
    val calls = mutableListOf<Long>()
    val closes = mutableListOf<String>()
    override suspend fun livePool(): List<PoolSession>? {
        calls += now()
        return if (script.size > 1) script.removeFirst() else script.firstOrNull()
    }
    override suspend fun close(localId: String): Boolean { closes += localId; return true }

    /** `X-Archie-Server-Id` of every answer (SRV-1); `null` = a server that does not send it. */
    var serverId: String? = null
    override suspend fun livePoolSnapshot(): LivePoolSnapshot? = livePool()?.let { LivePoolSnapshot(it, serverId) }
}

class FakeIds : OrchestratorIdStore {
    var value: String? = null
    var clears = 0
    override suspend fun load() = value
    override suspend fun save(localId: String) { value = localId }
    override suspend fun clear() { value = null; clears++ }
}

fun orch(localId: String = "ORCH", sdk: String? = "JSONL") =
    PoolSession(localId, sdk, null, 0.0, 0, "Orchestrator", isOrchestrator = true)
fun agent(localId: String = "AG") = PoolSession(localId, "S-$localId", null, 0.0, 0, "agent", isOrchestrator = false)

fun <T> ReceiveChannel<T>.drain(): List<T> = buildList { while (true) add(tryReceive().getOrNull() ?: break) }

fun started(localId: String = "ORCH", jsonl: String? = "JSONL") = ServerFrame.SessionStarted(
    sessionId = localId, contextWindow = null, resumeState = null, replayOverflow = false, jsonlId = jsonl,
    voice = false, modelInfo = null, voiceInitiator = null, voiceProvider = null, voiceModel = null,
    voiceName = null, voiceTranscriptionLanguage = null, voiceRecordingEnabled = null, voiceSessionUpdate = null,
    voiceConnectionInfo = null, voiceConnectionError = null, seq = null, streamId = null,
)

fun orchestratorActive() = ServerFrame.Error("orchestrator_active", "another orchestrator is running", null, null)
