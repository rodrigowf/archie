package com.assistant.archie.feature.sessions

import com.assistant.archie.feature.sessions.ui.historySupporting
import com.assistant.core.model.HarnessProvider
import com.assistant.core.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * History rows label every harness (web `providerLabel`): the short tag of a harness Archie ships
 * (Codex and Model Studio included), any other id as is — never "null", never a crash.
 */
class ProviderLabelTest {
    private fun row(provider: String?, orchestrator: Boolean = false) =
        SessionSummary("s", null, null, "t", 3, orchestrator, HarnessProvider.fromWire(provider), null)

    @Test fun historyRows_labelEveryHarness() {
        assertEquals("Claude · 3 messages", historySupporting(row("claude")))
        assertEquals("Codex · 3 messages", historySupporting(row("codex")))
        assertEquals("Model Studio · 3 messages", historySupporting(row("modelstudio")))
        assertEquals("future-cli · 3 messages", historySupporting(row("future-cli")))
        assertEquals("Agent · 3 messages", historySupporting(row(null)))
        assertEquals("Archie · 3 messages", historySupporting(row(null, orchestrator = true)))
    }
}
