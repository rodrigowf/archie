package com.assistant.archie.feature.toolcards.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.archie.feature.toolcards.OutputFooter
import com.assistant.archie.feature.toolcards.OutputKind
import com.assistant.archie.feature.toolcards.OutputRender
import com.assistant.archie.feature.toolcards.ToolOutputRules
import com.assistant.archie.feature.toolcards.formatClock
import com.assistant.archie.feature.toolcards.groupDigits
import com.assistant.archie.feature.toolcards.parseScriptResult
import com.assistant.archie.feature.toolcards.plural
import com.assistant.archie.feature.toolcards.stripAnsi
import com.assistant.archie.feature.toolcards.truncateOutput
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.ToolStatus
import com.assistant.core.design.components.ArchieButton
import com.assistant.core.design.components.ButtonSize
import com.assistant.core.design.components.ButtonStyle
import com.assistant.core.design.components.Spinner
import com.assistant.core.design.components.ToolOutput
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.markdown.ui.Markdown

/** Test tags of the output region (R7 tests find the region by its state). */
const val OUTPUT_TAG: String = "tool-output"

/**
 * The output region every tool card has (spec 12 §4.5 TC-1/TC-2/TC-3, Rodrigo's R7), ported from
 * the web's `OutputView.tsx`. It reads the [ToolBlock] itself, so it updates in place when the
 * result arrives, also while the card is already expanded:
 *
 *   running    spinner + "Running…" (or the tool's waiting text) + elapsed  — never empty
 *   done       the output (ANSI stripped, first 200 lines / 20 KB, then "Show all")
 *   error      the output in the error colour
 *   no_result  "No output received" (live) or "No output recorded" (history)
 *
 * An `inferred` result shows a subtle "matched by position" hint (R-4).
 */
@Composable
fun ToolOutputRegion(
    block: ToolBlock,
    modifier: Modifier = Modifier,
    label: String = "Output",
    render: OutputRender = OutputRender.Plain,
    footer: OutputFooter? = null,
    runningText: String = "Running…",
    runningSeconds: Double? = null,
    feed: List<String>? = null,
    feedTitle: String? = null,
    onLink: (String) -> Unit = {},
) {
    val kind = ToolOutputRules.kind(block, hasFeed = !feed.isNullOrEmpty())
    val clock = runningSeconds?.let(::formatClock)
    when (kind) {
        OutputKind.Feed -> ToolOutput(modifier.testTag("$OUTPUT_TAG:feed")) {
            Head(feedTitle ?: "Live", clock)
            val lines = feed.orEmpty()
            lines.forEachIndexed { i, line ->
                val last = i == lines.lastIndex
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(line, Modifier.weight(1f, fill = false), color = if (last) ArchieTheme.colors.onSurface else LocalContentColor.current)
                    if (last) Caret()
                }
            }
        }
        OutputKind.Running -> ToolOutput(modifier.testTag("$OUTPUT_TAG:running")) {
            Row(Modifier.alpha(0.85f), verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 12.dp)
                Spacer(Modifier.width(8.dp))
                Text(runningText)
                if (clock != null) Text("  · $clock", style = ArchieTheme.text.codeSmall.copy(fontFeatureSettings = "tnum"))
            }
        }
        OutputKind.NoResult, OutputKind.Empty -> ToolOutput(modifier.testTag("$OUTPUT_TAG:${if (kind == OutputKind.NoResult) "no-result" else "empty"}")) {
            val err = block.status == ToolStatus.ERROR
            Text(
                ToolOutputRules.placeholder(block, runningText).orEmpty(),
                Modifier.alpha(0.85f),
                color = if (err) ArchieTheme.colors.error else LocalContentColor.current,
            )
            if (footer != null) Footer(footer)
        }
        OutputKind.Output -> TextOutput(block, modifier, label, render, footer, onLink)
    }
}

@Composable
private fun TextOutput(block: ToolBlock, modifier: Modifier, label: String, render: OutputRender, footer: OutputFooter?, onLink: (String) -> Unit) {
    val text = block.output.orEmpty()
    val isError = block.status == ToolStatus.ERROR
    var showAll by rememberSaveable(block.id) { mutableStateOf(false) }
    val clean = remember(text) { stripAnsi(text) }
    val cut = remember(clean) { truncateOutput(clean) }
    val shown = if (showAll) clean else cut.text
    val meta = if (clean.length > 500) "${plural(cut.totalLines, "line")} · ${groupDigits(clean.length)} chars" else null
    val c = ArchieTheme.colors
    val errorBar = if (isError) {
        Modifier.clip(RoundedCornerShape(10.dp)).drawWithContent {
            drawContent()
            drawRect(c.error, size = Size(3.dp.toPx(), size.height))
        }
    } else {
        Modifier
    }
    ToolOutput(modifier.then(errorBar).testTag("$OUTPUT_TAG:output")) {
        if (meta != null || block.inferred) {
            Head(label, null) {
                if (block.inferred) Text("matched by position", fontStyle = FontStyle.Italic, modifier = Modifier.alpha(0.8f))
                if (block.inferred && meta != null) Text(" · ")
                if (meta != null) Text(meta)
            }
        }
        when (render) {
            OutputRender.Plain -> Text(
                shown,
                Modifier.fillMaxWidth().semantics { contentDescription = "Tool output" },
                color = if (isError) c.error else LocalContentColor.current,
                overflow = TextOverflow.Clip,
            )
            OutputRender.Markdown -> if (isError) {
                Text(shown, color = c.error)
            } else {
                Markdown(shown, Modifier.fillMaxWidth(), style = richMarkdownStyle(), onLinkClick = onLink, cacheId = "tool:${block.id}")
            }
            OutputRender.Script -> ScriptOutput(shown, isError)
        }
        if (cut.truncated) {
            ArchieButton(
                text = if (showAll) "Show less" else "Show all ${plural(cut.totalLines, "line")}",
                onClick = { showAll = !showAll },
                modifier = Modifier.padding(top = 6.dp),
                style = ButtonStyle.Text,
                size = ButtonSize.Small,
            )
        }
        if (footer != null) Footer(footer)
    }
}

/** run_script's JSON result: stdout, stderr in the error colour, the exit code. */
@Composable
private fun ScriptOutput(text: String, isError: Boolean) {
    val c = ArchieTheme.colors
    val r = remember(text) { parseScriptResult(text) }
    if (r == null) {
        Text(text, color = if (isError) c.error else LocalContentColor.current)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (r.stdout.isNotEmpty()) Text(r.stdout.removeSuffix("\n"))
        if (r.stderr.isNotEmpty()) Text(r.stderr.removeSuffix("\n"), color = c.error)
        if (r.stdout.isEmpty() && r.stderr.isEmpty()) Text("No output", Modifier.alpha(0.7f))
        if (r.exitCode != null) Text("exit ${r.exitCode}", color = if (r.exitCode == 0) ArchieTheme.extended.success.color else c.error)
    }
}

/** Region header (mockup `.tc-o .hd`): 11 sp uppercase label, right-aligned meta. */
@Composable
private fun Head(label: String, clock: String?, trailing: (@Composable () -> Unit)? = null) {
    val style = ArchieTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.W500, letterSpacing = 0.5.sp)
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), Modifier.weight(1f), style = style, maxLines = 1)
        if (clock != null) Text(clock, style = style.copy(fontFeatureSettings = "tnum"))
        if (trailing != null) {
            ProvideTextStyle(style.copy(letterSpacing = 0.sp)) { Row { trailing() } }
        }
    }
}

@Composable
private fun Footer(footer: OutputFooter) {
    val x = ArchieTheme.extended
    Text(
        footer.text,
        Modifier.padding(top = 6.dp).alpha(0.85f),
        color = if (footer.error) ArchieTheme.colors.error else x.success.color,
    )
}

/** The streaming caret (mockup `.caret`), still: goldens stay deterministic. */
@Composable
private fun Caret() {
    Box(Modifier.padding(start = 2.dp).size(width = 2.dp, height = 15.dp).background(ArchieTheme.colors.primary))
}
