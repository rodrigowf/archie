package com.assistant.core.settings

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `resume_checkpoints`: LRU 32, debounced ≥500 ms, flush now, checkpoint+snapshot together (T-10). */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckpointStoreTest {
    private fun cp(i: Int) = StoredCheckpoint("stream-$i", i.toLong(), "snapshot-$i")

    @Test fun boundedAt32_leastRecentlyUsedEvicted() = runTest {
        val ds = MemoryDataStore()
        val store = CheckpointStore(ds, backgroundScope)
        for (i in 0 until 40) store.put("L$i", cp(i))
        assertEquals(32, store.size)
        assertNull(store.get("L0")); assertNull(store.get("L7"))
        assertEquals(cp(8), store.get("L8"))                       // get() touches: L8 is now most recent
        store.put("L40", cp(40))
        assertNull(store.get("L9"))
        assertEquals(cp(8), store.get("L8"))
        store.flush()
        assertEquals(64, ds.current.asMap().size)                  // 32 × (cp + snapshot), never more
    }

    @Test fun writesAreDebouncedTo500msAndFlushIsImmediate() = runTest {
        val ds = MemoryDataStore()
        val store = CheckpointStore(ds, backgroundScope)
        runCurrent()
        repeat(200) { store.put("L", cp(it)) }                    // a streaming burst
        advanceTimeBy(499); runCurrent()
        assertEquals(0, ds.writes)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, ds.writes)
        store.put("L", cp(500))
        store.flush()
        assertEquals(2, ds.writes)
        advanceUntilIdle()
        assertEquals("debounced write cancelled by the flush", 2, ds.writes)
    }

    @Test fun survivesRestartInLruOrder_andDiscardsRowsWithoutSnapshot() = runTest {
        val ds = MemoryDataStore()
        val a = CheckpointStore(ds, backgroundScope)
        a.put("A", cp(1)); a.put("B", cp(2)); a.put("C", cp(3))
        a.get("A")                                                  // A most recent
        a.flush()
        val b = CheckpointStore(ds, backgroundScope)
        assertEquals(cp(2), b.get("B"))
        assertEquals(listOf("C", "A", "B"), b.keys())

        val orphan = MemoryDataStore(preferencesOf(stringPreferencesKey("cp:X") to "0|5|s"))
        assertNull(CheckpointStore(orphan, backgroundScope).get("X"))
        b.remove("B"); b.flush()
        assertNull(CheckpointStore(ds, backgroundScope).get("B"))
    }
}
