package com.assistant.core.session

import com.assistant.core.model.PoolSession
import com.assistant.core.network.ApiResult
import com.assistant.core.network.ArchieApi
import com.assistant.core.network.PinStore
import com.assistant.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** What the channel needs from REST: the pool probe and the explicit close (P-1). */
interface PoolApi {
    /** `GET /api/sessions/pool/live`; `null` = the call failed. */
    suspend fun livePool(): List<PoolSession>?

    /** `POST /api/sessions/{localId}/close`. Explicit user close only. */
    suspend fun close(localId: String): Boolean
}

class ArchiePoolApi(private val api: ArchieApi) : PoolApi {
    override suspend fun livePool(): List<PoolSession>? = api.livePool().getOrNull()
    override suspend fun close(localId: String): Boolean = api.closePoolSession(localId) is ApiResult.Ok
}

/** Persisted orchestrator `localId` hint (spec 12 §8.2). */
interface OrchestratorIdStore {
    suspend fun load(): String?
    suspend fun save(localId: String)
    suspend fun clear()
}

class SettingsOrchestratorIdStore(private val settings: SettingsStore) : OrchestratorIdStore {
    override suspend fun load() = settings.orchestratorLocalId()
    override suspend fun save(localId: String) = settings.setOrchestratorLocalId(localId)
    override suspend fun clear() = settings.clearOrchestratorLocalId()
}

/** TOFU pins persisted in `SettingsStore` (reads are synchronous, from its in-memory map). */
class SettingsPinStore(private val settings: SettingsStore, private val scope: CoroutineScope) : PinStore {
    override fun pinFor(hostPort: String): String? = settings.pinFor(hostPort)
    override fun savePin(hostPort: String, spkiSha256: String) {
        // UNDISPATCHED: the in-memory pin is set before this returns, so the next handshake sees it.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { settings.savePin(hostPort, spkiSha256) }
    }

    override fun removePin(hostPort: String) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { settings.removePin(hostPort) }
    }
}
