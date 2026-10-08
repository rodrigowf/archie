package com.assistant.archie.feature.settings

import com.assistant.core.model.HarnessValue
import com.assistant.core.model.SessionConfig
import com.assistant.core.network.ApiResult
import com.assistant.core.network.ArchieApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The per-session keys (spec 12 §6.14); the wire names double as the `inherit` set entries. */
enum class SessionKey(val wire: String) {
    WORKING_DIRECTORY("working_directory"),
    ENABLED_MCPS("enabled_mcps"),
    PROVIDER("provider"),
    HARNESS_MODEL("harness_model"),
    HARNESS_OPTIONS("harness_options"),
    CHROME_EXTENSION("chrome_extension"),
}

/**
 * A draft over a session's config. A key in [values] holds the draft value; `null` = "Use default"
 * (inherit the global value, written as JSON null).
 */
data class SessionDraft(val values: Map<SessionKey, Any?> = emptyMap()) {
    fun <T> valueOr(key: SessionKey, saved: T?): T? {
        @Suppress("UNCHECKED_CAST")
        return if (key in values) values[key] as T? else saved
    }
}

enum class SessionSheetPhase { NO_ID, READ_ONLY, LOADING, ERROR, READY }

data class SessionSettingsState(
    val phase: SessionSheetPhase = SessionSheetPhase.LOADING,
    val info: SessionInfo? = null,
    val saved: SessionConfig = SessionConfig(),
    val draft: SessionDraft = SessionDraft(),
    val loadError: String? = null,
    val saveError: String? = null,
    val busy: Boolean = false,
) {
    val workingDirectory: String? get() = draft.valueOr(SessionKey.WORKING_DIRECTORY, saved.workingDirectory)
    val enabledMcps: List<String>? get() = draft.valueOr(SessionKey.ENABLED_MCPS, saved.enabledMcps)
    val provider: String? get() = draft.valueOr(SessionKey.PROVIDER, saved.provider)
    val harnessModel: String? get() = draft.valueOr(SessionKey.HARNESS_MODEL, saved.harnessModel)
    val harnessOptions: Map<String, HarnessValue?>? get() = draft.valueOr(SessionKey.HARNESS_OPTIONS, saved.harnessOptions)
    val chromeExtension: Boolean? get() = draft.valueOr(SessionKey.CHROME_EXTENSION, saved.chromeExtension)

    /** Only the keys whose draft value differs from the saved one (spec 12 §6.14). */
    val changes: Map<SessionKey, Any?>
        get() = draft.values.filter { (k, v) -> !same(k, v, savedValue(k)) }

    val dirty: Boolean get() = changes.isNotEmpty()

    private fun savedValue(k: SessionKey): Any? = when (k) {
        SessionKey.WORKING_DIRECTORY -> saved.workingDirectory
        SessionKey.ENABLED_MCPS -> saved.enabledMcps
        SessionKey.PROVIDER -> saved.provider
        SessionKey.HARNESS_MODEL -> saved.harnessModel
        SessionKey.HARNESS_OPTIONS -> saved.harnessOptions
        SessionKey.CHROME_EXTENSION -> saved.chromeExtension
    }

    /** `harness_options` compares structurally, `null` and `{}` being the same (inherit every key). */
    @Suppress("UNCHECKED_CAST")
    private fun same(k: SessionKey, a: Any?, b: Any?): Boolean =
        if (k == SessionKey.HARNESS_OPTIONS) HarnessLogic.sameOptionsMap(a as Map<String, HarnessValue?>?, b as Map<String, HarnessValue?>?) else a == b

    /** The footer status line (web copy). */
    val footer: String
        get() = when {
            info?.busy == true -> "A reply is running: stop it to restart."
            dirty -> "Changes apply after a restart."
            else -> "The conversation is kept when the session restarts."
        }
}

/**
 * Session settings (IA §7, inv02 F-32, spec 12 §6.14), a port of the web `SessionSettingsSheet`
 * logic. Every field is `null` = inherit the global value ("Default"). Changes are a draft until
 * saved: **Save** PUTs only the changed keys; **Save and restart** also restarts the session
 * (close → reopen with the same pool key + `resume_sdk_id`), because the backend applies session
 * config only to a new pool entry. Restart is refused while a reply is running.
 */
class SessionSettingsController(
    private val localId: String,
    private val api: ArchieApi,
    private val sessions: SessionControl,
    private val messages: SettingsMessages,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SessionSettingsState())
    val state: StateFlow<SessionSettingsState> = _state.asStateFlow()
    private var loadedFor: String? = null

    init {
        scope.launch {
            sessions.session(localId).collect { info ->
                _state.update { it.copy(info = info) }
                when {
                    info == null || info.sdkId == null -> _state.update { it.copy(phase = SessionSheetPhase.NO_ID) }
                    info.readOnly -> _state.update { it.copy(phase = SessionSheetPhase.READ_ONLY) }
                    loadedFor != info.sdkId -> load()
                }
            }
        }
    }

    fun load() {
        val sdkId = _state.value.info?.sdkId ?: return
        loadedFor = sdkId
        _state.update { it.copy(phase = SessionSheetPhase.LOADING, loadError = null) }
        scope.launch {
            when (val r = api.sessionConfig(sdkId)) {
                is ApiResult.Ok -> _state.update { it.copy(phase = SessionSheetPhase.READY, saved = r.value, draft = SessionDraft()) }
                else -> _state.update { it.copy(phase = SessionSheetPhase.ERROR, loadError = r.errorMessage()) }
            }
        }
    }

    fun set(key: SessionKey, value: Any?) =
        _state.update { it.copy(draft = SessionDraft(it.draft.values + (key to value)), saveError = null) }

    /**
     * Change the harness. Its model and options belong to one harness, so they reset to inherit;
     * going back to the saved harness restores the saved values ([HarnessLogic.draftForProvider]).
     */
    fun setProvider(next: String?, globalProvider: String?) =
        _state.update { it.copy(draft = HarnessLogic.draftForProvider(it.saved, it.draft, next, globalProvider), saveError = null) }

    /** Set one harness option of the draft overlay. */
    fun setOption(key: String, state: OptionState) =
        set(SessionKey.HARNESS_OPTIONS, HarnessLogic.withSessionOption(_state.value.harnessOptions, key, state))

    /** Save (and optionally restart). Returns true when the sheet may close. */
    suspend fun run(restart: Boolean): Boolean {
        val s = _state.value
        val sdkId = s.info?.sdkId ?: return false
        if (restart && s.info.busy) return false
        _state.update { it.copy(busy = true, saveError = null) }
        val changes = s.changes
        if (changes.isNotEmpty()) {
            val body = SessionConfig(
                workingDirectory = changes[SessionKey.WORKING_DIRECTORY] as? String,
                enabledMcps = (changes[SessionKey.ENABLED_MCPS] as? List<*>)?.filterIsInstance<String>(),
                chromeExtension = changes[SessionKey.CHROME_EXTENSION] as? Boolean,
                provider = changes[SessionKey.PROVIDER] as? String,
                harnessModel = changes[SessionKey.HARNESS_MODEL] as? String,
                harnessOptions = (changes[SessionKey.HARNESS_OPTIONS] as? Map<*, *>)
                    ?.entries?.associate { (k, v) -> k as String to v as HarnessValue? }
                    ?.let(HarnessLogic::normalizeOptionsMap),
            )
            val inherit = changes.filter { (k, v) -> v == null || (k == SessionKey.HARNESS_OPTIONS && (v as Map<*, *>).isEmpty()) }.keys.map { it.wire }.toSet()
            when (val r = api.putSessionConfig(sdkId, body, inherit)) {
                is ApiResult.Ok -> _state.update { it.copy(saved = r.value, draft = SessionDraft()) }
                else -> {
                    val msg = r.errorMessage() ?: "Couldn't save"
                    _state.update { it.copy(busy = false, saveError = msg) }
                    messages.post(SettingsMessage.failed(msg) { scope.launch { run(restart) } })
                    return false
                }
            }
        }
        if (restart) {
            sessions.restart(localId)
            messages.post(SettingsMessage(if (changes.isNotEmpty()) "Saved. Restarting the session…" else "Restarting the session…"))
        } else {
            messages.post(SettingsMessage("Saved. Applies when the session restarts."))
        }
        _state.update { it.copy(busy = false) }
        return restart
    }
}
