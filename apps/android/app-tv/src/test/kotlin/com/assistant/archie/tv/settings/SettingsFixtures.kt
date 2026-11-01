package com.assistant.archie.tv.settings

import com.assistant.archie.feature.settings.Catalogs
import com.assistant.archie.feature.settings.ServerSettingsState
import com.assistant.core.data.LoadState
import com.assistant.core.model.HarnessCatalog
import com.assistant.core.model.HarnessCatalogModel
import com.assistant.core.model.HarnessInfo
import com.assistant.core.model.ServerConfig
import com.assistant.core.model.VoiceConfig
import com.assistant.core.model.WorkingDirectory
import com.assistant.core.protocol.AccountServiceDto
import com.assistant.core.protocol.McpServersDto
import com.assistant.core.protocol.ModelInfoDto
import com.assistant.core.protocol.OrchestratorModelsDto
import com.assistant.core.protocol.VoiceModelEntryDto
import com.assistant.core.protocol.VoiceModelsDto
import com.assistant.core.protocol.VoiceOptionDto
import kotlinx.serialization.json.JsonObject

/** Server settings shaped like the Jetson's (2026-10), for the logic tests and the Settings goldens. */
object SettingsFixtures {
    val config = ServerConfig(
        workingDirectory = "/home/rodrigo/assistant",
        workingDirectoryHistory = listOf(
            WorkingDirectory("/home/rodrigo/assistant", "/home/rodrigo/assistant", "Archie", null, null, null, null),
            WorkingDirectory("/home/rodrigo/projects/qvcm", "/home/rodrigo/projects/qvcm", "", null, null, null, null),
            WorkingDirectory("laptop:/home/rodrigo/assistant", "/home/rodrigo/assistant", "Laptop", "laptop", "rodrigo", null, null),
        ),
        enabledMcps = emptyList(),
        chromeExtension = false,
        provider = "claude",
        defaultModel = "claude-sonnet-5",
        summarizerModel = "",
        harnessModel = mapOf("claude" to "opus"),
        voice = VoiceConfig(provider = "openai", model = "gpt-realtime", voice = "cedar", transcriptionLanguage = ""),
        voiceRecordingEnabled = false,
        voiceVadThreshold = 0.5,
        voiceVadMinSilenceMs = 2500,
        voiceMicGain = 1.0,
        defaultAudioModel = "",
    )

    private val models = listOf(
        ModelInfoDto("anthropic", "claude-sonnet-5", "Claude Sonnet 5", supportsVision = true, contextWindow = 200_000),
        ModelInfoDto("anthropic", "claude-opus-5-5", "Claude Opus 5.5", supportsVision = true, contextWindow = 200_000),
        ModelInfoDto("openai", "gpt-4.1", "GPT-4.1", supportsVision = true, contextWindow = 1_000_000),
        ModelInfoDto("openai", "gpt-audio", "GPT Audio", supportsAudio = true, contextWindow = 128_000),
    )

    private val voices = VoiceModelsDto(
        providers = mapOf(
            "openai" to listOf(
                VoiceModelEntryDto(
                    "gpt-realtime", "GPT Realtime", voice = "cedar",
                    voices = listOf(VoiceOptionDto("cedar", "Cedar"), VoiceOptionDto("marin", "Marin"), VoiceOptionDto("alloy", "Alloy")),
                    transcriptionLanguages = listOf(VoiceOptionDto("", "Auto-detect"), VoiceOptionDto("en", "English"), VoiceOptionDto("pt", "Portuguese")),
                    default = true,
                ),
                VoiceModelEntryDto("gpt-realtime-mini", "GPT Realtime mini", voice = "marin", voices = listOf(VoiceOptionDto("marin", "Marin"))),
            ),
            "qwen" to listOf(VoiceModelEntryDto("qwen3.8-omni-flash", "Qwen 3.8 Omni Flash", voice = "Cherry", voices = listOf(VoiceOptionDto("Cherry", "Cherry")))),
        ),
        defaultProvider = "openai",
        defaultModel = "gpt-realtime",
    )

    private val harnesses = listOf(
        HarnessInfo(
            "claude", "Claude Code", "Anthropic's agent CLI",
            HarnessCatalog(
                "claude",
                models = listOf(HarnessCatalogModel("opus", "Opus"), HarnessCatalogModel("sonnet", "Sonnet")),
                defaultModel = "sonnet",
            ),
        ),
        HarnessInfo("codex", "Codex", "OpenAI's agent CLI", HarnessCatalog("codex", models = listOf(HarnessCatalogModel("gpt-5.5", "GPT-5.5")))),
        HarnessInfo("qwen", "Qwen Code", null, null),
    )

    val state = ServerSettingsState(
        config = LoadState(value = config),
        catalogs = Catalogs(
            orchestratorModels = OrchestratorModelsDto(models = models, defaultModel = "claude-sonnet-5", defaultAudioModel = "gpt-audio"),
            voiceModels = voices,
            harnesses = harnesses,
            mcpServers = McpServersDto(servers = mapOf("chrome-devtools" to JsonObject(emptyMap()), "atlassian" to JsonObject(emptyMap()), "posthog" to JsonObject(emptyMap()))),
        ),
    )

    val accounts = listOf(
        AccountServiceDto("claude", "Claude Code", group = "harness", state = "signed_in", account = "rodrigo@…", plan = "Max"),
        AccountServiceDto("codex", "Codex", group = "harness", state = "expired"),
        AccountServiceDto("openai", "OpenAI API", group = "api", state = "signed_in"),
    )
}
