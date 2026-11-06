package com.assistant.archie.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.assistant.core.conversation.QuestionItem
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.assistant.archie.feature.chat.ChatAction
import com.assistant.archie.feature.chat.ConversationUiMapper
import com.assistant.archie.feature.chat.InlineCardUi
import com.assistant.archie.feature.chat.model.BasicToolCatalog
import com.assistant.core.design.components.InlineCard
import com.assistant.core.design.components.InlineCardAction
import com.assistant.core.design.components.InlineCardKind
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.markdown.ui.Markdown
import com.assistant.core.markdown.ui.MarkdownStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.layout.padding
import kotlinx.collections.immutable.ImmutableList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Cards above the composer (IA §6): permission (Approve / Reject / type to give feedback), agent
 * approvals (PM-5), stall (Interrupt), errors (Retry + dismiss + detail), gap (Reload). A session
 * closed on the server closes its view instead (spec 12 OPEN-3). Never pinned, never modal, never an entry.
 */
@Composable
fun InlineCardsArea(
    cards: ImmutableList<InlineCardUi>,
    onAction: (ChatAction) -> Unit,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (card in cards) {
            val m = Modifier.testTag("card:${card.id}")
            when (card) {
                is InlineCardUi.Permission -> PermissionCard(card, onAction, onLink, m)
                is InlineCardUi.AgentApprovalCard -> AgentApprovalCard(card, onAction, m)
                is InlineCardUi.Question -> QuestionCard(card, onAction, m)
                is InlineCardUi.AgentQuestionCard -> AgentQuestionCard(card, onAction, m)
                is InlineCardUi.Stall -> InlineCard(
                    InlineCardKind.Stall,
                    ConversationUiMapper.stallTitle(card.toolName, card.elapsedSeconds),
                    m,
                    body = { Text(ConversationUiMapper.stallBody(card.toolName, card.elapsedSeconds)) },
                    actions = {
                        InlineCardAction("Keep waiting", { onAction(ChatAction.DismissCard("stall")) }, primary = false)
                        InlineCardAction("Interrupt", { onAction(ChatAction.Stop) }, primary = true)
                    },
                )
                is InlineCardUi.Error -> ErrorCard(card, onAction, m)
                InlineCardUi.GapPossible -> InlineCard(
                    InlineCardKind.Error,
                    "Some messages may be missing",
                    m,
                    icon = ArchieIcons.Sync,
                    onDismiss = { onAction(ChatAction.DismissCard("gap")) },
                    body = { Text("The live stream skipped ahead. Reload to fetch the full conversation.") },
                    actions = { InlineCardAction("Reload", { onAction(ChatAction.Reload) }, primary = true, icon = ArchieIcons.Refresh) },
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(card: InlineCardUi.Permission, onAction: (ChatAction) -> Unit, onLink: (String) -> Unit, modifier: Modifier) {
    val b = card.block
    val plan = (b.toolInput["plan"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    InlineCard(
        InlineCardKind.Permission,
        if (plan != null) "Plan ready: exit plan mode?" else "${b.toolName} wants to run",
        modifier,
        onDismiss = { onAction(ChatAction.DismissCard(card.id)) },
        hint = "Or type below to give feedback",
        body = {
            if (plan != null) {
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    Markdown(plan, style = cardMarkdownStyle(), onLinkClick = onLink, cacheId = "perm-plan:${b.requestId}")
                }
            } else {
                Text(inputSummary(b.toolName, b.toolInput))
            }
        },
        actions = {
            // One click only; the card closes when the block leaves `pending` (PM-3), never before.
            InlineCardActionGate(!card.answered) {
                InlineCardAction("Reject", { onAction(ChatAction.Permission(b.requestId, allow = false)) }, primary = false)
                InlineCardAction("Approve", { onAction(ChatAction.Permission(b.requestId, allow = true)) }, primary = true)
            }
        },
    )
}

@Composable
private fun AgentApprovalCard(
    card: InlineCardUi.AgentApprovalCard,
    onAction: (ChatAction) -> Unit,
    modifier: Modifier,
) {
    val a = card.approval
    val plan = (a.toolInput["plan"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    InlineCard(
        InlineCardKind.Permission,
        if (plan != null) "An agent finished planning" else ConversationUiMapper.agentApprovalTitle(a),
        modifier,
        onDismiss = { onAction(ChatAction.DismissCard(card.id)) },
        body = {
            if (plan != null) {
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    Text("It asks to exit plan mode:")
                    Markdown(plan, Modifier.padding(top = 6.dp), style = cardMarkdownStyle(), onLinkClick = {}, cacheId = "agent-plan:${a.localId}:${a.requestId}")
                }
            } else {
                Text("Agent session ${a.localId.take(8)} · " + inputSummary(a.toolName, a.toolInput))
            }
        },
        actions = {
            // Answered from here whether or not the agent's view is open (REST, §6.9).
            InlineCardActionGate(!card.answered) {
                InlineCardAction("Reject", { onAction(ChatAction.AgentApproval(a.localId, a.requestId, false)) }, primary = false)
                InlineCardAction("Approve", { onAction(ChatAction.AgentApproval(a.localId, a.requestId, true)) }, primary = true)
            }
        },
    )
}

/**
 * QU-1/QU-3: the AskUserQuestion picker. The agent is blocked inside the call until this is
 * answered, so one tap per question and Send unblocks it; typing in the composer answers it too
 * (QU-4), which is what the hint says.
 */
@Composable
private fun QuestionCard(card: InlineCardUi.Question, onAction: (ChatAction) -> Unit, modifier: Modifier) {
    val q = card.card
    var picks by rememberSaveable(q.requestId) { mutableStateOf(mapOf<String, List<String>>()) }
    InlineCard(
        InlineCardKind.Permission,
        if (q.questions.size > 1) "A few questions" else "One question",
        modifier,
        icon = ArchieIcons.Help,
        onDismiss = { onAction(ChatAction.DismissCard(card.id)) },
        hint = "Or just type your answer below",
        body = { QuestionRows(q.questions, picks, card.answered) { k, v -> picks = picks + (k to v) } },
        actions = {
            InlineCardActionGate(!card.answered && allAnswered(q.questions, picks)) {
                InlineCardAction("Send answer", { onAction(ChatAction.Question(q.requestId, picks)) }, primary = true)
            }
        },
    )
}

/** QU-8: the same picker for a question an agent is blocked on, from the Archie view. */
@Composable
private fun AgentQuestionCard(
    card: InlineCardUi.AgentQuestionCard,
    onAction: (ChatAction) -> Unit,
    modifier: Modifier,
) {
    val q = card.question
    val key = "${q.localId}:${q.requestId}"
    var picks by rememberSaveable(key) { mutableStateOf(mapOf<String, List<String>>()) }
    InlineCard(
        InlineCardKind.Permission,
        "Agent session ${q.localId.take(8)} is asking",
        modifier,
        icon = ArchieIcons.Help,
        onDismiss = { onAction(ChatAction.DismissCard(card.id)) },
        hint = "Or tell Archie below",
        body = { QuestionRows(q.questions, picks, card.answered) { k, v -> picks = picks + (k to v) } },
        actions = {
            InlineCardActionGate(!card.answered && allAnswered(q.questions, picks)) {
                InlineCardAction(
                    "Send answer",
                    { onAction(ChatAction.AgentQuestion(q.localId, q.requestId, picks)) },
                    primary = true,
                )
            }
        },
    )
}

/** Every question of one card: choice chips (multi-select toggles) or a field for text/number. */
@Composable
private fun QuestionRows(
    questions: List<QuestionItem>,
    picks: Map<String, List<String>>,
    answered: Boolean,
    onPick: (String, List<String>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (item in questions) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    buildString {
                        item.header?.takeIf { it.isNotEmpty() }?.let { append(it).append(" · ") }
                        append(item.question)
                        if (item.multiSelect) append("  (pick any)")
                    },
                    style = LocalTextStyle.current.copy(fontWeight = FontWeight.Medium),
                )
                val chosen = picks[item.question].orEmpty()
                if (item.kind == "text" || item.kind == "number") {
                    OutlinedTextField(
                        value = chosen.firstOrNull() ?: "",
                        onValueChange = { onPick(item.question, if (it.isEmpty()) emptyList() else listOf(it)) },
                        enabled = !answered,
                        singleLine = true,
                        keyboardOptions = if (item.kind == "number") {
                            KeyboardOptions(keyboardType = KeyboardType.Number)
                        } else {
                            KeyboardOptions.Default
                        },
                        modifier = Modifier.fillMaxWidth().testTag("question-field:${item.question}"),
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (o in item.options) {
                            val on = o.label in chosen
                            FilterChip(
                                selected = on,
                                enabled = !answered,
                                onClick = {
                                    onPick(
                                        item.question,
                                        when {
                                            !item.multiSelect -> listOf(o.label)
                                            on -> chosen - o.label
                                            else -> chosen + o.label
                                        },
                                    )
                                },
                                label = { Text(o.label) },
                                modifier = Modifier.testTag("question-option:${o.label}"),
                            )
                        }
                    }
                }
                item.options.firstOrNull { it.label in chosen }?.description?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.7f))
                }
            }
        }
    }
}

/** QU-3: every question needs an answer before the card can be sent. */
private fun allAnswered(questions: List<QuestionItem>, picks: Map<String, List<String>>): Boolean =
    questions.isNotEmpty() && questions.all { picks[it.question]?.any { v -> v.isNotBlank() } == true }

@Composable
private fun ErrorCard(card: InlineCardUi.Error, onAction: (ChatAction) -> Unit, modifier: Modifier) {
    var details by rememberSaveable(card.id) { mutableStateOf(false) }
    InlineCard(
        InlineCardKind.Error,
        card.title,
        modifier,
        onDismiss = { onAction(ChatAction.DismissCard(card.id)) },
        body = { Text(if (details && card.detail != null) "${card.body}\n\n${card.detail}" else card.body) },
        actions = {
            if (card.detail != null) InlineCardAction(if (details) "Hide details" else "Details", { details = !details }, primary = false)
            card.retry?.let { k -> InlineCardAction("Retry", { onAction(ChatAction.Retry(k, card.id)) }, primary = true) }
        },
    )
}

/** Shows the buttons only until answered; afterwards a short "Sent" state (buttons disabled, §6.9). */
@Composable
private fun InlineCardActionGate(enabled: Boolean, content: @Composable () -> Unit) {
    if (enabled) content() else Text("Answer sent…")
}

/** Markdown in a card's tone (the card sets the content color and body text style). */
@Composable
private fun cardMarkdownStyle(): MarkdownStyle {
    val fg = LocalContentColor.current
    val body = LocalTextStyle.current
    return MarkdownStyle.fromTheme().copy(body = body, textColor = fg, mutedColor = fg, blockSpacing = 4.dp, itemSpacing = 0.dp)
}

/** One readable line of a tool input for a permission prompt. */
internal fun inputSummary(toolName: String, input: JsonObject): String {
    val first = input.entries.firstOrNull { (_, v) -> v is JsonPrimitive && v.isString }
    val v = first?.let { (it.value as JsonPrimitive).content.lineSequence().firstOrNull() }
    return when {
        v == null -> toolName
        first.key.contains("path") -> BasicToolCatalog.shortPath(v)
        else -> v.take(160)
    }
}
