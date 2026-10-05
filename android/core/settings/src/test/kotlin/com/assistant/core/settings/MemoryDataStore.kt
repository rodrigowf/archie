package com.assistant.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory DataStore that counts writes (virtual-time friendly). */
class MemoryDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()
    var writes = 0
        private set
    override val data: Flow<Preferences> = state
    val current: Preferences get() = state.value

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = mutex.withLock {
        val next = transform(state.value)
        writes++
        state.value = next
        next
    }
}
