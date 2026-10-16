package com.assistant.archie.tv.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.assistant.archie.tv.shell.HintGlyph
import com.assistant.archie.tv.shell.KeyHint
import com.assistant.archie.tv.shell.LocalTvKeyActions
import com.assistant.archie.tv.shell.TvToasts
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import kotlinx.coroutines.delay

/*
 * Reusable 10-foot components (spec 15 §3.2, §3.4). tv-material surfaces underneath (focus scale,
 * press handling, long-press), styled only with the tokens — no color literals. Text inside a
 * tv-material surface uses tv-material `Text`/`Icon`, which read the surface's content color.
 */

/**
 * The focusable card: unfocused on `surfaceContainer`; focused it scales to 1.05, gets a 3 dp
 * `primary` border and the raised `surfaceContainerHighest` surface. Long-press OK goes to
 * [onLongClick], else to the screen's handler / the options sheet (the shell's KeyRouter).
 */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(TvDimens.CardCorner),
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    /** The resting container (lists use a transparent one: only the focused row is raised, mockup 5). */
    containerColor: androidx.compose.ui.graphics.Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = TvTheme.colors
    val keys = LocalTvKeyActions.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongClick ?: { keys.longPress() },
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor ?: c.surfaceContainer,
            contentColor = c.onSurface,
            focusedContainerColor = c.surfaceContainerHighest,
            focusedContentColor = c.onSurface,
            pressedContainerColor = c.surfaceContainerHigh,
            pressedContentColor = c.onSurface,
            disabledContainerColor = c.surfaceContainerLow,
            disabledContentColor = c.onSurfaceVariant,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = TvDimens.FocusedScale),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(TvDimens.FocusBorder, c.primary), shape = shape),
        ),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

enum class TvButtonStyle { Primary, Secondary }

/**
 * A tv-material [Button] with the tokens: Primary on `primaryContainer`, Secondary on
 * `surfaceContainerHigh`; focused, both use tv-material's high-contrast focus (inverse surface).
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: TvButtonStyle = TvButtonStyle.Secondary,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val c = TvTheme.colors
    val keys = LocalTvKeyActions.current
    val primary = style == TvButtonStyle.Primary
    Button(
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongClick ?: { keys.longPress() },
        enabled = enabled,
        scale = ButtonDefaults.scale(focusedScale = TvDimens.FocusedScale),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) c.primaryContainer else c.surfaceContainerHigh,
            contentColor = if (primary) c.onPrimaryContainer else c.onSurface,
            focusedContainerColor = c.inverseSurface,
            focusedContentColor = c.inverseOnSurface,
            disabledContainerColor = c.surfaceContainerLow,
            disabledContentColor = c.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = TvTheme.type.labelLarge, maxLines = 1)
    }
}

/** A row title above a `LazyRow` (Home, Conversations sections). */
@Composable
fun RowHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val c = TvTheme.colors
    Row(modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = TvTheme.type.titleMedium, color = c.onSurface)
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Text(trailing, style = TvTheme.type.labelMedium, color = c.onSurfaceVariant)
        }
    }
}

/** The bottom key-hint bar (not focusable): 3–4 hints for the current context; emphasized ones sit at the end. */
@Composable
fun KeyHintBar(hints: List<KeyHint>, modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    val warn = ArchieTheme.extended.warning
    Row(
        modifier.fillMaxWidth().padding(top = 12.dp).testTag("key-hints"),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (h in hints.filterNot { it.emphasis }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyCap(h.glyph)
                Spacer(Modifier.width(8.dp))
                Text(h.label, style = TvTheme.type.labelLarge, color = c.onSurfaceVariant, maxLines = 1)
            }
        }
        val emphasized = hints.filter { it.emphasis }
        if (emphasized.isNotEmpty()) {
            Spacer(Modifier.weight(1f))
            for (h in emphasized) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("key-hint-emphasis")) {
                    KeyCap(h.glyph, container = warn.colorContainer, content = warn.onColorContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(h.label, style = TvTheme.type.labelLarge, color = warn.color, maxLines = 1)
                }
            }
        }
    }
}

/** The remote key a hint refers to, drawn as a small outlined cap ([container]: a filled cap). */
@Composable
fun KeyCap(
    glyph: HintGlyph,
    modifier: Modifier = Modifier,
    container: androidx.compose.ui.graphics.Color? = null,
    content: androidx.compose.ui.graphics.Color? = null,
) {
    val c = TvTheme.colors
    val shape = RoundedCornerShape(8.dp)
    val fg = content ?: c.onSurfaceVariant
    Box(
        modifier
            .heightIn(min = 28.dp)
            .widthIn(min = 28.dp)
            .then(if (container != null) Modifier.background(container, shape) else Modifier.border(1.dp, c.outline, shape))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        val icon = when (glyph) {
            HintGlyph.MIC -> ArchieIcons.Mic
            HintGlyph.PLAY_PAUSE -> ArchieIcons.PlayArrow
            HintGlyph.MENU -> ArchieIcons.Menu
            HintGlyph.BACK -> ArchieIcons.ArrowBack
            HintGlyph.DOWN -> ArchieIcons.KeyboardArrowDown
            HintGlyph.OK, HintGlyph.REWIND_FF -> null
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = fg)
        } else {
            Text(if (glyph == HintGlyph.OK) "OK" else "RW FF", style = TvTheme.type.labelMedium, color = fg)
        }
    }
}

/** Connection dot + label and the voice chip (spec 15 §3.4), top-right; not focusable. */
@Composable
fun StatusCluster(
    phase: ConnectionStatus.Phase,
    voiceLabel: String,
    voiceActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = TvTheme.colors
    val x = ArchieTheme.extended
    val (dot, label) = when (phase) {
        ConnectionStatus.Phase.CONNECTED -> x.success.color to "Connected"
        ConnectionStatus.Phase.CONNECTING -> x.warning.color to "Connecting"
        ConnectionStatus.Phase.RECONNECTING -> x.warning.color to "Reconnecting"
        ConnectionStatus.Phase.OFFLINE -> c.error to "Offline"
    }
    Row(
        modifier.semantics { contentDescription = "$label, voice $voiceLabel" }.testTag("status-cluster"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Text(label, style = TvTheme.type.labelLarge, color = c.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        val chipBg = if (voiceActive) c.primaryContainer else c.surfaceContainerHigh
        val chipFg = if (voiceActive) c.onPrimaryContainer else c.onSurfaceVariant
        Row(
            Modifier.background(chipBg, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ArchieIcons.Mic, contentDescription = null, modifier = Modifier.size(16.dp), tint = chipFg)
            Spacer(Modifier.width(6.dp))
            Text(voiceLabel, style = TvTheme.type.labelMedium, color = chipFg)
        }
    }
}

/** Top-center toast (spec 15 §3.4); dismisses itself after the toast's time. Never focusable. */
@Composable
fun TvToastHost(toasts: TvToasts, modifier: Modifier = Modifier) {
    val toast by toasts.current.collectAsStateWithLifecycle()
    val t = toast ?: return
    LaunchedEffect(t.id) {
        delay(t.seconds * 1_000L)
        toasts.dismiss(t.id)
    }
    val c = TvTheme.colors
    Box(
        modifier
            .padding(top = TvDimens.OverscanVertical)
            .widthIn(max = 640.dp)
            .background(if (t.error) c.errorContainer else c.inverseSurface, RoundedCornerShape(14.dp))
            .padding(horizontal = 22.dp, vertical = 12.dp)
            .testTag("toast"),
    ) {
        Text(
            t.text,
            style = TvTheme.type.bodyMedium,
            color = if (t.error) c.onErrorContainer else c.inverseOnSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** An empty or placeholder state: icon, title, body, optional action. */
@Composable
fun TvEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val c = TvTheme.colors
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = c.primary)
        Text(title, style = TvTheme.type.titleLarge, color = c.onSurface, textAlign = TextAlign.Center)
        Text(body, style = TvTheme.type.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 560.dp))
        if (action != null) {
            Spacer(Modifier.size(4.dp))
            action()
        }
    }
}

/** A small rounded label (status / provider / "Current"). */
@Composable
fun TvBadge(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    val c = TvTheme.colors
    Box(
        modifier
            .background(if (emphasized) c.primaryContainer else c.surfaceContainerHigh, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, style = TvTheme.type.labelMedium, color = if (emphasized) c.onPrimaryContainer else c.onSurfaceVariant, maxLines = 1)
    }
}

/** Secondary line inside a card, in the card's content color at reduced emphasis. */
@Composable
fun CardMeta(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(
        text,
        modifier = modifier,
        style = TvTheme.type.bodyMedium,
        color = LocalContentColor.current.copy(alpha = 0.72f),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Focus for the element a screen wants focused first (spec 15 §3.1 principle 3: initial focus is
 * requested after first layout). Attach with `Modifier.focusRequester(it)`; re-requests when [key] changes.
 */
@Composable
fun rememberInitialFocus(key: Any? = Unit): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(key) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    return requester
}

/** Width of a standard card in a Home row (four fit in the 772 dp content box with the drawer collapsed). */
val CardWidth: Dp = 236.dp
