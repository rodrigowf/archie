package com.assistant.archie.tv.settings

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.assistant.archie.tv.control.TvControlState
import com.assistant.archie.tv.graph.TvDisplayPrefs
import com.assistant.archie.tv.shell.SettingsPage
import com.assistant.archie.tv.shell.TvFixtures
import com.assistant.archie.tv.shell.TvSettingsPageId
import com.assistant.archie.tv.shell.TvShell
import com.assistant.archie.tv.shell.TvShellController
import com.assistant.archie.tv.shell.TvToasts
import com.assistant.archie.tv.theme.TvTextSize
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.core.data.ConnectionStatus
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Roborazzi goldens of Settings (WP T5; mockup 14) at 960×540 dp → src/test/screenshots/settings-*.png:
 * the list + each page's pane, focus in the pane (the list scene: focus on the list). State-driven.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class SettingsGoldens {
    @get:Rule
    val compose = createComposeRule()

    private fun scene(name: String, page: TvSettingsPageId, focusPane: Boolean = true, after: () -> Unit = {}, pane: @Composable () -> Unit) {
        val controller = TvShellController(mutableStateListOf(SettingsPage(page)))
        compose.setContent {
            TvTheme(reduceMotion = true) {
                TvShell(TvFixtures.shell, controller, TvToasts()) {
                    SettingsScreen(page, onSelect = {}, focusPaneOnStart = focusPane) { pane() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        after()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/$name.png")
    }

    private val connection = connectionUi(
        ConnectionStatus(serverUrl = "wss://192.168.0.200:443", serverLabel = "jetson", phase = ConnectionStatus.Phase.CONNECTED),
        mapOf("192.168.0.200:443" to "q83vEjRWeJq8"),
    )

    @Test fun list() = scene("settings-list", TvSettingsPageId.CONNECTION, focusPane = false) { ConnectionPane(connection, ConnectionPaneActions()) }

    @Test fun connection() = scene("settings-connection", TvSettingsPageId.CONNECTION) { ConnectionPane(connection, ConnectionPaneActions()) }

    private val voiceUi = VoicePaneUi(
        choices = TvVoiceSettings.choices(SettingsFixtures.config, SettingsFixtures.state),
        idle = LiveIdle.choice(120),
    )

    @Test fun voice() = scene("settings-voice", TvSettingsPageId.VOICE) { VoicePane(voiceUi, VoicePaneActions()) }

    @Test fun voicePicker() = scene(
        "settings-voice-picker", TvSettingsPageId.VOICE,
        after = { compose.onNodeWithTag("choice-${TvVoiceSettings.VOICE}").performSemanticsAction(SemanticsActions.OnClick) },
    ) { VoicePane(voiceUi, VoicePaneActions()) }

    @Test fun display() = scene("settings-display", TvSettingsPageId.DISPLAY) {
        DisplayPane(TvDisplayPrefs(textSize = TvTextSize.NORMAL, showKeyHints = true, reduceMotion = false), DisplayPaneActions())
    }

    @Test fun tvControl() = scene("settings-tv-control", TvSettingsPageId.TV_CONTROL) {
        ControlPane(
            ControlPaneUi(TvSettingsStatus.control(TvControlState.Ready), deviceName = "Living room TV", systemName = "Fire TV", allowOpen = true, openedToasts = true, canRetry = false),
            ControlPaneActions(),
        )
    }

    @Test fun tvControlRejected() = scene("settings-tv-control-rejected", TvSettingsPageId.TV_CONTROL) {
        ControlPane(
            ControlPaneUi(
                TvSettingsStatus.control(TvControlState.Rejected("invalid token")), deviceName = "", systemName = "Fire TV",
                allowOpen = false, openedToasts = true, canRetry = true, tokenSet = true,
            ),
            ControlPaneActions(),
        )
    }

    @Test fun server() = scene("settings-server", TvSettingsPageId.SERVER) {
        ServerPane(ServerPaneUi(TvServerSettings.sections(SettingsFixtures.config, SettingsFixtures.state, SettingsFixtures.accounts), host = "jetson"), ServerPaneActions())
    }

    @Test fun about() = scene("settings-about", TvSettingsPageId.ABOUT) {
        AboutPane(AboutPaneUi("0.1.0 (1)", "Release", "com.assistant.archie.tv", "jetson · Connected"), onRefresh = {}, licensesOpen = true)
    }
}
