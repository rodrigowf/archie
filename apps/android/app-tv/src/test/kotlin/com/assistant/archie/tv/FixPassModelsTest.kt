package com.assistant.archie.tv

import com.assistant.archie.tv.conversation.TranscriptController
import com.assistant.archie.tv.conversation.TranscriptModel
import com.assistant.archie.tv.conversations.ConvRef
import com.assistant.archie.tv.conversations.ConvRowUi
import com.assistant.archie.tv.conversations.OwnCloseFilter
import com.assistant.archie.tv.conversations.PreviewCache
import com.assistant.archie.tv.conversations.PreviewLine
import com.assistant.archie.tv.conversations.PreviewUi
import com.assistant.archie.tv.shell.Conversation
import com.assistant.archie.tv.shell.Conversations
import com.assistant.archie.tv.shell.Home
import com.assistant.archie.tv.shell.TvNavigator
import com.assistant.archie.tv.theme.SessionState
import com.assistant.core.model.HarnessProvider
import com.assistant.core.model.SessionSummary
import androidx.navigation3.runtime.NavKey
import com.assistant.archie.tv.conversations.OpenTarget
import com.assistant.archie.feature.sessions.SessionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules of the Android fix pass after T5 (review 2026-10-16: B3, B5, B7, B8). */
class FixPassModelsTest {
    // ── B5: history arriving never takes focus from the user ──

    @Test
    fun initialPlacementOnlyWhileTheUserHasNotActed() {
        assertTrue(TranscriptModel.shouldPlaceInitialFocus(placed = false, userMoved = false, busy = false))
        assertFalse("a D-pad / OK press before the history came", TranscriptModel.shouldPlaceInitialFocus(placed = false, userMoved = true, busy = false))
        assertFalse("typing or an overlay open", TranscriptModel.shouldPlaceInitialFocus(placed = false, userMoved = false, busy = true))
        assertFalse("once only", TranscriptModel.shouldPlaceInitialFocus(placed = true, userMoved = false, busy = false))
    }

    // ── B3: where focus goes back on the conversation screen ──

    @Test
    fun conversationRestoreTarget() {
        val composed = setOf("a", "b", "card:p1")
        assertEquals(TranscriptModel.Restore.Block("card:p1"), TranscriptModel.restoreTarget("card:p1", composed, "b"))
        assertEquals("the card left: the newest block", TranscriptModel.Restore.Block("b"), TranscriptModel.restoreTarget("card:p1", setOf("a", "b"), "b"))
        assertEquals(TranscriptModel.Restore.Composer, TranscriptModel.restoreTarget(TranscriptController.COMPOSER, composed, "b"))
        assertEquals(TranscriptModel.Restore.Composer, TranscriptModel.restoreTarget("gone", emptySet(), null))
        assertEquals(TranscriptModel.Restore.Block("b"), TranscriptModel.restoreTarget(null, composed, "b"))
    }

    // ── B7: a screen that leaves by itself removes its own entry, once ──

    @Test
    fun removeTakesOnlyThatEntryAndIsIdempotent() {
        val c = Conversation(localId = "L1")
        val stack = mutableListOf<NavKey>(Home, Conversations, c)
        val nav = TvNavigator(stack)
        assertTrue(nav.remove(c))
        assertFalse("the second close signal finds nothing", nav.remove(c))
        assertEquals(listOf<NavKey>(Home, Conversations), stack)

        // Already popped by Back, then the close arrives: the screen below stays.
        val stack2 = mutableListOf<NavKey>(Home, Conversations)
        assertFalse(TvNavigator(stack2).remove(c))
        assertEquals(listOf<NavKey>(Home, Conversations), stack2)

        // Never the root.
        val stack3 = mutableListOf<NavKey>(c)
        assertFalse(TvNavigator(stack3).remove(c))
        assertEquals(1, stack3.size)
    }

    // ── B8: previews follow the conversation ──

    private fun row(messages: Int?, state: SessionState? = null): ConvRowUi {
        val s = SessionSummary("sdk-1", null, null, "A title", messages ?: 0, false, HarnessProvider.CLAUDE, null)
        return ConvRowUi("k", ConvRef(OpenTarget.History(s), SessionTarget.of(s), null), "A title", "Claude", state, "16:20", messages)
    }

    @Test
    fun previewCacheIsKeyedByMessageCountAndState() {
        var now = 0L
        val cache = PreviewCache(now = { now })
        val ui = PreviewUi(lines = listOf(PreviewLine.User("hi")))
        cache.put(row(4), ui)
        assertSame(ui, cache.get(row(4)))
        assertNull("new messages: fetch again", cache.get(row(5)))
        now += 10 * 60_000
        assertSame("a history row that did not change stays cached", ui, cache.get(row(4)))
    }

    @Test
    fun openRowsExpire() {
        var now = 0L
        val cache = PreviewCache(now = { now }, openTtlMs = 30_000)
        val ui = PreviewUi(lines = listOf(PreviewLine.Assistant("working")))
        val open = row(null, SessionState.WORKING)
        cache.put(open, ui)
        assertSame(ui, cache.get(open))
        assertNull("the state changed", cache.get(row(null, SessionState.IDLE)))
        now += 30_000
        assertNull("an open row's preview is at most 30 s old", cache.get(open))
    }

    // ── B11: only the TV's own confirmed close is quiet ──

    @Test
    fun ownCloseNoticeIsSwallowedOnceForThatSessionOnly() {
        var now = 0L
        val f = OwnCloseFilter(now = { now })
        assertFalse("nothing confirmed: every notice shows", f.consume("Fix the build was closed elsewhere"))
        f.expect(listOf("Fix the build", "fix-build"))
        assertFalse("another session's close still shows", f.consume("Write docs was closed elsewhere"))
        assertTrue(f.consume("Fix the build was closed elsewhere"))
        assertFalse("once", f.consume("Fix the build was closed elsewhere"))

        f.expect(listOf("Fix the build"))
        now += 8_000
        assertFalse("too late: it is news again", f.consume("Fix the build was closed elsewhere"))
    }
}
