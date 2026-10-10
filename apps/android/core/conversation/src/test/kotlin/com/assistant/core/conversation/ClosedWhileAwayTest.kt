package com.assistant.core.conversation

import com.assistant.core.model.ConnectionState
import com.assistant.core.model.SessionStatus
import com.assistant.core.protocol.ServerFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 12 POOL-2: the orchestrator channel found this conversation missing from `pool/live` on a
 * reconnect and feeds the synthesized `agent_session_closed` (WATCH-1). The view had a
 * "disconnected" banner from the drop; with the session gone there is nothing to reconnect to.
 */
class ClosedWhileAwayTest {
    @Test
    fun closedWhileAwayStopsTheViewAndDropsTheDisconnectedBanner() {
        val away = orchestrator().input(ConversationInput.SocketClosed)
        assertEquals("disconnected", away.connectionBanner?.code)
        val closed = away.on(ServerFrame.AgentSessionClosed("O1", isOrchestrator = true))
        assertEquals(SessionStatus.STOPPED, closed.status)
        assertNull(closed.connectionBanner)
    }

    @Test
    fun anotherSessionsCloseKeepsTheBanner() {
        val away = orchestrator().input(ConversationInput.SocketClosed)
        assertEquals("disconnected", away.on(ServerFrame.AgentSessionClosed("OTHER", isOrchestrator = true)).connectionBanner?.code)
    }

    @Test
    fun aStartErrorBannerIsKept() {
        val failed = orchestrator().input(ConversationInput.Resync).on(ServerFrame.Error("start_failed", "boom"))
        val closed = failed.on(ServerFrame.AgentSessionClosed("O1", isOrchestrator = true))
        assertEquals("start_failed", closed.connectionBanner?.code)
    }

    @Test
    fun closedWhileAwayInput_opensWithoutStart_releasesHeldFrames_andStopsTheView() {
        // an agent view that dropped while a re-start was pending
        val pending = agent().input(ConversationInput.SocketClosed, ConversationInput.SocketOpened)
            .on(ServerFrame.Status("processing"))
        assertTrue(pending.awaitingSessionStarted)
        val r = ConversationReducer.step(pending, ConversationInput.ClosedWhileAway("L1"))
        assertTrue("no start: ${r.effects}", r.effects.none { it is ConversationEffect.SendStart })
        assertEquals(ConnectionState.OPEN, r.state.connection)
        assertFalse(r.state.awaitingSessionStarted)
        assertEquals(SessionStatus.STOPPED, r.state.status)
        assertNull(r.state.connectionBanner)
        // a Resync afterwards (the explicit resume) starts it again
        assertTrue(ConversationReducer.step(r.state, ConversationInput.Resync).effects.any { it is ConversationEffect.SendStart })
    }

    @Test
    fun closedWhileAwayInputForAnotherConversationIsIgnored() {
        val s = orchestrator()
        assertEquals(s, s.input(ConversationInput.ClosedWhileAway("OTHER")))
    }
}
