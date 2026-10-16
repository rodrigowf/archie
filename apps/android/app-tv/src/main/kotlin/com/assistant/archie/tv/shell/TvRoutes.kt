package com.assistant.archie.tv.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.navigation3.runtime.NavKey
import com.assistant.archie.tv.conversation.ConversationRoute
import com.assistant.archie.tv.conversations.ConversationsRoute
import com.assistant.archie.tv.firstrun.ConnectRoute
import com.assistant.archie.tv.graph.TvAppGraph
import com.assistant.archie.tv.home.HomeRoute
import com.assistant.archie.tv.memory.MemoryDocRoute
import com.assistant.archie.tv.memory.MemoryRoute
import com.assistant.archie.tv.settings.SettingsPageRoute
import com.assistant.archie.tv.settings.SettingsRoute
import com.assistant.archie.tv.viewer.ViewerRoute
import com.assistant.archie.tv.visuals.VisualsRoute

/**
 * Everything a route needs: the process-scoped [graph], [nav] (push / pop / drawer roots) and the
 * shell's [toasts]. Every route takes exactly this, so adding a dependency never changes a signature.
 */
@Stable
class TvRouteContext(
    val graph: TvAppGraph,
    val nav: TvNavigator,
    val toasts: TvToasts = graph.toasts,
)

/**
 * Back-stack key → route. One route per screen, each in its own package/file (home/, conversations/,
 * conversation/, visuals/, viewer/, memory/, settings/, firstrun/), so T1–T4 never edit the same
 * file: they replace their route's body, not this dispatcher.
 */
object TvRoutes {
    @Composable
    fun Entry(key: NavKey, ctx: TvRouteContext) {
        when (key) {
            Home -> HomeRoute(ctx)
            Conversations -> ConversationsRoute(ctx)
            is Conversation -> ConversationRoute(key, ctx)
            Visuals -> VisualsRoute(ctx)
            is Viewer -> ViewerRoute(key, ctx)
            Memory -> MemoryRoute(ctx)
            is MemoryDoc -> MemoryDocRoute(key, ctx)
            Settings -> SettingsRoute(ctx)
            is SettingsPage -> SettingsPageRoute(key, ctx)
            is Connect -> ConnectRoute(key, ctx)
            else -> Unit
        }
    }
}
