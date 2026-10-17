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

    /** Dialogs and sheets the screens open ([TvOverlay]); the KeyRouter sends keys to the top one. */
    val overlayHost = TvOverlayHost()

    /** The options sheet or a screen overlay shows. */
    val overlayShowing: Boolean get() = optionsOpen || overlayHost.active

    /** Opens the options sheet. */
    fun openOptions() {
        optionsOpen = true
    }

    /** Focus is inside the navigation drawer (set by the drawer's focus listener). */
    var drawerFocused by mutableStateOf(false)

    /** Something inside the shell (content, drawer, sheets) has focus. */
    var shellFocused by mutableStateOf(false)

    /** Something drawn outside the shell has focus (the voice overlay's dictation chip; set by the Activity). */
    var externalFocused by mutableStateOf(false)

    /** The back-stack entry focus last entered (set by the shell around every entry). */
    var focusedOn: NavKey? = null

    /** Nothing in the app has focus: the focused element went away (B3). */
    val focusLost: Boolean get() = !shellFocused && !externalFocused

    /** What components see of the shell's focus ([LocalTvShellFocus]). */
    val focusProbe: TvShellFocus = object : TvShellFocus {
        override fun misplaced() = focusLost || drawerFocused
        override val editingText = androidx.compose.runtime.mutableIntStateOf(0)
    }

    /** Moves focus from the drawer to the content (set by the shell; needs the FocusManager). */
    var focusContent: () -> Unit = {}

    /** The top entry's contract (handlers, options). */
    val screen: TvScreenContract? get() = registry.contract(nav.current)

    private val overlayKeys = TvKeyHandler { key ->
        when (key) {
            TvKey.MENU, TvKey.LONG_OK -> { optionsOpen = false; true }
            TvKey.PLAY_PAUSE -> false        // → the defaults: live voice still works over the sheet
            else -> true                     // nothing scrolls underneath
        }
    }

    private val defaults = TvKeyHandler { key ->
        when (key) {
            TvKey.MENU, TvKey.LONG_OK -> { openOptions(); true }
            TvKey.PLAY_PAUSE -> liveVoice()
            TvKey.REWIND, TvKey.FAST_FORWARD -> false
        }
    }

    val router = KeyRouter(
        overlay = { if (optionsOpen) overlayKeys else overlayHost.keys },
        screen = { screen?.let { s -> TvKeyHandler(s::onKey) } },
        defaults = defaults,
    )

    /** What components reach through `LocalTvKeyActions`. */
    val keyActions: TvKeyActions = object : TvKeyActions {
        override fun longPress() {
            router.route(TvKey.LONG_OK)
        }

        override fun openOptions() {
            this@TvShellController.openOptions()
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

        /**
         * The screens whose focus the shell puts back when it drops (B3). The Viewer and the memory
         * reader keep focus in their page / blocks themselves; Connect is a form.
         */
        fun refocusesWhenLost(key: NavKey?): Boolean =
            key is Home || key is Conversations || key is Conversation || key is Visuals || key is Memory || key is Settings || key is SettingsPage
    }
}
