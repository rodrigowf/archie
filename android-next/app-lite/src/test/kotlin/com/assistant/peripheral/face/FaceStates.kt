package com.assistant.peripheral.face

import com.assistant.core.network.SocketState
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceSessionState
import com.assistant.core.voice.session.VoiceLinkState
import com.assistant.core.voicehost.HostConnection
import com.assistant.core.voicehost.LastExchange
import com.assistant.core.voicehost.VoiceUiState
import com.assistant.core.voicehost.WakeHealth
import com.assistant.core.voicehost.ports.TalkUiState

/**
 * The named face scenes (spec 14 §6.4 `lite-face-*`), shared by [FaceModelTest] and the goldens.
 * Captions follow the approved mockup §4.
 */
object FaceStates {
    private val connected = VoiceUiState(connection = HostConnection.CONNECTED, serverUrl = "ws://192.168.0.200:80", wake = WakeHealth.ARMED)
    private val lastDoor = LastExchange("Is the front door locked?", "Yes, locked since 22:10.")
    private val weather = LastExchange("What's the weather tomorrow?", "Sunny and 29 degrees, with a sea breeze after 3 pm.")

    data class Scene(val name: String, val state: VoiceUiState, val socket: SocketState, val exchange: LastExchange, val retryInMs: Long? = null)

    val scenes: List<Scene> = listOf(
        Scene("ready", connected, SocketState.Open, lastDoor),
        Scene("listening", connected.copy(session = live(SessionPhase.ACTIVE).copy(vadState = "speech", vadDurationMs = 1_200)), SocketState.Open,
            LastExchange("What's the weather tomorrow", null)),
        Scene("speaking", connected.copy(session = live(SessionPhase.SPEAKING)), SocketState.Open, weather),
        Scene("elsewhere", connected.copy(session = VoiceSessionState(remoteVoiceActive = true)), SocketState.Open,
            LastExchange("Pause the movie.", "Paused.")),
        Scene("offline", connected.copy(connection = HostConnection.CONNECTING), SocketState.Disconnected(true, "timeout"), weather, retryInMs = 4_200),
        Scene("reconnecting", connected.copy(
            session = live(SessionPhase.ACTIVE),
            link = VoiceLinkState.Reconnecting(sinceMs = 0, elapsedMs = 7_400, budgetMs = 30_000, attempts = 1, socketBack = false),
        ), SocketState.Disconnected(true, "reset"), LastExchange("What's the weather tomorrow?", null)),
        Scene("confirming", connected.copy(talk = TalkUiState(wakeConfirming = true)), SocketState.Open, lastDoor),
        Scene("recording", connected.copy(talk = TalkUiState(isRecording = true)), SocketState.Open, lastDoor),
        Scene("error", connected.copy(session = VoiceSessionState(phase = SessionPhase.ERROR, errorMessage = "Failed to start voice session (no connection info)")),
            SocketState.Open, lastDoor),
        Scene("mic-stalled", connected.copy(wake = WakeHealth.MIC_STALLED), SocketState.Open, lastDoor),
    )

    fun live(phase: SessionPhase) = VoiceSessionState(phase = phase, isOwner = true)

    fun model(scene: Scene) = FaceModel.from(scene.state, scene.socket, "jetson", scene.exchange, "wake up", "my friend", scene.retryInMs)
}
