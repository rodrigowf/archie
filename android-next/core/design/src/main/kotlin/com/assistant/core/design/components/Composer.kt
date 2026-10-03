package com.assistant.core.design.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.core.design.Motion
import com.assistant.core.design.StateLayer
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

/**
 * What the composer's primary button does (IA §6): Voice (Archie, empty field) → Send (text) →
 * Stop (working and empty). [SendDisabled] is an agent session with an empty field.
 */
enum class ComposerPrimary { Voice, Send, SendDisabled, Stop }

/**
 * The morphing primary button (mockup `.pa`): 48 dp; a circle on primary for Voice, a 16 dp
 * squircle for Send, secondary-container for Stop. Shape, colors and icon animate between modes.
 */
@Composable
fun ComposerPrimaryButton(mode: ComposerPrimary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = ArchieTheme.colors
    val spec = if (ArchieTheme.reduceMotion) tween<Float>(0) else tween(Motion.DurationShort4, easing = Motion.EasingStandard)
    val radius by animateDpAsState(
        if (mode == ComposerPrimary.Voice) 24.dp else 16.dp,
        if (ArchieTheme.reduceMotion) tween(0) else tween(Motion.DurationShort4, easing = Motion.EasingStandard),
        label = "pa-radius",
    )
    val (bgTarget, fgTarget) = when (mode) {
        ComposerPrimary.Voice, ComposerPrimary.Send -> c.primary to c.onPrimary
        ComposerPrimary.Stop -> c.secondaryContainer to c.onSecondaryContainer
        ComposerPrimary.SendDisabled ->
            c.onSurface.copy(alpha = StateLayer.DisabledContainer) to c.onSurface.copy(alpha = StateLayer.DisabledContent)
    }
    val colorSpec = if (ArchieTheme.reduceMotion) tween<Color>(0) else tween(Motion.DurationShort4)
    val bg by animateColorAsState(bgTarget, colorSpec, label = "pa-bg")
    val fg by animateColorAsState(fgTarget, colorSpec, label = "pa-fg")
    val label = when (mode) {
        ComposerPrimary.Voice -> "Start voice"
        ComposerPrimary.Send, ComposerPrimary.SendDisabled -> "Send"
        ComposerPrimary.Stop -> "Stop"
    }
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .size(48.dp)
            .clip(shape)
            .background(bg)
            .clickable(
                enabled = mode != ComposerPrimary.SendDisabled,
                role = Role.Button,
                interactionSource = null,
                indication = ripple(color = fg),
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val icon = when (mode) {
            ComposerPrimary.Voice -> ArchieIcons.GraphicEq
            ComposerPrimary.Send, ComposerPrimary.SendDisabled -> ArchieIcons.ArrowUpward
            ComposerPrimary.Stop -> ArchieIcons.StopFilled
        }
        AnimatedContent(
            targetState = icon,
            transitionSpec = { (fadeIn(spec) + scaleIn(spec, initialScale = 0.8f)) togetherWith fadeOut(spec) },
            label = "pa-icon",
        ) { icon ->
            ArchieIcon(icon, null, tint = fg)
        }
    }
}

/**
 * Composer container (IA §6, mockup `.cmp`): one rounded surface-container-high shell, r30,
 * 6 dp padding, a 2 dp primary ring while [focused]. [leading] holds ＋, [field] the text field
 * (use [ComposerTextField]), [trailing] the context ring and the 🎙 voice-message button, then the
 * morphing primary button.
 */
@Composable
fun ComposerShell(
    primary: ComposerPrimary,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
    field: @Composable RowScope.() -> Unit,
) {
    val c = ArchieTheme.colors
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(shape)
            .background(c.surfaceContainerHigh)
            .then(if (focused) Modifier.border(2.dp, c.primary, shape) else Modifier)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        field()
        trailing()
        ComposerPrimaryButton(primary, onPrimary)
    }
}

/** The composer's multiline field: 16 sp, no chrome, placeholder in on-surface-variant, grows to 6 lines. */
@Composable
fun RowScope.ComposerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSend: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val c = ArchieTheme.colors
    val style = ArchieTheme.typography.bodyLarge.copy(letterSpacing = 0.2.sp, color = c.onSurface)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.weight(1f).heightIn(min = 48.dp).padding(horizontal = 6.dp),
        enabled = enabled,
        textStyle = style,
        cursorBrush = SolidColor(c.primary),
        maxLines = 6,
        keyboardOptions = KeyboardOptions(imeAction = if (onSend != null) ImeAction.Send else ImeAction.Default),
        keyboardActions = KeyboardActions(onSend = { onSend?.invoke() }),
        decorationBox = { inner ->
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = style, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        },
    )
}

/**
 * Context-usage ring (mockup `.ring`): a 20 dp determinate ring (outline-variant track, primary
 * value) and the percentage. Tap → Compact context.
 */
@Composable
fun ContextRing(fraction: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = ArchieTheme.colors
    val pct = (fraction.coerceIn(0f, 1f) * 100).toInt()
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Context $pct% used" }
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = 3.dp.toPx()
            val inset = w / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(c.outlineVariant, 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
            drawArc(c.primary, -90f, 360f * fraction.coerceIn(0f, 1f), false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        Text("$pct%", style = ArchieTheme.typography.labelMedium.copy(letterSpacing = 0.sp, fontFeatureSettings = "tnum"), color = c.onSurfaceVariant)
    }
}
