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

/**
 * One configurable knob. [default] is what the CLI does when the option is unset (informational).
 * [models] restricts the option to those model ids (null = every model).
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
