package com.assistant.archie.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.archie.feature.settings.ControlContext
import com.assistant.archie.feature.settings.HarnessControls
import com.assistant.archie.feature.settings.HarnessLogic
import com.assistant.archie.feature.settings.HarnessScope
import com.assistant.archie.feature.settings.ModelPick
import com.assistant.archie.feature.settings.OptionPick
import com.assistant.archie.feature.settings.OptionState
import com.assistant.archie.feature.settings.SegmentItem
import com.assistant.archie.feature.settings.SegmentsView
import com.assistant.archie.feature.settings.VisibleOption
import com.assistant.core.design.StateLayer
import com.assistant.core.design.components.ArchieButton
import com.assistant.core.design.components.ArchieSwitch
import com.assistant.core.design.components.ArchieTextField
import com.assistant.core.design.components.ButtonSize
import com.assistant.core.design.components.ButtonStyle
import com.assistant.core.design.components.LevelSlider
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.model.HarnessCatalogModel
import com.assistant.core.model.HarnessControl
import com.assistant.core.model.HarnessInfo
import com.assistant.core.model.HarnessOptionKind
import com.assistant.core.model.HarnessValue
import kotlin.math.roundToInt

/*
 * The model + options of one harness, rendered from its catalog (`GET /api/config/harnesses`), a
 * port of the web `HarnessFields.tsx`. Used by Settings → Agent sessions (global defaults,
 * [HarnessScope.GLOBAL]) and by the session sheet ([HarnessScope.SESSION], where "Default" inherits
 * the global value).
 *
 * The model is a select (plus a field for a custom id). Each option gets the control its catalog
 * hints ask for, or one inferred from its kind ([HarnessControls.optionControl]):
 * - **switch** — a toggle with a known default; "Use default" when set;
 * - **segmented** / **levels** — a row of segments, "Default" first (the CLI default level is
 *   dotted); wraps into pills when one row would cut a label;
 * - **slider** — a (log) slider + number field; `presets` add Default · <presets> · Custom
 *   segments and the slider shows for Custom only;
 * - **dropdown** — the select with [Default (…)], CLI default and the values.
 * An option whose `requires` is not met is disabled with the reason (its saved value is kept).
 * The session sheet also offers "Use CLI default" when the global page sets the option.
 *
 * The model id field commits live in the session sheet (it is a draft) and on Done / focus loss on
 * the global page (each commit is a PUT); number fields commit on Done / focus loss in both.
 */

/** The catalog's `warnings` (e.g. "Codex is using the shared login…"); place it outside a Section. */
@Composable
internal fun HarnessWarnings(harness: HarnessInfo?) {
    val warnings = harness?.catalog?.warnings.orEmpty()
    if (harness == null || warnings.isEmpty()) return
    Notice(NoticeTone.WARNING, "${harness.label}: check the setup", Modifier.testTag("harness-warnings:${harness.id}"), body = warnings.joinToString("\n\n"))
}

/**
 * Rows for a [Section]. [model]: session `null` = inherit, "" = CLI default. [inheritedModel] /
 * [inheritedOptions]: the global values (session scope). [options]: the session overlay (`null` =
 * inherit all) or the global `harness_options[provider]`.
 */
@Composable
internal fun ColumnScope.HarnessFields(
    harness: HarnessInfo,
    scope: HarnessScope,
    model: String?,
    inheritedModel: String?,
    options: Map<String, HarnessValue?>?,
    inheritedOptions: Map<String, HarnessValue?>?,
    enabled: Boolean,
    onModel: (String?) -> Unit,
    onOption: (key: String, state: OptionState) -> Unit,
) {
    val catalog = harness.catalog
    val effective = if (scope == HarnessScope.SESSION && model == null) inheritedModel.orEmpty() else model.orEmpty()
    val row = HarnessLogic.gatingModel(catalog, effective)
    val note = if (catalog == null) "${harness.label} lists no models or options." else HarnessLogic.hiddenNote(catalog, effective)
    val resolve = HarnessControls.makeResolver(catalog, scope, options, inheritedOptions, row)
    ModelField(harness, scope, model, inheritedModel, enabled, onModel, note)
    for (vo in HarnessLogic.visibleOptions(catalog, effective)) {
        val k = vo.option.key
        key(harness.id, k) {
            val session = scope == HarnessScope.SESSION
            val ctx = ControlContext(
                scope,
                state = if (session) HarnessLogic.sessionOptionState(options, k) else HarnessLogic.globalOptionState(options, k),
                inherited = if (session) HarnessLogic.globalOptionState(inheritedOptions, k) else null,
                row = row,
            )
            val p = OptionProps(harness.id, vo, ctx, HarnessControls.unmetRequirement(vo.option, catalog, resolve), enabled, onOption)
            when (HarnessControls.optionControl(vo)) {
                HarnessControl.SWITCH -> SwitchOption(p)
                HarnessControl.SEGMENTED, HarnessControl.LEVELS -> ChoiceOption(p)
                HarnessControl.SLIDER -> NumberOption(p)
                HarnessControl.DROPDOWN -> DropdownOption(p)
            }
        }
    }
}

/** Commit on focus loss: [had] (remembered) holds whether the field had focus. */
private fun Modifier.onBlur(had: BooleanArray, action: () -> Unit): Modifier = onFocusChanged { f ->
    if (had[0] && !f.isFocused) action()
    had[0] = f.isFocused
}

@Composable
private fun ColumnScope.ModelField(
    harness: HarnessInfo,
    scope: HarnessScope,
    model: String?,
    inheritedModel: String?,
    enabled: Boolean,
    onModel: (String?) -> Unit,
    note: String?,
) {
    val catalog = harness.catalog
    val live = scope == HarnessScope.SESSION
    val focus = LocalFocusManager.current
    var customMode by remember(harness.id) { mutableStateOf(false) }
    val isCustomId = !model.isNullOrEmpty() && HarnessLogic.findModel(catalog, model) == null
    var text by remember(harness.id) { mutableStateOf(if (isCustomId) model.orEmpty() else "") }
    val hadFocus = remember { booleanArrayOf(false) }
    val value = HarnessLogic.modelSelectValue(catalog, model, customMode)
    val items = HarnessLogic.modelItems(catalog, scope, model, inheritedModel)
    val selected: HarnessCatalogModel? = HarnessLogic.findModel(catalog, if (model == null) inheritedModel else model)
    val help = when {
        model == null && live -> "Uses the default from Settings."
        live -> "Set for this session."
        else -> "New sessions on this harness start with this model."
    } + (note?.let { " $it" } ?: "")
    val commit = { raw: String ->
        val id = raw.trim()
        if (id.isNotEmpty() && id != model) onModel(id)
    }
    SelectRow(
        "Model", items, value,
        onSelect = { v ->
            when (val p = HarnessLogic.parseModelSelect(v)) {
                ModelPick.Custom -> {
                    customMode = true
                    if (live && text.isNotBlank()) onModel(text.trim())
                }
                is ModelPick.Model -> {
                    customMode = false
                    if (p.model != model) onModel(p.model)
                }
            }
        },
        modifier = Modifier.testTag("harness:${harness.id}:model"),
        enabled = enabled,
        supporting = listOfNotNull(selected?.let(HarnessLogic::modelTraits)?.takeIf { it.isNotEmpty() }, help).joinToString("\n"),
    )
    if (value == HarnessLogic.CUSTOM) {
        FieldBlock {
            ArchieTextField(
                text,
                { t ->
                    text = t
                    if (live && t.isNotBlank()) onModel(t.trim())
                },
                "Model id",
                Modifier.testTag("harness:${harness.id}:model-id").onBlur(hadFocus) { if (!live) commit(text) },
                placeholder = catalog?.defaultModel ?: "model-id",
                supportingText = if (live) "Passed to the CLI as is." else "Passed to the CLI as is. Saved when you press Done or leave the field.",
                enabled = enabled,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (!live) commit(text)
                    focus.clearFocus()
                }),
            )
        }
    }
}

// ───────────────────────── options ─────────────────────────

private class OptionProps(
    val harnessId: String,
    val vo: VisibleOption,
    val ctx: ControlContext,
    /** Why the option does not apply now (`requires`), or null. */
    val reason: String?,
    val enabled: Boolean,
    val onOption: (key: String, state: OptionState) -> Unit,
) {
    val option get() = vo.option
    val tag get() = "harness:$harnessId:${vo.option.key}"

    /** Disabled by the sheet / a save in flight, or by an unmet `requires`. */
    val active get() = enabled && reason == null
}

/** A small text action under a control ("Use default", "Use CLI default"). */
private data class TextAction(val label: String, val tag: String, val icon: ImageVector?, val onClick: () -> Unit)

/** The text actions of [HarnessControls.resetActions] for one option. */
private fun resetActionsOf(p: OptionProps, reset: Boolean): List<TextAction> {
    val a = HarnessControls.resetActions(p.ctx, reset)
    return buildList {
        if (a.useDefault) add(TextAction("Use default", "${p.tag}:use-default", ArchieIcons.Refresh) { p.onOption(p.option.key, HarnessControls.defaultState(p.ctx.scope)) })
        if (a.useCli) add(TextAction("Use CLI default", "${p.tag}:use-cli", null) { p.onOption(p.option.key, OptionState.Cli) })
    }
}

@Composable
private fun ActionsRow(actions: List<TextAction>, enabled: Boolean) {
    if (actions.isEmpty()) return
    FlowRow(Modifier.padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (a in actions) {
            ArchieButton(a.label, a.onClick, Modifier.testTag(a.tag), style = ButtonStyle.Text, size = ButtonSize.Small, icon = a.icon, enabled = enabled)
        }
    }
}

/** One short line under a control (source, reason or error). */
@Composable
private fun SupportLine(text: String, error: Boolean = false, modifier: Modifier = Modifier) {
    val c = ArchieTheme.colors
    Text(text, modifier, style = ArchieTheme.typography.bodySmall.copy(letterSpacing = 0.sp), color = if (error) c.error else c.onSurfaceVariant)
}

/** The block of one option (surface, title + value + ⓘ, the control, one help line, text actions). */
@Composable
private fun OptionBlock(
    title: String,
    tag: String,
    enabled: Boolean,
    help: String?,
    info: String?,
    value: String? = null,
    helpIsError: Boolean = false,
    actions: List<TextAction> = emptyList(),
    actionsEnabled: Boolean = enabled,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ArchieTheme.colors
    val alpha = if (enabled) 1f else StateLayer.DisabledContent
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(c.surfaceContainer)
            .testTag(tag)
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = if (actions.isEmpty()) 14.dp else 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = ArchieTheme.typography.titleMedium.copy(lineHeight = 22.sp, letterSpacing = 0.sp), color = c.onSurface.copy(alpha = alpha))
            if (value != null) {
                Text(value, Modifier.padding(start = 8.dp), style = ArchieTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = c.primary.copy(alpha = alpha))
            }
            if (info != null) InfoButton(title, info)
        }
        Column(Modifier.padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        if (help != null) SupportLine(help, helpIsError, Modifier.padding(end = 6.dp))
        ActionsRow(actions, actionsEnabled)
    }
}

/** A switch block: the whole header row toggles (title + help + switch), text actions below. */
@Composable
private fun SwitchBlock(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    help: String?,
    info: String?,
    enabled: Boolean,
    actions: List<TextAction>,
    switchTag: String,
) {
    val c = ArchieTheme.colors
    val alpha = if (enabled) 1f else StateLayer.DisabledContent
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(c.surfaceContainer).testTag(tag)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .testTag(switchTag)
                .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = if (actions.isEmpty()) 12.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = ArchieTheme.typography.titleMedium.copy(lineHeight = 22.sp, letterSpacing = 0.sp), color = c.onSurface.copy(alpha = alpha))
                if (help != null) HelpLine(help, info)
            }
            ArchieSwitch(checked, null, enabled = enabled)
        }
        if (actions.isNotEmpty()) Box(Modifier.padding(start = 12.dp, bottom = 6.dp)) { ActionsRow(actions, enabled = true) }
    }
}

@Composable
private fun SwitchOption(p: OptionProps) {
    val option = p.option
    SwitchBlock(
        option.label,
        checked = HarnessControls.resolvedValue(option, p.ctx) == HarnessValue.Flag(true),
        onCheckedChange = { p.onOption(option.key, OptionState.Value(HarnessValue.Flag(it))) },
        tag = p.tag,
        help = p.reason ?: HarnessControls.sourceLine(option, p.ctx),
        info = option.help,
        enabled = p.active,
        actions = resetActionsOf(p, reset = true),
        switchTag = "${p.tag}:switch",
    )
}

@Composable
private fun ChoiceOption(p: OptionProps) {
    val option = p.option
    val view = HarnessControls.choiceSegments(p.vo, p.ctx)
    OptionBlock(
        option.label, p.tag, p.active,
        help = p.reason ?: HarnessControls.choiceHelp(p.vo, p.ctx),
        info = option.help,
        actions = resetActionsOf(p, reset = false),
    ) {
        Segments(view, p.active, p.tag, option.label) { v ->
            if (v != view.selected) p.onOption(option.key, HarnessControls.parseSegment(option, p.ctx.scope, v))
        }
    }
}

@Composable
private fun NumberOption(p: OptionProps) {
    val option = p.option
    val ctx = p.ctx
    val focus = LocalFocusManager.current
    val scale = HarnessControls.numberScale(option)
    val presets = HarnessControls.presetsOf(option)
    val segments = if (presets.isNotEmpty()) HarnessControls.numberSegments(option, ctx) else null
    val resolved = (HarnessControls.resolvedValue(option, ctx) as? HarnessValue.Num)?.value
    val explicit = ((ctx.state as? OptionState.Value)?.value as? HarnessValue.Num)?.value
    // A new saved value (slider, preset, reset, another device) replaces the field's text.
    var text by remember(explicit) { mutableStateOf(explicit?.let(HarnessValue::formatNumber).orEmpty()) }
    var error by remember(explicit) { mutableStateOf<String?>(null) }
    // Done then focus loss both commit: send a value once until the saved value catches up.
    var sent by remember(explicit) { mutableStateOf<Double?>(null) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val hadFocus = remember { booleanArrayOf(false) }

    val custom = segments == null || segments.selected == HarnessControls.CUSTOM_SEG
    val shownNumber = resolved?.takeIf { r -> presets.none { it.value == r } }
    val sliderValue = shownNumber ?: HarnessControls.customStart(option, scale)
    val commit = { n: Double ->
        error = null
        text = HarnessValue.formatNumber(n)
        if (explicit != n && sent != n) {
            sent = n
            p.onOption(option.key, OptionState.Value(HarnessValue.Num(n)))
        }
    }
    val commitText = {
        if (text.isBlank() && explicit == null) {
            error = null
        } else {
            when (val r = HarnessControls.parseNumberField(option, text)) {
                is HarnessLogic.NumberInput.Error -> error = r.message
                is HarnessLogic.NumberInput.Ok -> commit(r.value)
            }
        }
    }
    val live = if (drag != null && scale != null) HarnessControls.fromPosition(scale, drag!!.toDouble()) else null
    val valueText = (live ?: resolved)?.let { HarnessControls.displayValue(option, HarnessValue.Num(it)) }

    OptionBlock(
        option.label, p.tag, p.active,
        help = error ?: p.reason ?: HarnessControls.sourceLine(option, ctx),
        helpIsError = error != null,
        info = option.help,
        value = valueText,
        actions = resetActionsOf(p, reset = segments == null),
    ) {
        if (segments != null) {
            Segments(segments, p.active, p.tag, option.label) { v ->
                if (v == segments.selected) return@Segments
                if (v == HarnessControls.CUSTOM_SEG) {
                    val inRange = shownNumber != null && (scale == null || (shownNumber >= scale.lo && shownNumber <= scale.hi))
                    commit(if (inRange) shownNumber!! else HarnessControls.customStart(option, scale))
                } else {
                    p.onOption(option.key, HarnessControls.parseSegment(option, ctx.scope, v))
                }
            }
        }
        if (custom) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (scale != null) {
                    val range = HarnessControls.sliderRange(scale)
                    val stepsCount = ((scale.hi - scale.lo) / scale.step).roundToInt()
                    val steps = if (!scale.log && stepsCount in 2..50) stepsCount - 1 else 0
                    val unset = explicit == null && shownNumber == null
                    LevelSlider(
                        value = drag ?: HarnessControls.toPosition(scale, sliderValue).toFloat(),
                        onValueChange = { drag = it },
                        modifier = Modifier.weight(1f).testTag("${p.tag}:slider").alpha(if (unset) 0.6f else 1f),
                        valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                        steps = steps,
                        onValueChangeFinished = {
                            val d = drag
                            drag = null
                            if (d != null) commit(HarnessControls.fromPosition(scale, d.toDouble()))
                        },
                        valueLabel = { HarnessControls.formatCompact(HarnessControls.fromPosition(scale, it.toDouble())) },
                        enabled = p.active,
                    )
                }
                val signed = (scale?.lo ?: option.min ?: 0.0) < 0 || presets.any { it.value < 0 }
                val decimal = (option.step ?: 1.0) % 1.0 != 0.0
                ArchieTextField(
                    text,
                    { t ->
                        text = t
                        error = null
                    },
                    option.unit?.replaceFirstChar { it.uppercase() } ?: "Value",
                    (if (scale != null) Modifier.width(128.dp) else Modifier).testTag("${p.tag}:value").onBlur(hadFocus) { commitText() },
                    placeholder = shownNumber?.let(HarnessValue::formatNumber) ?: "Default",
                    errorText = null,
                    enabled = p.active,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when {
                            signed -> KeyboardType.Text
                            decimal -> KeyboardType.Decimal
                            else -> KeyboardType.Number
                        },
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        commitText()
                        focus.clearFocus()
                    }),
                )
            }
            HarnessControls.rangeHint(option).takeIf { it.isNotEmpty() }?.let { SupportLine(it) }
        }
    }
}

/** The select of an option (tri-state rows); a number adds a field for a custom value. */
@Composable
private fun DropdownOption(p: OptionProps) {
    val option = p.option
    val (scope, state, inherited, row) = p.ctx
    val live = scope == HarnessScope.SESSION
    val focus = LocalFocusManager.current
    var customMode by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf((state as? OptionState.Value)?.value?.display.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val hadFocus = remember { booleanArrayOf(false) }
    val items = HarnessLogic.optionItems(p.vo, scope, state, row, (inherited as? OptionState.Value)?.value)
    val value = if (customMode && option.kind == HarnessOptionKind.NUMBER) HarnessLogic.CUSTOM else HarnessLogic.optionSelectValue(option, state)
    val selectedChoice = p.vo.choices.firstOrNull { state is OptionState.Value && it.value == state.value.display }
    val showNumber = option.kind == HarnessOptionKind.NUMBER && value == HarnessLogic.CUSTOM
    val commitNumber = { raw: String ->
        when (val r = HarnessLogic.parseNumberInput(option, raw)) {
            is HarnessLogic.NumberInput.Error -> error = r.message
            is HarnessLogic.NumberInput.Ok -> {
                error = null
                val v = HarnessValue.Num(r.value)
                if (!(state is OptionState.Value && state.value == v)) p.onOption(option.key, OptionState.Value(v))
            }
        }
    }
    SelectRow(
        option.label, items, value,
        onSelect = { v ->
            when (val pick = HarnessLogic.parseOptionSelect(option, v)) {
                OptionPick.Custom -> {
                    customMode = true
                    if (live && text.isNotBlank()) commitNumber(text)
                }
                is OptionPick.State -> {
                    customMode = false
                    error = null
                    p.onOption(option.key, pick.state)
                }
            }
        },
        modifier = Modifier.testTag(p.tag),
        enabled = p.active,
        supporting = p.reason ?: selectedChoice?.description,
        info = option.help,
    )
    if (showNumber) {
        val signed = (option.min ?: -1.0) < 0
        FieldBlock {
            ArchieTextField(
                text,
                { t ->
                    text = t
                    if (live) commitNumber(t) else error = null
                },
                option.label,
                Modifier.testTag("${p.tag}:value").onBlur(hadFocus) { if (!live) commitNumber(text) },
                errorText = error,
                supportingText = HarnessLogic.numberRange(option).takeIf { it.isNotEmpty() },
                enabled = p.active,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (signed) KeyboardType.Text else KeyboardType.Number,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    if (!live) commitNumber(text)
                    focus.clearFocus()
                }),
            )
        }
    }
}

// ───────────────────────── segments ─────────────────────────

/**
 * A single-choice row of segments with radio semantics (web `SegmentedButton wrap="auto"`): one
 * M3 segmented row when every label fits its (equal) share of the width, otherwise the segments
 * wrap into pills (FlowRow) instead of cutting labels. [SegmentsView.selected] null selects none.
 */
@Composable
private fun Segments(view: SegmentsView, enabled: Boolean, tag: String, label: String, onSelect: (String) -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = ArchieTheme.typography.labelLarge
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().semantics { contentDescription = label }) {
        val widest = view.segments.maxOfOrNull { s ->
            val text = with(density) { measurer.measure(s.label, style).size.width.toDp() }
            // label + check icon + gap + 12 dp padding each side (+ the dot) + borders
            text + 18.dp + 8.dp + 24.dp + (if (s.dot) 10.dp else 0.dp) + 2.dp
        } ?: 0.dp
        val oneRow = widest * view.segments.size <= maxWidth
        if (oneRow) SegmentRow(view, enabled, tag, onSelect) else SegmentPills(view, enabled, tag, onSelect)
    }
}

@Composable
private fun SegmentLabel(s: SegmentItem, tag: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (s.dot) {
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .size(6.dp)
                    .background(color, CircleShape)
                    .testTag("$tag:dot:${s.value}")
                    .semantics { contentDescription = "CLI default" },
            )
        }
        Text(s.label, style = ArchieTheme.typography.labelLarge, color = color, maxLines = 1)
    }
}

@Composable
private fun SegmentRow(view: SegmentsView, enabled: Boolean, tag: String, onSelect: (String) -> Unit) {
    val c = ArchieTheme.colors
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        view.segments.forEachIndexed { i, s ->
            val active = s.value == view.selected
            val on = enabled && !s.disabled
            SegmentedButton(
                selected = active,
                onClick = { onSelect(s.value) },
                shape = SegmentedButtonDefaults.itemShape(i, view.segments.size),
                modifier = Modifier.testTag("$tag:seg:${s.value}"),
                enabled = on,
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = c.secondaryContainer,
                    activeContentColor = c.onSecondaryContainer,
                    activeBorderColor = c.outline,
                    inactiveContainerColor = c.surface.copy(alpha = 0f),
                    inactiveContentColor = c.onSurface,
                    inactiveBorderColor = c.outline,
                ),
                icon = { if (active) ArchieIcon(ArchieIcons.Check, null, size = 18.dp) },
                label = {
                    val base = if (active) c.onSecondaryContainer else c.onSurface
                    SegmentLabel(s, tag, if (on) base else base.copy(alpha = StateLayer.DisabledContent))
                },
            )
        }
    }
}

@Composable
private fun SegmentPills(view: SegmentsView, enabled: Boolean, tag: String, onSelect: (String) -> Unit) {
    val c = ArchieTheme.colors
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (s in view.segments) {
            val active = s.value == view.selected
            val on = enabled && !s.disabled
            val base = if (active) c.onSecondaryContainer else c.onSurface
            val color = if (on) base else base.copy(alpha = StateLayer.DisabledContent)
            val shape = RoundedCornerShape(50)
            Row(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(shape)
                    .border(1.dp, if (on || active) c.outline else c.outline.copy(alpha = 0.38f), shape)
                    .background(if (active) c.secondaryContainer else Color.Transparent)
                    .selectable(selected = active, enabled = on, role = Role.RadioButton) { onSelect(s.value) }
                    .testTag("$tag:seg:${s.value}")
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (active) ArchieIcon(ArchieIcons.Check, null, size = 18.dp, tint = color)
                SegmentLabel(s, tag, color)
            }
        }
    }
}

// ───────────────────────── Claude in Chrome ─────────────────────────

/**
 * `chrome_extension`: Claude Code's `--chrome` (Anthropic's Claude in Chrome). Claude Code only,
 * so it sits in the Claude Code block (global page) / the harness section when the session runs
 * Claude (sheet). [value]: global → the saved flag; session → `null` = inherit [inherited].
 */
@Composable
internal fun ClaudeInChromeField(scope: HarnessScope, value: Boolean?, inherited: Boolean, enabled: Boolean, onChange: (Boolean?) -> Unit) {
    val session = scope == HarnessScope.SESSION
    SwitchBlock(
        "Claude in Chrome",
        checked = value ?: inherited,
        onCheckedChange = { onChange(it) },
        tag = "chrome-field",
        help = when {
            !session -> "Starts Claude sessions with the --chrome flag."
            value == null -> "Default from Settings (${if (inherited) "On" else "Off"})"
            else -> "Set for this session"
        },
        info = "Anthropic's Claude-in-Chrome integration (needs an Anthropic login). Archie's own browser extension (browser-control) does not need this.",
        enabled = enabled,
        actions = if (session && value != null) listOf(TextAction("Use default", "chrome-use-default", ArchieIcons.Refresh) { onChange(null) }) else emptyList(),
        switchTag = "chrome",
    )
}
