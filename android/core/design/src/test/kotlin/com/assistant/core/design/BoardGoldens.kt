package com.assistant.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.assistant.core.design.catalog.ComponentBoards
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.design.theme.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Roborazzi goldens of the component sheet (spec 14 §6.4): every board at Compact, Medium and
 * Expanded, dark and light → src/test/screenshots/<scene>_<size>_<theme>.png.
 *
 * Widths and densities are the spec's golden sets (Compact w443dp xxhdpi = the POCO, Medium
 * w700dp, Expanded w1280dp). Heights are taller than the devices' so a whole board fits in the
 * window: these are component sheets, not screens, and only the width decides the size class.
 *
 *   flock /tmp/archie-locks/gradle.lock ./gradlew :core:design:recordRoborazziDebug   record
 *   flock /tmp/archie-locks/gradle.lock ./gradlew :core:design:verifyRoborazziDebug   compare
 */
abstract class BoardGoldens(private val scene: String, private val size: String) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun board() {
        val content = ComponentBoards.first { it.first == scene }.second
        var mode by mutableStateOf(ThemeMode.Dark)
        compose.setContent {
            ArchieTheme(mode = mode, reduceMotion = true) {
                Box(Modifier.testTag(TAG)) { content() }
            }
        }
        for (theme in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            mode = theme
            compose.waitForIdle()
            compose.onNodeWithTag(TAG).captureRoboImage("$DIR/${scene}_${size}_${theme.name.lowercase()}.png")
        }
    }

    companion object {
        const val TAG = "board"
        const val DIR = "src/test/screenshots"
        fun scenes(): List<Array<Any>> = ComponentBoards.map { arrayOf<Any>(it.first) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w443dp-h2000dp-xxhdpi")
class CompactBoardGoldens(scene: String) : BoardGoldens(scene, "compact") {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = scenes()
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w700dp-h1600dp")
class MediumBoardGoldens(scene: String) : BoardGoldens(scene, "medium") {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = scenes()
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1280dp-h1200dp")
class ExpandedBoardGoldens(scene: String) : BoardGoldens(scene, "expanded") {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = scenes()
    }
}
