package com.assistant.archie.tv.voice

import com.assistant.core.audio.ports.MonotonicClock
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceSessionState
import com.assistant.core.voicehost.VoiceHost
import com.assistant.core.voicehost.VoiceHostSettings
import com.assistant.core.voicehost.VoiceUiEvent
import com.assistant.core.voicehost.VoiceUiState
import com.assistant.core.voicehost.ports.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A8 / spec 12 §7 (V-12): Play/Pause never silently takes over another device's live voice. */
class TvLiveVoiceTest {
    private class FakeHost : VoiceHost {
        override val state = MutableStateFlow(VoiceUiState())
        override val events = MutableSharedFlow<VoiceUiEvent>()
        val calls = mutableListOf<String>()
        override fun connect() {}
        override fun disconnect() {}
        override fun startVoice(trigger: Trigger) { calls += "start" }
        override fun stopVoice() { calls += "stop" }
        override fun reconnectVoice() {}
        override fun toggleMute() {}
        override fun toggleSpeakerMute() {}
        override fun startPushToTalk() {}
        override fun stopPushToTalk(send: Boolean) {}
        override fun pauseListening() {}
        override fun resumeListening() {}
        override fun updateSettings(s: VoiceHostSettings) {}
    }

    private class Clock(var now: Long = 10_000) : MonotonicClock {
        override fun nowMs() = now
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val host = FakeHost()
    private val clock = Clock()
    private val toasts = mutableListOf<String>()
    private val live = TvLiveVoice(
        host = host,
        bridge = LiveMicBridge(clock),
        scope = scope,
        clock = clock,
        hasPermission = { true },
        requestPermission = {},
        toast = { t, _ -> toasts += t },
        keepAwake = {},
    )

    @After fun tearDown() = scope.cancel()

    private fun elsewhere() {
        host.state.value = VoiceUiState(session = VoiceSessionState(phase = SessionPhase.OFF, isOwner = false, remoteVoiceActive = true))
    }

    @Test
    fun playPauseOffersTakeOverInsteadOfTakingIt() {
        elsewhere()
        assertTrue(live.toggle())
        assertTrue("no silent take-over", host.calls.isEmpty())
        assertEquals(listOf(TvLiveVoice.TAKE_OVER_OFFER), toasts)
    }

    @Test
    fun playPauseAgainWithinTheOfferTakesOver() {
        elsewhere()
        live.toggle()
        clock.now += 3_000
        live.toggle()
        assertEquals(listOf("start"), host.calls)
    }

    @Test
    fun aStaleOfferIsMadeAgain() {
        elsewhere()
        live.toggle()
        clock.now += TvLiveVoice.TAKE_OVER_CONFIRM_MS + 1
        live.toggle()
        assertTrue(host.calls.isEmpty())
        assertEquals(2, toasts.size)
    }

    @Test
    fun theTakeOverActionTakesOverAtOnce() {
        elsewhere()
        live.takeOver()
        assertEquals(listOf("start"), host.calls)
    }

    @Test
    fun withNobodyElseLivePlayPauseStartsAndStops() {
        live.toggle()
        assertEquals(listOf("start"), host.calls)
        host.state.value = VoiceUiState(session = VoiceSessionState(phase = SessionPhase.ACTIVE, isOwner = true))
        live.toggle()
        assertEquals(listOf("start", "stop"), host.calls)
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun chipKnowsWhenVoiceIsElsewhere() {
        assertTrue(VoiceChip.elsewhere(VoiceSessionState(phase = SessionPhase.OFF, isOwner = false, remoteVoiceActive = true)))
        assertTrue(!VoiceChip.elsewhere(VoiceSessionState(phase = SessionPhase.ACTIVE, isOwner = true)))
    }
}
