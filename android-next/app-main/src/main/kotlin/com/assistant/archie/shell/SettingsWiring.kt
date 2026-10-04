package com.assistant.archie.shell

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.assistant.archie.feature.settings.RepositoryConnectionControl
import com.assistant.archie.feature.settings.SessionControl
import com.assistant.archie.feature.settings.SessionInfo
import com.assistant.archie.feature.settings.SettingsDeps
import com.assistant.archie.feature.settings.SettingsFeature
import com.assistant.archie.feature.settings.android.AndroidDevicePlatform
import com.assistant.archie.feature.settings.android.PrefsAppearanceStore
import com.assistant.archie.graph.MainAppGraph
import com.assistant.core.data.ItemKey
import com.assistant.core.data.TabStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.WeakHashMap

/*
 * B-08 wiring: the settings feature built from the process-scoped graph (spec 14 §2.2). One
 * SettingsFeature per graph (process). B-09 binds the VoiceStatusSource adapter over the real
 * VoiceHost (`graph.voiceStatus`).
 */

private val features = WeakHashMap<MainAppGraph, SettingsFeature>()

fun settingsFeatureOf(graph: MainAppGraph, context: Context): SettingsFeature = synchronized(features) {
    features.getOrPut(graph) {
        val app = context.applicationContext
        SettingsFeature(
            SettingsDeps(
                settings = graph.settings,
                serverConfig = graph.serverConfig,
                api = graph.api,
                connection = RepositoryConnectionControl(graph.connection, graph.orchestrator, graph.http, graph.settings),
                platform = AndroidDevicePlatform(app),
                appearance = PrefsAppearanceStore(app),
                scope = graph.scope,
                sessions = GraphSessionControl(graph),
                voice = graph.voiceStatus, // B-09: wake health from the real voice host
            ),
        )
    }
}

@Composable
fun rememberSettingsFeature(graph: MainAppGraph): SettingsFeature {
    val context = LocalContext.current
    return remember(graph) { settingsFeatureOf(graph, context) }
}

/** [SessionControl] over the workspace items: restart = explicit close, then reopen the same history. */
private class GraphSessionControl(private val graph: MainAppGraph) : SessionControl {
    override fun session(localId: String): Flow<SessionInfo?> = graph.openSessions.items.map { items ->
        items.firstOrNull { it.localId == localId }?.let {
            SessionInfo(localId, it.sdkId, it.title, busy = it.status == TabStatus.WORKING)
        }
    }.distinctUntilChanged()

    override suspend fun restart(localId: String): Boolean {
        val item = graph.openSessions.items.value.firstOrNull { it.localId == localId } ?: return false
        val ref = when (val k = item.key) {
            is ItemKey.Agent -> graph.conversations.current(k.conversation)?.ref
            ItemKey.Archie -> graph.conversations.current(com.assistant.core.data.ConversationKey.ARCHIE)?.ref
            else -> null
        } ?: return false
        graph.openSessions.close(item.key)
        graph.openSessions.openRef(ref.copy(live = false, liveStatus = null))
        return true
    }
}
