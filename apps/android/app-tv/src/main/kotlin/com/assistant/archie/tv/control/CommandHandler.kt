package com.assistant.archie.tv.control

import androidx.navigation3.runtime.NavKey
import com.assistant.archie.feature.visuals.VisualUrls
import com.assistant.archie.tv.shell.Conversation
import com.assistant.archie.tv.shell.Home
import com.assistant.archie.tv.shell.MemoryDoc
import com.assistant.archie.tv.shell.TvDestination
import com.assistant.archie.tv.shell.TvNavHolder
import com.assistant.archie.tv.shell.TvNavigator
import com.assistant.archie.tv.shell.Viewer
import com.assistant.archie.tv.theme.TvFormat
import com.assistant.core.markdown.InternalLinks
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** A visualization looked up in `GET /api/visualizations`. */
sealed interface VisualLookup {
    data class Listed(val title: String) : VisualLookup
    data object Missing : VisualLookup

    /** The list could not be fetched: open it anyway (the Viewer shows the server's answer). */
    data object Unknown : VisualLookup
}

/** What [CommandHandler] needs from the app; the graph implements it ([TvControl]), tests fake it. */
interface TvCommandEnv {
    val nav: TvNavHolder
    val remote: TvRemoteScreens

    /** The app's activity is started (on screen). */
    fun isForeground(): Boolean

    /** Starts / reorders the activity to the front (Fire OS allows it with the SYSTEM_ALERT_WINDOW app-op). */
    fun bringToFront()

    /** Settings → TV control → "Allow agents to open pages" (default on). */
    fun allowAgentsToOpen(): Boolean

    /** `http(s)://host[:port]` of the server. */
    fun origin(): String

    fun isListedVisual(path: String): Boolean
    suspend fun visual(path: String, refresh: Boolean): VisualLookup

    /** false = the server says 404; null = could not tell. */
    suspend fun memoryExists(path: String): Boolean?

    /** Refreshes a list screen (Visuals, Memory) for `reload`; false = nothing to reload there. */
    fun refreshList(key: NavKey?): Boolean = false

    /** Toast inside the app (top center). */
    fun toast(text: String, seconds: Int)

    /** System toast, for `notify` while another app is in front (it must not take the screen over). */
    fun systemToast(text: String, seconds: Int)

    /** Settings → TV control → "Show 'Opened by Archie' toasts" (default on). */
    fun showOpenedToasts(): Boolean

    /** KEEP_AWAKE to the TvServerHub launcher (spec 15 §7.3). */
    fun pingLauncher()

    /** The current `state` frame fields (RD-5). */
    fun state(): JsonObject
}

/**
 * Runs the TV control commands (spec 15 §5.2, spec 12 §9.5 RD-3/RD-4). A command is a user action
 * (TV-5, the FOCUS-1 exemption): it brings the app to the front, waits for the shell's navigator,
 * navigates, and answers once the target screen is on top of the back stack (RD-2: not when the page has
 * finished loading; the cast path waits only 8 s). Parameters are checked before anything moves,
 * so a bad command never disturbs the screen. `get_state` and `notify` never bring the app forward
 * (`notify` while YouTube plays is a system toast, not an interruption).
 *
 * Runs on the main thread (the back stack is snapshot state).
 */
class CommandHandler(
    private val env: TvCommandEnv,
    private val frontTimeoutMs: Long = FRONT_TIMEOUT_MS,
    private val showTimeoutMs: Long = SHOW_TIMEOUT_MS,
) {
    suspend fun handle(cmd: TvCommand): TvReply {
        val name = cmd.name
        if (name !in TvCommands.ALL) return TvReply.Error("unknown command: $name")
        if (name == TvCommands.GET_STATE) return TvReply.Ok(TvControlProtocol.obj("type" to "state").let { JsonObject(it + env.state()) })
        if (name in TvCommands.DISPLAY && !env.allowAgentsToOpen()) {
            return TvReply.Error("agents may not change what this TV shows (Settings → TV control → Allow agents to open pages is off)", TvReply.CODE_REFUSED)
        }
        if (name == TvCommands.NOTIFY) return notify(cmd)

        val action = prepare(cmd)
        if (action is Prepared.Fail) return TvReply.Error(action.message)
        val nav = front() ?: return TvReply.Error("the Archie TV app could not come to the front")
        env.pingLauncher()
        return (action as Prepared.Run).run(nav)
    }

    private sealed interface Prepared {
        data class Fail(val message: String) : Prepared
        class Run(val run: suspend (TvNavigator) -> TvReply) : Prepared
    }

    private fun fail(message: String) = Prepared.Fail(message)
    private fun act(block: suspend (TvNavigator) -> TvReply) = Prepared.Run(block)

    /** Validates and resolves everything that can fail before the screen changes. */
    private suspend fun prepare(cmd: TvCommand): Prepared = when (cmd.name) {
        TvCommands.OPEN_VISUALIZATION -> {
            val path = cmd.string("path")?.let(::visualPath) ?: return fail("'path' is required (a GET /api/visualizations path, e.g. visualizations/x.html)")
            when (val v = lookup(path)) {
                VisualLookup.Missing -> fail("no visualization at '$path'")
                else -> act { nav -> openViewer(nav, path, (v as? VisualLookup.Listed)?.title) }
            }
        }
        TvCommands.OPEN_URL -> {
            val url = cmd.string("url") ?: return fail("'url' is required")
            when (val t = TvLinks.resolve(url, env.origin(), env::isListedVisual)) {
                null -> fail("cannot open '$url' on the TV (only http(s) URLs and server paths)")
                is TvLinkTarget.Visual -> when (val v = lookup(t.path)) {
                    // LNK-7: not a real page → the URL itself (on a TV, also the Viewer).
                    VisualLookup.Missing -> act { nav -> openWeb(nav, env.origin() + InternalLinks.url(InternalLinks.Target.Visual(t.path))) }
                    else -> act { nav -> openViewer(nav, t.path, (v as? VisualLookup.Listed)?.title) }
                }
                is TvLinkTarget.Memory -> memoryPlan(t.path)
                is TvLinkTarget.Web -> act { nav -> openWeb(nav, t.url) }
            }
        }
        TvCommands.OPEN_MEMORY -> {
            val raw = cmd.string("path") ?: return fail("'path' is required (as in GET /api/memory/tree, e.g. projects/x.md)")
            memoryPlan(memoryPath(raw) ?: return fail("'$raw' is not a memory note path"))
        }
        TvCommands.OPEN_CONVERSATION -> {
            val local = cmd.string("local_id")
            val sdk = cmd.string("sdk_session_id")
            if (local == null && sdk == null) fail("'local_id' or 'sdk_session_id' is required")
            else act { nav ->
                val key = Conversation(localId = local, sdkId = sdk)
                show(nav, key)
                TvReply.Ok(TvControlProtocol.obj("screen" to TvScreens.name(key), "local_id" to (local ?: sdk)))
            }
        }
        TvCommands.HOME -> act { nav ->
            nav.selectRoot(TvDestination.HOME)
            awaitTop(Home)
            ok(Home)
        }
        TvCommands.BACK -> act { nav ->
            val top = nav.current
            when {
                env.remote.of(top)?.back() == true -> Unit
                nav.pop() -> Unit
                TvDestination.of(nav.root) != null && nav.root != Home -> nav.selectRoot(TvDestination.HOME)
                else -> Unit // at Home: Back never leaves the app on an agent's behalf
            }
            ok(nav.current)
        }
        TvCommands.RELOAD -> act { nav ->
            val top = nav.current
            if (env.remote.of(top)?.reload() == true || env.refreshList(top)) ok(top)
            else TvReply.Error("nothing to reload on the ${TvScreens.name(top)} screen")
        }
        TvCommands.SCROLL -> {
            val dir = ScrollDirection.parse(cmd.string("direction")) ?: return fail("'direction' must be up, down, top or bottom")
            act { nav ->
                val top = nav.current
                if (env.remote.of(top)?.scroll(dir) == true) ok(top)
                else TvReply.Error("the ${TvScreens.name(top)} screen cannot scroll")
            }
        }
        TvCommands.MEDIA -> {
            val action = MediaAction.parse(cmd.string("action")) ?: return fail("'action' must be play, pause or toggle")
            act { nav ->
                val top = nav.current
                if (top !is Viewer) return@act TvReply.Error("media works in the Viewer only (the TV shows ${TvScreens.name(top)})")
                val playing = env.remote.of(top)?.media(action) ?: return@act TvReply.Error("no playable media on this page")
                TvReply.Ok(TvControlProtocol.obj("playing" to playing))
            }
        }
        else -> fail("unknown command: ${cmd.name}")
    }

    private suspend fun memoryPlan(path: String): Prepared {
        if (env.memoryExists(path) == false) return fail("no memory note at '$path'")
        return act { nav ->
            val key = MemoryDoc(path)
            show(nav, key)
            if (env.showOpenedToasts()) env.toast("Opened by Archie · ${TvFormat.titleOf(path)}", OPENED_TOAST_S)
            TvReply.Ok(TvControlProtocol.obj("screen" to TvScreens.name(key), "path" to path))
        }
    }

    private suspend fun lookup(path: String): VisualLookup =
        env.visual(path, refresh = false).let { if (it == VisualLookup.Missing) env.visual(path, refresh = true) else it }

    private suspend fun openViewer(nav: TvNavigator, path: String, title: String?): TvReply {
        val t = title ?: TvFormat.titleOf(path)
        show(nav, Viewer(path, t, pushed = true))
        return TvReply.Ok(TvControlProtocol.obj("screen" to "viewer", "url" to TvLinks.withoutDefaultPort(VisualUrls.href(env.origin(), path)), "title" to t))
    }

    private suspend fun openWeb(nav: TvNavigator, url: String): TvReply {
        show(nav, Viewer(url, null, pushed = true))
        return TvReply.Ok(TvControlProtocol.obj("screen" to "viewer", "url" to TvLinks.withoutDefaultPort(url)))
    }

    /** Pushes [key]; a Viewer replaces a Viewer on top (agents opening page after page don't pile up history). */
    private suspend fun show(nav: TvNavigator, key: NavKey) {
        val top = nav.current
        if (top is Viewer && key is Viewer && top != key) nav.pop()
        if (top is MemoryDoc && key is MemoryDoc && top != key) nav.pop()
        nav.push(key)
        awaitTop(key)
    }

    /** The shell reports its top entry once it composed it; a slow frame never fails the command. */
    private suspend fun awaitTop(key: NavKey) {
        withTimeoutOrNull(showTimeoutMs) { env.nav.current.first { it == key } }
    }

    private fun ok(key: NavKey?) = TvReply.Ok(TvControlProtocol.obj("screen" to TvScreens.name(key)))

    /** The navigator of a shell that is on screen, bringing the activity forward first if needed. */
    private suspend fun front(): TvNavigator? {
        if (!env.isForeground() || env.nav.navigator == null) env.bringToFront()
        fun shown(): TvNavigator? = env.nav.navigator?.takeIf { env.isForeground() }
        return withTimeoutOrNull(frontTimeoutMs) {
            var nav = shown()
            while (nav == null) {
                delay(POLL_MS)
                nav = shown()
            }
            nav
        }
    }

    private fun notify(cmd: TvCommand): TvReply {
        val title = cmd.string("title")
        val text = cmd.string("text")
        if (title == null && text == null) return TvReply.Error("'text' is required")
        val message = listOfNotNull(title, text).joinToString(" · ")
        val seconds = (cmd.int("seconds") ?: DEFAULT_NOTIFY_S).coerceIn(1, MAX_NOTIFY_S)
        if (env.isForeground()) env.toast(message, seconds) else env.systemToast(message, seconds)
        return TvReply.Ok()
    }

    companion object {
        /** Bring-to-front + first composition on the AFTKM; the server's default timeout is 15 s, the cast path 8 s. */
        const val FRONT_TIMEOUT_MS = 6_000L
        const val SHOW_TIMEOUT_MS = 1_500L
        const val POLL_MS = 50L
        const val DEFAULT_NOTIFY_S = 5
        const val MAX_NOTIFY_S = 30
        const val OPENED_TOAST_S = 3

        /** `visualizations/x.html`, `/x/` → `x/index.html`, `context/public/x.html` → `x.html`. */
        fun visualPath(raw: String): String? {
            var p = raw.trim().replace('\\', '/')
            p = p.substringAfter("context/public/", p).trimStart('/')
            if (p.isEmpty()) return null
            if (p.endsWith("/")) p += "index.html"
            if (p.split('/').any { it == ".." }) return null
            return p
        }

        /** `projects/x.md`, `/memory/x.md`, `context/memory/x.md`, `docs/x.md` (→ `archie/x.md`). */
        fun memoryPath(raw: String): String? {
            val p = raw.trim()
            if (p.startsWith("/") || "context/memory/" in p || p.startsWith("docs/") || "/assistant/docs/" in p) {
                return (InternalLinks.resolve(p) as? InternalLinks.Target.Memory)?.path
            }
            if (p.isEmpty() || p.split('/').any { it == ".." }) return null
            return p
        }
    }
}
