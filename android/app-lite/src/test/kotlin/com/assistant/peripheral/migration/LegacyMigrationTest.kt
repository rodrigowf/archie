package com.assistant.peripheral.migration

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.assistant.core.model.AudioOutput
import com.assistant.core.model.DeviceSettings
import com.assistant.core.model.SavedServer
import com.assistant.core.model.ThemeMode
import com.assistant.core.settings.MemoryKeyValue
import com.assistant.core.settings.ServicePrefs
import com.assistant.core.settings.SettingsKeys
import com.assistant.core.settings.SettingsStore
import com.assistant.core.wakeword.vosk.VoskModelFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * The in-place upgrade over the A300M's old app (spec 14 §5.7 step 4, L-F2), on a realistic old
 * `settings` DataStore: written by the DataStore library itself with the old app's exact keys and
 * value shapes (`SettingsRepository.kt:325-343`), including the unbounded per-token
 * `ws_resume_checkpoint:<localId>` = `"<streamId>|<seq>"` entries (inv03 §2.2) a long-lived device
 * accumulates, plus the old `assistant_service_prefs` and an extracted `vosk-model/`.
 */
class LegacyMigrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    private val checkpoints = 180

    /** What the old app (1.0.9) wrote on the A300M after months of use. */
    private suspend fun writeOldAppDataStore(file: File) {
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = oldScope, produceFile = { file })
        ds.edit { p ->
            p[stringPreferencesKey("server_url")] = "ws://192.168.0.200:80"
            p[stringPreferencesKey("saved_servers")] = "Jetson\tws://192.168.0.200:80|Laptop\tws://192.168.0.28:8765"
            p[booleanPreferencesKey("auto_connect")] = true
            p[booleanPreferencesKey("enable_wake_word")] = true
            p[stringPreferencesKey("turn_talk_word")] = "my friend, hello my friend"
            p[stringPreferencesKey("realtime_wake_word")] = "wake up"
            p[stringPreferencesKey("theme_mode")] = "DARK"
            p[floatPreferencesKey("mic_gain_level")] = 1.2f
            p[floatPreferencesKey("wake_word_mic_gain_level")] = 1.4f
            p[floatPreferencesKey("talk_silence_sensitivity")] = 2.5f
            p[floatPreferencesKey("speaker_volume_level")] = 1.0f
            p[floatPreferencesKey("echo_ducking_gain")] = 0.035f
            p[stringPreferencesKey("audio_output")] = "LOUDSPEAKER"
            p[booleanPreferencesKey("enable_button_trigger")] = true
            p[stringPreferencesKey("orchestrator_local_id")] = "8f1d2c3b-0000-4000-8000-00000000a300"
            repeat(checkpoints) { i ->
                p[stringPreferencesKey("ws_resume_checkpoint:${UUID(0xA300L, i.toLong())}")] = "s-${1_700_000_000 + i}|${i * 37}"
            }
        }
        // The old process dies before the new app opens the file (one active DataStore per file).
        oldScope.coroutineContext[Job]!!.cancelAndJoin()
    }

    private fun oldServicePrefs() = MemoryKeyValue(
        linkedMapOf(
            ServicePrefs.KEY_ENABLED to true, ServicePrefs.KEY_TALK_WORD to "my friend, hello my friend",
            ServicePrefs.KEY_WAKE_WORD to "wake up", ServicePrefs.KEY_WAKE_MIC_GAIN to 1.4f,
            ServicePrefs.KEY_TALK_SILENCE_SENSITIVITY to 2.5f, ServicePrefs.KEY_SERVER_URL to "ws://192.168.0.200:80",
            ServicePrefs.KEY_BUTTON_TRIGGER to true,
        ),
    )

    private fun extractedModel(filesDir: File): File {
        val dir = File(filesDir, VoskModelFiles.extractDirName).apply { mkdirs() }
        File(dir, "am").mkdirs()
        File(dir, "am/final.mdl").writeBytes(ByteArray(4096) { it.toByte() })
        File(dir, VoskModelFiles.stampFileName).writeText(VoskModelFiles.stamp)
        return dir
    }

    @Test fun upgradeKeepsSettings_purgesCheckpoints_convertsServers_keepsPrefsAndModel() = runBlocking {
        val filesDir = tmp.newFolder("files")
        val file = File(tmp.newFolder("datastore"), "${SettingsKeys.FILE_NAME}.preferences_pb")
        writeOldAppDataStore(file)
        val sizeBefore = file.length()
        val model = extractedModel(filesDir)
        val modelStamp = File(model, VoskModelFiles.stampFileName).lastModified()
        val prefs = oldServicePrefs()
        val prefsBefore = prefs.values.toMap()
        val marker = MemoryKeyValue()

        // The new app opens the same file (its own DataStore instance, as after an `adb install -r`).
        val ds = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        assertEquals(15 + checkpoints, ds.data.first().asMap().size)
        val logs = mutableListOf<String>()
        val report = LegacyMigration(ds, prefs, marker, filesDir) { logs += it }.run()

        assertTrue(report.ran)
        assertEquals(checkpoints, report.checkpointsPurged)
        assertEquals(2, report.serversConverted)
        assertEquals(16, report.settingsKeys) // 15 old keys + saved_servers_v2
        assertTrue(report.servicePrefsPresent)
        assertTrue("same stamp → no 68 MB re-extract", report.voskModelKept)
        assertTrue(logs.single().startsWith(LegacyMigration.LOG_MARKER))

        val raw = ds.data.first()
        assertFalse(raw.asMap().keys.any { it.name.startsWith(SettingsKeys.LEGACY_CHECKPOINT_PREFIX) })
        SettingsKeys.LEGACY_NAMES.forEach { assertTrue("kept $it", raw.asMap().keys.any { k -> k.name == it }) }
        assertEquals("Jetson\tws://192.168.0.200:80|Laptop\tws://192.168.0.28:8765", raw[SettingsKeys.SAVED_SERVERS_LEGACY]) // rollback
        assertTrue("the file shrank", file.length() < sizeBefore)

        // The new reader sees exactly the old values.
        val s = withTimeout(5_000) { SettingsStore(ds, scope).awaitLoaded() }
        assertEquals(
            DeviceSettings(
                serverUrl = "ws://192.168.0.200:80",
                savedServers = listOf(SavedServer("Jetson", "ws://192.168.0.200:80"), SavedServer("Laptop", "ws://192.168.0.28:8765")),
                autoConnect = true, enableWakeWord = true, talkWord = "my friend, hello my friend", wakeWord = "wake up",
                themeMode = ThemeMode.DARK, micGainLevel = 1.2f, wakeWordMicGainLevel = 1.4f, talkSilenceSensitivity = 2.5f,
                echoDuckingGain = 0.035f, audioOutput = AudioOutput.LOUDSPEAKER, enableButtonTrigger = true,
            ),
            s,
        )
        // assistant_service_prefs and the Vosk model are untouched.
        assertEquals(prefsBefore, prefs.values.toMap())
        assertEquals(VoskModelFiles.stamp, File(model, VoskModelFiles.stampFileName).readText())
        assertEquals(modelStamp, File(model, VoskModelFiles.stampFileName).lastModified())
        assertEquals(4096L, File(model, "am/final.mdl").length())

        // One-time: the marker skips it on the next start.
        assertEquals(LegacyMigration.VERSION.toString(), marker.getString(LegacyMigration.KEY_VERSION, ""))
        assertFalse(LegacyMigration(ds, prefs, marker, filesDir).run().ran)
    }

    /** The hand-encoded protobuf fixture of A-03 (independent of the DataStore writer). */
    @Test fun handEncodedOldFile_alsoMigrates() = runBlocking {
        val file = File(tmp.newFolder("ds"), "settings.preferences_pb")
        javaClass.getResourceAsStream("/legacy/settings.preferences_pb")!!.use { i -> file.outputStream().use { i.copyTo(it) } }
        val ds = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val hadCheckpoints = ds.data.first().asMap().keys.count { it.name.startsWith(SettingsKeys.LEGACY_CHECKPOINT_PREFIX) }
        val report = LegacyMigration(ds, MemoryKeyValue(), MemoryKeyValue(), tmp.newFolder("f")).run()
        assertEquals(hadCheckpoints, report.checkpointsPurged)
        assertFalse(report.servicePrefsPresent)
        assertFalse(report.voskModelKept)
        val s = withTimeout(5_000) { SettingsStore(ds, scope).awaitLoaded() }
        assertEquals("ws://192.168.0.123:8765", s.serverUrl)
        assertEquals(AudioOutput.EARPIECE, s.audioOutput)
    }

    @Test fun freshInstall_isANoOpThatStillMarks() = runBlocking {
        val ds = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.newFolder("n"), "settings.preferences_pb") })
        val marker = MemoryKeyValue()
        val r = LegacyMigration(ds, MemoryKeyValue(), marker, tmp.newFolder("f2")).run()
        assertTrue(r.ran)
        assertEquals(0, r.checkpointsPurged)
        assertEquals(0, r.serversConverted)
        assertEquals("1", marker.getString(LegacyMigration.KEY_VERSION, ""))
    }
}
