package com.assistant.core.model

/*
 * Harness catalogs (spec 12 §6.14, §8.1): the models and options (reasoning effort, thinking, …)
 * each agent harness (Claude Code, Codex, Gemini CLI, Qwen Code) accepts, as served by
 * `GET /api/config/harnesses` (`backend/manager/harness_catalog.py`). The settings UIs render the
 * model picker and the options generically from these.
 */

/** A harness option value: `select` → [Text], `toggle` → [Flag], `number` → [Num]. */
sealed interface HarnessValue {
    data class Text(val value: String) : HarnessValue
    data class Flag(val value: Boolean) : HarnessValue
    data class Num(val value: Double) : HarnessValue

    /** The value as a select / text field shows it (`32000.0` → "32000"). */
    val display: String
        get() = when (this) {
            is Text -> value
            is Flag -> value.toString()
            is Num -> formatNumber(value)
        }

    companion object {
        fun of(v: String): HarnessValue = Text(v)
        fun of(v: Boolean): HarnessValue = Flag(v)
        fun of(v: Number): HarnessValue = Num(v.toDouble())

        /** 1024.0 → "1024", 0.5 → "0.5". */
        fun formatNumber(v: Double): String =
            if (v % 1.0 == 0.0 && v >= Long.MIN_VALUE.toDouble() && v <= Long.MAX_VALUE.toDouble()) v.toLong().toString() else v.toString()
    }
}

/** One value of a `select` option; [models] restricts it to those model ids (null = every model). */
data class HarnessChoice(
    val value: String,
    val label: String,
    val description: String? = null,
    val models: List<String>? = null,
)

enum class HarnessOptionKind(val wire: String) {
    SELECT("select"),
    TOGGLE("toggle"),
    NUMBER("number"),
    ;

    companion object {
        fun fromWire(s: String?): HarnessOptionKind? = entries.firstOrNull { it.wire == s }
    }
}

/** The control an option renders as (the catalog's `control` hint; spec 12 §8.1). */
enum class HarnessControl(val wire: String) {
    SWITCH("switch"),
    SEGMENTED("segmented"),
    LEVELS("levels"),
    SLIDER("slider"),
    DROPDOWN("dropdown"),
    ;

    companion object {
        /** null for a control this client does not know (the UI then infers one). */
        fun fromWire(s: String?): HarnessControl? = entries.firstOrNull { it.wire == s }
    }
}

/** A named special number of a `number` option (Gemini's budget: -1 = Dynamic, 0 = Off). */
data class HarnessPreset(val value: Double, val label: String, val description: String? = null)

/**
 * One configurable knob. [default] is what the CLI does when the option is unset (informational).
 * [models] restricts the option to those model ids (null = every model).
 *
 * Presentation hints (all optional; older servers send none and the UI infers a control):
 * [control] forces the control; [ordered] marks a select's choices as ordinal levels; [unit] and
 * [scale] (`linear` | `log`) describe a number; [presets] are named special numbers shown next to
 * the slider; [customMin] is the lowest value the slider / field offers (default [min]; presets may
 * lie below it); [requires] maps another option's key to the effective values it must have for this
 * option to apply (`null` in a list = unset / unknown).
 */
data class HarnessOption(
    val key: String,
    val label: String,
    val kind: HarnessOptionKind = HarnessOptionKind.SELECT,
    val choices: List<HarnessChoice> = emptyList(),
    val default: HarnessValue? = null,
    val help: String? = null,
    val models: List<String>? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val control: HarnessControl? = null,
    val ordered: Boolean = false,
    val unit: String? = null,
    val scale: String? = null,
    val presets: List<HarnessPreset> = emptyList(),
    val customMin: Double? = null,
    val requires: Map<String, List<HarnessValue?>>? = null,
)

/**
 * One row of a harness's model picker. [efforts]: the effort levels the model accepts (null = the
 * `effort` option applies unchanged, empty = no effort control). [supportsThinking] `false` hides
 * `thinking` / `thinking_*`. [source]: `builtin` | `settings` | `live` | `cli`.
 */
data class HarnessCatalogModel(
    val id: String,
    val label: String,
    val source: String = "builtin",
    val description: String? = null,
    val contextWindow: Long? = null,
    val supportsThinking: Boolean? = null,
    val supportsVision: Boolean? = null,
    val efforts: List<String>? = null,
    val defaultEffort: String? = null,
)

/** Models + options of one harness. [allowCustomModel]: ids outside [models] are accepted. */
data class HarnessCatalog(
    val provider: String,
    val models: List<HarnessCatalogModel> = emptyList(),
    val options: List<HarnessOption> = emptyList(),
    val defaultModel: String? = null,
    val allowCustomModel: Boolean = true,
    val warnings: List<String> = emptyList(),
)

/** One registered harness; [catalog] null = it lists no models or options. */
data class HarnessInfo(
    val id: String,
    val label: String,
    val description: String? = null,
    val catalog: HarnessCatalog? = null,
)
