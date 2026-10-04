package com.assistant.archie.feature.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.assistant.core.data.AgentSocketPool
import com.assistant.core.data.ConnectionRepository
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.data.ConversationRepository
import com.assistant.core.data.HistoryRepository
import com.assistant.core.data.OpenSessionsRepository
import com.assistant.core.data.ServerScanner
import com.assistant.core.network.ApiResult
import com.assistant.core.network.ArchieApi
import com.assistant.core.network.CertificateInfo
import com.assistant.core.network.HttpStack
import com.assistant.core.network.ReconnectPolicy
import com.assistant.core.network.SocketClient
import com.assistant.core.network.UntrustedServerCertificateException
import com.assistant.core.session.ArchiePoolApi
import com.assistant.core.session.OrchestratorChannel
import com.assistant.core.session.OrchestratorIdStore
import com.assistant.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Settings → Connection: selecting a server **switches and reconnects** (fixes inv03 §8 bug 3 and
 * "selecting a server doesn't reconnect", inv03 §1.6), run against the real `ConnectionRepository`
 * / `OrchestratorChannel` and two MockWebServer backends. Plus the TOFU trust flow.
 */
class ConnectionSwitchTest {
    private val servers = mutableListOf<Backend>()
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun tearDown() {
        scopes.forEach { it.cancel() }
        servers.forEach { runCatching { it.server.shutdown() } }
    }

    /** A backend with an orchestrator WebSocket and an empty pool (the channel reports "no orchestrator"). */
    private class Backend {
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<String>()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val url get() = "ws://${server.hostName}:${server.port}"

        fun start(): Backend {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    requests += "${request.method} $path"
                    return when (path) {
                        "/api/orchestrator/chat" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) { sockets += webSocket }
                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                        })
                        "/api/sessions/pool/live", "/api/sessions" -> json("[]")
                        "/api/auth/status" -> json("""{"authenticated":true,"headless":true}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            return this
        }

        private fun json(b: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(b)
    }

    private class MemIds : OrchestratorIdStore {
        var id: String? = null
        override suspend fun load() = id
        override suspend fun save(localId: String) { id = localId }
        override suspend fun clear() { id = null }
    }

    private inner class Graph(serverUrl: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val settings = SettingsStore(MemoryDataStore(mutablePreferencesOf().apply { this[stringPreferencesKey("server_url")] = serverUrl }), scope)
        val http = HttpStack()
        val api = ArchieApi(http) { settings.settings.value?.serverUrl ?: serverUrl }
        val orchestrator = OrchestratorChannel(SocketClient(http, scope, ReconnectPolicy { 200 }), ArchiePoolApi(api), MemIds(), scope)
        val history = HistoryRepository(api, scope)
        val conversations = ConversationRepository(api, orchestrator, AgentSocketPool({ SocketClient(http, scope, ReconnectPolicy { 200 }) }), history, scope, { settings.settings.value?.serverUrl })
        val open = OpenSessionsRepository(conversations, history, orchestrator, scope)
        val connection = ConnectionRepository(settings, orchestrator, conversations, open, history, ServerScanner { emptyList() }, scope)
        val messages = SettingsMessages()
        val model = ConnectionModel(RepositoryConnectionControl(connection, orchestrator, http, settings), settings, messages, scope)
    }

    private fun backend() = Backend().start().also { servers += it }

    @Test fun selectingASavedServer_switchesAndReconnects() {
        val a = backend()
        val b = backend()
        val g = Graph(a.url)
        runBlocking { g.settings.addSavedServer("laptop", b.url) }
        g.connection.start()
        eventually(message = { "A=${a.requests} phase=${g.connection.status.value.phase}" }) {
            g.connection.status.value.phase == ConnectionStatus.Phase.CONNECTED && a.sockets.isNotEmpty()
        }
        assertTrue(b.requests.none { it.contains("/api/orchestrator/chat") })

        val row = g.model.state.value.rows.first { it.url == b.url }
        assertEquals("laptop", row.label)
        g.model.select(row)

        eventually(message = { "B=${b.requests} phase=${g.connection.status.value.phase}" }) {
            b.requests.any { it.contains("/api/orchestrator/chat") } && g.connection.status.value.phase == ConnectionStatus.Phase.CONNECTED
        }
        assertEquals(b.url, g.settings.settings.value?.serverUrl)
        assertEquals("laptop", g.connection.status.value.serverLabel)
        eventually { g.model.state.value.rows.first().let { it.url == b.url && it.current } }
    }

    @Test fun selectingTheCurrentServerWhileOffline_reconnects() {
        val a = backend()
        val g = Graph(a.url)
        assertEquals(ConnectionStatus.Phase.OFFLINE, g.connection.status.value.phase)
        eventually { g.model.state.value.rows.isNotEmpty() }
        g.model.select(g.model.state.value.rows.single())
        eventually(message = { "A=${a.requests}" }) { a.requests.any { it.contains("/api/orchestrator/chat") } }
    }

    @Test fun untrustedCertificate_asksFirst_thenPinsAndSwitches() {
        val cert = CertificateInfo("CN=jetson", "CN=jetson", 0, 4_102_444_800_000, "AAAA")
        val fake = FakeConnection(ConnectionStatus(serverUrl = "ws://192.168.0.200:80", phase = ConnectionStatus.Phase.CONNECTED))
        fake.probeResult = ApiResult.Untrusted(UntrustedServerCertificateException("archie.local:443", cert, changed = false))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val store = SettingsStore(MemoryDataStore(), scope)
        val model = ConnectionModel(fake, store, SettingsMessages(), scope)
        runBlocking { model.switchTo("wss://archie.local:443", "jetson-tls") }
        eventually { model.state.value.trust != null }
        val t = model.state.value.trust!!
        assertEquals("archie.local:443", t.hostPort)
        assertTrue("nothing switched before the user decides", fake.changes.isEmpty())
        model.trustAndSwitch()
        eventually { fake.changes == listOf("wss://archie.local:443") }
        assertEquals("archie.local:443" to cert, fake.trusted.single())
        eventually { model.state.value.trust == null }
    }

    @Test fun cancellingTrust_switchesNothing() {
        val cert = CertificateInfo("CN=x", "CN=x", 0, 1, "BBBB")
        val fake = FakeConnection(ConnectionStatus())
        fake.probeResult = ApiResult.Untrusted(UntrustedServerCertificateException("x:443", cert, changed = true))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val model = ConnectionModel(fake, SettingsStore(MemoryDataStore(), scope), SettingsMessages(), scope)
        runBlocking { model.switchTo("https://x:443") }
        eventually { model.state.value.trust != null }
        assertTrue(model.state.value.trust!!.changed)
        model.cancelTrust()
        Thread.sleep(100)
        assertTrue(fake.changes.isEmpty())
        assertTrue(fake.trusted.isEmpty())
    }

    @Test fun addServer_validatesThenSaves() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val store = SettingsStore(MemoryDataStore(), scope)
        val model = ConnectionModel(FakeConnection(ConnectionStatus()), store, SettingsMessages(), scope)
        assertEquals("Enter a ws://, wss://, http:// or https:// address", model.save("tv", "ftp://nope"))
        assertNull(model.save("", "192.168.0.77:8765"))
        eventually { store.settings.value?.savedServers?.any { it.label == "192.168.0.77" && it.url == "ws://192.168.0.77:8765" } == true }
    }

    /** Editing the current server's address switches to the new one. */
    @Test fun editingTheCurrentServer_switches() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val store = SettingsStore(MemoryDataStore(), scope)
        val fake = FakeConnection(ConnectionStatus())
        val model = ConnectionModel(fake, store, SettingsMessages(), scope)
        runBlocking { store.awaitLoaded(); store.addSavedServer("jetson", "ws://192.168.0.200:80") }
        assertNull(model.save("jetson", "ws://192.168.0.201:80", replacing = "ws://192.168.0.200:80"))
        eventually { fake.changes == listOf("ws://192.168.0.201:80") }
        assertEquals(listOf("ws://192.168.0.201:80"), store.settings.value?.savedServers?.map { it.url })
    }
}
