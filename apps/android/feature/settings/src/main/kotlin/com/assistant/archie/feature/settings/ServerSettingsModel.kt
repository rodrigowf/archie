package com.assistant.archie.feature.settings

import com.assistant.core.data.LoadState
import com.assistant.core.data.ServerConfigRepository
import com.assistant.core.model.ConfigPatch
import com.assistant.core.model.ServerConfig
import com.assistant.core.network.ApiResult
import com.assistant.core.network.ArchieApi
import com.assistant.core.protocol.AgentDto
import com.assistant.core.protocol.HarnessProviderDto
import com.assistant.core.protocol.McpServersDto
import com.assistant.core.protocol.OrchestratorModelsDto
import com.assistant.core.protocol.QwenModelDto
import com.assistant.core.protocol.SkillDto
import com.assistant.core.protocol.VoiceModelEntryDto
import com.assistant.core.protocol.VoiceModelsDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The catalogs a settings surface shows (each `null` until loaded; a failed load keeps the last copy). */
data class Catalogs(
    val orchestratorModels: OrchestratorModelsDto? = null,
    val voiceModels: VoiceModelsDto? = null,
    /** Discovered Gemini Live models per endpoint (`vertex` / `aistudio`). */
    val googleVoiceModels: Map<String, List<VoiceModelEntryDto>> = emptyMap(),
    val providers: List<HarnessProviderDto>? = null,
    val qwenModels: List<QwenModelDto>? = null,
    val mcpServers: McpServersDto? = null,
    val skills: List<SkillDto>? = null,
    val agents: List<AgentDto>? = null,
) {
    val mcpNames: List<String> get() = mcpServers?.servers?.keys?.toList().orEmpty()
}

data class ServerSettingsState(
    val config: LoadState<ServerConfig> = LoadState(),
    val catalogs: Catalogs = Catalogs(),
    /** Key of the save in flight (the page's controls are disabled, CFG-1); null when idle. */
    val saving: String? = null,
    /** Verbatim message of the last failed save (dialogs show it inline). */
    val saveError: String? = null,
    /** The dismissible notice after a Google auto-correct (CFG-6). */
    val autoCorrected: AutoCorrect? = null,
) {
    val google: List<VoiceModelEntryDto>?
        get() = config.value?.voice?.endpoint?.let { catalogs.googleVoiceModels[it] }
}

/**
 * Settings data flow for Archie (server), a port of the web controller (W-13, spec 12 §8.1).
 * Config is not broadcast (G-27), so every Settings open refetches config and catalogs (CFG-3).
 * Each control saves on its own with a partial PUT whose answer replaces the local copy
 * (CFG-1, CFG-5); a save shows "Saved" or the backend `detail` verbatim with Retry (CFG-2). After
 * every load or voice save the Google auto-correct runs (CFG-6, F-31 [LOAD-BEARING]).
 */
class ServerSettingsModel(
    private val repository: ServerConfigRepository,
    private val api: ArchieApi,
    private val messages: SettingsMessages,
    private val scope: CoroutineScope,
) {
    private val local = MutableStateFlow(ServerSettingsState())
    private val correctedFrom = HashSet<String>()

    val state: StateFlow<ServerSettingsState> = combine(repository.config, local) { cfg, l -> l.copy(config = cfg) }
        .stateIn(scope, SharingStarted.Eagerly, ServerSettingsState(config = repository.config.value))

    /** A synchronous snapshot ([state] is derived on a collector and may lag by a dispatch). */
    val current: ServerSettingsState get() = local.value.copy(config = repository.config.value)

    /** CFG-3: refetch everything a settings surface shows. Never throws. */
    fun refresh() {
        scope.launch { refreshNow() }
    }

    suspend fun refreshNow() {
        repository.load()
        coroutineScope {
            val om = async { api.orchestratorModels() }
            val vm = async { api.voiceModels() }
            val pr = async { api.providers() }
            val qm = async { api.qwenModels() }
            val mcp = async { api.mcpServers() }
            val sk = async { api.skills() }
            val ag = async { api.agents() }
            local.update { s ->
                val c = s.catalogs
                s.copy(
                    catalogs = c.copy(
                        orchestratorModels = om.await().getOrNull() ?: c.orchestratorModels,
                        voiceModels = vm.await().getOrNull() ?: c.voiceModels,
                        providers = pr.await().getOrNull()?.providers ?: c.providers,
                        qwenModels = qm.await().getOrNull()?.models ?: c.qwenModels,
                        mcpServers = mcp.await().getOrNull() ?: c.mcpServers,
                        skills = sk.await().getOrNull()?.skills ?: c.skills,
                        agents = ag.await().getOrNull()?.agents ?: c.agents,
                    ),
                )
            }
        }
        val cfg = awaitConfig() ?: return
        loadGoogleFor(cfg)
        maybeAutoCorrect()
    }

    /** `repository.load()` is fire-and-forget; wait for it to settle (ok or error). */
    private suspend fun awaitConfig(): ServerConfig? {
        val s = repository.config.first { !it.loading }
        return s.value
    }

    private suspend fun loadGoogleFor(cfg: ServerConfig) {
        val endpoint = cfg.voice.endpoint?.takeIf { it.isNotEmpty() } ?: return
        val r = api.googleVoiceModels(endpoint)
        val list = r.getOrNull()?.models ?: return
        local.update { it.copy(catalogs = it.catalogs.copy(googleVoiceModels = it.catalogs.googleVoiceModels + (endpoint to list))) }
    }

    /** CFG-6: PUT the discovered default once per stale id and show the notice. */
    suspend fun maybeAutoCorrect(): AutoCorrect? {
        val cfg = repository.config.value.value ?: return null
        val fix = VoiceLogic.googleAutoCorrect(cfg.voice.provider, cfg.voice.model, cfg.voice.voice, currentGoogle(cfg))
            ?: return null
        if (!correctedFrom.add(fix.from)) return null
        return when (val r = repository.update(ConfigPatch(defaultVoiceModel = fix.to, defaultVoiceName = fix.voice))) {
            is ApiResult.Ok -> { local.update { it.copy(autoCorrected = fix) }; fix }
            else -> { messages.post(SettingsMessage("Couldn't switch the Gemini Live model: ${r.errorMessage()}", error = true)); null }
        }
    }

    private fun currentGoogle(cfg: ServerConfig) = cfg.voice.endpoint?.let { local.value.catalogs.googleVoiceModels[it] }

    fun dismissAutoCorrect() = local.update { it.copy(autoCorrected = null) }

    /**
     * Save one control (CFG-1). Returns the new config, or null on failure (the snackbar already
     * shows the server message + Retry). Voice changes re-run the Google catalog + auto-correct.
     */
    suspend fun save(patch: ConfigPatch, key: String, snackbar: Boolean = true): ServerConfig? {
        local.update { it.copy(saving = key, saveError = null) }
        return when (val r = repository.update(patch)) {
            is ApiResult.Ok -> {
                local.update { it.copy(saving = null) }
                if (snackbar) messages.saved()
                if (patch.defaultVoiceEndpoint != null || patch.defaultVoiceProvider != null) loadGoogleFor(r.value)
                if (key == KEY_VOICE) maybeAutoCorrect()
                repository.config.value.value ?: r.value
            }
            else -> {
                val msg = r.errorMessage() ?: "Couldn't save"
                local.update { it.copy(saving = null, saveError = msg) }
                if (snackbar) messages.post(SettingsMessage.failed(msg) { launchSave(patch, key) })
                null
            }
        }
    }

    /** Fire-and-forget [save] for UI callbacks. */
    fun launchSave(patch: ConfigPatch, key: String) {
        scope.launch { save(patch, key) }
    }

    fun clearSaveError() = local.update { it.copy(saveError = null) }

    /** A new server: drop catalogs and corrections (T-15). */
    fun reset() {
        correctedFrom.clear()
        local.value = ServerSettingsState()
    }

    companion object {
        const val KEY_VOICE = "voice"
    }
}
