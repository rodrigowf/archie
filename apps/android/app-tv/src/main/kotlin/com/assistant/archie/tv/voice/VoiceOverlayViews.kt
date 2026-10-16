package com.assistant.archie.tv.voice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvButtonStyle
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.core.design.icons.ArchieIcons

/*
 * The voice overlay's stateless views (spec 15 §3.4, §4.4; mockups 7, 8, 9). [VoiceOverlayHost]
 * feeds them from TvVoice; the goldens render them with fixed state. None of them takes focus except
 * the dictation chip (the user just spoke).
 */

/** Bars of the hold meter, oldest first (0..1). */
const val LEVEL_BARS = 18

/** Mockup 7: the pill while the mic button is held. */
@Composable
fun HoldOverlay(label: String, levels: List<Float>, elapsedMs: Long, modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    Row(
        modifier
            .background(c.surfaceContainerHigh, RoundedCornerShape(28.dp))
            .border(1.5.dp, c.outlineVariant, RoundedCornerShape(28.dp))
            .padding(start = 16.dp, end = 24.dp, top = 15.dp, bottom = 15.dp)
            .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Listening. Release to send. $label" }
            .testTag("voice-hold"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(Modifier.size(56.dp).background(c.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(ArchieIcons.Mic, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(28.dp))
        }
        LevelBars(levels, Modifier.height(56.dp).width((LEVEL_BARS * 9).dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Release to send", style = TvTheme.type.titleLarge, color = c.onSurface)
            Text(label, style = TvTheme.type.labelLarge, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatElapsed(elapsedMs), style = TvTheme.type.titleMedium, color = c.onSurfaceVariant)
    }
}

/** "Sending…" / "Transcribing…" after the release. */
@Composable
fun WorkingOverlay(text: String, modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    Row(
        modifier
            .background(c.surfaceContainerHigh, RoundedCornerShape(28.dp))
            .border(1.5.dp, c.outlineVariant, RoundedCornerShape(28.dp))
            .padding(horizontal = 24.dp, vertical = 15.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("voice-working"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(56.dp).background(c.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
            Icon(ArchieIcons.Send, contentDescription = null, tint = c.primary, modifier = Modifier.size(26.dp))
        }
        Text(text, style = TvTheme.type.titleLarge, color = c.onSurface)
    }
}

/** What the live panel shows (mockup 8). */
@Immutable
data class LivePanelState(
    /** "Archie is speaking" / "Listening…" / "Thinking…" / "Your turn" / "Connecting…". */
    val status: String,
    val user: String? = null,
    val assistant: String? = null,
    /** The mic button is held right now. */
    val holding: Boolean = false,
    /** 0..1: mic level while held, else a resting pulse. */
    val level: Float = 0f,
    /** Another device owns voice: show "Take over" instead of the hold hint. */
    val elsewhere: Boolean = false,
)

/** Mockup 8: the live-voice panel (orb + running transcript). Never focusable. */
@Composable
fun LivePanel(state: LivePanelState, modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    Column(
        modifier
            .widthIn(max = 750.dp)
            .fillMaxWidth()
            .background(c.surfaceContainerHigh, RoundedCornerShape(28.dp))
            .border(1.5.dp, c.outlineVariant, RoundedCornerShape(28.dp))
            .padding(start = 18.dp, end = 28.dp, top = 18.dp, bottom = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("voice-live"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Orb(state.level, state.holding, Modifier.size(100.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.status, style = TvTheme.type.labelLarge, color = c.onSurfaceVariant)
                state.user?.let { Text("You: $it", style = TvTheme.type.bodyLarge, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                state.assistant?.let { Text(it, style = TvTheme.type.bodyLarge, color = c.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                if (state.user == null && state.assistant == null) {
                    Text("Live voice with Archie", style = TvTheme.type.bodyLarge, color = c.onSurface)
                }
            }
        }
        Spacer(Modifier.height(11.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant))
        Spacer(Modifier.height(11.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(ArchieIcons.Mic, contentDescription = null, tint = c.onSurface, modifier = Modifier.size(18.dp))
            Text(
                if (state.elsewhere) {
                    "Voice is live on another device · ⏯ Take over"
                } else {
                    "Hold the mic to speak · Release to let Archie answer · ⏯ or Back ends"
                },
                style = TvTheme.type.labelLarge,
                color = c.onSurfaceVariant,
            )
        }
    }
}

/** The tertiary orb of mockup 8; its rings follow [level]. */
@Composable
private fun Orb(level: Float, holding: Boolean, modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    val base = if (holding) c.primary else c.tertiary
    val onBase = if (holding) c.onPrimary else c.onTertiary
    Canvas(modifier) {
        val r = size.minDimension / 2
        val center = Offset(size.width / 2, size.height / 2)
        val grow = 0.85f + 0.15f * level.coerceIn(0f, 1f)
        drawCircle(base.copy(alpha = 0.14f), radius = r * grow, center = center)
        drawCircle(base.copy(alpha = 0.28f), radius = r * 0.78f * grow, center = center)
        drawCircle(base, radius = r * 0.57f, center = center)
        val bars = floatArrayOf(0.33f, 0.6f, 0.47f, 0.27f)
        val w = r * 0.1f
        for (i in bars.indices) {
            val h = r * (0.2f + bars[i] * (0.5f + level))
            val x = center.x + (i - 1.5f) * w * 1.5f - w / 2
            drawRoundRect(onBase, topLeft = Offset(x, center.y - h / 2), size = Size(w, h), cornerRadius = CornerRadius(w / 2, w / 2))
        }
    }
}

/** The hold meter: [LEVEL_BARS] rounded bars, newest on the right. */
@Composable
private fun LevelBars(levels: List<Float>, modifier: Modifier = Modifier) {
    val color = TvTheme.colors.primary
    Canvas(modifier) {
        val n = LEVEL_BARS
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val pad = List(maxOf(0, n - levels.size)) { 0f } + levels.takeLast(n)
        pad.forEachIndexed { i, l ->
            val h = (size.height * (0.12f + 0.88f * l.coerceIn(0f, 1f)))
            drawRoundRect(color, topLeft = Offset(i * (w + gap), (size.height - h) / 2), size = Size(w, h), cornerRadius = CornerRadius(w / 2, w / 2))
        }
    }
}

/** Dictation chip actions. */
class DictationActions(
    val send: (String) -> Unit = {},
    val edit: () -> Unit = {},
    val cancel: () -> Unit = {},
)

/**
 * Mockup 9: the dictated text with Send (focused on arrival) / Edit / Cancel. [editing] turns the
 * quote into a text field (IME prefilled); IME Go sends.
 */
@Composable
fun DictationChip(
    text: String,
    editing: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    actions: DictationActions,
    sendFocus: FocusRequester,
    fieldFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val c = TvTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.surfaceContainerHighest, RoundedCornerShape(20.dp))
            .border(1.5.dp, c.outlineVariant, RoundedCornerShape(20.dp))
            .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
            .testTag("voice-dictation"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(ArchieIcons.Mic, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text("Dictated · check before sending to the agent", style = TvTheme.type.labelLarge, color = c.onSurfaceVariant)
            }
            if (editing) {
                BasicTextField(
                    value = draft,
                    onValueChange = onDraft,
                    textStyle = TvTheme.type.titleLarge.copy(color = c.onSurface),
                    cursorBrush = SolidColor(c.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { actions.send(draft) }, onDone = { actions.send(draft) }, onGo = { actions.send(draft) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surfaceContainer, RoundedCornerShape(10.dp))
                        .border(2.dp, c.primary, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .focusRequester(fieldFocus)
                        .testTag("voice-dictation-field"),
                )
            } else {
                Text("“$text”", style = TvTheme.type.titleLarge, color = c.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        TvButton("Send", { actions.send(if (editing) draft else text) }, Modifier.focusRequester(sendFocus).testTag("voice-dictation-send"), icon = ArchieIcons.Send, style = TvButtonStyle.Primary)
        if (!editing) TvButton("Edit", actions.edit, Modifier.testTag("voice-dictation-edit"), icon = ArchieIcons.Edit)
        TvButton("Cancel", actions.cancel, Modifier.testTag("voice-dictation-cancel"), icon = ArchieIcons.Close)
    }
}

/** 0:03 / 1:12. */
fun formatElapsed(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
