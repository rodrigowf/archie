package com.assistant.core.voicehost.parity

import com.assistant.core.testing.FakeClock
import com.assistant.core.testing.FakeWakeConfigStore
import com.assistant.core.testing.FakeWakeEngineProvider
import com.assistant.core.testing.RecordingLog
import com.assistant.core.voicehost.ports.WakeNotice
import com.assistant.core.voicehost.ports.WakeServiceConfig
import com.assistant.core.voicehost.ports.WakeServiceController
import com.assistant.core.voicehost.ports.WakeServiceDeps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Wake-word service logic (old `AssistantService`, inv04 §3.5; §10.2 "Service"; RS-31…RS-34; R3).
 * Ports the intent of `PauseResumeAckParityTest`, `MicUnavailableParityTest` (notification side).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Ignore("A-08")
class WakeServiceParityTest {

    private class Rig(private val ts: TestScope, stored: WakeServiceConfig? = null) {
        val clock = FakeClock.boundTo(ts.testScheduler)
        val store = FakeWakeConfigStore(stored)
        val engines = FakeWakeEngineProvider()
        val service: WakeServiceController = hostCore.wakeService(WakeServiceDeps(ts.backgroundScope, clock, RecordingLog(), store, engines))
        fun advance(ms: Long) { ts.testScheduler.advanceTimeBy(ms); ts.runCurrent() }
    }

    private val enabled = WakeServiceConfig(true, "my friend", "wake up", 1.3f, 2.5f, "ws://192.168.0.200:80")

    @Test
    fun configUpdatePersistsEverythingAndStartsTheEngine() = runTest {
        val r = Rig(this)
        r.service.onConfigUpdate(enabled)
        assertEquals(enabled, r.store.stored)
        assertEquals(1, r.engines.created.size)
        assertEquals(enabled, r.engines.last.config)
        assertEquals(listOf("start"), r.engines.last.calls)
    }

    /** RS-33 (`d6181b1`/`9200d50`): config changes never reset the wake gain to 1.0. */
    @Test
    fun rs33_configChangesNeverResetTheWakeGain() = runTest {
        val r = Rig(this)
        r.service.onConfigUpdate(enabled)
        r.advance(5_000)
        r.service.onConfigUpdate(enabled.copy(talkWord = "hello my friend"))
        assertEquals(1.3f, r.engines.last.config.wakeGain, 0f)
        r.advance(5_000)
        r.service.onStickyRestart()
        assertEquals(1.3f, r.engines.last.config.wakeGain, 0f)
        assertTrue(r.engines.created.all { it.config.wakeGain == 1.3f })
    }

    /** RS-34: an identical update redelivered within 3 s builds one engine. */
    @Test
    fun rs34_duplicateUpdateWithinThreeSecondsStartsOneEngine() = runTest {
        val r = Rig(this)
        r.service.onConfigUpdate(enabled)
        r.advance(20)
        r.service.onConfigUpdate(enabled)
        r.advance(1_300)
        r.service.onConfigUpdate(enabled)
        assertEquals(1, r.engines.created.size)
        r.advance(3_000)
        r.service.onConfigUpdate(enabled)
        assertEquals(2, r.engines.created.size)
    }

    @Test
    fun disablingStopsTheEngineAndClearsTheDedupe() = runTest {
        val r = Rig(this)
        r.service.onConfigUpdate(enabled)
        r.service.onConfigUpdate(enabled.copy(enabled = false))
        assertTrue("stop" in r.engines.last.calls)
        assertFalse(r.store.stored!!.enabled)
        r.advance(100)
        r.service.onConfigUpdate(enabled)
        assertEquals("re-enable within 3 s is a real restart", 2, r.engines.created.size)
    }

    /** RS-34 (`495b5d9`): pause and resume always complete their acks. */
    @Test
    fun rs34_pauseAndResumeAcksComplete() = runTest {
        val r = Rig(this)
        r.service.onConfigUpdate(enabled)
        val pause = CompletableDeferred<Unit>()
        r.service.onPauseForVoice(pause)
        assertTrue(pause.isCompleted)
        assertTrue(r.service.voiceSessionActive)
        assertTrue("pause" in r.engines.last.calls)
        r.advance(10_000)
        val resume = CompletableDeferred<Unit>()
        r.service.onResumeAfterVoice(resume)
        assertTrue(resume.isCompleted)
        assertFalse(r.service.voiceSessionActive)
    }

    /** RS-34: a duplicate resume is ignored but still acked. */
    @Test
    fun rs34_duplicateResumeIsIgnoredButAcked() = runTest {
        val r = Rig(this)
        r.service.onConfigUpdate(enabled)
        r.service.onPauseForVoice(CompletableDeferred())
        r.advance(10_000)
        r.service.onResumeAfterVoice(CompletableDeferred())
        val engines = r.engines.created.size
        r.advance(700)
        val dup = CompletableDeferred<Unit>()
        r.service.onResumeAfterVoice(dup)
        assertTrue(dup.isCompleted)
        assertEquals(engines, r.engines.created.size)
    }

    /** inv04 §3.5: resume does a FULL restart (a new engine), not `resume()`. */
    @Test
    fun resumeAfterVoiceRebuildsTheEngine() = runTest {
        val r = Rig(this)
        r.service.onConfigUpdate(enabled)
        r.service.onPauseForVoice(CompletableDeferred())
        r.advance(10_000)
        r.service.onResumeAfterVoice(CompletableDeferred())
        assertEquals(2, r.engines.created.size)
        assertEquals(listOf("start"), r.engines.last.calls)
    }

    /** RS-31 (`d93f7d7`): wake disabled during the call is not re-enabled on resume. */
    @Test
    fun rs31_wakeDisabledDuringCallIsNotReenabledOnResume() = runTest {
        val r = Rig(this)
        r.service.onConfigUpdate(enabled)
        r.service.onPauseForVoice(CompletableDeferred())
        r.service.onConfigUpdate(enabled.copy(enabled = false))
        r.advance(10_000)
        val ack = CompletableDeferred<Unit>()
        r.service.onResumeAfterVoice(ack)
        assertTrue(ack.isCompleted)
        assertEquals(1, r.engines.created.size)
        assertFalse(r.engines.last.isActive)
    }

    /** RS-32 (`5bde0d1`): a sticky restart after process death restores the persisted config. */
    @Test
    fun rs32_stickyRestartRestoresPersistedConfig() = runTest {
        val r = Rig(this, stored = enabled)
        r.service.onStickyRestart()
        assertEquals(enabled, r.engines.last.config)
        assertEquals(enabled, r.service.currentConfig)
        val off = Rig(this, stored = enabled.copy(enabled = false))
        off.service.onStickyRestart()
        assertTrue(off.engines.created.isEmpty())
    }

    @Test
    fun anEmptyStoreUsesTheOldDefaultsAndStaysOff() = runTest {
        val r = Rig(this)
        r.service.onStickyRestart()
        assertTrue(r.engines.created.isEmpty())
        assertEquals(WakeServiceConfig(false, "my friend", "wake up", 1.0f, 2.0f, ""), r.service.currentConfig)
    }

    /** RS-32 (`95201b5`): SCREEN_ON / USER_PRESENT (debounced 300 ms) re-arm without a lock screen. */
    @Test
    fun rs32_screenOnRearmsAfterTheDebounce() = runTest {
        val r = Rig(this, stored = enabled)
        r.service.onScreenOnOrUserPresent()
        r.advance(50)
        r.service.onScreenOnOrUserPresent()
        r.advance(299)
        assertTrue(r.engines.created.isEmpty())
        r.advance(1)
        assertEquals("SCREEN_ON + USER_PRESENT collapse into one re-arm", 1, r.engines.created.size)
    }

    @Test
    fun screenOnResumesAPausedEngine() = runTest {
        val r = Rig(this, stored = enabled)
        r.service.onConfigUpdate(enabled)
        r.engines.last.pause()
        r.service.onScreenOnOrUserPresent()
        r.advance(300)
        assertTrue("resume" in r.engines.last.calls)
        assertEquals(1, r.engines.created.size)
    }

    @Test
    fun screenOnDuringVoiceIsSkipped() = runTest {
        val r = Rig(this, stored = enabled)
        r.service.onConfigUpdate(enabled)
        r.service.onPauseForVoice(CompletableDeferred())
        r.advance(10_000)
        r.service.onScreenOnOrUserPresent()
        r.advance(300)
        assertEquals(1, r.engines.created.size)
        assertEquals(listOf("start", "pause"), r.engines.last.calls)
    }

    /** R3 (new requirement): a screen-on caused by the talk onset must not restart a confirm/capture. */
    @Test
    fun r3_screenOnNeverRestartsABusyEngine() = runTest {
        val r = Rig(this, stored = enabled)
        r.service.onConfigUpdate(enabled)
        r.advance(10_000)
        r.engines.last.isBusy = true
        r.service.onScreenOnOrUserPresent()
        r.advance(300)
        assertEquals(1, r.engines.created.size)
        assertEquals(listOf("start"), r.engines.last.calls)
    }

    @Test
    fun screenOnRestartsAHealthyIdleEngine() = runTest {
        val r = Rig(this, stored = enabled)
        r.service.onConfigUpdate(enabled)
        r.advance(10_000)
        r.service.onScreenOnOrUserPresent()
        r.advance(300)
        assertEquals(2, r.engines.created.size)
        assertTrue("stop" in r.engines.created[0].calls)
    }

    @Test
    fun screenOnWithWakeDisabledDoesNothing() = runTest {
        val r = Rig(this, stored = enabled.copy(enabled = false))
        r.service.onScreenOnOrUserPresent()
        r.advance(1_000)
        assertTrue(r.engines.created.isEmpty())
    }

    @Test
    fun recognizerUnhealthyRebuildsUnlessVoiceIsActive() = runTest {
        val r = Rig(this)
        r.service.onConfigUpdate(enabled)
        r.advance(10_000)
        r.service.onRecognizerUnhealthy()
        assertEquals(2, r.engines.created.size)
        r.service.onPauseForVoice(CompletableDeferred())
        r.advance(10_000)
        r.service.onRecognizerUnhealthy()
        assertEquals(2, r.engines.created.size)
    }

    /** Mic stalled: the notification flips once per stretch (inv04 §3.5, F18). */
    @Test
    fun micStalledNoticeTogglesOncePerStretch() = runTest {
        val r = Rig(this)
        val seen = mutableListOf(r.service.notice.value)
        fun observe() { if (seen.last() != r.service.notice.value) seen += r.service.notice.value }
        r.service.onMicUnavailable(); observe()
        r.service.onMicUnavailable(); observe()
        r.service.onMicAvailable(); observe()
        r.service.onMicAvailable(); observe()
        assertEquals(listOf(WakeNotice.NORMAL, WakeNotice.MIC_STALLED, WakeNotice.NORMAL), seen)
    }
}
