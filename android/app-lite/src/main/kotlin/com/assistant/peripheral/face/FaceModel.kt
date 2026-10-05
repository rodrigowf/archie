package com.assistant.peripheral.face

import com.assistant.core.network.SocketState
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.session.VoiceLinkState
import com.assistant.core.voicehost.HostConnection
import com.assistant.core.voicehost.LastExchange
import com.assistant.core.voicehost.VoiceUiState
import com.assistant.core.voicehost.WakeHealth

/** The big shape in the middle of the face (mockup §4). */
enum class FaceShape {
    /** Quiet ring around the Archie mark (wake armed / idle). */
    MARK_RING,

    /** Pulsing orb with level bars (voice and talk activity). */
    ORB,

    /** Amber, dimmed, slowly pulsing orb (P-2 reconnecting). */
    RECONNECT_ORB,

    /** Dashed ring + "devices" glyph (another device owns voice). */
    DASHED_RING,

    /** Red ring + a glyph (offline, error, mic busy). */
    ERROR_RING,
}

/** Color role of the orb / ring (token roles; resolved by [LitePalette][com.assistant.peripheral.ui.LitePalette]). */
enum class FaceTone { PRIMARY, SECONDARY, TERTIARY, CONTAINER, OUTLINE, WARNING, ERROR }

enum class FaceGlyph { NONE, MARK, DEVICES, CLOUD_OFF, ALERT, MIC_OFF, PAUSE }

enum class DotTone { SUCCESS, WARNING, ERROR, OFF }

/** What a tap on the shape does (the single big start/stop target, inv04 §9.2 item 5). */
enum class FaceAction { START, STOP, RECONNECT_VOICE, CONNECT, NONE }

data class Caption(val label: String, val text: String, val dim: Boolean)

/**
 * Everything one frame of the face shows. Built by [FaceModel.from] (pure, JVM-tested); rendered by
 * [FaceScreen]. No markdown reaches here ([PlainText]).
 */
data class FaceModel(
    val shape: FaceShape,
    val tone: FaceTone,
    val glyph: FaceGlyph,
    val word: String,
    val sub: String?,
    val dot: DotTone,
    val connLabel: String,
    val captions: List<Caption>,
    val action: FaceAction,
    /** Orb pulse / level animation runs (never in the armed idle state: spec 14 §5.2). */
    val animate: Boolean,
    /** 0..1 level of the orb bars (VAD speech → high). */
    val level: Float,
    val showMute: Boolean,
    val muted: Boolean,
    /** Keep the screen on (live voice). */
    val keepScreenOn: Boolean,
    val reconnectBanner: String? = null,
) {
    companion object {
        /** Spec 14 §5.2 / inv04 §4.10: the "Ns" listening timer appears after 3,000 ms of speech. */
        const val LISTENING_TIMER_REVEAL_MS = 3_000L

        /**
         * @param socket raw socket state of the orchestrator channel (the host folds "retry
         *   scheduled" into CONNECTING; the face shows it as Offline + next retry, mockup §4).
         * @param retryInMs estimated time to the next reconnect attempt, if one is scheduled.
         * @param serverName the saved-server label of the current URL, else its host.
         * @param exchange the lite last exchange ([LastExchangeSink][com.assistant.peripheral.LastExchangeSink]).
         * @param recentError a voice failure of the last few seconds (the core finalizes to OFF).
         */
        fun from(
            s: VoiceUiState,
            socket: SocketState,
            serverName: String,
            exchange: LastExchange,
            wakePhrase: String,
            talkPhrase: String,
            retryInMs: Long? = null,
            recentError: String? = null,
            autoConnect: Boolean = true,
        ): FaceModel {
            val phase = s.session.phase
            val owner = s.session.isOwner
            val live = owner && phase != SessionPhase.OFF && phase != SessionPhase.ERROR
            val socketDown = socket is SocketState.Disconnected || socket == SocketState.Idle
            val dot = when {
                s.link is VoiceLinkState.Reconnecting -> DotTone.WARNING
                s.connection == HostConnection.CONNECTED && !socketDown -> DotTone.SUCCESS
                socket is SocketState.Connecting -> DotTone.WARNING
                else -> DotTone.ERROR
            }
            val connLabel = if (dot == DotTone.ERROR) "offline" else serverName
            val base = FaceModel(
                shape = FaceShape.MARK_RING, tone = FaceTone.OUTLINE, glyph = FaceGlyph.MARK,
                word = "Ready", sub = null, dot = dot, connLabel = connLabel,
                captions = captions(exchange, live = false, offline = false),
                action = FaceAction.START, animate = false, level = 0f,
                showMute = live, muted = s.session.isMuted, keepScreenOn = live,
                reconnectBanner = s.session.reconnectBanner,
            )
            val liveCaptions = captions(exchange, live = true, offline = false)
            val orb = { tone: FaceTone, word: String, sub: String?, action: FaceAction ->
                base.copy(shape = FaceShape.ORB, tone = tone, glyph = FaceGlyph.NONE, word = word, sub = sub,
                    captions = liveCaptions, action = action, animate = true, level = levelOf(s))
            }

            // 1. P-2 link states take precedence over everything during live voice.
            when (val link = s.link) {
                is VoiceLinkState.Reconnecting -> return base.copy(
                    shape = FaceShape.RECONNECT_ORB, tone = FaceTone.WARNING, glyph = FaceGlyph.NONE,
                    word = "Reconnecting", sub = "${clock(link.elapsedMs)} · a tone plays when it's back",
                    captions = liveCaptions, action = FaceAction.STOP, animate = true, level = 0.28f,
                    showMute = false, keepScreenOn = true,
                )
                is VoiceLinkState.Failed -> return base.copy(
                    shape = FaceShape.ERROR_RING, tone = FaceTone.ERROR, glyph = FaceGlyph.CLOUD_OFF,
                    word = if (socketDown) "Offline" else "Dropped",
                    sub = "Voice lost after ${clock(link.downtimeMs)} · tap to reconnect",
                    captions = captions(exchange, live = false, offline = true),
                    action = FaceAction.RECONNECT_VOICE, showMute = false, keepScreenOn = false,
                )
                VoiceLinkState.Up -> Unit
            }

            // 2. This device's voice session.
            if (owner || phase == SessionPhase.CONNECTING || phase == SessionPhase.SUMMARIZING) {
                when (phase) {
                    SessionPhase.CONNECTING -> return orb(FaceTone.CONTAINER, "Connecting", null, FaceAction.STOP).copy(level = 0.4f)
                    SessionPhase.SUMMARIZING -> return orb(FaceTone.CONTAINER, "Preparing", null, FaceAction.STOP).copy(level = 0.4f)
                    SessionPhase.ACTIVE -> {
                        val speech = s.session.vadState == "speech" && s.session.vadDurationMs >= LISTENING_TIMER_REVEAL_MS
                        val sub = when {
                            s.session.isMuted -> "Muted"
                            speech -> "${s.session.vadDurationMs / 1000} s"
                            else -> null
                        }
                        return orb(FaceTone.PRIMARY, "Listening", sub, FaceAction.STOP)
                    }
                    SessionPhase.SPEAKING -> return orb(FaceTone.TERTIARY, "Speaking", null, FaceAction.STOP)
                    SessionPhase.THINKING -> return orb(FaceTone.SECONDARY, "Thinking", null, FaceAction.STOP).copy(level = 0.4f)
                    SessionPhase.TOOL_USE -> return orb(FaceTone.SECONDARY, "Working", null, FaceAction.STOP).copy(level = 0.4f)
                    SessionPhase.ENDING -> return orb(FaceTone.OUTLINE, "Ending", null, FaceAction.NONE).copy(animate = false, level = 0.3f)
                    SessionPhase.ERROR -> return base.copy(
                        shape = FaceShape.ERROR_RING, tone = FaceTone.ERROR, glyph = FaceGlyph.ALERT, word = "Error",
                        sub = (s.session.errorMessage ?: "Voice failed").take(120) + " · tap to retry",
                        action = FaceAction.START,
                    )
                    SessionPhase.OFF -> Unit
                }
            }
            if (phase == SessionPhase.ERROR || (phase == SessionPhase.OFF && recentError != null)) {
                return base.copy(
                    shape = FaceShape.ERROR_RING, tone = FaceTone.ERROR, glyph = FaceGlyph.ALERT, word = "Error",
                    sub = (s.session.errorMessage ?: recentError ?: "Voice failed").take(120) + " · tap to retry",
                    action = FaceAction.START,
                )
            }

            // 3. Another device owns voice: read-only (RS-12).
            if (s.session.remoteVoiceActive) {
                return base.copy(
                    shape = FaceShape.DASHED_RING, tone = FaceTone.OUTLINE, glyph = FaceGlyph.DEVICES,
                    word = "Elsewhere", sub = "Voice is on another device", action = FaceAction.NONE,
                )
            }

            // 4. Talk path (turn-based voice message).
            if (s.talk.isRecording || s.pushToTalk) {
                return orb(FaceTone.TERTIARY, "Recording", "Pause to send", FaceAction.NONE).copy(captions = base.captions)
            }
            if (s.talk.wakeConfirming) {
                return orb(FaceTone.SECONDARY, "Confirming", "Checking what you said", FaceAction.NONE)
                    .copy(captions = base.captions, level = 0.4f)
            }

            // 5. No connection. A socket that never started (cold start) is "connecting" when the
            //    app will auto-connect, so the face does not flash Offline at every launch.
            if (socket == SocketState.Idle && autoConnect) {
                return base.copy(word = "Connecting", sub = "Reaching $serverName…", action = FaceAction.NONE,
                    dot = DotTone.WARNING, connLabel = serverName)
            }
            if (socketDown || s.connection == HostConnection.OFFLINE) {
                val willRetry = (socket as? SocketState.Disconnected)?.willReconnect == true
                val sub = when {
                    !willRetry -> "Not connected · tap to connect"
                    retryInMs != null && retryInMs >= 1_000 -> "Retrying $serverName in ${(retryInMs + 999) / 1000} s"
                    else -> "Retrying $serverName…"
                }
                return base.copy(
                    shape = FaceShape.ERROR_RING, tone = FaceTone.ERROR, glyph = FaceGlyph.CLOUD_OFF,
                    word = "Offline", sub = sub, captions = captions(exchange, live = false, offline = true),
                    action = if (willRetry) FaceAction.NONE else FaceAction.CONNECT,
                )
            }
            if (socket is SocketState.Connecting || s.connection == HostConnection.CONNECTING) {
                return base.copy(word = "Connecting", sub = "Reaching $serverName…", action = FaceAction.NONE)
            }

            // 6. Idle: wake-word health.
            return when (s.wake) {
                WakeHealth.MIC_STALLED -> base.copy(
                    shape = FaceShape.ERROR_RING, tone = FaceTone.ERROR, glyph = FaceGlyph.MIC_OFF,
                    word = "Mic busy", sub = "Another app is using the microphone",
                )
                WakeHealth.PAUSED_BY_USER, WakeHealth.PAUSED_NEEDS_FOREGROUND, WakeHealth.BACKGROUND_RESTRICTED -> base.copy(
                    glyph = FaceGlyph.PAUSE, word = "Paused", sub = "Listening paused · tap to talk",
                )
                WakeHealth.DISABLED -> base.copy(sub = if (s.noOrchestrator) NO_ORCHESTRATOR else "Wake word off · tap to talk")
                WakeHealth.ARMED, WakeHealth.PAUSED_FOR_VOICE -> base.copy(
                    sub = if (s.noOrchestrator) NO_ORCHESTRATOR else hint(wakePhrase, talkPhrase),
                )
            }
        }

        const val NO_ORCHESTRATOR = "No conversation open on the server"

        /** `Say "wake up" or "my friend"` from the first phrase of each comma list. */
        fun hint(wakePhrase: String, talkPhrase: String): String {
            val w = wakePhrase.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() }
            val t = talkPhrase.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() }
            return when {
                w != null && t != null -> "Say \"$w\" or \"$t\""
                w != null -> "Say \"$w\""
                t != null -> "Say \"$t\""
                else -> "Tap to talk"
            }
        }

        fun clock(ms: Long): String {
            val s = (ms.coerceAtLeast(0) / 1000)
            return "%d:%02d".format(s / 60, s % 60)
        }

        private fun levelOf(s: VoiceUiState): Float = when {
            s.session.isMuted -> 0.2f
            s.session.phase == SessionPhase.SPEAKING -> 1f
            s.session.vadState == "speech" -> 1f
            else -> 0.55f
        }

        private fun captions(e: LastExchange, live: Boolean, offline: Boolean): List<Caption> {
            if (offline) {
                val last = e.assistant ?: e.user ?: return emptyList()
                return listOf(Caption("Last", last, dim = true))
            }
            return buildList {
                e.user?.let { add(Caption("You", it, dim = !live || e.assistant != null)) }
                e.assistant?.let { add(Caption("Archie", it, dim = !live)) }
            }
        }
    }
}
