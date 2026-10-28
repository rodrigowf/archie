package com.assistant.core.design.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assistant.core.design.R

/**
 * The ChatGPT mark, for labelling a conversation imported from a ChatGPT data export (read-only).
 * The same glyph the web app shows, from `res/drawable/ic_chatgpt_mark.xml`.
 *
 * Tinted with [tint] (the local content colour by default), so it sits with the Material glyphs
 * around it in both themes rather than as a foreign brand block. It marks *imported history* —
 * it is never Archie's own branding.
 */
@Composable
fun ChatGptMark(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(R.drawable.ic_chatgpt_mark),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}
