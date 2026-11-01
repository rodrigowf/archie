package com.assistant.archie.tv.shell

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.assistant.archie.tv.firstrun.ConnectActions
import com.assistant.archie.tv.firstrun.ConnectScreen
import com.assistant.archie.tv.home.HomeActions
import com.assistant.archie.tv.home.HomeScreen
import com.assistant.archie.tv.settings.DisplayPane
import com.assistant.archie.tv.settings.DisplayPaneActions
import com.assistant.archie.tv.settings.SettingsScreen
import com.assistant.archie.tv.graph.TvDisplayPrefs
import com.assistant.archie.tv.theme.TvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Back on the T5-fixed overlays (spec 15 §3.3): a focus trap must not swallow Back (Compose turns
 * Back into a focus exit). The options sheet and the certificate dialog close; Settings' pane hands
 * focus back to its list row.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class OverlayBackTest {
    @get:Rule
    val compose = createComposeRule()

    private fun settle() {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun back() = compose.onRoot().performKeyInput { pressKey(Key.Back) }

    @Test fun backClosesTheOptionsSheet() {
        val controller = TvShellController(mutableStateListOf(Home))
        compose.setContent {
            TvTheme(reduceMotion = true) {
                TvShell(TvFixtures.shell, controller, TvToasts(), overlays = TvShellOverlays(optionsOpen = true)) { HomeScreen(TvFixtures.home, HomeActions()) }
            }
        }
        settle()
        compose.onNodeWithTag("options-sheet").assertExists()
        back()
        settle()
        assertFalse(controller.optionsOpen)
        compose.onNodeWithTag("options-sheet").assertDoesNotExist()
    }

    @Test fun backCancelsTheCertificateDialog() {
        var cancelled = 0
        var trust by mutableStateOf(true)
        compose.setContent {
            TvTheme(reduceMotion = true) {
                ConnectScreen(
                    TvFixtures.connect.copy(trust = if (trust) TvFixtures.trust else null),
                    firstRun = true,
                    actions = ConnectActions(cancelTrust = { cancelled++; trust = false }),
                )
            }
        }
        settle()
        compose.onNodeWithTag("trust-dialog").assertExists()
        back()
        settle()
        assertEquals(1, cancelled)
        compose.onNodeWithTag("trust-dialog").assertDoesNotExist()
    }

    @Test fun backInTheSettingsPaneReturnsToTheList() {
        compose.setContent {
            TvTheme(reduceMotion = true) {
                SettingsScreen(TvSettingsPageId.DISPLAY, onSelect = {}, focusPaneOnStart = true) {
                    DisplayPane(TvDisplayPrefs(), DisplayPaneActions())
                }
            }
        }
        settle()
        compose.onNodeWithTag("choice-text-size").assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
        back()
        settle()
        compose.onNodeWithTag("settings-item-display").assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
    }
}
