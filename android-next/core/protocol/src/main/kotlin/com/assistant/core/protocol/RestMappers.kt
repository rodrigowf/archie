package com.assistant.core.protocol

import com.assistant.core.model.AudioFormat
import com.assistant.core.model.AuthStatus
import com.assistant.core.model.ConfigPatch
import com.assistant.core.model.ConnectionInfo
import com.assistant.core.model.HarnessProvider
import com.assistant.core.model.LiveStatus
import com.assistant.core.model.MemoryNode
import com.assistant.core.model.PoolSession
import com.assistant.core.model.ServerConfig
import com.assistant.core.model.SessionConfig
import com.assistant.core.model.SessionSummary
import com.assistant.core.model.UploadResult
import com.assistant.core.model.VisualInfo
import com.assistant.core.model.VoiceConfig
import com.assistant.core.model.VoiceConnectionType
import com.assistant.core.model.WorkingDirectory
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.encodeToJsonElement

/** DTO ↔ `:core:model` mappings. */

fun SessionInfoDto.toModel() = SessionSummary(
    sdkId = sessionId,
    startedAt = startedAt,
    lastActivity = lastActivity,
    title = title,
    messageCount = messageCount,
    isOrchestrator = isOrchestrator,
    provider = HarnessProvider.fromWire(provider),
    localId = localId,
)

fun PoolSessionDto.toModel() = PoolSession(
    localId = localId,
    sdkId = sdkSessionId,
    status = LiveStatus.fromWire(status),
    cost = cost,
    turns = turns,
    title = title,
    isOrchestrator = isOrchestrator,
)

fun AuthStatusDto.toModel() = AuthStatus(authenticated, authUrl, headless)

fun VisualizationInfoDto.toModel() = VisualInfo(path, url, title, created, modified, size)

fun MemoryNodeDto.toModel(): MemoryNode = MemoryNode(name, path, isDir, children?.map { it.toModel() })

fun UploadResultDto.toModel() = UploadResult(filename, path, url, size, contentType)

fun WorkingDirectoryDto.toModel() = WorkingDirectory(id, path, label, sshHost, sshUser, sshKey, claudeConfigDir)

fun WorkingDirectory.toDto() = WorkingDirectoryDto(id, path, label, sshHost, sshUser, sshKey, claudeConfigDir)

fun ConfigDto.toModel() = ServerConfig(
    workingDirectory = workingDirectory,
    workingDirectoryHistory = workingDirectoryHistory.map { it.toModel() },
    enabledMcps = enabledMcps,
    chromeExtension = chromeExtension,
    provider = provider,
    defaultModel = defaultModel,
    summarizerModel = summarizerModel,
    harnessModel = harnessModel,
    voice = VoiceConfig(
        provider = defaultVoiceProvider,
        model = defaultVoiceModel,
        voice = defaultVoiceName,
        transcriptionLanguage = defaultVoiceTranscriptionLanguage,
        endpoint = defaultVoiceEndpoint,
    ),
    voiceRecordingEnabled = voiceRecordingEnabled,
    voiceVadThreshold = voiceVadThreshold,
    voiceVadMinSilenceMs = voiceVadMinSilenceMs,
    voiceMicGain = voiceMicGain,
)

fun ConfigPatch.toDto() = ConfigUpdateDto(
    workingDirectory = workingDirectory,
    workingDirectoryHistory = workingDirectoryHistory?.map { it.toDto() },
    enabledMcps = enabledMcps,
    chromeExtension = chromeExtension,
    provider = provider,
    defaultModel = defaultModel,
    summarizerModel = summarizerModel,
    harnessModel = harnessModel,
    defaultVoiceProvider = defaultVoiceProvider,
    defaultVoiceModel = defaultVoiceModel,
    defaultVoiceName = defaultVoiceName,
    defaultVoiceTranscriptionLanguage = defaultVoiceTranscriptionLanguage,
    defaultVoiceEndpoint = defaultVoiceEndpoint,
    voiceRecordingEnabled = voiceRecordingEnabled,
    voiceVadThreshold = voiceVadThreshold,
    voiceVadMinSilenceMs = voiceVadMinSilenceMs,
    voiceMicGain = voiceMicGain,
)

fun SessionConfigDto.toModel() = SessionConfig(workingDirectory, enabledMcps, chromeExtension, provider, harnessModel)

/**
 * Body of `PUT /api/sessions/{sdkId}/config`: only changed keys (spec 12 §6.14). Keys named in
 * [inherit] are sent as JSON `null` ("inherit global"); other null fields are not sent.
 */
fun SessionConfig.toPutBody(inherit: Set<String> = emptySet()): JsonObject {
    val map = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    workingDirectory?.let { map["working_directory"] = JsonPrimitive(it) }
    enabledMcps?.let { list -> map["enabled_mcps"] = buildJsonArray { list.forEach { add(JsonPrimitive(it)) } } }
    chromeExtension?.let { map["chrome_extension"] = JsonPrimitive(it) }
    provider?.let { map["provider"] = JsonPrimitive(it) }
    harnessModel?.let { map["harness_model"] = JsonPrimitive(it) }
    for (key in inherit) map[key] = JsonNull
    return JsonObject(map)
}

fun AudioFormatDto.toModel() = AudioFormat(sampleRate, encoding)

fun ConnectionInfoDto.toModel() = ConnectionInfo(
    connectionType = VoiceConnectionType.fromWire(connectionType),
    endpoint = endpoint,
    ephemeralToken = ephemeralToken,
    expiresAt = expiresAt,
    audioIn = audioInFormat?.toModel() ?: AudioFormat.DEFAULT_24K,
    audioOut = audioOutFormat?.toModel() ?: AudioFormat.DEFAULT_24K,
    model = model,
    voice = voice,
    audioRelay = audioRelay,
)

fun ConnectionInfo.toDto() = ConnectionInfoDto(
    connectionType = connectionType?.wire,
    endpoint = endpoint,
    ephemeralToken = ephemeralToken,
    expiresAt = expiresAt,
    audioInFormat = AudioFormatDto(audioIn.sampleRate, audioIn.encoding),
    audioOutFormat = AudioFormatDto(audioOut.sampleRate, audioOut.encoding),
    model = model,
    voice = voice,
    audioRelay = audioRelay,
)

internal fun ConnectionInfo.toJson(): JsonObject = RestJson.encodeToJsonElement(toDto()) as JsonObject
