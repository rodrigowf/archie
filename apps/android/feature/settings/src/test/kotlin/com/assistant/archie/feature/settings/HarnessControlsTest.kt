package com.assistant.archie.feature.settings

import com.assistant.archie.feature.settings.HarnessControls.CUSTOM_SEG
import com.assistant.archie.feature.settings.HarnessControls.LOG_POSITIONS
import com.assistant.archie.feature.settings.HarnessControls.UNSET
import com.assistant.archie.feature.settings.HarnessLogic.NumberInput
import com.assistant.core.model.HarnessCatalog
import com.assistant.core.model.HarnessChoice
import com.assistant.core.model.HarnessControl
import com.assistant.core.model.HarnessOption
import com.assistant.core.model.HarnessOptionKind
import com.assistant.core.model.HarnessPreset
import com.assistant.core.model.HarnessValue
import com.assistant.core.protocol.HarnessesDto
import com.assistant.core.protocol.RestJson
import com.assistant.core.protocol.toModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Harness option controls ([HarnessControls]), mirroring the web
 * `apps/web/src/features/settings/__tests__/harnessControls.test.ts` on the same catalogs
 * (`apps/web/mock-server/data/harnesses.json`): control inference vs hints, resolved values and
 * `requires`, source lines, segments for segmented / levels / presets, the log slider scale
 * (mapping, snapping) and number parsing / formatting.
 */
class HarnessControlsTest {
    private val samples = RestJson.decodeFromString<HarnessesDto>(harnessCatalogsFixture()).toModel()
    private fun cat(id: String): HarnessCatalog = requireNotNull(samples.first { it.id == id }.catalog)
    private val claude = cat("claude")
    private val codex = cat("codex")
    private val qwen = cat("qwen")
    private val gemini = cat("gemini")

    private fun opt(c: HarnessCatalog, key: String) = c.options.first { it.key == key }
    private fun vo(c: HarnessCatalog, key: String, model: String? = null) = HarnessLogic.visibleOptions(c, model).first { it.option.key == key }
    private fun text(s: String) = HarnessValue.Text(s)
    private fun num(n: Number) = HarnessValue.Num(n.toDouble())
    private fun flag(b: Boolean) = HarnessValue.Flag(b)
    private fun v(x: HarnessValue) = OptionState.Value(x)
    private val inh = OptionState.Inherit
    private val cli = OptionState.Cli
    private fun g(state: OptionState) = ControlContext(HarnessScope.GLOBAL, state)
    private fun s(state: OptionState, inherited: OptionState = cli) = ControlContext(HarnessScope.SESSION, state, inherited)
    private fun numOpt(min: Double? = null, max: Double? = null, step: Double? = null, scale: String? = null, default: HarnessValue? = null) =
        HarnessOption("t", "T", HarnessOptionKind.NUMBER, min = min, max = max, step = step, scale = scale, default = default)

    // ───────── control ─────────

    @Test fun optionControl_followsTheCatalogHints() {
        assertEquals(HarnessControl.LEVELS, HarnessControls.optionControl(vo(claude, "effort")))
        assertEquals(HarnessControl.SEGMENTED, HarnessControls.optionControl(vo(claude, "thinking")))
        assertEquals(HarnessControl.SLIDER, HarnessControls.optionControl(vo(claude, "thinking_budget")))
        assertEquals(HarnessControl.DROPDOWN, HarnessControls.optionControl(vo(claude, "fallback_model")))
        assertEquals(HarnessControl.SWITCH, HarnessControls.optionControl(vo(claude, "todo_tools")))
        assertEquals(HarnessControl.SEGMENTED, HarnessControls.optionControl(vo(gemini, "approval_mode")))
        assertEquals(HarnessControl.LEVELS, HarnessControls.optionControl(vo(gemini, "thinking_level")))
        assertEquals(HarnessControl.LEVELS, HarnessControls.optionControl(vo(codex, "verbosity")))
        assertEquals(HarnessControl.SEGMENTED, HarnessControls.optionControl(vo(codex, "web_search")))
    }

    @Test fun optionControl_inferredWithoutHints() {
        fun bare(kind: HarnessOptionKind = HarnessOptionKind.SELECT, choices: Int = 0, default: HarnessValue? = null, ordered: Boolean = false, control: HarnessControl? = null) =
            VisibleOption(
                HarnessOption("k", "K", kind, default = default, ordered = ordered, control = control),
                List(choices) { HarnessChoice("v$it", "V$it") },
            )
        assertEquals(HarnessControl.SWITCH, HarnessControls.optionControl(bare(HarnessOptionKind.TOGGLE, default = flag(false))))
        assertEquals(HarnessControl.SEGMENTED, HarnessControls.optionControl(bare(HarnessOptionKind.TOGGLE)))
        assertEquals(HarnessControl.SLIDER, HarnessControls.optionControl(bare(HarnessOptionKind.NUMBER)))
        assertEquals(HarnessControl.LEVELS, HarnessControls.optionControl(bare(choices = 6, ordered = true)))
        assertEquals(HarnessControl.SEGMENTED, HarnessControls.optionControl(bare(choices = 4)))
        assertEquals(HarnessControl.DROPDOWN, HarnessControls.optionControl(bare(choices = 5)))
        // a hint that does not suit the kind is ignored (an unknown one never reaches the model)
        assertEquals(HarnessControl.SLIDER, HarnessControls.optionControl(bare(HarnessOptionKind.NUMBER, control = HarnessControl.SWITCH)))
        assertEquals(HarnessControl.SWITCH, HarnessControls.optionControl(bare(HarnessOptionKind.TOGGLE, default = flag(true), control = HarnessControl.SLIDER)))
        assertEquals(HarnessControl.DROPDOWN, HarnessControls.optionControl(bare(HarnessOptionKind.NUMBER, control = HarnessControl.DROPDOWN)))
    }

    // ───────── resolved values and requires ─────────

    @Test fun resolvedValue_sessionGlobalCliDefault() {
        val effort = opt(codex, "effort")
        assertEquals(text("low"), HarnessControls.resolvedValue(effort, s(v(text("low")), v(text("high")))))
        assertEquals(text("high"), HarnessControls.resolvedValue(effort, s(inh, v(text("high")))))
        assertEquals(text("medium"), HarnessControls.resolvedValue(effort, s(cli, v(text("high")))))
        assertEquals(text("low"), HarnessControls.resolvedValue(effort, g(cli).copy(row = HarnessLogic.gatingModel(codex, "codex-mini"))))
        assertNull(HarnessControls.resolvedValue(opt(claude, "thinking"), g(cli)))
    }

    @Test fun requires_aBudgetAppliesOnlyWithTheRequiredThinking() {
        val budget = opt(claude, "thinking_budget")
        fun r(opts: Map<String, HarnessValue?>?, inherited: Map<String, HarnessValue?>? = null, scope: HarnessScope = HarnessScope.GLOBAL) =
            HarnessControls.unmetRequirement(budget, claude, HarnessControls.makeResolver(claude, scope, opts, inherited, null))
        assertEquals("Applies when Thinking is Fixed budget", r(null))
        assertEquals("Applies when Thinking is Fixed budget", r(mapOf("thinking" to text("adaptive"))))
        assertNull(r(mapOf("thinking" to text("enabled"))))
        // session: inherits the global Thinking, or overrides it
        assertNull(r(null, mapOf("thinking" to text("enabled")), HarnessScope.SESSION))
        assertEquals("Applies when Thinking is Fixed budget", r(mapOf("thinking" to text("disabled")), mapOf("thinking" to text("enabled")), HarnessScope.SESSION))
        assertEquals("Applies when Thinking is Fixed budget", r(mapOf("thinking" to null), mapOf("thinking" to text("enabled")), HarnessScope.SESSION))

        val qb = opt(qwen, "thinking_budget")
        fun q(opts: Map<String, HarnessValue?>?) = HarnessControls.unmetRequirement(qb, qwen, HarnessControls.makeResolver(qwen, HarnessScope.GLOBAL, opts, null, null))
        // fixture: Qwen thinking defaults to On; [true, null] → applies unless turned off
        assertNull(q(null))
        assertEquals("Applies when Thinking is On", q(mapOf("thinking" to flag(false))))
        val noDefault = qwen.copy(options = qwen.options.map { if (it.key == "thinking") HarnessOption(it.key, it.label, it.kind) else it })
        assertNull(HarnessControls.unmetRequirement(qb, noDefault, HarnessControls.makeResolver(noDefault, HarnessScope.GLOBAL, null, null, null)))
    }

    @Test fun requires_ignoresUnknownKeys_namesSeveralValuesAndUnset() {
        val resolve = HarnessControls.makeResolver(claude, HarnessScope.GLOBAL, mapOf("thinking" to text("adaptive")), null, null)
        val effort = opt(claude, "effort")
        assertNull(HarnessControls.unmetRequirement(effort, claude, resolve))
        assertNull(HarnessControls.unmetRequirement(effort.copy(requires = mapOf("nope" to listOf(text("x")))), claude, resolve))
        assertEquals(
            "Applies when Thinking is Fixed budget or Off",
            HarnessControls.unmetRequirement(effort.copy(requires = mapOf("thinking" to listOf(text("enabled"), text("disabled")))), claude, resolve),
        )
        assertEquals(
            "Applies when Thinking is Adaptive, Fixed budget or Off",
            HarnessControls.unmetRequirement(effort.copy(requires = mapOf("thinking" to listOf(text("adaptive"), text("enabled"), text("disabled")))), claude) { text("x") },
        )
        assertEquals("Applies when Thinking is unset", HarnessControls.unmetRequirement(effort.copy(requires = mapOf("thinking" to listOf(null))), claude, resolve))
        assertNull(HarnessControls.makeResolver(claude, HarnessScope.GLOBAL, null, null, null)("nope"))
    }

    // ───────── labels ─────────

    @Test fun displayValue_choicesOnOffPresetsNumbersWithUnit() {
        assertEquals("Extra high", HarnessControls.displayValue(opt(claude, "effort"), text("xhigh")))
        assertEquals("Off", HarnessControls.displayValue(opt(claude, "todo_tools"), flag(false)))
        assertEquals("16,000 tokens", HarnessControls.displayValue(opt(claude, "thinking_budget"), num(16000)))
        assertEquals("Dynamic", HarnessControls.displayValue(opt(gemini, "thinking_budget"), num(-1)))
        assertEquals("Off", HarnessControls.displayValue(opt(gemini, "thinking_budget"), num(0)))
        assertEquals("0.7", HarnessControls.displayValue(numOpt(step = 0.1), num(0.7)))
    }

    @Test fun sourceLine_saysWhereTheValueComesFrom() {
        val todo = opt(claude, "todo_tools")
        assertEquals("CLI default · On", HarnessControls.sourceLine(todo, g(cli)))
        assertEquals("Overrides the CLI default (On)", HarnessControls.sourceLine(todo, g(v(flag(false)))))
        assertEquals("Default from Settings (Off)", HarnessControls.sourceLine(todo, s(inh, v(flag(false)))))
        assertEquals("Default (CLI default · On)", HarnessControls.sourceLine(todo, s(inh)))
        assertEquals("CLI default for this session (On)", HarnessControls.sourceLine(todo, s(cli, v(flag(false)))))
        assertEquals("Set for this session", HarnessControls.sourceLine(todo, s(v(flag(true)))))
        val ws = opt(codex, "web_search")
        assertEquals("CLI default", HarnessControls.sourceLine(ws, g(cli)))
        assertEquals("Overrides the CLI default", HarnessControls.sourceLine(ws, g(v(text("live")))))
        assertEquals("Default (CLI default)", HarnessControls.sourceLine(ws, s(inh)))
        assertEquals("CLI default for this session", HarnessControls.sourceLine(ws, s(cli, v(text("live")))))
        assertEquals("CLI default · 16,000 tokens", HarnessControls.sourceLine(opt(claude, "thinking_budget"), g(cli)))
    }

    @Test fun resetActions_useDefaultOnResetControlsThatAreSet_useCliWhenSettingsSetsIt() {
        assertEquals(ResetActions(useDefault = true, useCli = false), HarnessControls.resetActions(g(v(flag(true))), true))
        assertEquals(ResetActions(useDefault = false, useCli = false), HarnessControls.resetActions(g(cli), true))
        assertEquals(ResetActions(useDefault = false, useCli = false), HarnessControls.resetActions(g(v(flag(true))), false))
        assertEquals(ResetActions(useDefault = false, useCli = true), HarnessControls.resetActions(s(inh, v(flag(true))), true))
        assertEquals(ResetActions(useDefault = true, useCli = false), HarnessControls.resetActions(s(cli, v(flag(true))), true))
        assertEquals(ResetActions(useDefault = true, useCli = true), HarnessControls.resetActions(s(v(flag(false)), v(flag(true))), true))
        assertEquals(ResetActions(useDefault = false, useCli = false), HarnessControls.resetActions(s(v(flag(false))), false))
        assertEquals(OptionState.Cli, HarnessControls.defaultState(HarnessScope.GLOBAL))
        assertEquals(OptionState.Inherit, HarnessControls.defaultState(HarnessScope.SESSION))
    }

    // ───────── segments ─────────

    @Test fun levels_defaultFirst_visibleLevelsInOrder_cliDefaultDotted() {
        val row = HarnessLogic.gatingModel(codex, "gpt-6-luna")
        val view = HarnessControls.choiceSegments(vo(codex, "effort", "gpt-6-luna"), g(cli).copy(row = row))
        assertEquals(listOf("Default", "Low", "Medium", "High", "Extra high", "Max"), view.segments.map { it.label })
        assertEquals(listOf("medium"), view.segments.filter { it.dot }.map { it.value })
        assertEquals("CLI default", view.segments.first { it.dot }.title)
        assertEquals(UNSET, view.selected)
        assertEquals("ultra", HarnessControls.choiceSegments(vo(codex, "effort"), g(v(text("ultra")))).selected)
    }

    @Test fun toggleWithoutDefault_defaultOnOff() {
        val toggle = VisibleOption(HarnessOption("thinking", "Thinking", HarnessOptionKind.TOGGLE), emptyList())
        assertEquals(listOf("Default", "On", "Off"), HarnessControls.choiceSegments(toggle, g(cli)).segments.map { it.label })
        assertEquals("false", HarnessControls.choiceSegments(toggle, g(v(flag(false)))).selected)
        assertEquals(v(flag(false)), HarnessControls.parseSegment(toggle.option, HarnessScope.GLOBAL, "false"))
        assertEquals(cli, HarnessControls.parseSegment(toggle.option, HarnessScope.GLOBAL, UNSET))
        assertEquals(inh, HarnessControls.parseSegment(toggle.option, HarnessScope.SESSION, UNSET))
    }

    @Test fun selectionPerScope_sessionCliDefaultOverAGlobalValue_selectsNone() {
        val t = vo(claude, "thinking")
        assertEquals(UNSET, HarnessControls.choiceSegments(t, s(inh, v(text("adaptive")))).selected)
        assertNull(HarnessControls.choiceSegments(t, s(cli, v(text("adaptive")))).selected)
        assertEquals(UNSET, HarnessControls.choiceSegments(t, s(cli)).selected)
        assertEquals(v(text("enabled")), HarnessControls.parseSegment(t.option, HarnessScope.SESSION, "enabled"))
    }

    @Test fun aSavedValueTheModelLacks_showsAsADisabledSegment() {
        val effort = vo(claude, "effort", "claude-opus-4-6")
        val view = HarnessControls.choiceSegments(effort, g(v(text("xhigh"))))
        val last = view.segments.last()
        assertEquals(SegmentItem("xhigh", "Extra high", disabled = true, title = "Not for this model"), last)
        assertEquals("xhigh", view.selected)
        assertEquals("Extra high is not available for this model", HarnessControls.choiceHelp(effort, g(v(text("xhigh")))))
    }

    @Test fun choiceHelp_selectedDescriptionElseSourceLine() {
        val summary = vo(codex, "reasoning_summary")
        assertEquals("No thinking shown in the UI", HarnessControls.choiceHelp(summary, g(v(text("none")))))
        assertEquals("Overrides the CLI default (Concise)", HarnessControls.choiceHelp(summary, g(v(text("auto")))))
        assertEquals("CLI default · Concise", HarnessControls.choiceHelp(summary, g(cli)))
    }

    @Test fun presets_defaultDynamicOffCustom() {
        val budget = opt(gemini, "thinking_budget")
        assertEquals(listOf("Dynamic", "Off"), HarnessControls.presetsOf(budget).map { it.label })
        assertEquals(emptyList<HarnessPreset>(), HarnessControls.presetsOf(opt(claude, "thinking_budget")))
        assertEquals(emptyList<HarnessPreset>(), HarnessControls.presetsOf(opt(claude, "effort").copy(presets = listOf(HarnessPreset(1.0, "One")))))
        val view = HarnessControls.numberSegments(budget, g(cli))
        assertEquals(listOf("Default", "Dynamic", "Off", "Custom"), view.segments.map { it.label })
        assertEquals("The model decides", view.segments[1].description)
        assertEquals(UNSET, view.selected)
        assertEquals("preset:-1", HarnessControls.numberSegments(budget, g(v(num(-1)))).selected)
        assertEquals(CUSTOM_SEG, HarnessControls.numberSegments(budget, g(v(num(4096)))).selected)
        assertNull(HarnessControls.numberSegments(budget, s(cli, v(num(0)))).selected)
        assertEquals(v(num(0)), HarnessControls.parseSegment(budget, HarnessScope.GLOBAL, "preset:0"))
        assertEquals(v(num(-1)), HarnessControls.parseSegment(budget, HarnessScope.GLOBAL, "preset:-1"))
        assertEquals(inh, HarnessControls.parseSegment(budget, HarnessScope.SESSION, UNSET))
        assertEquals(inh, HarnessControls.parseSegment(budget, HarnessScope.SESSION, "preset:x"))
    }

    // ───────── numbers ─────────

    @Test fun numberScale_customMin_logOnlyAboveZero_unknownRangeNull() {
        assertEquals(NumberScale(1024.0, 128000.0, 1024.0, log = true), HarnessControls.numberScale(opt(claude, "thinking_budget")))
        assertEquals(NumberScale(128.0, 32768.0, 1.0, log = true), HarnessControls.numberScale(opt(gemini, "thinking_budget")))
        assertEquals(NumberScale(0.0, 2.0, 0.1, log = false), HarnessControls.numberScale(numOpt(0.0, 2.0, 0.1)))
        assertEquals(false, HarnessControls.numberScale(numOpt(0.0, 10.0, scale = "log"))?.log)
        assertNull(HarnessControls.numberScale(numOpt(min = 1.0)))
        assertNull(HarnessControls.numberScale(numOpt(5.0, 5.0)))
    }

    @Test fun logSlider_positions0to1000_endsExact_middleIsTheSnappedGeometricMean() {
        val c = HarnessControls.numberScale(opt(claude, "thinking_budget"))!!
        assertEquals(0.0..LOG_POSITIONS.toDouble(), HarnessControls.sliderRange(c))
        assertEquals(1024.0, HarnessControls.fromPosition(c, 0.0), 0.0)
        assertEquals(128000.0, HarnessControls.fromPosition(c, LOG_POSITIONS.toDouble()), 0.0)
        assertEquals(11264.0, HarnessControls.fromPosition(c, 500.0), 0.0) // √(1024·128000) ≈ 11449 → 11000 → 11·1024
        assertEquals(0.0, HarnessControls.toPosition(c, 1024.0), 0.0)
        assertEquals(LOG_POSITIONS.toDouble(), HarnessControls.toPosition(c, 128000.0), 0.0)
        assertEquals(0.0, HarnessControls.toPosition(c, 500.0), 0.0)
        // every position lands on a 1024 multiple (or an end)
        for (p in 0..LOG_POSITIONS step 37) {
            val x = HarnessControls.fromPosition(c, p.toDouble())
            assertTrue("$p → $x", x % 1024 == 0.0 || x == 128000.0)
        }
        // round trip stays close
        assertTrue(abs(HarnessControls.fromPosition(c, HarnessControls.toPosition(c, 16384.0)) - 16384) <= 1024)

        val gem = HarnessControls.numberScale(opt(gemini, "thinking_budget"))!!
        assertEquals(128.0, HarnessControls.fromPosition(gem, 0.0), 0.0)
        assertEquals(32768.0, HarnessControls.fromPosition(gem, LOG_POSITIONS.toDouble()), 0.0)
        assertEquals(2000.0, HarnessControls.fromPosition(gem, 500.0), 0.0) // √(128·32768) = 2048 → two significant digits
        var last = 0.0
        for (p in 0..LOG_POSITIONS step 10) {
            val x = HarnessControls.fromPosition(gem, p.toDouble())
            assertTrue("monotonic at $p", x >= last)
            last = x
        }
    }

    @Test fun linearSlider_snapsToLoPlusKStep_withoutFloatNoise() {
        val t = HarnessControls.numberScale(numOpt(0.0, 2.0, 0.1))!!
        assertEquals(0.0..2.0, HarnessControls.sliderRange(t))
        assertEquals(0.3, HarnessControls.fromPosition(t, 0.30000000000000004), 0.0)
        assertEquals(1.3, HarnessControls.snapNumber(t, 1.26), 0.0)
        assertEquals(2.0, HarnessControls.snapNumber(t, 7.0), 0.0)
        assertEquals(0.0, HarnessControls.snapNumber(t, Double.NaN), 0.0)
        assertEquals(0.7, HarnessControls.toPosition(t, 0.7), 0.0)
    }

    @Test fun niceRound_keepsTwoSignificantDigits() {
        assertEquals(1400.0, HarnessControls.niceRound(1371.0), 0.0)
        assertEquals(15000.0, HarnessControls.niceRound(15360.0), 0.0)
        assertEquals(130.0, HarnessControls.niceRound(128.0), 0.0)
        assertEquals(0.0, HarnessControls.niceRound(0.0), 0.0)
    }

    @Test fun customStart_theDefaultWhenInRange_elseTheLowestCustomValue() {
        val gb = opt(gemini, "thinking_budget")
        assertEquals(8192.0, HarnessControls.customStart(gb, HarnessControls.numberScale(gb)), 0.0)
        val qb = opt(qwen, "thinking_budget")
        assertEquals(1.0, HarnessControls.customStart(qb, HarnessControls.numberScale(qb)), 0.0)
        assertEquals(10.0, HarnessControls.customStart(numOpt(default = num(5)), HarnessControls.numberScale(numOpt(10.0, 20.0))), 0.0)
        assertEquals(0.0, HarnessControls.customStart(numOpt(), null), 0.0)
    }

    @Test fun parseNumberField_rejectsNonNumbers_keepsPresets_roundsWholeSteps_clamps() {
        val budget = opt(claude, "thinking_budget")
        assertEquals(NumberInput.Error("Enter a number"), HarnessControls.parseNumberField(budget, ""))
        assertEquals(NumberInput.Error("Not a number"), HarnessControls.parseNumberField(budget, "abc"))
        assertEquals(NumberInput.Ok(1024.0), HarnessControls.parseNumberField(budget, "100"))
        assertEquals(NumberInput.Ok(128000.0), HarnessControls.parseNumberField(budget, "200000"))
        assertEquals(NumberInput.Ok(16000.0), HarnessControls.parseNumberField(budget, "16,000"))
        assertEquals(NumberInput.Ok(2049.0), HarnessControls.parseNumberField(budget, "2048.6"))
        val gem = opt(gemini, "thinking_budget")
        assertEquals(NumberInput.Ok(-1.0), HarnessControls.parseNumberField(gem, "-1"))
        assertEquals(NumberInput.Ok(0.0), HarnessControls.parseNumberField(gem, "0"))
        assertEquals(NumberInput.Ok(128.0), HarnessControls.parseNumberField(gem, "5"))
        assertEquals(NumberInput.Ok(0.8), HarnessControls.parseNumberField(numOpt(0.0, 2.0, 0.1), "0.75"))
        assertEquals(NumberInput.Ok(3.0), HarnessControls.parseNumberField(numOpt(min = 3.0), "1"))
        assertEquals(NumberInput.Ok(1.5), HarnessControls.parseNumberField(numOpt(), "1.5"))
    }

    @Test fun formatting() {
        assertEquals("16,000", HarnessControls.formatNumber(16000.0))
        assertEquals("1,234,567", HarnessControls.formatNumber(1234567.0))
        assertEquals("999", HarnessControls.formatNumber(999.0))
        assertEquals("-1", HarnessControls.formatNumber(-1.0))
        assertEquals("0.7", HarnessControls.formatNumber(0.7, 0.1))
        assertEquals("0.0", HarnessControls.formatNumber(-0.04, 0.1))
        assertEquals(2, HarnessControls.decimalsOf(0.25))
        assertEquals(0, HarnessControls.decimalsOf(1024.0))
        assertEquals(0, HarnessControls.decimalsOf(null))
        assertEquals("16k", HarnessControls.formatCompact(16000.0))
        assertEquals("16.4k", HarnessControls.formatCompact(16384.0))
        assertEquals("1M", HarnessControls.formatCompact(1_000_000.0))
        assertEquals("512", HarnessControls.formatCompact(512.0))
        assertEquals("128–32,768 tokens", HarnessControls.rangeHint(opt(gemini, "thinking_budget")))
        assertEquals("", HarnessControls.rangeHint(numOpt()))
    }
}
