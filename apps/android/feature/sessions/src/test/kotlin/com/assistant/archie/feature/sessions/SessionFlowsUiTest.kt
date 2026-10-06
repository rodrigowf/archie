package com.assistant.archie.feature.sessions

import android.app.Application
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.assistant.archie.feature.sessions.support.FakeBackend
import com.assistant.archie.feature.sessions.support.FlowScreen
import com.assistant.archie.feature.sessions.support.Harness
import com.assistant.archie.feature.sessions.support.Surface
import com.assistant.archie.feature.sessions.support.eventually
import com.assistant.core.data.ItemKey
import com.assistant.core.data.ItemKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Shared setup of the B-06 flow tests: the real data layer against [FakeBackend] under [FlowScreen]. */
abstract class FlowTestBase {
    @get:Rule val compose = createComposeRule()
    protected val backend = FakeBackend().start()
    private var harness: Harness? = null

    @After fun tearDown() {
        harness?.close()
        backend.shutdown()
    }

    protected val history = listOf(
        FakeBackend.session("JSONL", "Living-room TV", "2026-10-03T11:00:00+00:00", orch = true, localId = "ORCH"),
        FakeBackend.session("H3", "Morning briefing", "2026-10-02T08:12:00+00:00", orch = true),
        FakeBackend.session("S1", "Refactor voice module", "2026-10-01T09:00:00+00:00", orch = false, count = 14),
        FakeBackend.session("S2", "Fix context-sync delete race", "2026-09-30T11:05:00+00:00", orch = false, provider = "qwen"),
    )

    protected fun start(surface: Surface, archieRunning: Boolean): Harness {
        backend.sessionsJson = "[" + history.joinToString(",") + "]"
        if (archieRunning) backend.poolJson = FakeBackend.orchRow("ORCH", "JSONL")
        val h = Harness(backend).connect()
        harness = h
        if (archieRunning) eventually { h.open.active.value == ItemKey.Archie }
        compose.setContent { FlowScreen(h, surface) }
        compose.waitForIdle()
        return h
    }

    protected fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    protected fun waitGone(tag: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    protected fun row(title: String): SemanticsMatcher = hasText(title) and hasTestTag("history-row")

    protected fun inDialog(tag: String, text: String): SemanticsMatcher = hasText(text) and hasAnyAncestor(hasTestTag(tag))

    protected fun openRowMenu(title: String) {
        compose.onNodeWithContentDescription("More actions for “$title”").performClick()
        compose.waitForIdle()
    }
}

/**
 * Every B-06 flow end to end (spec 14 §7 B-06 DoD): real UI → SessionsController → B-03 repositories
 * → real sockets and REST against a MockWebServer backend. Asserts what is sent (and what is not:
 * P-1, nothing closes unless the user asked).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w443dp-h986dp-xxhdpi", application = Application::class)
class SessionFlowsUiTest : FlowTestBase() {
    // ───────────────────────── new Archie / new agent ─────────────────────────

    @Test fun newArchie_nothingRunning_startsFresh() {
        val h = start(Surface.Switcher, archieRunning = false)
        compose.onNodeWithText("New Archie chat").performClick()
        eventually(message = { "starts=${backend.starts("orch")}" }) { backend.starts("orch").size == 1 }
        // A new orchestrator: a client-minted id, and its JSONL id is that same id (G-14).
        val frame = backend.starts("orch").single()
        val id = Regex("\"local_id\":\"([^\"]+)\"").find(frame)!!.groupValues[1]
        assertTrue(frame, frame.contains("\"resume_sdk_id\":\"$id\""))
        eventually { h.open.active.value == ItemKey.Archie }
        compose.onAllNodesWithTag("conflict-dialog").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        assertTrue("nothing closed: ${backend.closeRequests()}", backend.closeRequests().isEmpty())
    }

    @Test fun newArchie_whileRunning_asksWithStackedActions_openTheRunningOne() {
        val h = start(Surface.Switcher, archieRunning = true)
        val startsBefore = backend.starts("orch").size
        compose.onNodeWithText("New Archie chat").performClick()
        waitForText("Archie is already active")
        compose.onNodeWithText("Only one Archie conversation runs at a time. Starting a new one stops the running one on every device. You can resume it later from the history.").assertIsDisplayed()
        // A proper dialog with stacked buttons (inv03 §1.7): one column, recommended first, Cancel last.
        val tops = listOf("Open the running one", "Stop it and start new", "Cancel").map {
            compose.onNode(inDialog("conflict-dialog", it)).fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("stacked top to bottom: $tops", tops[0] < tops[1] && tops[1] < tops[2])

        compose.onNodeWithText("Open the running one").performClick()
        waitGone("conflict-dialog")
        Thread.sleep(300)
        assertEquals("no new start, no close", startsBefore, backend.starts("orch").size)
        assertTrue(backend.closeRequests().isEmpty())
        assertEquals(ItemKey.Archie, h.open.active.value)
    }

    @Test fun newArchie_whileRunning_stopItAndStartNew_closesThenStarts() {
        start(Surface.Switcher, archieRunning = true)
        compose.onNodeWithText("New Archie chat").performClick()
        waitForText("Archie is already active")
        compose.onNodeWithText("Stop it and start new").performClick()
        eventually(message = { "requests=${backend.requests} starts=${backend.starts("orch")}" }) {
            backend.closeRequests() == listOf("POST /api/sessions/ORCH/close") && backend.starts("orch").size == 2
        }
        val fresh = backend.starts("orch").last()
        val id = Regex("\"local_id\":\"([^\"]+)\"").find(fresh)!!.groupValues[1]
        assertTrue(fresh, id != "ORCH" && fresh.contains("\"resume_sdk_id\":\"$id\""))
    }

    @Test fun newArchie_cancel_doesNothing() {
        start(Surface.Switcher, archieRunning = true)
        compose.onNodeWithText("New Archie chat").performClick()
        waitForText("Archie is already active")
        compose.onNodeWithText("Cancel").performClick()
        waitGone("conflict-dialog")
        Thread.sleep(300)
        assertEquals(1, backend.starts("orch").size)
        assertTrue(backend.closeRequests().isEmpty())
    }

    @Test fun newArchie_loseTheStartRace_dropsFailedView_andAsks() {
        val h = start(Surface.Switcher, archieRunning = false)
        backend.raceOrchestrator = "OTHER"           // another device starts Archie after our pool check
        compose.onNodeWithText("New Archie chat").performClick()
        waitForText("Archie is already active")
        compose.onNodeWithText("Only one Archie conversation runs at a time, and one is running on another device.", substring = true).assertIsDisplayed()
        assertTrue("the failed view never existed server-side: no close", backend.closeRequests().isEmpty())
        eventually { h.conversations.current(com.assistant.core.data.ConversationKey.ARCHIE) == null }

        compose.onNodeWithText("Open the running one").performClick()
        eventually(message = { "starts=${backend.starts("orch")}" }) { backend.starts("orch").last().contains("\"local_id\":\"OTHER\"") }
        assertTrue(backend.starts("orch").last().contains("\"resume_sdk_id\":\"OTHER_JSONL\""))
    }

    @Test fun newAgentSession_opensFocused_withGlobalDefaults_andSaysWhatWaitsForTheFirstReply() {
        val h = start(Surface.Switcher, archieRunning = true)
        compose.onNodeWithText("New agent session").performClick()
        eventually(message = { "starts=${backend.starts("agent")}" }) { backend.starts("agent").size == 1 }
        assertTrue(!backend.starts("agent").single().contains("resume_sdk_id"))
        eventually { h.open.items.value.firstOrNull { it.key == h.open.active.value }?.kind == ItemKind.AGENT }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("New agent session").fetchSemanticsNodes().size >= 2 }

        // ID-3: Rename / Fork / Delete say why they are unavailable instead of doing nothing.
        compose.onNodeWithContentDescription("Session menu").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Rename")).assertIsNotEnabled()
        assertTrue(compose.onAllNodesWithText(NEEDS_FIRST_REPLY).fetchSemanticsNodes().size >= 3)
    }

    // ───────────────────────── resume past Archie ─────────────────────────

    @Test fun resumePastArchie_nothingRunning_startsWithResumeId() {
        val h = start(Surface.History, archieRunning = false)
        compose.onNode(row("Morning briefing")).performClick()
        eventually(message = { "starts=${backend.starts("orch")}" }) { backend.starts("orch").any { it.contains("\"resume_sdk_id\":\"H3\"") } }
        eventually { h.open.active.value == ItemKey.Archie }
        assertTrue(backend.closeRequests().isEmpty())
    }

    @Test fun resumePastArchie_whileAnotherRuns_asks_thenStopsAndResumes() {
        start(Surface.History, archieRunning = true)
        compose.onNode(row("Morning briefing")).performClick()
        waitForText("Another Archie conversation is running")
        compose.onNodeWithText("Resuming this conversation stops the running one on every device.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Stop it and resume this one").performClick()
        eventually(message = { "requests=${backend.requests}" }) {
            backend.closeRequests() == listOf("POST /api/sessions/ORCH/close") &&
                backend.starts("orch").any { it.contains("\"resume_sdk_id\":\"H3\"") }
        }
        // The close came before the new start (else the server answers orchestrator_active).
        val closeAt = backend.requests.indexOf("POST /api/sessions/ORCH/close")
        assertTrue(closeAt >= 0)
    }

    // ───────────────────────── rename / duplicate ─────────────────────────

    @Test fun duplicate_doesNotOpen_snackbarOffersOpen() {
        val h = start(Surface.History, archieRunning = true)
        openRowMenu("Fix context-sync delete race")
        compose.onNodeWithText("Duplicate").performClick()
        eventually { backend.requests.contains("POST /api/sessions/S2/duplicate") }
        waitForText("Duplicated “Fix context-sync delete race”")
        Thread.sleep(300)
        assertTrue("not opened (web parity)", backend.starts("agent").isEmpty())
        compose.onNodeWithText("Open").performClick()
        eventually(message = { "agent=${backend.starts("agent")}" }) { backend.starts("agent").any { it.contains("\"resume_sdk_id\":\"DUP1\"") } }
        eventually { h.open.items.value.firstOrNull { it.key == h.open.active.value }?.sdkId == "DUP1" }
    }

    // ───────────────────────── fork ─────────────────────────

    @Test fun fork_fromSessionMenu_copiesWholeConversation_opensTheCopy() {
        val h = start(Surface.History, archieRunning = true)
        compose.onNode(row("Refactor voice module")).performClick()
        eventually { h.open.items.value.firstOrNull { it.key == h.open.active.value }?.sdkId == "S1" }
        compose.onNodeWithContentDescription("Session menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Fork").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Fork this conversation?").assertIsDisplayed()
        compose.onNode(inDialog("fork-dialog", "Fork")).performClick()
        eventually(message = { "bodies=${backend.bodies}" }) {
            backend.bodies.any { it.first == "POST /api/sessions/S1/fork" && it.second.replace(" ", "").contains("\"drop_last_n\":0") }
        }
        eventually(message = { "agent=${backend.starts("agent")}" }) { backend.starts("agent").any { it.contains("\"resume_sdk_id\":\"FORK1\"") } }
    }

    // ───────────────────────── close ─────────────────────────

    @Test fun close_idleAgent_closesAtOnce_archieAsksFirst() {
        val h = start(Surface.Switcher, archieRunning = true)
        h.open.openSession(com.assistant.core.model.SessionSummary("S1", null, null, "Refactor voice module", 14, false, com.assistant.core.model.HarnessProvider.CLAUDE, null))
        eventually { h.open.items.value.any { it.sdkId == "S1" } }
        eventually { h.open.items.value.first { it.sdkId == "S1" }.status == com.assistant.core.data.TabStatus.IDLE }
        val agentLocal = h.open.items.value.first { it.sdkId == "S1" }.localId!!
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close Refactor voice module").performClick()
        eventually(message = { "close=${backend.closeRequests()}" }) { backend.closeRequests() == listOf("POST /api/sessions/$agentLocal/close") }
        eventually { h.open.items.value.none { it.sdkId == "S1" } }

        compose.onNodeWithContentDescription("Close Living-room TV").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Stop Archie on all devices?").assertIsDisplayed()
        assertEquals("nothing before the confirmation", 1, backend.closeRequests().size)
        compose.onNodeWithText("Stop Archie").performClick()
        eventually(message = { "close=${backend.closeRequests()}" }) { backend.closeRequests().contains("POST /api/sessions/ORCH/close") }
    }

    @Test fun switcher_swipeClosesRow_bothDirections() {
        val h = start(Surface.Switcher, archieRunning = true)
        h.open.newAgentSession()
        eventually { h.open.items.value.size == 2 && h.open.items.value[1].status == com.assistant.core.data.TabStatus.IDLE }
        val agentLocal = h.open.items.value[1].localId!!
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("switcher-swipe").fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithTag("switcher-swipe")[1].performTouchInput { swipeLeft() }
        compose.waitForIdle()
        eventually(message = { "close=${backend.closeRequests()}" }) { backend.closeRequests() == listOf("POST /api/sessions/$agentLocal/close") }
        eventually { h.open.items.value.size == 1 }

        // Archie asks first; the row snaps back while the dialog is up.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("switcher-swipe").fetchSemanticsNodes().size == 1 }
        compose.onAllNodesWithTag("switcher-swipe")[0].performTouchInput { swipeRight() }
        compose.waitForIdle()
        waitForText("Stop Archie on all devices?")
        assertEquals(1, backend.closeRequests().size)
        compose.onNodeWithText("Cancel").performClick()
        waitGone("close-dialog")
        compose.onNode(hasText("Living-room TV") and hasTestTag("switcher-row")).assertIsDisplayed()
    }

    // ───────────────────────── delete ─────────────────────────

    @Test fun delete_openSession_closesViewAndPoolEntryBeforeDelete() {
        val h = start(Surface.History, archieRunning = true)
        compose.onNode(row("Refactor voice module")).performClick()
        eventually { h.open.items.value.firstOrNull { it.key == h.open.active.value }?.sdkId == "S1" }
        val local = h.open.items.value.first { it.sdkId == "S1" }.localId!!
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Session menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete this session?").assertIsDisplayed()
        compose.onNodeWithText(
            "“Refactor voice module” and its 14 messages move to the server’s trash (recoverable from context/trash/). Memory files are kept. The session stops on every device.",
        ).assertIsDisplayed()
        compose.onNode(inDialog("delete-dialog", "Delete")).performClick()
        eventually(message = { "requests=${backend.requests}" }) { backend.requests.contains("DELETE /api/sessions/S1") }
        val closeAt = backend.requests.indexOf("POST /api/sessions/$local/close")
        val deleteAt = backend.requests.indexOf("DELETE /api/sessions/S1")
        assertTrue("close ($closeAt) before delete ($deleteAt)", closeAt in 0 until deleteAt)
        eventually { h.open.items.value.none { it.sdkId == "S1" } }
        waitForText("Deleted “Refactor voice module”")
    }

    @Test fun delete_historyRow_notOpen_deletesWithoutClosing() {
        start(Surface.History, archieRunning = true)
        compose.onNode(row("Fix context-sync delete race")).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("“Fix context-sync delete race” and its 6 messages move to the server’s trash (recoverable from context/trash/). Memory files are kept.").assertIsDisplayed()
        compose.onNode(inDialog("delete-dialog", "Delete")).performClick()
        eventually { backend.requests.contains("DELETE /api/sessions/S2") }
        assertTrue("nothing live to close: ${backend.closeRequests()}", backend.closeRequests().isEmpty())
        waitForText("Deleted “Fix context-sync delete race”")
    }

    @Test fun deleteCancel_sendsNothing() {
        start(Surface.History, archieRunning = true)
        openRowMenu("Morning briefing")
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete this conversation?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        waitGone("delete-dialog")
        Thread.sleep(200)
        assertTrue(backend.requests.none { it.startsWith("DELETE") })
        assertNotEquals(0, compose.onAllNodesWithText("Morning briefing").fetchSemanticsNodes().size)
    }
}

/**
 * Rename (§6.6, P-4: Archie conversations too). On the default Robolectric screen: with a size
 * qualifier, any text field inside a Dialog window keeps Robolectric from ever idling (verified with a
 * bare `Dialog { BasicTextField }`), which says nothing about the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RenameFlowUiTest : FlowTestBase() {
    private fun renameVia(openMenu: () -> Unit, title: String) {
        openMenu()
        compose.waitForIdle()
        compose.onNodeWithText("Rename").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("rename-dialog").assertIsDisplayed()
        compose.onNodeWithTag("rename-field").performTextReplacement(title)
        compose.onNodeWithText("Save").performClick()
    }

    @Test fun rename_fromHistoryRow_patchesTitle_snackbarUndoRenamesBack() {
        start(Surface.History, archieRunning = true)
        renameVia({ openRowMenu("Refactor voice module") }, "Voice state machine")
        eventually(message = { "bodies=${backend.bodies}" }) {
            backend.bodies.any { it.first == "PATCH /api/sessions/S1/rename" && it.second.contains("Voice state machine") }
        }
        waitGone("rename-dialog")
        waitForText("Renamed to “Voice state machine”")
        compose.onNodeWithText("Undo").performClick()
        eventually(message = { "bodies=${backend.bodies}" }) {
            backend.bodies.any { it.first == "PATCH /api/sessions/S1/rename" && it.second.contains("Refactor voice module") }
        }
    }

    @Test fun rename_serverError_keepsDialogOpen_showsDetailVerbatim() {
        start(Surface.History, archieRunning = true)
        backend.renameCode = 409
        renameVia({ openRowMenu("Refactor voice module") }, "Nope")
        waitForText("Title is locked")
        compose.onNodeWithTag("rename-dialog").assertIsDisplayed()
    }

    @Test fun rename_activeArchie_fromSessionMenu() {
        start(Surface.History, archieRunning = true)
        renameVia({ compose.onNodeWithContentDescription("Session menu").performClick() }, "Movie night")
        eventually { backend.bodies.any { it.first == "PATCH /api/sessions/JSONL/rename" && it.second.contains("Movie night") } }
    }

    @Test fun rename_unchangedTitle_sendsNothing() {
        start(Surface.History, archieRunning = true)
        renameVia({ openRowMenu("Refactor voice module") }, "Refactor voice module")
        waitGone("rename-dialog")
        Thread.sleep(200)
        assertTrue(backend.requests.none { it.contains("/rename") })
    }
}
