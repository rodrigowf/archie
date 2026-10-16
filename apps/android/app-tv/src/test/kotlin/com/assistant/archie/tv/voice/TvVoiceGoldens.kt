package com.assistant.archie.tv.voice

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation3.runtime.NavKey
import com.assistant.archie.tv.firstrun.MicCheckActions
import com.assistant.archie.tv.firstrun.MicCheckStep
import com.assistant.archie.tv.home.HomeActions
import com.assistant.archie.tv.home.HomeScreen
import com.assistant.archie.tv.settings.RemoteMicActions
import com.assistant.archie.tv.settings.RemoteMicPage
import com.assistant.archie.tv.shell.Connect
import com.assistant.archie.tv.shell.Home
import com.assistant.archie.tv.shell.SettingsPage
import com.assistant.archie.tv.shell.TvFixtures
import com.assistant.archie.tv.shell.TvSettingsPageId
import com.assistant.archie.tv.shell.TvShell
import com.assistant.archie.tv.shell.TvShellController
import com.assistant.archie.tv.shell.TvToasts
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceSessionState
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Roborazzi goldens of the voice UI (WP T4; mockups 7, 8, 9, 14, 15) at 960×540 dp →
 * src/test/screenshots/voice-*.png. State-driven: the overlay layer over the shell, no graph.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class TvVoiceGoldens {
    @get:Rule
    val compose = createComposeRule()

    private fun scene(name: String, root: NavKey, voice: VoiceSessionState = VoiceSessionState(), overlay: OverlayUi = OverlayUi.None, content: @Composable (NavKey) -> Unit) {
        val controller = TvShellController(mutableStateListOf(root))
        compose.setContent {
            TvTheme(reduceMotion = true) {
                Box(Modifier.fillMaxSize()) {
                    TvShell(TvFixtures.shell.copy(voice = voice), controller, TvToasts(), entry = content)
                    VoiceOverlayLayer(ui = overlay, levels = LEVELS, nowMs = 3_400)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/$name.png")
    }

    private val home: @Composable (NavKey) -> Unit = { HomeScreen(TvFixtures.home, HomeActions()) }

    @Test fun hold() = scene(
        "voice-hold", Home,
        voice = VoiceChip.compose(VoiceSessionState(), TvVoiceOverlay.Holding(VoiceDestination.VoiceMessage, "To Archie", 0), false),
        overlay = OverlayUi.Hold("To Archie · Plan the TV app", 0),
        content = home,
    )

    @Test fun live() = scene(
        "voice-live", Home,
        voice = VoiceSessionState(SessionPhase.SPEAKING, isOwner = true),
        overlay = OverlayUi.Live(
            LivePanelState(
                status = liveStatus(SessionPhase.SPEAKING, holding = false),
                user = "And how much did we export yesterday?",
                assistant = "Yesterday you exported 1.6 kWh, so today is already a bit better.",
                level = 0.6f,
            ),
        ),
        content = home,
    )

    @Test fun dictation() = scene(
        "voice-dictation", Home,
        overlay = OverlayUi.Dictation("run the tests again and show me the failures"),
        content = home,
    )

    @Test fun remoteMicSettings() = scene("voice-settings-remote-mic", SettingsPage(TvSettingsPageId.REMOTE_MIC)) {
        RemoteMicPage(MicCheckUi(MicStatus.READY, level = 0.64f, canPlayBack = true, lastDurationMs = 2_400, heard = true), cues = true, actions = RemoteMicActions())
    }

    @Test fun remoteMicAlexa() = scene("voice-settings-remote-mic-alexa", SettingsPage(TvSettingsPageId.REMOTE_MIC)) {
        RemoteMicPage(MicCheckUi(MicStatus.ALEXA_LISTENS), cues = false, actions = RemoteMicActions())
    }

    @Test fun firstRunMicCheck() = scene("voice-first-run-mic", Connect(firstRun = true)) {
        MicCheckStep(MicCheckUi(MicStatus.NO_PERMISSION), MicCheckActions())
    }

    private companion object {
        val LEVELS = listOf(0.1f, 0.3f, 0.55f, 0.8f, 0.5f, 0.7f, 0.9f, 0.6f, 0.3f, 0.75f, 0.85f, 0.5f, 0.2f, 0.45f, 0.7f, 0.35f, 0.15f, 0.1f)
    }
}
