package com.assistant.core.model

/** A backend the device talks to. URL scheme mapping (ws/http) lives in `:core:network`. */
data class ServerEndpoint(
    /** Base URL as the user entered or discovery found it, e.g. `https://192.168.0.200`. */
    val url: String,
    val label: String? = null,
)

/** `GET /api/auth/status` (inv01 §3.1). */
data class AuthStatus(
    val authenticated: Boolean,
    val authUrl: String?,
    val headless: Boolean,
)

/** One `working_directory_history` entry (inv01 §3.6). */
data class WorkingDirectory(
    val id: String,
    val path: String,
    val label: String?,
    val sshHost: String?,
    val sshUser: String?,
    val sshKey: String?,
    val claudeConfigDir: String?,
)

/** `GET /api/config` (inv01 §3.6). `enabledMcps` empty = all enabled (CFG-4). */
data class ServerConfig(
    val workingDirectory: String?,
    val workingDirectoryHistory: List<WorkingDirectory>,
    val enabledMcps: List<String>,
    val chromeExtension: Boolean,
    val provider: String?,
    val defaultModel: String?,
    val summarizerModel: String?,
    val harnessModel: Map<String, String>,
    val voice: VoiceConfig,
    val voiceRecordingEnabled: Boolean,
    val voiceVadThreshold: Double?,
    val voiceVadMinSilenceMs: Int?,
    val voiceMicGain: Double?,
)

/**
 * Partial `PUT /api/config` body. `null` = "leave unchanged" (the field is not sent).
 * `workingDirectoryHistory` is a full replacement when present (CFG-7).
 */
data class ConfigPatch(
    val workingDirectory: String? = null,
    val workingDirectoryHistory: List<WorkingDirectory>? = null,
    val enabledMcps: List<String>? = null,
    val chromeExtension: Boolean? = null,
    val provider: String? = null,
    val defaultModel: String? = null,
    val summarizerModel: String? = null,
    val harnessModel: Map<String, String>? = null,
    val defaultVoiceProvider: String? = null,
    val defaultVoiceModel: String? = null,
    val defaultVoiceName: String? = null,
    val defaultVoiceTranscriptionLanguage: String? = null,
    val defaultVoiceEndpoint: String? = null,
    val voiceRecordingEnabled: Boolean? = null,
    val voiceVadThreshold: Double? = null,
    val voiceVadMinSilenceMs: Int? = null,
    val voiceMicGain: Double? = null,
)

/**
 * Per-session config (`GET/PUT /api/sessions/{sdkId}/config`). `null` = inherit global.
 * On PUT only non-null keys are sent unless [clear] names them (sent as JSON `null`).
 */
data class SessionConfig(
    val workingDirectory: String? = null,
    val enabledMcps: List<String>? = null,
    val chromeExtension: Boolean? = null,
    val provider: String? = null,
    val harnessModel: String? = null,
)

/** A node of `GET /api/memory/tree`. `children == null` for files. */
data class MemoryNode(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val children: List<MemoryNode>?,
)

/** One item of `GET /api/visualizations`. `url` is NOT percent-encoded (G-38, VZ-1). */
data class VisualInfo(
    val path: String,
    val url: String,
    val title: String,
    val created: String?,
    val modified: String?,
    val size: Long,
)

/** `POST /api/uploads` response. */
data class UploadResult(
    val filename: String,
    val path: String,
    val url: String,
    val size: Long,
    val contentType: String,
)
