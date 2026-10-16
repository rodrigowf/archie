package com.assistant.archie.tv.shell

import androidx.compose.runtime.Stable
import androidx.navigation3.runtime.NavKey

/** The five drawer destinations (spec 15 §3.4, TV-6), in drawer order. */
enum class TvDestination(val label: String, val key: NavKey) {
    HOME("Home", Home),
    CONVERSATIONS("Conversations", Conversations),
    VISUALS("Visuals", Visuals),
    MEMORY("Memory", Memory),
    SETTINGS("Settings", Settings),
    ;

    companion object {
        /** The drawer item a back stack belongs to (its root), or null (first-run Connect). */
        fun of(root: NavKey?): TvDestination? = entries.firstOrNull { it.key == root }
    }
}

/**
 * Navigation over the Navigation 3 back stack (a `SnapshotStateList` in the app, any list in tests).
 * Screens get it through `TvRouteContext.nav`; they never touch the list directly.
 */
@Stable
class TvNavigator(private val backStack: MutableList<NavKey>) {
    val current: NavKey? get() = backStack.lastOrNull()
    val root: NavKey? get() = backStack.firstOrNull()
    val depth: Int get() = backStack.size

    /** Pushes content on top (no-op when it is already on top). */
    fun push(key: NavKey) {
        if (backStack.lastOrNull() != key) backStack.add(key)
    }

    /** Pops one level; false at the root. */
    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    /** A drawer destination replaces the whole stack (spec 15 §3.4). */
    fun selectRoot(destination: TvDestination) = replaceRoot(destination.key)

    /** Replaces the whole stack with [key] (first run → Home, drawer selection). */
    fun replaceRoot(key: NavKey) {
        if (backStack.size == 1 && backStack[0] == key) return
        backStack.add(key)
        while (backStack.size > 1) backStack.removeAt(0)
    }

    /** Pops back to the root. */
    fun popToRoot() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
}

/** What Back does (spec 15 §3.3, TV-7): overlays first, then pop, then Home, then leave the app. */
enum class BackAction { CLOSE_OVERLAY, FOCUS_CONTENT, POP, GO_HOME, LEAVE_APP }

object BackPolicy {
    /**
     * @param overlayOpen the options sheet (or another shell overlay) is showing.
     * @param drawerFocused focus is in the navigation drawer.
     * @param stack the back stack, root first.
     */
    fun decide(overlayOpen: Boolean, drawerFocused: Boolean, stack: List<NavKey>): BackAction = when {
        overlayOpen -> BackAction.CLOSE_OVERLAY
        // Back in the drawer closes it (focus returns to the content), as in tv-material samples.
        drawerFocused -> BackAction.FOCUS_CONTENT
        stack.size > 1 -> BackAction.POP
        // A drawer root other than Home goes Home first; Home and the first-run Connect leave the app.
        TvDestination.of(stack.firstOrNull()) != null && stack.firstOrNull() != Home -> BackAction.GO_HOME
        else -> BackAction.LEAVE_APP
    }
}
