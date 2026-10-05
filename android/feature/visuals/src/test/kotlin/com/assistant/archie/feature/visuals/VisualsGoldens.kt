package com.assistant.archie.feature.visuals

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.assistant.archie.feature.visuals.ui.VisualCard
import com.assistant.archie.feature.visuals.ui.VisualScreen
import com.assistant.archie.feature.visuals.ui.VisualViewer
import com.assistant.archie.feature.visuals.ui.VisualsListPane
import com.assistant.archie.feature.visuals.ui.VisualsScreen
import com.assistant.core.data.LoadState
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.design.theme.ThemeMode
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/*
 * Roborazzi goldens of B-07 Visuals (spec 14 §6.4 scene `visuals-list`, plus phone (h) and the
 * tablet inline card) on the POCO size, dark and light. Under Robolectric the WebView draws nothing,
 * so (h) shows the chrome (top bar, Show on TV, ⋮ menu) over the empty page area; the real page is
 * in the live emulator screenshot (docs/frontend-refactor/audit/screenshots/b07-live-*).
 *
 *   flock /tmp/archie-locks/gradle.lock ./gradlew :feature:visuals:recordRoborazziDebug
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w443dp-h986dp-xxhdpi", application = Application::class)
class VisualsGoldens {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val now = Instant.parse("2026-10-03T15:00:00Z")

    private fun scene(name: String, before: () -> Unit = {}, content: @Composable () -> Unit) {
        var mode by mutableStateOf(ThemeMode.Dark)
        compose.setContent { ArchieTheme(mode = mode, reduceMotion = true) { content() } }
        compose.waitForIdle()
        before()
        for (theme in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            mode = theme
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            captureScreenRoboImage("src/test/screenshots/${name}_${theme.name.lowercase()}.png")
        }
    }

    /** `visuals-list`: search, rows with "folder · age", ⋮ per row. */
    @Test fun visualsList() {
        val deps = FakeVisualsDeps(app)
        scene("b07-visuals-list") { VisualsScreen(deps, onBack = {}, onOpen = {}, now = now) }
    }

    /** Phone (h): ✕, title over "Visuals · updated …", Show on TV, and the ⋮ menu open. */
    @Test fun visualScreen() {
        val deps = FakeVisualsDeps(app)
        scene("b07-visual-screen", before = { compose.onNodeWithTag("visual-menu").performClick() }) {
            VisualScreen(deps, "energy/weekly-energy.html", onBack = {}, now = now)
        }
    }

    /** Tablet "Visuals inline": the card with Open + Show on TV, and without the cast action. */
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    @Test fun visualCard() {
        scene("b07-visual-card") {
            Column(
                Modifier.fillMaxSize().background(ArchieTheme.colors.surface).padding(32.dp).widthIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("It's on the living-room TV. Want a monthly comparison next to it?", style = ArchieTheme.typography.bodyLarge, color = ArchieTheme.colors.onSurface)
                VisualCard("Weekly energy usage", null, onOpen = {}, onShowOnTv = {}, status = "Visual · showing on Living-room TV")
                VisualCard("Weekly energy usage", "2026-10-03T13:00:00+00:00", onOpen = {}, onShowOnTv = null, now = now)
            }
        }
    }

    /** Expanded: the Visuals list pane beside a visual opened as a tab (slim bar over the WebView). */
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    @Test fun visualsExpanded() {
        val deps = FakeVisualsDeps(app)
        scene("b07-visuals-expanded") {
            Row(Modifier.fillMaxSize().background(ArchieTheme.colors.surfaceContainerLow)) {
                VisualsListPane(LoadState(VisualFixtures.items), true, {}, {}, "energy/weekly-energy.html", { _, _ -> }, Modifier.width(360.dp), now = now)
                VisualViewer(deps, "energy/weekly-energy.html", Modifier.weight(1f), now = now)
            }
        }
    }
}
