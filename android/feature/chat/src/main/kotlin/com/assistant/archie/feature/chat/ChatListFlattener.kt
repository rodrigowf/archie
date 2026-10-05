package com.assistant.archie.feature.chat

import com.assistant.archie.feature.chat.model.ChatItem
import com.assistant.archie.feature.chat.model.ChatItem.Gap
import com.assistant.archie.feature.chat.model.ChatItem.GroupPosition
import com.assistant.archie.feature.chat.model.ToolDescriptor
import com.assistant.archie.feature.chat.model.ToolStatusUi
import com.assistant.core.conversation.AssistantEntry
import com.assistant.core.conversation.BlockOrigin
import com.assistant.core.conversation.BlockScope
import com.assistant.core.conversation.ConversationState
import com.assistant.core.conversation.NoticeEntry
import com.assistant.core.conversation.NoticeKind
import com.assistant.core.conversation.PermissionBlock
import com.assistant.core.conversation.StreamedBlock
import com.assistant.core.conversation.TextBlock
import com.assistant.core.conversation.ThinkingBlock
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.ToolStatus
import com.assistant.core.conversation.UserEntry
import com.assistant.core.conversation.UserOrigin
import com.assistant.core.markdown.MdSnapshot
import com.assistant.core.model.SessionKind

/** UI-local choices that change the list (kept by the ViewModel; spec 14 §3.1 "a user toggle wins"). */
data class FlattenOptions(
    /** Group key (`g:…`) → expanded, set by the user. Wins over the automatic state. */
    val groupToggles: Map<String, Boolean> = emptyMap(),
    /** Card key (`t:…`) → expanded, set by the user. Wins over the automatic state. */
    val cardToggles: Map<String, Boolean> = emptyMap(),
    val loadingOlder: Boolean = false,
    /** Fold runs of ≥ 2 consecutive tool calls into "N steps" groups (IA §9.3). */
    val grouping: Boolean = true,
)

class FlattenResult(
    /** In timeline order, top to bottom. */
    val items: List<ChatItem>,
    /** Some block is still streaming (the ViewModel samples the list at 33 ms while true). */
    val streaming: Boolean,
)

/**
 * Turns [ConversationState.entries] into `LazyColumn` items (spec 14 §3.1). Pure: the same state and
 * options always give the same items, in **exactly the reducer's order** (R4: entries in order, blocks
 * in arrival order inside a run; nothing is regrouped by type). Grouping only *hides* member cards
 * behind their header; it never moves them.
 *
 * [describe] gives the tool's tile/name/summary (the [com.assistant.archie.feature.chat.ui.ToolCardRenderer]
 * slot); [markdown] gives each text block's nodes ([MarkdownStore]).
 */
class ChatListFlattener(
    private val describe: (ToolBlock, SessionKind) -> ToolDescriptor,
    private val markdown: (blockId: String, text: String, streaming: Boolean) -> MdSnapshot,
) {
    fun flatten(state: ConversationState, options: FlattenOptions = FlattenOptions()): FlattenResult {
        val out = ArrayList<ChatItem>(state.entries.size * 3)
        var streaming = false
        fun gap(g: Gap) = if (out.isEmpty()) Gap.None else g

        if (state.history.hasMore) out += ChatItem.LoadOlder(options.loadingOlder)
        if (state.kind == SessionKind.ORCHESTRATOR && state.entries.any { it.fromHistory() }) out += ChatItem.HistoryFootnote

        val lastIndex = state.entries.lastIndex
        val turnBusy = state.inTurn || state.status.busy
        state.entries.forEachIndexed { ei, e ->
            when (e) {
                is UserEntry -> {
                    if (e.streaming) streaming = true
                    out += ChatItem.UserBubble(
                        entryId = e.id,
                        text = e.text,
                        origin = e.origin,
                        pending = e.state == com.assistant.core.conversation.UserState.PENDING,
                        live = e.streaming,
                        gap = gap(Gap.Item),
                    )
                }
                is NoticeEntry -> out += if (e.notice == NoticeKind.COMPACTION) {
                    ChatItem.CompactDivider(e.id, e.text, e.data["tokens_before"], e.data["tokens_after"], gap(Gap.Item))
                } else {
                    ChatItem.Notice(e.id, e.notice, e.text, gap(Gap.Item))
                }
                is AssistantEntry -> {
                    val blocks = e.blocks
                    val tailLive = ei == lastIndex && (
                        turnBusy || blocks.any { (it as? StreamedBlock)?.streaming == true } ||
                            blocks.any { it is ToolBlock && it.status == ToolStatus.RUNNING && it.scope == BlockScope.VOICE && state.voiceActive }
                        )
                    var i = 0
                    while (i < blocks.size) {
                        when (val b = blocks[i]) {
                            is ToolBlock -> {
                                var j = i
                                while (j < blocks.size && blocks[j] is ToolBlock) j++
                                @Suppress("UNCHECKED_CAST")
                                val run = blocks.subList(i, j) as List<ToolBlock>
                                val liveRun = tailLive && j == blocks.size
                                addRun(out, e.id, run, liveRun, state, options, ::gap)
                                i = j
                                continue
                            }
                            is TextBlock -> {
                                if (b.streaming) streaming = true
                                if (b.text.isNotEmpty()) addText(out, e.id, b, ::gap)
                            }
                            is ThinkingBlock -> {
                                if (b.streaming) streaming = true
                                if (b.text.isNotBlank() || b.streaming) {
                                    out += ChatItem.Thinking(e.id, b.id, b.text, b.streaming, gap(Gap.Item))
                                }
                            }
                            is PermissionBlock -> out += ChatItem.Permission(e.id, b, gap(Gap.Item))
                        }
                        i++
                    }
                }
            }
        }

        val orphans = state.reachableOrphans()
        if (orphans.isNotEmpty() || state.unattributed.isNotEmpty()) {
            out += ChatItem.UnmatchedResults(
                results = orphans.values.toList() + state.unattributed,
                ids = orphans.keys.toList() + state.unattributed.map { null },
            )
        }
        return FlattenResult(out, streaming)
    }

    private fun addText(out: MutableList<ChatItem>, entryId: String, b: TextBlock, gap: (Gap) -> Gap) {
        val snap = markdown(b.id, b.text, b.streaming)
        for (k in 0 until snap.size) {
            out += ChatItem.MdBlock(
                entryId = entryId,
                blockId = b.id,
                mdIndex = k,
                node = snap[k],
                previous = if (k > 0) snap[k - 1] else null,
                tail = snap.isTail(k),
                gap = if (k == 0) gap(Gap.Item) else Gap.Tight,
            )
        }
    }

    private fun addRun(
        out: MutableList<ChatItem>,
        entryId: String,
        run: List<ToolBlock>,
        liveRun: Boolean,
        state: ConversationState,
        options: FlattenOptions,
        gap: (Gap) -> Gap,
    ) {
        val descriptors = run.map { describe(it, state.kind) }
        fun cardExpanded(b: ToolBlock, d: ToolDescriptor): Boolean =
            options.cardToggles[ChatItem.toolKey(entryId, b)] ?: (d.defaultOpen || liveRun || b.executing)

        if (run.size < 2 || !options.grouping) {
            run.forEachIndexed { k, b ->
                out += ChatItem.ToolCard(entryId, b, descriptors[k], GroupPosition.Solo, cardExpanded(b, descriptors[k]), gap(Gap.Item))
            }
            return
        }
        val firstKey = ChatItem.toolKey(entryId, run[0])
        val groupKey = "g:$firstKey"
        val auto = liveRun || run.any { it.executing }
        val expanded = options.groupToggles[groupKey] ?: auto
        val status = groupStatus(run)
        out += ChatItem.ToolGroup(
            entryId = entryId,
            firstToolKey = firstKey,
            count = run.size,
            tiles = descriptors,
            summary = if (status == ToolStatusUi.Running && expanded) "running" else summarize(descriptors, run),
            status = status,
            expanded = expanded,
            gap = gap(Gap.Item),
        )
        if (!expanded) return
        run.forEachIndexed { k, b ->
            val pos = when (k) {
                0 -> GroupPosition.First
                run.lastIndex -> GroupPosition.Last
                else -> GroupPosition.Middle
            }
            out += ChatItem.ToolCard(entryId, b, descriptors[k], pos, cardExpanded(b, descriptors[k]), if (k == 0) Gap.Group else Gap.Tight)
        }
    }

    companion object {
        fun status(b: ToolBlock): ToolStatusUi = when (b.status) {
            ToolStatus.RUNNING -> ToolStatusUi.Running
            ToolStatus.DONE -> ToolStatusUi.Done
            ToolStatus.ERROR -> ToolStatusUi.Error
            ToolStatus.NO_RESULT -> ToolStatusUi.Waiting
        }

        fun groupStatus(run: List<ToolBlock>): ToolStatusUi = when {
            run.any { it.status == ToolStatus.RUNNING } -> ToolStatusUi.Running
            run.any { it.status == ToolStatus.ERROR } -> ToolStatusUi.Error
            run.any { it.status == ToolStatus.NO_RESULT } -> ToolStatusUi.Waiting
            else -> ToolStatusUi.Done
        }

        /** "Read, Edit ×3, Bash" in order of first use, plus " · 1 failed" (IA §9.3). */
        fun summarize(descriptors: List<ToolDescriptor>, run: List<ToolBlock>): String {
            val counts = LinkedHashMap<String, Int>()
            descriptors.forEach { counts[it.name] = (counts[it.name] ?: 0) + 1 }
            val names = counts.entries.joinToString(", ") { (n, c) -> if (c > 1) "$n ×$c" else n }
            val failed = run.count { it.status == ToolStatus.ERROR }
            return if (failed > 0) "$names · $failed failed" else names
        }

        private fun com.assistant.core.conversation.Entry.fromHistory(): Boolean = when (this) {
            is UserEntry -> origin == UserOrigin.HISTORY
            is AssistantEntry -> blocks.any { it.origin == BlockOrigin.HISTORY }
            else -> false
        }
    }
}
