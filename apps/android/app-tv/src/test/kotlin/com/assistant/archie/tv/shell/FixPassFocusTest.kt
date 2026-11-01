package com.assistant.archie.tv.shell

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvDialog
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.viewer.T3Fixtures
import com.assistant.archie.tv.viewer.ViewerActions
import com.assistant.archie.tv.viewer.ViewerScreen
import com.assistant.archie.tv.visuals.VisualsActions
import com.assistant.archie.tv.visuals.VisualsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The fix pass after T5 in the real shell: Back closes the Viewer's options sheet and the Visuals
 * dialogs (B1), focus comes back to the card after a dialog (B3), and a screen's dialog owns the
 * accelerator keys while it shows (B6).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class FixPassFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private fun settle() {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun back() = compose.onRoot().performKeyInput { pressKey(Key.Back) }

    private fun focused(tag: String) = compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))

    private fun shell(controller: TvShellController, content: @androidx.compose.runtime.Composable (androidx.navigation3.runtime.NavKey) -> Unit) {
        compose.setContent { TvTheme(reduceMotion = true) { TvShell(TvFixtures.shell, controller, TvToasts(), entry = content) } }
        settle()
    }

    @Test fun backClosesTheViewerOptionsSheet() {
        val viewer = Viewer("visualizations/solar-dashboard.html", "Solar dashboard", pushed = true)
        val controller = TvShellController(mutableStateListOf(Visuals, viewer))
        shell(controller) { ViewerScreen(T3Fixtures.viewer, ViewerActions(), initialOptionsOpen = true) { Box(Modifier.fillMaxSize()) } }
        compose.onNodeWithTag("viewer-options").assertExists()
        back()
        settle()
        compose.onNodeWithTag("viewer-options").assertDoesNotExist()
        assertEquals("Back closed the sheet, not the Viewer", viewer, controller.nav.current)
    }

    /** Menu → [option] on the focused card (as the user does), then Back. */
    private fun dialogThenBack(option: String, tag: String) {
        val card = T3Fixtures.visuals.cards[1]
        val controller = TvShellController(mutableStateListOf(Visuals))
        shell(controller) { VisualsScreen(T3Fixtures.visuals, VisualsActions(linkOf = { "https://192.168.0.200/${it.path}" }), initialFocusPath = card.path) }
        focused("visual-${card.path}")
        controller.optionsOpen = true
        settle()
        controller.runOption(option)
        settle()
        compose.onNodeWithTag(tag).assertExists()
        back()
        settle()
        compose.onNodeWithTag(tag).assertDoesNotExist()
        assertEquals(Visuals, controller.nav.current)
        focused("visual-${card.path}")
    }

    @Test fun backClosesTheCopyLinkDialogAndFocusReturnsToTheCard() = dialogThenBack("link", "link-dialog")

    @Test fun backClosesTheRenameDialogAndFocusReturnsToTheCard() = dialogThenBack("rename", "rename-dialog")

    @Test fun aScreenDialogOwnsTheAcceleratorKeys() {
        val screenKeys = mutableListOf<TvKey>()
        var dialog by mutableStateOf(true)
        var voice = 0
        val controller = TvShellController(mutableStateListOf(Home), liveVoice = { voice++; true })
        shell(controller) {
            ScreenChromeEffect(ScreenChrome(title = "Home", keys = TvKeyHandler { k -> screenKeys += k; true }))
            TvButton("Behind", {}, Modifier.testTag("behind"))
            if (dialog) TvDialog("Delete?", onDismiss = { dialog = false }) { TvButton("Cancel", { dialog = false }, Modifier.testTag("cancel")) }
        }
        for (k in listOf(TvKey.MENU, TvKey.REWIND, TvKey.FAST_FORWARD, TvKey.LONG_OK)) assertTrue(controller.router.route(k))
        assertTrue(controller.router.route(TvKey.PLAY_PAUSE))
        assertTrue("nothing reached the screen behind the dialog", screenKeys.isEmpty())
        assertFalse("Menu over a dialog does not open the options sheet", controller.optionsOpen)
        assertEquals("Play/Pause is still live voice", 1, voice)

        dialog = false
        settle()
        controller.router.route(TvKey.REWIND)
        assertEquals(listOf(TvKey.REWIND), screenKeys)
    }

    @Test fun settingsMenuThenBackReturnsToThePaneRow() {
        val controller = TvShellController(mutableStateListOf(Settings))
        shell(controller) {
            com.assistant.archie.tv.settings.SettingsScreen(TvSettingsPageId.DISPLAY, onSelect = {}, focusPaneOnStart = true) {
                com.assistant.archie.tv.settings.DisplayPane(com.assistant.archie.tv.graph.TvDisplayPrefs(), com.assistant.archie.tv.settings.DisplayPaneActions())
            }
        }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) } // the pane's second row
        settle()
        focused("toggle-key-hints")
        controller.router.route(TvKey.MENU)
        settle()
        compose.onNodeWithTag("options-sheet").assertExists()
        back()
        settle()
        compose.onNodeWithTag("options-sheet").assertDoesNotExist()
        focused("toggle-key-hints")
    }
}
