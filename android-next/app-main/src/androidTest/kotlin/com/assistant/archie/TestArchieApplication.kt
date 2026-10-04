package com.assistant.archie

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.assistant.archie.graph.ArchieApplication
import com.assistant.archie.graph.MainAppGraph
import com.assistant.archie.system.MainVoiceHost
import com.assistant.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The production graph and voice host (`MainVoiceHost`), but with in-memory device settings that
 * point at [TestBackend] and start with the wake word OFF, so nothing listens or dials out unless a
 * test turns it on.
 */
class TestArchieApplication : ArchieApplication() {
    override fun createGraph(): MainAppGraph {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefs = mutablePreferencesOf().apply {
            this[stringPreferencesKey("server_url")] = TestBackend.url
            this[booleanPreferencesKey("auto_connect")] = true
            this[booleanPreferencesKey("enable_wake_word")] = false
            this[stringPreferencesKey("theme_mode")] = "DARK"
        }
        return MainAppGraph(
            this,
            settings = SettingsStore(MemoryDataStore(prefs), scope),
            scanner = { emptyList() },
            scope = scope,
            voiceHostFactory = MainVoiceHost::create,
        )
    }
}

/** In-memory DataStore for SettingsStore. */
class MemoryDataStore(initial: Preferences) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = mutex.withLock {
        transform(state.value).also { state.value = it }
    }
}
