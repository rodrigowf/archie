package com.assistant.archie.feature.visuals

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.assistant.archie.feature.visuals.ui.VisualCard
import com.assistant.archie.feature.visuals.ui.VisualScreen
import com.assistant.archie.feature.visuals.ui.VisualsScreen
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.network.ApiResult
import com.assistant.core.protocol.CastProbeDto
import com.assistant.core.protocol.CastResponse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B-07 DoD hidden-action UI test (spec 14 §4.2): "Show on TV" is absent — not disabled — unless the
 * capability is Available, in the viewer's top bar, the row menu and the inline card. Plus the cast
 * snackbar copy and the list's ⋮ actions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w443dp-h986dp-xxhdpi", application = Application::class)
class VisualsUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val path = "energy/weekly-energy.html"

    private fun unavailable() = FakeVisualsDeps(app, probe = ApiResult.Ok(CastProbeDto(false, "No Fire TV connected over adb")))

    @Test fun `viewer hides Show on TV when unavailable`() {
        val deps = unavailable()
        compose.setContent { ArchieTheme { VisualScreen(deps, path, onBack = {}) } }
        compose.onNodeWithText("Weekly energy usage").assertIsDisplayed()
        compose.onNodeWithTag("show-on-tv").assertDoesNotExist()
        compose.onNodeWithText("Show on TV").assertDoesNotExist()
    }

    @Test fun `viewer hides Show on TV on a backend without BX-2 (405) and while unknown`() {
        val deps = FakeVisualsDeps(app, probe = ApiResult.HttpError(405, null, html = true))
        compose.setContent { ArchieTheme { VisualScreen(deps, path, onBack = {}) } }
        compose.onNodeWithTag("show-on-tv").assertDoesNotExist()
        deps.probe = ApiResult.NetworkError(java.io.IOException("down"))
        deps.cast.onConnected()
        compose.onNodeWithTag("show-on-tv").assertDoesNotExist()
    }

    @Test fun `viewer shows Show on TV when available and casting reports the title`() {
        val deps = FakeVisualsDeps(app)
        compose.setContent { ArchieTheme { VisualScreen(deps, path, onBack = {}) } }
        compose.onNodeWithTag("show-on-tv").assertIsDisplayed().performClick()
        compose.onNodeWithText("Showing “Weekly energy usage” on TV").assertIsDisplayed()
        assertEquals(listOf(path), deps.casts)
    }

    @Test fun `a failed cast shows the server's message verbatim`() {
        val deps = FakeVisualsDeps(app, castResult = ApiResult.Ok(CastResponse(false, "No Fire TV connected over adb")))
        compose.setContent { ArchieTheme { VisualScreen(deps, path, onBack = {}) } }
        compose.onNodeWithTag("show-on-tv").performClick()
        compose.onNodeWithText("No Fire TV connected over adb").assertIsDisplayed()
    }

    @Test fun `row menu lists Show on TV only when available`() {
        val off = unavailable()
        compose.setContent { ArchieTheme { VisualsScreen(off, onBack = {}, onOpen = {}) } }
        compose.onNodeWithTag("visual-row-menu:$path").performClick()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Open in browser").assertIsDisplayed()
        compose.onNodeWithText("Copy link").assertIsDisplayed()
        compose.onNodeWithText("Show on TV").assertDoesNotExist()
    }

    @Test fun `row menu with cast available, and opening a row`() {
        val deps = FakeVisualsDeps(app)
        val opened = mutableListOf<String>()
        compose.setContent { ArchieTheme { VisualsScreen(deps, onBack = {}, onOpen = { opened += it }) } }
        compose.onNodeWithTag("visual-row-menu:$path").performClick()
        compose.onNodeWithText("Show on TV").assertIsDisplayed().performClick()
        assertEquals(listOf(path), deps.casts)
        compose.onNodeWithTag("visual-row:dashboard.html").performClick()
        assertEquals(listOf("dashboard.html"), opened)
        compose.onNodeWithText("public · ", substring = true).assertIsDisplayed()
    }

    @Test fun `inline card hides Show on TV unless available`() {
        val off = unavailable()
        compose.setContent { ArchieTheme { VisualCard(off, path, "Weekly energy usage", null, onOpen = {}, onMessage = {}) } }
        compose.onNodeWithText("Open").assertIsDisplayed()
        compose.onNodeWithTag("show-on-tv").assertDoesNotExist()
    }

    @Test fun `inline card casts when available`() {
        val deps = FakeVisualsDeps(app)
        val messages = mutableListOf<String>()
        compose.setContent { ArchieTheme { VisualCard(deps, path, "Weekly energy usage", null, onOpen = {}, onMessage = { messages += it }) } }
        compose.onNodeWithTag("show-on-tv").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Showing “Weekly energy usage” on TV"), messages)
    }
}
