package com.assistant.core.protocol

import com.assistant.core.model.HarnessCatalog
import com.assistant.core.model.HarnessCatalogModel
import com.assistant.core.model.HarnessChoice
import com.assistant.core.model.HarnessInfo
import com.assistant.core.model.HarnessOption
import com.assistant.core.model.HarnessOptionKind
import com.assistant.core.model.HarnessValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * Harness catalogs and option values on the wire (spec 12 §6.14, §8.1), plus the fallback for
 * older servers without `GET /api/config/harnesses` (a port of the web `services/harnessFallback.ts`).
 */

// ───────────── option values ─────────────

/** A string, boolean or finite number → its value; anything else (null, objects, arrays) → null. */
fun JsonElement?.toHarnessValue(): HarnessValue? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    if (p.isString) return HarnessValue.Text(p.content)
    p.booleanOrNull?.let { return HarnessValue.Flag(it) }
    val d = p.doubleOrNull ?: return null
    return if (d.isFinite()) HarnessValue.Num(d) else null
}

/** Numbers that are whole go out as integers (`32000`, not `32000.0`). */
fun HarnessValue.toJson(): JsonPrimitive = when (this) {
    is HarnessValue.Text -> JsonPrimitive(value)
    is HarnessValue.Flag -> JsonPrimitive(value)
    is HarnessValue.Num ->
        if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 9.0e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
}

/** Global `harness_options`: `{provider: {key: value}}`; nulls and wrong types are dropped. */
fun harnessOptionsByProvider(raw: JsonElement?): Map<String, Map<String, HarnessValue>> {
    val o = raw as? JsonObject ?: return emptyMap()
    val out = LinkedHashMap<String, Map<String, HarnessValue>>()
    for ((provider, v) in o) {
        val m = v as? JsonObject ?: continue
        val values = LinkedHashMap<String, HarnessValue>()
        for ((k, x) in m) x.toHarnessValue()?.let { values[k] = it }
        out[provider] = values
    }
    return out
}

/**
 * Session `harness_options` overlay: JSON `null` values stay (= CLI default), other non-values are
 * dropped, and an empty or missing map is `null` (= inherit every key).
 */
fun harnessOptionsOverlay(raw: JsonElement?): Map<String, HarnessValue?>? {
    val o = raw as? JsonObject ?: return null
    val out = LinkedHashMap<String, HarnessValue?>()
    for ((k, v) in o) {
        if (v is JsonNull) out[k] = null else v.toHarnessValue()?.let { out[k] = it }
    }
    return out.takeIf { it.isNotEmpty() }
}

/** `{key: value | null}` as JSON (null → JSON `null`). */
fun harnessOptionsJson(map: Map<String, HarnessValue?>): JsonObject =
    JsonObject(map.mapValues { (_, v) -> v?.toJson() ?: JsonNull })

// ───────────── catalogs ─────────────

fun HarnessChoiceDto.toModel() = HarnessChoice(value, label.ifEmpty { value }, description?.takeIf { it.isNotEmpty() }, models)

/** null for an option kind this client does not know (it is skipped, never guessed). */
fun HarnessOptionDto.toModel(): HarnessOption? {
    val k = HarnessOptionKind.fromWire(kind) ?: return null
    return HarnessOption(
        key = key,
        label = label.ifEmpty { key },
        kind = k,
        choices = choices.map { it.toModel() },
        default = default.toHarnessValue(),
        help = help?.takeIf { it.isNotEmpty() },
        models = models,
        min = min,
        max = max,
        step = step,
    )
}

fun HarnessCatalogModelDto.toModel() = HarnessCatalogModel(
    id = id,
    label = label.ifEmpty { id },
    source = source,
    description = description?.takeIf { it.isNotEmpty() },
    contextWindow = contextWindow,
    supportsThinking = supportsThinking,
    supportsVision = supportsVision,
    efforts = efforts,
    defaultEffort = defaultEffort?.takeIf { it.isNotEmpty() },
)

fun HarnessCatalogDto.toModel(fallbackProvider: String = "") = HarnessCatalog(
    provider = provider.ifEmpty { fallbackProvider },
    models = models.filter { it.id.isNotEmpty() }.map { it.toModel() },
    options = options.mapNotNull { it.toModel() },
    defaultModel = defaultModel?.takeIf { it.isNotEmpty() },
    allowCustomModel = allowCustomModel,
    warnings = warnings.filter { it.isNotEmpty() },
)

fun HarnessInfoDto.toModel() = HarnessInfo(
    id = id,
    label = label.ifEmpty { id },
    description = description?.takeIf { it.isNotEmpty() },
    catalog = catalog?.toModel(id),
)

fun HarnessesDto.toModel(): List<HarnessInfo> = harnesses.filter { it.id.isNotEmpty() }.map { it.toModel() }

// ───────────── older servers (no /api/config/harnesses) ─────────────

object HarnessFallback {
    const val NO_QWEN_MODELS = "No models listed. Run qwen once on the server to create ~/.qwen/settings.json."

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.booleanOrNull

    /** `/api/config/harness/qwen/models` rows: plain ids or `{id, display_name, context_window, supports_*}`. */
    fun qwenCatalogFromModels(raw: List<JsonElement>?): HarnessCatalog {
        val models = ArrayList<HarnessCatalogModel>()
        for (r in raw.orEmpty()) {
            val p = r as? JsonPrimitive
            if (p != null && p.isString) {
                val id = p.content
                if (id.isNotEmpty() && models.none { it.id == id }) models += HarnessCatalogModel(id, id, source = "settings")
                continue
            }
            val o = r as? JsonObject ?: continue
            val id = o.text("id")
            if (id.isEmpty() || models.any { it.id == id }) continue
            val ctx = (o["context_window"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it > 0 }?.toLong()
            models += HarnessCatalogModel(
                id = id,
                label = o.text("display_name").ifEmpty { o.text("label") }.ifEmpty { id },
                source = "settings",
                description = o.text("description").takeIf { it.isNotEmpty() },
                contextWindow = ctx,
                supportsThinking = o.flag("supports_thinking"),
                supportsVision = o.flag("supports_vision"),
            )
        }
        return HarnessCatalog(
            provider = "qwen",
            models = models,
            options = emptyList(),
            defaultModel = null,
            allowCustomModel = true,
            warnings = if (models.isEmpty()) listOf(NO_QWEN_MODELS) else emptyList(),
        )
    }

    /** Harness rows from `/api/config/providers` (+ the Qwen model list as the Qwen catalog). */
    fun harnessesFromProviders(providers: List<HarnessProviderDto>, qwenModels: List<JsonElement>?): List<HarnessInfo> =
        providers.map { p ->
            HarnessInfo(
                id = p.id,
                label = p.label.ifEmpty { p.id },
                description = p.description.takeIf { it.isNotEmpty() },
                catalog = if (p.id == "qwen" && qwenModels != null) qwenCatalogFromModels(qwenModels) else null,
            )
        }

    fun providersFromHarnesses(harnesses: List<HarnessInfo>): List<HarnessProviderDto> =
        harnesses.map { HarnessProviderDto(it.id, it.label.ifEmpty { it.id }, it.description.orEmpty()) }
}
