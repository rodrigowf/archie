package com.assistant.core.settings

import com.assistant.core.model.ThemeMode
import com.assistant.core.model.SavedServer
import com.assistant.core.model.AudioOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreTest {
    @Test fun nullUntilLoaded_thenSettersRoundTripWithOldClamps() = runTest {
        val ds = MemoryDataStore()
        val store = SettingsStore(ds, backgroundScope)
        assertNull("nobody may act on defaults before load (inv04 B4)", store.settings.value)
        store.awaitLoaded()

        store.setMicGainLevel(9f)
        store.setWakeWordMicGainLevel(-1f)
        store.setTalkSilenceSensitivity(0.2f)
        store.setEchoDuckingGain(3f)
        store.setAudioOutput(AudioOutput.BLUETOOTH)
        store.setThemeMode(ThemeMode.LIGHT)
        store.setServerUrl("  ws://10.0.0.2:80 ")
        runCurrent()
        val s = store.settings.first { it?.audioOutput == AudioOutput.BLUETOOTH && it.serverUrl == "ws://10.0.0.2:80" }!!
        assertEquals(1.5f, s.micGainLevel); assertEquals(0f, s.wakeWordMicGainLevel)
        assertEquals(1f, s.talkSilenceSensitivity); assertEquals(1f, s.echoDuckingGain)
        assertEquals(ThemeMode.LIGHT, s.themeMode)
        // Same wire values as the old app.
        assertEquals("BLUETOOTH", ds.current[SettingsKeys.AUDIO_OUTPUT]); assertEquals("LIGHT", ds.current[SettingsKeys.THEME_MODE])
    }

    @Test fun savedServersSurviveTabsAndPipes_v2Json() = runTest {
        val store = SettingsStore(MemoryDataStore(), backgroundScope)
        store.awaitLoaded()
        store.addSavedServer("Home | main\tbox", "ws://192.168.0.200:80")
        store.addSavedServer("Laptop", "ws://192.168.0.28:8765")
        store.addSavedServer("Home (renamed)", "ws://192.168.0.200:80")            // same URL replaces
        store.addSavedServer(" ", "ws://x")                                          // ignored
        runCurrent()
        assertEquals(
            listOf(SavedServer("Laptop", "ws://192.168.0.28:8765"), SavedServer("Home (renamed)", "ws://192.168.0.200:80")),
            store.settings.value!!.savedServers,
        )
        store.addSavedServer("a|b\tc", "ws://y")
        store.removeSavedServer("ws://192.168.0.28:8765")
        runCurrent()
        assertEquals(listOf("Home (renamed)", "a|b\tc"), store.settings.value!!.savedServers.map { it.label })
    }

    @Test fun orchestratorIdAndPins() = runTest {
        val ds = MemoryDataStore()
        val store = SettingsStore(ds, backgroundScope)
        store.awaitLoaded()
        store.setOrchestratorLocalId("L1")
        assertEquals("L1", store.orchestratorLocalId())
        store.clearOrchestratorLocalId()
        assertNull(store.orchestratorLocalId())

        store.savePin("192.168.0.200:443", "abc=")
        assertEquals("abc=", store.pinFor("192.168.0.200:443"))
        // Persisted, and reloaded by a fresh store over the same data.
        runCurrent()
        val store2 = SettingsStore(ds, backgroundScope)
        runCurrent()
        assertEquals("abc=", store2.pinFor("192.168.0.200:443"))
        store.removePin("192.168.0.200:443")
        assertNull(store.pinFor("192.168.0.200:443"))
    }

    @Test fun legacyServerDecodingMatchesTheOldParser() {
        assertEquals(listOf(SavedServer("A", "ws://a")), SettingsStore.decodeLegacyServers("A\tws://a|broken|\tws://b|C\t"))
        assertEquals(emptyList<SavedServer>(), SettingsStore.decodeLegacyServers(null))
    }
}
