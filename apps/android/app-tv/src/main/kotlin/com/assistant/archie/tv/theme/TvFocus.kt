package com.assistant.archie.tv.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Screen-level focus restoration (spec 15 §3.1 principle 3): remembers which element of a screen
 * had focus, by a stable string key, across leaving the screen and coming back (Navigation 3 keeps
 * a screen's saveable state while it is on the back stack). Lazy rows/lists restore their own scroll
 * position (saveable list states), so the remembered element is composed again when [restore] runs.
 *
 * Usage: `Modifier.focusMemory(memory, key)` on each focusable element, then
 * `if (!memory.restore()) initial.requestFocus()` after the first frame.
 */
@Stable
class FocusMemory(initial: String? = null) {
    /** The key of the element that last had focus on this screen. */
    var lastKey: String? = initial
        private set

    private val requesters = HashMap<String, FocusRequester>()

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun onFocused(key: String) {
        lastKey = key
    }

    /** Focuses the remembered element; false when there is none or it is not on screen. */
    fun restore(): Boolean {
        val k = lastKey ?: return false
        val r = requesters[k] ?: return false
        return runCatching { r.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
    }

    companion object {
        val Saver: Saver<FocusMemory, String> = Saver(save = { it.lastKey ?: "" }, restore = { FocusMemory(it.ifEmpty { null }) })
    }
}

@Composable
fun rememberFocusMemory(): FocusMemory = rememberSaveable(saver = FocusMemory.Saver) { FocusMemory() }

/** Registers this element under [key] in [memory] (focus requester + "last focused" tracking). */
fun Modifier.focusMemory(memory: FocusMemory, key: String): Modifier =
    this.focusRequester(memory.requester(key)).onFocusChanged { if (it.isFocused) memory.onFocused(key) }

/**
 * Keeps D-pad focus inside an overlay (sheet, dialog). Back is not a focus move here: Compose turns
 * Back into a focus "exit", and a cancelled exit would swallow the key, so Back is left alone and
 * handled by [dismissOnBack].
 */
fun Modifier.trapFocus(): Modifier = focusGroup().focusProperties {
    onExit = { if (requestedFocusDirection != FocusDirection.Exit) cancelFocusChange() }
}

/** Back (on key-up) closes the overlay that has focus, before Compose or the shell see the key. */
fun Modifier.dismissOnBack(onBack: () -> Unit): Modifier = onPreviewKeyEvent { e ->
    if (e.key != Key.Back) {
        false
    } else {
        if (e.type == KeyEventType.KeyUp) onBack()
        true
    }
}
