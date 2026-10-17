package com.assistant.archie.tv.theme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.assistant.archie.tv.shell.LocalTvKeyActions
import com.assistant.archie.tv.shell.LocalTvShellFocus
import com.assistant.archie.tv.shell.TvKey
import com.assistant.archie.tv.shell.TvKeyHandler
import com.assistant.archie.tv.shell.TvOverlay

/*
 * Overlays and text input for the T1/T2 screens (spec 15 §3.4-§3.5): a right side sheet (the same
 * look as the shell's options sheet), a centered dialog, a sheet row, and a text input that opens
 * the Fire TV keyboard only when the user presses OK on it (D-pad focus alone never pops the IME).
 * Every overlay traps focus, closes on Back and never takes focus by itself unless the user opened it.
 */

/**
 * A right side sheet over a scrim (Menu sheets, agents, provider picker). Back or Menu closes it
 * (Menu, like the options sheet); Rewind / FF never reach the screen behind it.
 */
@Composable
fun TvSideSheet(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    width: Dp = 440.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = TvTheme.colors
    val latestClose by rememberUpdatedState(onClose)
    val keys = remember {
        TvKeyHandler { key ->
            when (key) {
                TvKey.MENU -> { latestClose(); true }
                TvKey.PLAY_PAUSE -> false // live voice
                else -> true
            }
        }
    }
    TvOverlay(keys) {
        BackHandler(onBack = onClose)
        Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.55f)).testTag("side-sheet-scrim")) {
            Column(
                modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(width)
                    .background(c.surfaceContainerHigh, RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                    .padding(start = 24.dp, end = TvDimens.OverscanHorizontal, top = TvDimens.OverscanVertical + 8.dp, bottom = TvDimens.OverscanVertical)
                    .dismissOnBack(onClose)
                    .then(rememberRefocusInside())
                    .trapFocus()
                    .testTag("side-sheet"),
            ) {
                if (eyebrow != null) Text(eyebrow, style = TvTheme.type.labelLarge, color = c.primary)
                Text(title, style = TvTheme.type.titleLarge, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = TvTheme.type.labelMedium, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.size(16.dp))
                content()
            }
        }
    }
}

/** One action row of a side sheet: icon, label, optional detail. */
@Composable
fun TvSheetRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    detail: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    FocusCard(onClick = onClick, onLongClick = {}, enabled = enabled, modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = TvTheme.type.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) CardMeta(detail, maxLines = 2)
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
    }
}

/**
 * A centered dialog over a scrim: title, body, optional content, and a row of buttons. The first
 * button the caller marks with [initialFocus] gets focus; Back calls [onDismiss].
 */
@Composable
fun TvDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    buttons: @Composable RowScope.() -> Unit,
) {
    val c = TvTheme.colors
    TvOverlay {
        BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.6f)).testTag("dialog-scrim"), contentAlignment = Alignment.Center) {
            Column(
                modifier
                    .widthIn(min = 420.dp, max = 620.dp)
                    .background(c.surfaceContainerHigh, RoundedCornerShape(28.dp))
                    .padding(28.dp)
                    .dismissOnBack(onDismiss)
                    .then(rememberRefocusInside())
                    .trapFocus()
                    .testTag("dialog"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = TvTheme.type.titleLarge, color = c.onSurface)
                if (body != null) Text(body, style = TvTheme.type.bodyMedium, color = c.onSurfaceVariant)
                content?.invoke(this)
                Spacer(Modifier.size(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End), content = buttons)
            }
        }
    }
}

/** Requests focus on [requester] after the first frame (dialog / sheet openers). */
@Composable
fun FocusOnOpen(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}

/**
 * A text field for the remote (spec 15 §3.5 search, rename, composer "Type"): shown as a focusable
 * pill; OK starts editing (focus moves into the real field and the Fire TV keyboard opens), the
 * keyboard's action key calls [onSubmit], Back leaves editing. [startEditing] opens it in edit mode
 * (the composer's Type button, a rename dialog).
 */
@Composable
fun TvTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    imeAction: ImeAction = ImeAction.Done,
    startEditing: Boolean = false,
    singleLine: Boolean = true,
    onSubmit: (String) -> Unit = {},
    onEditingChanged: (Boolean) -> Unit = {},
    pillRequester: FocusRequester = remember { FocusRequester() },
) {
    val c = TvTheme.colors
    var editing by rememberSaveable { mutableStateOf(startEditing) }
    var refocusPill by remember { mutableStateOf(false) }
    var refocusIfMisplaced by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val keys = LocalTvKeyActions.current
    val shellFocus = LocalTvShellFocus.current
    fun setEditing(v: Boolean) {
        if (editing == v) return
        editing = v
        onEditingChanged(v)
    }
    if (editing) {
        // The shell never moves focus away from a field in edit mode (an overlay closing).
        DisposableEffect(shellFocus) {
            shellFocus.editingText.intValue++
            onDispose { shellFocus.editingText.intValue-- }
        }
        BackHandler {
            keyboard?.hide()
            refocusPill = true
            setEditing(false)
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { field.requestFocus() }
            keyboard?.show()
        }
        val shape = RoundedCornerShape(50)
        // onFocusChanged also reports the initial (unfocused) state: leave editing only after a real focus loss.
        val gained = remember { booleanArrayOf(false) }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4,
            textStyle = TvTheme.type.bodyLarge.copy(color = c.onSurface),
            cursorBrush = SolidColor(c.primary),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = {
                keyboard?.hide()
                refocusPill = true
                setEditing(false)
                onSubmit(value)
            }),
            modifier = modifier
                .heightIn(min = 52.dp)
                .focusRequester(field)
                .dismissOnBack {
                    keyboard?.hide()
                    refocusPill = true
                    setEditing(false)
                }
                .onFocusChanged {
                    if (it.hasFocus) {
                        gained[0] = true
                    } else if (gained[0] && editing) {
                        // Focus left the field without our Back / action key: the Fire TV's full-screen
                        // keyboard closed and Compose parked focus somewhere (the drawer). Back to the pill
                        // when it is misplaced; a real move (D-pad out of the field) stays (B3).
                        refocusIfMisplaced = true
                        setEditing(false)
                    }
                }
                .testTag("text-input-field"),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .background(c.surfaceContainerHighest, shape)
                        .border(BorderStroke(TvDimens.FocusBorder, c.primary), shape)
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leadingIcon != null) {
                        Icon(leadingIcon, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, style = TvTheme.type.bodyLarge, color = c.onSurfaceVariant, maxLines = 1)
                        inner()
                    }
                }
            },
        )
    } else {
        if (refocusPill) {
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { pillRequester.requestFocus() }
                refocusPill = false
            }
        }
        if (refocusIfMisplaced) {
            LaunchedEffect(Unit) {
                repeat(2) { withFrameNanos { } }
                if (shellFocus.misplaced()) runCatching { pillRequester.requestFocus() }
                refocusIfMisplaced = false
            }
        }
        FocusCard(
            onClick = { setEditing(true) },
            onLongClick = { keys.longPress() },
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            modifier = modifier.heightIn(min = 52.dp).focusRequester(pillRequester).testTag("text-input"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    value.ifEmpty { placeholder },
                    style = TvTheme.type.bodyLarge,
                    color = if (value.isEmpty()) c.onSurfaceVariant else c.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A centered busy veil ("Deleting…", "Forking…"): not focusable, covers its parent. */
@Composable
fun BoxScope.TvBusyVeil(label: String) {
    val c = TvTheme.colors
    Box(Modifier.matchParentSize().background(c.scrim.copy(alpha = 0.45f)).testTag("busy-veil"), contentAlignment = Alignment.Center) {
        Row(
            Modifier.background(c.surfaceContainerHigh, RoundedCornerShape(20.dp)).padding(horizontal = 26.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.assistant.core.design.components.Spinner(size = 24.dp, strokeWidth = 3.dp)
            Spacer(Modifier.width(14.dp))
            Text(label, style = TvTheme.type.bodyLarge, color = c.onSurface)
        }
    }
}
