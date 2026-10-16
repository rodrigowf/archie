package com.assistant.archie.tv.shell

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The live navigator for code outside composition (spec 15 §4.1, §5.2): TV control commands
 * (`control/`) push screens, and the voice router (`voice/`) reads which screen is showing to
 * decide where a mic burst goes. The shell attaches the navigator when it composes and reports the
 * top of the back stack on every change; until then (and while the activity is gone) [navigator]
 * is null and [current] is null.
 *
 * [bringToFront] is set by the Application to start MainActivity from the background (a pushed
 * command while YouTube is in front); null in tests.
 */
class TvNavHolder {
    @Volatile var navigator: TvNavigator? = null
        private set

    private val _current = MutableStateFlow<NavKey?>(null)
    /** The screen on top of the back stack, or null when no shell is composed. */
    val current: StateFlow<NavKey?> = _current.asStateFlow()

    /** Starts (or brings back) the app's activity; set by the Application. */
    @Volatile var bringToFront: (() -> Unit)? = null

    fun attach(nav: TvNavigator) {
        navigator = nav
        _current.value = nav.current
    }

    fun detach(nav: TvNavigator) {
        if (navigator === nav) {
            navigator = null
            _current.value = null
        }
    }

    /** Called by the shell whenever the back stack changes. */
    fun onTopChanged(top: NavKey?) {
        _current.value = top
    }
}
