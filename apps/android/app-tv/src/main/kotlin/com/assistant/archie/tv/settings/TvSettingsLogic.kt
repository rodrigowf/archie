package com.assistant.archie.tv.settings

import androidx.compose.runtime.Immutable
import com.assistant.archie.feature.settings.AccountsModel
import com.assistant.archie.feature.settings.HarnessLogic
import com.assistant.archie.feature.settings.HarnessScope
import com.assistant.archie.feature.settings.McpLogic
import com.assistant.archie.feature.settings.ModelLogic
import com.assistant.archie.feature.settings.ModelPick
import com.assistant.archie.feature.settings.Option
import com.assistant.archie.feature.settings.ServerSettingsState
import com.assistant.archie.feature.settings.VoiceLogic
import com.assistant.archie.feature.settings.WorkingDirectoryLogic
import com.assistant.archie.tv.control.TvControlState
import com.assistant.archie.tv.shell.TvSettingsPageId
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.model.ConfigPatch
import com.assistant.core.model.ServerConfig
import com.assistant.core.protocol.AccountServiceDto
import com.assistant.core.protocol.ModelInfoDto

/*
 * Settings on the TV (spec 15 §3.5): the pure part. Every server setting the TV edits is a plain
 * choice (OK opens a picker) or a switch, built here from `:feature:settings`' logic (VoiceLogic,
 * ModelLogic, HarnessLogic, McpLogic, WorkingDirectoryLogic) so the TV saves exactly the partial
 * PUTs the phone and web apps save (CFG-1). Anything that needs typing beyond the server URL and the
 * device name (custom model ids, harness options, directories, sign-ins, API keys) is left to the
 * phone or web app. No Android, no I/O: JVM-tested in TvSettingsLogicTest.
 */

/** A choice row: OK opens a picker with [options]; picking saves [patch] of the picked id. */
@Immutable
data class TvChoice(
    val key: String,
    val title: String,
    val options: List<Option>,
    val selected: String?,
    val enabled: Boolean = true,
    /** One supporting line under the title. */
    val note: String? = null,
) {
    /** What the row shows on the right: the picked option's label, else the raw value. */
    val valueLabel: String
        get() = options.firstOrNull { it.id == selected }?.label ?: selected?.takeIf { it.isNotEmpty() } ?: "Not set"
}

/** A switch row. [note] explains a disabled switch ("Last one on…"). */
@Immutable
data class TvToggle(val key: String, val title: String, val on: Boolean, val enabled: Boolean = true, val note: String? = null)

/** A read-only line ("Claude Code · Signed in"); OK says where to change it. */
@Immutable
data class TvInfoLine(val key: String, val title: String, val value: String, val warn: Boolean = false)

/** A titled block of rows on the Server page. */
@Immutable
data class TvSettingsSection(
    val title: String,
    val choices: List<TvChoice> = emptyList(),
    val toggles: List<TvToggle> = emptyList(),
    val info: List<TvInfoLine> = emptyList(),
    /** "Edit … on the phone or web app": what this section leaves to the other apps. */
    val elsewhere: String? = null,
)

/** The list on the left (mockup 14): one entry per page. */
object TvSettingsPages {
    val ORDER: List<TvSettingsPageId> = listOf(
        TvSettingsPageId.CONNECTION, TvSettingsPageId.REMOTE_MIC, TvSettingsPageId.VOICE, TvSettingsPageId.DISPLAY,
        TvSettingsPageId.TV_CONTROL, TvSettingsPageId.SERVER, TvSettingsPageId.ABOUT,
    )

    fun summary(id: TvSettingsPageId): String = when (id) {
        TvSettingsPageId.CONNECTION -> "Server, status, certificate"
        TvSettingsPageId.REMOTE_MIC -> "Hold the mic button to talk to Archie"
        TvSettingsPageId.VOICE -> "Live voice provider, model and voice"
        TvSettingsPageId.DISPLAY -> "Text size, key hints, motion"
        TvSettingsPageId.TV_CONTROL -> "Let agents open pages on this TV"
        TvSettingsPageId.SERVER -> "Models, agents, working directory"
        TvSettingsPageId.ABOUT -> "Versions and licenses"
    }
}

/** Live voice ends after this long with nobody talking (`TvVoicePrefs.liveIdleSeconds`, 0 = never). */
object LiveIdle {
    val SECONDS = listOf(60, 120, 180, 300, 600, 0)

    fun label(seconds: Int): String = when {
        seconds <= 0 -> "Never"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "$seconds s"
    }

    fun choice(current: Int): TvChoice {
        val opts = SECONDS.map { Option(it.toString(), label(it), if (it == 120) "Default" else null) }.toMutableList()
        if (opts.none { it.id == current.toString() }) opts.add(0, Option(current.toString(), label(current)))
        return TvChoice(
            "live-idle", "End live voice after", opts, current.toString(),
            note = "With nobody talking. Play/Pause or Back also ends it.",
        )
    }
}

/** Settings → Voice: the same server settings as the apps' Voice page (F-31, CFG-5, CFG-6). */
object TvVoiceSettings {
    const val PROVIDER = "voice-provider"
    const val ENDPOINT = "voice-endpoint"
    const val MODEL = "voice-model"
    const val VOICE = "voice-voice"
    const val LANGUAGE = "voice-language"

    fun choices(cfg: ServerConfig, st: ServerSettingsState): List<TvChoice> {
        val saving = st.saving != null
        val v = cfg.voice
        val catalog = VoiceLogic.catalog(st.catalogs.voiceModels, st.google)
        val providerIds = catalog.keys.toMutableList()
        if (!v.provider.isNullOrEmpty() && v.provider !in providerIds) providerIds.add(0, v.provider!!)
        val entries = catalog[v.provider].orEmpty()
        val entry = entries.firstOrNull { it.id == v.model }
        val models = entries.map { Option(it.id, it.label.ifEmpty { it.id }, it.description?.takeIf { d -> d.isNotEmpty() && d != it.label }) }.toMutableList()
        if (entry == null && !v.model.isNullOrEmpty()) models.add(0, Option(v.model!!, "${v.model} (not listed)"))
        val voices = VoiceLogic.voiceOptions(entry).toMutableList()
        if (!v.voice.isNullOrEmpty() && voices.none { it.id == v.voice }) voices.add(0, Option(v.voice!!, v.voice!!))
        val langs = VoiceLogic.languageOptions(entry).toMutableList()
        val lang = v.transcriptionLanguage.orEmpty()
        if (langs.isNotEmpty() && langs.none { it.id == lang }) langs += Option(lang, lang)
        return buildList {
            add(TvChoice(PROVIDER, "Provider", providerIds.map { Option(it, VoiceLogic.providerLabel(it)) }, v.provider, enabled = !saving))
            if (v.provider == "google") {
                add(TvChoice(ENDPOINT, "Google backend", VoiceLogic.GOOGLE_ENDPOINTS, v.endpoint, enabled = !saving, note = "Vertex AI is the stable path"))
            }
            add(TvChoice(MODEL, "Model", models, v.model, enabled = !saving && models.isNotEmpty()))
            if (voices.isNotEmpty()) add(TvChoice(VOICE, "Voice", voices, v.voice, enabled = !saving))
            if (langs.isNotEmpty()) add(TvChoice(LANGUAGE, "Transcription language", langs, lang, enabled = !saving, note = "Auto-detect is best when you mix languages"))
        }
    }

    fun patch(key: String, value: String): ConfigPatch? = when (key) {
        PROVIDER -> ConfigPatch(defaultVoiceProvider = value)
        ENDPOINT -> ConfigPatch(defaultVoiceEndpoint = value)
        MODEL -> ConfigPatch(defaultVoiceModel = value)
        VOICE -> ConfigPatch(defaultVoiceName = value)
        LANGUAGE -> ConfigPatch(defaultVoiceTranscriptionLanguage = value)
        else -> null
    }
}

/**
 * Settings → Server (spec 15 §3.5, phase-3 parity subset): conversation model, agent harness
 * defaults, the active working directory, MCP servers and the accounts' sign-in state.
 */
object TvServerSettings {
    const val TEXT_MODEL = "default_model"
    const val AUDIO_MODEL = "default_audio_model"
    const val HARNESS = "provider"
    const val HARNESS_MODEL = "harness_model"
    const val WORKING_DIR = "working_directory"
    const val CHROME = "chrome_extension"
    const val MCP_PREFIX = "mcp:"

    /** Text models of every provider, "Anthropic · Claude Sonnet" (the picker is one list on a TV). */
    private fun modelOptions(models: List<ModelInfoDto>, current: String?): List<Option> {
        val opts = models.filter { it.modelId.isNotEmpty() }.map { m ->
            val name = m.displayName.ifEmpty { m.modelId }
            val p = m.provider.takeIf { it.isNotEmpty() }?.let { "${ModelLogic.providerLabel(it)} · " }.orEmpty()
            Option(m.modelId, "$p$name", ModelLogic.traits(m).takeIf { it.isNotEmpty() })
        }.toMutableList()
        if (!current.isNullOrEmpty() && opts.none { it.id == current }) opts.add(0, Option(current, "$current (unavailable)", enabled = false))
        return opts
    }

    fun sections(cfg: ServerConfig, st: ServerSettingsState, accounts: List<AccountServiceDto>?): List<TvSettingsSection> {
        val saving = st.saving != null
        val c = st.catalogs
        val models = c.orchestratorModels?.models.orEmpty()
        val text = ModelLogic.textModels(models)
        val audio = ModelLogic.audioModels(models)
        val audioSupported = cfg.defaultAudioModel != null
        val serverAudio = c.orchestratorModels?.defaultAudioModel
        val conversation = TvSettingsSection(
            "Conversation model",
            choices = buildList {
                add(
                    TvChoice(
                        TEXT_MODEL, "Text model", modelOptions(text, cfg.defaultModel), cfg.defaultModel,
                        enabled = !saving && text.isNotEmpty(),
                        note = if (c.orchestratorModels == null) "Loading the model list…" else "Answers typed messages",
                    ),
                )
                if (audioSupported) {
                    add(
                        TvChoice(
                            AUDIO_MODEL, "Audio model",
                            listOf(Option("", "Server default", serverAudio?.let { "Now $it" })) + modelOptions(audio, cfg.defaultAudioModel.orEmpty().ifEmpty { null }),
                            cfg.defaultAudioModel.orEmpty(), enabled = !saving, note = "Answers voice messages",
                        ),
                    )
                }
            },
            elsewhere = "History summaries and voice tuning",
        )

        val harnesses = c.harnesses.orEmpty()
        val harnessOptions = harnesses.map { Option(it.id, it.label.ifEmpty { it.id }, it.description) }.toMutableList()
        if (!cfg.provider.isNullOrEmpty() && harnessOptions.none { it.id == cfg.provider }) harnessOptions.add(0, Option(cfg.provider!!, cfg.provider!!))
        val selected = HarnessLogic.info(harnesses, cfg.provider)
        val agents = TvSettingsSection(
            "Agent sessions",
            choices = buildList {
                add(
                    TvChoice(
                        HARNESS, "Default harness", harnessOptions, cfg.provider, enabled = !saving && harnessOptions.isNotEmpty(),
                        note = if (c.harnesses == null) "Loading the harness list…" else "For new agent sessions",
                    ),
                )
                if (selected != null) add(harnessModelChoice(cfg, selected.id, selected.catalog, selected.label, saving))
            },
            toggles = listOf(TvToggle(CHROME, "Claude in Chrome", cfg.chromeExtension, enabled = !saving, note = "Claude Code sessions can drive a browser")),
            elsewhere = "Harness options and custom model ids",
        )

        val dirs = WorkingDirectoryLogic.coerce(cfg.workingDirectoryHistory)
        val dirOptions = dirs.map { Option(it.id, WorkingDirectoryLogic.name(it), WorkingDirectoryLogic.detail(it)) }.toMutableList()
        if (!cfg.workingDirectory.isNullOrEmpty() && dirOptions.none { it.id == cfg.workingDirectory }) {
            dirOptions.add(0, Option(cfg.workingDirectory!!, cfg.workingDirectory!!))
        }
        val workingDir = TvSettingsSection(
            "Working directory",
            choices = if (dirOptions.isEmpty()) emptyList() else listOf(
                TvChoice(WORKING_DIR, "New sessions start in", dirOptions, cfg.workingDirectory, enabled = !saving),
            ),
            info = if (dirOptions.isEmpty()) listOf(TvInfoLine("dirs-none", "Directories", "None saved")) else emptyList(),
            elsewhere = "Adding, editing and SSH directories",
        )

        val names = c.mcpNames
        val mcp = TvSettingsSection(
            "MCP servers",
            toggles = names.map { name ->
                val on = McpLogic.isEnabled(cfg.enabledMcps, name)
                val blocked = if (on) McpLogic.offBlockedReason(cfg.enabledMcps, names, name) else null
                TvToggle(MCP_PREFIX + name, name, on, enabled = !saving && blocked == null, note = blocked)
            },
            info = when {
                c.mcpServers == null -> listOf(TvInfoLine("mcp-loading", "MCP servers", "Loading…"))
                names.isEmpty() -> listOf(TvInfoLine("mcp-none", "MCP servers", "None configured"))
                else -> emptyList()
            },
        )

        val accountLines = accounts?.map { s ->
            val state = AccountsModel.STATE_LABEL[s.state] ?: s.state
            val who = listOfNotNull(s.account?.takeIf { it.isNotEmpty() }, s.plan?.takeIf { it.isNotEmpty() }).joinToString(" · ")
            TvInfoLine("account:${s.id}", s.label.ifEmpty { s.id }, if (who.isEmpty()) state else "$state · $who", warn = s.state == "expired" || s.state == "signed_out")
        } ?: listOf(TvInfoLine("accounts-loading", "Accounts", "Loading…"))
        val accountsSection = TvSettingsSection("Accounts", info = accountLines, elsewhere = "Signing in and out, API keys")

        return listOf(conversation, agents, workingDir, mcp, accountsSection)
    }

    private fun harnessModelChoice(cfg: ServerConfig, id: String, catalog: com.assistant.core.model.HarnessCatalog?, label: String, saving: Boolean): TvChoice {
        val current = cfg.harnessModel[id].orEmpty()
        // No "Custom model id…" on a TV (that needs typing); a saved custom id stays listed as is.
        val items = HarnessLogic.modelItems(catalog, HarnessScope.GLOBAL, current)
            .filter { it.id != HarnessLogic.CUSTOM }
            .toMutableList()
        if (current.isNotEmpty() && items.none { it.id == current }) items += Option(current, "$current (custom)")
        return TvChoice(
            "$HARNESS_MODEL:$id", "${label.ifEmpty { id }} model", items,
            if (current.isEmpty()) HarnessLogic.CLI_DEFAULT else current, enabled = !saving,
        )
    }

    /** The PUT for a picked choice, or null when nothing should be saved. */
    fun patch(cfg: ServerConfig, key: String, value: String): ConfigPatch? = when {
        key == TEXT_MODEL -> ConfigPatch(defaultModel = value)
        key == AUDIO_MODEL -> ConfigPatch(defaultAudioModel = value)
        key == HARNESS -> ConfigPatch(provider = value)
        key == WORKING_DIR -> ConfigPatch(workingDirectory = value)
        key.startsWith("$HARNESS_MODEL:") -> {
            val provider = key.substringAfter(':')
            when (val pick = HarnessLogic.parseModelSelect(value)) {
                is ModelPick.Model -> HarnessLogic.globalModelPatch(provider, pick.model.orEmpty())
                ModelPick.Custom -> null
            }
        }
        else -> null
    }

    /** The PUT for a switch. MCP: the list McpLogic writes (empty = all on, CFG-4). */
    fun togglePatch(cfg: ServerConfig, mcpNames: List<String>, key: String, on: Boolean): ConfigPatch? = when {
        key == CHROME -> ConfigPatch(chromeExtension = on)
        key.startsWith(MCP_PREFIX) -> {
            val name = key.removePrefix(MCP_PREFIX)
            if (!on && McpLogic.offBlockedReason(cfg.enabledMcps, mcpNames, name) != null) null
            else ConfigPatch(enabledMcps = McpLogic.toggle(cfg.enabledMcps, mcpNames, name, on))
        }
        else -> null
    }
}

/** Settings → Connection / TV control: status lines. */
object TvSettingsStatus {
    fun connection(st: ConnectionStatus): Pair<String, String> {
        val where = st.serverLabel.ifEmpty { st.serverUrl?.let(::hostPortOf).orEmpty() }
        return when (st.phase) {
            ConnectionStatus.Phase.CONNECTED -> "Connected to $where" to "Archie answers on every screen of this TV."
            ConnectionStatus.Phase.CONNECTING -> "Connecting to $where…" to "Waiting for the server to answer."
            ConnectionStatus.Phase.RECONNECTING -> "Reconnecting to $where…" to "The connection dropped; retrying."
            ConnectionStatus.Phase.OFFLINE -> "Not connected" to "Pick a server, or check that Archie is running."
        }
    }

    enum class Tone { OK, WAIT, BAD }

    data class Line(val title: String, val body: String, val tone: Tone)

    fun control(s: TvControlState): Line = when (s) {
        TvControlState.Ready -> Line("Agents can reach this TV", "Pages they open appear here, also over YouTube.", Tone.OK)
        TvControlState.Idle -> Line("Not connected", "The control channel starts once the app reaches its server.", Tone.WAIT)
        is TvControlState.Connecting -> Line("Connecting…", "Opening the control channel (/api/tv/ws).", Tone.WAIT)
        is TvControlState.Waiting -> Line(
            "Reconnecting in ${(s.delayMs + 999) / 1000} s",
            s.reason?.let { "Last error: $it" } ?: "The server did not answer.",
            Tone.WAIT,
        )
        is TvControlState.Rejected -> Line("The server refused this TV", s.reason, Tone.BAD)
        TvControlState.Replaced -> Line("Another app took over", "A newer connection with this TV's id replaced this one.", Tone.BAD)
    }

    /** `host:port` of a server URL (default ports by scheme): the key of a pinned certificate. */
    fun hostPortOf(url: String): String {
        val scheme = url.substringBefore("://", "").lowercase()
        val rest = url.substringAfter("://").substringBefore('/')
        if (':' in rest.substringAfterLast(']')) return rest
        val port = when (scheme) { "wss", "https" -> 443; else -> 80 }
        return "$rest:$port"
    }

    fun isSecure(url: String?): Boolean = url != null && (url.startsWith("wss://") || url.startsWith("https://"))
}
