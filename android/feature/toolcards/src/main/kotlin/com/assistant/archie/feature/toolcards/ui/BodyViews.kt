package com.assistant.archie.feature.toolcards.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.archie.feature.toolcards.DiffLine
import com.assistant.archie.feature.toolcards.DiffLineKind
import com.assistant.archie.feature.toolcards.DiffResult
import com.assistant.archie.feature.toolcards.Field
import com.assistant.archie.feature.toolcards.Question
import com.assistant.archie.feature.toolcards.TodoItem
import com.assistant.archie.feature.toolcards.TodoStatus
import com.assistant.archie.feature.toolcards.ToolDiff
import com.assistant.core.design.Corner
import com.assistant.core.design.StateLayer
import com.assistant.core.design.ToolCategory
import com.assistant.core.design.components.ArchieButton
import com.assistant.core.design.components.ButtonSize
import com.assistant.core.design.components.ButtonStyle
import com.assistant.core.design.components.toolColors
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.markdown.ui.CodeBlockView
import com.assistant.core.markdown.ui.Markdown
import com.assistant.core.markdown.ui.MarkdownStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Markdown inside a card (web `.rich`): body-medium, code blocks on surface-container-low. */
@Composable
internal fun richMarkdownStyle(): MarkdownStyle {
    val base = MarkdownStyle.fromTheme()
    val c = ArchieTheme.colors
    val t = ArchieTheme.typography
    return remember(base) {
        base.copy(
            body = t.bodyMedium,
            headings = listOf(t.titleMedium, t.titleSmall, t.titleSmall, t.titleSmall, t.titleSmall, t.titleSmall),
            codeSurface = c.surfaceContainerLow,
            blockSpacing = 8.dp,
            headingSpacing = 12.dp,
        )
    }
}

/** Label/value rows (web `Fields`, a description list): label column ≥ 72 dp, then the value. */
@Composable
internal fun FieldList(rows: List<Field>, onLink: (String) -> Unit) {
    val c = ArchieTheme.colors
    val small = ArchieTheme.typography.bodySmall
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp).testTag("tool-fields"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { f ->
            Row(Modifier.fillMaxWidth()) {
                Text(f.label, Modifier.widthIn(min = 72.dp), style = small.copy(fontWeight = FontWeight.W500), color = c.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                val style = if (f.mono) ArchieTheme.text.codeSmall.copy(fontSize = 12.sp, lineHeight = small.lineHeight) else small
                val href = f.href
                if (href != null) {
                    Text(
                        f.value,
                        Modifier.weight(1f).clickable(role = Role.Button) { onLink(href) },
                        style = style.copy(textDecoration = TextDecoration.Underline),
                        color = c.primary,
                    )
                } else {
                    Text(f.value, Modifier.weight(1f), style = style, color = c.onSurface)
                }
            }
        }
    }
}

/** A highlighted code view (web `CodeView`), capped at [maxHeightDp] with "Show all (N lines)". */
@Composable
internal fun CodeView(code: String, lang: String?, maxHeightDp: Int) {
    val base = richMarkdownStyle()
    val style = remember(base, maxHeightDp) { base.copy(codeMaxHeight = maxHeightDp.dp, codeCorner = Corner.Small) }
    CodeBlockView(lang, code, style, Modifier.testTag("tool-code"))
}

private const val FOLD_LINES = 12

/**
 * Pre-formatted input (prompts, messages; the generic JSON view with [json]). The web caps these at
 * 240 px with an inner scroll; inside a lazy list a fold reads better: 12 lines + "Show all".
 */
@Composable
internal fun PreText(text: String, json: Boolean = false) {
    val c = ArchieTheme.colors
    var all by rememberSaveable(text) { mutableStateOf(false) }
    val lines = remember(text) { text.count { it == '\n' } + 1 }
    val folded = !all && lines > FOLD_LINES
    val shown = remember(text, folded) { if (folded) text.lineSequence().take(FOLD_LINES).joinToString("\n") else text }
    val style = if (json) ArchieTheme.text.codeSmall.copy(fontSize = 12.sp, lineHeight = 18.sp) else ArchieTheme.text.codeSmall
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Small))
            .background(c.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(if (json) "tool-json" else "tool-pre"),
    ) {
        Text(shown, style = style, color = if (json) c.onSurfaceVariant else c.onSurface)
        if (lines > FOLD_LINES) {
            ArchieButton(
                text = if (all) "Show less" else "Show all ($lines lines)",
                onClick = { all = !all },
                style = ButtonStyle.Text,
                size = ButtonSize.Small,
            )
        }
    }
}

@Composable
internal fun RichMarkdown(text: String, cacheId: String, onLink: (String) -> Unit) {
    Markdown(text, Modifier.fillMaxWidth().padding(horizontal = 4.dp), style = richMarkdownStyle(), onLinkClick = onLink, cacheId = cacheId)
}

@Composable
internal fun NoteText(text: String) {
    Text(text, Modifier.padding(horizontal = 4.dp).alpha(0.7f), style = ArchieTheme.typography.bodyMedium, color = ArchieTheme.colors.onSurfaceVariant)
}

/* ---------- Edit diff (spec 14 §3.5) ---------- */

/** Rows shown before "Show full diff": ≈ the 480 dp cap at 19 dp per line. */
private const val DIFF_CAP_ROWS = 25

/** The diff of an Edit: small inputs diff during composition; big ones on Dispatchers.Default. */
@Composable
internal fun rememberDiff(old: String, new: String): DiffResult? {
    val sync = old.length + new.length <= ToolDiff.SYNC_LIMIT_CHARS
    val now = remember(old, new) { if (sync) ToolDiff.diff(old, new) else ToolDiff.cached(old, new) }
    val later by produceState(now, old, new) {
        if (value == null) value = withContext(Dispatchers.Default) { ToolDiff.diff(old, new) }
    }
    return now ?: later
}

/**
 * Unified line diff (inv02 F-06): `+`/`−` markers, add/remove tints, long unchanged runs folded
 * into "⋯ N unchanged lines" (tap to show them). Lines wrap, so long lines never create wide
 * render nodes; over 25 rows the view caps with "Show full diff".
 */
@Composable
internal fun DiffView(old: String, new: String) {
    val diff = rememberDiff(old, new)
    val base = diff?.lines ?: remember(old, new) { ToolDiff.plain(old, new) }
    var opened by remember(old, new) { mutableStateOf(emptySet<Int>()) }
    var full by rememberSaveable(old, new) { mutableStateOf(false) }
    // (index in [base] for fold rows, line); an opened fold is replaced by the lines it hid.
    val rows = remember(base, opened) {
        buildList {
            base.forEachIndexed { i, l ->
                if (l.kind == DiffLineKind.Fold && i in opened) l.hidden.forEach { add(-1 to DiffLine(DiffLineKind.Ctx, it)) } else add(i to l)
            }
        }
    }
    val c = ArchieTheme.colors
    val capped = !full && rows.size > DIFF_CAP_ROWS
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.surfaceContainerLowest)
            .padding(vertical = 10.dp)
            .semantics { contentDescription = "Diff" }
            .testTag(if (diff != null) "tool-diff" else "tool-diff-pending"),
    ) {
        CompositionLocalProvider(LocalContentColor provides c.onSurfaceVariant) {
            ProvideTextStyle(ArchieTheme.text.codeSmall) {
                (if (capped) rows.take(DIFF_CAP_ROWS) else rows).forEach { (i, line) ->
                    DiffRow(line, if (line.kind == DiffLineKind.Fold) ({ opened = opened + i }) else null)
                }
            }
        }
        if (capped) {
            ArchieButton(
                text = "Show full diff (${rows.size} lines)",
                onClick = { full = true },
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                style = ButtonStyle.Text,
                size = ButtonSize.Small,
            )
        }
    }
}

private fun mark(kind: DiffLineKind): String = when (kind) {
    DiffLineKind.Add -> "+"
    DiffLineKind.Del -> "−"
    DiffLineKind.Ctx -> " "
    DiffLineKind.Fold -> "⋯"
}

@Composable
private fun DiffRow(line: DiffLine, onClick: (() -> Unit)?) {
    val c = ArchieTheme.colors
    val x = ArchieTheme.extended
    val (fg, bg) = when (line.kind) {
        DiffLineKind.Add -> x.toolWrite.color to x.toolWrite.tint
        DiffLineKind.Del -> c.error to c.error.copy(alpha = StateLayer.Hover)
        DiffLineKind.Fold -> x.toolRead.color to Color.Transparent
        DiffLineKind.Ctx -> LocalContentColor.current to Color.Transparent
    }
    val spoken = when (line.kind) {
        DiffLineKind.Add -> "added: ${line.text}"
        DiffLineKind.Del -> "removed: ${line.text}"
        else -> line.text
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Show unchanged lines", onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Text(mark(line.kind), Modifier.width(12.dp), color = fg)
        Text(
            line.text.ifEmpty { " " },
            Modifier.weight(1f),
            color = fg,
            fontStyle = if (line.kind == DiffLineKind.Fold) FontStyle.Italic else null,
        )
    }
}

/** Header meta of an Edit: "+4 −1" (add in success, remove in error), once the diff exists. */
@Composable
internal fun DiffStat(old: String, new: String) {
    val diff = rememberDiff(old, new) ?: return
    val x = ArchieTheme.extended
    val c = ArchieTheme.colors
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = x.success.color)) { append("+${diff.added}") }
            append(" ")
            withStyle(SpanStyle(color = c.error)) { append("−${diff.removed}") }
        },
        style = ArchieTheme.text.codeSmall.copy(fontSize = 12.sp),
        maxLines = 1,
    )
}

/* ---------- TodoWrite ---------- */

/**
 * The checklist (inv02 F-05 TodoWriteBlock): pending ○, in progress ◐ with `activeForm` in bold,
 * completed ✓ dimmed and struck through, cancelled ✕ struck through.
 */
@Composable
internal fun TodoList(items: List<TodoItem>) {
    val c = ArchieTheme.colors
    val x = ArchieTheme.extended
    val todo = toolColors(ToolCategory.Todo).color
    val body = ArchieTheme.typography.bodyMedium.copy(lineHeight = 22.sp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp).testTag("tool-todos")) {
        items.forEach { t ->
            val done = t.status == TodoStatus.Completed
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (done) Modifier.alpha(0.55f) else Modifier)
                    .semantics(mergeDescendants = true) { contentDescription = "${t.status.spoken}: ${t.text}" },
            ) {
                Box(Modifier.width(20.dp).padding(top = 3.dp), contentAlignment = Alignment.TopCenter) {
                    when (t.status) {
                        TodoStatus.Completed -> ArchieIcon(ArchieIcons.CheckCircle, null, size = 16.dp, tint = x.success.color)
                        TodoStatus.Cancelled -> ArchieIcon(ArchieIcons.Close, null, size = 16.dp, tint = c.onSurfaceVariant)
                        TodoStatus.InProgress -> Ring(todo, half = true)
                        TodoStatus.Pending -> Ring(c.onSurfaceVariant, half = false)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    t.text,
                    Modifier.weight(1f).then(if (t.status == TodoStatus.Cancelled) Modifier.alpha(0.55f) else Modifier),
                    style = body,
                    color = if (t.status == TodoStatus.InProgress) c.onSurface else c.onSurfaceVariant,
                    fontWeight = if (t.status == TodoStatus.InProgress) FontWeight.W600 else null,
                    textDecoration = if (done || t.status == TodoStatus.Cancelled) TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

@Composable
private fun Ring(color: Color, half: Boolean) {
    Box(
        Modifier
            .padding(top = 2.dp)
            .size(12.dp)
            .clip(CircleShape)
            .then(if (half) Modifier.drawBehind { drawRect(color, size = Size(size.width / 2, size.height)) } else Modifier)
            .border(2.dp, color, CircleShape),
    )
}

/* ---------- AskUserQuestion ---------- */

@Composable
internal fun QuestionList(items: List<Question>) {
    val c = ArchieTheme.colors
    val body = ArchieTheme.typography.bodyMedium
    val accent = toolColors(ToolCategory.Interact).color
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { q ->
            Text(
                buildAnnotatedString {
                    if (!q.header.isNullOrEmpty()) {
                        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.W500)) { append(q.header) }
                        append("  ")
                    }
                    withStyle(SpanStyle(color = c.onSurface)) { append(q.question) }
                    if (q.multiSelect) withStyle(SpanStyle(color = c.onSurfaceVariant)) { append(" (multiple)") }
                },
                style = body,
            )
            q.options.forEach { o ->
                Text(
                    buildAnnotatedString {
                        append("•  ")
                        withStyle(SpanStyle(color = c.onSurface, fontWeight = FontWeight.W500)) { append(o.label) }
                        if (!o.description.isNullOrEmpty()) withStyle(SpanStyle(color = c.onSurfaceVariant)) { append(" — ${o.description}") }
                    },
                    Modifier.padding(start = 8.dp),
                    style = body,
                    color = c.onSurfaceVariant,
                )
            }
        }
    }
}
