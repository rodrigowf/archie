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
        for (p in HarnessProvider.entries) assertEquals(p, HarnessProvider.fromWire(p.wire))
        for (k in SessionKind.entries) assertEquals(k, SessionKind.fromWire(k.wire))
        assertNull(SessionStatus.fromWire("ready"))                            // ST-1: never mapped to "Ready"
        assertNull(LiveStatus.fromWire(null))
        assertEquals(VoiceConnectionType.WEBRTC, VoiceConnectionType.fromWire("webrtc"))
        assertNull(VoiceConnectionType.fromWire("sip"))
    }
}
