package com.assistant.archie.feature.settings

import com.assistant.core.model.ConfigPatch
import com.assistant.core.model.HarnessCatalog
import com.assistant.core.model.HarnessCatalogModel
import com.assistant.core.model.HarnessChoice
import com.assistant.core.model.HarnessInfo
import com.assistant.core.model.HarnessOption
import com.assistant.core.model.HarnessOptionKind
import com.assistant.core.model.HarnessValue
import com.assistant.core.model.ServerConfig
import com.assistant.core.model.SessionConfig
import kotlin.math.roundToLong

/*
 * Pure harness-configuration logic (spec 12 §6.14, §8.1), a port of the web
 * `apps/web/src/features/settings/harness.ts`: the model picker and the harness options (reasoning
 * effort, thinking, …) of every harness, rendered generically from its catalog
 * (`GET /api/config/harnesses`).
 *
 * Values have three states:
 * - **inherit** (session only): the session map is `null` or lacks the key → the global value;
 * - **CLI default**: global key absent / session key `null` → nothing is passed, the CLI decides;
 * - **a value**.
 *
 * Gating (what the UI shows for the effective model): an option with `models` is hidden for other
 * models, a choice with `models` likewise; the `effort` choices are narrowed to the model's
 * `efforts` (empty hides effort); `supportsThinking == false` hides `thinking` / `thinking_*`.
 * When the effective model is unknown (CLI default, a custom id) everything is shown.
 * No Android, no I/O: unit-tested in HarnessSettingsLogicTest.
 */

/** Where a harness control lives: the session sheet (with an inherit row) or the global page. */
enum class HarnessScope { SESSION, GLOBAL }

/** inherit (session only) · CLI default · a value. */
sealed interface OptionState {
    data object Inherit : OptionState
    data object Cli : OptionState
    data class Value(val value: HarnessValue) : OptionState
}

/** One option as shown for a model: [choices] already narrowed (select only). */
data class VisibleOption(val option: HarnessOption, val choices: List<HarnessChoice>)

/** A select value back from [HarnessLogic.parseOptionSelect]: a state, or "show the number field". */
sealed interface OptionPick {
    data class State(val state: OptionState) : OptionPick
    data object Custom : OptionPick
}

/** A model select value back from [HarnessLogic.parseModelSelect]: `model` null = inherit, "" = CLI default. */
sealed interface ModelPick {
    data class Model(val model: String?) : ModelPick
    data object Custom : ModelPick
}

object HarnessLogic {
    /** Select values that are not catalog values. */
    const val INHERIT = "__inherit__"
    const val CLI_DEFAULT = "__cli__"
    const val CUSTOM = "__custom__"

    // ───────────────────────── catalog lookups ─────────────────────────

    fun info(harnesses: List<HarnessInfo>?, id: String?): HarnessInfo? = harnesses.orEmpty().firstOrNull { it.id == id }

    fun label(harnesses: List<HarnessInfo>?, id: String): String = info(harnesses, id)?.label?.takeIf { it.isNotEmpty() } ?: id

    fun findModel(catalog: HarnessCatalog?, id: String?): HarnessCatalogModel? =
        if (catalog == null || id.isNullOrEmpty()) null else catalog.models.firstOrNull { it.id == id }

    /** The label of a model id ("" → "CLI default"; unknown ids as they are). */
    fun modelLabel(catalog: HarnessCatalog?, id: String): String {
        if (id.isEmpty()) return "CLI default"
        return findModel(catalog, id)?.label?.takeIf { it.isNotEmpty() } ?: id
    }

    /** 1_000_000 → "1M", 1_048_576 → "1M", 1_500_000 → "1.5M", 272_000 → "272K". */
    fun formatContextWindow(n: Long): String {
        if (n >= 1_000_000) {
            val m = (n / 100_000.0).roundToLong() / 10.0
            return "${HarnessValue.formatNumber(m)}M"
        }
        return "${(n / 1000.0).roundToLong()}K"
    }

    private val SOURCE_LABELS = mapOf("live" to "live list", "settings" to "CLI settings", "cli" to "from the CLI", "builtin" to "")

    /** One line under a model row: id (when the label differs), description, context, badges, source. */
    fun modelTraits(m: HarnessCatalogModel): String = buildList {
        if (m.label.isNotEmpty() && m.label != m.id) add(m.id)
        m.description?.takeIf { it.isNotEmpty() }?.let { add(it) }
        m.contextWindow?.takeIf { it > 0 }?.let { add("${formatContextWindow(it)} context") }
        if (m.supportsThinking == true) add("thinking")
        if (m.supportsVision == true) add("vision")
        val src = SOURCE_LABELS[m.source] ?: m.source
        if (src.isNotEmpty()) add(src)
    }.joinToString(" · ")

    // ───────────────────────── effective model + gating ─────────────────────────

    /** The model a session runs: its own value, else the global one ("" = CLI default). */
    fun effectiveSessionModel(sessionModel: String?, globalModel: String?): String = sessionModel ?: globalModel ?: ""

    /** The catalog row options are gated on, or null when the model is unknown (CLI default / custom id). */
    fun gatingModel(catalog: HarnessCatalog?, id: String?): HarnessCatalogModel? = findModel(catalog, id)

    private fun isThinkingKey(key: String) = key == "thinking" || key.startsWith("thinking_")

    private fun capitalize(s: String) = if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1).replace('_', ' ')

    /** The options shown for [modelId] (see the gating rules in the header). */
    fun visibleOptions(catalog: HarnessCatalog?, modelId: String?): List<VisibleOption> {
        if (catalog == null) return emptyList()
        val row = gatingModel(catalog, modelId)
        val out = ArrayList<VisibleOption>()
        for (option in catalog.options) {
            if (row != null) {
                val only = option.models
                if (only != null && row.id !in only) continue
                if (row.supportsThinking == false && isThinkingKey(option.key)) continue
            }
            var choices = option.choices
            if (row != null) {
                choices = choices.filter { c -> c.models.let { it == null || row.id in it } }
                val efforts = row.efforts
                if (option.key == "effort" && efforts != null) {
                    if (efforts.isEmpty()) continue
                    val narrowed = choices.filter { it.value in efforts }.toMutableList()
                    // A level the model lists that the option does not know yet.
                    for (lvl in efforts) if (narrowed.none { it.value == lvl }) narrowed += HarnessChoice(lvl, capitalize(lvl))
                    choices = narrowed
                }
            }
            if (option.kind == HarnessOptionKind.SELECT && choices.isEmpty()) continue
            out += VisibleOption(option, choices)
        }
        return out
    }

    // ───────────────────────── value labels ─────────────────────────

    fun valueLabel(option: HarnessOption, v: HarnessValue): String = when {
        option.kind == HarnessOptionKind.TOGGLE || v is HarnessValue.Flag -> if ((v as? HarnessValue.Flag)?.value ?: (v.display == "true")) "On" else "Off"
        option.kind == HarnessOptionKind.SELECT -> option.choices.firstOrNull { it.value == v.display }?.label ?: v.display
        else -> v.display
    }

    /** What the CLI does when unset: the model's `default_effort` for effort, else the option's `default`. */
    fun cliDefaultValue(option: HarnessOption, row: HarnessCatalogModel?): HarnessValue? {
        if (option.key == "effort") row?.defaultEffort?.let { return HarnessValue.Text(it) }
        return option.default
    }

    /** "CLI default" or "CLI default · High". */
    fun cliDefaultLabel(option: HarnessOption, row: HarnessCatalogModel?): String {
        val d = cliDefaultValue(option, row) ?: return "CLI default"
        return "CLI default · ${valueLabel(option, d)}"
    }

    /** The session's "Default (…)" row: the global value, else the CLI default. */
    fun inheritLabel(option: HarnessOption, inherited: HarnessValue?, row: HarnessCatalogModel?): String =
        "Default (${if (inherited == null) cliDefaultLabel(option, row) else valueLabel(option, inherited)})"

    // ───────────────────────── option state ─────────────────────────

    /** Session overlay: absent (or no map) → inherit, `null` → CLI default, else the value. */
    fun sessionOptionState(map: Map<String, HarnessValue?>?, key: String): OptionState {
        if (map == null || !map.containsKey(key)) return OptionState.Inherit
        return map[key]?.let { OptionState.Value(it) } ?: OptionState.Cli
    }

    /** Global map: absent or `null` → CLI default. */
    fun globalOptionState(map: Map<String, HarnessValue?>?, key: String): OptionState =
        map?.get(key)?.let { OptionState.Value(it) } ?: OptionState.Cli

    /** The global value of [key] for [provider] (null = unset). */
    fun globalOptionValue(cfg: ServerConfig, provider: String, key: String): HarnessValue? = cfg.harnessOptions[provider]?.get(key)

    /** An empty overlay is `null` (= inherit every key). */
    fun normalizeOptionsMap(map: Map<String, HarnessValue?>?): Map<String, HarnessValue?>? = map?.takeIf { it.isNotEmpty() }

    /** Structural equality; `null` and `{}` are the same (both inherit every key). */
    fun sameOptionsMap(a: Map<String, HarnessValue?>?, b: Map<String, HarnessValue?>?): Boolean =
        normalizeOptionsMap(a).orEmpty() == normalizeOptionsMap(b).orEmpty()

    /** The session overlay after setting one key (inherit removes it; an empty map becomes `null`). */
    fun withSessionOption(map: Map<String, HarnessValue?>?, key: String, state: OptionState): Map<String, HarnessValue?>? {
        val next = LinkedHashMap<String, HarnessValue?>()
        map.orEmpty().forEach { (k, v) -> if (k != key) next[k] = v }
        when (state) {
            OptionState.Inherit -> Unit
            OptionState.Cli -> next[key] = null
            is OptionState.Value -> next[key] = state.value
        }
        return normalizeOptionsMap(next)
    }

    /** `PUT /api/config` body for one global option (`null` deletes the key = CLI default). */
    fun globalOptionPatch(provider: String, key: String, state: OptionState): ConfigPatch =
        ConfigPatch(harnessOptions = mapOf(provider to mapOf(key to (state as? OptionState.Value)?.value)))

    /** `PUT /api/config` body for a global model ("" = CLI default). */
    fun globalModelPatch(provider: String, model: String): ConfigPatch = ConfigPatch(harnessModel = mapOf(provider to model))

    // ───────────────────────── option select rows ─────────────────────────

    fun optionSelectValue(option: HarnessOption, state: OptionState): String = when (state) {
        OptionState.Inherit -> INHERIT
        OptionState.Cli -> CLI_DEFAULT
        is OptionState.Value -> if (option.kind == HarnessOptionKind.NUMBER) CUSTOM else state.value.display
    }

    /** A select value back to a state; [OptionPick.Custom] = the number field should show. */
    fun parseOptionSelect(option: HarnessOption, value: String): OptionPick = when (value) {
        INHERIT -> OptionPick.State(OptionState.Inherit)
        CLI_DEFAULT -> OptionPick.State(OptionState.Cli)
        CUSTOM -> OptionPick.Custom
        else -> OptionPick.State(
            when (option.kind) {
                HarnessOptionKind.TOGGLE -> OptionState.Value(HarnessValue.Flag(value == "true"))
                HarnessOptionKind.NUMBER -> value.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { OptionState.Value(HarnessValue.Num(it)) } ?: OptionState.Cli
                HarnessOptionKind.SELECT -> OptionState.Value(HarnessValue.Text(value))
            },
        )
    }

    /** The select rows of one option: [Default (…)], CLI default, then the values. [inherited]: the global value (session). */
    fun optionItems(vo: VisibleOption, scope: HarnessScope, state: OptionState, row: HarnessCatalogModel?, inherited: HarnessValue? = null): List<Option> {
        val option = vo.option
        val items = ArrayList<Option>()
        if (scope == HarnessScope.SESSION) items += Option(INHERIT, inheritLabel(option, inherited, row))
        items += Option(CLI_DEFAULT, cliDefaultLabel(option, row), "Archie passes nothing; the CLI decides")
        when (option.kind) {
            HarnessOptionKind.TOGGLE -> { items += Option("true", "On"); items += Option("false", "Off") }
            HarnessOptionKind.NUMBER -> items += Option(CUSTOM, "Custom value…", numberRange(option).takeIf { it.isNotEmpty() })
            HarnessOptionKind.SELECT -> {
                for (c in vo.choices) items += Option(c.value, c.label, c.description)
                val cur = (state as? OptionState.Value)?.value?.display
                if (cur != null && items.none { it.id == cur }) items += Option(cur, "${valueLabel(option, HarnessValue.Text(cur))} (not for this model)", enabled = false)
            }
        }
        return items
    }

    private fun num(v: Double) = HarnessValue.formatNumber(v)

    /** "1024–128000, step 1024" (the number field's helper line). */
    fun numberRange(option: HarnessOption): String {
        val parts = ArrayList<String>()
        val min = option.min
        val max = option.max
        when {
            min != null && max != null -> parts += "${num(min)}–${num(max)}"
            min != null -> parts += "at least ${num(min)}"
            max != null -> parts += "at most ${num(max)}"
        }
        option.step?.takeIf { it != 1.0 }?.let { parts += "step ${num(it)}" }
        return parts.joinToString(", ")
    }

    sealed interface NumberInput {
        data class Ok(val value: Double) : NumberInput
        data class Error(val message: String) : NumberInput
    }

    /** Parse the number field against `min` / `max` / an integer `step`. */
    fun parseNumberInput(option: HarnessOption, text: String): NumberInput {
        val t = text.trim()
        if (t.isEmpty()) return NumberInput.Error("Enter a number")
        val n = t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return NumberInput.Error("Not a number")
        val step = option.step
        if (step != null && step % 1.0 == 0.0 && n % 1.0 != 0.0) return NumberInput.Error("Whole numbers only")
        option.min?.let { if (n < it) return NumberInput.Error("At least ${num(it)}") }
        option.max?.let { if (n > it) return NumberInput.Error("At most ${num(it)}") }
        return NumberInput.Ok(n)
    }

    // ───────────────────────── model select rows ─────────────────────────

    fun cliDefaultModelLabel(catalog: HarnessCatalog?): String =
        catalog?.defaultModel?.let { "CLI default (${modelLabel(catalog, it)})" } ?: "CLI default"

    private fun allowsCustom(catalog: HarnessCatalog?) = catalog == null || catalog.allowCustomModel

    /** [current]: `null` = inherit (session), "" = CLI default. [inherited]: the global `harness_model[provider]` (session). */
    fun modelItems(catalog: HarnessCatalog?, scope: HarnessScope, current: String?, inherited: String? = null): List<Option> {
        val items = ArrayList<Option>()
        if (scope == HarnessScope.SESSION) {
            val inh = inherited.orEmpty()
            items += Option(INHERIT, "Default (${if (inh.isNotEmpty()) modelLabel(catalog, inh) else cliDefaultModelLabel(catalog)})")
        }
        items += Option(CLI_DEFAULT, cliDefaultModelLabel(catalog), "Archie passes no model; the CLI decides")
        for (m in catalog?.models.orEmpty()) items += Option(m.id, m.label.ifEmpty { m.id }, modelTraits(m).takeIf { it.isNotEmpty() })
        if (allowsCustom(catalog)) items += Option(CUSTOM, "Custom model id…", "Any id the CLI accepts")
        if (!current.isNullOrEmpty() && findModel(catalog, current) == null && !allowsCustom(catalog)) {
            items += Option(current, "$current (unavailable)", enabled = false)
        }
        return items
    }

    /** The select value for a model ([customMode]: the user picked "Custom model id…" and has not typed yet). */
    fun modelSelectValue(catalog: HarnessCatalog?, current: String?, customMode: Boolean = false): String = when {
        customMode && allowsCustom(catalog) -> CUSTOM
        current == null -> INHERIT
        current.isEmpty() -> CLI_DEFAULT
        findModel(catalog, current) != null -> current
        allowsCustom(catalog) -> CUSTOM
        else -> current
    }

    fun parseModelSelect(value: String): ModelPick = when (value) {
        INHERIT -> ModelPick.Model(null)
        CLI_DEFAULT -> ModelPick.Model("")
        CUSTOM -> ModelPick.Custom
        else -> ModelPick.Model(value)
    }

    // ───────────────────────── session draft ─────────────────────────

    /**
     * Changing the session's harness: the model and options belong to one harness, so they reset to
     * inherit (`null`). Going back to the saved harness restores the saved values.
     */
    fun draftForProvider(saved: SessionConfig, draft: SessionDraft, next: String?, globalProvider: String?): SessionDraft {
        val out = LinkedHashMap(draft.values)
        out[SessionKey.PROVIDER] = next
        val nextEff = next ?: globalProvider
        val savedEff = saved.provider ?: globalProvider
        if (nextEff == savedEff) {
            out.remove(SessionKey.HARNESS_MODEL)
            out.remove(SessionKey.HARNESS_OPTIONS)
        } else {
            out[SessionKey.HARNESS_MODEL] = null
            out[SessionKey.HARNESS_OPTIONS] = null
        }
        return SessionDraft(out)
    }

    // ───────────────────────── summaries ─────────────────────────

    /** "Opus", "CLI default · 2 options" — the collapsed row of a harness on the global page. */
    fun harnessDefaultsSummary(catalog: HarnessCatalog?, model: String, options: Map<String, HarnessValue?>?): String {
        val set = catalog?.options.orEmpty().count { globalOptionState(options, it.key) is OptionState.Value }
        val m = if (model.isNotEmpty()) modelLabel(catalog, model) else "CLI default"
        return if (set > 0) "$m · $set ${if (set == 1) "option" else "options"}" else m
    }

    /** "3 more options do not apply to Claude Opus 5.5." (null when nothing is hidden or the model is unknown). */
    fun hiddenNote(catalog: HarnessCatalog?, modelId: String?): String? {
        val row = gatingModel(catalog, modelId) ?: return null
        val hidden = catalog!!.options.size - visibleOptions(catalog, modelId).size
        if (hidden <= 0) return null
        return "$hidden more ${if (hidden == 1) "option does" else "options do"} not apply to ${row.label}."
    }
}
