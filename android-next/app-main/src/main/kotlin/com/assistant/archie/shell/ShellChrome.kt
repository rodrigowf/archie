package com.assistant.archie.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.data.HistoryRow
import com.assistant.core.data.ItemKind
import com.assistant.core.data.TabStatus
import com.assistant.core.data.WorkspaceItem
import com.assistant.core.design.components.ArchieMark
import com.assistant.core.design.components.DotTone
import com.assistant.core.design.components.ListLeadingIcon
import com.assistant.core.design.components.LiveStatus
import com.assistant.core.design.components.StatusDot
import com.assistant.core.design.components.TabLead
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.model.HarnessProvider
import com.assistant.core.model.ThemeMode
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.design.theme.ThemeMode as DesignThemeMode

/* Small mappings and pieces shared by the Compact and wide shells. */

internal fun TabStatus.toLive(): LiveStatus? = when (this) {
    TabStatus.IDLE -> LiveStatus.Idle
    TabStatus.WORKING, TabStatus.CONNECTING -> LiveStatus.Working
    TabStatus.NEEDS_YOU -> LiveStatus.NeedsYou
    TabStatus.DISCONNECTED -> LiveStatus.Disconnected
    TabStatus.STOPPED -> LiveStatus.Off
    TabStatus.NONE -> null
}

/** IA §1: provider shown as a labeled chip ("Claude", "Qwen", "Gemini"). */
internal val HarnessProvider.label: String
    get() = when (this) {
        HarnessProvider.CLAUDE -> "Claude"
        HarnessProvider.QWEN -> "Qwen"
        HarnessProvider.GEMINI -> "Gemini"
    }

internal fun ThemeMode.toDesign(): DesignThemeMode = when (this) {
    ThemeMode.SYSTEM -> DesignThemeMode.System
    ThemeMode.LIGHT -> DesignThemeMode.Light
    ThemeMode.DARK -> DesignThemeMode.Dark
}

internal fun ItemKind.icon(): ImageVector = when (this) {
    ItemKind.ARCHIE -> ArchieIcons.Forum
    ItemKind.AGENT -> ArchieIcons.Terminal
    ItemKind.MEMORY -> ArchieIcons.Description
    ItemKind.VISUAL -> ArchieIcons.BarChart
}

internal fun WorkspaceItem.tabLead(): TabLead =
    if (kind == ItemKind.ARCHIE) TabLead.Archie else TabLead.Icon(kind.icon())

/** 24 dp leading slot: the Archie mark or the item's type icon. */
@Composable
internal fun ItemLeading(kind: ItemKind, markSize: androidx.compose.ui.unit.Dp = 24.dp) {
    if (kind == ItemKind.ARCHIE) ArchieMark(size = markSize) else ListLeadingIcon(kind.icon())
}

@Composable
internal fun HistoryLeading(row: HistoryRow) {
    if (row.summary.isOrchestrator) {
        Box(Modifier.padding(2.dp)) { ArchieMark(size = 20.dp) }
    } else {
        ListLeadingIcon(ArchieIcons.Terminal)
    }
}

/** "Connected to jetson" / "Reconnecting…" / "Offline" with its dot (drawer header, server group). */
@Composable
internal fun ConnectionLine(status: ConnectionStatus) {
    val (tone, text) = when (status.phase) {
        ConnectionStatus.Phase.CONNECTED -> DotTone.Success to "Connected to ${status.serverLabel}"
        ConnectionStatus.Phase.CONNECTING -> DotTone.Warning to "Connecting to ${status.serverLabel}…"
        ConnectionStatus.Phase.RECONNECTING -> DotTone.Warning to "Reconnecting to ${status.serverLabel}…"
        ConnectionStatus.Phase.OFFLINE -> DotTone.Off to if (status.scanning) "Looking for Archie…" else "Offline"
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(tone = tone)
        Text(text, style = ArchieTheme.typography.labelMedium.copy(letterSpacing = 0.sp), color = ArchieTheme.colors.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * The search pill as an editable field (mockup `.search`, 52 dp, surface-container-high): filters
 * the history below it as you type.
 */
@Composable
internal fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = ArchieTheme.colors
    val style = ArchieTheme.typography.bodyLarge.copy(letterSpacing = 0.sp, color = c.onSurface)
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(c.surfaceContainerHigh, RoundedCornerShape(26.dp))
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArchieIcon(ArchieIcons.Search, null, tint = c.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).semantics { contentDescription = placeholder },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(c.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = style, color = c.onSurfaceVariant, maxLines = 1)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            com.assistant.core.design.components.ArchieIconButton(ArchieIcons.Close, "Clear search", { onValueChange("") }, size = 40.dp)
        }
    }
}

/** The live subtitle of the Compact top bar and the wide status text. */
internal data class Subtitle(val text: String, val icon: ImageVector?, val tone: SubtitleTone)

internal enum class SubtitleTone { Normal, Warning, Primary }

internal fun subtitleFor(state: ShellUiState): Subtitle? {
    val item = state.activeItem
    if (item?.kind == ItemKind.ARCHIE && state.voiceActive) {
        val v = state.voice
        val word = when {
            v.reconnectBanner != null -> return Subtitle("Voice · Reconnecting…", ArchieIcons.Sync, SubtitleTone.Warning)
            v.phase == SessionPhase.CONNECTING || v.phase == SessionPhase.SUMMARIZING -> "Connecting"
            v.phase == SessionPhase.SPEAKING -> "Speaking"
            v.phase == SessionPhase.THINKING -> "Thinking"
            v.phase == SessionPhase.TOOL_USE -> "Using tools"
            v.phase == SessionPhase.ENDING -> "Ending"
            else -> "Listening"
        }
        return Subtitle("Voice · $word", ArchieIcons.GraphicEq, SubtitleTone.Primary)
    }
    if (item == null) {
        return when (state.connection.phase) {
            ConnectionStatus.Phase.CONNECTED -> null
            ConnectionStatus.Phase.OFFLINE -> Subtitle("Offline", ArchieIcons.CloudOff, SubtitleTone.Warning)
            else -> Subtitle("Connecting…", null, SubtitleTone.Normal)
        }
    }
    return when (item.status) {
        TabStatus.NEEDS_YOU -> Subtitle("Waiting for your approval", ArchieIcons.FrontHand, SubtitleTone.Warning)
        TabStatus.DISCONNECTED -> Subtitle("Reconnecting…", ArchieIcons.Sync, SubtitleTone.Warning)
        else -> Subtitle(item.detail.removePrefix("Archie · ").replaceFirstChar { it.uppercase() }, null, SubtitleTone.Normal)
    }
}
