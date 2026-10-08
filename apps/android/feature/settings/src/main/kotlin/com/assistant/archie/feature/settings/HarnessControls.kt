package com.assistant.archie.feature.settings

import com.assistant.core.model.HarnessCatalog
import com.assistant.core.model.HarnessCatalogModel
import com.assistant.core.model.HarnessControl
import com.assistant.core.model.HarnessOption
import com.assistant.core.model.HarnessOptionKind
import com.assistant.core.model.HarnessPreset
import com.assistant.core.model.HarnessValue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * Pure decisions behind the harness option controls (`ui/HarnessFields.kt`), a port of the web
 * `apps/web/src/features/settings/harnessControls.ts`: which control an option renders as, the
 * value it resolves to, whether it applies (`requires`), the segments of a segmented / levels row,
 * the slider's (log) scale and number formatting.
 *
 * The catalog may carry presentation hints (`backend/manager/harness_catalog.py` `HarnessOption`:
 * `control`, `ordered`, `unit`, `scale`, `presets`, `custom_min`, `requires`); older servers send
 * none and the control is inferred:
 *
 * | kind   | condition                       | control                         |
 * |--------|---------------------------------|---------------------------------|
 * | toggle | `default` is true/false         | switch                          |
 * | toggle | no `default` (model-dependent)  | segmented (Default · On · Off)  |
 * | select | `ordered`                       | levels                          |
 * | select | ≤ 4 visible choices             | segmented                       |
 * | select | otherwise                       | dropdown                        |
 * | number | any                             | slider (+ number field)         |
 *
 * No Android, no I/O: unit-tested in HarnessControlsTest.
 */

/** Where a control's value comes from: this scope's [state], the global state of the key ([inherited], session only), the effective model's [row]. */
data class ControlContext(
    val scope: HarnessScope,
    val state: OptionState,
    val inherited: OptionState? = null,
    val row: HarnessCatalogModel? = null,
)

/** One segment of a segmented / levels / presets row. [dot] marks the CLI default level. */
data class SegmentItem(
    val value: String,
    val label: String,
    val dot: Boolean = false,
    val title: String? = null,
    val disabled: Boolean = false,
    val description: String? = null,
)

/** [selected] null = none (a session forcing the CLI default over a global value). */
data class SegmentsView(val segments: List<SegmentItem>, val selected: String?)

/** The text actions under a control: "Use default" and (session) "Use CLI default". */
data class ResetActions(val useDefault: Boolean, val useCli: Boolean)

/** The slider's scale: [lo] = `custom_min ?: min`, [step] > 0, [log] only when lo > 0. */
data class NumberScale(val lo: Double, val hi: Double, val step: Double, val log: Boolean)

object HarnessControls {
    /** A select with at most this many visible choices (and no hint) renders as segments. */
    const val SEGMENTED_MAX = 4

    /** The first segment: unset (global: CLI default; session: inherit the global value). */
    const val UNSET = "__unset__"

    /** The number control's "Custom" segment. */
    const val CUSTOM_SEG = "__custom__"
    private const val PRESET_PREFIX = "preset:"

    /** Positions of a log slider (its native range is 0…LOG_POSITIONS). */
    const val LOG_POSITIONS = 1000

    private val INHERIT: OptionState = OptionState.Inherit
    private val CLI: OptionState = OptionState.Cli

    // ───────────────────────── control ─────────────────────────

    private fun fits(control: HarnessControl, kind: HarnessOptionKind): Boolean = when (control) {
        HarnessControl.SWITCH -> kind == HarnessOptionKind.TOGGLE
        HarnessControl.SLIDER -> kind == HarnessOptionKind.NUMBER
        HarnessControl.DROPDOWN -> true
        HarnessControl.SEGMENTED, HarnessControl.LEVELS -> kind != HarnessOptionKind.NUMBER
    }

    /** The control of an option: its `control` hint when it suits the kind, else inferred (table above). */
    fun optionControl(vo: VisibleOption): HarnessControl {
        val option = vo.option
        option.control?.let { if (fits(it, option.kind)) return it }
        return when (option.kind) {
            HarnessOptionKind.TOGGLE -> if (option.default is HarnessValue.Flag) HarnessControl.SWITCH else HarnessControl.SEGMENTED
            HarnessOptionKind.NUMBER -> HarnessControl.SLIDER
            HarnessOptionKind.SELECT -> when {
                option.ordered -> HarnessControl.LEVELS
                vo.choices.size <= SEGMENTED_MAX -> HarnessControl.SEGMENTED
                else -> HarnessControl.DROPDOWN
            }
        }
    }

    // ───────────────────────── values ─────────────────────────

    /** The value a session would run with: session value → global value → CLI default → null (unknown). */
    fun resolvedValue(option: HarnessOption, ctx: ControlContext): HarnessValue? {
        (ctx.state as? OptionState.Value)?.let { return it.value }
        if (ctx.scope == HarnessScope.SESSION && ctx.state == OptionState.Inherit) (ctx.inherited as? OptionState.Value)?.let { return it.value }
        return HarnessLogic.cliDefaultValue(option, ctx.row)
    }

    /** The global value of the key in session scope (null = unset). */
    private fun inheritedValue(ctx: ControlContext): HarnessValue? =
        if (ctx.scope == HarnessScope.SESSION) (ctx.inherited as? OptionState.Value)?.value else null

    /** Resolve every key of a catalog for one scope (the `requires` lookups). */
    fun makeResolver(
        catalog: HarnessCatalog?,
        scope: HarnessScope,
        options: Map<String, HarnessValue?>?,
        inheritedOptions: Map<String, HarnessValue?>?,
        row: HarnessCatalogModel?,
    ): (String) -> HarnessValue? = { key ->
        val option = catalog?.options?.firstOrNull { it.key == key }
        if (option == null) {
            null
        } else {
            val session = scope == HarnessScope.SESSION
            val state = if (session) HarnessLogic.sessionOptionState(options, key) else HarnessLogic.globalOptionState(options, key)
            val inherited = if (session) HarnessLogic.globalOptionState(inheritedOptions, key) else null
            resolvedValue(option, ControlContext(scope, state, inherited, row))
        }
    }

    /**
     * Why the option does not apply now ("Applies when Thinking is Fixed budget"), or null when it
     * does. Keys the catalog does not know are ignored; `null` in a list = unset / unknown.
     */
    fun unmetRequirement(option: HarnessOption, catalog: HarnessCatalog?, resolve: (String) -> HarnessValue?): String? {
        val req = option.requires ?: return null
        for ((key, allowed) in req) {
            val other = catalog?.options?.firstOrNull { it.key == key } ?: continue
            val v = resolve(key)
            if (allowed.any { it == v }) continue
            val named = allowed.filterNotNull().map { displayValue(other, it) }
            return if (named.isNotEmpty()) "Applies when ${other.label} is ${joinOr(named)}" else "Applies when ${other.label} is unset"
        }
        return null
    }

    private fun joinOr(xs: List<String>): String =
        if (xs.size <= 1) xs.firstOrNull().orEmpty() else "${xs.dropLast(1).joinToString(", ")} or ${xs.last()}"

    // ───────────────────────── labels ─────────────────────────

    /** Decimals of a step: 0.1 → 1, 0.25 → 2, 1024 → 0. */
    fun decimalsOf(step: Double?): Int {
        if (step == null || !step.isFinite() || step % 1.0 == 0.0) return 0
        val s = step.toBigDecimal().stripTrailingZeros().toPlainString()
        val i = s.indexOf('.')
        return if (i < 0) 0 else min(6, s.length - i - 1)
    }

    /** 16000 → "16,000"; 0.7 (step 0.1) → "0.7"; -1 → "-1". */
    fun formatNumber(n: Double, step: Double? = null): String {
        val d = decimalsOf(step)
        val fixed = String.format(Locale.US, "%.${d}f", abs(n))
        val dot = fixed.indexOf('.')
        val int = if (dot < 0) fixed else fixed.substring(0, dot)
        val frac = if (dot < 0) "" else fixed.substring(dot)
        val grouped = StringBuilder()
        for (i in int.indices) {
            if (i > 0 && (int.length - i) % 3 == 0) grouped.append(',')
            grouped.append(int[i])
        }
        val negative = n < 0 && fixed.toDouble() != 0.0
        return "${if (negative) "-" else ""}$grouped$frac"
    }

    /** 16000 → "16k", 16384 → "16.4k", 1_000_000 → "1M", 512 → "512". */
    fun formatCompact(n: Double): String {
        val a = abs(n)
        val sign = if (n < 0) "-" else ""
        fun one(x: Double): String {
            val r = Math.round(x * 10) / 10.0
            return if (r % 1.0 == 0.0) r.toLong().toString() else String.format(Locale.US, "%.1f", r)
        }
        return when {
            a >= 1_000_000 -> "$sign${one(a / 1_000_000)}M"
            a >= 1000 -> "$sign${one(a / 1000)}k"
            else -> HarnessValue.formatNumber(n)
        }
    }

    /** The valid presets of a number option (the mapper already dropped malformed ones). */
    fun presetsOf(option: HarnessOption): List<HarnessPreset> =
        if (option.kind != HarnessOptionKind.NUMBER) emptyList() else option.presets.filter { it.value.isFinite() }

    /** A value as the UI shows it: choice label, On/Off, a preset's label, or "16,000 tokens". */
    fun displayValue(option: HarnessOption, v: HarnessValue): String = when {
        v is HarnessValue.Flag -> if (v.value) "On" else "Off"
        option.kind == HarnessOptionKind.SELECT -> option.choices.firstOrNull { HarnessValue.Text(it.value) == v }?.label ?: v.display
        v is HarnessValue.Num -> presetsOf(option).firstOrNull { it.value == v.value }?.label
            ?: option.unit?.let { "${formatNumber(v.value, option.step)} $it" }
            ?: formatNumber(v.value, option.step)
        else -> v.display
    }

    /**
     * Where the shown value comes from (the control's supporting line):
     * global — "CLI default · On" / "Overrides the CLI default (On)";
     * session — "Default from Settings (Off)" / "Default (CLI default · On)" /
     * "CLI default for this session (On)" / "Set for this session".
     */
    fun sourceLine(option: HarnessOption, ctx: ControlContext): String {
        val dl = HarnessLogic.cliDefaultValue(option, ctx.row)?.let { displayValue(option, it) }
        if (ctx.scope == HarnessScope.GLOBAL) {
            if (ctx.state is OptionState.Value) return if (dl == null) "Overrides the CLI default" else "Overrides the CLI default ($dl)"
            return if (dl == null) "CLI default" else "CLI default · $dl"
        }
        if (ctx.state is OptionState.Value) return "Set for this session"
        if (ctx.state == OptionState.Cli) return if (dl == null) "CLI default for this session" else "CLI default for this session ($dl)"
        inheritedValue(ctx)?.let { return "Default from Settings (${displayValue(option, it)})" }
        return if (dl == null) "Default (CLI default)" else "Default (CLI default · $dl)"
    }

    /**
     * The text actions under a control: "Use default" (`reset` controls only — switch, slider
     * without presets — whose state is not already the unset one: global → drop the key, session →
     * inherit), and in the session sheet "Use CLI default" whenever the global page sets the option.
     */
    fun resetActions(ctx: ControlContext, reset: Boolean): ResetActions = ResetActions(
        useDefault = reset && (if (ctx.scope == HarnessScope.GLOBAL) ctx.state is OptionState.Value else ctx.state != OptionState.Inherit),
        useCli = ctx.scope == HarnessScope.SESSION && ctx.inherited is OptionState.Value && ctx.state != OptionState.Cli,
    )

    /** The state "Use default" writes: global → CLI default (drop the key), session → inherit. */
    fun defaultState(scope: HarnessScope): OptionState = if (scope == HarnessScope.GLOBAL) CLI else INHERIT

    // ───────────────────────── segmented / levels ─────────────────────────

    /** Which unset state the "Default" segment stands for; none for a session forcing the CLI default over a global value. */
    private fun unsetSelected(ctx: ControlContext): String? = when (ctx.state) {
        OptionState.Inherit -> UNSET
        OptionState.Cli -> if (ctx.scope == HarnessScope.SESSION && inheritedValue(ctx) != null) null else UNSET
        is OptionState.Value -> null
    }

    /** The string a segment carries for a value ("true", "high", "16000"). */
    private fun segValue(v: HarnessValue): String = v.display

    /** Segments of a toggle / select: Default, then On · Off or the visible choices (CLI default dotted). */
    fun choiceSegments(vo: VisibleOption, ctx: ControlContext): SegmentsView {
        val option = vo.option
        val d = HarnessLogic.cliDefaultValue(option, ctx.row)
        val segments = ArrayList<SegmentItem>()
        segments += SegmentItem(UNSET, "Default")
        fun item(value: String, label: String, v: HarnessValue, description: String? = null): SegmentItem {
            val isDefault = d != null && d == v
            return SegmentItem(value, label, dot = isDefault, title = if (isDefault) "CLI default" else null, description = description)
        }
        if (option.kind == HarnessOptionKind.TOGGLE) {
            segments += item("true", "On", HarnessValue.Flag(true))
            segments += item("false", "Off", HarnessValue.Flag(false))
        } else {
            for (c in vo.choices) segments += item(c.value, c.label, HarnessValue.Text(c.value), c.description)
        }
        val st = ctx.state as? OptionState.Value ?: return SegmentsView(segments, unsetSelected(ctx))
        val cur = segValue(st.value)
        if (segments.none { it.value == cur }) {
            segments += SegmentItem(cur, displayValue(option, st.value), disabled = true, title = "Not for this model")
        }
        return SegmentsView(segments, cur)
    }

    /** A segment back to a state ([UNSET] → global: CLI default, session: inherit). */
    fun parseSegment(option: HarnessOption, scope: HarnessScope, value: String): OptionState {
        if (value == UNSET) return defaultState(scope)
        return when (option.kind) {
            HarnessOptionKind.TOGGLE -> OptionState.Value(HarnessValue.Flag(value == "true"))
            HarnessOptionKind.NUMBER -> {
                val n = if (value.startsWith(PRESET_PREFIX)) value.substring(PRESET_PREFIX.length).toDoubleOrNull()?.takeIf { it.isFinite() } else null
                if (n != null) OptionState.Value(HarnessValue.Num(n)) else defaultState(scope)
            }
            HarnessOptionKind.SELECT -> OptionState.Value(HarnessValue.Text(value))
        }
    }

    /** The supporting line of a segmented / levels row: the selected choice's description, else where the value comes from. */
    fun choiceHelp(vo: VisibleOption, ctx: ControlContext): String {
        val st = ctx.state
        if (st is OptionState.Value) {
            val choice = vo.choices.firstOrNull { HarnessValue.Text(it.value) == st.value }
            choice?.description?.let { return it }
            if (vo.option.kind == HarnessOptionKind.SELECT && choice == null) return "${displayValue(vo.option, st.value)} is not available for this model"
        }
        return sourceLine(vo.option, ctx)
    }

    // ───────────────────────── numbers ─────────────────────────

    /** The slider's scale, or null when the range is unknown (field only). Log needs lo > 0. */
    fun numberScale(option: HarnessOption): NumberScale? {
        val lo = option.customMin?.takeIf { it.isFinite() } ?: option.min
        val hi = option.max
        if (lo == null || hi == null || !lo.isFinite() || !hi.isFinite() || hi <= lo) return null
        val step = option.step?.takeIf { it > 0 } ?: 1.0
        return NumberScale(lo, hi, step, log = option.scale == "log" && lo > 0)
    }

    private fun clamp(n: Double, lo: Double, hi: Double) = min(hi, max(lo, n))

    private fun roundTo(n: Double, decimals: Int): Double {
        val f = 10.0.pow(decimals)
        return Math.round(n * f) / f
    }

    /** Two significant digits: 1371 → 1400, 15360 → 15000, 128 → 130. */
    fun niceRound(v: Double): Double {
        if (v == 0.0 || !v.isFinite()) return v
        val mag = 10.0.pow(floor(log10(abs(v))) - 1)
        return Math.round(v / mag) * mag
    }

    /**
     * Snap a value onto the scale. Linear: `lo + k·step`. Log: two significant digits, then a
     * multiple of `step` (so 1024-step budgets land on 2048, 16384 …); the ends stay exact.
     */
    fun snapNumber(s: NumberScale, v: Double): Double {
        if (!v.isFinite()) return s.lo
        if (v <= s.lo) return s.lo
        if (v >= s.hi) return s.hi
        val d = decimalsOf(s.step)
        if (!s.log) return clamp(roundTo(s.lo + Math.round((v - s.lo) / s.step) * s.step, d), s.lo, s.hi)
        var n = niceRound(v)
        if (s.step > 1 || d > 0) n = roundTo(Math.round(n / s.step) * s.step, d)
        return clamp(n, s.lo, s.hi)
    }

    /** Value → slider position (log: 0…[LOG_POSITIONS]; linear: the value itself). */
    fun toPosition(s: NumberScale, v: Double): Double {
        val x = clamp(v, s.lo, s.hi)
        if (!s.log) return x
        return Math.round(((ln(x) - ln(s.lo)) / (ln(s.hi) - ln(s.lo))) * LOG_POSITIONS).toDouble()
    }

    /** Slider position → snapped value. */
    fun fromPosition(s: NumberScale, pos: Double): Double {
        if (!s.log) return snapNumber(s, pos)
        val p = clamp(pos, 0.0, LOG_POSITIONS.toDouble())
        if (p <= 0) return s.lo
        if (p >= LOG_POSITIONS) return s.hi
        return snapNumber(s, exp(ln(s.lo) + (p / LOG_POSITIONS) * (ln(s.hi) - ln(s.lo))))
    }

    /** The slider's native range: log → 0…[LOG_POSITIONS]; linear → lo…hi. */
    fun sliderRange(s: NumberScale): ClosedFloatingPointRange<Double> = if (s.log) 0.0..LOG_POSITIONS.toDouble() else s.lo..s.hi

    /** Where "Custom" starts: the option's default when it is in range, else the lowest custom value. */
    fun customStart(option: HarnessOption, s: NumberScale?): Double {
        val d = (option.default as? HarnessValue.Num)?.value
        if (d != null && (s == null || (d >= s.lo && d <= s.hi))) return d
        return s?.lo ?: option.min ?: 0.0
    }

    /** Segments of a number with presets: Default · <presets> · Custom. */
    fun numberSegments(option: HarnessOption, ctx: ControlContext): SegmentsView {
        val presets = presetsOf(option)
        val segments = ArrayList<SegmentItem>()
        segments += SegmentItem(UNSET, "Default")
        for (p in presets) segments += SegmentItem("$PRESET_PREFIX${HarnessValue.formatNumber(p.value)}", p.label, title = p.description, description = p.description)
        segments += SegmentItem(CUSTOM_SEG, "Custom")
        val st = ctx.state as? OptionState.Value ?: return SegmentsView(segments, unsetSelected(ctx))
        val v = (st.value as? HarnessValue.Num)?.value
        val preset = presets.firstOrNull { it.value == v }
        return SegmentsView(segments, if (preset != null) "$PRESET_PREFIX${HarnessValue.formatNumber(preset.value)}" else CUSTOM_SEG)
    }

    /**
     * The number field: empty → error, not a number → error; a preset value is kept as is,
     * otherwise whole-number steps round and the value is clamped to the slider range.
     */
    fun parseNumberField(option: HarnessOption, text: String): HarnessLogic.NumberInput {
        val t = text.trim().replace(",", "")
        if (t.isEmpty()) return HarnessLogic.NumberInput.Error("Enter a number")
        val n = t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return HarnessLogic.NumberInput.Error("Not a number")
        if (presetsOf(option).any { it.value == n }) return HarnessLogic.NumberInput.Ok(n)
        val s = numberScale(option)
        val step = option.step?.takeIf { it > 0 }
        var v = when {
            step != null && step % 1.0 == 0.0 -> Math.round(n).toDouble()
            step != null -> roundTo(n, decimalsOf(step))
            else -> n
        }
        if (s != null) {
            v = clamp(v, s.lo, s.hi)
        } else {
            option.min?.let { v = max(it, v) }
            option.max?.let { v = min(it, v) }
        }
        return HarnessLogic.NumberInput.Ok(v)
    }

    /** The field's hint: "128–32,768 tokens". */
    fun rangeHint(option: HarnessOption): String {
        val s = numberScale(option) ?: return ""
        val unit = option.unit?.let { " $it" }.orEmpty()
        return "${formatNumber(s.lo, option.step)}–${formatNumber(s.hi, option.step)}$unit"
    }
}
