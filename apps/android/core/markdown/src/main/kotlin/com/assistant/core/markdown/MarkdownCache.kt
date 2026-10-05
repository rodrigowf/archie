package com.assistant.core.markdown

import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Parsed history messages and memory documents (spec 14 §3.2 step 4): an LRU of [capacity]
 * entries keyed by the caller's id (the `blockId`, or a memory path) + text length + hash, so an
 * edited text never returns a stale parse. Parsing runs on [Dispatchers.Default].
 */
class MarkdownCache(private val capacity: Int = DEFAULT_CAPACITY) {
    private data class Key(val id: String, val length: Int, val hash: Int)

    private val map = object : LinkedHashMap<Key, ImmutableList<MdNode>>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ImmutableList<MdNode>>?): Boolean =
            size > capacity
    }

    private fun key(id: String, text: String) = Key(id, text.length, text.hashCode())

    /** The cached nodes, or null (no parsing). */
    fun peek(id: String, text: String): ImmutableList<MdNode>? = synchronized(map) { map[key(id, text)] }

    /** Cached or parsed now, on the calling thread. */
    fun getBlocking(id: String, text: String): ImmutableList<MdNode> {
        val k = key(id, text)
        synchronized(map) { map[k] }?.let { return it }
        val nodes = MarkdownDocument.parse(text)
        synchronized(map) { map[k] = nodes }
        return nodes
    }

    /** Cached, or parsed off the main thread. */
    suspend fun get(id: String, text: String): ImmutableList<MdNode> =
        peek(id, text) ?: withContext(Dispatchers.Default) { getBlocking(id, text) }

    val size: Int get() = synchronized(map) { map.size }

    fun clear() = synchronized(map) { map.clear() }

    companion object {
        const val DEFAULT_CAPACITY: Int = 200

        /** The process-wide cache used by [com.assistant.core.markdown.ui.rememberMarkdown]. */
        val Shared: MarkdownCache = MarkdownCache()
    }
}
