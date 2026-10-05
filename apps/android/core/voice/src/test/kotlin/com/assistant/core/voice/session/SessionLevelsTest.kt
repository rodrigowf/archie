package com.assistant.core.voice.session

import com.assistant.core.testing.FakeAudioSession
import com.assistant.core.testing.FakeClock
import com.assistant.core.testing.FakeOrchestratorContext
import com.assistant.core.testing.FakeVoiceApi
import com.assistant.core.testing.FakeVoiceTransport
import com.assistant.core.testing.FakeVoiceTransportFactory
import com.assistant.core.testing.FakeVoiceWire
import com.assistant.core.testing.FakeWakeHandoff
import com.assistant.core.testing.RecordingCues
import com.assistant.core.testing.RecordingLog
import com.assistant.core.testing.RecordingTranscriptSink
import com.assistant.core.voice.ports.ConnectionType
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceInbound
import com.assistant.core.voice.ports.VoiceLevels
import com.assistant.core.voice.ports.VoiceSessionDeps
import com.assistant.core.voice.ports.VoiceTransport
import com.assistant.core.voice.ports.VoiceTransportFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The controller's `levels`: the current transport's while subscribed, null without one. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionLevelsTest {

    private class MeteredTransport(inner: FakeVoiceTransport, override val levels: Flow<VoiceLevels>) : VoiceTransport by inner

    @Test
    fun followsTheLiveTransportAndClearsWhenItEnds() = runTest {
        val clock = FakeClock.boundTo(testScheduler)
        val fakes = FakeVoiceTransportFactory(clock)
        val transportLevels = MutableStateFlow(VoiceLevels(0.1f, 0.2f))
        val factory = object : VoiceTransportFactory {
            override fun create(providerId: String, connectionType: ConnectionType): VoiceTransport =
                MeteredTransport(fakes.create(providerId, connectionType) as FakeVoiceTransport, transportLevels)
        }
        val controller = DefaultVoiceSessionController(
            VoiceSessionDeps(
                backgroundScope, clock, RecordingLog(), FakeVoiceApi(clock), FakeVoiceWire(clock), FakeOrchestratorContext(),
                FakeWakeHandoff(clock), factory, FakeAudioSession(clock), RecordingTranscriptSink(), RecordingCues(),
            ),
        )
        val seen = mutableListOf<VoiceLevels?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.levels.collect { seen += it } }
        assertNull(controller.levels.value)

        controller.startVoice()
        testScheduler.advanceTimeBy(1_000)
        runCurrent()
        assertEquals(SessionPhase.ACTIVE, controller.state.value.phase)
        assertEquals(VoiceLevels(0.1f, 0.2f), controller.levels.value)
        transportLevels.value = VoiceLevels(0.3f, 0f)
        runCurrent()
        assertEquals(VoiceLevels(0.3f, 0f), controller.levels.value)

        controller.onInbound(VoiceInbound.Ended("idle"))
        runCurrent()
        assertNull(controller.levels.value)
        assertEquals(listOf(null, VoiceLevels(0.1f, 0.2f), VoiceLevels(0.3f, 0f), null), seen)
        controller.release()
    }
}
