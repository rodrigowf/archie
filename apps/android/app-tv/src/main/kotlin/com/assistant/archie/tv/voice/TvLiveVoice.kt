package com.assistant.archie.tv.voice

import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voicehost.VoiceHost
import com.assistant.core.voicehost.VoiceUiEvent
import com.assistant.core.voicehost.ports.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The live panel's last lines (spec 15 §3.4: orb + running transcript). */
data class LiveTranscript(val user: String? = null, val assistant: String? = null)

/**
 * Live voice through the remote (spec 15 §4.2) over the shared voice host: Play/Pause / hero /
 * Menu toggle it, the TV is a normal voice owner (spec 12 §7). While another device owns voice a
 * toggle never takes it over silently: it offers "Take over?" (toast), and only [takeOver] — the
 * "Take over" buttons, or Play/Pause again within [TAKE_OVER_CONFIRM_MS] of the offer — starts
 * here. The remote's bursts are walkie-talkie turns fed through [bridge].
 *
 * Ends on the toggle, the panel's End / Back, or after [idleSeconds] with neither side talking.
 * While live it pings the launcher (KEEP_AWAKE) every [TvVoiceTuning.LIVE_KEEP_AWAKE_MS].
 */
class TvLiveVoice(
    private val host: VoiceHost?,
    private val bridge: LiveMicBridge,
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val hasPermission: () -> Boolean,
    private val requestPermission: () -> Unit,
    private val toast: (String, Boolean) -> Unit,
    private val keepAwake: () -> Unit,
    private val idleSeconds: () -> Int = { TvVoiceTuning.DEFAULT_LIVE_IDLE_SECONDS },
    private val log: (String) -> Unit = {},
) : LiveVoiceToggle, LiveMicSink {
    private val _transcript = MutableStateFlow(LiveTranscript())
    val transcript: StateFlow<LiveTranscript> = _transcript.asStateFlow()

    @Volatile private var lastActivity = 0L
    private var watch: Job? = null

    /** When the last "Take over?" offer was made (0 = none pending). */
    @Volatile private var offeredAt = 0L

    init {
        host?.let { h ->
            scope.launch {
                h.state.map { it.session.phase to it.session.isOwner }.distinctUntilChanged().collect { (phase, owner) -> onPhase(phase, owner) }
            }
            scope.launch {
                h.events.collect { e ->
                    if (e is VoiceUiEvent.Transcript && e.final && e.text.isNotBlank()) {
                        lastActivity = clock.nowMs()
                        _transcript.value = when (e.role) {
                            VoiceUiEvent.Role.USER -> LiveTranscript(user = e.text, assistant = null)
                            VoiceUiEvent.Role.ASSISTANT -> _transcript.value.copy(assistant = e.text)
                            VoiceUiEvent.Role.SYSTEM -> _transcript.value
                        }
                    }
                }
            }
        }
    }

    /** This device owns a live session that is connecting or running (the router's "Live"). */
    fun liveOwned(): Boolean {
        val s = host?.state?.value?.session ?: return false
        return s.isOwner && s.phase.isLive()
    }

    override fun toggle(): Boolean {
        val h = host ?: run {
            toast("Live voice is not available on this device", true)
            return true
        }
        val s = h.state.value.session
        when {
            s.isOwner && s.phase.isLive() -> {
                log("live voice: user stop")
                h.stopVoice()
            }
            s.remoteVoiceActive && !s.isOwner -> {
                val now = clock.nowMs()
                if (offeredAt > 0 && now - offeredAt <= TAKE_OVER_CONFIRM_MS) {
                    start(h, takingOver = true) // pressed again after the offer: the explicit take-over
                } else {
                    offeredAt = now
                    log("live voice: owned by another device — offering take over")
                    toast(TAKE_OVER_OFFER, false)
                }
            }
            else -> start(h, takingOver = false)
        }
        return true
    }

    /** The explicit "Take over" (spec 12 §7): starts live voice here even while another device owns it. */
    override fun takeOver(): Boolean {
        val h = host ?: return toggle()
        val s = h.state.value.session
        if (s.isOwner && s.phase.isLive()) return true
        start(h, takingOver = s.remoteVoiceActive && !s.isOwner)
        return true
    }

    private fun start(h: VoiceHost, takingOver: Boolean) {
        offeredAt = 0
        if (!hasPermission()) {
            requestPermission()
            return
        }
        if (takingOver) log("live voice: taking over from another device")
        bridge.reset()
        _transcript.value = LiveTranscript()
        h.startVoice(Trigger.BUTTON)
    }

    /** The panel's End button / Back from the overlay. */
    fun end() {
        val s = host?.state?.value?.session ?: return
        if (s.isOwner && s.phase.isLive()) host.stopVoice()
    }

    // ── LiveMicSink (RemoteMicSource → the call) ──

    override fun onLiveBurstStart() { lastActivity = clock.nowMs() }
    override fun onLiveFrame(pcm: ShortArray) = bridge.push(pcm)
    override fun onLiveBurstEnd() {
        lastActivity = clock.nowMs()
        bridge.endOfBurst()
    }

    private fun onPhase(phase: SessionPhase, owner: Boolean) {
        val live = owner && phase.isLive()
        if (phase == SessionPhase.SPEAKING || phase == SessionPhase.THINKING || phase == SessionPhase.TOOL_USE) lastActivity = clock.nowMs()
        if (live && watch == null) {
            lastActivity = clock.nowMs()
            keepAwake()
            watch = scope.launch { watchLoop() }
        } else if (!live && watch != null) {
            watch?.cancel()
            watch = null
            bridge.reset()
        }
    }

    private suspend fun watchLoop() {
        var lastPing = clock.nowMs()
        while (true) {
            delay(WATCH_TICK_MS)
            val now = clock.nowMs()
            if (now - lastPing >= TvVoiceTuning.LIVE_KEEP_AWAKE_MS) {
                keepAwake()
                lastPing = now
            }
            val phase = host?.state?.value?.session?.phase ?: return
            val limit = idleSeconds()
            if (limit > 0 && phase == SessionPhase.ACTIVE && now - lastActivity >= limit * 1000L) {
                log("live voice: ${limit}s without talking — ending")
                toast("Live voice ended after ${formatIdle(limit)} of silence", false)
                host.stopVoice()
                return
            }
        }
    }

    companion object {
        const val WATCH_TICK_MS = 1_000L

        /** Play/Pause again within this long after the "Take over?" toast takes over. */
        const val TAKE_OVER_CONFIRM_MS = 6_000L

        const val TAKE_OVER_OFFER = "Live voice is on another device — Take over? Press ⏯ again"

        fun SessionPhase.isLive(): Boolean = this != SessionPhase.OFF && this != SessionPhase.ERROR

        fun formatIdle(seconds: Int): String = if (seconds % 60 == 0) "${seconds / 60} min" else "$seconds s"
    }
}
