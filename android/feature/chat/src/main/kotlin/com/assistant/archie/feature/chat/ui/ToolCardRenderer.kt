package com.assistant.archie.feature.chat.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.assistant.archie.feature.chat.ChatListFlattener
import com.assistant.archie.feature.chat.model.BasicToolCatalog
import com.assistant.archie.feature.chat.model.ChatItem
import com.assistant.archie.feature.chat.model.ToolDescriptor
import com.assistant.archie.feature.chat.model.ToolStatusUi
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.ToolStatus
import com.assistant.core.design.components.ToolCardPlacement
import com.assistant.core.design.components.ToolCardShell
import com.assistant.core.design.components.ToolOutput
import com.assistant.core.design.components.ToolStatus as ShellStatus
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.model.SessionKind
import kotlin.math.roundToInt

/**
 * The tool-card slot (spec 14 §3.4). The conversation list owns ordering, keys, grouping and the
 * expanded state; a renderer only describes a call and draws one card. B-05's `:feature:toolcards`
 * plugs its per-tool renderers in here; [DefaultToolCardRenderer] is the built-in fallback.
 *
 * Contract (R7): the card must show the result whenever `item.block.output != null` and the card is
 * expanded, and must re-render from `item.block` on every change: never cache the output.
 */
@Stable
interface ToolCardRenderer {
    /** Pure and cheap; called off the main thread by the flattener. */
    fun describe(block: ToolBlock, kind: SessionKind): ToolDescriptor

    @Composable
    fun Card(item: ChatItem.ToolCard, kind: SessionKind, onToggle: (expanded: Boolean) -> Unit, modifier: Modifier)
}

/** Built on B-01's [ToolCardShell]: header line + plain monospace output well. */
object DefaultToolCardRenderer : ToolCardRenderer {
    override fun describe(block: ToolBlock, kind: SessionKind): ToolDescriptor = BasicToolCatalog.describe(block, kind)

    @Composable
    override fun Card(item: ChatItem.ToolCard, kind: SessionKind, onToggle: (Boolean) -> Unit, modifier: Modifier) {
        val b = item.block
        val d = item.descriptor
        ToolCardShell(
            category = d.category,
            icon = d.icon,
            name = d.name,
            summary = d.summary,
            status = ChatListFlattener.status(b).shell(),
            expanded = item.expanded,
            onToggle = { onToggle(!item.expanded) },
            modifier = modifier,
            placement = if (item.position == ChatItem.GroupPosition.Solo) ToolCardPlacement.Solo else ToolCardPlacement.Grouped,
            meta = elapsed(b)?.let { t -> { Text(t) } },
            output = { ToolResultWell(b) },
        )
    }

    private fun elapsed(b: ToolBlock): String? {
        val s = b.progress?.elapsedSeconds ?: return null
        if (b.status != ToolStatus.RUNNING) return null
        val n = s.roundToInt()
        return "${n / 60}:${(n % 60).toString().padStart(2, '0')}"
    }
}

/**
 * The output well of a card (R7). Shows the result whenever there is one; a running call says
 * "Running…" (never empty); a call whose result was never recorded says so. Long output is capped so a
 * card never becomes one giant RenderNode (the libhwui overflow lesson, spec 14 §3.2).
 */
@Composable
fun ToolResultWell(b: ToolBlock) {
    val c = ArchieTheme.colors
    ToolOutput(Modifier.testTag("tool-output")) {
        if (b.inferred) Text("Matched by position", Modifier.alpha(0.7f))
        val out = b.output
        when {
            out != null && out.isEmpty() -> Text("No output", Modifier.alpha(0.7f))
            out != null -> {
                val shown = remember(out) { capOutput(out) }
                Text(
                    shown,
                    Modifier.fillMaxWidth().semantics { contentDescription = "Tool output" },
                    color = if (b.status == ToolStatus.ERROR) c.error else LocalContentColor.current,
                    overflow = TextOverflow.Clip,
                )
            }
            b.status == ToolStatus.RUNNING -> {
                val msg = b.progress?.message
                if (msg != null) Text(msg) else Text("Running…", Modifier.alpha(0.7f))
            }
            b.status == ToolStatus.NO_RESULT -> Text("No output recorded", Modifier.alpha(0.7f))
            else -> Text("No output", Modifier.alpha(0.7f))
        }
    }
}

private const val MAX_LINES = 200
private const val MAX_CHARS = 16_000

/** First [MAX_LINES] lines / [MAX_CHARS] chars, then "… N more lines". */
fun capOutput(out: String): String {
    var lines = 0
    var end = 0
    while (end < out.length && end < MAX_CHARS) {
        if (out[end] == '\n') {
            lines++
            if (lines >= MAX_LINES) break
        }
        end++
    }
    if (end >= out.length) return out
    val rest = out.substring(end).count { it == '\n' } + 1
    return out.substring(0, end).trimEnd('\n') + "\n… $rest more lines"
}

fun ToolStatusUi.shell(): ShellStatus = when (this) {
    ToolStatusUi.Running -> ShellStatus.Running
    ToolStatusUi.Waiting -> ShellStatus.Waiting
    ToolStatusUi.Done -> ShellStatus.Done
    ToolStatusUi.Error -> ShellStatus.Error
}
