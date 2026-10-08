package com.assistant.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsTest {
    @Test
    fun busyStatuses() {
        val busy = SessionStatus.entries.filter { it.busy }.toSet()
        assertEquals(
            setOf(SessionStatus.PROCESSING, SessionStatus.STREAMING, SessionStatus.THINKING, SessionStatus.TOOL_USE, SessionStatus.RETRYING, SessionStatus.COMPACTING),
            busy,
        )
    }

    @Test
    fun onlyClaudeAgentSessionsAreSeqCapable() {
        assertTrue(SessionRef("L", null, SessionKind.AGENT, HarnessProvider.CLAUDE).seqCapable)
        assertFalse(SessionRef("L", null, SessionKind.AGENT, HarnessProvider.QWEN).seqCapable)
        assertFalse(SessionRef("L", null, SessionKind.AGENT, HarnessProvider.GEMINI).seqCapable)
        assertFalse(SessionRef("O", "O", SessionKind.ORCHESTRATOR, null).seqCapable)
    }

    @Test
    fun wireValuesRoundTripAndUnknownsAreNull() {
        for (s in SessionStatus.entries) assertEquals(s, SessionStatus.fromWire(s.wire))
        for (s in LiveStatus.entries) assertEquals(s, LiveStatus.fromWire(s.wire))
        for (p in listOf(HarnessProvider.CLAUDE, HarnessProvider.QWEN, HarnessProvider.GEMINI, HarnessProvider.CODEX, HarnessProvider.MODELSTUDIO)) {
            assertEquals(p, HarnessProvider.fromWire(p.wire))
        }
        for (k in SessionKind.entries) assertEquals(k, SessionKind.fromWire(k.wire))
        assertNull(SessionStatus.fromWire("ready"))                            // ST-1: never mapped to "Ready"
        assertNull(LiveStatus.fromWire(null))
        assertEquals(VoiceConnectionType.WEBRTC, VoiceConnectionType.fromWire("webrtc"))
        assertNull(VoiceConnectionType.fromWire("sip"))
    }

    /** Harness ids are open-ended (registry-driven): unknown ids are kept and labelled, never null. */
    @Test
    fun providerIdsAreOpenEnded_andLabelsNeverNull() {
        assertEquals(HarnessProvider("codex"), HarnessProvider.fromWire("codex"))
        assertEquals("future-cli", HarnessProvider.fromWire("future-cli")?.wire)
        assertNull(HarnessProvider.fromWire(null))
        assertNull(HarnessProvider.fromWire(" "))
        assertEquals(
            listOf("Claude", "Qwen", "Gemini", "Codex", "Model Studio"),
            listOf("claude", "qwen", "gemini", "codex", "modelstudio").map { HarnessProvider(it).label },
        )
        assertEquals("future-cli", HarnessProvider("future-cli").label)
        assertNull(HarnessLabels.label(null))
        assertNull(HarnessLabels.label(""))
        // short tag → the given registry → the remembered registry → the id
        assertEquals("Claude", HarnessLabels.label("claude", listOf(HarnessInfo("claude", "Claude Code"))))
        assertEquals("Future CLI", HarnessLabels.label("future-cli", listOf(HarnessInfo("future-cli", "Future CLI"))))
        assertEquals("future-cli", HarnessLabels.label("future-cli", listOf(HarnessInfo("future-cli", " "))))
        try {
            HarnessLabels.register(listOf(HarnessInfo("future-cli", "Future CLI"), HarnessInfo("", "x")))
            assertEquals("Future CLI", HarnessProvider("future-cli").label)
            assertEquals("Codex", HarnessProvider.CODEX.label)
        } finally {
            HarnessLabels.register(emptyList())
        }
    }
}
