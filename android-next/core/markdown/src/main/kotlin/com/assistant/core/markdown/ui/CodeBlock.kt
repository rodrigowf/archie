package com.assistant.core.markdown.ui

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assistant.core.design.components.ArchieButton
import com.assistant.core.design.components.ButtonSize
import com.assistant.core.design.components.ButtonStyle
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.markdown.CodeHighlight
import com.assistant.core.markdown.CodeHighlighter
import com.assistant.core.markdown.CodeTokenKind
import com.assistant.core.markdown.MdNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val CODE_BODY_TAG = "md-code-body"
const val CODE_TAIL_TAG = "md-code-tail"

/**
 * A closed code block (spec 14 §3.3): header with the language and Copy ("Copied" for 2 s),
 * wrapped by default with a "No wrap" toggle (horizontal scroll for closed blocks only), height
 * capped at [MarkdownStyle.codeMaxHeight] with "Show all (N lines)". Highlighting runs on
 * Dispatchers.Default and is cached; a fence without a language is still a block, shown plain.
 */
@Composable
fun CodeBlockView(lang: String?, code: String, style: MarkdownStyle, modifier: Modifier = Modifier) {
    var wrap by rememberSaveable(code) { mutableStateOf(true) }
    var expanded by rememberSaveable(code) { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    val highlight by produceState(CodeHighlighter.cached(lang, code), lang, code) {
        if (value == null && CodeHighlighter.isHighlightable(lang, code)) value = CodeHighlighter.highlightAsync(lang, code)
    }
    val lineHeight = with(LocalDensity.current) { style.code.lineHeight.toDp() }
    val maxLines = (style.codeMaxHeight / lineHeight).toInt().coerceAtLeast(4)
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    val collapsed = !expanded && lineCount > maxLines
    val shown = remember(code, collapsed, maxLines) { if (collapsed) firstLines(code, maxLines) else code }
    val text = remember(shown, highlight, style.syntax) { highlighted(shown, highlight, style) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.codeCorner))
            .background(style.codeSurface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(style.codeHeaderSurface)
                .heightIn(min = 40.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                lang ?: "text",
                Modifier.weight(1f),
                color = style.mutedColor,
                style = style.codeHeaderText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ArchieButton(
                text = if (wrap) "No wrap" else "Wrap",
                onClick = { wrap = !wrap },
                style = ButtonStyle.Text,
                size = ButtonSize.Small,
            )
            ArchieButton(
                text = if (copied) "Copied" else "Copy",
                onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code))) }
                    copied = true
                },
                style = ButtonStyle.Text,
                size = ButtonSize.Small,
                icon = if (copied) ArchieIcons.Check else ArchieIcons.ContentCopy,
            )
        }
        val scroll = if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())
        Box(scroll.testTag(CODE_BODY_TAG)) {
            Text(
                text,
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                color = style.codeColor,
                style = style.code,
                softWrap = wrap,
            )
        }
        if (collapsed) {
            ArchieButton(
                text = "Show all ($lineCount lines)",
                onClick = { expanded = true },
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                style = ButtonStyle.Text,
                size = ButtonSize.Small,
            )
        }
    }
}

/**
 * A chunk of a fence that is still streaming: plain, soft-wrapped, no scroll, no highlighting
 * (spec 14 §3.2). Chunks stack into one visual block: the first carries the header, the open one
 * the bottom corners.
 */
@Composable
internal fun CodeTailView(node: MdNode.CodeTail, style: MarkdownStyle) {
    val r = style.codeCorner
    val shape: Shape = when {
        node.chunk == 0 && node.open -> RoundedCornerShape(r)
        node.chunk == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
        node.open -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> RoundedCornerShape(0.dp)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(style.codeSurface)
            .testTag(CODE_TAIL_TAG),
    ) {
        if (node.chunk == 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(style.codeHeaderSurface)
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(node.lang ?: "text", color = style.mutedColor, style = style.codeHeaderText, maxLines = 1)
            }
        }
        Text(
            node.text,
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = if (node.chunk == 0) 12.dp else 0.dp, bottom = if (node.open) 12.dp else 0.dp),
            color = style.codeColor,
            style = style.code,
            softWrap = true,
        )
    }
}

private fun firstLines(code: String, n: Int): String {
    var idx = -1
    repeat(n) {
        idx = code.indexOf('\n', idx + 1)
        if (idx < 0) return code
    }
    return code.substring(0, idx)
}

private fun highlighted(code: String, highlight: CodeHighlight?, style: MarkdownStyle): AnnotatedString {
    if (highlight == null || highlight.spans.isEmpty()) return AnnotatedString(code)
    val b = AnnotatedString.Builder(code)
    for (s in highlight.spans) {
        if (s.start >= code.length) break
        val end = minOf(s.end, code.length)
        val span = if (s.kind == CodeTokenKind.Comment) {
            SpanStyle(color = style.syntax.of(s.kind), fontStyle = FontStyle.Italic)
        } else {
            SpanStyle(color = style.syntax.of(s.kind))
        }
        b.addStyle(span, s.start, end)
    }
    return b.toAnnotatedString()
}
