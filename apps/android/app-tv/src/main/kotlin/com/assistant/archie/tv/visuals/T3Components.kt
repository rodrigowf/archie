package com.assistant.archie.tv.visuals

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.assistant.archie.tv.graph.TvAppGraph
import com.assistant.archie.tv.theme.StatusCluster
import com.assistant.archie.tv.theme.TvDimens
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.voice.VoiceChip
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.design.icons.ArchieIcons

/*
 * Small pieces T3's screens share (Visuals, Memory, MemoryDoc): the search box (spec 15 §3.5, the
 * Fire IME only on OK), and the status cluster for screens that draw their own header.
 */

/** What [StatusCluster] needs, for screens that draw their own header (goldens pass it directly). */
@Immutable
data class StatusView(
    val phase: ConnectionStatus.Phase = ConnectionStatus.Phase.CONNECTED,
    val voiceLabel: String = "Idle",
    val voiceActive: Boolean = false,
)

@Composable
fun rememberStatus(graph: TvAppGraph): StatusView {
    val connection by graph.connection.status.collectAsStateWithLifecycle()
    val voice by graph.voice.state.collectAsStateWithLifecycle()
    return StatusView(connection.phase, VoiceChip.label(voice), VoiceChip.active(voice))
}

@Composable
fun TvStatus(status: StatusView, modifier: Modifier = Modifier) =
    StatusCluster(status.phase, status.voiceLabel, status.voiceActive, modifier)

/**
 * A search field for the remote: a focusable pill showing the query (or [placeholder]). OK turns it
 * into a text field and opens the Fire IME; the list filters as you type; IME "Search"/Back closes
 * the keyboard and keeps the query; Menu-free. An empty query shows everything.
 */
@Composable
fun TvSearchBox(
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    focusRequester: FocusRequester? = null,
) {
    val c = TvTheme.colors
    var editing by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf(false) }
    val pill = focusRequester ?: remember { FocusRequester() }
    val shape = RoundedCornerShape(50)
    // Leaving the text field puts focus back on the pill (the field it had is gone).
    LaunchedEffect(editing) { if (!editing && edited) runCatching { pill.requestFocus() } }
    if (!editing) {
        Surface(
            onClick = { editing = true; edited = true },
            modifier = modifier.heightIn(min = height).focusRequester(pill).testTag("search-box"),
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = c.surfaceContainerHigh,
                contentColor = c.onSurfaceVariant,
                focusedContainerColor = c.surfaceContainerHighest,
                focusedContentColor = c.onSurface,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(TvDimens.FocusBorder, c.primary), shape = shape),
            ),
        ) {
            Row(Modifier.heightIn(min = height).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ArchieIcons.Search, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    query.ifEmpty { placeholder },
                    style = TvTheme.type.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (query.isEmpty()) c.onSurfaceVariant else c.onSurface,
                )
            }
        }
    } else {
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        LaunchedEffect(Unit) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
        BackHandler { editing = false }
        Row(
            modifier
                .heightIn(min = height)
                .background(c.surfaceContainerHighest, shape)
                .border(if (focused) TvDimens.FocusBorder else 1.dp, if (focused) c.primary else c.outline, shape)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(ArchieIcons.Search, contentDescription = null, modifier = Modifier.size(22.dp), tint = c.onSurface)
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TvTheme.type.bodyMedium.copy(color = c.onSurface),
                    cursorBrush = SolidColor(c.primary),
                    interactionSource = interaction,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); editing = false }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("search-field"),
                )
            }
        }
    }
}

/** A small colored dot ("changed since last open"). */
@Composable
fun Dot(color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).background(color, RoundedCornerShape(50)))
}
