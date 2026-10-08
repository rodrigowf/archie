package com.assistant.archie.feature.settings

import com.assistant.archie.feature.settings.HarnessLogic.CLI_DEFAULT
import com.assistant.archie.feature.settings.HarnessLogic.CUSTOM
import com.assistant.archie.feature.settings.HarnessLogic.INHERIT
import com.assistant.archie.feature.settings.HarnessLogic.NumberInput
import com.assistant.core.model.ConfigPatch
import com.assistant.core.model.HarnessCatalog
import com.assistant.core.model.HarnessCatalogModel
import com.assistant.core.model.HarnessChoice
import com.assistant.core.model.HarnessOption
import com.assistant.core.model.HarnessOptionKind
import com.assistant.core.model.HarnessValue
import com.assistant.core.model.SessionConfig
import com.assistant.core.protocol.HarnessesDto
import com.assistant.core.protocol.RestJson
import com.assistant.core.protocol.toModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Harness configuration logic (spec 12 §6.14, §8.1), mirroring the web
 * `apps/web/src/features/settings/__tests__/harness.test.ts` on the same catalogs
 * (`apps/web/mock-server/data/harnesses.json`): gating of options / choices by the effective model,
 * the inherit / CLI default / value states, select rows and labels, map diffs, and the session draft
 * reset on a harness change.
 */
class HarnessSettingsLogicTest {
    private val samples = RestJson.decodeFromString<HarnessesDto>(harnessCatalogsFixture()).toModel()
    private fun cat(id: String): HarnessCatalog = requireNotNull(samples.first { it.id == id }.catalog)
    private val claude = cat("claude")
    private val codex = cat("codex")
    private val qwen = cat("qwen")
    private val gemini = cat("gemini")

    private fun keys(c: HarnessCatalog, model: String?) = HarnessLogic.visibleOptions(c, model).map { it.option.key }
    private fun choiceValues(c: HarnessCatalog, model: String?, key: String) =
        HarnessLogic.visibleOptions(c, model).firstOrNull { it.option.key == key }?.choices?.map { it.value }
    private fun opt(c: HarnessCatalog, key: String) = c.options.first { it.key == key }
    private fun vo(c: HarnessCatalog, model: String?, key: String) = HarnessLogic.visibleOptions(c, model).first { it.option.key == key }
    private fun text(s: String) = HarnessValue.Text(s)
    private fun value(v: HarnessValue) = OptionState.Value(v)

    // ───────── option gating by the effective model ─────────

    @Test fun gating_unknownModel_showsEverything() {
        assertEquals(listOf("effort", "thinking", "thinking_budget", "fallback_model", "todo_tools"), keys(claude, ""))
        assertEquals(keys(claude, null), keys(claude, "my-custom-claude"))
        assertEquals(listOf("adaptive", "enabled", "disabled"), choiceValues(claude, "", "thinking"))
        assertEquals(listOf("low", "medium", "high", "xhigh", "max", "ultra"), choiceValues(codex, null, "effort"))
    }

    @Test fun gating_optionModels_hideAnOptionForOtherModels() {
        assertEquals(listOf("effort", "fallback_model", "todo_tools"), keys(claude, "claude-opus-5-5"))
        assertEquals(listOf("effort", "thinking", "thinking_budget", "fallback_model", "todo_tools"), keys(claude, "claude-opus-4-6"))
        assertEquals(listOf("thinking_budget", "approval_mode"), keys(gemini, "gemini-2.5-pro"))
        assertEquals(listOf("thinking_level", "approval_mode"), keys(gemini, "gemini-3.1-pro-preview"))
    }

    @Test fun gating_choiceModels_hideAChoiceForOtherModels() {
        assertEquals(listOf("adaptive", "disabled"), choiceValues(claude, "claude-haiku-5-5", "thinking"))
        assertEquals(listOf("adaptive", "enabled", "disabled"), choiceValues(claude, "claude-sonnet-4-6", "thinking"))
    }

    @Test fun gating_effortNarrowedToTheModel_emptyHidesIt() {
        assertEquals(listOf("low", "medium", "high", "max"), choiceValues(claude, "claude-opus-4-6", "effort"))
        assertEquals(listOf("low", "medium", "high"), choiceValues(claude, "claude-opus-4-5-20251101", "effort"))
        assertFalse("effort" in keys(claude, "claude-haiku-4-5-20251001"))
        assertFalse("effort" in keys(claude, "haiku"))
        assertTrue("ultra" in choiceValues(codex, "gpt-5.6-terra", "effort")!!)
        assertFalse("ultra" in choiceValues(codex, "gpt-6-luna", "effort")!!)
        assertEquals(listOf("low", "medium", "high"), choiceValues(codex, "codex-mini", "effort"))
    }

    @Test fun gating_aLevelTheOptionLacks_isStillOffered() {
        val c = codex.copy(models = listOf(HarnessCatalogModel("m", "M", source = "cli", efforts = listOf("low", "turbo_max"))))
        assertEquals(listOf(HarnessChoice("low", "Low"), HarnessChoice("turbo_max", "Turbo max")), HarnessLogic.visibleOptions(c, "m").first { it.option.key == "effort" }.choices)
    }

    @Test fun gating_supportsThinkingFalse_hidesThinkingOptions() {
        assertEquals(emptyList<String>(), keys(qwen, "qwen3-coder-flash"))
        assertEquals(listOf("thinking", "thinking_budget"), keys(qwen, "qwen3.6-plus"))
    }

    @Test fun gating_noCatalog_andEmptySelects() {
        assertEquals(emptyList<VisibleOption>(), HarnessLogic.visibleOptions(null, "x"))
        val c = claude.copy(
            models = listOf(HarnessCatalogModel("m", "M", source = "live")),
            options = listOf(HarnessOption("x", "X", HarnessOptionKind.SELECT, choices = listOf(HarnessChoice("a", "A", models = listOf("other"))))),
        )
        assertEquals(emptyList<String>(), keys(c, "m"))
        assertEquals(listOf("x"), keys(c, ""))
    }

    // ───────── labels ─────────

    @Test fun labels_contextTraitsAndModels() {
        assertEquals("1M", HarnessLogic.formatContextWindow(1_000_000))
        assertEquals("1M", HarnessLogic.formatContextWindow(1_048_576))
        assertEquals("1.5M", HarnessLogic.formatContextWindow(1_500_000))
        assertEquals("272K", HarnessLogic.formatContextWindow(272_000))
        assertEquals(
            "opus · Alias → Claude Opus 5.5 · 1M context · thinking · vision",
            HarnessLogic.modelTraits(HarnessCatalogModel("opus", "Opus", "builtin", "Alias → Claude Opus 5.5", 1_000_000, supportsThinking = true, supportsVision = true)),
        )
        assertEquals("live list", HarnessLogic.modelTraits(HarnessCatalogModel("x", "x", "live")))
        assertEquals("q · CLI settings", HarnessLogic.modelTraits(HarnessCatalogModel("q", "Q", "settings", supportsThinking = false)))
        assertEquals("Claude Opus 5.5", HarnessLogic.modelLabel(claude, "claude-opus-5-5"))
        assertEquals("whatever", HarnessLogic.modelLabel(claude, "whatever"))
        assertEquals("CLI default", HarnessLogic.modelLabel(claude, ""))
        assertEquals("Codex", HarnessLogic.label(samples, "codex"))
        assertEquals("nope", HarnessLogic.label(samples, "nope"))
    }

    @Test fun labels_valuesCliDefaultsAndInherit() {
        val effort = opt(codex, "effort")
        assertEquals("Extra high", HarnessLogic.valueLabel(effort, text("xhigh")))
        assertEquals("Off", HarnessLogic.valueLabel(opt(claude, "todo_tools"), HarnessValue.Flag(false)))
        assertEquals("32000", HarnessLogic.valueLabel(opt(claude, "thinking_budget"), HarnessValue.Num(32000.0)))
        assertEquals("CLI default · Medium", HarnessLogic.cliDefaultLabel(effort, null))
        assertEquals("CLI default · Low", HarnessLogic.cliDefaultLabel(effort, HarnessLogic.gatingModel(codex, "codex-mini")))
        assertEquals("CLI default", HarnessLogic.cliDefaultLabel(opt(codex, "web_search"), null))
        assertEquals("CLI default · On", HarnessLogic.cliDefaultLabel(opt(claude, "todo_tools"), null))
        assertEquals("Default (High)", HarnessLogic.inheritLabel(effort, text("high"), null))
        assertEquals("Default (CLI default · Medium)", HarnessLogic.inheritLabel(effort, null, null))
    }

    @Test fun labels_collapsedSummaryAndHiddenNote() {
        assertEquals("CLI default", HarnessLogic.harnessDefaultsSummary(claude, "", emptyMap()))
        assertEquals("Opus · 1 option", HarnessLogic.harnessDefaultsSummary(claude, "opus", mapOf("effort" to text("max"))))
        assertEquals("GPT-6-Luna · 2 options", HarnessLogic.harnessDefaultsSummary(codex, "gpt-6-luna", mapOf("effort" to text("low"), "verbosity" to text("high"), "gone" to text("x"))))
        assertEquals("m", HarnessLogic.harnessDefaultsSummary(null, "m", null))
        assertEquals("2 more options do not apply to Claude Opus 5.5.", HarnessLogic.hiddenNote(claude, "claude-opus-5-5"))
        assertNull(HarnessLogic.hiddenNote(claude, ""))
    }

    // ───────── option states ─────────

    @Test fun states_sessionAndGlobal() {
        assertEquals(OptionState.Inherit, HarnessLogic.sessionOptionState(null, "effort"))
        assertEquals(OptionState.Inherit, HarnessLogic.sessionOptionState(mapOf("thinking" to text("x")), "effort"))
        assertEquals(OptionState.Cli, HarnessLogic.sessionOptionState(mapOf("effort" to null), "effort"))
        assertEquals(value(text("low")), HarnessLogic.sessionOptionState(mapOf("effort" to text("low")), "effort"))
        assertEquals(value(HarnessValue.Flag(false)), HarnessLogic.sessionOptionState(mapOf("todo_tools" to HarnessValue.Flag(false)), "todo_tools"))
        assertEquals(OptionState.Cli, HarnessLogic.globalOptionState(null, "effort"))
        assertEquals(OptionState.Cli, HarnessLogic.globalOptionState(mapOf("effort" to null), "effort"))
        assertEquals(value(HarnessValue.Num(0.0)), HarnessLogic.globalOptionState(mapOf("thinking_budget" to HarnessValue.Num(0.0)), "thinking_budget"))
    }

    @Test fun states_sessionOverlayEdits() {
        assertEquals(mapOf("effort" to text("max")), HarnessLogic.withSessionOption(null, "effort", value(text("max"))))
        assertEquals(mapOf("effort" to text("max"), "thinking" to null), HarnessLogic.withSessionOption(mapOf("effort" to text("max")), "thinking", OptionState.Cli))
        assertNull(HarnessLogic.withSessionOption(mapOf("effort" to text("max")), "effort", OptionState.Inherit))
        assertEquals(mapOf("thinking" to null), HarnessLogic.withSessionOption(mapOf("effort" to text("max"), "thinking" to null), "effort", OptionState.Inherit))
    }

    @Test fun states_globalPutBodies() {
        assertEquals(ConfigPatch(harnessOptions = mapOf("claude" to mapOf("effort" to text("xhigh")))), HarnessLogic.globalOptionPatch("claude", "effort", value(text("xhigh"))))
        assertEquals(ConfigPatch(harnessOptions = mapOf("codex" to mapOf("web_search" to null))), HarnessLogic.globalOptionPatch("codex", "web_search", OptionState.Cli))
        assertEquals(ConfigPatch(harnessModel = mapOf("gemini" to "")), HarnessLogic.globalModelPatch("gemini", ""))
    }

    @Test fun states_mapsCompareStructurally() {
        assertTrue(HarnessLogic.sameOptionsMap(null, emptyMap()))
        assertTrue(HarnessLogic.sameOptionsMap(mapOf("a" to HarnessValue.Num(1.0), "b" to null), linkedMapOf("b" to null, "a" to HarnessValue.Num(1.0))))
        assertFalse(HarnessLogic.sameOptionsMap(mapOf("a" to HarnessValue.Num(1.0)), mapOf("a" to HarnessValue.Num(1.0), "b" to null)))
        assertFalse(HarnessLogic.sameOptionsMap(mapOf("a" to null), null))
        assertFalse(HarnessLogic.sameOptionsMap(mapOf("a" to text("1")), mapOf("a" to HarnessValue.Num(1.0))))
        assertNull(HarnessLogic.normalizeOptionsMap(emptyMap()))
    }

    // ───────── option select rows ─────────

    @Test fun rows_sessionSelect_staleValueShownDisabled() {
        val row = HarnessLogic.gatingModel(claude, "claude-opus-4-5-20251101")
        val rows = HarnessLogic.optionItems(vo(claude, "claude-opus-4-5-20251101", "effort"), HarnessScope.SESSION, value(text("max")), row, inherited = text("high"))
        assertEquals(listOf("Default (High)", "CLI default", "Low", "Medium", "High", "Max (not for this model)"), rows.map { it.label })
        assertEquals(INHERIT, rows[0].id)
        assertEquals(CLI_DEFAULT, rows[1].id)
        assertFalse(rows.last().enabled)
    }

    @Test fun rows_toggleAndNumber_valuesRoundTrip() {
        val toggle = HarnessLogic.optionItems(vo(qwen, null, "thinking"), HarnessScope.GLOBAL, OptionState.Cli, null)
        assertEquals(listOf("CLI default · On", "On", "Off"), toggle.map { it.label })
        val num = HarnessLogic.optionItems(vo(qwen, null, "thinking_budget"), HarnessScope.SESSION, OptionState.Inherit, null)
        assertEquals(listOf("Default (CLI default)", "CLI default", "Custom value…"), num.map { it.label })
        assertEquals("1–32768", num[2].description)
        val tb = opt(qwen, "thinking_budget")
        assertEquals(CUSTOM, HarnessLogic.optionSelectValue(tb, value(HarnessValue.Num(2048.0))))
        assertEquals(OptionPick.Custom, HarnessLogic.parseOptionSelect(tb, CUSTOM))
        val th = opt(qwen, "thinking")
        assertEquals("false", HarnessLogic.optionSelectValue(th, value(HarnessValue.Flag(false))))
        assertEquals(OptionPick.State(value(HarnessValue.Flag(false))), HarnessLogic.parseOptionSelect(th, "false"))
        assertEquals(OptionPick.State(OptionState.Inherit), HarnessLogic.parseOptionSelect(th, INHERIT))
        assertEquals(OptionPick.State(OptionState.Cli), HarnessLogic.parseOptionSelect(th, CLI_DEFAULT))
        assertEquals(OptionPick.State(value(text("ultra"))), HarnessLogic.parseOptionSelect(opt(codex, "effort"), "ultra"))
    }

    @Test fun rows_numberInputValidation() {
        val tb = opt(claude, "thinking_budget")
        assertEquals("1024–128000, step 1024", HarnessLogic.numberRange(tb))
        assertEquals(NumberInput.Error("Enter a number"), HarnessLogic.parseNumberInput(tb, ""))
        assertEquals(NumberInput.Error("Not a number"), HarnessLogic.parseNumberInput(tb, "abc"))
        assertEquals(NumberInput.Error("At least 1024"), HarnessLogic.parseNumberInput(tb, "100"))
        assertEquals(NumberInput.Error("At most 128000"), HarnessLogic.parseNumberInput(tb, "200000"))
        assertEquals(NumberInput.Error("Whole numbers only"), HarnessLogic.parseNumberInput(tb, "2048.5"))
        assertEquals(NumberInput.Ok(32000.0), HarnessLogic.parseNumberInput(tb, " 32000 "))
        assertEquals(NumberInput.Ok(-1.0), HarnessLogic.parseNumberInput(opt(gemini, "thinking_budget"), "-1"))
    }

    // ───────── model select rows ─────────

    @Test fun models_sessionRows() {
        val rows = HarnessLogic.modelItems(claude, HarnessScope.SESSION, current = null, inherited = "opus")
        assertEquals(Option(INHERIT, "Default (Opus)"), rows[0])
        assertEquals("CLI default (Claude Sonnet 5.5)", rows[1].label)
        assertEquals("claude-opus-4-6 · 1M context · thinking · vision · live list", rows.first { it.id == "claude-opus-4-6" }.description)
        assertEquals(Option(CUSTOM, "Custom model id…", "Any id the CLI accepts"), rows.last())
        assertEquals("Default (CLI default (Claude Sonnet 5.5))", HarnessLogic.modelItems(claude, HarnessScope.SESSION, null, "")[0].label)
    }

    @Test fun models_globalRows_andNoCatalog() {
        assertEquals(CLI_DEFAULT, HarnessLogic.modelItems(codex, HarnessScope.GLOBAL, "")[0].id)
        assertEquals(listOf(CLI_DEFAULT, CUSTOM), HarnessLogic.modelItems(null, HarnessScope.GLOBAL, "").map { it.id })
    }

    @Test fun models_strictCatalog_showsAnUnknownSavedIdAsUnavailable() {
        val strict = codex.copy(allowCustomModel = false)
        val rows = HarnessLogic.modelItems(strict, HarnessScope.GLOBAL, "old-model")
        assertEquals(Option("old-model", "old-model (unavailable)", enabled = false), rows.last())
        assertEquals("old-model", HarnessLogic.modelSelectValue(strict, "old-model"))
    }

    @Test fun models_selectValues() {
        assertEquals(INHERIT, HarnessLogic.modelSelectValue(claude, null))
        assertEquals(CLI_DEFAULT, HarnessLogic.modelSelectValue(claude, ""))
        assertEquals(CUSTOM, HarnessLogic.modelSelectValue(claude, "", customMode = true))
        assertEquals("opus", HarnessLogic.modelSelectValue(claude, "opus"))
        assertEquals(CUSTOM, HarnessLogic.modelSelectValue(claude, "claude-opus-5-5[1m]"))
        assertEquals(ModelPick.Model(null), HarnessLogic.parseModelSelect(INHERIT))
        assertEquals(ModelPick.Model(""), HarnessLogic.parseModelSelect(CLI_DEFAULT))
        assertEquals(ModelPick.Custom, HarnessLogic.parseModelSelect(CUSTOM))
        assertEquals(ModelPick.Model("opus"), HarnessLogic.parseModelSelect("opus"))
        assertEquals("opus", HarnessLogic.effectiveSessionModel(null, "opus"))
        assertEquals("", HarnessLogic.effectiveSessionModel("", "opus"))
        assertEquals("", HarnessLogic.effectiveSessionModel(null, null))
    }

    // ───────── session draft: changing the harness ─────────

    private val saved = SessionConfig(provider = "claude", harnessModel = "opus", harnessOptions = mapOf("effort" to text("max")))

    @Test fun draft_changingTheHarness_resetsModelAndOptions() {
        val d = HarnessLogic.draftForProvider(saved, SessionDraft(mapOf(SessionKey.CHROME_EXTENSION to true)), "codex", "claude")
        assertEquals(
            mapOf(SessionKey.CHROME_EXTENSION to true, SessionKey.PROVIDER to "codex", SessionKey.HARNESS_MODEL to null, SessionKey.HARNESS_OPTIONS to null),
            d.values,
        )
    }

    @Test fun draft_goingBackToTheSavedHarness_restoresTheSavedValues() {
        val away = HarnessLogic.draftForProvider(saved, SessionDraft(), "codex", "claude")
        assertEquals(mapOf(SessionKey.PROVIDER to "claude"), HarnessLogic.draftForProvider(saved, away, "claude", "claude").values)
        assertEquals(mapOf(SessionKey.PROVIDER to null), HarnessLogic.draftForProvider(saved, away, null, "claude").values)
    }

    @Test fun draft_dirtyCheckComparesOptionMapsStructurally() {
        val st = SessionSettingsState(saved = saved, draft = SessionDraft(mapOf(SessionKey.HARNESS_OPTIONS to linkedMapOf<String, HarnessValue?>("effort" to text("max")))))
        assertFalse(st.dirty)
        val none = SessionSettingsState(saved = SessionConfig(), draft = SessionDraft(mapOf(SessionKey.HARNESS_OPTIONS to emptyMap<String, HarnessValue?>())))
        assertFalse("{} = null = inherit every key", none.dirty)
        assertTrue(st.copy(draft = SessionDraft(mapOf(SessionKey.HARNESS_OPTIONS to mapOf("effort" to null)))).dirty)
    }
}
