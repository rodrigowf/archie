package com.assistant.archie.tv.control

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** `scroll` directions (RD-3). */
enum class ScrollDirection {
    UP, DOWN, TOP, BOTTOM;

    companion object {
        fun parse(s: String?): ScrollDirection? = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) }
    }
}

/** `media` actions (RD-3). */
enum class MediaAction {
    PLAY, PAUSE, TOGGLE;

    companion object {
        fun parse(s: String?): MediaAction? = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) }
    }
}

/** What a screen reports for the `state` frame (RD-5) beyond its back-stack key. */
data class RemoteInfo(val url: String? = null, val title: String? = null, val path: String? = null)

/**
 * A screen the control channel can drive beyond navigation: the Viewer (page history, reload,
 * scroll, media) and MemoryDoc (reload, scroll). T2's conversation screen can register one too.
 * All calls arrive on the main thread.
 */
interface RemoteScreen {
    /** In-screen Back (page history); false = nothing to go back to here. */
    fun back(): Boolean = false

    /** false = this screen cannot reload. */
    fun reload(): Boolean = false

    /** false = this screen cannot scroll. */
    fun scroll(direction: ScrollDirection): Boolean = false

    /** Page media: true = playing, false = paused, null = no media / not supported. */
    suspend fun media(action: MediaAction): Boolean? = null
}

/**
 * The screens currently composed that registered a [RemoteScreen], keyed by their back-stack key
 * (Navigation 3 keys are data classes: the key identifies the entry). [info] carries the url / title
 * the `state` frame reports.
 */
class TvRemoteScreens {
    private val screens = LinkedHashMap<NavKey, RemoteScreen>()
    private val _info = MutableStateFlow<Map<NavKey, RemoteInfo>>(emptyMap())
    val info: StateFlow<Map<NavKey, RemoteInfo>> = _info.asStateFlow()

    @Synchronized fun register(key: NavKey, screen: RemoteScreen) {
        screens[key] = screen
    }

    @Synchronized fun unregister(key: NavKey, screen: RemoteScreen) {
        if (screens[key] === screen) {
            screens.remove(key)
            _info.update { it - key }
        }
    }

    @Synchronized fun of(key: NavKey?): RemoteScreen? = key?.let { screens[it] }

    fun report(key: NavKey, info: RemoteInfo) = _info.update { if (it[key] == info) it else it + (key to info) }
}

/** Registers [screen] for [key] while composed and reports [info] (state frames). */
@Composable
fun RemoteScreenEffect(remote: TvRemoteScreens?, key: NavKey, info: RemoteInfo, screen: RemoteScreen) {
    if (remote == null) return
    val latest by rememberUpdatedState(screen)
    DisposableEffect(remote, key) {
        val proxy = object : RemoteScreen {
            override fun back() = latest.back()
            override fun reload() = latest.reload()
            override fun scroll(direction: ScrollDirection) = latest.scroll(direction)
            override suspend fun media(action: MediaAction) = latest.media(action)
        }
        remote.register(key, proxy)
        onDispose { remote.unregister(key, proxy) }
    }
    DisposableEffect(remote, key, info) {
        remote.report(key, info)
        onDispose { }
    }
}
