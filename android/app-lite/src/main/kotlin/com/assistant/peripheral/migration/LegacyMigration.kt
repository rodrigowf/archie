package com.assistant.peripheral.migration

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.assistant.core.settings.KeyValue
import com.assistant.core.settings.ServicePrefs
import com.assistant.core.settings.SettingsKeys
import com.assistant.core.settings.SettingsStore
import com.assistant.core.wakeword.vosk.VoskModelFiles
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * One-time in-place upgrade from the old `com.assistant.peripheral` 1.0.x (spec 14 §5.7 step 4,
 * field test L-F2). It runs before anything else reads settings and is idempotent:
 *
 * - keeps the DataStore `settings` file and every key in it (the new reader uses the same names);
 * - purges every `ws_resume_checkpoint:*` key (they grew without bound, inv03 §2.2; resume
 *   checkpoints now live in the bounded `resume_checkpoints` store);
 * - converts `saved_servers` (`label\turl|…`) to `saved_servers_v2` (JSON), keeping the legacy key
 *   for rollback;
 * - keeps `assistant_service_prefs` (wake config + button-trigger mirror) untouched;
 * - keeps `filesDir/vosk-model/` (same stamp → no 68 MB re-extract on the A300M).
 *
 * A marker in [marker] (`lite_migration`) records the version so later starts skip the work.
 */
class LegacyMigration(
    private val settings: DataStore<Preferences>,
    private val servicePrefs: KeyValue,
    private val marker: KeyValue,
    private val filesDir: File,
    private val log: (String) -> Unit = {},
) {
    data class Report(
        val ran: Boolean,
        val settingsKeys: Int = 0,
        val checkpointsPurged: Int = 0,
        val serversConverted: Int = 0,
        val servicePrefsPresent: Boolean = false,
        val voskModelKept: Boolean = false,
    )

    suspend fun run(): Report {
        if ((marker.getString(KEY_VERSION, "").toIntOrNull() ?: 0) >= VERSION) return Report(ran = false)
        var purged = 0
        var converted = 0
        settings.edit { p ->
            val stale = p.asMap().keys.filter { it.name.startsWith(SettingsKeys.LEGACY_CHECKPOINT_PREFIX) }
            stale.forEach { p.remove(it) }
            purged = stale.size
            if (p[SettingsKeys.SAVED_SERVERS_V2] == null) {
                val legacy = SettingsStore.decodeLegacyServers(p[SettingsKeys.SAVED_SERVERS_LEGACY])
                if (legacy.isNotEmpty()) {
                    p[SettingsKeys.SAVED_SERVERS_V2] = SettingsStore.encodeServers(legacy)
                    converted = legacy.size
                }
            }
        }
        val keys = settings.data.first().asMap().size
        val prefsPresent = servicePrefs.getString(ServicePrefs.KEY_TALK_WORD, ABSENT) != ABSENT
        val model = File(filesDir, VoskModelFiles.extractDirName)
        val modelKept = !VoskModelFiles.shouldExtract(model, VoskModelFiles.stamp)
        marker.edit { putString(KEY_VERSION, VERSION.toString()) }
        val report = Report(true, keys, purged, converted, prefsPresent, modelKept)
        log(
            "$LOG_MARKER kept $keys settings keys, purged $purged ws_resume_checkpoint keys, converted $converted saved servers, " +
                "assistant_service_prefs ${if (prefsPresent) "kept" else "absent"}, vosk-model ${if (modelKept) "kept (stamp ok, no re-extract)" else "absent or stale (extracts on first wake)"}",
        )
        return report
    }

    companion object {
        const val VERSION = 1
        const val KEY_VERSION = "legacy_migration_version"
        const val MARKER_FILE = "lite_migration"
        const val LOG_MARKER = "[LegacyMigration]"

        private const val ABSENT = "\u0000absent"
    }
}
