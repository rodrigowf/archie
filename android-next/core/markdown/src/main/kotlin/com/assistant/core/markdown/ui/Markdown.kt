package com.assistant.core.markdown.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assistant.core.design.Corner
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.markdown.Frontmatter
import com.assistant.core.markdown.MarkdownCache
import com.assistant.core.markdown.MdNode
import com.assistant.core.markdown.MdSnapshot
import kotlinx.collections.immutable.ImmutableList

/**
 * One lazy item per markdown node (spec 14 §3.1): key `"$keyPrefix/md:$index"`, content type
 * from [MdSnapshot.contentType]. A streaming delta changes only the tail item(s); committed nodes
 * are the same instances, so their [MarkdownBlock]s are skipped. :feature:chat builds the same
 * items through its flattener; this helper serves memory documents and tests.
 */
fun LazyListScope.markdownItems(
    snapshot: MdSnapshot,
    keyPrefix: String,
    style: MarkdownStyle,
    onLinkClick: (String) -> Unit,
    itemModifier: Modifier = Modifier,
) {
    items(
        count = snapshot.size,
        key = { "$keyPrefix/md:$it" },
        contentType = { snapshot.contentType(it) },
    ) { i ->
        val node = snapshot[i]
        val previous = if (i > 0) snapshot[i - 1] else null
        MarkdownBlock(node, itemModifier.padding(top = style.spacingBefore(previous, node)), style, onLinkClick)
    }
}

/**
 * Small, finished markdown in a plain Column (tool output, plan text). Long documents should use
 * [markdownItems] in a LazyColumn instead. Parsed once per text through [MarkdownCache].
 */
@Composable
fun Markdown(
    text: String,
    modifier: Modifier = Modifier,
    style: MarkdownStyle = MarkdownStyle.fromTheme(),
    onLinkClick: (String) -> Unit = {},
    cacheId: String = "inline",
) {
    val nodes = remember(cacheId, text) { MarkdownCache.Shared.getBlocking(cacheId, text) }
    MarkdownNodes(nodes, modifier, style, onLinkClick)
}

@Composable
fun MarkdownNodes(
    nodes: List<MdNode>,
    modifier: Modifier = Modifier,
    style: MarkdownStyle = MarkdownStyle.fromTheme(),
    onLinkClick: (String) -> Unit = {},
) {
    Column(modifier) {
        nodes.forEachIndexed { i, node ->
            MarkdownBlock(node, Modifier.padding(top = style.spacingBefore(nodes.getOrNull(i - 1), node)), style, onLinkClick)
        }
    }
}

/** Parsed nodes of a finished text, parsed off the main thread; null until ready (cache hit: immediately). */
@Composable
fun rememberMarkdown(id: String, text: String, cache: MarkdownCache = MarkdownCache.Shared): ImmutableList<MdNode>? {
    val state by produceState(cache.peek(id, text), id, text) {
        if (value == null) value = cache.get(id, text)
    }
    return state
}

/**
 * The memory document's frontmatter (spec 14 §4.1, mockup g2): a collapsed chip
 * "Frontmatter · category · modified · tags · N refs" that expands in place to the raw YAML.
 */
@Composable
fun FrontmatterCard(frontmatter: String, modifier: Modifier = Modifier, initiallyExpanded: Boolean = false) {
    val summary = remember(frontmatter) { Frontmatter.summarize(frontmatter) }
    var expanded by rememberSaveable(frontmatter) { mutableStateOf(initiallyExpanded) }
    val c = ArchieTheme.colors
    val shape = RoundedCornerShape(Corner.Small)
    val label = listOf("Frontmatter", summary.line()).filter { it.isNotEmpty() }.joinToString(" · ")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .height(32.dp)
                .clip(shape)
                .border(1.dp, c.outlineVariant, shape)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(PaddingValues(start = 8.dp, end = 8.dp)),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArchieIcon(ArchieIcons.DataObject, null, size = 18.dp, tint = c.onSurfaceVariant)
            Text(
                label,
                Modifier.weight(1f, fill = false),
                color = c.onSurfaceVariant,
                style = ArchieTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ArchieIcon(
                if (expanded) ArchieIcons.KeyboardArrowUp else ArchieIcons.KeyboardArrowDown,
                null,
                size = 18.dp,
                tint = c.onSurfaceVariant,
            )
        }
        if (expanded) {
            Text(
                frontmatter,
                Modifier
                    .fillMaxWidth()
                    .background(c.surfaceContainer, RoundedCornerShape(Corner.Medium))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                color = c.onSurfaceVariant,
                style = ArchieTheme.text.codeSmall,
            )
        }
    }
}
