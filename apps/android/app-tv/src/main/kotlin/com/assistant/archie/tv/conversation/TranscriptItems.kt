package com.assistant.archie.tv.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.assistant.archie.feature.chat.ChatAction
import com.assistant.archie.feature.chat.ConversationUiMapper
import com.assistant.archie.feature.chat.InlineCardUi
import com.assistant.archie.feature.chat.model.ChatItem
import com.assistant.archie.feature.chat.ui.ToolCardRenderer
import com.assistant.archie.feature.chat.ui.capOutput
import com.assistant.archie.feature.chat.ui.shell
import com.assistant.archie.tv.shell.LocalTvKeyActions
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvButtonStyle
import com.assistant.archie.tv.theme.TvDimens
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.core.conversation.NoticeKind
import com.assistant.core.conversation.PermissionState
import com.assistant.core.conversation.UserOrigin
import com.assistant.core.design.components.Spinner
import com.assistant.core.design.components.ToolIconTile
import com.assistant.core.design.components.ToolStatusIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.markdown.ui.Markdown
import com.assistant.core.markdown.ui.MarkdownBlock
import com.assistant.core.markdown.ui.MarkdownStyle
import com.assistant.core.model.SessionKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * One transcript item for the TV (spec 15 §3.5, mockup 6). Every block is one tv-material surface:
 * transparent at rest, `surfaceContainerHighest` + a 3 dp primary outline when focused (no scale:
 * the reading column must not jump). The phone's renderers draw inside (markdown blocks,
 * `:feature:toolcards` cards), read-only: the surface owns OK and long-press, so nothing inside is a
 * focus stop. Inline cards are not surfaces: their buttons are the focus stops.
 */

/** The TV markdown style: the TV type scale (via the M3 slots), code never folded (tall blocks scroll inside). */
@Composable
fun tvMarkdownStyle(): MarkdownStyle = MarkdownStyle.fromTheme().copy(codeMaxHeight = 100_000.dp, tableMaxColumnWidth = 360.dp)

@Composable
internal fun TranscriptItem(
    item: TItem,
    kind: SessionKind,
    toolCards: ToolCardRenderer,
    onAction: (ChatAction) -> Unit,
    onLongPress: (String) -> Unit,
    modifier: Modifier,
    readOnly: Boolean,
) {
    when (item) {
        is TItem.Card -> InlineCardItem(item.card, onAction, modifier.padding(top = 14.dp), readOnly)
        is TItem.Ended -> Block(onClick = {}, modifier = Modifier.padding(top = 14.dp).then(modifier)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(ArchieIcons.StopFilled, contentDescription = null, modifier = Modifier.size(18.dp), tint = TvTheme.colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(item.reason, style = TvTheme.type.labelLarge, color = TvTheme.colors.onSurfaceVariant)
            }
        }
        is TItem.Chat -> ChatItemView(item.item, kind, toolCards, onAction, onLongPress, modifier, readOnly)
    }
}

/**
 * The focus surface of a block. [longPress] = message actions (rewind / fork / copy), else the
 * shell's (the options sheet).
 */
@Composable
private fun Block(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    container: Color = Color.Transparent,
    focusedContainer: Color = TvTheme.colors.surfaceContainerHighest,
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    longPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = TvTheme.colors
    val keys = LocalTvKeyActions.current
    Surface(
        onClick = onClick,
        onLongClick = longPress ?: { keys.longPress() },
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = c.onSurface,
            focusedContainerColor = focusedContainer,
            focusedContentColor = c.onSurface,
            pressedContainerColor = focusedContainer,
            pressedContentColor = c.onSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(TvDimens.FocusBorder, c.primary), shape = shape)),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

private fun gapOf(item: ChatItem, style: MarkdownStyle): Dp = when (item.gap) {
    ChatItem.Gap.None -> 0.dp
    ChatItem.Gap.Tight -> if (item is ChatItem.MdBlock) style.spacingBefore(item.previous, item.node) else 2.dp
    ChatItem.Gap.Group -> 4.dp
    ChatItem.Gap.Item -> 14.dp
}

@Composable
private fun ChatItemView(
    item: ChatItem,
    kind: SessionKind,
    toolCards: ToolCardRenderer,
    onAction: (ChatAction) -> Unit,
    onLongPress: (String) -> Unit,
    modifier: Modifier,
    readOnly: Boolean,
) {
    val c = TvTheme.colors
    val style = tvMarkdownStyle()
    val gap = Modifier.padding(top = gapOf(item, style))
    val press: (() -> Unit)? = item.entryId?.takeIf { !readOnly }?.let { id -> { onLongPress(id) } }
    when (item) {
        is ChatItem.LoadOlder -> Block(onClick = { onAction(ChatAction.LoadOlder) }, modifier = gap.then(modifier).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (item.loading) Spinner(size = 18.dp) else Icon(ArchieIcons.History, contentDescription = null, modifier = Modifier.size(18.dp), tint = c.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(if (item.loading) "Loading older messages…" else "Load older messages", style = TvTheme.type.labelLarge, color = c.onSurfaceVariant)
            }
        }
        ChatItem.HistoryFootnote -> Block(onClick = {}, modifier = gap.then(modifier).fillMaxWidth()) {
            Text(
                "Background updates from agents aren’t kept in history.",
                style = TvTheme.type.labelMedium,
                color = c.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        is ChatItem.UserBubble -> Row(gap.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 7.dp, bottomStart = 22.dp)
            Block(
                onClick = {},
                modifier = modifier.widthIn(max = TranscriptMaxWidth * 0.78f),
                shape = shape,
                container = c.surfaceContainerHigh,
                padding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
                longPress = press,
            ) {
                if (item.origin == UserOrigin.VOICE || item.origin == UserOrigin.AUDIO) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(ArchieIcons.Mic, contentDescription = null, modifier = Modifier.size(15.dp), tint = c.onSurfaceVariant)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            when {
                                item.origin == UserOrigin.AUDIO -> "Voice message"
                                item.live -> "Voice · live"
                                else -> "Voice"
                            },
                            style = TvTheme.type.labelMedium,
                            color = c.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    item.text.ifEmpty { if (item.origin == UserOrigin.AUDIO) "Voice message" else "" },
                    style = TvTheme.type.bodyLarge,
                    color = if (item.live || item.pending) c.onSurfaceVariant else c.onSurface,
                )
                if (item.pending) Text("Sending…", style = TvTheme.type.labelMedium, color = c.onSurfaceVariant)
            }
        }
        is ChatItem.MdBlock -> Block(onClick = {}, modifier = gap.then(modifier).fillMaxWidth(), longPress = press) {
            MarkdownBlock(item.node, Modifier.fillMaxWidth(), style)
        }
        is ChatItem.Thinking -> {
            var open by rememberSaveable(item.key) { mutableStateOf(false) }
            Block(onClick = { open = !open }, modifier = gap.then(modifier).fillMaxWidth(), longPress = press) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.streaming) Spinner(size = 16.dp) else Icon(ArchieIcons.StarShine, contentDescription = null, modifier = Modifier.size(18.dp), tint = c.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(if (item.streaming) "Thinking…" else "Thought", style = TvTheme.type.labelLarge, color = c.onSurfaceVariant)
                    Icon(if (open) ArchieIcons.KeyboardArrowUp else ArchieIcons.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(20.dp), tint = c.onSurfaceVariant)
                }
                if (open) {
                    Text(
                        capOutput(item.text),
                        style = TvTheme.type.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = c.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    )
                }
            }
        }
        is ChatItem.ToolGroup -> Row(gap.fillMaxWidth()) {
            Block(
                onClick = { onAction(ChatAction.ToggleGroup(item.key, !item.expanded)) },
                modifier = modifier,
                shape = RoundedCornerShape(50),
                padding = PaddingValues(start = 8.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
                longPress = press,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (item.expanded) ArchieIcons.KeyboardArrowDown else ArchieIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(22.dp), tint = c.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy((-7).dp)) {
                        item.tiles.take(4).forEach { d ->
                            Box(Modifier.size(28.dp).background(c.surface, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                ToolIconTile(d.category, d.icon, size = 24.dp, iconSize = 15.dp, corner = 8.dp)
                            }
                        }
                    }
                    Text(if (item.count == 1) "1 step" else "${item.count} steps", style = TvTheme.type.labelLarge, color = c.onSurface, maxLines = 1)
                    Text(item.summary, style = TvTheme.type.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    ToolStatusIcon(item.status.shell())
                }
            }
        }
        is ChatItem.ToolCard -> Block(
            onClick = { onAction(ChatAction.ToggleCard(item.key, !item.expanded)) },
            modifier = gap.padding(start = if (item.position == ChatItem.GroupPosition.Solo) 0.dp else 22.dp).then(modifier).fillMaxWidth(),
            padding = PaddingValues(3.dp),
            longPress = press,
        ) {
            // Read-only: the surface toggles; the card's own header click is unreachable by focus.
            toolCards.Card(item, kind, {}, Modifier.fillMaxWidth())
        }
        is ChatItem.Permission -> Block(onClick = {}, modifier = gap.then(modifier).fillMaxWidth(), container = c.surfaceContainer, longPress = press) {
            val b = item.block
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (b.state == PermissionState.PENDING) ArchieIcons.FrontHand else ArchieIcons.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = when (b.state) {
                        PermissionState.PENDING -> ArchieTheme.extended.warning.color
                        PermissionState.ALLOWED -> ArchieTheme.extended.success.color
                        PermissionState.DENIED -> c.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(10.dp))
                Text(b.toolName, style = TvTheme.type.labelLarge, color = c.onSurface, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                Text(
                    when (b.state) {
                        PermissionState.PENDING -> "Waiting for approval"
                        PermissionState.ALLOWED -> "Approved" + responder(b.responder)
                        PermissionState.DENIED -> if (b.responder == "system") "Expired" else "Rejected" + responder(b.responder)
                    },
                    style = TvTheme.type.bodyMedium,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        is ChatItem.CompactDivider -> Block(onClick = {}, modifier = gap.then(modifier).fillMaxWidth()) {
            val tokens = if (item.tokensBefore != null && item.tokensAfter != null) " · ${item.tokensBefore} → ${item.tokensAfter} tokens" else ""
            SystemText("Context compacted$tokens", ArchieIcons.Compress)
        }
        is ChatItem.Notice -> Block(onClick = {}, modifier = gap.then(modifier).fillMaxWidth(), longPress = press) {
            when (item.kind) {
                NoticeKind.ERROR -> SystemText(item.text.ifBlank { "The reply failed" }, ArchieIcons.Error, c.error)
                NoticeKind.INTERRUPTED -> SystemText("Interrupted", ArchieIcons.Stop)
                NoticeKind.BACKGROUND -> SystemText(backgroundLine(item.text), ArchieIcons.Bolt)
                NoticeKind.COMMAND -> SystemText(item.text.lineSequence().firstOrNull().orEmpty().take(120), ArchieIcons.Terminal)
                NoticeKind.COMPACTION -> SystemText("Context compacted", ArchieIcons.Compress)
            }
        }
        is ChatItem.UnmatchedResults -> {
            var open by rememberSaveable(item.key) { mutableStateOf(false) }
            Block(onClick = { open = !open }, modifier = gap.then(modifier).fillMaxWidth(), container = c.surfaceContainer) {
                SystemText("Tool output not matched to a call (${item.results.size})", ArchieIcons.Info)
                if (open) {
                    item.results.forEach { r ->
                        Text(capOutput(r.output).ifEmpty { "No output" }, style = TvTheme.type.code, color = if (r.isError) c.error else c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

private fun responder(r: String?) = when (r) {
    "user" -> " by you"
    "orchestrator" -> " by Archie"
    else -> ""
}

private val TASK_SUMMARY = Regex("<summary>([\\s\\S]*?)</summary>")

/** A CLI `<task-notification>`: its summary line (as the apps show it). */
private fun backgroundLine(text: String): String {
    if (!text.startsWith("<task-notification>")) return text.ifBlank { "Archie started on its own" }.lineSequence().first()
    val s = TASK_SUMMARY.find(text)?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: "A background task reported back."
    return if (Regex("^(Background\\s+)?agent\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) "Background agent · $s" else "Background task · $s"
}

@Composable
private fun SystemText(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color = TvTheme.colors.onSurfaceVariant) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Text(text, style = TvTheme.type.labelLarge, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ───────────────────────── inline cards ─────────────────────────

/**
 * Permission, agent approval, stall, error and gap cards with real buttons (spec 15 §3.5). They
 * never take focus on arrival (FOCUS-1); the hint bar says "▼ Approval needed" instead. In a
 * read-only transcript (a subagent's) they show no buttons.
 */
@Composable
private fun InlineCardItem(card: InlineCardUi, onAction: (ChatAction) -> Unit, modifier: Modifier, readOnly: Boolean) {
    val c = TvTheme.colors
    val x = ArchieTheme.extended
    when (card) {
        is InlineCardUi.Permission -> {
            val b = card.block
            val plan = (b.toolInput["plan"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ActionCard(
                container = c.primaryContainer,
                content = c.onPrimaryContainer,
                icon = ArchieIcons.FrontHand,
                title = if (plan != null) "Plan ready: exit plan mode?" else "${b.toolName} wants to run",
                body = if (plan == null) inputSummary(b.toolName, b.toolInput) else null,
                plan = plan,
                planId = "perm-plan:${b.requestId}",
                modifier = modifier.testTag("card-permission"),
            ) {
                if (readOnly) return@ActionCard
                if (card.answered) {
                    Text("Answer sent…", style = TvTheme.type.labelLarge)
                } else {
                    TvButton("Reject", { onAction(ChatAction.Permission(b.requestId, allow = false)) }, Modifier.testTag("perm-reject"), icon = ArchieIcons.Close)
                    TvButton("Approve", { onAction(ChatAction.Permission(b.requestId, allow = true)) }, Modifier.testTag("perm-approve"), icon = ArchieIcons.Check)
                }
            }
        }
        is InlineCardUi.AgentApprovalCard -> {
            val a = card.approval
            val plan = (a.toolInput["plan"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ActionCard(
                container = c.primaryContainer,
                content = c.onPrimaryContainer,
                icon = ArchieIcons.FrontHand,
                title = if (plan != null) "An agent finished planning" else ConversationUiMapper.agentApprovalTitle(a),
                body = if (plan != null) "It asks to exit plan mode:" else "Agent session ${a.localId.take(8)} · " + inputSummary(a.toolName, a.toolInput),
                plan = plan,
                planId = "agent-plan:${a.localId}:${a.requestId}",
                modifier = modifier.testTag("card-agent-approval"),
            ) {
                if (readOnly) return@ActionCard
                if (card.answered) {
                    Text("Answer sent…", style = TvTheme.type.labelLarge)
                } else {
                    TvButton("Reject", { onAction(ChatAction.AgentApproval(a.localId, a.requestId, false)) }, icon = ArchieIcons.Close)
                    TvButton("Approve", { onAction(ChatAction.AgentApproval(a.localId, a.requestId, true)) }, Modifier.testTag("agent-approve"), icon = ArchieIcons.Check)
                }
            }
        }
        is InlineCardUi.Stall -> ActionCard(
            container = x.warning.colorContainer,
            content = x.warning.onColorContainer,
            icon = ArchieIcons.HourglassTop,
            title = ConversationUiMapper.stallTitle(card.toolName, card.elapsedSeconds),
            body = ConversationUiMapper.stallBody(card.toolName, card.elapsedSeconds),
            modifier = modifier.testTag("card-stall"),
        ) {
            if (readOnly) return@ActionCard
            TvButton("Keep waiting", { onAction(ChatAction.DismissCard("stall")) })
            TvButton("Interrupt", { onAction(ChatAction.Stop) }, icon = ArchieIcons.StopFilled, style = TvButtonStyle.Primary)
        }
        is InlineCardUi.Error -> ActionCard(
            container = c.errorContainer,
            content = c.onErrorContainer,
            icon = ArchieIcons.Error,
            title = card.title,
            body = card.body,
            modifier = modifier.testTag("card-error"),
        ) {
            if (readOnly) return@ActionCard
            TvButton("Dismiss", { onAction(ChatAction.DismissCard(card.id)) })
            card.retry?.let { k -> TvButton("Retry", { onAction(ChatAction.Retry(k, card.id)) }, icon = ArchieIcons.Refresh, style = TvButtonStyle.Primary) }
        }
        InlineCardUi.GapPossible -> ActionCard(
            container = c.surfaceContainerHigh,
            content = c.onSurface,
            icon = ArchieIcons.Sync,
            title = "Some messages may be missing",
            body = "The live stream skipped ahead. Reload to fetch the full conversation.",
            modifier = modifier.testTag("card-gap"),
        ) {
            if (readOnly) return@ActionCard
            TvButton("Dismiss", { onAction(ChatAction.DismissCard("gap")) })
            TvButton("Reload", { onAction(ChatAction.Reload) }, icon = ArchieIcons.Refresh, style = TvButtonStyle.Primary)
        }
    }
}

/** Mockup `.pc`: icon, title + body (and a plan, capped), buttons at the end. */
@Composable
private fun ActionCard(
    container: Color,
    content: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String?,
    modifier: Modifier,
    plan: String? = null,
    planId: String = "",
    buttons: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(16.dp))
            .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = TvTheme.type.labelLarge.copy(fontWeight = FontWeight.W600), color = content)
                if (body != null) Text(body, style = TvTheme.type.bodyMedium, color = content, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides content) { buttons() }
            }
        }
        if (plan != null) {
            val style = MarkdownStyle.fromTheme().copy(
                body = TvTheme.type.bodyMedium,
                textColor = content,
                mutedColor = content,
                blockSpacing = 4.dp,
                itemSpacing = 0.dp,
                codeMaxHeight = 160.dp,
            )
            Box(Modifier.padding(start = 38.dp, top = 8.dp).heightIn(max = 200.dp).clipToBounds()) {
                Markdown(plan, style = style, cacheId = planId, syncLimit = 2_000)
            }
        }
    }
}

/** One readable line of a tool input (as the apps' permission card). */
internal fun inputSummary(toolName: String, input: JsonObject): String {
    val first = input.entries.firstOrNull { (_, v) -> v is JsonPrimitive && v.isString }
    val v = first?.let { (it.value as JsonPrimitive).content.lineSequence().firstOrNull() }
    return when {
        v == null -> toolName
        first.key.contains("path") -> v.trimEnd('/').split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")
        else -> v.take(160)
    }
}
