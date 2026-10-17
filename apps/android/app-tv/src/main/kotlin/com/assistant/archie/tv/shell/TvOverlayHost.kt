package com.assistant.archie.tv.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Full-screen layers above the shell (spec 15 §3.4: side sheets and dialogs cover the whole screen,
 * like the options sheet), for overlays a screen opens from inside its Navigation 3 entry, which
 * is laid out in the overscan-padded content box. A screen calls [TvOverlay]; the shell draws the
 * layers at its root, above the drawer and below toasts. The content keeps reading the screen's
 * state (it is the screen's own lambda), and leaves when the screen stops calling [TvOverlay].
 *
 * The layers are also the KeyRouter's: while one shows, Menu / Rewind / FF / long-press OK go to the
 * top layer's handler and never to the screen behind it (B6), and when the last one closes the shell
 * gives focus back to the element the screen had (B3).
 */
@Stable
class TvOverlayHost {
    internal class Layer(val content: State<@Composable () -> Unit>, val keys: State<TvKeyHandler?>)

    internal val layers = mutableStateListOf<Layer>()

    /** Some screen overlay is showing. */
    val active: Boolean get() = layers.isNotEmpty()

    /**
     * The top layer's key handler: its own, else [Default]. Null while no layer shows. A handler that
     * returns false hands the key to the global defaults (Play/Pause → live voice), never to the screen.
     */
    val keys: TvKeyHandler? get() = layers.lastOrNull()?.let { it.keys.value ?: Default }

    companion object {
        /** A dialog / sheet without its own keys: Play/Pause stays live voice, everything else is ignored. */
        val Default = TvKeyHandler { key -> key != TvKey.PLAY_PAUSE }
    }
}

val LocalTvOverlayHost = staticCompositionLocalOf<TvOverlayHost?> { null }

/**
 * What components may ask the shell about focus (B3). [misplaced]: nothing has focus, or the drawer
 * took it without the user going there — e.g. after the Fire TV's full-screen keyboard closed.
 * [editingText] counts text fields in edit mode (an overlay closing then never moves focus).
 */
interface TvShellFocus {
    fun misplaced(): Boolean

    val editingText: MutableIntState

    object None : TvShellFocus {
        override fun misplaced() = false
        override val editingText: MutableIntState = mutableIntStateOf(0)
    }
}

val LocalTvShellFocus = staticCompositionLocalOf<TvShellFocus> { TvShellFocus.None }

/**
 * Shows [content] as a full-screen layer of the shell (in place when there is no shell, e.g. a golden
 * of one screen). [keys] handles Menu / Play/Pause / Rewind / FF / long-press OK while this layer is
 * on top ([TvOverlayHost.Default] when null).
 */
@Composable
fun TvOverlay(keys: TvKeyHandler? = null, content: @Composable () -> Unit) {
    val host = LocalTvOverlayHost.current
    if (host == null) {
        content()
        return
    }
    val latest = rememberUpdatedState(content)
    val latestKeys = rememberUpdatedState(keys)
    DisposableEffect(host) {
        val layer = TvOverlayHost.Layer(latest, latestKeys)
        host.layers.add(layer)
        onDispose { host.layers.remove(layer) }
    }
}

/** The shell's side: draws every layer, oldest first. */
@Composable
internal fun TvOverlayLayers(host: TvOverlayHost) {
    for (layer in host.layers) key(layer) { layer.content.value() }
}
