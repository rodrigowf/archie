package com.assistant.archie.shell

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation3.runtime.NavKey
import com.assistant.core.data.ItemKey
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

/*
 * Roborazzi goldens of the shell (spec 14 §6.4) at the spec's golden sizes — Compact w443dp xxhdpi
 * (the POCO), Medium w700dp, Expanded w1280dp — dark and light → src/test/screenshots/
 * <scene>_<size>_<theme>.png. Scenes reproduce the approved mockups: phone (a)/(b)/(c), tablet, desktop.
 *
 *   flock /tmp/archie-locks/gradle.lock ./gradlew :app-main:recordRoborazziDebug   record
 *   flock /tmp/archie-locks/gradle.lock ./gradlew :app-main:verifyRoborazziDebug   compare
 */
@OptIn(ExperimentalRoborazziApi::class)
abstract class ShellGoldenBase(private val size: String) {
    @get:Rule
    val compose = createComposeRule()

    protected fun scene(name: String, active: ItemKey = ItemKey.Archie, overlays: ShellOverlays = ShellOverlays()) {
        var mode by mutableStateOf(ThemeMode.Dark)
        val backStack = mutableStateListOf<NavKey>(Workspace)
        val destinations = ShellFixtures.Destinations()
        compose.setContent {
            ArchieTheme(mode = mode, reduceMotion = true) {
                ArchieShell(ShellFixtures.state(active), {}, backStack, destinations, overlays = overlays)
            }
        }
        for (theme in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            mode = theme
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            captureScreenRoboImage("$DIR/${name}_${size}_${theme.name.lowercase()}.png")
        }
    }

    companion object {
        const val DIR = "src/test/screenshots"
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w443dp-h986dp-xxhdpi", application = Application::class)
class CompactShellGoldens : ShellGoldenBase("compact") {
    /** Phone (a): Archie conversation, title + "waiting for approval" subtitle, no bottom bar. */
    @Test fun workspace() = scene("shell-workspace")

    /** Phone (b): navigation drawer with Open now, history by date and the footer. */
    @Test fun drawer() = scene("drawer", overlays = ShellOverlays(drawerOpen = true))

    /** Phone (c): session switcher sheet. */
    @Test fun switcher() = scene("session-switcher", overlays = ShellOverlays(switcherOpen = true))
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w700dp-h1000dp", application = Application::class)
class MediumShellGoldens : ShellGoldenBase("medium") {
    /** Tablet: rail + workspace with tabs; the list pane is an overlay, closed. */
    @Test fun workspace() = scene("shell-workspace")

    /** Tablet with the list pane overlay open. */
    @Test fun listOverlay() = scene("shell-list-overlay", overlays = ShellOverlays(listOverlayOpen = true))
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp", application = Application::class)
class ExpandedShellGoldens : ShellGoldenBase("expanded") {
    /** Desktop: rail + 320 dp list pane + workspace, agent session active in the tab strip. */
    @Test fun workspace() = scene("shell-workspace", active = ShellFixtures.agentKey)
}
