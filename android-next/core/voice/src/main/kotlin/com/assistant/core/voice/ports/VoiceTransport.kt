package com.assistant.core.voice.ports

import com.assistant.core.audio.ports.AudioCore
import com.assistant.core.audio.ports.AudioDeviceRef
import com.assistant.core.audio.ports.AudioTrackFactory
import com.assistant.core.audio.ports.MicSourceFactory
import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.audio.ports.ProviderKind
import com.assistant.core.audio.ports.SpeakerMode
import com.assistant.core.audio.ports.VoiceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/*
 * Provider transports and their pure event parsers (inv04 §2.2, §2.3). Interface-only (A-04).
 */

enum class ProviderPhase { OFF, CONNECTING, SUMMARIZING, ACTIVE, SPEAKING, THINKING, TOOL_USE, ERROR }

data class ProviderPhaseState(val phase: ProviderPhase, val message: String? = null)

/** Everything a provider can report (old `VoiceEvent` + phase changes + transport actions). */
sealed interface ProviderSignal {
    data class Phase(val phase: ProviderPhase, val message: String? = null) : ProviderSignal
    data object SessionCreated : ProviderSignal
    data object SessionEnded : ProviderSignal
    data object SpeechStarted : ProviderSignal
    data object SpeechStopped : ProviderSignal
    data object TurnComplete : ProviderSignal
    data class TextDelta(val text: String) : ProviderSignal
    data class TextComplete(val text: String) : ProviderSignal
    data class UserTranscript(val text: String) : ProviderSignal
    data class ToolUse(val callId: String, val name: String, val args: JsonObject) : ProviderSignal
    data class Error(val message: String) : ProviderSignal
    data class RoutingFallback(val message: String) : ProviderSignal
    data class ReconnectWarning(val timeLeftSeconds: Int?) : ProviderSignal
    data object Reconnecting : ProviderSignal

    /** Barge-in: drop queued + in-flight speaker audio and restore the mic now. */
    data object FlushSpeaker : ProviderSignal

    /** Tear the transport down (relay/provider error). */
    data object Teardown : ProviderSignal
}

enum class ParserKind { OPENAI, QWEN, GEMINI }

/**
 * Pure, possibly stateful (Gemini stages text) parser of one provider's events. The WebSocket
 * parsers (QWEN, GEMINI) also handle the backend-synthesised `voice_status` and `error` envelopes
 * (`WebSocketPcmProvider.handleProviderEvent`), which is why [current] is passed in: a
 * `voice_status:summarizing` only applies while the phase is OFF/ERROR/CONNECTING/SUMMARIZING
 * (`40ce856`, RS-15). Nested arrays (`modelTurn.parts`, `toolCall.functionCalls`) are read from the
 * JSON tree (fixes B1).
 */
interface ProviderEventParser {
    fun parse(event: JsonObject, current: ProviderPhase): List<ProviderSignal>
}

/** One provider connection. New instance per session. */
interface VoiceTransport {
    val providerId: String
    val kind: ProviderKind
    val phase: StateFlow<ProviderPhaseState>

    /** Non-phase signals; hot, buffered, never drops. */
    val signals: SharedFlow<ProviderSignal>

    /** Getter for the cached `session.update`, used for the DC-open / echo self-heal (RS-03). */
    fun setSessionUpdateFallback(fallback: () -> JsonObject?)
    fun setMicGain(level: Float)
    fun setEchoDuckingGain(gain: Float)
    fun toggleMute(): Boolean
    fun setSpeakerMode(mode: SpeakerMode, preferredDevice: AudioDeviceRef?)

    suspend fun connect(
        info: VoiceConnection,
        mirrorEvent: (JsonObject) -> Unit,
        sendMicChunk: (String) -> Unit,
    )

    /** Backend → provider command (WebRTC: over the data channel or queued until open; WS: no-op). */
    fun handleBackendCommand(command: JsonObject)

    /** Backend-mirrored provider event (WS providers). */
    fun handleProviderEvent(event: JsonObject)

    /** `voice_audio_out` chunk (WS providers; WebRTC ignores). */
    fun pushSpeakerChunk(audioB64: String)

    suspend fun disconnect()
}

/**
 * "openai" → WebRTC/OPENAI, "qwen" → WS/QWEN, "google" → WS/GEMINI; unknown → by connection type
 * (WEBRTC → OPENAI, WEBSOCKET → QWEN parser keeping the unknown provider id).
 */
interface VoiceTransportFactory {
    fun create(providerId: String, connectionType: ConnectionType): VoiceTransport
}

/** Deps of the WebSocket PCM transport (Qwen/Gemini): MicSource → `voice_audio_in`, `voice_audio_out` → PcmPlayer. */
class WsPcmDeps(
    val scope: CoroutineScope,
    val clock: MonotonicClock,
    val log: VoiceLog,
    val sdkInt: Int,
    val mics: MicSourceFactory,
    val tracks: AudioTrackFactory,
    val audio: AudioCore,
    val hasRecordPermission: () -> Boolean,
)
