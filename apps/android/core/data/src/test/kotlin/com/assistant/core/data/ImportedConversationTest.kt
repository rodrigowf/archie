package com.assistant.core.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A conversation imported from a ChatGPT export is read-only: its history comes from REST and the
 * data layer must never open an agent socket for it (`start` would be refused anyway —
 * `error{read_only_session}`). See docs/harnesses/chatgpt-import.md.
 */
class ImportedConversationTest {
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

    private fun conversations(b: FakeBackend): ConversationRepository {
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
        return ConversationRepository(api, orchestrator, agentSockets, history, scope, { settings.settings.value?.serverUrl })
    }

    @Test fun readOnlyRefLoadsHistoryOverRestAndNeverOpensASocket() {
        val b = FakeBackend().start().also { backends += it }
        b.messagesJson = """
            {"messages":[
              {"role":"user","text":"Oi Chat","blocks":[{"type":"text","text":"Oi Chat"}],"timestamp":"2026-10-02T19:28:00+00:00"},
              {"role":"assistant","text":"Olá!","blocks":[{"type":"text","text":"Olá!"}],"timestamp":"2026-10-02T19:28:05+00:00"}
            ],"total_count":2,"has_more":false,"start_index":0}
        """.trimIndent()
        val convs = conversations(b)
        val key = ConversationKey.agent("CG1")

        convs.openAgent(SessionRef("CG1", "SDK-CG1", SessionKind.AGENT, null, readOnly = true))

        eventually(message = { "history did not load: ${convs.current(key)}" }) {
            (convs.current(key)?.entries?.size ?: 0) >= 2
        }
        // The whole point: REST only. No agent socket was ever upgraded.
        assertEquals("agent sockets opened for a read-only view", 0, b.agentSockets.size)
        assertTrue("the view should not be in a turn", convs.current(key)?.inTurn != true)
    }

    @Test fun aNormalRefStillOpensItsSocket() {
        val b = FakeBackend().start().also { backends += it }
        val convs = conversations(b)

        convs.openAgent(SessionRef("AG1", "SDK-AG1", SessionKind.AGENT, null))

        eventually(message = { "no agent socket opened for a live view" }) { b.agentSockets.isNotEmpty() }
    }
}
