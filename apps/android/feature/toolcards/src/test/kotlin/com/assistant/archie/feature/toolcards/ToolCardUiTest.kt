package com.assistant.archie.feature.toolcards

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.assistant.archie.feature.toolcards.ui.ToolCard
import com.assistant.core.conversation.BlockOrigin
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.ToolStatus
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.model.SessionKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R7 / TC-1 / TC-2 in the UI (spec 12 §4.5, spec 14 §3.4): the output region is bound to the block,
 * updates in place when the result arrives (also while expanded), shows on expand when the result
 * came while collapsed, and a running card is never empty. Then every web sample through
 * collapsed → expanded running → done → error, like the web's `ToolCard.test.tsx`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xhdpi", application = Application::class)
class ToolCardUiTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetTiming() = ToolTiming.reset()

    private fun bash(status: ToolStatus = ToolStatus.RUNNING, output: String? = null, desc: String? = "Build the web app") = ToolBlock(
        id = "b1",
        toolUseId = "toolu_1",
        toolName = "Bash",
        toolInput = buildJsonObject {
            put("command", "cd /home/rodrigo/assistant/frontend-next && npm run build")
            if (desc != null) put("description", desc)
        },
        status = status,
        output = output,
    )

    private fun ToolBlock.done(out: String) = copy(status = ToolStatus.DONE, output = out)
    private fun ToolBlock.failed(out: String) = copy(status = ToolStatus.ERROR, output = out)

    @Test fun runningBashExpandedIsNeverEmpty() {
        compose.setContent { ArchieTheme(reduceMotion = true) { ToolCard(bash(), expanded = true, onToggle = {}) } }
        compose.onNodeWithTag("tool-output:running").assertIsDisplayed()
        compose.onNodeWithText("Running…", substring = true).assertIsDisplayed()
        // The header summary is the tool's description, not the command (live-check finding).
        // (once in the header, once in the Description field)
        compose.onAllNodesWithText("Build the web app").assertCountEquals(2)
        compose.onAllNodesWithText("Build the web app")[0].assertIsDisplayed()
        // The full command is in the body.
        compose.onNodeWithText("cd /home/rodrigo/assistant/frontend-next && npm run build", substring = true).assertIsDisplayed()
    }

    @Test fun resultArrivingWhileExpandedUpdatesTheOpenRegion() {
        var block by mutableStateOf(bash())
        compose.setContent { ArchieTheme(reduceMotion = true) { ToolCard(block, expanded = true, onToggle = {}) } }
        compose.onNodeWithTag("tool-output:running").assertIsDisplayed()
        block = block.done("built in 12s")
        compose.onNodeWithText("built in 12s").assertIsDisplayed()
        compose.onNodeWithText("exit 0").assertIsDisplayed()
        compose.onNodeWithTag("tool-output:running").assertDoesNotExist()
    }

    @Test fun resultArrivingWhileCollapsedShowsOnExpand() {
        var block by mutableStateOf(bash())
        compose.setContent { ArchieTheme(reduceMotion = true) { ToolCard(block, autoOpen = false) } }
        compose.onNodeWithTag("tool-body").assertDoesNotExist()
        block = block.done("file body")
        compose.onNodeWithText("file body").assertDoesNotExist()
        compose.onNodeWithText("Bash").performClick()
        compose.onNodeWithText("file body").assertIsDisplayed()
    }

    @Test fun liveCardFoldsWhenItsTurnMovesOnAndUserToggleWins() {
        var block by mutableStateOf(bash())
        var live by mutableStateOf(true)
        compose.setContent { ArchieTheme(reduceMotion = true) { ToolCard(block, autoOpen = live) } }
        compose.onNodeWithTag("tool-body").assertIsDisplayed()
        block = block.done("ok")
        compose.onNodeWithText("ok").assertIsDisplayed() // stays open after the result while live
        live = false
        compose.onNodeWithTag("tool-body").assertDoesNotExist() // folds once text follows
        compose.onNodeWithText("Bash").performClick()
        compose.onNodeWithText("ok").assertIsDisplayed()
    }

    @Test fun noResultLiveAndHistoryAndEmptyOutputs() {
        var block by mutableStateOf(bash(ToolStatus.NO_RESULT))
        compose.setContent { ArchieTheme(reduceMotion = true) { ToolCard(block, expanded = true, onToggle = {}) } }
        compose.onNodeWithText("No output received").assertIsDisplayed()
        block = block.copy(origin = BlockOrigin.HISTORY)
        compose.onNodeWithText("No output recorded").assertIsDisplayed()
        block = block.done("")
        compose.onNodeWithText("No output").assertIsDisplayed()
        block = block.failed("")
        compose.onNodeWithText("Failed with no output").assertIsDisplayed()
        // no_result is not final: a later result upgrades it (R-6).
        block = block.copy(status = ToolStatus.NO_RESULT, output = null)
        block = block.done("late result")
        compose.onNodeWithText("late result").assertIsDisplayed()
    }

    @Test fun inferredResultShowsTheHint() {
        compose.setContent { ArchieTheme(reduceMotion = true) { ToolCard(bash().done("ok").copy(inferred = true), expanded = true, onToggle = {}) } }
        compose.onNodeWithText("matched by position").assertIsDisplayed()
    }

    @Test fun headerToggleReportsTheNewState() {
        val calls = mutableListOf<Boolean>()
        compose.setContent { ArchieTheme(reduceMotion = true) { ToolCard(bash(), expanded = false, onToggle = { calls += it }) } }
        compose.onNodeWithText("Bash").performClick()
        assertEquals(listOf(true), calls)
    }

    @Test fun todoWriteIsOpenByDefault() {
        val todo = ToolBlock(
            "t1", "toolu_t", "TodoWrite",
            Json.parseToJsonElement("""{"todos":[{"content":"Port parity tests","status":"in_progress","activeForm":"Porting parity tests"},{"content":"Ship","status":"pending"}]}""").jsonObject,
            ToolStatus.DONE, "Todos have been modified successfully.",
        )
        compose.setContent { ArchieTheme(reduceMotion = true) { ToolCard(todo, autoOpen = false) } }
        compose.onNodeWithText("Porting parity tests").assertIsDisplayed()
        compose.onNodeWithText("0 of 2 done").assertIsDisplayed()
    }

    /** Every web sample (`__fixtures__/blocks.ts` SAMPLES, via the oracle): collapsed, running, done, error. */
    @Test fun everySampleCollapsedRunningDoneError() {
        val oracle = Json.parseToJsonElement(javaClass.classLoader!!.getResource("web-tool-oracle.json")!!.readText()).jsonObject
        val rows = oracle.getValue("tools").jsonArray.map { it.jsonObject }.filter { it.getValue("output").jsonPrimitive.content != "ok" }
        check(rows.size >= 30) { "expected the web samples in the oracle, got ${rows.size}" }
        var row by mutableStateOf(rows.first())
        var block by mutableStateOf(sampleBlock(rows.first(), ToolStatus.RUNNING, null))
        var expanded by mutableStateOf(false)
        compose.setContent {
            ArchieTheme(reduceMotion = true) {
                Column {
                    val kind = if (row["kind"]?.jsonPrimitive?.content == "orchestrator") SessionKind.ORCHESTRATOR else SessionKind.AGENT
                    ToolCard(block, expanded = expanded, onToggle = { expanded = it }, kind = kind)
                }
            }
        }
        for (r in rows) {
            val name = r.getValue("resolved").jsonObject.getValue("name").jsonPrimitive.content
            row = r
            expanded = false
            block = sampleBlock(r, ToolStatus.RUNNING, null)
            compose.waitForIdle()
            compose.onNodeWithTag("tool-card:$name").assertExists()
            compose.onNode(hasTestTag("tool-body")).assertDoesNotExist()
            expanded = true
            compose.onNodeWithTag("tool-output:running").assertExists()
            val output = r.getValue("output").jsonPrimitive.content
            block = sampleBlock(r, ToolStatus.DONE, output)
            compose.onNodeWithTag("tool-output:output").assertExists()
            block = sampleBlock(r, ToolStatus.ERROR, "Error: boom ($name)")
            compose.onNode(hasText("Error: boom ($name)", substring = true)).assertExists()
            expanded = false
            compose.onNode(hasTestTag("tool-body")).assertDoesNotExist()
        }
    }

    private fun sampleBlock(r: JsonObject, status: ToolStatus, output: String?) = ToolBlock(
        id = "s:${r.getValue("name").jsonPrimitive.content}:${r["input"].hashCode()}",
        toolUseId = "toolu_s",
        toolName = r.getValue("name").jsonPrimitive.content,
        toolInput = r.getValue("input").jsonObject,
        status = status,
        output = output,
    )
}
