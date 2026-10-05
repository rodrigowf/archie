package com.assistant.archie.feature.sessions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.assistant.archie.feature.sessions.ConflictChoice
import com.assistant.archie.feature.sessions.ConflictMode
import com.assistant.archie.feature.sessions.SessionDialog
import com.assistant.archie.feature.sessions.SessionsIntent
import com.assistant.archie.feature.sessions.SessionsUiState
import com.assistant.core.design.Corner
import com.assistant.core.design.Elevation
import com.assistant.core.design.components.ArchieButton
import com.assistant.core.design.components.ArchieButtonColors
import com.assistant.core.design.components.ArchieButtonDefaults
import com.assistant.core.design.components.ArchieDialogSurface
import com.assistant.core.design.components.ArchieSnackbar
import com.assistant.core.design.components.ArchieTextField
import com.assistant.core.design.components.ButtonStyle
import com.assistant.core.design.components.Spinner
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

/**
 * Mounted once by the shell (web `SessionActionsHost`): the session dialogs, the busy overlay and
 * the session snackbar, all driven by [SessionsUiState].
 */
@Composable
fun SessionsHost(
    state: SessionsUiState,
    onIntent: (SessionsIntent) -> Unit,
    modifier: Modifier = Modifier,
    /** The rename field takes focus when the dialog opens (Robolectric tests turn this off: a focused field blinks forever there). */
    autofocus: Boolean = true,
) {
    when (val d = state.dialog) {
        is SessionDialog.Rename -> RenameDialog(d, onIntent, autofocus)
        is SessionDialog.Delete -> DeleteDialog(d, onIntent)
        is SessionDialog.Fork -> ForkDialog(d, onIntent)
        is SessionDialog.Close -> CloseDialog(d, onIntent)
        is SessionDialog.ArchieConflict -> ArchieConflictDialog(d, onIntent)
        null -> Unit
    }
    state.busy?.let { BusyOverlay(it) }
    SessionSnackbar(state, onIntent, modifier)
}

@Composable
private fun DialogFrame(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) { content() }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit, destructive: Boolean = false, enabled: Boolean = true) {
    val c = ArchieTheme.colors
    ArchieButton(
        label,
        onClick,
        style = ButtonStyle.Text,
        enabled = enabled,
        colors = if (destructive) ArchieButtonColors(Color.Transparent, c.error) else ArchieButtonDefaults.colors(ButtonStyle.Text),
    )
}

// ───────────────────────── rename (§6.6) ─────────────────────────

/** Title edit dialog (web `RenameDialog`): Save commits a changed, non-empty title; Cancel / Back leave it. */
@Composable
internal fun RenameDialog(d: SessionDialog.Rename, onIntent: (SessionsIntent) -> Unit, autofocus: Boolean = true) {
    DialogFrame({ onIntent(SessionsIntent.DismissDialog) }) { RenameDialogContent(d, onIntent, autofocus) }
}

/** The rename dialog's surface (also drawn in place for its golden). */
@Composable
fun RenameDialogContent(d: SessionDialog.Rename, onIntent: (SessionsIntent) -> Unit, autofocus: Boolean = true) {
    var value by rememberSaveable(d.target.sdkId) { mutableStateOf(d.initial) }
    val focus = remember { FocusRequester() }
    val cancel = { onIntent(SessionsIntent.DismissDialog) }
    val save = { onIntent(SessionsIntent.CommitRename(value)) }
    ArchieDialogSurface(
        if (d.target.isArchie) "Rename conversation" else "Rename session",
        Modifier.testTag("rename-dialog"),
        body = {
            ArchieTextField(
                value = value,
                onValueChange = { value = it },
                label = "Title",
                modifier = Modifier.focusRequester(focus).testTag("rename-field"),
                enabled = !d.saving,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) save() }),
            )
        },
    ) {
        TextAction("Cancel", cancel)
        TextAction(if (d.saving) "Saving…" else "Save", save, enabled = value.isNotBlank() && !d.saving)
    }
    if (autofocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

// ───────────────────────── delete (§6.8) ─────────────────────────

internal fun deleteTitle(d: SessionDialog.Delete) = if (d.target.isArchie) "Delete this conversation?" else "Delete this session?"

/** Spec 12 §6.8 copy (web parity): trash, recoverable; memory kept; a live session stops everywhere. */
internal fun deleteBody(d: SessionDialog.Delete): String {
    val n = d.messageCount
    val count = if (n != null && n > 0) " and its $n ${if (n == 1) "message" else "messages"}" else ""
    val stop = when {
        !d.stopsLive -> ""
        d.target.isArchie -> " Archie stops on every device."
        else -> " The session stops on every device."
    }
    return "“${d.target.title}”$count move to the server’s trash (recoverable from context/trash/). Memory files are kept.$stop"
}

@Composable
internal fun DeleteDialog(d: SessionDialog.Delete, onIntent: (SessionsIntent) -> Unit) {
    val cancel = { onIntent(SessionsIntent.DismissDialog) }
    DialogFrame(cancel) {
        ArchieDialogSurface(deleteTitle(d), Modifier.testTag("delete-dialog"), body = { Text(deleteBody(d)) }) {
            TextAction("Cancel", cancel)
            TextAction("Delete", { onIntent(SessionsIntent.ConfirmDelete) }, destructive = true)
        }
    }
}

// ───────────────────────── fork (§6.5) ─────────────────────────

internal fun forkBody(d: SessionDialog.Fork): String =
    if (d.target.isArchie) "A copy of the whole conversation is added to the history. Open it from there to continue the copy. The original is unchanged."
    else "A copy of the whole conversation opens as a new session. The original is unchanged."

@Composable
internal fun ForkDialog(d: SessionDialog.Fork, onIntent: (SessionsIntent) -> Unit) {
    val cancel = { onIntent(SessionsIntent.DismissDialog) }
    DialogFrame(cancel) {
        ArchieDialogSurface("Fork this conversation?", Modifier.testTag("fork-dialog"), body = { Text(forkBody(d)) }) {
            TextAction("Cancel", cancel)
            TextAction("Fork", { onIntent(SessionsIntent.ConfirmFork) })
        }
    }
}

// ───────────────────────── close (§6.7) ─────────────────────────

internal fun closeTitle(d: SessionDialog.Close) = if (d.archie) "Stop Archie on all devices?" else "Close this session?"

internal fun closeBody(d: SessionDialog.Close) =
    if (d.archie) "Closing “${d.title}” ends the Archie conversation for every device. You can resume it later from the history."
    else "“${d.title}” is still working. Closing it stops the current reply for every device."

@Composable
internal fun CloseDialog(d: SessionDialog.Close, onIntent: (SessionsIntent) -> Unit) {
    val cancel = { onIntent(SessionsIntent.DismissDialog) }
    DialogFrame(cancel) {
        ArchieDialogSurface(closeTitle(d), Modifier.testTag("close-dialog"), body = { Text(closeBody(d)) }) {
            TextAction("Cancel", cancel)
            TextAction(if (d.archie) "Stop Archie" else "Close session", { onIntent(SessionsIntent.ConfirmClose) }, destructive = true)
        }
    }
}

// ───────────────────────── Archie conflict (§6.11) ─────────────────────────

internal fun conflictTitle(d: SessionDialog.ArchieConflict) =
    if (d.mode == ConflictMode.RESUME) "Another Archie conversation is running" else "Archie is already active"

internal fun conflictBody(d: SessionDialog.ArchieConflict): String {
    val where = if (d.running.here) "" else ", and one is running on another device"
    val what = if (d.mode == ConflictMode.RESUME) "Resuming this conversation" else "Starting a new one"
    return "Only one Archie conversation runs at a time$where. $what stops the running one on every device. You can resume it later from the history."
}

internal fun conflictStopLabel(d: SessionDialog.ArchieConflict) =
    if (d.mode == ConflictMode.RESUME) "Stop it and resume this one" else "Stop it and start new"

/**
 * The three-action dialog as a proper dialog with **stacked** buttons (fixes inv03 §1.7: three
 * actions crammed into AlertDialog's confirm/dismiss slots). M3 stacked actions: end-aligned, the
 * recommended one first, the dismissive one last.
 */
@Composable
internal fun ArchieConflictDialog(d: SessionDialog.ArchieConflict, onIntent: (SessionsIntent) -> Unit) {
    val cancel = { onIntent(SessionsIntent.ResolveConflict(ConflictChoice.CANCEL)) }
    val c = ArchieTheme.colors
    DialogFrame(cancel) {
        Surface(
            modifier = Modifier.widthIn(max = 360.dp).testTag("conflict-dialog").semantics { paneTitle = conflictTitle(d) },
            shape = RoundedCornerShape(Corner.ExtraLarge),
            color = c.surfaceContainerHigh,
            shadowElevation = Elevation.Level3,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ArchieIcon(ArchieIcons.Forum, null, tint = c.secondary)
                }
                Text(conflictTitle(d), Modifier.fillMaxWidth(), style = ArchieTheme.typography.headlineSmall, color = c.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                CompositionLocalProvider(LocalContentColor provides c.onSurfaceVariant) {
                    Text(conflictBody(d), style = ArchieTheme.typography.bodyMedium)
                }
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextAction("Open the running one", { onIntent(SessionsIntent.ResolveConflict(ConflictChoice.OPEN)) })
                    TextAction(conflictStopLabel(d), { onIntent(SessionsIntent.ResolveConflict(ConflictChoice.REPLACE)) }, destructive = true)
                    TextAction("Cancel", cancel)
                }
            }
        }
    }
}

// ───────────────────────── busy + snackbar ─────────────────────────

/** inv02 F-12: rewind, fork, delete, replacing Archie block input until they finish. */
@Composable
internal fun BusyOverlay(label: String) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(
            Modifier.testTag("busy-overlay").semantics { liveRegion = LiveRegionMode.Polite },
            shape = RoundedCornerShape(Corner.Large),
            color = ArchieTheme.colors.surfaceContainerHigh,
            shadowElevation = Elevation.Level3,
        ) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 20.dp)
                Text(label, style = ArchieTheme.typography.bodyLarge, color = ArchieTheme.colors.onSurface)
            }
        }
    }
}

/**
 * The session snackbar ("Deleted …", "Duplicated … · Open", "Renamed … · Undo", errors verbatim).
 * Its timeout is the controller's, so it is the same in the app and in tests.
 */
@Composable
internal fun SessionSnackbar(state: SessionsUiState, onIntent: (SessionsIntent) -> Unit, modifier: Modifier = Modifier) {
    val snack = state.snack ?: return
    Box(modifier.fillMaxSize().navigationBarsPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
        ArchieSnackbar(
            message = snack.message,
            modifier = Modifier.widthIn(max = 600.dp).padding(start = 12.dp, end = 12.dp, bottom = 96.dp).testTag("session-snackbar"),
            actionLabel = snack.actionLabel,
            onAction = { onIntent(SessionsIntent.SnackAction(snack.id)) },
            onDismiss = if (snack.error || snack.actionLabel != null) ({ onIntent(SessionsIntent.SnackDismissed(snack.id)) }) else null,
        )
    }
}
