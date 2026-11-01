package com.assistant.archie.tv.shell

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation3.runtime.NavKey
import com.assistant.archie.tv.TvScreenFixtures
import com.assistant.archie.tv.conversation.ConversationOverlay
import com.assistant.archie.tv.conversation.ConversationScreen
import com.assistant.archie.tv.conversation.ConversationScreenActions
import com.assistant.archie.tv.conversations.ConversationsActions
import com.assistant.archie.tv.conversations.ConversationsScreen
import com.assistant.archie.tv.home.HomeActions
import com.assistant.archie.tv.home.HomeScreen
import com.assistant.archie.tv.theme.TvTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Roborazzi goldens of the T1/T2 screens at 960×540 dp (television, xhdpi): Home scrolled to the
 * last rows (mockup 3b), Conversations (5), Conversation reading with "3 steps" collapsed, a tool
 * card expanded and an approval card (6), the conversation's options sheet and its Agents sheet.
 * Home (3) and the overlay drawer (4) are TvShellGoldens' `home` and `drawer-expanded`.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class ScreenGoldens {
    @get:Rule
    val compose = createComposeRule()

    private fun scene(name: String, root: NavKey, pushed: NavKey? = null, overlays: TvShellOverlays = TvShellOverlays(), content: @Composable (NavKey) -> Unit) {
        val backStack = mutableStateListOf(root).also { if (pushed != null) it.add(pushed) }
        val controller = TvShellController(backStack)
        compose.setContent {
            TvTheme(reduceMotion = true) {
                TvShell(TvFixtures.shell.copy(needsApproval = true), controller, TvToasts(), overlays = overlays, entry = content)
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        captureScreenRoboImage("${TvShellGoldens.DIR}/$name.png")
    }

    @Test fun homeScrolled() = scene("home-scrolled", Home) {
        HomeScreen(
            TvScreenFixtures.home,
            HomeActions(),
            listState = LazyListState(firstVisibleItemIndex = 3),
            initialFocusKey = "recent:s-archie",
        )
    }

    @Test fun conversations() = scene("conversations", Conversations) {
        ConversationsScreen(
            sections = TvScreenFixtures.sections,
            loading = false,
            query = "",
            onQuery = {},
            preview = TvScreenFixtures.preview,
            focused = TvScreenFixtures.focusedRow,
            actions = ConversationsActions(),
            initialFocusKey = TvScreenFixtures.focusedRow.key,
        )
    }

    private val conversationKey = Conversation(archie = true)

    @Composable
    private fun ConversationFixture(overlay: ConversationOverlay = ConversationOverlay.NONE) {
        ConversationScreen(
            state = TvScreenFixtures.conversation,
            header = TvScreenFixtures.conversationHeader,
            draft = "",
            actions = ConversationScreenActions(),
            reduceMotion = true,
            initialFocusKey = TvScreenFixtures.paragraphKey,
            initialOverlay = overlay,
        )
    }

    @Test fun conversation() = scene("conversation", Home, pushed = conversationKey) { k -> if (k == conversationKey) ConversationFixture() }

    @Test fun conversationOptions() = scene("conversation-options", Home, pushed = conversationKey, overlays = TvShellOverlays(optionsOpen = true)) { k ->
        if (k == conversationKey) ConversationFixture()
    }

    @Test fun conversationAgents() = scene("conversation-agents", Home, pushed = conversationKey) { k ->
        if (k == conversationKey) ConversationFixture(ConversationOverlay.AGENTS)
    }
}
