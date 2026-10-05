package com.assistant.archie.feature.chat

import com.assistant.core.markdown.MarkdownCache
import com.assistant.core.markdown.MarkdownDocument
import com.assistant.core.markdown.MdSnapshot

/**
 * Markdown per text block (spec 14 §3.2): a streaming block keeps one [MarkdownDocument] and feeds it
 * only the appended characters, so each update costs O(tail). A finished block is parsed once and
 * cached ([MarkdownCache], keyed by block id + text). The reducer may *replace* a block's text
 * (`*_complete`, a continuation prefix removed, I-5); then the document is rebuilt from scratch.
 *
 * Not thread-safe: one writer (the flattener's confined dispatcher).
 */
class MarkdownStore(private val cache: MarkdownCache = MarkdownCache.Shared) {
    private class Live(val doc: MarkdownDocument, var text: String, var snapshot: MdSnapshot)

    private val live = HashMap<String, Live>()
    private var seen = HashSet<String>()

    /** Finished snapshots by block id, so a frozen block keeps its node instances (no recomposition). */
    private val finished = object : LinkedHashMap<String, Pair<String, MdSnapshot>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, MdSnapshot>>?) = size > FINISHED_CAPACITY
    }

    /** Test hook: number of times a document was (re)built from the start. */
    var rebuilds: Int = 0
        private set

    fun snapshot(blockId: String, text: String, streaming: Boolean): MdSnapshot {
        seen.add(blockId)
        val l = live[blockId]
        if (!streaming) {
            finished[blockId]?.let { (t, snap) -> if (t == text) return snap }
            val snap = if (l != null && l.text == text) {
                // Same text: freeze the live document so committed nodes keep their identity.
                l.doc.finish()
            } else {
                MdSnapshot.of(cache.getBlocking(blockId, text))
            }
            live.remove(blockId)
            finished[blockId] = text to snap
            return snap
        }
        if (l != null && text.length >= l.text.length && text.startsWith(l.text)) {
            if (text.length > l.text.length) {
                l.snapshot = l.doc.append(text.substring(l.text.length))
                l.text = text
            }
            return l.snapshot
        }
        rebuilds++
        val doc = MarkdownDocument()
        val snap = doc.append(text)
        live[blockId] = Live(doc, text, snap)
        return snap
    }

    /** Drops documents of blocks that were not asked for since the previous [sweep]. */
    fun sweep() {
        live.keys.retainAll(seen)
        seen = HashSet()
    }

    private companion object {
        const val FINISHED_CAPACITY = 400
    }
}
