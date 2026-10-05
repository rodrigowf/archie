package com.assistant.peripheral

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import com.assistant.core.audio.ports.AvailableOutputs
import com.assistant.core.model.AudioOutput
import com.assistant.core.model.DeviceSettings
import com.assistant.core.model.SavedServer
import com.assistant.core.network.DiscoveredServer
import com.assistant.core.voicehost.HostConnection
import com.assistant.peripheral.face.FaceScreen
import com.assistant.peripheral.face.FaceStates
import com.assistant.peripheral.settings.LiteSettings
import com.assistant.peripheral.settings.SettingsActions
import com.assistant.peripheral.settings.SettingsScreen
import com.assistant.peripheral.settings.SettingsViewState
import com.assistant.peripheral.ui.LitePalette
import com.assistant.peripheral.ui.dpi
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Lite goldens (spec 14 §6.4): every face state + Settings at the A300M's 360×640 dp hdpi
 * (540×960 px), dark only → src/test/screenshots/lite-*_w360dp_dark.png. Views are captured with
 * Robolectric native graphics, which needs SDK ≥ 26; SDK 28 is used (closest cached image to
 * Lollipop's widget look). The real API 21 rendering is checked on the A300M_API21 AVD.
 *
 *   flock /tmp/archie-locks/gradle.lock ./gradlew :app-lite:recordRoborazziDebug   record
 *   flock /tmp/archie-locks/gradle.lock ./gradlew :app-lite:verifyRoborazziDebug   compare
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-hdpi", application = Application::class)
class LiteGoldens {
    private fun host(): Activity {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        a.setTheme(R.style.Theme_ArchieLite)
        return a
    }

    @Test fun faces() {
        val activity = host()
        val ctx = ContextThemeWrapper(activity, R.style.Theme_ArchieLite)
        val palette = LitePalette(ctx)
        for (scene in FaceStates.scenes) {
            val face = FaceScreen(ctx, palette)
            // A fixed animation phase (orb fully expanded) so goldens are deterministic.
            face.shape.fixedPhaseMs = 1_300L
            face.bind(FaceStates.model(scene))
            activity.setContentView(face, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            shadowOf(Looper.getMainLooper()).idle()
            face.captureRoboImage("$DIR/lite-face-${scene.name}_w360dp_dark.png")
            assertEquals(540, face.width)
            assertFalse("goldens freeze the animator", face.shape.isAnimating)
        }
    }

    @Test fun armedFaceNeverAnimates_liveFaceDoes() {
        val activity = host()
        val ctx = ContextThemeWrapper(activity, R.style.Theme_ArchieLite)
        val face = FaceScreen(ctx, LitePalette(ctx))
        activity.setContentView(face)
        shadowOf(Looper.getMainLooper()).idle()
        face.bind(FaceStates.model(FaceStates.scenes.first { it.name == "ready" }))
        assertFalse(face.shape.isAnimating)
        face.bind(FaceStates.model(FaceStates.scenes.first { it.name == "listening" }))
        assertTrue(face.shape.isAnimating)
        face.visibility = View.GONE
        assertFalse("stops when not shown", face.shape.isAnimating)
    }

    @Test fun settings() {
        val activity = host()
        val ctx = ContextThemeWrapper(activity, R.style.Theme_ArchieLite)
        val palette = LitePalette(ctx)
        val screen = SettingsScreen(ctx, palette, object : SettingsActions {
            override fun back() = Unit
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun scan() = Unit
            override fun selectServer(url: String) = Unit
            override fun addServer() = Unit
            override fun removeServer(label: String, url: String) = Unit
            override fun openAccessibilitySettings() = Unit
            override fun openAssistSettings() = Unit
            override val settings: LiteSettings get() = error("not used")
        })
        screen.bind(
            SettingsViewState(
                settings = DeviceSettings(
                    serverUrl = "ws://192.168.0.200:80",
                    savedServers = listOf(SavedServer("Jetson", "ws://192.168.0.200:80"), SavedServer("Laptop", "ws://192.168.0.28:8765")),
                    micGainLevel = 1.2f, wakeWordMicGainLevel = 1.4f, talkSilenceSensitivity = 2.5f, echoDuckingGain = 0.035f,
                    audioOutput = AudioOutput.LOUDSPEAKER, enableButtonTrigger = true, talkWord = "my friend, hello my friend",
                ),
                connection = HostConnection.CONNECTED,
                serverName = "jetson",
                discovered = listOf(DiscoveredServer("192.168.0.31", 8765, secure = false)),
                scanMessage = "Found 2 on 192.168.0.x",
                outputs = AvailableOutputs(bluetoothCallAudio = false, bluetoothMedia = false, wired = true),
                accessibilityEnabled = true,
                versionName = "2.0.0",
                versionCode = 100,
            ),
        )
        activity.setContentView(screen)
        shadowOf(Looper.getMainLooper()).idle()
        screen.captureRoboImage("$DIR/lite-settings_w360dp_dark.png")
        for ((i, y) in listOf(640, 1280).withIndex()) {
            screen.scrollTo(0, context(screen).dpi(y.toFloat()))
            shadowOf(Looper.getMainLooper()).idle()
            screen.captureRoboImage("$DIR/lite-settings-${i + 2}_w360dp_dark.png")
        }
    }

    private fun context(v: View) = v.context

    companion object {
        const val DIR = "src/test/screenshots"
    }
}
