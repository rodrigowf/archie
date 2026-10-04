package com.assistant.archie.feature.toolcards.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.assistant.archie.feature.toolcards.BodyPart
import com.assistant.archie.feature.toolcards.DefaultOpen
import com.assistant.archie.feature.toolcards.ResolvedTool
import com.assistant.archie.feature.toolcards.ToolBodies
import com.assistant.archie.feature.toolcards.ToolCatalog
import com.assistant.archie.feature.toolcards.ToolTiming
import com.assistant.archie.feature.toolcards.str
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.ToolStatus
import com.assistant.core.design.components.ToolCardPlacement
import com.assistant.core.design.components.ToolCardShell
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.model.SessionKind
import kotlinx.coroutines.delay
import com.assistant.core.design.components.ToolStatus as ShellStatus

/** Test tag of a card: `tool-card:<canonical name>`. */
const val TOOL_CARD_TAG: String = "tool-card"

/**
 * One tool call (spec 14 §3.4, IA §6, web `ToolCard.tsx`): B-01's [ToolCardShell] header — category
 * tile, label, mono summary, then the time / diff stat and the status — and the renderer's body:
 * the input details and the output region.
 *
 * Stateless about expansion: [expanded] comes from the conversation list (spec 14 §3.1, "a user
 * toggle wins"); the stateful overload below serves standalone use. R7 / TC-1: the body reads
 * [block] on every composition (nothing is cached across results), so the status and the output
 * update in place when the result arrives, also while expanded; a card collapsed before its result
 * shows it on expand.
 */
@Composable
fun ToolCard(
    block: ToolBlock,
    expanded: Boolean,
    onToggle: (expanded: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    kind: SessionKind = SessionKind.AGENT,
    placement: ToolCardPlacement = ToolCardPlacement.Solo,
    /** The conversation's stall names this tool: hourglass instead of the spinner (mockups). */
    stalled: Boolean = false,
    /** Live lines while running (e.g. the delegated agent's activity for send_to_agent_session). */
    feed: List<String>? = null,
    feedTitle: String? = null,
    onLink: (String) -> Unit = {},
) {
    val tool = remember(block.toolName, block.toolInput, kind) { ToolCatalog.resolve(block, kind) }
    val timing = rememberToolTiming(block)
    val time = timing.label
    val diffMeta = tool.spec.diffMeta
    val old = if (diffMeta) tool.input.str("old_string") else null
    val new = if (diffMeta) tool.input.str("new_string") else null
    val hasMeta = time != null || (old != null && new != null)
    ToolCardShell(
        category = tool.spec.category,
        icon = ArchieIcons.named(tool.spec.icon) ?: ArchieIcons.Build,
        name = tool.label,
        summary = tool.summary,
        status = shellStatus(block.status, stalled),
        expanded = expanded,
        onToggle = { onToggle(!expanded) },
        modifier = modifier.testTag("$TOOL_CARD_TAG:${tool.name}"),
        placement = placement,
        meta = if (hasMeta) {
            {
                if (time != null) Text(time)
                if (old != null && new != null) DiffStat(old, new)
            }
        } else {
            null
        },
        output = { ToolBodyView(tool, block, timing.runningSeconds, feed, feedTitle, onLink) },
    )
}

/**
 * A self-contained card: open while [autoOpen] (default: while the tool runs; TodoWrite always),
 * folding by itself when that turns false, until the user toggles it (web `useAutoOpen`).
 */
@Composable
fun ToolCard(
    block: ToolBlock,
    modifier: Modifier = Modifier,
    kind: SessionKind = SessionKind.AGENT,
    autoOpen: Boolean? = null,
    placement: ToolCardPlacement = ToolCardPlacement.Solo,
    stalled: Boolean = false,
    feed: List<String>? = null,
    onLink: (String) -> Unit = {},
) {
    val always = remember(block.toolName, kind) { ToolCatalog.resolve(block, kind).spec.defaultOpen == DefaultOpen.Always }
    val auto = always || (autoOpen ?: (block.status == ToolStatus.RUNNING))
    var manual by remember { mutableStateOf<Boolean?>(null) }
    var prevAuto by remember { mutableStateOf(auto) }
    if (prevAuto != auto) {
        prevAuto = auto
        manual = null
    }
    ToolCard(block, manual ?: auto, { manual = it }, modifier, kind, placement, stalled, feed, onLink = onLink)
}

/** Design status of a block: no_result has no glyph of its own and reads as "waiting". */
fun shellStatus(status: ToolStatus, stalled: Boolean = false): ShellStatus = when (status) {
    ToolStatus.RUNNING -> if (stalled) ShellStatus.Waiting else ShellStatus.Running
    ToolStatus.DONE -> ShellStatus.Done
    ToolStatus.ERROR -> ShellStatus.Error
    ToolStatus.NO_RESULT -> ShellStatus.Waiting
}

/** Elapsed / duration of a card; recomposes once a second while the tool runs. */
@Composable
fun rememberToolTiming(block: ToolBlock): ToolTiming.Timing {
    val running = block.status == ToolStatus.RUNNING
    var tick by remember { mutableIntStateOf(0) }
    if (running) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000)
                tick++
            }
        }
    }
    val t = tick
    return remember(block, t) { ToolTiming.of(block) }
}

/** The expanded part of a card: input details above the output region (web `Section`). */
@Composable
internal fun ToolBodyView(
    tool: ResolvedTool,
    block: ToolBlock,
    runningSeconds: Double?,
    feed: List<String>?,
    feedTitle: String?,
    onLink: (String) -> Unit,
) {
    val parts = remember(tool, block.status, block.output) { ToolBodies.parts(tool, block) }
    val title = feedTitle ?: if (tool.name == "send_to_agent_session") tool.input.str("session_id")?.let { "Live · session ${it.take(8)}" } else null
    Column(Modifier.fillMaxWidth().testTag("tool-body"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { p ->
            when (p) {
                is BodyPart.Fields -> FieldList(p.rows, onLink)
                is BodyPart.Code -> CodeView(p.code, p.lang, p.maxHeightDp)
                is BodyPart.Pre -> PreText(p.text)
                is BodyPart.Json -> PreText(p.text, json = true)
                is BodyPart.Markdown -> RichMarkdown(p.text, "tool-plan:${block.id}", onLink)
                is BodyPart.Diff -> DiffView(p.old, p.new)
                is BodyPart.Todos -> TodoList(p.items)
                is BodyPart.Questions -> QuestionList(p.items)
                is BodyPart.Note -> NoteText(p.text)
                is BodyPart.Output -> ToolOutputRegion(
                    block = block,
                    label = p.label,
                    render = p.render,
                    footer = p.footer,
                    runningText = tool.spec.runningText ?: "Running…",
                    runningSeconds = runningSeconds,
                    feed = feed,
                    feedTitle = title,
                    onLink = onLink,
                )
            }
        }
    }
}
