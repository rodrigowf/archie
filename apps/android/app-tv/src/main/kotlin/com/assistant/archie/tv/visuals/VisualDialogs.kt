package com.assistant.archie.tv.visuals

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.assistant.archie.tv.shell.TvOverlay
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvButtonStyle
import com.assistant.archie.tv.theme.TvDimens
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.theme.dismissOnBack
import com.assistant.archie.tv.theme.rememberInitialFocus
import com.assistant.archie.tv.theme.rememberRefocusInside
import com.assistant.archie.tv.theme.trapFocus
import com.assistant.core.design.icons.ArchieIcons

/** Menu → Rename: the title in a text field with the Fire IME; "Done" saves, Back cancels. */
@Composable
fun RenameDialog(title: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    TvOverlay { RenamePanel(title, onRename, onDismiss) }
}

@Composable
internal fun RenamePanel(title: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    val c = TvTheme.colors
    var value by remember { mutableStateOf(TextFieldValue(title, TextRange(title.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    val save = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    // The Fire TV keyboard closing takes focus out of the field: it comes back to Save, never the
    // drawer (and not the field, which would open the keyboard again).
    DialogPanel(onDismiss, Modifier.testTag("rename-dialog"), refocus = save) {
        Text("Rename", style = TvTheme.type.headlineSmall, color = c.onSurface)
        Text("The new title shows in every app.", style = TvTheme.type.bodyMedium, color = c.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = TvTheme.type.bodyLarge.copy(color = c.onSurface),
            cursorBrush = SolidColor(c.primary),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); onRename(value.text) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .background(c.surfaceContainerHighest, RoundedCornerShape(12.dp))
                .border(if (focused) TvDimens.FocusBorder else 1.dp, if (focused) c.primary else c.outline, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("rename-field"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvButton("Cancel", onDismiss)
            TvButton("Save", { onRename(value.text) }, Modifier.focusRequester(save), style = TvButtonStyle.Primary, icon = ArchieIcons.Check)
        }
    }
}

/**
 * Menu → Copy link: the page's address (also put on the clipboard) and a QR code of it, so a phone
 * can open the same page. Close has focus; Back closes.
 */
@Composable
fun LinkDialog(title: String, url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(url) {
        runCatching {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, url))
        }
    }
    TvOverlay { LinkPanel(title, url, onDismiss) }
}

@Composable
internal fun LinkPanel(title: String, url: String, onDismiss: () -> Unit) {
    val c = TvTheme.colors
    val close = rememberInitialFocus(url)
    val qr = remember(url) { runCatching { QrCode.encode(url) }.getOrNull() }
    BackHandler(onBack = onDismiss)
    DialogPanel(onDismiss, Modifier.testTag("link-dialog")) {
        Text(title, style = TvTheme.type.headlineSmall, color = c.onSurface, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (qr != null) QrImage(qr, Modifier.size(180.dp).testTag("link-qr"))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Scan with a phone, or type:", style = TvTheme.type.bodyMedium, color = c.onSurfaceVariant)
                Text(url, style = TvTheme.type.code, color = c.onSurface)
                Text("Also copied to this TV's clipboard.", style = TvTheme.type.labelMedium, color = c.onSurfaceVariant)
            }
        }
        TvButton("Close", onDismiss, Modifier.focusRequester(close), style = TvButtonStyle.Primary)
    }
}

/** The QR matrix on a white quiet zone (scanners need dark-on-light whatever the theme). */
@Composable
fun QrImage(qr: QrCode, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color.White, RoundedCornerShape(8.dp))) {
        val quiet = 2
        val n = qr.size + quiet * 2
        val cell = size.minDimension / n
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr[x, y]) drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/**
 * A centered panel over a scrim, drawn as a shell overlay layer ([TvOverlay]): focus stays inside,
 * Back closes it (`trapFocus` + `dismissOnBack`; a trap that cancelled every exit swallowed Back,
 * B1), and the shell gives focus back to the card when it closes.
 */
@Composable
private fun DialogPanel(onDismiss: () -> Unit, modifier: Modifier = Modifier, refocus: FocusRequester? = null, content: @Composable () -> Unit) {
    val c = TvTheme.colors
    Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .background(c.surfaceContainerHigh, RoundedCornerShape(28.dp))
                .padding(horizontal = 32.dp, vertical = 28.dp)
                .dismissOnBack(onDismiss)
                .then(rememberRefocusInside(refocus))
                .trapFocus(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
            Spacer(Modifier.height(2.dp))
        }
    }
}
