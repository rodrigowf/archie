package com.assistant.archie.feature.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.assistant.archie.feature.chat.model.ChatItem
import com.assistant.archie.feature.chat.model.ToolDescriptor
import com.assistant.archie.feature.toolcards.DefaultOpen
import com.assistant.archie.feature.toolcards.ToolCatalog
import com.assistant.archie.feature.toolcards.ui.ToolCard
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.design.components.ToolCardPlacement
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.model.SessionKind

/**
 * B-05's full tool cards (`:feature:toolcards`) in the [ToolCardRenderer] slot: the catalog's
 * category, icon, label and summary (web W-10 parity) for the list and group headers, and the
 * per-tool renderers (diffs, checklists, code views, output region) for the cards.
 */
object CatalogToolCardRenderer : ToolCardRenderer {
    override fun describe(block: ToolBlock, kind: SessionKind): ToolDescriptor {
        val t = ToolCatalog.resolve(block, kind)
        return ToolDescriptor(
            category = t.spec.category,
            icon = ArchieIcons.named(t.spec.icon) ?: ArchieIcons.Build,
            name = t.label,
            summary = t.summary,
            renderer = t.spec.body.id,
            defaultOpen = t.spec.defaultOpen == DefaultOpen.Always,
        )
    }

    @Composable
    override fun Card(item: ChatItem.ToolCard, kind: SessionKind, onToggle: (Boolean) -> Unit, modifier: Modifier) {
        val uri = LocalUriHandler.current
        ToolCard(
            block = item.block,
            expanded = item.expanded,
            onToggle = onToggle,
            modifier = modifier,
            kind = kind,
            placement = if (item.position == ChatItem.GroupPosition.Solo) ToolCardPlacement.Solo else ToolCardPlacement.Grouped,
            onLink = { href -> runCatching { uri.openUri(href) } },
        )
    }
}
