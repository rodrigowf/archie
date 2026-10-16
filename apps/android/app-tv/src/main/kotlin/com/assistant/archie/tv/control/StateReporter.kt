package com.assistant.archie.tv.control

import androidx.navigation3.runtime.NavKey
import com.assistant.archie.tv.shell.Connect
import com.assistant.archie.tv.shell.Conversation
import com.assistant.archie.tv.shell.Conversations
import com.assistant.archie.tv.shell.Home
import com.assistant.archie.tv.shell.Memory
import com.assistant.archie.tv.shell.MemoryDoc
import com.assistant.archie.tv.shell.Settings
import com.assistant.archie.tv.shell.SettingsPage
import com.assistant.archie.tv.shell.Viewer
import com.assistant.archie.tv.shell.Visuals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject

/**
 * The `state` frame (spec 12 §9.5 RD-5): `screen` (one of [TvScreens]), `url` / `title` / `path`
 * of the Viewer or memory note, `conversation` (the open conversation's id), `voice` (the status
 * chip's label, lower case) and `foreground` (false while another app is in front).
 */
object TvScreens {
    const val NONE = "none"

    fun name(key: NavKey?): String = when (key) {
        null -> NONE
        Home -> "home"
        Conversations -> "conversations"
        is Conversation -> "conversation"
        Visuals -> "visuals"
        is Viewer -> "viewer"
        Memory -> "memory"
        is MemoryDoc -> "memory_doc"
        Settings, is SettingsPage -> "settings"
        is Connect -> "connect"
        else -> key.javaClass.simpleName.lowercase()
    }

    fun frame(key: NavKey?, info: RemoteInfo?, voice: String?, foreground: Boolean): JsonObject = TvControlProtocol.obj(
        "screen" to name(key),
        "url" to info?.url,
        "title" to (info?.title ?: (key as? Viewer)?.title),
        "path" to (info?.path ?: (key as? MemoryDoc)?.path),
        "conversation" to (key as? Conversation)?.let { it.localId ?: it.sdkId },
        "voice" to voice,
        "foreground" to foreground,
    )
}

/** Sends a `state` frame whenever what the frame says changes (RD-5); the client re-sends it after every `ready`. */
class StateReporter(
    private val current: Flow<NavKey?>,
    private val info: Flow<Map<NavKey, RemoteInfo>>,
    private val voice: Flow<String?>,
    private val foreground: Flow<Boolean>,
    private val send: (String) -> Unit,
) {
    fun start(scope: CoroutineScope) {
        combine(current, info, voice, foreground) { key, infos, v, fg -> TvScreens.frame(key, key?.let(infos::get), v, fg) }
            .distinctUntilChanged()
            .onEach { send(TvControlProtocol.state(it)) }
            .launchIn(scope)
    }
}
