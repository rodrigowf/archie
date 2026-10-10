package com.assistant.core.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.assistant.core.model.ConnectionState
import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
import com.assistant.core.model.SessionStatus
import com.assistant.core.network.ArchieApi
import com.assistant.core.network.HttpStack
import com.assistant.core.network.ReconnectPolicy
import com.assistant.core.network.SocketClient
import com.assistant.core.session.ArchiePoolApi
import com.assistant.core.session.OrchestratorChannel
import com.assistant.core.session.OrchestratorIdStore
import com.assistant.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Spec 12 POOL-2 for agent sessions (2026-10-10): a session closed on another device while this
 * one was away must not be re-opened by this device's automatic re-`start` (socket reopen,
 * foreground). A backend restart still resumes; typing into the stopped view is the explicit resume.
 */
class AgentClosedElsewhereTest {
    private val backends = mutableListOf<FakeBackend>()
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun tearDown() {
        scopes.forEach { it.cancel() }
        backends.forEach { it.shutdown() }
    }

    private class MemIds : OrchestratorIdStore {
        var id: String? = null
        override suspend fun load() = id
        override suspend fun save(localId: String) { id = localId }
        override suspend fun clear() { id = null }
    }

    private val withAgent =
        """[{"local_id":"AG7","sdk_session_id":"SDK7","status":"idle","cost":0.0,"turns":3,"title":"Digest","is_orchestrator":false}]"""

    private class Rig(val b: FakeBackend, val convs: ConversationRepository, val events: MutableList<ConversationEvent>) {
        val key = ConversationKey.agent("AG7")
        fun state() = convs.current(key)
        fun agentStarts() = b.frames.filter { it.first == "agent" && it.second.contains("\"type\":\"start\"") }
        fun agentFrames() = b.frames.filter { it.first == "agent" }.map { it.second }
        fun poolReads() = b.requests.count { it == "GET /api/sessions/pool/live" }
        fun dropAgentSockets() = b.agentSockets.toList().forEach { it.close(1001, "going away") }
    }

    private fun rig(serverId: String?): Rig {
        val b = FakeBackend().start().also { backends += it }
        b.serverId = serverId
        b.poolJson = withAgent
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val settings = SettingsStore(
            MemoryDataStore(
                mutablePreferencesOf().apply {
                    this[stringPreferencesKey("server_url")] = b.url
                    this[booleanPreferencesKey("auto_connect")] = true
                },
            ),
            scope,
        )
        val url: () -> String = { settings.settings.value?.serverUrl ?: "ws://127.0.0.1:9" }
        val http = HttpStack()
        val api = ArchieApi(http, url)
        val orchestrator = OrchestratorChannel(SocketClient(http, scope, ReconnectPolicy { 200 }), ArchiePoolApi(api), MemIds(), scope)
        val history = HistoryRepository(api, scope)
        val agentSockets = AgentSocketPool({ SocketClient(http, scope, ReconnectPolicy { 200 }) })
        val convs = ConversationRepository(api, orchestrator, agentSockets, history, scope, { settings.settings.value?.serverUrl })
        val events = CopyOnWriteArrayList<ConversationEvent>()
        scope.launch { convs.events.collect { events += it } }
        return Rig(b, convs, events)
    }

    /** Open AG7 and wait until it subscribed and its post-subscribe pool read (server id) is done. */
    private fun Rig.subscribed() {
        convs.openAgent(SessionRef("AG7", "SDK7", SessionKind.AGENT, null))
        eventually(message = { "not subscribed: ${state()}" }) { state()?.connection == ConnectionState.SUBSCRIBED && poolReads() >= 1 }
        Thread.sleep(150)                                                    // the read's answer is applied
    }

    @Test fun closedWhileAwayOnTheSameServer_isStoppedNotRestarted() {
        val r = rig("BOOT1")
        r.subscribed()
        r.b.poolJson = "[]"                                                  // closed on another device meanwhile
        r.dropAgentSockets()
        eventually(message = { "not closed: ${r.state()}" }) {
            r.state()?.status == SessionStatus.STOPPED && r.state()?.connection == ConnectionState.OPEN
        }
        Thread.sleep(600)                                                    // nothing else follows
        assertEquals("only the first start: a re-start would re-open it for every device", 1, r.agentStarts().size)
        assertEquals(null, r.state()?.connectionBanner)
        assertTrue(r.events.any { it is ConversationEvent.ClosedElsewhere && it.localId == "AG7" })

        // Foreground with the socket open: still no start.
        r.convs.onForeground()
        Thread.sleep(600)
        assertEquals(1, r.agentStarts().size)

        // Typing into the stopped view is the explicit resume: start first, then the message.
        r.b.poolJson = withAgent
        r.convs.send(r.key, "carry on")
        eventually(message = { "not resumed: ${r.agentFrames()}" }) { r.agentFrames().any { it.contains("\"type\":\"send\"") } }
        val frames = r.agentFrames()
        val start2 = frames.indexOfLast { it.contains("\"type\":\"start\"") }
        val send = frames.indexOfFirst { it.contains("\"type\":\"send\"") }
        assertEquals(2, r.agentStarts().size)
        assertTrue("start before the message: $frames", start2 in 0 until send)
        eventually(message = { "${r.state()}" }) { r.state()?.connection == ConnectionState.SUBSCRIBED }
    }

    @Test fun aBackendRestart_newServerId_resumes() {
        val r = rig("BOOT1")
        r.subscribed()
        r.b.serverId = "BOOT2"
        r.b.poolJson = "[]"
        r.dropAgentSockets()
        eventually(message = { "not resumed: ${r.state()}" }) { r.agentStarts().size == 2 && r.state()?.connection == ConnectionState.SUBSCRIBED }
        assertTrue(r.events.none { it is ConversationEvent.ClosedElsewhere })
    }

    @Test fun stillInThePool_reopenStarts() {
        val r = rig("BOOT1")
        r.subscribed()
        r.dropAgentSockets()
        eventually(message = { "not resubscribed: ${r.state()}" }) { r.agentStarts().size == 2 && r.state()?.connection == ConnectionState.SUBSCRIBED }
    }

    @Test fun missedCloseWithTheSocketStillOpen_foregroundStopsIt() {
        val r = rig("BOOT1")
        r.subscribed()
        r.b.poolJson = "[]"
        r.convs.onForeground()
        eventually(message = { "not closed: ${r.state()}" }) { r.state()?.status == SessionStatus.STOPPED }
        Thread.sleep(400)
        assertEquals(1, r.agentStarts().size)
    }

    @Test fun aSessionOpenedFromHistory_neverLiveHere_starts() {
        val r = rig("BOOT1")
        r.b.poolJson = "[]"                                                  // a past session, not in the pool
        r.convs.openAgent(SessionRef("AG7", "SDK7", SessionKind.AGENT, null))
        eventually(message = { "not started: ${r.state()}" }) { r.state()?.connection == ConnectionState.SUBSCRIBED }
        assertEquals(1, r.agentStarts().size)
    }
}
