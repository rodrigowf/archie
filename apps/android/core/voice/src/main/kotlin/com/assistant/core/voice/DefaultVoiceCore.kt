package com.assistant.core.voice

import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.voice.delivery.LockedCommandRelay
import com.assistant.core.voice.delivery.LockedDataChannelGate
import com.assistant.core.voice.duck.OpenAiDucker
import com.assistant.core.voice.parse.GoDuration
import com.assistant.core.voice.parse.ProviderParsers
import com.assistant.core.voice.ports.CommandRelay
import com.assistant.core.voice.ports.ConnectionType
import com.assistant.core.voice.ports.DataChannelCommandGate
import com.assistant.core.voice.ports.OpenAiDuckPolicy
import com.assistant.core.voice.ports.ParserKind
import com.assistant.core.voice.ports.ProviderEventParser
import com.assistant.core.voice.ports.VoiceCore
import com.assistant.core.voice.ports.VoiceSessionDeps
import com.assistant.core.voice.ports.VoiceTransport
import com.assistant.core.voice.ports.VoiceTransportFactory
import com.assistant.core.voice.ports.WebRtcDeps
import com.assistant.core.voice.ports.WsPcmDeps
import com.assistant.core.voice.session.DefaultVoiceSessionController
import com.assistant.core.voice.session.ReconnectableVoiceSession
import com.assistant.core.voice.transport.WebRtcTransport
import com.assistant.core.voice.transport.WsPcmTransport
import kotlinx.serialization.json.JsonObject

/**
 * The `:core:voice` entry point (registered in `META-INF/services` for the parity harness; the
 * app graph may construct the pieces directly). Pure wiring: no Android type is touched here.
 */
class DefaultVoiceCore : VoiceCore {
    override fun parser(kind: ParserKind): ProviderEventParser = ProviderParsers.create(kind)

    override fun parserKindFor(providerId: String, connectionType: ConnectionType): ParserKind =
        ProviderParsers.kindFor(providerId, connectionType)

    override fun parseGoDurationSeconds(text: String): Int? = GoDuration.parseSeconds(text)

    override fun openAiDuckPolicy(clock: MonotonicClock, log: VoiceLog): OpenAiDuckPolicy = OpenAiDucker(clock, log)

    override fun commandRelay(log: VoiceLog): CommandRelay = LockedCommandRelay(log)

    override fun dataChannelGate(
        transmit: (JsonObject) -> Unit,
        fallback: () -> JsonObject?,
        log: VoiceLog,
    ): DataChannelCommandGate = LockedDataChannelGate(transmit, fallback, log)

    /** The returned controller also implements [ReconnectableVoiceSession] (P-2 link surface). */
    override fun sessionController(deps: VoiceSessionDeps): ReconnectableVoiceSession = DefaultVoiceSessionController(deps)

    override fun webRtcTransport(deps: WebRtcDeps): VoiceTransport = WebRtcTransport(deps)

    override fun wsPcmTransport(deps: WsPcmDeps, parser: ParserKind, providerId: String): VoiceTransport =
        WsPcmTransport(deps, ProviderParsers.create(parser), providerId)
}

/**
 * Production [VoiceTransportFactory]: a new transport per session, chosen by
 * [ProviderParsers.kindFor] ("openai" → WebRTC; "qwen" / "google" / unknown-over-WS → WS PCM with
 * the matching parser, keeping the unknown provider id).
 */
class DefaultVoiceTransportFactory(
    private val webRtcDeps: () -> WebRtcDeps,
    private val wsPcmDeps: () -> WsPcmDeps,
) : VoiceTransportFactory {
    override fun create(providerId: String, connectionType: ConnectionType): VoiceTransport =
        when (val kind = ProviderParsers.kindFor(providerId, connectionType)) {
            ParserKind.OPENAI -> WebRtcTransport(webRtcDeps())
            ParserKind.QWEN, ParserKind.GEMINI -> WsPcmTransport(wsPcmDeps(), ProviderParsers.create(kind), providerId)
        }
}
