package com.assistant.archie.tv.voice

import com.assistant.archie.tv.shell.Conversation
import com.assistant.archie.tv.shell.Home
import com.assistant.archie.tv.shell.SettingsPage
import com.assistant.archie.tv.shell.TvSettingsPageId
import com.assistant.archie.tv.shell.Viewer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 15 §4.1: where a burst goes. */
class TvVoiceRouterTest {
    private fun route(screen: androidx.navigation3.runtime.NavKey?, live: Boolean = false, testing: Boolean = false) =
        TvVoiceRouter.route(RouteInputs(liveOwned = live, screen = screen, testing = testing, agentTitle = "Claude"))

    @Test
    fun liveVoiceOwnedByThisDeviceTakesEveryBurst() {
        assertEquals(VoiceDestination.Live, route(Home, live = true))
        assertEquals(VoiceDestination.Live, route(Conversation(localId = "a1"), live = true))
        assertEquals("a running call is never broken by a test page", VoiceDestination.Live, route(SettingsPage(TvSettingsPageId.REMOTE_MIC), live = true, testing = true))
    }

    @Test
    fun agentConversationDictates() {
        assertEquals(VoiceDestination.Dictation("a1", null, "Claude"), route(Conversation(localId = "a1")))
        assertEquals(VoiceDestination.Dictation(null, "s9", "Claude"), route(Conversation(sdkId = "s9")))
    }

    @Test
    fun everythingElseIsAVoiceMessageToArchie() {
        assertEquals(VoiceDestination.VoiceMessage, route(Home))
        assertEquals(VoiceDestination.VoiceMessage, route(Conversation(archie = true)))
        assertEquals(VoiceDestination.VoiceMessage, route(Conversation(sdkId = "s1", archie = true)))
        assertEquals(VoiceDestination.VoiceMessage, route(Viewer("visualizations/x.html")))
        assertEquals(VoiceDestination.VoiceMessage, route(Conversation())) // no id: nothing to dictate into
        assertEquals("no shell composed", VoiceDestination.VoiceMessage, route(null))
    }

    @Test
    fun micTestPagesGetTheBurst() {
        assertEquals(VoiceDestination.Test, route(SettingsPage(TvSettingsPageId.REMOTE_MIC), testing = true))
    }
}
