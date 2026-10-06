package com.assistant.core.voicehost.trigger

import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voicehost.ports.TalkUiState
import com.assistant.core.voicehost.ports.Trigger
import com.assistant.core.voicehost.ports.TriggerDeps
import com.assistant.core.voicehost.ports.TriggerRouter
import com.assistant.core.wakeword.ports.WakeLoopEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Wake signals and external triggers → cue + voice + talk UI (old `MainActivity` wake/talk
 * callbacks, `MainActivity.kt:322-389`, now headless: spec 14 §2.5 "wake → voice without an
 * Activity"). Every call is synchronous: the cue and the CONNECTING flip happen inside the call,
 * before any network work (RS-45).
 */
class DefaultTriggerRouter(private val deps: TriggerDeps) : TriggerRouter {
    private val _talkUi = MutableStateFlow(TalkUiState())
    override val talkUi: StateFlow<TalkUiState> = _talkUi.asStateFlow()

    override fun onWakeEvent(event: WakeLoopEvent) {
        when (event) {
            is WakeLoopEvent.Confirming -> _talkUi.update { it.copy(wakeConfirming = true) }
            // Clears BOTH the confirming indicator and a record button a talk onset already showed (`b710c8b`).
            is WakeLoopEvent.ConfirmFailed -> _talkUi.value = TalkUiState()
            WakeLoopEvent.WakeDetected -> {
                _talkUi.value = TalkUiState()
                startRealtime("wake word")
            }
            WakeLoopEvent.TalkDetected -> {
                deps.cues.talkAck()
                _talkUi.value = TalkUiState(wakeConfirming = false, isRecording = true)
            }
            is WakeLoopEvent.TalkMessageCaptured -> {
                // Both indicators off (`3bee23a`): the talk path re-raised "confirming" just before the confirm.
                _talkUi.value = TalkUiState()
                deps.log.i(TAG, "Talk message captured (${event.wav.size} B) — sending")
                deps.talkSender.send(event.wav)
            }
            // Health signals belong to the wake service (notification / rebuild), not to the UI path.
            WakeLoopEvent.MicUnavailable, WakeLoopEvent.MicAvailable, WakeLoopEvent.RecognizerUnhealthy -> Unit
        }
    }

    override fun onTrigger(trigger: Trigger) {
        when (trigger) {
            // B5: the recents trigger honours `button_trigger_enabled` on every path.
            Trigger.RECENTS -> if (deps.buttonTriggerEnabled()) startRealtime("recents long-press") else deps.log.d(TAG, "Recents long-press ignored — button trigger disabled")
            // The in-app voice button: no ack beep (the user is looking at the button).
            Trigger.BUTTON -> if (voiceIdle()) deps.voice.startVoice() else deps.log.d(TAG, "Voice button ignored — voice already ${deps.voice.state.value.phase}")
            // Assist gesture, tile, notification "Talk": the same UX path as the realtime wake word.
            Trigger.WAKE_WORD, Trigger.ASSIST, Trigger.TILE, Trigger.NOTIFICATION -> startRealtime(trigger.name.lowercase())
        }
    }

    private fun startRealtime(source: String) {
        if (!voiceIdle()) {
            deps.log.d(TAG, "Trigger ($source) ignored — voice already ${deps.voice.state.value.phase}")
            return
        }
        deps.log.i(TAG, "Trigger ($source) → starting realtime voice session")
        deps.cues.wakeAck()
        deps.voice.markConnecting()
        deps.voice.startVoice()
    }

    private fun voiceIdle(): Boolean = when (deps.voice.state.value.phase) {
        SessionPhase.OFF, SessionPhase.ERROR -> true
        else -> false
    }

    private companion object {
        const val TAG = "TriggerRouter"
    }
}

/** Screen-on + bring-to-front on a wake/talk trigger (lite only; the main app runs headless, spec 14 §2.6). */
fun interface ForegroundBringer {
    fun bringToForeground()

    companion object {
        val NONE = ForegroundBringer { }
    }
}

/**
 * The single trigger entry point (spec 14 §2.5, §5.5). Every source — the wake loop, the
 * accessibility recents key, the `/dev/input` monitor, the assist gesture / VIS session, the QS
 * tile, notification actions, the in-app button — calls this one object, which lives in the
 * process-scoped runtime. Nothing is broadcast to an Activity, so a trigger can no longer be lost
 * because the Activity is not alive (old R2: `LocalBroadcast` → `MainActivity` receiver only).
 */
class TriggerIngress(
    private val router: TriggerRouter,
    private val bringer: ForegroundBringer,
    private val buttonTriggerEnabled: () -> Boolean,
) {
    /** Wake loop events (Vosk/SR → Whisper). Wake and talk detections also wake the screen (lite). */
    fun onWakeEvent(event: WakeLoopEvent) {
        router.onWakeEvent(event)
        if (event == WakeLoopEvent.WakeDetected || event == WakeLoopEvent.TalkDetected) bringer.bringToForeground()
    }

    /** External triggers. */
    fun trigger(trigger: Trigger) {
        if (trigger == Trigger.RECENTS && !buttonTriggerEnabled()) {
            router.onTrigger(trigger) // logs and ignores (B5); no screen wake
            return
        }
        router.onTrigger(trigger)
        if (trigger != Trigger.BUTTON) bringer.bringToForeground()
    }

    val talkUi get() = router.talkUi
}

/**
 * Pure recents long-press detector shared by the accessibility service and the `/dev/input`
 * monitor (old `ButtonAccessibilityService.onKeyEvent`, `AssistantService.kt:647-664`): a press
 * held ≥ [longPressMs] fires on release. A release without a press never fires.
 */
class LongPressDetector(private val longPressMs: Long = com.assistant.core.voicehost.HostTuning.RECENTS_LONG_PRESS_MS) {
    private var downAtMs = 0L

    fun onDown(nowMs: Long) {
        if (downAtMs == 0L) downAtMs = nowMs
    }

    /** Returns true when the release completes a long press. */
    fun onUp(nowMs: Long): Boolean {
        val down = downAtMs
        downAtMs = 0L
        return down > 0L && nowMs - down >= longPressMs
    }
}
