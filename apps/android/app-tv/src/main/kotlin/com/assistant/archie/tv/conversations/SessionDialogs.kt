package com.assistant.archie.tv.conversations

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.assistant.archie.feature.sessions.ConflictChoice
import com.assistant.archie.feature.sessions.ConflictMode
import com.assistant.archie.feature.sessions.SessionDialog
import com.assistant.archie.feature.sessions.SessionsIntent
import com.assistant.archie.feature.sessions.SessionsUiState
import com.assistant.archie.tv.theme.FocusOnOpen
import com.assistant.archie.tv.theme.TvBusyVeil
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvButtonStyle
import com.assistant.archie.tv.theme.TvDialog
import com.assistant.archie.tv.theme.TvSheetRow
import com.assistant.archie.tv.theme.TvSideSheet
import com.assistant.archie.tv.theme.TvTextInput
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.model.HarnessInfo
import com.assistant.core.model.HarnessProvider

/*
 * TV renderings of `:feature:sessions`' dialogs (rename with the Fire TV keyboard, delete, close,
 * fork, the §6.11 Archie conflict), the busy veil, and the New agent harness picker. Each screen
 * that runs session flows (Home, Conversations, Conversation) hosts [SessionFlowsHost] once.
 */

/** The dialogs and busy veil of [sessions]; draws nothing while no flow needs the screen. */
@Composable
fun SessionFlowsHost(sessions: TvSessions) {
    val ui by sessions.ui.collectAsStateWithLifecycle()
    SessionFlowsContent(ui, sessions::onIntent)
}

@Composable
fun SessionFlowsContent(ui: SessionsUiState, onIntent: (SessionsIntent) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        ui.busy?.let { TvBusyVeil(it) }
    }
    when (val d = ui.dialog) {
        null -> Unit
        is SessionDialog.Rename -> RenameDialog(d, onIntent)
        is SessionDialog.Delete -> {
            val first = remember { FocusRequester() }
            FocusOnOpen(first)
            val what = d.messageCount?.let { if (it == 1) "1 message" else "$it messages" }
            TvDialog(
                title = "Delete “${d.target.title}”?",
                body = listOfNotNull(
                    what?.let { "Its $it will be deleted." } ?: "The conversation will be deleted.",
                    if (d.stopsLive) "It is open now: it closes on every device first." else null,
                    "This cannot be undone.",
                ).joinToString(" "),
                onDismiss = { onIntent(SessionsIntent.DismissDialog) },
            ) {
                TvButton("Cancel", { onIntent(SessionsIntent.DismissDialog) }, Modifier.focusRequester(first).testTag("dialog-cancel"))
                TvButton("Delete", { onIntent(SessionsIntent.ConfirmDelete) }, Modifier.testTag("dialog-confirm"), icon = ArchieIcons.Delete, style = TvButtonStyle.Primary)
            }
        }
        is SessionDialog.Close -> {
            val first = remember { FocusRequester() }
            FocusOnOpen(first)
            TvDialog(
                title = "Close “${d.title}”?",
                body = if (d.archie) "Archie stops for every device. The conversation stays in history." else "The agent is working. Closing stops it for every device; the conversation stays in history.",
                onDismiss = { onIntent(SessionsIntent.DismissDialog) },
            ) {
                TvButton("Cancel", { onIntent(SessionsIntent.DismissDialog) }, Modifier.focusRequester(first))
                TvButton("Close", { onIntent(SessionsIntent.ConfirmClose) }, icon = ArchieIcons.Close, style = TvButtonStyle.Primary)
            }
        }
        is SessionDialog.Fork -> {
            val first = remember { FocusRequester() }
            FocusOnOpen(first)
            TvDialog(
                title = "Fork “${d.target.title}”?",
                body = "A copy of the whole conversation opens as a new one.",
                onDismiss = { onIntent(SessionsIntent.DismissDialog) },
            ) {
                TvButton("Cancel", { onIntent(SessionsIntent.DismissDialog) })
                TvButton("Fork", { onIntent(SessionsIntent.ConfirmFork) }, Modifier.focusRequester(first), icon = ArchieIcons.CallSplit, style = TvButtonStyle.Primary)
            }
        }
        is SessionDialog.ArchieConflict -> {
            val first = remember { FocusRequester() }
            FocusOnOpen(first)
            val where = if (d.running.here) "here" else "on another device"
            TvDialog(
                title = "Archie is already running",
                body = if (d.mode == ConflictMode.NEW) {
                    "A conversation is running $where. Open it, or stop it and start a new one?"
                } else {
                    "Another conversation is running $where. Open it, or stop it and resume this one?"
                },
                onDismiss = { onIntent(SessionsIntent.ResolveConflict(ConflictChoice.CANCEL)) },
            ) {
                TvButton("Cancel", { onIntent(SessionsIntent.ResolveConflict(ConflictChoice.CANCEL)) })
                TvButton(
                    if (d.mode == ConflictMode.NEW) "Stop it and start new" else "Stop it and resume",
                    { onIntent(SessionsIntent.ResolveConflict(ConflictChoice.REPLACE)) },
                )
                TvButton("Open running", { onIntent(SessionsIntent.ResolveConflict(ConflictChoice.OPEN)) }, Modifier.focusRequester(first), style = TvButtonStyle.Primary)
            }
        }
    }
}

@Composable
private fun RenameDialog(d: SessionDialog.Rename, onIntent: (SessionsIntent) -> Unit) {
    var text by rememberSaveable(d.target.sdkId) { mutableStateOf(d.initial) }
    TvDialog(title = "Rename", body = "The new title shows on every device.", onDismiss = { onIntent(SessionsIntent.DismissDialog) }, content = {
        TvTextInput(
            value = text,
            onValueChange = { text = it },
            placeholder = d.target.title,
            imeAction = ImeAction.Done,
            startEditing = true,
            modifier = Modifier.fillMaxWidth(),
            onSubmit = { onIntent(SessionsIntent.CommitRename(it)) },
        )
        if (d.saving) Text("Saving…", style = TvTheme.type.labelMedium, color = TvTheme.colors.onSurfaceVariant)
    }) {
        TvButton("Cancel", { onIntent(SessionsIntent.DismissDialog) })
        TvButton("Save", { onIntent(SessionsIntent.CommitRename(text)) }, icon = ArchieIcons.Check, style = TvButtonStyle.Primary, enabled = !d.saving)
    }
}

/**
 * New agent session: the server's harness catalog (`GET /api/config/harnesses`, the same registry
 * the apps' settings read). "Default" starts the harness the server is configured with.
 */
@Composable
fun NewAgentSheet(sessions: TvSessions, onPick: (HarnessProvider?) -> Unit, onClose: () -> Unit) {
    var list by remember { mutableStateOf<List<HarnessInfo>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val r = sessions.harnesses()
        if (r == null) failed = true else list = r
    }
    NewAgentSheetContent(list, failed, onPick, onClose)
}

@Composable
fun NewAgentSheetContent(list: List<HarnessInfo>?, failed: Boolean, onPick: (HarnessProvider?) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    FocusOnOpen(first)
    TvSideSheet(
        title = "New agent session",
        eyebrow = "Agent",
        subtitle = when {
            failed -> "Couldn't load the harness list: the default one starts."
            list == null -> "Loading harnesses…"
            else -> "Pick the harness that runs it."
        },
        onClose = onClose,
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "default") {
                TvSheetRow(
                    "Default",
                    { onPick(null) },
                    Modifier.focusRequester(first).testTag("harness-default"),
                    icon = ArchieIcons.Terminal,
                    detail = "The harness set in the server's settings",
                )
            }
            items(list.orEmpty(), key = { it.id }) { h ->
                TvSheetRow(
                    h.label.ifBlank { h.id },
                    { onPick(HarnessProvider(h.id)) },
                    Modifier.testTag("harness-${h.id}"),
                    icon = ArchieIcons.Terminal,
                    detail = h.description?.lineSequence()?.firstOrNull()?.take(90),
                )
            }
        }
    }
}

