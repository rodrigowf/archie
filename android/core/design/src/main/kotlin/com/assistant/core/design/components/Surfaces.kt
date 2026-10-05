package com.assistant.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.core.design.Corner
import com.assistant.core.design.Elevation
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

/**
 * Code block surface (mockup `.doc pre`): surface-container, r12, mono 12.5/19 on-surface-variant.
 * :core:markdown puts highlighted text inside.
 */
@Composable
fun CodeSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = ArchieTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.surfaceContainer, RoundedCornerShape(Corner.Medium))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides c.onSurfaceVariant) {
            ProvideTextStyle(ArchieTheme.text.codeSmall) { content() }
        }
    }
}

/**
 * Empty state of a new Archie conversation (IA §6, mockup `.empty`): mark, greeting, one line,
 * the big voice button with its label, then suggestion chips. Never a blank screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    onVoice: (() -> Unit)? = null,
    voiceLabel: String = "Tap to talk",
    suggestions: (@Composable FlowRowScope.() -> Unit)? = null,
) {
    val c = ArchieTheme.colors
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ArchieMark(size = 72.dp)
            Text(
                title,
                Modifier.padding(top = 6.dp),
                style = ArchieTheme.typography.headlineMedium.copy(fontSize = 30.sp, lineHeight = 38.sp),
                color = c.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(body, style = ArchieTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = c.onSurfaceVariant, textAlign = TextAlign.Center)
            if (onVoice != null) {
                Surface(
                    onClick = onVoice,
                    modifier = Modifier.padding(top = 18.dp).size(112.dp).semantics { contentDescription = "Start voice conversation" },
                    shape = RoundedCornerShape(40.dp),
                    color = c.primary,
                    contentColor = c.onPrimary,
                    shadowElevation = Elevation.Level2,
                ) {
                    Box(contentAlignment = Alignment.Center) { ArchieIcon(ArchieIcons.GraphicEq, null, size = 48.dp) }
                }
                Text(voiceLabel, style = ArchieTheme.typography.labelMedium, color = c.onSurfaceVariant)
            }
        }
        if (suggestions != null) {
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = suggestions,
            )
        }
    }
}
