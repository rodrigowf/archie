package com.assistant.peripheral.face

import com.assistant.core.network.SocketState
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceSessionState
import com.assistant.core.voice.session.VoiceLinkState
import com.assistant.core.voicehost.HostConnection
import com.assistant.core.voicehost.LastExchange
import com.assistant.core.voicehost.VoiceUiState
import com.assistant.core.voicehost.WakeHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `VoiceUiState` → face (spec 14 §5.2 table, mockup §4, P-2). */
class FaceModelTest {
    private fun scene(name: String) = FaceStates.model(FaceStates.scenes.first { it.name == name })

    private val base = VoiceUiState(connection = HostConnection.CONNECTED, wake = WakeHealth.ARMED)
    private fun face(s: VoiceUiState, socket: SocketState = SocketState.Open, ex: LastExchange = LastExchange(), retry: Long? = null, recentError: String? = null) =
        FaceModel.from(s, socket, "jetson", ex, "wake up, hey archie", "my friend", retry, recentError)

    @Test fun ready_isAQuietRingAroundTheMark_noAnimation() {
        val m = scene("ready")
        assertEquals(FaceShape.MARK_RING, m.shape)
        assertEquals(FaceGlyph.MARK, m.glyph)
        assertEquals("Ready", m.word)
        assertEquals("Say \"wake up\" or \"my friend\"", m.sub)
        assertFalse("armed must not animate (spec 14 §5.2)", m.animate)
        assertEquals(DotTone.SUCCESS, m.dot)
        assertEquals("jetson", m.connLabel)
        assertEquals(FaceAction.START, m.action)
        assertTrue(m.captions.all { it.dim })
        assertEquals(listOf("You", "Archie"), m.captions.map { it.label })
    }

    @Test fun listening_isAPrimaryOrb_speakingIsTertiary() {
        val l = scene("listening")
        assertEquals(FaceShape.ORB, l.shape)
        assertEquals(FaceTone.PRIMARY, l.tone)
        assertEquals("Listening", l.word)
        assertTrue(l.animate)
        assertTrue(l.showMute)
        assertTrue(l.keepScreenOn)
        assertEquals(FaceAction.STOP, l.action)
        val s = scene("speaking")
        assertEquals(FaceTone.TERTIARY, s.tone)
        assertEquals("Speaking", s.word)
        assertFalse(s.captions.last().dim)
    }

    @Test fun listeningTimer_appearsAfter3sOfSpeech() {
        val early = face(base.copy(session = FaceStates.live(SessionPhase.ACTIVE).copy(vadState = "speech", vadDurationMs = 2_999)))
        assertNull(early.sub)
        val late = face(base.copy(session = FaceStates.live(SessionPhase.ACTIVE).copy(vadState = "speech", vadDurationMs = 4_100)))
        assertEquals("4 s", late.sub)
    }

    @Test fun elsewhere_isReadOnlyDashedRing() {
        val m = scene("elsewhere")
        assertEquals(FaceShape.DASHED_RING, m.shape)
        assertEquals(FaceGlyph.DEVICES, m.glyph)
        assertEquals("Elsewhere", m.word)
        assertEquals(FaceAction.NONE, m.action)
        assertFalse(m.showMute)
    }

    @Test fun offline_showsNextRetry_andRedDot() {
        val m = scene("offline")
        assertEquals(FaceShape.ERROR_RING, m.shape)
        assertEquals(FaceGlyph.CLOUD_OFF, m.glyph)
        assertEquals("Offline", m.word)
        assertEquals("Retrying jetson in 5 s", m.sub)
        assertEquals(DotTone.ERROR, m.dot)
        assertEquals("offline", m.connLabel)
        assertEquals(listOf("Last"), m.captions.map { it.label })
        // At zero, or with no estimate: "Retrying …"; not retrying: tap to connect.
        assertEquals("Retrying jetson…", face(base.copy(connection = HostConnection.CONNECTING), SocketState.Disconnected(true, null), retry = 0).sub)
        // Cold start: Idle socket + auto-connect → Connecting, never an Offline flash.
        val cold = FaceModel.from(base.copy(connection = HostConnection.OFFLINE), SocketState.Idle, "jetson", LastExchange(), "w", "t")
        assertEquals("Connecting", cold.word)
        val manual = FaceModel.from(base.copy(connection = HostConnection.OFFLINE), SocketState.Idle, "jetson", LastExchange(), "w", "t", autoConnect = false)
        assertEquals(FaceAction.CONNECT, manual.action)
        val idle = face(base.copy(connection = HostConnection.OFFLINE), SocketState.Disconnected(false, "client"))
        assertEquals(FaceAction.CONNECT, idle.action)
        assertEquals("Not connected · tap to connect", idle.sub)
    }

    @Test fun reconnecting_isAmberOrbWithElapsedClock_P2() {
        val m = scene("reconnecting")
        assertEquals(FaceShape.RECONNECT_ORB, m.shape)
        assertEquals(FaceTone.WARNING, m.tone)
        assertEquals("Reconnecting", m.word)
        assertEquals("0:07 · a tone plays when it's back", m.sub)
        assertEquals(DotTone.WARNING, m.dot)
        assertTrue(m.animate)
        assertTrue(m.keepScreenOn)
    }

    @Test fun linkFailed_offersManualReconnect_P2() {
        val m = face(base.copy(link = VoiceLinkState.Failed(30_000)), SocketState.Disconnected(true, null))
        assertEquals(FaceShape.ERROR_RING, m.shape)
        assertEquals("Offline", m.word)
        assertEquals(FaceAction.RECONNECT_VOICE, m.action)
        assertEquals("Voice lost after 0:30 · tap to reconnect", m.sub)
        assertEquals("Dropped", face(base.copy(link = VoiceLinkState.Failed(31_000))).word)
    }

    @Test fun talkPath_confirmingThenRecording() {
        assertEquals("Confirming", scene("confirming").word)
        assertEquals(FaceTone.SECONDARY, scene("confirming").tone)
        val r = scene("recording")
        assertEquals("Recording", r.word)
        assertEquals(FaceTone.TERTIARY, r.tone)
        assertEquals(FaceAction.NONE, r.action)
    }

    @Test fun error_showsTheMessage_andRetries() {
        val m = scene("error")
        assertEquals(FaceShape.ERROR_RING, m.shape)
        assertEquals(FaceGlyph.ALERT, m.glyph)
        assertEquals("Error", m.word)
        assertTrue(m.sub!!.startsWith("Failed to start voice session"))
        assertEquals(FaceAction.START, m.action)
    }

    @Test fun aFinalizedVoiceFailure_isHeldOnTheFace() {
        // The core finalizes a fatal error straight to OFF (RS-09); the lite holds it from the system line.
        val m = face(base, recentError = "Failed to start voice session (no connection info)")
        assertEquals("Error", m.word)
        assertEquals("Failed to start voice session (no connection info) · tap to retry", m.sub)
        // A new attempt wins over the held error.
        assertEquals("Connecting", face(base.copy(session = FaceStates.live(SessionPhase.CONNECTING)), recentError = "x").word)
    }

    @Test fun micStalled_and_otherWakeHealth() {
        val m = scene("mic-stalled")
        assertEquals("Mic busy", m.word)
        assertEquals(FaceGlyph.MIC_OFF, m.glyph)
        assertEquals("Wake word off · tap to talk", face(base.copy(wake = WakeHealth.DISABLED)).sub)
        assertEquals("Paused", face(base.copy(wake = WakeHealth.PAUSED_BY_USER)).word)
        assertEquals(FaceModel.NO_ORCHESTRATOR, face(base.copy(noOrchestrator = true)).sub)
    }

    @Test fun voicePhases_mapToWords() {
        val words = mapOf(
            SessionPhase.CONNECTING to "Connecting", SessionPhase.SUMMARIZING to "Preparing",
            SessionPhase.THINKING to "Thinking", SessionPhase.TOOL_USE to "Working", SessionPhase.ENDING to "Ending",
        )
        for ((p, w) in words) assertEquals(w, face(base.copy(session = FaceStates.live(p))).word)
        // Markconnecting before ownership (RS-45) still shows Connecting.
        assertEquals("Connecting", face(base.copy(session = VoiceSessionState(phase = SessionPhase.CONNECTING))).word)
    }

    @Test fun hint_usesFirstPhraseOfEachList() {
        assertEquals("Say \"hey\" or \"buddy\"", FaceModel.hint(" hey , wake up", "buddy,my friend"))
        assertEquals("Tap to talk", FaceModel.hint(" , ", ""))
    }

    @Test fun everySceneHasAWord() {
        FaceStates.scenes.forEach { assertTrue(it.name, FaceStates.model(it).word.isNotBlank()) }
        assertEquals(10, FaceStates.scenes.size)
    }
}
