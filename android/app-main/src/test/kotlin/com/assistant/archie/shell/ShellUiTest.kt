package com.assistant.archie.shell

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.navigation3.runtime.NavKey
import com.assistant.archie.feature.sessions.SessionDialog
import com.assistant.archie.feature.sessions.SessionsIntent
import com.assistant.archie.feature.sessions.SessionsUiState
import com.assistant.core.data.ItemKey
import com.assistant.core.design.theme.ArchieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drawer, switcher, title swipe and close confirmation on Compact (spec 14 §6.3 `DrawerUiTest`, `SessionSwitcherUiTest`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w443dp-h986dp-xxhdpi", application = Application::class)
class CompactShellUiTest {
    @get:Rule val compose = createComposeRule()
    private val actions = mutableListOf<ShellAction>()
    private val backStack = mutableStateListOf<NavKey>(Workspace)

    private fun show(state: ShellUiState = ShellFixtures.state()) = compose.setContent {
        ArchieTheme(reduceMotion = true) { ArchieShell(state, { actions += it }, backStack, ShellFixtures.Destinations()) }
    }

    @Test fun noBottomBar_titleIsActiveSession_withLiveSubtitle() {
        show()
        compose.onNode(titleInTopBar("Living-room TV")).assertIsDisplayed()
        compose.onNode(titleInTopBar("Waiting for your approval")).assertIsDisplayed()
        compose.onNodeWithContentDescription("Open navigation").assertIsDisplayed()
    }

    @Test fun drawer_historyGroupedByDate_openRowDispatches_footerNavigates() {
        show()
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("drawer").assertIsDisplayed()
        compose.onNodeWithText("Connected to jetson").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Yesterday").assertIsDisplayed()
        compose.onNodeWithText("14:20").assertIsDisplayed()
        compose.onNodeWithText("Fix context-sync delete race").performClick()
        compose.waitForIdle()
        val open = actions.filterIsInstance<ShellAction.Sessions>().map { it.intent }.filterIsInstance<SessionsIntent.OpenHistory>().single()
        assertEquals("h2", open.session.sdkId)

        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        assertEquals(SettingsHome, backStack.last())
        compose.onNodeWithTag("placeholder-B-08").assertIsDisplayed()
    }

    @Test fun titleOpensSwitcher_rowsFromOpenSessions_selectAndNew() {
        show()
        compose.onNode(titleInTopBar("Living-room TV")).performClick()
        compose.waitForIdle()
        assertEquals(ShellFixtures.items.size, compose.onAllNodesWithTag("switcher-row").fetchSemanticsNodes().size)
        compose.onNode(androidx.compose.ui.test.hasText("Refactor voice module") and androidx.compose.ui.test.hasTestTag("switcher-row")).performClick()
        compose.waitForIdle()
        assertTrue(actions.contains(ShellAction.Select(ShellFixtures.agentKey)))

        compose.onNode(titleInTopBar("Living-room TV")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("New agent session").performClick()
        compose.waitForIdle()
        assertTrue(actions.contains(ShellAction.NewAgent))
    }

    @Test fun closingArchieAsksFirst_thenClosesExplicitly() {
        // The × goes to the B-06 close flow; nothing closes before the confirmation.
        show()
        compose.onNode(titleInTopBar("Living-room TV")).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close Living-room TV").performClick()
        compose.waitForIdle()
        assertTrue("no close before confirmation", actions.none { it is ShellAction.Close })
        val req = actions.filterIsInstance<ShellAction.Sessions>().map { it.intent }.filterIsInstance<SessionsIntent.RequestClose>().single()
        assertEquals(ItemKey.Archie, req.item.key)
    }

    @Test fun closeConfirmation_fromSessionsState_confirms() {
        show(ShellFixtures.state().copy(sessions = SessionsUiState(dialog = SessionDialog.Close(ItemKey.Archie, "Living-room TV", archie = true))))
        compose.onNodeWithText("Stop Archie on all devices?").assertIsDisplayed()
        compose.onNodeWithText("Stop Archie").performClick()
        compose.waitForIdle()
        assertEquals(listOf<SessionsIntent>(SessionsIntent.ConfirmClose), actions.filterIsInstance<ShellAction.Sessions>().map { it.intent })
    }

    @Test fun horizontalSwipeOnTitle_switchesSessions() {
        show()
        compose.onNodeWithTag("top-app-bar").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("top-app-bar").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(
            listOf(ShellAction.SelectRelative(+1), ShellAction.SelectRelative(-1)),
            actions.filterIsInstance<ShellAction.SelectRelative>(),
        )
    }
}

/** Expanded: rail + list pane + tab strip fed by the open sessions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp", application = Application::class)
class ExpandedShellUiTest {
    @get:Rule val compose = createComposeRule()
    private val actions = mutableListOf<ShellAction>()
    private val backStack = mutableStateListOf<NavKey>(Workspace)

    @Test fun tabsFromOpenSessions_tabSelects_railSwitchesPane_settingsPushes() {
        compose.setContent {
            ArchieTheme(reduceMotion = true) { ArchieShell(ShellFixtures.state(), { actions += it }, backStack, ShellFixtures.Destinations()) }
        }
        assertEquals(ShellFixtures.items.size, compose.onAllNodesWithTag("tab").fetchSemanticsNodes().size)
        compose.onNodeWithTag("list-pane").assertIsDisplayed()
        compose.onNodeWithContentDescription("All tabs (5)").assertIsDisplayed()

        compose.onAllNodesWithTag("tab")[2].performClick()
        compose.waitForIdle()
        assertTrue(actions.last() is ShellAction.Select)

        compose.onNodeWithText("Memory").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Memory tree").assertIsDisplayed()

        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        assertEquals(SettingsHome, backStack.last())
        compose.onNodeWithTag("rail").assertIsDisplayed()
    }
}
