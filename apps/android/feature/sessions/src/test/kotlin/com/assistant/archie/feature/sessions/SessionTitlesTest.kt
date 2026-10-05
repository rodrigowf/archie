package com.assistant.archie.feature.sessions

import com.assistant.core.data.HistoryRepository
import com.assistant.core.data.OpenSessionsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** IA §1 / CR-9 and spec 12 MC-2: placeholder titles never reach the UI as titles. */
class SessionTitlesTest {
    @Test fun archieGenericNamesShowAsNewConversation() {
        for (raw in listOf("Orchestrator", "orchestrator", "Archie", "", "  ")) {
            assertEquals(raw, NEW_CONVERSATION, SessionTitles.conversationTitle(raw, isArchie = true))
            assertEquals(raw, OpenSessionsRepository.NEW_CONVERSATION, OpenSessionsRepository.archieTitle(raw))
        }
        assertEquals("Plan the trip", SessionTitles.conversationTitle("Plan the trip", isArchie = true))
        assertEquals("Plan the trip", OpenSessionsRepository.archieTitle(" Plan the trip "))
    }

    @Test fun liveAgentWithoutHistoryFileIsANewAgentSession() {
        assertEquals("New agent session", SessionTitles.conversationTitle("(active session)", isArchie = false))
        assertEquals("Untitled", SessionTitles.conversationTitle("", isArchie = false))
        assertFalse(HistoryRepository.isRealTitle("(active session)"))
        assertFalse(HistoryRepository.isRealTitle(" "))
        assertTrue(HistoryRepository.isRealTitle("Run these two Bash commands"))
    }
}
