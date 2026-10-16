package com.assistant.archie.tv.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavKey

/**
 * The shell's non-UI state: navigation, the screen contracts, the options sheet, drawer focus, and
 * the [KeyRouter] the Activity's `dispatchKeyEvent` feeds. Plain class (remembered by the Activity's
 * content) so the routing and Back rules are testable without Compose.
 *
 * @param liveVoice Play/Pause's global default (the T4 seam `TvAppGraph.liveVoice`).
 * @param onLeaveApp Back at Home (finishes the Activity; sessions stay, they are process-scoped).
 */
@Stable
class TvShellController(
    val backStack: MutableList<NavKey>,
    private val liveVoice: () -> Boolean = { false },
    private val onLeaveApp: () -> Unit = {},
) {
    val nav = TvNavigator(backStack)
    val registry = ScreenChromeRegistry()

    /** The options sheet (Menu) is showing. */
    var optionsOpen by mutableStateOf(false)

    /** Focus is inside the navigation drawer (set by the drawer's focus listener). */
    var drawerFocused by mutableStateOf(false)

    /** Moves focus from the drawer to the content (set by the shell; needs the FocusManager). */
    var focusContent: () -> Unit = {}

    /** The top entry's contract (handlers, options). */
    val screen: TvScreenContract? get() = registry.contract(nav.current)

    private val overlayKeys = TvKeyHandler { key ->
        when (key) {
            TvKey.MENU, TvKey.LONG_OK -> { optionsOpen = false; true }
            TvKey.PLAY_PAUSE -> false        // live voice still works over the sheet
            else -> true                     // nothing scrolls underneath
        }
    }

    private val defaults = TvKeyHandler { key ->
        when (key) {
            TvKey.MENU, TvKey.LONG_OK -> { optionsOpen = true; true }
            TvKey.PLAY_PAUSE -> liveVoice()
            TvKey.REWIND, TvKey.FAST_FORWARD -> false
        }
    }

    val router = KeyRouter(
        overlay = { if (optionsOpen) overlayKeys else null },
        screen = { screen?.let { s -> TvKeyHandler(s::onKey) } },
        defaults = defaults,
    )

    /** What components reach through `LocalTvKeyActions`. */
    val keyActions: TvKeyActions = object : TvKeyActions {
        override fun longPress() {
            router.route(TvKey.LONG_OK)
        }

        override fun openOptions() {
            optionsOpen = true
        }
    }

    /** Back (spec 15 §3.3): see [BackPolicy]. Screens with their own Back (viewer history) register a BackHandler instead. */
    fun back() {
        when (BackPolicy.decide(optionsOpen, drawerFocused, backStack)) {
            BackAction.CLOSE_OVERLAY -> optionsOpen = false
            BackAction.FOCUS_CONTENT -> focusContent()
            BackAction.POP -> nav.pop()
            BackAction.GO_HOME -> nav.selectRoot(TvDestination.HOME)
            BackAction.LEAVE_APP -> onLeaveApp()
        }
    }

    /**
     * Runs an option of the sheet by id: the shell's global entries, else the top screen's option
     * (the sheet shows views; the lambda is looked up at click time).
     */
    fun runOption(id: String) {
        optionsOpen = false
        when (id) {
            GLOBAL_HOME -> nav.selectRoot(TvDestination.HOME)
            GLOBAL_SETTINGS -> nav.selectRoot(TvDestination.SETTINGS)
            else -> screen?.options?.firstOrNull { it.id == id && it.enabled }?.onSelect?.invoke()
        }
    }

    companion object {
        const val GLOBAL_HOME = "global:home"
        const val GLOBAL_SETTINGS = "global:settings"
    }
}
