package com.assistant.peripheral.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.assistant.core.model.AudioOutput
import com.assistant.core.settings.SettingsStore
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A recording [VoiceHost] for UI-side tests (the lite UI on fakes, spec 14 §7 C-01). */
class FakeVoiceHost : VoiceHost {
    override val state = MutableStateFlow(VoiceUiState())
    override val events = MutableSharedFlow<VoiceUiEvent>()
    val calls = mutableListOf<String>()
    val applied = mutableListOf<VoiceHostSettings>()
    override fun connect() { calls += "connect" }
    override fun disconnect() { calls += "disconnect" }
    override fun startVoice(trigger: Trigger) { calls += "start:$trigger" }
    override fun stopVoice() { calls += "stop" }
    override fun reconnectVoice() { calls += "reconnect" }
    override fun toggleMute() { calls += "mute" }
    override fun toggleSpeakerMute() { calls += "speakerMute" }
    override fun startPushToTalk() { calls += "ptt" }
    override fun stopPushToTalk(send: Boolean) { calls += "pttStop" }
    override fun pauseListening() { calls += "pause" }
    override fun resumeListening() { calls += "resume" }
    override fun updateSettings(s: VoiceHostSettings) { applied += s }
}

class LiteSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    private fun store() = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.newFolder(), "settings.preferences_pb") }, scope)

    @Test fun everyChangeGoesThroughTheHostThenPersists() = runBlocking {
        val store = store()
        withTimeout(5_000) { store.awaitLoaded() }
        val host = FakeVoiceHost()
        val s = LiteSettings(scope, store, host)

        s.setMicGainStep(13)
        assertEquals(1.3f, host.applied.last().micGain)
        withTimeout(5_000) { store.settings.first { it?.micGainLevel == 1.3f } }

        s.setEchoDuckStep(3)
        assertEquals(0.015f, host.applied.last().echoDuckingGain, 1e-6f)
        // Explicit gains every time (RS-33): the duck change still carries the mic gain.
        assertEquals(1.3f, host.applied.last().micGain)

        s.setOutput(AudioOutput.EARPIECE)
        s.setTalkStopStep(5)
        s.setWakeSensitivityStep(7)
        s.setRecentsTrigger(true)
        val last = withTimeout(5_000) { store.settings.first { it?.enableButtonTrigger == true && it.wakeWordMicGainLevel == 0.7f } }!!
        assertEquals(AudioOutput.EARPIECE, last.audioOutput)
        assertEquals(3.5f, last.talkSilenceSensitivity)
        assertEquals(VoiceHostSettings.from(last), host.applied.last())
    }

    @Test fun unchangedValueIsNotReapplied_andNothingBeforeLoad() = runBlocking {
        val store = store()
        withTimeout(5_000) { store.awaitLoaded() }
        val host = FakeVoiceHost()
        LiteSettings(scope, store, host).setMicGainStep(10) // default 1.0
        assertTrue(host.applied.isEmpty())
        LiteSettings(scope, store, host) { null }.setMicGainStep(3)
        assertTrue("nothing applied before the real settings loaded (inv04 B4)", host.applied.isEmpty())
    }

    @Test fun phrasesNormalize_andBlankIsIgnored() = runBlocking {
        val store = store()
        withTimeout(5_000) { store.awaitLoaded() }
        val host = FakeVoiceHost()
        val s = LiteSettings(scope, store, host)
        s.setWakePhrases("  Wake Up ,, hey Archie ")
        assertEquals("wake up, hey archie", host.applied.last().wakeWord)
        s.setTalkPhrases(" , ")
        assertEquals(1, host.applied.size)
    }

    @Test fun sliderGridsMatchTheOldApp() {
        assertEquals((0..15).map { it / 10f }, (0..Steps.GAIN_MAX).map(Steps::gainOf))
        assertEquals("150%", Steps.gainLabel(15))
        assertEquals(10, Steps.gainStep(1.0f))
        assertEquals(0.1f, Steps.duckOf(20), 1e-6f)
        assertEquals(10, Steps.duckStep(0.05f)) // default 5 %
        assertEquals("5.0%", Steps.duckLabel(10))
        assertEquals(listOf(1f, 1.5f, 2f, 2.5f, 3f, 3.5f, 4f), (0..Steps.TALK_STOP_MAX).map(Steps::talkStopOf))
        assertEquals(2, Steps.talkStopStep(2.0f))
    }
}
