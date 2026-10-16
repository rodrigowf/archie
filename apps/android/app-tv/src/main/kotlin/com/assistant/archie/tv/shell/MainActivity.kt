package com.assistant.archie.tv.shell

import com.assistant.archie.tv.voice.VoiceOverlayHost
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import com.assistant.archie.feature.toolcards.LocalToolImageLoader
import com.assistant.archie.tv.graph.TvAppGraph
import com.assistant.archie.tv.graph.TvGraphOwner
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.core.design.components.ArchieMark
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The single Activity (spec 15 §6.1). It holds no domain state: everything lives in the
 * process-scoped [TvAppGraph], so Back at Home (finish) never closes a session.
 *
 * [dispatchKeyEvent] is the activity-level KeyRouter hook: Menu, Play/Pause, Rewind/FF and
 * long-press OK go to the current screen's handler or the global default before Compose's focus
 * system sees them (spec 15 §3.3).
 */
class MainActivity : ComponentActivity() {
    private val graph: TvAppGraph get() = (application as TvGraphOwner).graph

    /** Set by the content once composed; null before the first frame (keys then go to Compose as usual). */
    internal var controller: TvShellController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph.connection.start()
        setContent { TvApp(graph, onController = { controller = it }, onLeaveApp = ::finish) }
    }

    // RestrictedApi: androidx.core's ComponentActivity re-declares the framework's
    // Activity.dispatchKeyEvent with @RestrictTo; overriding the framework hook is the intended use.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val router = controller?.router ?: return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return super.dispatchKeyEvent(event)
        val raw = RawKey(
            code = event.keyCode,
            down = event.action == KeyEvent.ACTION_DOWN,
            repeat = event.repeatCount,
            longPress = event.isLongPress,
        )
        return router.dispatch(raw) { super.dispatchKeyEvent(event) }
    }
}

/** The root composable: TV theme from the TV prefs, splash until settings load, then the shell. */
@Composable
fun TvApp(graph: TvAppGraph, onController: (TvShellController) -> Unit, onLeaveApp: () -> Unit) {
    val display by graph.prefs.display.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    TvTheme(textSize = display.textSize, reduceMotion = display.reduceMotion) {
        // Nobody acts on default settings (inv04 B4): the Archie mark until DataStore has loaded.
        if (settings == null) Splash() else TvAppContent(graph, onController, onLeaveApp, display.showKeyHints)
    }
}

@Composable
private fun TvAppContent(graph: TvAppGraph, onController: (TvShellController) -> Unit, onLeaveApp: () -> Unit, showKeyHints: Boolean) {
    val first: NavKey = remember { if (graph.prefs.setupDone) Home else Connect(firstRun = true) }
    val backStack = rememberNavBackStack(first)
    val controller = remember(backStack) {
        TvShellController(backStack, liveVoice = { graph.liveVoice.toggle() }, onLeaveApp = onLeaveApp).also(onController)
    }
    val ctx = remember(controller) { TvRouteContext(graph, controller.nav) }
    DisposableEffect(controller) {
        graph.navHolder.attach(controller.nav)
        onDispose { graph.navHolder.detach(controller.nav) }
    }
    val top = backStack.lastOrNull()
    LaunchedEffect(top) { graph.navHolder.onTopChanged(top) }
    val connection by graph.connection.status.collectAsStateWithLifecycle()
    val voice by graph.voice.state.collectAsStateWithLifecycle()
    // The drawer's amber dot (spec 15 §3.5): an open conversation waits for an approval.
    val needsApproval by remember(graph) {
        graph.openSessions.items.map { list -> list.any { it.status == com.assistant.core.data.TabStatus.NEEDS_YOU } }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(false)
    val state = TvShellState(connection = connection, voice = voice, showKeyHints = showKeyHints, needsApproval = needsApproval)
    CompositionLocalProvider(LocalToolImageLoader provides graph.toolImages) {
        Box(Modifier.fillMaxSize()) {
            TvShell(state, controller, graph.toasts) { key -> TvRoutes.Entry(key, ctx) }
            VoiceOverlayHost(graph)
        }
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize().background(TvTheme.colors.surface), contentAlignment = Alignment.Center) {
        ArchieMark(size = 96.dp, contentDescription = "Archie")
    }
}
