package com.assistant.archie.system

import com.assistant.archie.feature.chat.ChatVoice
import com.assistant.archie.feature.settings.VoiceStatusSource
import com.assistant.core.data.VoicePresence
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceLevels
import com.assistant.core.voice.ports.VoiceSessionState
import com.assistant.core.voice.session.VoiceLinkState
import com.assistant.core.voicehost.VoiceHost
import com.assistant.core.voicehost.VoiceUiState
import com.assistant.core.voicehost.WakeHealth
import com.assistant.core.voicehost.ports.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/*
 * Adapters from the process-scoped voice host (A-08) to the ports the features program against
 * (spec 14 §2.1: features never see the runtime). They replace B-03's `VoicePresence.Idle`, B-04's
 * state-only `PresenceChatVoice` and B-08's `VoiceStatusSource.None`.
 */

private fun <T> StateFlow<VoiceUiState>.slice(scope: CoroutineScope, f: (VoiceUiState) -> T): StateFlow<T> =
    map(f).stateIn(scope, SharingStarted.Eagerly, f(value))

/** Shell chrome (top-bar voice subtitle, speaker state). */
class HostVoicePresence(host: VoiceHost, scope: CoroutineScope) : VoicePresence {
    override val state: StateFlow<VoiceSessionState> = host.state.slice(scope) { it.session }
}

/**
 * The Archie conversation's voice dock and composer (IA §6). Every start goes through the mic
 * permission gate first (spec 14 §2.9: asked on the first tap, with a rationale), then the single
 * trigger ingress. "Active elsewhere" is passive: the dock renders `remoteVoiceActive && !isOwner`
 * from [state] and sends nothing (spec 12 V-12) until the user taps Take over (a plain owner start).
 */
class HostChatVoice(
    private val host: VoiceHost,
    private val mic: MicPermissionGate,
    scope: CoroutineScope,
) : ChatVoice {
    override val state: StateFlow<VoiceSessionState> = host.state.slice(scope) { it.session }

    /** Measured only while the dock observes it (the transports meter nothing unobserved). */
    override val level: StateFlow<Float?> = combine(host.levels, host.state) { lv, s -> dockLevel(lv, s) }
        .stateIn(scope, SharingStarted.WhileSubscribed(0, 0), null)
    override val speakerMuted: StateFlow<Boolean> = host.state.slice(scope) { it.speakerMuted }

    /** The server does not name the owning device yet; the dock falls back to "another device". */
    override val remoteDevice: StateFlow<String?> = MutableStateFlow(null)
    override val remoteTranscriptMirrored: StateFlow<Boolean> = MutableStateFlow(true)
    override val recording: StateFlow<Boolean> = host.state.slice(scope) { it.pushToTalk || it.talk.isRecording }

    override fun start() = mic.withMicrophone { host.startVoice(Trigger.BUTTON) }
    override fun stop() = host.stopVoice()
    override fun toggleMute() = host.toggleMute()
    override fun toggleSpeaker() = host.toggleSpeakerMute()
    override fun takeOver() = mic.withMicrophone { host.startVoice(Trigger.BUTTON) }

    override fun toggleRecording() {
        if (host.state.value.pushToTalk) host.stopPushToTalk(send = true) else mic.withMicrophone { host.startPushToTalk() }
    }

    companion object {
        /**
         * The orb's visual level by turn: the speaker while Archie speaks, the mic while it is the
         * user's turn (0 when that side is muted); null — the orb's own pulse — without a local
         * session, while thinking / using tools, or while the link is down.
         */
        fun dockLevel(levels: VoiceLevels?, s: VoiceUiState): Float? {
            if (levels == null || s.link !is VoiceLinkState.Up) return null
            return when (s.session.phase) {
                SessionPhase.SPEAKING -> if (s.speakerMuted) 0f else VoiceLevels.visual(levels.speaker)
                SessionPhase.ACTIVE -> if (s.session.isMuted) 0f else VoiceLevels.visual(levels.mic)
                else -> null
            }
        }
    }
}

/** Settings → Wake word & triggers: the live health line (spec 14 §2.6, §2.7 texts). */
class HostVoiceStatus(host: VoiceHost, scope: CoroutineScope, wakePhrase: () -> String?) : VoiceStatusSource {
    override val wakeStatus: StateFlow<String?> = host.state.slice(scope) { wakeText(it.wake, wakePhrase()) }

    companion object {
        fun wakeText(h: WakeHealth, phrase: String?): String? = when (h) {
            WakeHealth.DISABLED -> null
            WakeHealth.ARMED -> if (phrase.isNullOrBlank()) "Listening" else "Listening for “$phrase”"
            WakeHealth.PAUSED_FOR_VOICE -> "Paused while a voice conversation runs"
            WakeHealth.PAUSED_BY_USER -> "Paused — resume from the notification"
            WakeHealth.MIC_STALLED -> "Stalled — another app holds the microphone"
            WakeHealth.PAUSED_NEEDS_FOREGROUND -> "Paused after Android restarted Archie — open Archie or tap Resume listening"
            WakeHealth.BACKGROUND_RESTRICTED -> "Android blocked background listening — see Background reliability"
        }
    }
}
