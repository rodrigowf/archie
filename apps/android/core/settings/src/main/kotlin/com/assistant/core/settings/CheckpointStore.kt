package com.assistant.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A resume checkpoint persisted **together with** the full entries snapshot it belongs to
 * (spec 12 T-10: restoring both is equivalent to "in memory"; a checkpoint without its snapshot
 * must never be sent as `resume_from`). [snapshot] is opaque here (serialized by `:core:data`).
 */
data class StoredCheckpoint(val streamId: String, val seq: Long, val snapshot: String)

/**
 * Separate DataStore file `resume_checkpoints` (spec 14 §1.2), replacing the old per-token
 * `ws_resume_checkpoint:<id>` keys in `settings` that grew forever (inv03 §2.2):
 * - LRU-bounded to [capacity] (32) sessions;
 * - writes debounced to ≥ [debounceMs] (500 ms); [flush] writes now (call it on `turn_complete`
 *   and `onStop`). Intended use is the Activity/process `onStop` snapshot, not per event (A-8.14).
 */
class CheckpointStore(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    val capacity: Int = CAPACITY,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    private val lock = Any()
    private val lru = LinkedHashMap<String, StoredCheckpoint>(capacity + 1, 0.75f, true)
    private val loaded = CompletableDeferred<Unit>()
    private val writeMutex = Mutex()
    private var pendingWrite: Job? = null

    init {
        scope.launch {
            val prefs = dataStore.data.first()
            val restored = decode(prefs)
            synchronized(lock) {
                // Entries put before the load finished win; restored ones fill in, oldest first.
                val fresh = LinkedHashMap(lru)
                lru.clear()
                restored.forEach { (k, v) -> if (k !in fresh) lru[k] = v }
                fresh.forEach { (k, v) -> lru[k] = v }
                trimLocked()
            }
            loaded.complete(Unit)
        }
    }

    suspend fun get(localId: String): StoredCheckpoint? {
        loaded.await()
        return synchronized(lock) { lru[localId] }
    }

    val size: Int get() = synchronized(lock) { lru.size }

    /** Most-recently-used last. */
    fun keys(): List<String> = synchronized(lock) { lru.keys.toList() }

    fun put(localId: String, checkpoint: StoredCheckpoint) {
        if (localId.isBlank()) return
        synchronized(lock) {
            lru.remove(localId)
            lru[localId] = checkpoint
            trimLocked()
        }
        scheduleWrite()
    }

    /** Pruned when the view closes (spec 12 §10.2 A-2.2). */
    fun remove(localId: String) {
        val removed = synchronized(lock) { lru.remove(localId) != null }
        if (removed) scheduleWrite()
    }

    /** Write immediately (cancels the pending debounce). */
    suspend fun flush() {
        synchronized(lock) { pendingWrite?.cancel(); pendingWrite = null }
        write()
    }

    private fun trimLocked() {
        while (lru.size > capacity) lru.remove(lru.keys.first())
    }

    private fun scheduleWrite() = synchronized(lock) {
        if (pendingWrite?.isActive == true) return
        pendingWrite = scope.launch {
            delay(debounceMs)
            synchronized(lock) { pendingWrite = null }
            write()
        }
    }

    private suspend fun write() {
        loaded.await()
        writeMutex.withLock {
            val snapshot = synchronized(lock) { lru.entries.map { it.key to it.value } }
            dataStore.edit { p ->
                p.asMap().keys.filter { it.name.startsWith(CP) || it.name.startsWith(SNAP) }.forEach { p.remove(it) }
                snapshot.forEachIndexed { order, (id, cp) ->
                    p[stringPreferencesKey(CP + id)] = "$order|${cp.seq}|${cp.streamId}"
                    p[stringPreferencesKey(SNAP + id)] = cp.snapshot
                }
            }
        }
    }

    companion object {
        const val FILE_NAME = "resume_checkpoints"
        const val CAPACITY = 32
        const val DEBOUNCE_MS = 500L
        private const val CP = "cp:"
        private const val SNAP = "snap:"

        @Volatile private var processStore: DataStore<Preferences>? = null

        fun dataStore(context: Context): DataStore<Preferences> = processStore ?: synchronized(this) {
            processStore ?: PreferenceDataStoreFactory.create(
                produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
            ).also { processStore = it }
        }

        /** Restored entries in LRU order (oldest first). Rows without a snapshot are discarded (T-10). */
        internal fun decode(p: Preferences): List<Pair<String, StoredCheckpoint>> {
            val map = p.asMap()
            return map.keys.filter { it.name.startsWith(CP) }.mapNotNull { key ->
                val id = key.name.removePrefix(CP)
                val parts = (map[key] as? String)?.split('|', limit = 3) ?: return@mapNotNull null
                val order = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val seq = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                val stream = parts.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val snap = map[stringPreferencesKey(SNAP + id)] as? String ?: return@mapNotNull null
                Triple(order, id, StoredCheckpoint(stream, seq, snap))
            }.sortedBy { it.first }.map { it.second to it.third }
        }
    }
}
