package com.assistant.peripheral

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.assistant.core.settings.ServicePrefs
import com.assistant.core.settings.SettingsStore
import com.assistant.core.settings.SharedPreferencesKeyValue
import com.assistant.core.voicehost.runtime.VoiceHostRuntime
import com.assistant.core.voicehost.service.VoiceHostOwner
import com.assistant.core.voicehost.service.VoiceHostService
import com.assistant.peripheral.migration.LegacyMigration
import kotlinx.coroutines.launch

/**
 * Process entry (spec 14 §5.5). Builds the graph and ALWAYS starts [VoiceHostService] (API 21 has
 * no background-start limits), so any process start — companion watchdog launch, accessibility
 * rebind, sticky restart — re-arms the host. The FGS is the "long-running service in package" the
 * companion's watchdog looks for (inv04 §6.4 contract 3).
 */
class LiteApplication : Application(), VoiceHostOwner {
    lateinit var graph: LiteGraph
        private set

    override val voiceHostRuntime: VoiceHostRuntime get() = graph.voiceHost

    override fun onCreate() {
        super.onCreate()
        graph = LiteGraph(this)
        graph.scope.launch {
            try {
                LegacyMigration(
                    settings = SettingsStore.dataStore(this@LiteApplication),
                    servicePrefs = SharedPreferencesKeyValue(getSharedPreferences(ServicePrefs.FILE_NAME, Context.MODE_PRIVATE)),
                    marker = SharedPreferencesKeyValue(getSharedPreferences(LegacyMigration.MARKER_FILE, Context.MODE_PRIVATE)),
                    filesDir = filesDir,
                    log = { Log.i(TAG, it) },
                ).run()
            } catch (e: Exception) {
                Log.e(TAG, "${LegacyMigration.LOG_MARKER} failed: ${e.message}", e)
            }
        }
        graph.start()
        if (!VoiceHostService.start(this, fromForeground = true)) Log.w(TAG, "VoiceHostService start refused")
        Log.i(TAG, "Archie lite ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) started")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) graph.trimMemory()
    }

    private companion object {
        const val TAG = "ArchieLite"
    }
}
