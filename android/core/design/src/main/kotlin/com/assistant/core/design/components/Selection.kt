package com.assistant.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.assistant.core.design.StateLayer
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

/**
 * M3 switch (52 × 32; 16 dp thumb off, 24 dp on). Disabled draws the enabled colors at 38%
 * opacity, as in the mockup.
 */
@Composable
fun ArchieSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = ArchieTheme.colors
    val colors = SwitchDefaults.colors(
        checkedThumbColor = c.onPrimary,
        checkedTrackColor = c.primary,
        checkedBorderColor = c.primary,
        uncheckedThumbColor = c.outline,
        uncheckedTrackColor = c.surfaceContainerHighest,
        uncheckedBorderColor = c.outline,
    )
    val stillColors = colors.copy(
        disabledCheckedThumbColor = c.onPrimary,
        disabledCheckedTrackColor = c.primary,
        disabledCheckedBorderColor = c.primary,
        disabledUncheckedThumbColor = c.outline,
        disabledUncheckedTrackColor = c.surfaceContainerHighest,
        disabledUncheckedBorderColor = c.outline,
    )
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = if (enabled) modifier else modifier.alpha(StateLayer.DisabledContent),
        enabled = enabled,
        colors = stillColors,
    )
}

/**
 * The M3 (2024) slider: 16 dp thick track, 4 × 44 dp bar handle, stop indicator, and a value
 * bubble while dragging (mockup `.slider`). The value moves with [onValueChange]; settings commit
 * in [onValueChangeFinished] (IA §7: sliders commit on release, never one PUT per tick).
 * [showValueLabel] pins the bubble (screenshots, accessibility previews).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LevelSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    valueLabel: (Float) -> String = { "%.2f".format(it) },
    showValueLabel: Boolean = false,
    enabled: Boolean = true,
) {
    val c = ArchieTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val colors = SliderDefaults.colors(
        thumbColor = c.primary,
        activeTrackColor = c.primary,
        inactiveTrackColor = c.secondaryContainer,
        activeTickColor = c.onPrimary,
        inactiveTickColor = c.primary,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interaction,
        thumb = {
            Box(contentAlignment = Alignment.Center) {
                SliderDefaults.Thumb(
                    interactionSource = interaction,
                    colors = colors,
                    enabled = enabled,
                    thumbSize = DpSize(4.dp, 44.dp),
                )
                if (showValueLabel || dragged || pressed) {
                    ValueBubble(valueLabel(value))
                }
            }
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                enabled = enabled,
                thumbTrackGapSize = 4.dp,
                trackInsideCornerSize = 2.dp,
            )
        },
    )
}

/** 48 × 36 inverse-surface bubble, 6 dp above the 44 dp handle; drawn outside the slider's bounds. */
@Composable
private fun ValueBubble(text: String) {
    val c = ArchieTheme.colors
    Box(
        Modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                // Zero-size in layout so the slider's height does not grow; placed above the handle.
                layout(0, 0) { p.place(IntOffset(-p.width / 2, -(22.dp.roundToPx() + 6.dp.roundToPx() + p.height))) }
            }
            .requiredSize(48.dp, 36.dp)
            .background(c.inverseSurface, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = ArchieTheme.typography.labelLarge, color = c.inverseOnSurface)
    }
}

/** One option of a [SegmentedChoice]. */
data class SegmentOption(val label: String, val icon: ImageVector? = null)

/**
 * Single-choice segmented button (mockup `.seg`): 40 dp, full radius, outline border; the
 * selected segment is secondary-container and shows a check in place of its icon.
 */
@Composable
fun SegmentedChoice(
    options: List<SegmentOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ArchieTheme.colors
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            val active = i == selectedIndex
            SegmentedButton(
                selected = active,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = c.secondaryContainer,
                    activeContentColor = c.onSecondaryContainer,
                    activeBorderColor = c.outline,
                    inactiveContainerColor = c.surface.copy(alpha = 0f),
                    inactiveContentColor = c.onSurface,
                    inactiveBorderColor = c.outline,
                ),
                icon = {
                    when {
                        active -> ArchieIcon(ArchieIcons.Check, null, size = 18.dp)
                        option.icon != null -> ArchieIcon(option.icon, null, size = 18.dp)
                    }
                },
                label = { Text(option.label, style = ArchieTheme.typography.labelLarge, maxLines = 1) },
            )
        }
    }
}

/** Space reserved above a [LevelSlider] whose bubble is pinned, so it does not overlap the row above. */
val SliderBubbleHeadroom = 40.dp
