package com.assistant.core.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.core.design.Elevation
import com.assistant.core.design.ShapeFull
import com.assistant.core.design.StateLayer
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.theme.ArchieTheme

/** Button styles of the component sheet (mockup `.btn.*`). [Warning] and [Danger] are the inline-card actions. */
enum class ButtonStyle { Filled, Tonal, Outlined, Text, Elevated, Warning, Danger }

/** 32 dp (sm), 40 dp (default), 56 dp (lg, "Talk to Archie"). */
enum class ButtonSize(internal val height: Dp, internal val padding: Dp, internal val iconPadding: Dp) {
    Small(32.dp, 14.dp, 14.dp),
    Medium(40.dp, 24.dp, 16.dp),
    Large(56.dp, 28.dp, 28.dp),
}

/** Resolved colors of one button; any field can be overridden (inline cards do). */
data class ArchieButtonColors(
    val container: Color,
    val content: Color,
    val border: Color = Color.Transparent,
)

object ArchieButtonDefaults {
    @Composable
    fun colors(style: ButtonStyle): ArchieButtonColors {
        val c = ArchieTheme.colors
        val x = ArchieTheme.extended
        return when (style) {
            ButtonStyle.Filled -> ArchieButtonColors(c.primary, c.onPrimary)
            ButtonStyle.Tonal -> ArchieButtonColors(c.secondaryContainer, c.onSecondaryContainer)
            ButtonStyle.Outlined -> ArchieButtonColors(Color.Transparent, c.onSurfaceVariant, c.outlineVariant)
            ButtonStyle.Text -> ArchieButtonColors(Color.Transparent, c.primary)
            ButtonStyle.Elevated -> ArchieButtonColors(c.surfaceContainerLow, c.primary)
            ButtonStyle.Warning -> ArchieButtonColors(x.warning.color, x.warning.onColor)
            ButtonStyle.Danger -> ArchieButtonColors(c.error, c.onError)
        }
    }
}

/**
 * The M3 common button, 40 dp and fully rounded, with an optional 18 dp leading icon. Disabled
 * buttons use on-surface at 12% / 38% (text buttons keep no container).
 */
@Composable
fun ArchieButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Filled,
    size: ButtonSize = ButtonSize.Medium,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    colors: ArchieButtonColors = ArchieButtonDefaults.colors(style),
) {
    val c = ArchieTheme.colors
    val isText = style == ButtonStyle.Text
    val horizontal = when {
        isText -> 12.dp
        icon != null -> size.iconPadding
        else -> size.padding
    }
    val end = if (isText) (if (icon != null) 16.dp else 12.dp) else size.padding
    val disabledContainer = if (isText || style == ButtonStyle.Outlined) Color.Transparent
    else c.onSurface.copy(alpha = StateLayer.DisabledContainer)
    Button(
        onClick = onClick,
        modifier = modifier.height(size.height).heightIn(min = size.height),
        enabled = enabled,
        shape = ShapeFull,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.container,
            contentColor = colors.content,
            disabledContainerColor = disabledContainer,
            disabledContentColor = c.onSurface.copy(alpha = StateLayer.DisabledContent),
        ),
        elevation = if (style == ButtonStyle.Elevated && enabled) {
            ButtonDefaults.buttonElevation(defaultElevation = Elevation.Level1, pressedElevation = Elevation.Level1)
        } else {
            ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp)
        },
        border = if (colors.border != Color.Transparent && enabled) BorderStroke(1.dp, colors.border) else null,
        contentPadding = PaddingValues(start = horizontal, end = end),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) ArchieIcon(icon, null, size = 18.dp)
            val label = ArchieTheme.typography.labelLarge
            Text(
                text,
                style = if (size == ButtonSize.Large) label.copy(fontSize = 16.sp, lineHeight = 24.sp) else label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
