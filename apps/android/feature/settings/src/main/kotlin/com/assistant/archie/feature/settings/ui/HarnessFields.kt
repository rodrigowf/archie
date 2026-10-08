package com.assistant.archie.feature.settings.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.assistant.archie.feature.settings.HarnessLogic
import com.assistant.archie.feature.settings.HarnessScope
import com.assistant.archie.feature.settings.ModelPick
import com.assistant.archie.feature.settings.OptionPick
import com.assistant.archie.feature.settings.OptionState
import com.assistant.archie.feature.settings.VisibleOption
import com.assistant.core.design.components.ArchieTextField
import com.assistant.core.model.HarnessCatalogModel
import com.assistant.core.model.HarnessInfo
import com.assistant.core.model.HarnessOptionKind
import com.assistant.core.model.HarnessValue

/*
 * The model + options of one harness, rendered from its catalog (`GET /api/config/harnesses`), a
 * port of the web `HarnessFields.tsx`. Used by Settings → Agent sessions (global defaults,
 * [HarnessScope.GLOBAL]) and by the session sheet ([HarnessScope.SESSION], with a "Default (…)"
 * inherit row on every control). Every option is a select (tri-state: [inherit], CLI default,
 * values); a number option adds a field for its value, the model a field for a custom id. The rules
 * live in [HarnessLogic].
 *
 * Text fields commit live in the session sheet (it is a draft) and on Done / focus loss on the
 * global page (each commit is a PUT).
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
    ModelField(harness, scope, model, inheritedModel, enabled, onModel, note)
    for (vo in HarnessLogic.visibleOptions(catalog, effective)) {
        val key = vo.option.key
        OptionField(
            harness.id, vo, scope,
            state = if (scope == HarnessScope.SESSION) HarnessLogic.sessionOptionState(options, key) else HarnessLogic.globalOptionState(options, key),
            inherited = inheritedOptions?.get(key),
            row = row,
            enabled = enabled,
            onOption = onOption,
        )
    }
}

/** Commit on focus loss (global page): [had] (remembered) holds whether the field had focus. */
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

@Composable
private fun ColumnScope.OptionField(
    harnessId: String,
    vo: VisibleOption,
    scope: HarnessScope,
    state: OptionState,
    inherited: HarnessValue?,
    row: HarnessCatalogModel?,
    enabled: Boolean,
    onOption: (key: String, state: OptionState) -> Unit,
) {
    val option = vo.option
    val live = scope == HarnessScope.SESSION
    val focus = LocalFocusManager.current
    var customMode by remember(harnessId, option.key) { mutableStateOf(false) }
    var text by remember(harnessId, option.key) { mutableStateOf((state as? OptionState.Value)?.value?.display.orEmpty()) }
    var error by remember(harnessId, option.key) { mutableStateOf<String?>(null) }
    val hadFocus = remember { booleanArrayOf(false) }
    val items = HarnessLogic.optionItems(vo, scope, state, row, inherited)
    val value = if (customMode && option.kind == HarnessOptionKind.NUMBER) HarnessLogic.CUSTOM else HarnessLogic.optionSelectValue(option, state)
    val selectedChoice = vo.choices.firstOrNull { state is OptionState.Value && it.value == state.value.display }
    val showNumber = option.kind == HarnessOptionKind.NUMBER && value == HarnessLogic.CUSTOM
    val commitNumber = { raw: String ->
        when (val r = HarnessLogic.parseNumberInput(option, raw)) {
            is HarnessLogic.NumberInput.Error -> error = r.message
            is HarnessLogic.NumberInput.Ok -> {
                error = null
                val v = HarnessValue.Num(r.value)
                if (!(state is OptionState.Value && state.value == v)) onOption(option.key, OptionState.Value(v))
            }
        }
    }
    SelectRow(
        option.label, items, value,
        onSelect = { v ->
            when (val p = HarnessLogic.parseOptionSelect(option, v)) {
                OptionPick.Custom -> {
                    customMode = true
                    if (live && text.isNotBlank()) commitNumber(text)
                }
                is OptionPick.State -> {
                    customMode = false
                    error = null
                    onOption(option.key, p.state)
                }
            }
        },
        modifier = Modifier.testTag("harness:$harnessId:${option.key}"),
        enabled = enabled,
        supporting = selectedChoice?.description,
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
                Modifier.testTag("harness:$harnessId:${option.key}:value").onBlur(hadFocus) { if (!live) commitNumber(text) },
                errorText = error,
                supportingText = HarnessLogic.numberRange(option).takeIf { it.isNotEmpty() },
                enabled = enabled,
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
