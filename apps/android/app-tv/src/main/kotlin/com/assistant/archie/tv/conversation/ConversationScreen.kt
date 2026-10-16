package com.assistant.archie.tv.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.assistant.archie.feature.sessions.SessionTitles
import com.assistant.archie.tv.shell.Conversation
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvEmptyState
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.theme.rememberInitialFocus
import com.assistant.core.data.ItemKind
import com.assistant.core.design.icons.ArchieIcons

/*
 * Conversation (spec 15 §3.5) — T0 placeholder. T2 replaces the body: :feature:chat's
 * ConversationViewModel per key (the shell already installs the ViewModelStore entry decorator),
 * the block-by-block reading focus model, cards, composer + IME, permissions, subagents. It is a
 * full-screen route: no drawer, its own header.
 */

@Composable
fun ConversationRoute(key: Conversation, ctx: TvRouteContext) {
    val g = ctx.graph
    val items = g.openSessions.items.collectAsStateWithLifecycle().value
    g.history.sessions.collectAsStateWithLifecycle()
    val title = if (key.archie && key.sdkId == null) {
        items.firstOrNull { it.kind == ItemKind.ARCHIE }?.title?.let { SessionTitles.conversationTitle(it, isArchie = true) } ?: "Archie"
    } else {
        SessionTitles.conversationTitle(g.history.titleFor(key.sdkId, key.localId, ""), key.archie)
    }
    ConversationScreen(title, kind = if (key.archie) "Archie" else "Agent session", onBack = { ctx.nav.pop() })
}

@Composable
fun ConversationScreen(title: String, kind: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    ScreenChromeEffect(ScreenChrome(title = title, hints = listOf(KeyHints.Mic, KeyHints.LiveVoice, KeyHints.Menu, KeyHints.Back), fullScreen = true, showHeader = false))
    val c = TvTheme.colors
    val back = rememberInitialFocus()
    Column(modifier.fillMaxSize().testTag("conversation"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(ArchieIcons.Forum, contentDescription = null, modifier = Modifier.size(28.dp), tint = c.primary)
            Spacer(Modifier.width(14.dp))
            Text("$kind · ", style = TvTheme.type.titleLarge, color = c.onSurfaceVariant)
            Text(title, style = TvTheme.type.titleLarge, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TvEmptyState(
            "The conversation view is on its way",
            "Reading, replying, approvals and voice arrive with the conversation screen.",
            icon = ArchieIcons.Forum,
        ) {
            TvButton("Back", onBack, Modifier.focusRequester(back), icon = ArchieIcons.ArrowBack)
        }
    }
}
