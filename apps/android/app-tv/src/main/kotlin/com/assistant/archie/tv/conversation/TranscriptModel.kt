package com.assistant.archie.tv.conversation

import androidx.compose.runtime.Immutable
import com.assistant.archie.feature.chat.InlineCardUi
import com.assistant.archie.feature.chat.model.ChatItem

/*
 * The pure parts of the conversation reading model (spec 15 §3.5 "Focus model for reading"),
 * JVM-tested: what the transcript list holds, how a tall block scrolls before focus moves on, which
 * block paging lands on, and when focus follows the live tail.
 */

/** One item of the TV transcript: a conversation item, or an inline card at the tail. */
@Immutable
sealed interface TItem {
    val key: String
    val contentType: String

    /** Focus follows the tail onto it (cards never take focus by themselves, FOCUS-1). */
    val followable: Boolean

    data class Chat(val item: ChatItem) : TItem {
        override val key: String get() = item.key
        override val contentType: String get() = item.contentType
        override val followable: Boolean get() = item !is ChatItem.LoadOlder && item !is ChatItem.HistoryFootnote
    }

    data class Card(val card: InlineCardUi) : TItem {
        override val key: String get() = "card:${card.id}"
        override val contentType: String get() = "card-" + card::class.simpleName
        override val followable: Boolean get() = false
    }

    /** The session ended (terminated): the reason, after everything else. */
    data class Ended(val reason: String) : TItem {
        override val key: String get() = "ended"
        override val contentType: String get() = "ended"
        override val followable: Boolean get() = false
    }
}

object TranscriptModel {
    /** Conversation items (timeline order) + the inline cards after them (spec 15 mockup 6: cards close the transcript). */
    fun items(chat: List<ChatItem>, cards: List<InlineCardUi>, ended: String? = null): List<TItem> {
        val out = ArrayList<TItem>(chat.size + cards.size + 1)
        chat.mapTo(out) { TItem.Chat(it) }
        cards.mapTo(out) { TItem.Card(it) }
        if (ended != null) out += TItem.Ended(ended)
        return out
    }

    /** The newest item focus may follow (timeline order: the last followable one). */
    fun newestFollowable(items: List<TItem>): String? = items.lastOrNull { it.followable }?.key

    /** Some card asks for an answer the user has not given yet (the "▼ Approval needed" hint). */
    fun approvalPending(cards: List<InlineCardUi>): Boolean = cards.any {
        (it is InlineCardUi.Permission && !it.answered) || (it is InlineCardUi.AgentApprovalCard && !it.answered)
    }

    /**
     * Up/Down on a focused block (positions in px, y grows downwards): while the block reaches past
     * the viewport in that direction, scroll by at most [step] × viewport height and keep focus.
     * Returns the distance to move the content **up** (negative: down), or null to let focus move.
     */
    fun internalScroll(top: Float, bottom: Float, viewTop: Float, viewBottom: Float, down: Boolean, step: Float = PAGE_STEP, slop: Float = 2f): Float? {
        val page = step * (viewBottom - viewTop)
        if (page <= 0f) return null
        return if (down) {
            val over = bottom - viewBottom
            if (over > slop) minOf(page, over) else null
        } else {
            val over = viewTop - top
            if (over > slop) -minOf(page, over) else null
        }
    }

    /** A laid-out focusable item: [key] and its vertical extent in px. */
    data class Placed(val key: String, val top: Float, val bottom: Float)

    /**
     * Rewind / FF moved the view by a page: the block to focus now. The focused one stays when it
     * still crosses the viewport's middle; otherwise the visible block that crosses the middle, else
     * the visible block nearest to it. At an end the list could not move: FF goes to the newest
     * block ([newest]), Rewind to the topmost visible one.
     */
    fun afterPage(placed: List<Placed>, focused: String?, viewTop: Float, viewBottom: Float, moved: Boolean, down: Boolean, newest: String?): String? {
        val visible = placed.filter { it.bottom > viewTop && it.top < viewBottom }
        if (visible.isEmpty()) return focused
        if (!moved) {
            return if (down) newest?.takeIf { k -> placed.any { it.key == k } } ?: visible.maxByOrNull { it.bottom }?.key
            else visible.minByOrNull { it.top }?.key
        }
        val mid = (viewTop + viewBottom) / 2f
        visible.firstOrNull { it.key == focused && it.top <= mid && it.bottom >= mid }?.let { return it.key }
        visible.firstOrNull { it.top <= mid && it.bottom >= mid }?.let { return it.key }
        return visible.minByOrNull { minOf(kotlin.math.abs(it.top - mid), kotlin.math.abs(it.bottom - mid)) }?.key
    }

    /**
     * Focus follows the live tail: it sat on the newest followable item ([previousNewest]) and a newer
     * one arrived ([newest]). Cards are not followable, so an approval card never takes focus.
     */
    fun followTo(focused: String?, previousNewest: String?, newest: String?): String? =
        if (focused != null && focused == previousNewest && newest != null && newest != previousNewest) newest else null

    /**
     * The newest block takes focus once the history arrives (spec 15 §3.5) only while the user has
     * not done anything yet: no D-pad / OK press since the screen opened ([userMoved]), not typing
     * and no overlay open ([busy]) — history arriving never takes focus from the user (B5).
     */
    fun shouldPlaceInitialFocus(placed: Boolean, userMoved: Boolean, busy: Boolean): Boolean = !placed && !userMoved && !busy

    /**
     * Where focus goes back on this screen (B3: an overlay closed, or the focused element left —
     * an answered approval card): [last] when it is still there, else the newest block, else the
     * composer. [Restore.COMPOSER] = the composer row's own focus restorer decides.
     */
    fun restoreTarget(last: String?, composed: Set<String>, newest: String?): Restore = when {
        last == TranscriptController.COMPOSER -> Restore.Composer
        last != null && last in composed -> Restore.Block(last)
        newest != null && newest in composed -> Restore.Block(newest)
        else -> Restore.Composer
    }

    sealed interface Restore {
        data class Block(val key: String) : Restore
        data object Composer : Restore
    }

    /** A page is 0.8 of the viewport (spec 15 §3.3). */
    const val PAGE_STEP = 0.8f
}
