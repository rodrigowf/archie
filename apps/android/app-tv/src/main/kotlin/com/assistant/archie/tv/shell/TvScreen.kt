package com.assistant.archie.tv.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * The per-screen extension point (spec 15 §3.3-§3.4). Every screen — Home, Conversations,
 * Conversation, Visuals, Viewer, Memory, MemoryDoc, Settings — tells the shell, from its own file:
 *
 *   ScreenChromeEffect(ScreenChrome(
 *       title = "Visuals",
 *       hints = listOf(KeyHints.Ok("Open"), KeyHints.Menu, KeyHints.Back),
 *       options = listOf(TvOption("rename", "Rename") { … }),
 *       keys = TvKeyHandler { key -> key == TvKey.REWIND && previousRow() },
 *   ))
 *
 * The shell shows the title, the key-hint bar and the options sheet (Menu / long-press OK), and the
 * KeyRouter sends Menu / Play/Pause / Rewind / FF / long-press OK to `keys` first. A screen that
 * needs a richer object can implement [TvScreenContract] itself.
 */

/** What a key-hint shows as its key cap. */
enum class HintGlyph { MIC, PLAY_PAUSE, MENU, BACK, OK, REWIND_FF }

@Immutable
data class KeyHint(val glyph: HintGlyph, val label: String)

/** Ready-made hints; [Default] is what a screen gets when it says nothing (spec 15 §3.4). */
object KeyHints {
    val Mic = KeyHint(HintGlyph.MIC, "Hold to talk")
    val LiveVoice = KeyHint(HintGlyph.PLAY_PAUSE, "Live voice")
    val Menu = KeyHint(HintGlyph.MENU, "Options")
    val Back = KeyHint(HintGlyph.BACK, "Back")
    fun Ok(label: String) = KeyHint(HintGlyph.OK, label)
    fun Page(label: String = "Page") = KeyHint(HintGlyph.REWIND_FF, label)

    val Default: List<KeyHint> = listOf(Mic, LiveVoice, Menu, Back)
}

/** One action of the options sheet (Menu). [id] must be stable while the screen shows. */
@Immutable
data class TvOption(
    val id: String,
    val label: String,
    val icon: ImageVector? = null,
    val detail: String? = null,
    val enabled: Boolean = true,
    val onSelect: () -> Unit,
)

/** The contract between a screen and the shell. */
interface TvScreenContract {
    /** Shown top-left by the shell header (unless [showHeader] is false). */
    val title: String

    /** The key-hint bar (3–4 hints). */
    val hints: List<KeyHint>

    /** The options sheet's actions (Menu / long-press OK). Empty = the sheet shows only the global entries. */
    val options: List<TvOption>

    /** Hides the drawer (Conversation, Viewer, MemoryDoc). */
    val fullScreen: Boolean

    /** The shell header (title + status cluster); full-screen screens usually draw their own. */
    val showHeader: Boolean

    /** Accelerators (Menu, Play/Pause, Rewind/FF, long-press OK) before the global defaults. */
    fun onKey(key: TvKey): Boolean
}

/** The plain-data [TvScreenContract] most screens use. */
data class ScreenChrome(
    override val title: String = "",
    override val hints: List<KeyHint> = KeyHints.Default,
    override val options: List<TvOption> = emptyList(),
    override val fullScreen: Boolean = false,
    override val showHeader: Boolean = true,
    val keys: TvKeyHandler? = null,
) : TvScreenContract {
    override fun onKey(key: TvKey): Boolean = keys?.onKey(key) == true
}

/** The displayable part of a contract (no lambdas), compared to skip needless recompositions. */
@Immutable
data class ChromeView(
    val title: String = "",
    val hints: List<KeyHint> = KeyHints.Default,
    val options: List<OptionView> = emptyList(),
    val fullScreen: Boolean = false,
    val showHeader: Boolean = true,
) {
    @Immutable
    data class OptionView(val id: String, val label: String, val icon: ImageVector?, val detail: String?, val enabled: Boolean)

    companion object {
        fun of(c: TvScreenContract) = ChromeView(
            title = c.title,
            hints = c.hints,
            options = c.options.map { OptionView(it.id, it.label, it.icon, it.detail, it.enabled) },
            fullScreen = c.fullScreen,
            showHeader = c.showHeader,
        )
    }
}

/**
 * Which back-stack entry published which contract. The shell reads the one of the top entry; while
 * Navigation 3 animates, the outgoing entry's contract simply is not on top any more.
 */
@Stable
class ScreenChromeRegistry {
    private val views = mutableStateMapOf<Any, ChromeView>()
    private val contracts = HashMap<Any, () -> TvScreenContract>()

    /** The view of [key] (snapshot state: the shell recomposes when a screen changes it). */
    fun view(key: Any?): ChromeView? = key?.let { views[it] }

    /** The latest contract of [key] (handlers, option lambdas). */
    fun contract(key: Any?): TvScreenContract? = key?.let { contracts[it]?.invoke() }

    internal fun attach(key: Any, latest: () -> TvScreenContract) {
        contracts[key] = latest
    }

    internal fun publish(key: Any, view: ChromeView) {
        if (views[key] != view) views[key] = view
    }

    internal fun detach(key: Any, latest: () -> TvScreenContract) {
        if (contracts[key] === latest) {
            contracts.remove(key)
            views.remove(key)
        }
    }
}

val LocalScreenChromeRegistry = staticCompositionLocalOf<ScreenChromeRegistry?> { null }

/** The back-stack key of the entry being composed (provided by the shell around every entry). */
val LocalTvEntryKey = staticCompositionLocalOf<Any?> { null }

/** Publishes [contract] for the current back-stack entry; call it once per screen, every composition. */
@Composable
fun ScreenChromeEffect(contract: TvScreenContract) {
    val registry = LocalScreenChromeRegistry.current ?: return
    val key = LocalTvEntryKey.current ?: return
    val latest by rememberUpdatedState(contract)
    DisposableEffect(registry, key) {
        val provider: () -> TvScreenContract = { latest }
        registry.attach(key, provider)
        onDispose { registry.detach(key, provider) }
    }
    val view = ChromeView.of(contract)
    SideEffect { registry.publish(key, view) }
}

/** Shell actions reachable from components (tv-material surfaces route long-press OK through here). */
interface TvKeyActions {
    /** Long-press OK on a focused surface: the screen's handler, else the options sheet. */
    fun longPress()

    /** Opens the options sheet (the "More" button of a composer, a hero card's ⋯). */
    fun openOptions()

    object None : TvKeyActions {
        override fun longPress() = Unit
        override fun openOptions() = Unit
    }
}

val LocalTvKeyActions = staticCompositionLocalOf<TvKeyActions> { TvKeyActions.None }
