package com.assistant.archie.tv.control

import androidx.navigation3.runtime.NavKey
import com.assistant.archie.tv.shell.Conversation
import com.assistant.archie.tv.shell.Home
import com.assistant.archie.tv.shell.MemoryDoc
import com.assistant.archie.tv.shell.TvNavHolder
import com.assistant.archie.tv.shell.TvNavigator
import com.assistant.archie.tv.shell.Viewer
import com.assistant.archie.tv.shell.Visuals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every TV control command → navigation + result (spec 15 §5.2, spec 12 §9.5 RD-3/RD-4). */
class CommandHandlerTest {
    /** A back stack that reports its top like the shell does after composing it. */
    private class Stack(private val holder: TvNavHolder) : ArrayList<NavKey>() {
        override fun add(element: NavKey): Boolean = super.add(element).also { holder.onTopChanged(lastOrNull()) }
        override fun removeAt(index: Int): NavKey = super.removeAt(index).also { holder.onTopChanged(lastOrNull()) }
    }

    private class FakeRemote(var canBack: Boolean = false, var playing: Boolean? = true) : RemoteScreen {
        val calls = mutableListOf<String>()
        override fun back(): Boolean { calls += "back"; return canBack.also { canBack = false } }
        override fun reload(): Boolean { calls += "reload"; return true }
        override fun scroll(direction: ScrollDirection): Boolean { calls += "scroll:${direction.name.lowercase()}"; return true }
        override suspend fun media(action: MediaAction): Boolean? { calls += "media:${action.name.lowercase()}"; return playing }
    }

    private class Env(root: NavKey = Home, var foreground: Boolean = true, attached: Boolean = true) : TvCommandEnv {
        override val nav = TvNavHolder()
        override val remote = TvRemoteScreens()
        val stack = Stack(nav).apply { add(root) }
        val navigator = TvNavigator(stack)
        var fronted = 0
        var pings = 0
        var allow = true
        var bringWorks = true
        val toasts = mutableListOf<String>()
        val systemToasts = mutableListOf<String>()
        val visuals = mutableMapOf("visualizations/solar.html" to "Solar dashboard", "tv-app/index.html" to "Archie TV plan")
        val notes = setOf("projects/x.md", "archie/devices/fire-tv.md", "MEMORY.md")
        var refreshes = 0

        init {
            if (attached) nav.attach(navigator)
        }

        override fun isForeground() = foreground
        override fun bringToFront() {
            fronted++
            if (bringWorks) {
                foreground = true
                if (nav.navigator == null) nav.attach(navigator)
            }
        }
        override fun allowAgentsToOpen() = allow
        override fun origin() = "https://192.168.0.200"
        override fun isListedVisual(path: String) = path in visuals
        override suspend fun visual(path: String, refresh: Boolean): VisualLookup {
            if (refresh) refreshes++
            return visuals[path]?.let { VisualLookup.Listed(it) } ?: VisualLookup.Missing
        }
        override suspend fun memoryExists(path: String) = path in notes
        override fun refreshList(key: NavKey?) = key == Visuals
        override fun toast(text: String, seconds: Int) { toasts += text }
        override fun systemToast(text: String, seconds: Int) { systemToasts += text }
        override fun showOpenedToasts() = true
        override fun pingLauncher() { pings++ }
        override fun state(): JsonObject = TvScreens.frame(nav.current.value, null, "idle", foreground)
        val top: NavKey? get() = stack.lastOrNull()
    }

    private fun cmd(name: String, vararg params: Pair<String, Any>) =
        TvCommand("id1", name, JsonObject(params.associate { (k, v) -> k to (if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())) }))

    private fun ok(r: TvReply): JsonObject {
        assertTrue("expected ok, got $r", r is TvReply.Ok)
        return (r as TvReply.Ok).result
    }

    private fun err(r: TvReply): String {
        assertTrue("expected an error, got $r", r is TvReply.Error)
        return (r as TvReply.Error).message
    }

    private fun JsonObject.s(k: String) = (this[k] as JsonElement?)?.jsonPrimitive?.contentOrNull

    @Test fun openVisualizationPushesAPushedViewerAndAnswersWithItsUrl() = runTest {
        val env = Env(foreground = false)
        val r = ok(CommandHandler(env).handle(cmd("open_visualization", "path" to "visualizations/solar.html")))
        assertEquals(Viewer("visualizations/solar.html", "Solar dashboard", pushed = true), env.top)
        assertEquals("viewer", r.s("screen"))
        assertEquals("https://192.168.0.200/visualizations/solar.html", r.s("url"))
        assertEquals("Solar dashboard", r.s("title"))
        assertEquals(1, env.fronted)
        assertEquals(1, env.pings)
    }

    @Test fun aFolderPathAndContextPathsNormalise() = runTest {
        val env = Env()
        ok(CommandHandler(env).handle(cmd("open_visualization", "path" to "/tv-app/")))
        assertEquals(Viewer("tv-app/index.html", "Archie TV plan", pushed = true), env.top)
        assertEquals("x.html", CommandHandler.visualPath("context/public/x.html"))
        assertEquals(null, CommandHandler.visualPath("../etc/passwd"))
    }

    @Test fun aMissingVisualizationFailsWithoutTouchingTheScreen() = runTest {
        val env = Env(foreground = false)
        val e = err(CommandHandler(env).handle(cmd("open_visualization", "path" to "visualizations/nope.html")))
        assertTrue(e, "no visualization" in e)
        assertEquals(Home, env.top)
        assertEquals(0, env.fronted)
        assertEquals(1, env.refreshes) // looked again after one refresh (LNK-7)
        assertTrue("required" in err(CommandHandler(env).handle(cmd("open_visualization"))))
    }

    @Test fun aViewerReplacesTheViewerOnTop() = runTest {
        val env = Env(root = Visuals)
        val h = CommandHandler(env)
        ok(h.handle(cmd("open_visualization", "path" to "visualizations/solar.html")))
        ok(h.handle(cmd("open_url", "url" to "https://example.com/page")))
        assertEquals(listOf(Visuals, Viewer("https://example.com/page", null, pushed = true)), env.stack.toList())
    }

    @Test fun openUrlResolvesInternalLinks() = runTest {
        val env = Env()
        val h = CommandHandler(env)
        val m = ok(h.handle(cmd("open_url", "url" to "https://192.168.0.200/memory/projects/x.md")))
        assertEquals(MemoryDoc("projects/x.md"), env.top)
        assertEquals("memory_doc", m.s("screen"))
        assertEquals("projects/x.md", m.s("path"))
        assertTrue(env.toasts.single().startsWith("Opened by Archie"))

        val v = ok(h.handle(cmd("open_url", "url" to "/tv-app/")))
        assertEquals(Viewer("tv-app/index.html", "Archie TV plan", pushed = true), env.top)
        assertEquals("viewer", v.s("screen"))

        ok(h.handle(cmd("open_url", "url" to "https://192.168.0.200/#/memory/MEMORY.md")))
        assertEquals(MemoryDoc("MEMORY.md"), env.top)

        // A server page that is not a listed visualization: the URL itself (LNK-7), still in the Viewer.
        ok(h.handle(cmd("open_url", "url" to "/visualizations/gone.html")))
        assertEquals(Viewer("https://192.168.0.200/visualizations/gone.html", null, pushed = true), env.top)

        assertTrue("cannot open" in err(h.handle(cmd("open_url", "url" to "javascript:alert(1)"))))
        assertTrue("cannot open" in err(h.handle(cmd("open_url", "url" to "file:///etc/passwd"))))
    }

    @Test fun openMemoryAcceptsTheUsualPathFormsAndChecksTheNoteExists() = runTest {
        val env = Env()
        val h = CommandHandler(env)
        ok(h.handle(cmd("open_memory", "path" to "/memory/projects/x.md")))
        assertEquals(MemoryDoc("projects/x.md"), env.top)
        ok(h.handle(cmd("open_memory", "path" to "docs/devices/fire-tv.md")))
        assertEquals(listOf(Home, MemoryDoc("archie/devices/fire-tv.md")), env.stack.toList()) // a note replaces a note
        assertTrue("no memory note" in err(h.handle(cmd("open_memory", "path" to "projects/missing.md"))))
    }

    @Test fun openConversationPushesItsKey() = runTest {
        val env = Env()
        val h = CommandHandler(env)
        val r = ok(h.handle(cmd("open_conversation", "local_id" to "L1")))
        assertEquals(Conversation(localId = "L1"), env.top)
        assertEquals("conversation", r.s("screen"))
        assertEquals("L1", r.s("local_id"))
        ok(h.handle(cmd("open_conversation", "sdk_session_id" to "S1")))
        assertEquals(Conversation(sdkId = "S1"), env.top)
        assertTrue("required" in err(h.handle(cmd("open_conversation"))))
    }

    @Test fun homeAndBack() = runTest {
        val env = Env(root = Visuals)
        val h = CommandHandler(env)
        ok(h.handle(cmd("open_visualization", "path" to "visualizations/solar.html")))
        val remote = FakeRemote(canBack = true)
        env.remote.register(env.top!!, remote)
        ok(h.handle(cmd("back"))) // page history first
        assertEquals(listOf("back"), remote.calls)
        assertTrue(env.top is Viewer)
        assertEquals("visuals", ok(h.handle(cmd("back"))).s("screen")) // then close
        assertEquals("home", ok(h.handle(cmd("back"))).s("screen")) // a drawer root goes Home
        assertEquals("home", ok(h.handle(cmd("back"))).s("screen")) // Back never leaves the app for an agent
        ok(h.handle(cmd("open_memory", "path" to "projects/x.md")))
        assertEquals("home", ok(h.handle(cmd("home"))).s("screen"))
        assertEquals(listOf<NavKey>(Home), env.stack.toList())
    }

    @Test fun reloadScrollAndMediaGoToTheScreen() = runTest {
        val env = Env()
        val h = CommandHandler(env)
        assertTrue("nothing to reload" in err(h.handle(cmd("reload"))))
        assertTrue("cannot scroll" in err(h.handle(cmd("scroll", "direction" to "down"))))
        assertTrue("Viewer only" in err(h.handle(cmd("media", "action" to "toggle"))))
        assertTrue("direction" in err(h.handle(cmd("scroll", "direction" to "sideways"))))
        assertTrue("action" in err(h.handle(cmd("media", "action" to "rewind"))))

        ok(h.handle(cmd("open_visualization", "path" to "visualizations/solar.html")))
        val remote = FakeRemote()
        env.remote.register(env.top!!, remote)
        assertEquals("viewer", ok(h.handle(cmd("reload"))).s("screen"))
        ok(h.handle(cmd("scroll", "direction" to "BOTTOM")))
        assertEquals("true", ok(h.handle(cmd("media", "action" to "play"))).s("playing"))
        remote.playing = null
        assertTrue("no playable media" in err(h.handle(cmd("media", "action" to "pause"))))
        assertEquals(listOf("reload", "scroll:bottom", "media:play", "media:pause"), remote.calls)

        val list = Env(root = Visuals)
        assertEquals("visuals", ok(CommandHandler(list).handle(cmd("reload"))).s("screen"))
    }

    @Test fun notifyNeverTakesTheScreenOver() = runTest {
        val env = Env(foreground = false)
        val h = CommandHandler(env)
        ok(h.handle(cmd("notify", "title" to "Archie", "text" to "Build finished", "seconds" to 4)))
        assertEquals(listOf("Archie · Build finished"), env.systemToasts)
        assertEquals(0, env.fronted)
        env.foreground = true
        ok(h.handle(cmd("notify", "text" to "Hi")))
        assertEquals(listOf("Hi"), env.toasts)
        assertTrue("text" in err(h.handle(cmd("notify"))))
    }

    @Test fun getStateNeverMovesAnything() = runTest {
        val env = Env(foreground = false)
        val r = ok(CommandHandler(env).handle(cmd("get_state")))
        assertEquals("state", r.s("type"))
        assertEquals("home", r.s("screen"))
        assertEquals("false", r.s("foreground"))
        assertEquals(0, env.fronted)
    }

    @Test fun theUserSettingRefusesDisplayCommands() = runTest {
        val env = Env()
        env.allow = false
        val h = CommandHandler(env)
        for (c in listOf("open_url", "open_visualization", "open_memory", "open_conversation", "home", "back", "reload", "scroll", "media")) {
            assertTrue(c, "Allow agents to open pages" in err(h.handle(cmd(c, "url" to "/x/", "path" to "projects/x.md", "local_id" to "L"))))
        }
        assertEquals(listOf<NavKey>(Home), env.stack.toList())
        ok(h.handle(cmd("get_state")))
        ok(h.handle(cmd("notify", "text" to "still fine")))
    }

    @Test fun unknownCommandsAreErrors() = runTest {
        assertEquals("unknown command: dance", err(CommandHandler(Env()).handle(cmd("dance"))))
    }

    @Test fun anAppThatCannotComeForwardFailsInsteadOfHanging() = runTest {
        val env = Env(foreground = false, attached = false)
        env.bringWorks = false
        val e = err(CommandHandler(env, frontTimeoutMs = 500).handle(cmd("home")))
        assertTrue("front" in e)
        assertEquals(1, env.fronted)
    }

    @Test fun aColdStartWaitsForTheShell() = runTest {
        val env = Env(foreground = false, attached = false)
        ok(CommandHandler(env).handle(cmd("open_memory", "path" to "MEMORY.md")))
        assertEquals(MemoryDoc("MEMORY.md"), env.top)
    }
}
