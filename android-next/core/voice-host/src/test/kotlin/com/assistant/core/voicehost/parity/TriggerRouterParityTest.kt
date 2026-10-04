package com.assistant.core.voicehost.parity

import com.assistant.core.testing.FakeVoiceSessionController
import com.assistant.core.testing.OrderLog
import com.assistant.core.testing.RecordingCues
import com.assistant.core.testing.RecordingLog
import com.assistant.core.voicehost.ports.TalkUiState
import com.assistant.core.voicehost.ports.Trigger
import com.assistant.core.voicehost.ports.TriggerDeps
import com.assistant.core.voicehost.ports.TriggerRouter
import com.assistant.core.wakeword.ports.WakeLoopEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trigger ingress (spec 14 §2.5: wake → cue → voice without an Activity; inv04 §3.1 broadcast
 * contract; RS-42, RS-45; fixes B5).
 */
class TriggerRouterParityTest {
    private val order = OrderLog()
    private val voice = FakeVoiceSessionController(order)
    private val cues = RecordingCues(order)
    private val sent = mutableListOf<ByteArray>()
    private var buttonTrigger = true
    private val router: TriggerRouter = hostCore.triggerRouter(
        TriggerDeps(voice, cues, { sent += it }, { buttonTrigger }, RecordingLog()),
    )

    /** RS-45 (`3c4dbba`): beep + CONNECTING flip happen synchronously on detection, before network work. */
    @Test
    fun rs45_cueAndConnectingFlipHappenSynchronouslyBeforeNetwork() {
        router.onWakeEvent(WakeLoopEvent.WakeDetected)
        assertEquals(listOf("cue.wakeAck", "voice.markConnecting", "voice.startVoice"), order.all)
    }

    @Test
    fun talkOnsetBeepsAndShowsRecording() {
        router.onWakeEvent(WakeLoopEvent.TalkDetected)
        assertEquals(listOf("cue.talkAck"), order.all)
        assertEquals(TalkUiState(wakeConfirming = false, isRecording = true), router.talkUi.value)
    }

    @Test
    fun confirmingShowsTheTransientIndicator() {
        router.onWakeEvent(WakeLoopEvent.Confirming(isRealtime = true))
        assertTrue(router.talkUi.value.wakeConfirming)
    }

    /** RS-42 (`b710c8b`): a rejected talk clears the record button (and confirming). */
    @Test
    fun rs42_rejectedTalkClearsRecording() {
        router.onWakeEvent(WakeLoopEvent.TalkDetected)
        router.onWakeEvent(WakeLoopEvent.ConfirmFailed(isRealtime = false))
        assertEquals(TalkUiState(), router.talkUi.value)
        assertTrue(sent.isEmpty())
    }

    /** RS-42 (`3bee23a`): a confirmed talk clears BOTH indicators and ships the WAV once. */
    @Test
    fun rs42_confirmedTalkClearsBothIndicatorsAndSendsOnce() {
        router.onWakeEvent(WakeLoopEvent.Confirming(isRealtime = false))
        router.onWakeEvent(WakeLoopEvent.TalkDetected)
        val wav = ByteArray(64) { 7 }
        router.onWakeEvent(WakeLoopEvent.TalkMessageCaptured(wav))
        assertEquals(TalkUiState(), router.talkUi.value)
        assertEquals(1, sent.size)
        assertTrue(sent.single().contentEquals(wav))
    }

    @Test
    fun aRejectedWakeOnlyClearsTheConfirmingIndicator() {
        router.onWakeEvent(WakeLoopEvent.Confirming(isRealtime = true))
        router.onWakeEvent(WakeLoopEvent.ConfirmFailed(isRealtime = true))
        assertEquals(TalkUiState(), router.talkUi.value)
        assertTrue("no voice start", "voice.startVoice" !in order.all)
    }

    @Test
    fun recentsAndAssistStartVoice() {
        router.onTrigger(Trigger.RECENTS)
        router.onTrigger(Trigger.ASSIST)
        assertEquals(2, order.all.count { it == "voice.startVoice" })
    }

    /** B5: the recents trigger honours `button_trigger_enabled` (the old /dev/input path ignored it). */
    @Test
    fun b5_recentsIsIgnoredWhenTheButtonTriggerIsDisabled() {
        buttonTrigger = false
        router.onTrigger(Trigger.RECENTS)
        assertTrue(order.all.isEmpty())
    }
}
