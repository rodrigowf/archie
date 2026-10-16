package com.assistant.archie.tv.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Full-screen layers above the shell (spec 15 §3.4: side sheets and dialogs cover the whole screen,
 * like the options sheet), for overlays a screen opens from inside its Navigation 3 entry, which
 * is laid out in the overscan-padded content box. A screen calls [TvOverlay]; the shell draws the
 * layers at its root, above the drawer and below toasts. The content keeps reading the screen's
 * state (it is the screen's own lambda), and leaves when the screen stops calling [TvOverlay].
 */
@Stable
class TvOverlayHost {
    internal class Layer(val content: State<@Composable () -> Unit>)

    internal val layers = mutableStateListOf<Layer>()
}

val LocalTvOverlayHost = staticCompositionLocalOf<TvOverlayHost?> { null }

/** Shows [content] as a full-screen layer of the shell (in place when there is no shell, e.g. a golden of one screen). */
@Composable
fun TvOverlay(content: @Composable () -> Unit) {
    val host = LocalTvOverlayHost.current
    if (host == null) {
        content()
        return
    }
    val latest = rememberUpdatedState(content)
    DisposableEffect(host) {
        val layer = TvOverlayHost.Layer(latest)
        host.layers.add(layer)
        onDispose { host.layers.remove(layer) }
    }
}

/** The shell's side: draws every layer, oldest first. */
@Composable
internal fun TvOverlayLayers(host: TvOverlayHost) {
    for (layer in host.layers) key(layer) { layer.content.value() }
}
