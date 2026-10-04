package com.assistant.archie.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.assistant.core.conversation.AssistantEntry
import com.assistant.core.conversation.ConversationState
import com.assistant.core.conversation.NoticeEntry
import com.assistant.core.conversation.PermissionBlock
import com.assistant.core.conversation.TextBlock
import com.assistant.core.conversation.ThinkingBlock
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.UserEntry
import com.assistant.core.data.ItemKey
import com.assistant.core.design.components.ArchieTopAppBar
import com.assistant.core.design.components.ArchieIconButton
import com.assistant.core.design.components.ComposerPrimary
import com.assistant.core.design.components.ComposerShell
import com.assistant.core.design.components.ComposerTextField
import com.assistant.core.design.components.EmptyState
import com.assistant.core.design.components.SuggestionChip
import com.assistant.core.design.components.SystemLine
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.model.SessionKind

/*
 * ─────────────────────────────── PLACEHOLDERS ───────────────────────────────
 * Destinations owned by later work packages. Each is clearly marked with its owner; the WP that
 * builds the real screen replaces the body of the matching ShellDestinations method (GraphDestinations)
 * and deletes the placeholder here. Nothing in the shell depends on their internals.
 *
 *   ConversationPlaceholder   → replaced by B-04's ConversationScreen in GraphDestinations; kept only
 *                               because the shell test fixtures (ShellFixtures) render it
 *   HistoryPlaceholder        → B-06 :feature:sessions HistoryScreen
 *   MemoryPlaceholder         → B-07 :feature:memory MemoryTreePane / MemoryDocumentScreen
 *   VisualsPlaceholder        → B-07 :feature:visuals VisualsListPane / VisualScreen
 *   SettingsPlaceholder       → B-08 :feature:settings (IA §7 hierarchy)
 *   SessionSettingsPlaceholder→ B-08 (session settings sheet)
 */

/**
 * PLACEHOLDER (B-04): a minimal timeline + composer so the shell is usable end to end. User prompts
 * are tonal bubbles, assistant text is plain prose, tools/notices are one-line pills. No markdown,
 * no tool cards, no inline cards.
 */
@Composable
fun ConversationPlaceholder(
    state: ConversationState?,
    isArchie: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ArchieTheme.colors
    var draft by rememberSaveable { mutableStateOf("") }
    val entries = state?.entries.orEmpty()
    val busy = state != null && (state.inTurn || state.status.busy)
    Column(modifier.fillMaxSize().testTag("conversation-placeholder")) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (isArchie) {
                        EmptyState(
                            title = "Hello.\nWhat are we doing?",
                            body = "Talk or type. Archie can hand work to an agent.",
                            modifier = Modifier.widthIn(max = 560.dp),
                        ) {
                            SuggestionChip("Start an agent session", {}, icon = ArchieIcons.Terminal)
                            SuggestionChip("What do I know about voice?", {}, icon = ArchieIcons.Book2)
                        }
                    } else {
                        Text(
                            if (state?.history?.loaded == false) "Loading…" else "Send a message to start.",
                            style = ArchieTheme.typography.bodyMedium,
                            color = c.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                val list = rememberLazyListState()
                LaunchedEffect(entries.size) { list.scrollToItem(entries.size - 1) }
                LazyColumn(
                    Modifier.widthIn(max = 840.dp).fillMaxSize(),
                    state = list,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
                ) {
                    items(entries, key = { it.id }) { e ->
                        when (e) {
                            is UserEntry -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Text(
                                    e.text.ifBlank { "[voice message]" },
                                    Modifier
                                        .widthIn(max = 320.dp)
                                        .background(c.surfaceContainerHigh, RoundedCornerShape(20.dp))
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    style = ArchieTheme.typography.bodyLarge,
                                    color = c.onSurface,
                                )
                            }
                            is AssistantEntry -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (b in e.blocks) when (b) {
                                    is TextBlock -> if (b.text.isNotBlank()) Text(b.text, style = ArchieTheme.typography.bodyLarge, color = c.onSurface)
                                    is ThinkingBlock -> Unit
                                    is ToolBlock -> SystemLine(b.toolName, icon = ArchieIcons.Build)
                                    is PermissionBlock -> SystemLine("Permission: ${b.toolName} · ${b.state.wire}", icon = ArchieIcons.Shield)
                                }
                            }
                            is NoticeEntry -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                SystemLine(e.text.ifBlank { e.notice.wire })
                            }
                        }
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val primary = when {
                draft.isNotBlank() -> ComposerPrimary.Send
                busy -> ComposerPrimary.Stop
                isArchie -> ComposerPrimary.Voice
                else -> ComposerPrimary.SendDisabled
            }
            val send = {
                val t = draft.trim()
                if (t.isNotEmpty()) { onSend(t); draft = "" }
            }
            ComposerShell(
                primary = primary,
                onPrimary = { if (primary == ComposerPrimary.Stop) onStop() else if (primary == ComposerPrimary.Send) send() },
                modifier = Modifier.widthIn(max = 816.dp),
                leading = { ArchieIconButton(ArchieIcons.Add, "Attach, voice message or slash command", {}) },
                trailing = { ArchieIconButton(ArchieIcons.Mic, "Record voice message", {}) },
            ) {
                ComposerTextField(draft, { draft = it }, placeholder = if (isArchie) "Message Archie…" else "Message agent…", onSend = send)
            }
        }
    }
}

/** A generic "built in a later package" screen with a back arrow (Compact full screens). */
@Composable
fun PlaceholderScreen(title: String, owner: String, body: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = ArchieTheme.colors
    Column(modifier.fillMaxSize().background(c.surface).testTag("placeholder-$owner")) {
        ArchieTopAppBar(
            title,
            navigationIcon = onBack?.let { back -> { ArchieIconButton(ArchieIcons.ArrowBack, "Back", back) } },
        )
        PlaceholderBody(owner, body)
    }
}

@Composable
fun PlaceholderBody(owner: String, body: String, modifier: Modifier = Modifier) {
    val c = ArchieTheme.colors
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.padding(top = 24.dp))
        Text(body, style = ArchieTheme.typography.bodyLarge, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
        Text("Placeholder · $owner", style = ArchieTheme.typography.labelMedium, color = c.outline)
    }
}

/** Kind of a conversation item for the placeholder (Archie vs agent). */
internal fun ItemKey.isArchie(): Boolean = this == ItemKey.Archie

internal fun ConversationState?.isOrchestrator(): Boolean = this?.kind == SessionKind.ORCHESTRATOR
