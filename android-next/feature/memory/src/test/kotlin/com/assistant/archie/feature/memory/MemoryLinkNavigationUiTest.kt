package com.assistant.archie.feature.memory

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.assistant.archie.feature.memory.ui.MemoryDocumentScreen
import com.assistant.archie.feature.memory.ui.MemoryScreen
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.design.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec 14 §6.3 `MemoryLinkNavigationUiTest`: tapping `../folder/x.md` opens that document; Back
 * returns. Plus the tree (open a file, fold a folder, search), the frontmatter chip, "Not found" +
 * "Open anyway", external links, and the error + Retry state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w443dp-h986dp-xxhdpi", application = Application::class)
class MemoryLinkNavigationUiTest {
    @get:Rule val compose = createComposeRule()

    private val deps = FakeMemoryDeps(
        docs = mutableMapOf(
            "assistant/architecture/voice_subsystem.md" to "# Voice\n\n[ssh.md](../infrastructure/infra_1.md)\n\n[web](https://example.com/a)\n\n[gone.md](../nowhere/gone.md)\n",
            "assistant/infrastructure/infra_1.md" to "# Infra one\n\nBody of infra one.\n",
            "assistant/nowhere/gone.md" to "# Stale\n",
        ),
    )

    /** Markdown is parsed off the main thread: wait for [text] to be composed. */
    private fun await(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** A back stack like the shell's Compact NavDisplay: links push, Back pops. */
    @Composable
    private fun Stack(stack: MutableList<String>) {
        ArchieTheme(mode = ThemeMode.Dark, reduceMotion = true) {
            val top = stack.last()
            MemoryDocumentScreen(deps, top, onBack = { stack.removeAt(stack.lastIndex) }, onOpenDoc = { stack.add(it) })
        }
    }

    @Test fun `relative link opens the target document and Back returns`() {
        val stack = mutableStateListOf("assistant/architecture/voice_subsystem.md")
        compose.setContent { Stack(stack) }
        await("ssh.md")
        compose.onNodeWithText("Voice").assertIsDisplayed()
        compose.onNodeWithText("ssh.md").performClick()
        compose.waitForIdle()
        assertEquals("assistant/infrastructure/infra_1.md", stack.last())
        await("Body of infra one.")
        compose.onNodeWithText("Body of infra one.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertEquals(listOf("assistant/architecture/voice_subsystem.md"), stack.toList())
        await("ssh.md")
        compose.onNodeWithText("Voice").assertIsDisplayed()
    }

    @Test fun `a link missing from the tree asks first, Open anyway opens it`() {
        val stack = mutableStateListOf("assistant/architecture/voice_subsystem.md")
        compose.setContent { Stack(stack) }
        await("gone.md")
        compose.onNodeWithText("gone.md").performClick()
        compose.onNodeWithText("Not found: assistant/nowhere/gone.md").assertIsDisplayed()
        assertEquals(1, stack.size)
        compose.onNodeWithText("Open anyway").performClick()
        compose.waitForIdle()
        assertEquals("assistant/nowhere/gone.md", stack.last())
    }

    @Test fun `external links leave the app`() {
        val stack = mutableStateListOf("assistant/architecture/voice_subsystem.md")
        compose.setContent { Stack(stack) }
        await("web")
        compose.onNodeWithText("web").performClick()
        assertEquals(listOf("https://example.com/a"), deps.external)
    }

    @Test fun `Open raw uses the encoded memory URL on the server`() {
        val stack = mutableStateListOf("assistant/architecture/voice_subsystem.md")
        compose.setContent { Stack(stack) }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Open raw").performClick()
        assertEquals(listOf("http://192.168.0.200/memory/assistant/architecture/voice_subsystem.md"), deps.external)
    }

    @Test fun `frontmatter folds into a chip that expands in place`() {
        deps.docs["assistant/architecture/voice_subsystem.md"] = MemoryFixtures.VOICE_DOC
        val stack = mutableStateListOf("assistant/architecture/voice_subsystem.md")
        compose.setContent { Stack(stack) }
        compose.onNodeWithText("Frontmatter · architecture · 4 refs").assertIsDisplayed()
        compose.onNodeWithText("source: curated", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("frontmatter-chip").performClick()
        compose.onNodeWithText("source: curated", substring = true).assertIsDisplayed()
        await("Modified")
        compose.onNodeWithText("Modified", substring = true).assertIsDisplayed()
    }

    @Test fun `load error shows the path and the error, Retry reloads`() {
        deps.failNext = "Internal Server Error"
        val stack = mutableStateListOf("assistant/infrastructure/infra_1.md")
        compose.setContent { Stack(stack) }
        compose.onNodeWithText("Could not load assistant/infrastructure/infra_1.md — Internal Server Error").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        await("Body of infra one.")
        compose.onNodeWithText("Body of infra one.").assertIsDisplayed()
    }

    @Test fun `tree opens files, folds folders and searches`() {
        val opened = mutableListOf<String>()
        compose.setContent {
            ArchieTheme(mode = ThemeMode.Dark, reduceMotion = true) { MemoryScreen(deps, onBack = {}, onOpenDoc = { opened += it }) }
        }
        assertEquals(1, deps.refreshes) // refreshed on entry
        compose.onNodeWithContentDescription("Search 138 memory files").assertIsDisplayed()
        compose.onNodeWithTag("memory-folder:assistant/architecture").performClick() // closed → open
        compose.onNodeWithTag("memory-file:assistant/architecture/voice_subsystem.md").performClick()
        assertEquals(listOf("assistant/architecture/voice_subsystem.md"), opened)
        compose.onNodeWithTag("memory-folder:assistant").performClick() // fold the top level
        compose.onNodeWithTag("memory-folder:assistant/architecture").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search 138 memory files").performTextInput("wakeword")
        compose.onNodeWithTag("memory-file:assistant/architecture/wakeword_subsystem.md").assertIsDisplayed()
        compose.onNodeWithTag("memory-file:MEMORY.md").assertDoesNotExist()
    }
}
