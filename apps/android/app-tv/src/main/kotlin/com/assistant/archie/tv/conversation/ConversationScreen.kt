package com.assistant.archie.tv.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import com.assistant.archie.tv.shell.FocusRestoreEffect
import com.assistant.archie.tv.theme.rememberFocusMemory
import com.assistant.archie.tv.theme.focusMemory
import com.assistant.archie.tv.theme.FocusMemory
import com.assistant.archie.tv.theme.FocusSettling
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.assistant.archie.feature.chat.ChatAction
import com.assistant.archie.feature.chat.ConversationUiState
import com.assistant.archie.feature.chat.ui.CatalogToolCardRenderer
import com.assistant.archie.feature.chat.ui.ToolCardRenderer
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.LocalTvKeyActions
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvKey
import com.assistant.archie.tv.shell.TvKeyHandler
import com.assistant.archie.tv.shell.TvOption
import com.assistant.archie.tv.theme.ContextRing
import com.assistant.archie.tv.theme.FocusCard
import com.assistant.archie.tv.theme.ProviderPill
import com.assistant.archie.tv.theme.StatusCluster
import com.assistant.archie.tv.theme.TvBusyVeil
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvTextInput
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.design.components.ArchieMark
import com.assistant.core.design.components.Spinner
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

/*
 * Conversation (spec 15 §3.5, mockup 6) — full screen, drawer hidden, its own header. The
 * transcript (Transcript.kt) is a column of focusable blocks; the composer row is Hold to talk
 * (explains itself; the mic works anywhere, T4), Type (the Fire TV keyboard), Live voice (Archie),
 * Stop and More (= Menu). Menu: Stop, Compact, Live voice, Type, Rename, New, Close, Delete, Agents.
 * Long-press OK on a message: rewind / fork / copy to composer. The screen is state-driven
 * (`ConversationUiState` from `:feature:chat`'s ViewModel), so goldens render it without a graph.
 */

/** The header line (title, harness, status, context, agents) and the status cluster. */
@Immutable
data class ConversationHeaderUi(
    val title: String = "",
    val archie: Boolean = false,
    val provider: String? = null,
    val connection: ConnectionStatus.Phase = ConnectionStatus.Phase.CONNECTED,
    val voiceLabel: String = "Idle",
    val voiceActive: Boolean = false,
    /** Another device owns live voice: "Live voice" reads "Take over" (spec 12 §7). */
    val voiceElsewhere: Boolean = false,
)

class ConversationScreenActions(
    val onAction: (ChatAction) -> Unit = {},
    val liveVoice: () -> Unit = {},
    /** The explicit take-over of live voice owned by another device. */
    val takeOver: () -> Unit = {},
    val holdToTalk: () -> Unit = {},
    val rename: () -> Unit = {},
    val newConversation: () -> Unit = {},
    val close: () -> Unit = {},
    val delete: () -> Unit = {},
    val textOf: (String) -> String? = { null },
    val back: () -> Unit = {},
)

/** Which overlay the screen shows (goldens open them directly). */
enum class ConversationOverlay { NONE, AGENTS, MESSAGE_ACTIONS }

@Composable
fun ConversationScreen(
    state: ConversationUiState,
    header: ConversationHeaderUi,
    draft: String,
    actions: ConversationScreenActions,
    modifier: Modifier = Modifier,
    toolCards: ToolCardRenderer = CatalogToolCardRenderer,
    listState: LazyListState = rememberLazyListState(),
    reduceMotion: Boolean = false,
    /** Goldens: the item key to focus first instead of the newest block. */
    initialFocusKey: String? = null,
    /** Goldens: an overlay open from the start. */
    initialOverlay: ConversationOverlay = ConversationOverlay.NONE,
) {
    val c = TvTheme.colors
    val controller = rememberTranscriptController(listState, reduceMotion)
    var typing by rememberSaveable { mutableStateOf(false) }
    var overlay by rememberSaveable { mutableStateOf(initialOverlay) }
    var actionsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRewind by rememberSaveable { mutableStateOf<String?>(null) }
    val onAction = actions.onAction
    val agents = state.agents
    val composer = state.composer
    val working = composer.working
    val ended = composer.disabledReason
    val items = remember(state.items, state.cards, ended) { TranscriptModel.items(state.items, state.cards, ended) }
    val newest = remember(items) { TranscriptModel.newestFollowable(items) }
    val pending = TranscriptModel.approvalPending(state.cards)
    val overlayOpen = typing || overlay != ConversationOverlay.NONE || actionsFor != null || confirmRewind != null || state.agentView != null

    ScreenChromeEffect(
        ScreenChrome(
            title = header.title,
            hints = buildList {
                add(KeyHints.Mic)
                if (header.archie) add(KeyHints.LiveVoice)
                add(KeyHints.Page())
                add(KeyHints.Menu)
                if (pending) add(KeyHints.ApprovalNeeded)
            },
            options = buildList {
                if (working) add(TvOption("c:stop", "Stop", ArchieIcons.StopFilled, detail = "Interrupt the reply") { onAction(ChatAction.Stop) })
                add(
                    TvOption(
                        "c:compact", "Compact", ArchieIcons.Compress,
                        detail = composer.context?.let { "Context ${(it * 100).toInt()}% used" } ?: "Summarize to free context",
                        enabled = composer.canCompact,
                    ) { onAction(ChatAction.Compact) },
                )
                if (header.archie) {
                    if (header.voiceElsewhere) {
                        add(TvOption("c:voice", "Take over live voice", ArchieIcons.GraphicEq, detail = "It is on another device") { actions.takeOver() })
                    } else {
                        add(TvOption("c:voice", "Live voice", ArchieIcons.GraphicEq) { actions.liveVoice() })
                    }
                }
                add(TvOption("c:type", "Type", ArchieIcons.Keyboard, enabled = composer.enabled) { typing = true })
                add(TvOption("c:rename", "Rename", ArchieIcons.Edit) { actions.rename() })
                add(TvOption("c:new", "New", ArchieIcons.AddComment, detail = if (header.archie) "New Archie conversation" else "New agent session") { actions.newConversation() })
                add(TvOption("c:close", "Close", ArchieIcons.Close, detail = "Stops it on every device") { actions.close() })
                add(TvOption("c:delete", "Delete", ArchieIcons.Delete, detail = "Asks first") { actions.delete() })
                if (agents.any) add(TvOption("c:agents", "Agents", ArchieIcons.SmartToy, detail = "${agents.all.size} in this session") { overlay = ConversationOverlay.AGENTS })
            },
            fullScreen = true,
            showHeader = false,
            keys = TvKeyHandler { key ->
                when (key) {
                    TvKey.REWIND, TvKey.FAST_FORWARD -> {
                        if (!overlayOpen) controller.page(down = key == TvKey.FAST_FORWARD, newest = newest)
                        true
                    }
                    else -> false
                }
            },
        ),
    )

    // Initial focus: the composer at once (never zero focused elements), then the newest block once
    // the history arrives — unless the user already moved.
    val typeButton = remember { FocusRequester() }
    val talkButton = remember { FocusRequester() }
    val composerMemory = rememberFocusMemory()
    var userMoved by rememberSaveable { mutableStateOf(false) }
    var placed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (!placed) runCatching { (if (header.archie) talkButton else typeButton).requestFocus() }
    }
    LaunchedEffect(newest != null, state.loaded) {
        if (!TranscriptModel.shouldPlaceInitialFocus(placed, userMoved, typing || overlayOpen)) return@LaunchedEffect
        val target = initialFocusKey ?: newest ?: return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (TranscriptModel.shouldPlaceInitialFocus(placed, userMoved, typing || overlayOpen) && controller.focus(target)) placed = true
    }
    // Focus comes back (an overlay closed, the focused card left): the element it left, else the
    // newest block, else the composer (B3).
    FocusRestoreEffect {
        when (val t = TranscriptModel.restoreTarget(controller.lastFocusedKey, controller.requesters.keys, newest)) {
            is TranscriptModel.Restore.Block -> controller.focus(t.key)
            TranscriptModel.Restore.Composer -> composerMemory.restore() || runCatching { (if (header.archie) talkButton else typeButton).requestFocus() }.getOrDefault(false)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            // B5: any D-pad / OK press before the history placed focus is the user's move: placement
            // then never takes focus from them.
            .onPreviewKeyEvent { e ->
                if (!placed && !userMoved && e.type == KeyEventType.KeyDown && e.key in UserMoveKeys) userMoved = true
                false
            }
            .testTag("conversation"),
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(header, state, onAgents = { overlay = ConversationOverlay.AGENTS })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !state.loaded && state.empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(size = 28.dp, strokeWidth = 3.dp) }
                    state.empty && items.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (header.archie) "Talk or type to start." else "Type a message to start.", style = TvTheme.type.titleLarge, color = c.onSurface)
                        Text(
                            if (header.archie) "Hold the mic button on the remote to talk to Archie." else "Type, or hold the mic button on the remote to dictate.",
                            style = TvTheme.type.bodyMedium,
                            color = c.onSurfaceVariant,
                        )
                    }
                    else -> Transcript(
                        items = items,
                        kind = state.kind,
                        toolCards = toolCards,
                        controller = controller,
                        onAction = onAction,
                        onLongPress = { id -> actionsFor = id },
                        modifier = Modifier.onFocusChanged { if (it.hasFocus && placed) userMoved = true },
                    )
                }
                state.busyOverlay?.let { TvBusyVeil(it) }
            }
            if (state.queue.isNotEmpty()) {
                Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = TranscriptMaxWidth).padding(top = 6.dp)) {
                    state.queue.forEach { q ->
                        Text("Queued · ${q.text}", style = TvTheme.type.labelMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (typing) {
                Column(Modifier.fillMaxWidth()) {
                    TvTextInput(
                        value = draft,
                        onValueChange = { onAction(ChatAction.DraftChanged(it)) },
                        placeholder = composer.placeholder,
                        leadingIcon = ArchieIcons.Keyboard,
                        imeAction = ImeAction.Send,
                        startEditing = true,
                        modifier = Modifier.fillMaxWidth().testTag("composer-field"),
                        onSubmit = { onAction(ChatAction.Send) },
                        onEditingChanged = { editing -> if (!editing) typing = false },
                    )
                    if (working) {
                        Text(
                            "Sends into the running turn, or waits until it ends.",
                            style = TvTheme.type.labelMedium,
                            color = c.onSurfaceVariant,
                            modifier = Modifier.padding(start = 22.dp, top = 4.dp),
                        )
                    }
                }
            } else {
                ComposerRow(
                    archie = header.archie,
                    voiceElsewhere = header.voiceElsewhere,
                    working = working,
                    enabled = composer.enabled,
                    talk = talkButton,
                    type = typeButton,
                    memory = composerMemory,
                    actions = actions,
                    onType = { typing = true },
                    onFocusComposer = {
                        controller.toBottom()
                        if (!FocusSettling.active) controller.lastFocusedKey = TranscriptController.COMPOSER
                    },
                )
                LaunchedEffect(Unit) {
                    // Back from the keyboard: focus returns to Type.
                    if (placed || userMoved) {
                        withFrameNanos { }
                        runCatching { typeButton.requestFocus() }
                    }
                }
            }
        }
        state.agentView?.let { v -> AgentTranscript(v, state.kind, toolCards, onAction, reduceMotion) }
        if (overlay == ConversationOverlay.AGENTS && agents.any) AgentsSheet(agents, onAction) { overlay = ConversationOverlay.NONE }
        actionsFor?.let { id ->
            val text = remember(id) { actions.textOf(id) }
            MessageActionsSheet(
                hasText = text != null,
                canEdit = composer.enabled,
                onCopyToComposer = {
                    actionsFor = null
                    if (text != null) onAction(ChatAction.DraftChanged(text))
                    typing = true
                },
                onFork = {
                    actionsFor = null
                    onAction(ChatAction.Fork(id))
                },
                onRewind = {
                    actionsFor = null
                    confirmRewind = id
                },
                onClose = { actionsFor = null },
            )
        }
        confirmRewind?.let { id ->
            RewindConfirm(onConfirm = {
                confirmRewind = null
                onAction(ChatAction.Rewind(id))
            }, onDismiss = { confirmRewind = null })
        }
    }
}

@Composable
private fun Header(header: ConversationHeaderUi, state: ConversationUiState, onAgents: () -> Unit) {
    val c = TvTheme.colors
    Row(Modifier.fillMaxWidth().height(48.dp).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ArchieIcons.ArrowBack, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        if (header.archie) ArchieMark(size = 28.dp) else ProviderPill(header.provider ?: "Agent")
        Spacer(Modifier.width(12.dp))
        Text(header.title, style = TvTheme.type.titleLarge, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("conversation-title"))
        Spacer(Modifier.width(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.composer.context != null) ContextRing(state.composer.context)
            if (state.composer.working) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 15.dp, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(7.dp))
                    Text("Working…", style = TvTheme.type.labelLarge, color = c.primary)
                }
            } else {
                Text(if (state.composer.disabledReason != null) "Ended" else "Idle", style = TvTheme.type.labelLarge, color = c.onSurfaceVariant)
            }
            if (state.agents.any) {
                FocusCard(
                    onClick = onAgents,
                    onLongClick = {},
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("agents-chip"),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(ArchieIcons.SmartToy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Agents ${state.agents.all.size}", style = TvTheme.type.labelLarge, maxLines = 1)
                        if (state.agents.running > 0) {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.size(8.dp).background(ArchieTheme.extended.success.color, RoundedCornerShape(50)))
                        }
                    }
                }
            }
            StatusCluster(header.connection, header.voiceLabel, header.voiceActive)
        }
    }
}

@Composable
private fun ComposerRow(
    archie: Boolean,
    voiceElsewhere: Boolean,
    working: Boolean,
    enabled: Boolean,
    talk: FocusRequester,
    type: FocusRequester,
    memory: FocusMemory,
    actions: ConversationScreenActions,
    onType: () -> Unit,
    onFocusComposer: () -> Unit,
) {
    val keys = LocalTvKeyActions.current
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.hasFocus) onFocusComposer() }
            .testTag("composer"),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // [memory]: focus comes back to the button it left (B3: an overlay opened from More closed).
        TvButton("Hold to talk", actions.holdToTalk, Modifier.focusRequester(talk).focusMemory(memory, "talk").testTag("composer-talk"), icon = ArchieIcons.Mic)
        TvButton("Type", onType, Modifier.focusRequester(type).focusMemory(memory, "type").testTag("composer-type"), icon = ArchieIcons.Keyboard, enabled = enabled)
        if (archie) {
            TvButton(
                if (voiceElsewhere) "Take over" else "Live voice",
                if (voiceElsewhere) actions.takeOver else actions.liveVoice,
                Modifier.focusMemory(memory, "voice").testTag("composer-voice"),
                icon = ArchieIcons.GraphicEq,
            )
        }
        TvButton("Stop", { actions.onAction(ChatAction.Stop) }, Modifier.focusMemory(memory, "stop").testTag("composer-stop"), icon = ArchieIcons.StopFilled, enabled = working)
        TvButton("More", { keys.openOptions() }, Modifier.focusMemory(memory, "more").testTag("composer-more"), icon = ArchieIcons.Menu)
    }
}

/** Keys that count as the user's own move (B5). */
private val UserMoveKeys = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
