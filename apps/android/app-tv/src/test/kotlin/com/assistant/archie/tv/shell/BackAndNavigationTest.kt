package com.assistant.archie.tv.shell

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Back semantics (spec 15 §3.3, TV-7) and the navigator (drawer roots replace the stack). */
class BackAndNavigationTest {
    @Test fun policy() {
        assertEquals(BackAction.CLOSE_OVERLAY, BackPolicy.decide(overlayOpen = true, drawerFocused = true, stack = listOf(Home, MemoryDoc("a.md"))))
        assertEquals(BackAction.FOCUS_CONTENT, BackPolicy.decide(overlayOpen = false, drawerFocused = true, stack = listOf(Visuals)))
        assertEquals(BackAction.POP, BackPolicy.decide(overlayOpen = false, drawerFocused = false, stack = listOf(Memory, MemoryDoc("a.md"))))
        assertEquals(BackAction.GO_HOME, BackPolicy.decide(overlayOpen = false, drawerFocused = false, stack = listOf(Visuals)))
        assertEquals(BackAction.LEAVE_APP, BackPolicy.decide(overlayOpen = false, drawerFocused = false, stack = listOf(Home)))
        assertEquals(BackAction.LEAVE_APP, BackPolicy.decide(overlayOpen = false, drawerFocused = false, stack = listOf(Connect(firstRun = true))))
        assertEquals(BackAction.POP, BackPolicy.decide(overlayOpen = false, drawerFocused = false, stack = listOf(Settings, Connect())))
    }

    @Test fun controllerWalksBackOverlaysFirstThenThePageThenHomeThenOut() {
        val stack = mutableListOf<NavKey>(Visuals, Viewer("visualizations/x.html"))
        var left = 0
        var focusedContent = 0
        val c = TvShellController(stack, onLeaveApp = { left++ })
        c.focusContent = { focusedContent++ }

        c.optionsOpen = true
        c.back()
        assertFalse(c.optionsOpen)
        assertEquals(listOf(Visuals, Viewer("visualizations/x.html")), stack)

        c.back()
        assertEquals(listOf<NavKey>(Visuals), stack)

        c.drawerFocused = true
        c.back()
        assertEquals(1, focusedContent)
        c.drawerFocused = false

        c.back()
        assertEquals(listOf<NavKey>(Home), stack)
        assertEquals(0, left)

        c.back()
        assertEquals(1, left)
    }

    @Test fun drawerSelectionReplacesTheStack() {
        val stack = mutableListOf<NavKey>(Home, Conversation(archie = true))
        val nav = TvNavigator(stack)
        nav.selectRoot(TvDestination.MEMORY)
        assertEquals(listOf<NavKey>(Memory), stack)
        nav.push(MemoryDoc("x.md"))
        nav.push(MemoryDoc("x.md"))
        assertEquals(listOf(Memory, MemoryDoc("x.md")), stack)
        assertTrue(nav.pop())
        assertFalse(nav.pop())
        nav.replaceRoot(Home)
        assertEquals(listOf<NavKey>(Home), stack)
        assertEquals(TvDestination.HOME, TvDestination.of(nav.root))
        assertEquals(null, TvDestination.of(Connect(firstRun = true)))
    }

    @Test fun menuOpensTheOptionsSheetAndMenuAgainClosesIt() {
        val c = TvShellController(mutableListOf(Home))
        assertTrue(c.router.route(TvKey.MENU))
        assertTrue(c.optionsOpen)
        assertTrue(c.router.route(TvKey.MENU))
        assertFalse(c.optionsOpen)
    }

    @Test fun playPauseCallsTheLiveVoiceSeamEvenOverTheSheet() {
        var toggles = 0
        val c = TvShellController(mutableListOf(Home), liveVoice = { toggles++; true })
        c.optionsOpen = true
        assertTrue(c.router.route(TvKey.PLAY_PAUSE))
        assertEquals(1, toggles)
    }

    @Test fun globalOptionsNavigate() {
        val stack = mutableListOf<NavKey>(Visuals)
        val c = TvShellController(stack)
        c.optionsOpen = true
        c.runOption(TvShellController.GLOBAL_SETTINGS)
        assertFalse(c.optionsOpen)
        assertEquals(listOf<NavKey>(Settings), stack)
        c.runOption(TvShellController.GLOBAL_HOME)
        assertEquals(listOf<NavKey>(Home), stack)
    }
}
