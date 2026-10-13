package com.assistant.archie.feature.chat

import com.assistant.archie.feature.chat.ui.isAgentNotification
import com.assistant.archie.feature.chat.ui.taskNotificationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A `<task-notification>` notice names an agent only when a subagent reported (web: NoticeRow.tsx). */
class TaskNotificationLabelTest {
    @Test fun agentAndShellNotificationsAreTold() {
        val agent = taskNotificationSummary("<task-notification>\n<status>completed</status>\n<summary>Agent \"Digest logs\" finished</summary>\n</task-notification>")
        val shell = taskNotificationSummary("<task-notification>\n<summary>Background command \"Run tests\" completed (exit code 0)</summary>\n</task-notification>")
        assertEquals("Agent \"Digest logs\" finished", agent)
        assertTrue(isAgentNotification(agent!!))
        assertFalse(isAgentNotification(shell!!))
        assertTrue(isAgentNotification("Background agent \"Digest logs\" completed."))
        assertNull(taskNotificationSummary("Archie started on its own"))
    }
}
