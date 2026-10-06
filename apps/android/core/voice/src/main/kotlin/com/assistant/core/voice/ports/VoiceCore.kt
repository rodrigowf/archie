package com.assistant.core.voice.ports

import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.audio.ports.VoiceLog
import kotlinx.serialization.json.JsonObject

/*
 * Parity entry point for :core:voice (A-04). Interface-only.
 *
 * A-06 registers one implementation with java.util.ServiceLoader:
 *   core/voice/src/main/resources/META-INF/services/com.assistant.core.voice.ports.VoiceCore
 * Tuning values live in `com.assistant.core.voice.VoiceTuning` (names pinned by SessionTuningTest
 * and WebRtcTuningTest).
 */

/**
 * OpenAI WebRTC ducking (inv04 §3.3 WebRTC table), tick-driven like `EchoDucker`:
 *  - `response.created` / `output_audio_buffer.started`: cancel restore, DUCK (`started` also sets agentPlaying);
 *  - `output_audio_buffer.stopped`: agentPlaying = false, restore after 2000 ms;
 *  - `output_audio_buffer.cleared`: agentPlaying = false, DUCK, restore after 2000 ms;
 *  - `input_audio_buffer.speech_started`: restore now when !agentPlaying, else suppressed (echo);
 *  - `response.done`: does NOT restore (`687442e`).
 */
interface OpenAiDuckPolicy {
    val currentMicGain: Float
    val isDucked: Boolean
    val agentPlaying: Boolean
    fun setMicGain(level: Float)
    fun setEchoDuckingGain(gain: Float)

    /** Feed every data-channel event type. Returns true when a `speech_started` was suppressed as echo. */
    fun onDcEvent(type: String): Boolean
    val nextTickDelayMs: Long?
    fun tick()

    /** Restore the saved gain, clear agentPlaying. */
    fun cleanup()
}

interface VoiceCore {
    fun parser(kind: ParserKind): ProviderEventParser
    fun parserKindFor(providerId: String, connectionType: ConnectionType): ParserKind

    /** Go duration ("50s", "30m0s", "1h30m0s", "500ms") → whole seconds; null when unparseable. */
    fun parseGoDurationSeconds(text: String): Int?

    fun openAiDuckPolicy(clock: MonotonicClock, log: VoiceLog): OpenAiDuckPolicy
    fun commandRelay(log: VoiceLog): CommandRelay
    fun dataChannelGate(
        transmit: (JsonObject) -> Unit,
        fallback: () -> JsonObject?,
        log: VoiceLog,
    ): DataChannelCommandGate

    fun sessionController(deps: VoiceSessionDeps): VoiceSessionController
    fun webRtcTransport(deps: WebRtcDeps): VoiceTransport
    fun wsPcmTransport(deps: WsPcmDeps, parser: ParserKind, providerId: String): VoiceTransport
}
