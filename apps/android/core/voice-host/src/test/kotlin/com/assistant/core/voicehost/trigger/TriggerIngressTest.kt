package com.assistant.core.voicehost.trigger

import com.assistant.core.testing.FakeVoiceSessionController
import com.assistant.core.testing.OrderLog
import com.assistant.core.testing.RecordingCues
import com.assistant.core.testing.RecordingLog
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceSessionState
import com.assistant.core.voicehost.HostTuning
import com.assistant.core.voicehost.ports.Trigger
import com.assistant.core.voicehost.ports.TriggerDeps
import com.assistant.core.wakeword.ports.WakeLoopEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The single trigger ingress (spec 14 §2.5) and the recents long-press detection. */
class TriggerIngressTest {
    private val order = OrderLog()
    private val voice = FakeVoiceSessionController(order)
    private var buttonEnabled = true
    private var brought = 0
    private val ingress = TriggerIngress(
        DefaultTriggerRouter(TriggerDeps(voice, RecordingCues(order), { }, { buttonEnabled }, RecordingLog())),
        { brought++; order.add("bring") },
    ) { buttonEnabled }

    /** RS-45 stays intact through the ingress: cue + CONNECTING before anything else, then the screen. */
    @Test
    fun everyExternalTriggerTakesTheWakePath() {
        for (t in listOf(Trigger.ASSIST, Trigger.TILE, Trigger.NOTIFICATION, Trigger.RECENTS, Trigger.WAKE_WORD)) {
            order.clear()
            ingress.trigger(t)
            assertEquals("$t", listOf("cue.wakeAck", "voice.markConnecting", "voice.startVoice", "bring"), order.all)
        }
    }

    @Test
    fun theInAppButtonStartsVoiceWithoutCueOrScreenWake() {
        ingress.trigger(Trigger.BUTTON)
        assertEquals(listOf("voice.startVoice"), order.all)
    }

    @Test
    fun wakeAndTalkDetectionsWakeTheScreen() {
        ingress.onWakeEvent(WakeLoopEvent.WakeDetected)
        ingress.onWakeEvent(WakeLoopEvent.TalkDetected)
        ingress.onWakeEvent(WakeLoopEvent.Confirming(true))
        ingress.onWakeEvent(WakeLoopEvent.ConfirmFailed(true))
        assertEquals(2, brought)
    }

    @Test
    fun b5_disabledRecentsNeitherStartsVoiceNorWakesTheScreen() {
        buttonEnabled = false
        ingress.trigger(Trigger.RECENTS)
        assertTrue(order.all.isEmpty())
        assertEquals(0, brought)
    }

    /** A trigger while voice already runs must not re-flip the UI to CONNECTING or beep again. */
    @Test
    fun aTriggerDuringLiveVoiceIsIgnored() {
        voice.stateFlow.value = VoiceSessionState(phase = SessionPhase.SPEAKING, isOwner = true)
        ingress.trigger(Trigger.ASSIST)
        ingress.onWakeEvent(WakeLoopEvent.WakeDetected)
        assertFalse("voice.startVoice" in order.all)
        assertFalse("cue.wakeAck" in order.all)
    }

    @Test
    fun aTriggerAfterAnErrorStartsAgain() {
        voice.stateFlow.value = VoiceSessionState(phase = SessionPhase.ERROR)
        ingress.trigger(Trigger.ASSIST)
        assertTrue("voice.startVoice" in order.all)
    }

    @Test
    fun longPressNeedsSixHundredMillisecondsAndAPress() {
        val d = LongPressDetector()
        assertFalse("release without press (old code fired here)", d.onUp(10_000))
        d.onDown(10_000)
        assertFalse(d.onUp(10_000 + HostTuning.RECENTS_LONG_PRESS_MS - 1))
        d.onDown(20_000)
        d.onDown(20_100) // key repeat keeps the first down time
        assertTrue(d.onUp(20_000 + HostTuning.RECENTS_LONG_PRESS_MS))
        assertFalse("one fire per press", d.onUp(30_000))
    }

    /** The raw `/dev/input` monitor parses 32-bit little-endian `input_event`s (A300M sec_touchkey). */
    @Test
    fun devInputEventParsing() {
        val buf = ByteArray(16)
        // timeval 8 bytes (ignored), type EV_KEY=1, code KEY_APPSWITCH=0xfe, value 1 (down)
        buf[8] = 1; buf[10] = 0xfe.toByte(); buf[12] = 1
        assertEquals(DevInputRecentsMonitor.InputEvent(1, 0xfe, 1), DevInputRecentsMonitor.parse(buf))
        assertNull(DevInputRecentsMonitor.parse(ByteArray(8)))
    }

    /** Without access (no root: root:input 0660 + SELinux), the monitor fails once with the greppable Q8 marker. */
    @Test
    fun devInputFailureIsLoggedWithTheQ8Marker() {
        val log = RecordingLog()
        val m = DevInputRecentsMonitor(log, "/nonexistent/input/event2") { error("must not fire") }
        m.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (!log.contains("Recents monitor stopped") && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(log.dump(), log.contains(DevInputRecentsMonitor.LOG_MARKER))
        m.stop()
    }
}
