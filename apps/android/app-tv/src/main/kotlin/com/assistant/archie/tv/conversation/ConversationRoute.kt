package com.assistant.archie.tv.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assistant.archie.feature.chat.ChatEffect
import com.assistant.archie.feature.chat.ConversationViewModel
import com.assistant.archie.feature.chat.PresenceChatVoice
import com.assistant.archie.feature.chat.RepositoryChatBackend
import com.assistant.archie.feature.chat.conversationViewModelFactory
import com.assistant.archie.feature.chat.ui.CatalogToolCardRenderer
import com.assistant.archie.feature.sessions.SessionTarget
import com.assistant.archie.feature.sessions.SessionTitles
import com.assistant.archie.tv.conversations.ConversationRouting
import com.assistant.archie.tv.conversations.SessionFlowsHost
import com.assistant.archie.tv.conversations.TvSessions
import com.assistant.archie.tv.conversations.ViewRef
import com.assistant.archie.tv.graph.TvAppGraph
import com.assistant.archie.tv.shell.Conversation
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.theme.FocusOnOpen
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvButtonStyle
import com.assistant.archie.tv.theme.TvEmptyState
import com.assistant.archie.tv.theme.sampled
import com.assistant.archie.tv.voice.VoiceChip
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.data.ConversationKey
import com.assistant.core.data.ItemKey
import com.assistant.core.design.components.Spinner
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
import java.util.UUID

/*
 * The Conversation route (spec 15 §3.5, spec 12 §3.7). The key says which conversation; the route
 * finds its view: the Archie view, an agent view open here, or a pool session to reattach to
 * (automatic, reattach only: OPEN-2). A conversation that is neither open nor in the pool (a
 * restored back stack after the session was closed) waits for the user's "Open" — a user action may
 * resume it. A view the server closes (OPEN-3) leaves the screen.
 */

@Composable
fun ConversationRoute(key: Conversation, ctx: TvRouteContext) {
    val g = ctx.graph
    val sessions = remember(g) { TvSessions.of(g) }
    val openKeys by g.conversations.openKeys.collectAsStateWithLifecycle()
    val pool by g.history.pool.collectAsStateWithLifecycle()
    // Once resolved, the route sticks to its view key (a rewind re-keys the session, not the view).
    var bound by rememberSaveable(key) { mutableStateOf<String?>(null) }
    val resolution = remember(key, openKeys, pool) {
        ConversationRouting.resolve(key, openKeys.mapNotNull { k -> g.conversations.current(k)?.ref?.let { ViewRef(k, it.localId, it.sdkId, it.kind) } }, pool)
    }
    val convKey = when {
        key.archie -> ConversationKey.ARCHIE
        bound != null && ConversationKey(bound!!) in openKeys -> ConversationKey(bound!!)
        resolution is ConversationRouting.Resolution.View -> resolution.key
        else -> null
    }
    if (convKey != null && !key.archie && bound != convKey.value) bound = convKey.value
    // This screen showed a view and it went away (closed here, elsewhere, or deleted): leave (OPEN-3).
    var hadView by remember(key) { mutableStateOf(false) }
    if (convKey != null) hadView = true
    LaunchedEffect(hadView, convKey == null) { if (hadView && convKey == null && !key.archie) ctx.nav.remove(key) }

    when {
        key.archie -> ArchieConversation(g, ctx, sessions, key)
        convKey != null -> ConversationView(g, ctx, sessions, convKey, archie = false, routeKey = key)
        hadView -> Waiting("Closing…")
        resolution is ConversationRouting.Resolution.Reattach -> {
            LaunchedEffect(resolution.pool.localId) { sessions.reattach(resolution.pool) }
            Waiting("Opening the conversation…")
        }
        else -> NotOpen(key, g, ctx, sessions)
    }
    SessionFlowsHost(sessions)
}

@Composable
private fun ArchieConversation(g: TvAppGraph, ctx: TvRouteContext, sessions: TvSessions, routeKey: Conversation) {
    val archie by g.conversations.state(ConversationKey.ARCHIE).collectAsStateWithLifecycle()
    val connection by g.connection.status.collectAsStateWithLifecycle()
    if (archie == null) {
        ScreenChromeEffect(ScreenChrome(title = "Archie", hints = listOf(KeyHints.Mic, KeyHints.LiveVoice, KeyHints.Menu, KeyHints.Back), fullScreen = true))
        val first = remember { FocusRequester() }
        FocusOnOpen(first)
        val connected = connection.phase == ConnectionStatus.Phase.CONNECTED
        TvEmptyState(
            if (connected) "No Archie conversation is open" else "Connecting to Archie…",
            if (connected) "Start a new one, or hold the mic button and talk." else "The conversation appears as soon as the server answers.",
            Modifier.testTag("archie-empty"),
            icon = ArchieIcons.Forum,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvButton("New conversation", { sessions.newArchie(ctx.nav) }, Modifier.focusRequester(first), icon = ArchieIcons.AddComment, style = TvButtonStyle.Primary)
                TvButton("Live voice", { g.liveVoice.toggle() }, icon = ArchieIcons.GraphicEq)
                TvButton("Back", { ctx.nav.pop() }, icon = ArchieIcons.ArrowBack)
            }
        }
        return
    }
    ConversationView(g, ctx, sessions, ConversationKey.ARCHIE, archie = true, routeKey = routeKey)
}

@Composable
private fun ConversationView(g: TvAppGraph, ctx: TvRouteContext, sessions: TvSessions, convKey: ConversationKey, archie: Boolean, routeKey: Conversation) {
    val itemKey = if (archie) ItemKey.Archie else ItemKey.Agent(convKey)
    val vm: ConversationViewModel = viewModel(
        key = "conversation:${convKey.value}",
        factory = conversationViewModelFactory(
            backend = { RepositoryChatBackend(convKey, g.conversations, g.uploads) },
            // The TV's voice is the remote mic + live voice toggle (T4); the view only mirrors presence.
            voice = PresenceChatVoice(g.voice),
            toolCards = CatalogToolCardRenderer,
            title = { g.openSessions.items.value.firstOrNull { it.key == itemKey }?.title },
        ),
    )
    // Spec 15 §6.2: the stick recomposes the transcript at most 10 times a second while text streams.
    val stateFlow = remember(vm) { vm.state.sampled(100) }
    val initial = remember(vm) { vm.state.value }
    val state by stateFlow.collectAsStateWithLifecycle(initial)
    val draft by vm.draftText.collectAsStateWithLifecycle()
    val raw by g.conversations.state(convKey).collectAsStateWithLifecycle()
    val items by g.openSessions.items.collectAsStateWithLifecycle()
    val connection by g.connection.status.collectAsStateWithLifecycle()
    val voice by g.voice.state.collectAsStateWithLifecycle()
    val display by g.prefs.display.collectAsStateWithLifecycle()
    val item = items.firstOrNull { it.key == itemKey }
    val ref = raw?.ref

    // OPEN-3: the server closed it (or it was closed / deleted from here): the screen goes.
    var seen by remember { mutableStateOf(false) }
    if (raw != null) seen = true
    // Both this and the route's own check can see the close: remove this screen's entry once (B7).
    LaunchedEffect(raw == null, seen) { if (seen && raw == null && !archie) ctx.nav.remove(routeKey) }

    LaunchedEffect(vm) {
        vm.effectFlow.collect { e ->
            when (e) {
                is ChatEffect.Snackbar -> ctx.toasts.show(e.message)
                is ChatEffect.OpenSession -> sessions.openRef(e.ref, ctx.nav)
            }
        }
    }
    val title = SessionTitles.conversationTitle(item?.title ?: g.history.titleFor(ref?.sdkId, ref?.localId, if (archie) "Archie" else "New agent session"), archie)
    val target = item?.let(SessionTarget::of) ?: SessionTarget(ref?.sdkId, ref?.localId, title, archie, ref?.provider)
    ConversationScreen(
        state = state,
        header = ConversationHeaderUi(
            title = title,
            archie = archie,
            provider = (item?.provider ?: ref?.provider)?.label,
            connection = connection.phase,
            voiceLabel = VoiceChip.label(voice),
            voiceActive = VoiceChip.active(voice),
            voiceElsewhere = VoiceChip.elsewhere(voice),
        ),
        draft = draft,
        reduceMotion = display.reduceMotion,
        actions = ConversationScreenActions(
            onAction = vm::onAction,
            liveVoice = { g.liveVoice.toggle() },
            takeOver = { g.liveVoice.takeOver() },
            holdToTalk = {
                ctx.toasts.show(
                    if (archie) "Hold the mic button on the remote and speak: Archie gets it when you let go."
                    else "Hold the mic button on the remote to dictate a message to this agent.",
                    seconds = 5,
                )
            },
            rename = { sessions.rename(target) },
            newConversation = {
                if (archie) sessions.newArchie(ctx.nav) else sessions.newAgent(ref?.provider, ctx.nav)
            },
            close = { item?.let(sessions::close) ?: ctx.toasts.show("This conversation is not open") },
            delete = { sessions.delete(target) },
            textOf = vm::textOf,
            back = { ctx.nav.pop() },
        ),
    )
}

@Composable
private fun NotOpen(key: Conversation, g: TvAppGraph, ctx: TvRouteContext, sessions: TvSessions) {
    ScreenChromeEffect(ScreenChrome(title = "Conversation", hints = listOf(KeyHints.Ok("Open"), KeyHints.Back), fullScreen = true))
    val first = remember { FocusRequester() }
    FocusOnOpen(first)
    TvEmptyState(
        "This conversation isn't open",
        "It is not running on the server any more. Open it to resume where it stopped.",
        Modifier.testTag("conversation-not-open"),
        icon = ArchieIcons.Forum,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvButton(
                "Open",
                {
                    // A user action (OPEN-2): may resume. This placeholder makes way for the opened view.
                    ctx.nav.pop()
                    val summary = key.sdkId?.let { sdk -> g.history.sessions.value.value?.firstOrNull { it.sdkId == sdk } }
                    when {
                        summary != null -> sessions.openHistory(summary, ctx.nav)
                        key.sdkId != null -> sessions.openRef(SessionRef(UUID.randomUUID().toString(), key.sdkId, SessionKind.AGENT, null), ctx.nav)
                        else -> ctx.toasts.show("This conversation has no history yet")
                    }
                },
                Modifier.focusRequester(first),
                icon = ArchieIcons.Forum,
                style = TvButtonStyle.Primary,
                enabled = key.sdkId != null,
            )
            TvButton("Back", { ctx.nav.pop() }, icon = ArchieIcons.ArrowBack)
        }
    }
}

@Composable
private fun Waiting(label: String) {
    ScreenChromeEffect(ScreenChrome(title = label, hints = listOf(KeyHints.Back), fullScreen = true))
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spinner(size = 28.dp, strokeWidth = 3.dp)
            androidx.tv.material3.Text(label, style = com.assistant.archie.tv.theme.TvTheme.type.bodyLarge)
        }
    }
}
