package com.assistant.archie.tv.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusPropertiesModifierNode
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.restoreFocusedChild
import androidx.compose.ui.focus.saveFocusedChild
import androidx.compose.ui.node.ModifierNodeElement

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
        if (FocusSettling.active) return
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

/**
 * True while focus moves without the user (B3): an overlay shows, or has just closed and the shell
 * is putting focus back. When an overlay's focused element goes away, Compose itself parks focus on
 * the nearest element of the screen behind; screens' "last focused" trackers ([FocusMemory], the
 * transcript's last block, …) ignore focus events while this is set, so the shell's restore uses
 * where the user really was. Main thread; set by the shell.
 */
object FocusSettling {
    @Volatile var active: Boolean = false
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

/**
 * For an overlay's focus group (sheet, dialog): when the focused element inside it goes away (an
 * agent's Stop button once it stopped, a row that left; the Fire TV keyboard closing over a field),
 * focus goes to [target], else the group's first element, instead of nowhere or the drawer (B3).
 * Put it before [trapFocus] so the request targets the group.
 */
@Composable
fun rememberRefocusInside(target: FocusRequester? = null): Modifier {
    val requester = remember { FocusRequester() }
    var had by remember { mutableStateOf(false) }
    var has by remember { mutableStateOf(false) }
    LaunchedEffect(has) {
        if (has || !had) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { (target ?: requester).requestFocus() }
    }
    return remember {
        Modifier.focusRequester(requester).onFocusChanged {
            has = it.hasFocus
            if (it.hasFocus) had = true
        }
    }
}

/**
 * Compose's `focusRestorer`, for D-pad entries only: moving into the container (Up/Down/Left/Right)
 * lands on the child that had focus last, else [fallback]. Compose's own restorer also runs for an
 * explicit `requestFocus` of an element inside it — a screen putting focus back after an overlay
 * (FocusMemory, B3) — and redirects that request to its saved child; this one leaves it alone.
 */
fun Modifier.dpadFocusRestorer(fallback: FocusRequester = FocusRequester.Default): Modifier = this then DpadFocusRestorerElement(fallback)

private data class DpadFocusRestorerElement(val fallback: FocusRequester) : ModifierNodeElement<DpadFocusRestorerNode>() {
    override fun create() = DpadFocusRestorerNode(fallback)
    override fun update(node: DpadFocusRestorerNode) {
        node.fallback = fallback
    }
}

private class DpadFocusRestorerNode(var fallback: FocusRequester) : Modifier.Node(), FocusPropertiesModifierNode, FocusRequesterModifierNode {
    override fun applyFocusProperties(focusProperties: FocusProperties) {
        focusProperties.onExit = { saveFocusedChild() }
        focusProperties.onEnter = {
            if (requestedFocusDirection != FocusDirection.Enter && !restoreFocusedChild() && fallback != FocusRequester.Default) {
                fallback.requestFocus()
            }
        }
    }
}
