package com.assistant.archie.feature.memory

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.assistant.archie.feature.memory.ui.MemoryDocumentContent
import com.assistant.archie.feature.memory.ui.MemoryDocumentScreen
import com.assistant.archie.feature.memory.ui.MemoryPane
import com.assistant.archie.feature.memory.ui.MemoryScreen
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
 * Roborazzi goldens of B-07 Memory (spec 14 §6.4 scenes `memory-tree`, `memory-doc`) on the POCO
 * size, dark and light → src/test/screenshots/<scene>_<theme>.png. Compared by hand with the
 * approved mockups phone (g1) tree and (g2) document (docs/frontend-refactor/audit/screenshots/b07-*).
 *
 *   flock /tmp/archie-locks/gradle.lock ./gradlew :feature:memory:recordRoborazziDebug
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w443dp-h986dp-xxhdpi", application = Application::class)
class MemoryGoldens {
    @get:Rule val compose = createComposeRule()

    private fun scene(name: String, awaitText: String? = null, content: @Composable () -> Unit) {
        var mode by mutableStateOf(ThemeMode.Dark)
        compose.setContent { ArchieTheme(mode = mode, reduceMotion = true) { content() } }
        // The markdown is parsed off the main thread; capture only once it is on screen.
        if (awaitText != null) compose.waitUntil(10_000) { compose.onAllNodesWithText(awaitText).fetchSemanticsNodes().isNotEmpty() }
        for (theme in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            mode = theme
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            captureScreenRoboImage("src/test/screenshots/${name}_${theme.name.lowercase()}.png")
        }
    }

    private val doc = "assistant/architecture/voice_subsystem.md"
    private val open = setOf("assistant", "assistant/architecture")
    private val now = java.time.Instant.parse("2026-10-04T12:00:00Z")
    private fun deps() = FakeMemoryDeps(docs = mutableMapOf(doc to MemoryFixtures.VOICE_DOC))

    /** Phone (g1): search over 138 files, MEMORY.md first, assistant + architecture open, counts. */
    @Test fun memoryTree() {
        val deps = deps()
        scene("b07-memory-tree") { MemoryScreen(deps, onBack = {}, onOpenDoc = {}, selectedPath = doc, initialExpanded = open) }
    }

    /** Phone (g2): the document with its frontmatter chip, title, modified line, list, code and links. */
    @Test fun memoryDoc() {
        val deps = deps()
        scene("b07-memory-doc", awaitText = "Lifecycle") { MemoryDocumentScreen(deps, doc, onBack = {}, onOpenDoc = {}, now = now) }
    }

    /** Expanded (w1280dp): the Memory list pane beside the document opened as a tab, its row selected. */
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    @Test fun memoryExpanded() {
        val deps = deps()
        scene("b07-memory-expanded", awaitText = "Lifecycle") {
            Row(Modifier.fillMaxSize().background(ArchieTheme.colors.surfaceContainerLow)) {
                MemoryPane(deps, onOpenDoc = {}, selectedPath = doc, modifier = Modifier.width(360.dp), initialExpanded = open)
                MemoryDocumentContent(deps, doc, onOpenDoc = {}, modifier = Modifier.weight(1f), now = now)
            }
        }
    }
}
