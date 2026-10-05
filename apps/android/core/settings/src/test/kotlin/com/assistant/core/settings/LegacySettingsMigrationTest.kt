package com.assistant.core.settings

import com.assistant.core.model.DeviceSettings
import com.assistant.core.model.ThemeMode
import com.assistant.core.model.SavedServer
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.assistant.core.model.AudioOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The lite app upgrades the A300M in place and must read the old DataStore `settings` file with
 * the exact keys (spec 14 §1.2, §5.7, A-03 DoD). The fixture `legacy/settings.preferences_pb` is
 * hand-encoded protobuf from the old key list (`tools/make_legacy_settings_fixture.py`), independent
 * of this reader, with non-default values for every key.
 */
class LegacySettingsMigrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    private fun legacyFile(): File {
        val dir = tmp.newFolder("datastore")
        val f = File(dir, "${SettingsKeys.FILE_NAME}.preferences_pb")
        LegacySettingsMigrationTest::class.java.getResourceAsStream("/legacy/settings.preferences_pb")!!.use { input ->
            f.outputStream().use { input.copyTo(it) }
        }
        return f
    }

    @Test fun readsEveryOldKeyFromTheOldFile() = runBlocking {
        val file = legacyFile()
        val ds = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

        // The fixture really contains every legacy key (guards the fixture itself).
        val raw = ds.data.first().asMap().keys.map { it.name }.toSet()
        SettingsKeys.LEGACY_NAMES.forEach { assertEquals("fixture lacks $it", true, it in raw) }

        val store = SettingsStore(ds, scope)
        val s = withTimeout(5_000) { store.awaitLoaded() }
        assertEquals(
            DeviceSettings(
                serverUrl = "ws://192.168.0.123:8765",
                savedServers = listOf(SavedServer("Jetson", "ws://192.168.0.200:80"), SavedServer("Laptop", "ws://192.168.0.28:8765")),
                autoConnect = false,
                enableWakeWord = false,
                talkWord = "hey buddy, my friend",
                wakeWord = "wake up, archie",
                themeMode = ThemeMode.DARK,
                micGainLevel = 1.3f,
                wakeWordMicGainLevel = 0.7f,
                talkSilenceSensitivity = 3.5f,
                echoDuckingGain = 0.08f,
                audioOutput = AudioOutput.EARPIECE,
                enableButtonTrigger = true,
            ),
            s,
        )
        assertEquals("3540ff69-0000-4000-8000-000000000001", store.orchestratorLocalId())

        // One-time migration: v2 servers written, legacy key kept (rollback), per-token checkpoints purged.
        val after = withTimeout(5_000) {
            ds.data.first { p -> p[SettingsKeys.SAVED_SERVERS_V2] != null }
        }
        assertEquals(
            listOf(SavedServer("Jetson", "ws://192.168.0.200:80"), SavedServer("Laptop", "ws://192.168.0.28:8765")),
            SettingsStore.decodeServers(after[SettingsKeys.SAVED_SERVERS_V2]!!),
        )
        assertEquals("Jetson\tws://192.168.0.200:80|Laptop\tws://192.168.0.28:8765", after[SettingsKeys.SAVED_SERVERS_LEGACY])
        assertFalse(after.asMap().keys.any { it.name.startsWith(SettingsKeys.LEGACY_CHECKPOINT_PREFIX) })
        assertEquals(0.4f, after[SettingsKeys.SPEAKER_VOLUME_LEVEL])            // untouched, just unused
    }

    @Test fun emptyFileGivesOldDefaults() = runBlocking {
        val ds = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.newFolder("d"), "settings.preferences_pb") })
        val store = SettingsStore(ds, scope)
        val s = withTimeout(5_000) { store.awaitLoaded() }
        assertEquals(DeviceSettings(), s)
        assertEquals("ws://192.168.0.200:80", s.serverUrl)
        assertEquals("my friend", s.talkWord); assertEquals("wake up", s.wakeWord)
        assertEquals(0.05f, s.echoDuckingGain); assertEquals(2.0f, s.talkSilenceSensitivity)
        assertNull(store.orchestratorLocalId())
    }

    /** The key list is checked against the old source kept under `legacy/android/` since the cutover. */
    @Test fun legacyKeyNamesMatchTheOldSource() {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var old: File? = null
        while (dir != null && old == null) {
            File(dir, "legacy/android/app/src/main/java/com/assistant/peripheral/settings/SettingsRepository.kt").takeIf { it.isFile }?.let { old = it }
            dir = dir.parentFile
        }
        assumeTrue("old app tree not present", old != null)
        val names = Regex("""PreferencesKey\("([^"]+)"\)""").findAll(old!!.readText()).map { it.groupValues[1] }
            .filterNot { it.startsWith("ws_resume_checkpoint") }.toSet()
        assertEquals(names, SettingsKeys.LEGACY_NAMES.toSet())
    }
}
