package com.assistant.archie.tv.shell

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation3.runtime.NavKey
import com.assistant.archie.feature.settings.ConnectionUiState
import com.assistant.archie.feature.settings.ServerRow
import com.assistant.archie.feature.settings.TrustRequest
import com.assistant.archie.tv.firstrun.ConnectActions
import com.assistant.archie.tv.firstrun.ConnectScreen
import com.assistant.archie.tv.home.HomeActions
import com.assistant.archie.tv.home.HomeCard
import com.assistant.archie.tv.home.HomeScreen
import com.assistant.archie.tv.home.HomeUiState
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.network.CertificateInfo
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Roborazzi goldens of the TV shell at the AFTKM's 960×540 dp (1920×1080 px @ xhdpi, television
 * UI mode) → src/test/screenshots/<scene>.png. Scenes are state-driven (no graph, no network).
 *
 *   shared/scripts/heavy.sh --gradle apps/android :app-tv:recordRoborazziDebug   record
 *   shared/scripts/heavy.sh --gradle apps/android :app-tv:verifyRoborazziDebug   compare
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class TvShellGoldens {
    @get:Rule
    val compose = createComposeRule()

    private fun scene(
        name: String,
        root: NavKey,
        overlays: TvShellOverlays = TvShellOverlays(),
        toast: String? = null,
        content: @androidx.compose.runtime.Composable (NavKey) -> Unit,
    ) {
        val backStack = mutableStateListOf(root)
        val controller = TvShellController(backStack)
        val toasts = TvToasts()
        if (toast != null) toasts.show(toast, seconds = 60)
        compose.setContent {
            TvTheme(reduceMotion = true) {
                TvShell(TvFixtures.shell, controller, toasts, overlays = overlays, entry = content)
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        captureScreenRoboImage("$DIR/$name.png")
    }

    @Test fun home() = scene("home", Home, toast = "Opened by Archie: Solar dashboard") { HomeScreen(TvFixtures.home, HomeActions()) }

    @Test fun drawerExpanded() = scene("drawer-expanded", Home, TvShellOverlays(drawerOpen = true)) { HomeScreen(TvFixtures.home, HomeActions()) }

    @Test fun optionsSheet() = scene("options-sheet", Home, TvShellOverlays(optionsOpen = true)) { HomeScreen(TvFixtures.home, HomeActions()) }

    @Test fun connect() = scene("connect", Connect(firstRun = true)) { ConnectScreen(TvFixtures.connect, firstRun = true, actions = ConnectActions()) }

    @Test fun connectTrust() = scene("connect-trust", Connect(firstRun = true)) {
        ConnectScreen(TvFixtures.connect.copy(trust = TvFixtures.trust), firstRun = true, actions = ConnectActions())
    }

    companion object {
        const val DIR = "src/test/screenshots"
    }
}

object TvFixtures {
    val shell = TvShellState(
        connection = ConnectionStatus(serverUrl = "wss://192.168.0.200:443", serverLabel = "jetson", phase = ConnectionStatus.Phase.CONNECTED),
    )

    val home = HomeUiState(
        archieTitle = "Plan the TV app",
        archieDetail = "Ready · 14 turns — “Done. It's on screen.”",
        openNow = listOf(
            HomeCard("Archie TV — T0 foundations", "Working · 6 turns", Conversation(localId = "a1"), busy = true),
            HomeCard("History search eval", "Idle · 22 turns", Conversation(localId = "a2")),
        ),
        visuals = listOf(
            HomeCard("Solar dashboard", "Updated 5 min ago", Viewer("visualizations/solar.html")),
            HomeCard("TV app mockups", "Updated 2 h ago", Viewer("visualizations/tv-mockups/index.html")),
            HomeCard("Launch plan", "Updated yesterday", Viewer("visualizations/launch.html")),
            HomeCard("Tarot app", "Updated 3 days ago", Viewer("visualizations/tarot.html")),
        ),
        memory = listOf(
            HomeCard("rodrigo personal context", "people/rodrigo", MemoryDoc("people/rodrigo/rodrigo_personal_context.md")),
            HomeCard("personal projects index", "projects", MemoryDoc("projects/personal_projects_index.md")),
        ),
        recent = listOf(
            HomeCard("Qwen self-interrupt on the A300M", "Archie · 2 h ago", Conversation(sdkId = "s1", archie = true)),
        ),
    )

    val connect = ConnectionUiState(
        status = ConnectionStatus(serverUrl = "wss://192.168.0.200:443", serverLabel = "192.168.0.200", phase = ConnectionStatus.Phase.CONNECTED),
        rows = listOf(
            ServerRow("wss://192.168.0.200:443", "192.168.0.200", "wss://192.168.0.200:443", saved = false, current = true, discovered = true),
            ServerRow("ws://192.168.0.28:8765", "192.168.0.28", "ws://192.168.0.28:8765 · found on this network", saved = false, current = false, discovered = true),
        ),
    )

    val trust = TrustRequest(
        url = "wss://192.168.0.200:443",
        label = "192.168.0.200",
        hostPort = "192.168.0.200:443",
        certificate = CertificateInfo(
            subject = "CN=jetson",
            issuer = "CN=jetson",
            notBeforeMillis = 1_760_000_000_000,
            notAfterMillis = 2_075_000_000_000,
            spkiSha256 = "q83vEjRWeJq83vEjRWeJq83vEjRWeJq83vEjRWeJq80=",
        ),
        changed = false,
    )
}
