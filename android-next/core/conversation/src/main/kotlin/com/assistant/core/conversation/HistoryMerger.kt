package com.assistant.core.conversation

import com.assistant.core.protocol.ContentBlockDto
import com.assistant.core.protocol.MessagePreviewDto
import com.assistant.core.protocol.PaginatedMessagesDto
import kotlinx.collections.immutable.PersistentList

/**
 * REST history ↔ live timeline (spec 12 §5). History pages go through the same helpers as live
 * frames, so both produce the same structures (§5.1). There is no join key between REST and live
 * events (G-10); duplicates are prevented by the reducer's idempotence rules (§5.4).
 *
 * Ids: entries/blocks built from a page get ids derived from the absolute REST index, so the same
 * page always yields the same keys. On `REPLACE`, content the view already holds keeps its live
 * ids (greedy in-order match on content), so a canonical reload does not re-key what is on screen
 * (spec 14 §3.1, A-4.4.2). `PREPEND` and `RECONCILE` never change an existing id.
 */
object HistoryMerger {

    /** §5.1 `applyHistoryPage`. */
    internal fun applyPage(d: Draft, mode: PageMode, resp: PaginatedMessagesDto) {
        if (mode == PageMode.RECONCILE) { reconcile(d, resp); return }
        val previous = if (mode == PageMode.REPLACE) d.entries.build() else null
        if (mode == PageMode.REPLACE) resetContent(d)

        // Prepend converts into a scratch list; live bookkeeping must not leak into it.
        val savedSplit = d.pendingSplit
        val savedVoiceUser = d.openVoiceUserId
        val savedPrompt = d.promptSinceTurnEnd
        d.pendingSplit = null
        d.openVoiceUserId = null
        val keptList = d.entries
        d.kept = keptList
        d.entries = kotlinx.collections.immutable.persistentListOf<Entry>().builder()
        val ids = HistoryIdSource(d.ref.sdkId ?: d.ref.localId)
        d.historyIds = ids
        resp.messages.forEachIndexed { i, p ->
            ids.absIndex = resp.startIndex + i
            convertPreview(d, p, ids)
        }
        d.historyIds = null
        val page = d.entries
        d.entries = keptList
        d.kept = null
        if (mode == PageMode.PREPEND) {
            d.pendingSplit = savedSplit
            d.openVoiceUserId = savedVoiceUser
            d.promptSinceTurnEnd = savedPrompt
        }

        // TC-2: a history tool without a result is never "done"; only the tail of a running turn may still run.
        val lastOfPage = page.lastOrNull()
        for (i in page.indices) {
            val e = page[i] as? AssistantEntry ?: continue
            val nb = e.blocks.builder()
            var changed = false
            for (j in nb.indices) {
                val tb = nb[j] as? ToolBlock ?: continue
                if (tb.status != ToolStatus.RUNNING) continue
                val mayStillRun = mode == PageMode.REPLACE && d.inTurn && lastOfPage === e
                if (!mayStillRun) { nb[j] = tb.copy(status = ToolStatus.NO_RESULT); changed = true }
            }
            if (changed) page[i] = e.copy(blocks = nb.build())
        }

        if (mode == PageMode.REPLACE) {
            if (previous != null) carryOverLiveIds(previous, page)
            d.entries.clear()
            d.entries.addAll(page)
            d.promptSinceTurnEnd = false
        } else {
            // merge a run split by the page boundary (H-5); the newer run keeps its id
            if (page.isNotEmpty() && d.entries.isNotEmpty()) {
                val older = page.last()
                val newer = d.entries[0]
                if (older is AssistantEntry && newer is AssistantEntry) {
                    d.entries[0] = newer.copy(blocks = older.blocks.addAll(newer.blocks))
                    page.removeAt(page.lastIndex)
                }
            }
            d.entries.addAll(0, page)
            d.speechAnchor = d.speechAnchor?.let { it + page.size }
        }
        d.rebuildIndexes()
        d.history = HistoryState(loaded = true, startIndex = resp.startIndex, totalCount = resp.totalCount, hasMore = resp.hasMore)
    }

    private fun resetContent(d: Draft) {
        d.entries.clear(); d.tools.clear(); d.perms.clear(); d.orphans.clear(); d.unattributed.clear()
        d.pendingSplit = null; d.openVoiceUserId = null; d.speechAnchor = null
    }

    private fun convertPreview(d: Draft, p: MessagePreviewDto, ids: HistoryIdSource) {
        val blocks = p.blocks
        if (p.role == "user") {
            for (b in blocks) if (b.type == "tool_result") deliver(d, b)
            if (blocks.isNotEmpty() && blocks.all { it.type == "tool_result" }) return   // protocol wrapper, not a turn
            val text = p.text
            if (text.isEmpty()) return                                                  // e.g. image-only line
            d.appendEntry(classifyUserLine(text).toEntry(ids.entryId()))
            return
        }
        if (blocks.isEmpty()) {                                                         // empty = Claude thinking placeholder
            if (p.text.isNotEmpty()) { ids.blockIndex = 0; pushHistory(d, thinking = false, text = p.text) }
            return
        }
        blocks.forEachIndexed { bi, b ->
            ids.blockIndex = bi
            when (b.type) {
                "text" -> if (!b.text.isNullOrEmpty()) pushHistory(d, thinking = false, text = b.text!!)
                "thinking" -> if (!b.text.isNullOrEmpty()) pushHistory(d, thinking = true, text = b.text!!)   // backend O-2
                "tool_use" -> historyToolUse(d, b)
                "tool_result" -> deliver(d, b)
            }
        }
    }

    private fun deliver(d: Draft, b: ContentBlockDto) {
        d.deliverResult(b.toolUseId ?: "", PendingResult(normalizeOutput(b.output), b.isError, ResultOrigin.HISTORY))
    }

    private fun pushHistory(d: Draft, thinking: Boolean, text: String) {
        d.pushBlock(streamedBlock(thinking, d.newBlockId(), text, streaming = false, scope = BlockScope.TURN, origin = BlockOrigin.HISTORY))
    }

    private fun historyToolUse(d: Draft, b: ContentBlockDto) {
        val id = b.toolUseId
        if (!id.isNullOrEmpty() && d.tools.containsKey(id)) return                      // I-11
        val tb = ToolBlock(
            id = d.newBlockId(),
            toolUseId = id ?: "",
            toolName = b.toolName ?: "",
            toolInput = b.toolInput ?: Draft.EMPTY_OBJECT,
            origin = BlockOrigin.HISTORY,
        )
        val entryId = d.pushBlock(tb)
        if (!id.isNullOrEmpty()) {
            d.tools[id] = entryId
            d.orphans.remove(id)?.let { r -> d.updateTool(id) { d.applyResult(it, r, inferred = false) } }
        }
    }

    /** §5.5: fills tool results only; never adds, removes or reorders entries. */
    private fun reconcile(d: Draft, resp: PaginatedMessagesDto) {
        for (p in resp.messages) for (b in p.blocks) {
            if (b.type != "tool_result" || b.toolUseId.isNullOrEmpty()) continue
            val id = b.toolUseId!!
            if (d.tool(id) == null) continue
            val r = PendingResult(normalizeOutput(b.output), b.isError, ResultOrigin.RECONCILE)
            d.updateTool(id) { d.applyResult(it, r, inferred = false) }
        }
        d.unattributed.clear()                                                         // superseded by REST
    }

    // ───────────────────────── user-line classification ─────────────────────────

    /** Result of [classifyUserLine]: a user entry (with its stripped text and origin) or a notice. */
    sealed interface UserLine {
        fun toEntry(id: String): Entry

        data class User(val text: String, val origin: UserOrigin) : UserLine {
            override fun toEntry(id: String) = UserEntry(id, text, origin, UserState.SENT)
        }

        data class Notice(val kind: NoticeKind, val text: String) : UserLine {
            override fun toEntry(id: String) = NoticeEntry(id, kind, text)
        }
    }

    private val VOICE_RECORDING = Regex("^\\[voice, recording: [^\\]]*\\] ?")
    private val AUDIO = Regex("^\\[audio:[A-Za-z0-9]+\\] ?")
    private val COMMAND = Regex("^<(command-name|command-message|command-args|local-command-stdout|local-command-stderr|local-command-caveat)>")

    /** §5.1 `classifyUserLine`: strips the backend's / CLI's text prefixes (G-11, §5.7). */
    fun classifyUserLine(text: String): UserLine {
        if (text.startsWith("[voice] ")) return UserLine.User(text.substring(8), UserOrigin.VOICE)
        VOICE_RECORDING.find(text)?.let { return UserLine.User(text.substring(it.value.length), UserOrigin.VOICE) }
        AUDIO.find(text)?.let { return UserLine.User(text.substring(it.value.length), UserOrigin.AUDIO) }
        if (text.startsWith("[shared file] ") || text.startsWith("[shared text]")) return UserLine.User(text, UserOrigin.INJECT)
        if (text.startsWith("[Request interrupted by user")) return UserLine.Notice(NoticeKind.INTERRUPTED, "")
        if (text.startsWith("This session is being continued from a previous conversation")) {
            return UserLine.Notice(NoticeKind.COMPACTION, text)
        }
        if (text.startsWith("<task-notification>")) return UserLine.Notice(NoticeKind.BACKGROUND, text)
        if (COMMAND.containsMatchIn(text)) return UserLine.Notice(NoticeKind.COMMAND, text)
        return UserLine.User(text, UserOrigin.HISTORY)
    }

    // ───────────────────────── live-id carry-over on replace ─────────────────────────

    private const val LOOKAHEAD = 64

    /**
     * Gives the new page entries the ids of matching **live** entries of the previous timeline, in
     * order. History-derived ids are deterministic already, so only live (`e…`/`b…`) ids are carried.
     */
    private fun carryOverLiveIds(previous: PersistentList<Entry>, page: MutableList<Entry>) {
        var from = 0
        for (ni in page.indices) {
            val ne = page[ni]
            val key = contentKey(ne) ?: continue
            val end = minOf(previous.size, from + LOOKAHEAD)
            var match = -1
            for (oi in from until end) if (contentKey(previous[oi]) == key) { match = oi; break }
            if (match < 0) continue
            from = match + 1
            val oe = previous[match]
            var updated: Entry = ne
            if (isLiveId(oe.id)) updated = withId(updated, oe.id)
            if (updated is AssistantEntry && oe is AssistantEntry) updated = carryBlocks(oe, updated)
            page[ni] = updated
        }
    }

    private fun carryBlocks(old: AssistantEntry, new: AssistantEntry): AssistantEntry {
        val nb = new.blocks.builder()
        var from = 0
        for (j in nb.indices) {
            val key = blockKey(nb[j])
            var match = -1
            for (k in from until old.blocks.size) if (blockKey(old.blocks[k]) == key) { match = k; break }
            if (match < 0) continue
            from = match + 1
            val oldId = old.blocks[match].id
            if (isLiveId(oldId)) nb[j] = withBlockId(nb[j], oldId)
        }
        return new.copy(blocks = nb.build())
    }

    private fun isLiveId(id: String) = !id.startsWith("h:")

    private fun contentKey(e: Entry): String? = when (e) {
        is UserEntry -> "u|${e.text}"
        is NoticeEntry -> "n|${e.notice}|${e.text}"
        is AssistantEntry -> e.blocks.firstOrNull()?.let { "a|" + blockKey(it) }
    }

    private fun blockKey(b: Block): String = when (b) {
        is TextBlock -> "t|${b.text}"
        is ThinkingBlock -> "k|${b.text}"
        is ToolBlock -> if (b.toolUseId.isNotEmpty()) "x|${b.toolUseId}" else "x|${b.toolName}|${b.toolInput}"
        is PermissionBlock -> "p|${b.requestId}"
    }

    private fun withId(e: Entry, id: String): Entry = when (e) {
        is UserEntry -> e.copy(id = id)
        is NoticeEntry -> e.copy(id = id)
        is AssistantEntry -> e.copy(id = id)
    }

    private fun withBlockId(b: Block, id: String): Block = when (b) {
        is TextBlock -> b.copy(id = id)
        is ThinkingBlock -> b.copy(id = id)
        is ToolBlock -> b.copy(id = id)
        is PermissionBlock -> b.copy(id = id)
    }
}
