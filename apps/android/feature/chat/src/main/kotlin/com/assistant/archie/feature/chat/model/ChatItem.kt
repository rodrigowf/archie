package com.assistant.archie.feature.chat.model

import androidx.compose.runtime.Immutable
import com.assistant.core.conversation.NoticeKind
import com.assistant.core.conversation.PendingResult
import com.assistant.core.conversation.PermissionBlock
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.UserOrigin
import com.assistant.core.markdown.MdNode

/**
 * One `LazyColumn` item of the conversation (spec 14 §3.1). An assistant run becomes several items,
 * one per renderable unit, so a streaming delta invalidates exactly one item. [key] is built only from
 * spec-12 ids (entry/block ids, `tool_use_id`), so it is identical whether the content arrived live or
 * from a REST page, and [contentType] lets the list recycle compositions across items of one kind.
 *
 * [entryId] is the timeline entry the item belongs to (long-press actions: copy, rewind, fork).
 */
@Immutable
sealed interface ChatItem {
    val key: String
    val contentType: String
    val entryId: String?

    /** Vertical gap above this item, in the timeline's natural (top-to-bottom) order. */
    val gap: Gap

    enum class Gap { None, Tight, Group, Item }

    /** "Load older" at the top of the loaded range (§5.3). */
    data class LoadOlder(val loading: Boolean) : ChatItem {
        override val key get() = "load-older"
        override val contentType get() = "load-older"
        override val entryId: String? get() = null
        override val gap get() = Gap.None
    }

    /** Spec 12 §5.7: the one-line footnote over a history-loaded orchestrator conversation. */
    data object HistoryFootnote : ChatItem {
        override val key get() = "history-footnote"
        override val contentType get() = "footnote"
        override val entryId: String? get() = null
        override val gap get() = Gap.Item
    }

    data class UserBubble(
        override val entryId: String,
        val text: String,
        val origin: UserOrigin,
        /** A local inject awaiting its echo (§7.9). */
        val pending: Boolean,
        /** A Gemini transcript that is still growing (VOICE · LIVE, caret). */
        val live: Boolean,
        override val gap: Gap,
    ) : ChatItem {
        override val key get() = "m:$entryId"
        override val contentType get() = if (origin == UserOrigin.VOICE) "voice-transcript" else "user"
        val lineCount: Int get() = text.count { it == '\n' } + 1
    }

    /** One top-level markdown node of a text block (§3.2). [tail] = part of the unfinished tail. */
    data class MdBlock(
        override val entryId: String,
        val blockId: String,
        val mdIndex: Int,
        val node: MdNode,
        val previous: MdNode?,
        val tail: Boolean,
        override val gap: Gap,
    ) : ChatItem {
        override val key get() = "m:$entryId/b:$blockId/md:$mdIndex"
        override val contentType get() = if (tail) MdNode.CONTENT_TYPE_TAIL else node.contentType
    }

    data class Thinking(
        override val entryId: String,
        val blockId: String,
        val text: String,
        val streaming: Boolean,
        override val gap: Gap,
    ) : ChatItem {
        override val key get() = "m:$entryId/b:$blockId"
        override val contentType get() = "thinking"
    }

    /** Where a card sits in an "N steps" group, for the outer corner rounding (mockup `.tg-b`). */
    enum class GroupPosition { Solo, First, Middle, Last, Only }

    data class ToolCard(
        override val entryId: String,
        val block: ToolBlock,
        val descriptor: ToolDescriptor,
        val position: GroupPosition,
        val expanded: Boolean,
        override val gap: Gap,
    ) : ChatItem {
        override val key get() = toolKey(entryId, block)
        override val contentType get() = "tool-${descriptor.renderer}"
    }

    /** "N steps" header (IA §9.3); its members follow as [ToolCard]s only while [expanded]. */
    data class ToolGroup(
        override val entryId: String,
        val firstToolKey: String,
        val count: Int,
        val tiles: List<ToolDescriptor>,
        val summary: String,
        val status: ToolStatusUi,
        val expanded: Boolean,
        override val gap: Gap,
    ) : ChatItem {
        override val key get() = "g:$firstToolKey"
        override val contentType get() = "tool-group"
    }

    /** A permission request's record in the timeline; the live question is an inline card (§6.9). */
    data class Permission(
        override val entryId: String,
        val block: PermissionBlock,
        override val gap: Gap,
    ) : ChatItem {
        override val key get() = "m:$entryId/b:${block.id}"
        override val contentType get() = "permission"
    }

    data class CompactDivider(
        override val entryId: String,
        val summary: String,
        val tokensBefore: String?,
        val tokensAfter: String?,
        override val gap: Gap,
    ) : ChatItem {
        override val key get() = "m:$entryId/compact"
        override val contentType get() = "compact"
    }

    data class Notice(
        override val entryId: String,
        val kind: NoticeKind,
        val text: String,
        override val gap: Gap,
    ) : ChatItem {
        override val key get() = "n:$entryId"
        override val contentType get() = "notice"
    }

    /** Results the UI must keep reachable (R-9): orphans and unattributed results, never dropped. */
    data class UnmatchedResults(val results: List<PendingResult>, val ids: List<String?>) : ChatItem {
        override val key get() = "unmatched-results"
        override val contentType get() = "unmatched"
        override val entryId: String? get() = null
        override val gap get() = Gap.Item
    }

    companion object {
        /** `t:<tool_use_id>`; a card without an id (G-20) falls back to its block id. */
        fun toolKey(entryId: String, block: ToolBlock): String =
            if (block.toolUseId.isNotEmpty()) "t:${block.toolUseId}" else "m:$entryId/b:${block.id}"
    }
}

/** Tool status as the UI shows it. */
enum class ToolStatusUi { Running, Waiting, Done, Error }
