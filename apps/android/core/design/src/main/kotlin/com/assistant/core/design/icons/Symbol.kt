package com.assistant.core.design.icons

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

/** Material Symbols use a 960-unit viewBox at (0, -960); the group moves it into the viewport. */
internal fun symbol(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    ).addGroup(translationY = 960f)
        .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        .clearGroup()
        .build()

/** An icon at the Material Symbols optical sizes the mockups use (16, 18, 20, 24, 36, 48 dp). */
@Composable
fun ArchieIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
) {
    Icon(icon, contentDescription, modifier.size(size), tint)
}
