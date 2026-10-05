package com.assistant.archie.feature.toolcards

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.assistant.archie.feature.toolcards.ui.ToolCard
import com.assistant.core.conversation.BlockOrigin
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.ToolProgressInfo
import com.assistant.core.conversation.ToolStatus
import com.assistant.core.design.Corner
import com.assistant.core.design.components.ToolCardPlacement
import com.assistant.core.design.components.ToolGroup
import com.assistant.core.design.components.ToolGroupHeader
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.design.theme.ThemeMode
import com.assistant.core.model.SessionKind
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.assistant.core.design.components.ToolStatus as ShellStatus

/**
 * Roborazzi goldens (spec 14 §6.4, B-05 DoD): one board per renderer — collapsed (done), expanded
 * running, expanded done, expanded error — in both themes, plus the approved mockup boards
 * (`docs/frontend-refactor/mockups/archie-mockups.html` "Tool cards, all 12 categories" and the
 * "N steps" groups) rebuilt from real tool calls through the real catalog.
 *
 *   flock /tmp/archie-locks/gradle.lock ./gradlew :feature:toolcards:recordRoborazziDebug   record
 *   flock /tmp/archie-locks/gradle.lock ./gradlew :feature:toolcards:verifyRoborazziDebug   compare
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2600dp-xhdpi", application = Application::class)
class ToolCardGoldens {
    @get:Rule val compose = createComposeRule()

    @Before fun fixClock() {
        ToolTiming.reset()
        ToolTiming.clock = { NOW }
    }

    @After fun restoreClock() {
        ToolTiming.clock = System::currentTimeMillis
        ToolTiming.reset()
    }

    private fun board(name: String, content: @Composable ColumnScope.() -> Unit) {
        var mode by mutableStateOf(ThemeMode.Dark)
        compose.setContent {
            ArchieTheme(mode = mode, reduceMotion = true) {
                Column(
                    Modifier.width(412.dp).background(ArchieTheme.colors.surface).padding(20.dp).testTag("board"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
            }
        }
        for (theme in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            mode = theme
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            compose.onNodeWithTag("board").captureRoboImage("$DIR/${name}_${theme.name.lowercase()}.png")
        }
    }

    /* ---------- one board per renderer ---------- */

    private val oracleRows: List<JsonObject> by lazy {
        Json.parseToJsonElement(javaClass.classLoader!!.getResource("web-tool-oracle.json")!!.readText()).jsonObject
            .getValue("tools").jsonArray.map { it.jsonObject }
    }

    /** The web sample of a renderer (its richest input), else its first oracle row. */
    private fun sampleFor(body: ToolBody): JsonObject {
        val rows = oracleRows.filter { it.getValue("resolved").jsonObject.getValue("body").jsonPrimitive.content == "${body.name}Body" }
        return rows.firstOrNull { it.getValue("output").jsonPrimitive.content != "ok" } ?: rows.first()
    }

    private fun renderer(body: ToolBody) {
        val row = sampleFor(body)
        val kind = if (row["kind"]?.jsonPrimitive?.content == "orchestrator") SessionKind.ORCHESTRATOR else SessionKind.AGENT
        val name = row.getValue("name").jsonPrimitive.content
        val input = row.getValue("input").jsonObject
        val output = row.getValue("output").jsonPrimitive.content
        fun b(id: String, status: ToolStatus, out: String?, progress: Double? = null) =
            ToolBlock("$id-${body.id}", "toolu_$id", name, input, status, out, progress = progress?.let { ToolProgressInfo(it, null) })
        board("toolcard-${body.id}") {
            Caption("Collapsed · done")
            ToolCard(b("c", ToolStatus.DONE, output, ), expanded = false, onToggle = {}, kind = kind)
            Caption("Expanded · running")
            ToolCard(b("r", ToolStatus.RUNNING, null, progress = 12.0), expanded = true, onToggle = {}, kind = kind)
            Caption("Expanded · done")
            ToolCard(b("d", ToolStatus.DONE, output), expanded = true, onToggle = {}, kind = kind)
            Caption("Expanded · error")
            ToolCard(b("e", ToolStatus.ERROR, ERRORS[body] ?: "Error: permission denied"), expanded = true, onToggle = {}, kind = kind)
        }
    }

    @Test fun read() = renderer(ToolBody.Read)
    @Test fun orchestratorRead() = renderer(ToolBody.OrchestratorRead)
    @Test fun write() = renderer(ToolBody.Write)
    @Test fun orchestratorWrite() = renderer(ToolBody.OrchestratorWrite)
    @Test fun edit() = renderer(ToolBody.Edit)
    @Test fun multiEdit() = renderer(ToolBody.MultiEdit)
    @Test fun notebookEdit() = renderer(ToolBody.NotebookEdit)
    @Test fun glob() = renderer(ToolBody.Glob)
    @Test fun grep() = renderer(ToolBody.Grep)
    @Test fun listFiles() = renderer(ToolBody.ListFiles)
    @Test fun webFetch() = renderer(ToolBody.WebFetch)
    @Test fun webSearch() = renderer(ToolBody.WebSearch)
    @Test fun bash() = renderer(ToolBody.Bash)
    @Test fun shellId() = renderer(ToolBody.ShellId)
    @Test fun skill() = renderer(ToolBody.Skill)
    @Test fun plan() = renderer(ToolBody.Plan)
    @Test fun todo() = renderer(ToolBody.Todo)
    @Test fun task() = renderer(ToolBody.Task)
    @Test fun askUserQuestion() = renderer(ToolBody.AskUserQuestion)
    @Test fun runScript() = renderer(ToolBody.RunScript)
    @Test fun evaluateScript() = renderer(ToolBody.EvaluateScript)
    @Test fun sendToAgent() = renderer(ToolBody.SendToAgent)
    @Test fun agentSession() = renderer(ToolBody.AgentSession)
    @Test fun search() = renderer(ToolBody.Search)
    @Test fun generic() = renderer(ToolBody.Generic)

    /* ---------- mockup boards ---------- */

    /** Mockup "Tool cards, all 12 categories", one real call per category. */
    @Test fun boardCategories() {
        val cards = Mockup.categories()
        board("tool-board-categories") {
            cards.forEach { (block, kind, open) ->
                ToolCard(block, expanded = open, onToggle = {}, kind = kind, stalled = block.toolName == "WebFetch")
            }
        }
    }

    /** Mockup "N steps" groups: a finished group opened (Read, Grep, Edit, Bash), a folded one, a live one. */
    @Test fun boardGroups() {
        board("tool-board-groups") {
            Mockup.groups().forEach { g -> Group(g.blocks, g.expanded, g.kind, g.feed) }
            Caption("Solo card, folded")
            val solo = Mockup.solo()
            ToolCard(solo, expanded = false, onToggle = {}, kind = SessionKind.ORCHESTRATOR)
        }
    }

    @Composable
    private fun Group(blocks: List<ToolBlock>, expanded: Boolean, kind: SessionKind, feed: Map<String, List<String>>) {
        val tools = blocks.map { ToolCatalog.resolve(it, kind) }
        val running = blocks.any { it.status == ToolStatus.RUNNING }
        val status = when {
            running -> ShellStatus.Running
            blocks.any { it.status == ToolStatus.ERROR } -> ShellStatus.Error
            else -> ShellStatus.Done
        }
        val counts = LinkedHashMap<String, Int>()
        tools.forEach { counts[it.label] = (counts[it.label] ?: 0) + 1 }
        val summary = if (running && expanded) "running" else counts.entries.joinToString(", ") { (l, n) -> if (n > 1) "$l ×$n" else l }
        ToolGroup(
            header = {
                ToolGroupHeader(
                    count = blocks.size,
                    tiles = tools.map { it.spec.category to (ArchieIcons.named(it.spec.icon) ?: ArchieIcons.Build) },
                    summary = summary,
                    status = status,
                    expanded = expanded,
                    onToggle = {},
                )
            },
            expanded = expanded,
        ) {
            blocks.forEachIndexed { i, b ->
                val shape = when (i) {
                    0 -> RoundedCornerShape(topStart = Corner.Large, topEnd = Corner.Large, bottomStart = Corner.ExtraSmall, bottomEnd = Corner.ExtraSmall)
                    blocks.lastIndex -> RoundedCornerShape(topStart = Corner.ExtraSmall, topEnd = Corner.ExtraSmall, bottomStart = Corner.Large, bottomEnd = Corner.Large)
                    else -> RoundedCornerShape(Corner.ExtraSmall)
                }
                ToolCard(
                    b, expanded = true, onToggle = {}, modifier = Modifier.clip(shape), kind = kind,
                    placement = ToolCardPlacement.Grouped, feed = feed[b.toolUseId],
                    feedTitle = if (feed.containsKey(b.toolUseId)) "Live · TV setup plan (Claude)" else null,
                )
            }
        }
    }

    @Composable
    private fun Caption(text: String) {
        Text(text, style = ArchieTheme.typography.labelMedium, color = ArchieTheme.colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }

    companion object {
        const val DIR = "src/test/screenshots"
        const val NOW = 1_759_500_000_000L

        private val ERRORS = mapOf(
            ToolBody.Bash to "Error: Exit code 1\nnpm ERR! Missing script: \"build\"",
            ToolBody.SendToAgent to "Session is not open (404). Reopen it from history.",
            ToolBody.Read to "File does not exist.",
            ToolBody.Edit to "String to replace not found in file.",
            ToolBody.WebFetch to "Request failed with status code 503",
        )
    }
}

/** Real tool calls that reproduce the approved mockup boards. */
internal object Mockup {
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private var n = 0
    private fun call(
        name: String,
        input: String,
        status: ToolStatus = ToolStatus.DONE,
        output: String? = null,
        durationMs: Long? = null,
        progress: Double? = null,
    ): ToolBlock {
        n += 1
        val b = ToolBlock("m$n", "toolu_m$n", name, json(input), status, output, progress = progress?.let { ToolProgressInfo(it, null) }, origin = BlockOrigin.LIVE)
        if (durationMs != null) seed(b, durationMs)
        return b
    }

    /** Records "first seen running" [ms] before the fixed golden clock, so the card shows a duration. */
    private fun seed(b: ToolBlock, ms: Long) {
        val now = ToolTiming.clock
        ToolTiming.clock = { ToolCardGoldens.NOW - ms }
        ToolTiming.of(b.copy(status = ToolStatus.RUNNING, output = null))
        ToolTiming.clock = now
    }

    data class Card(val block: ToolBlock, val kind: SessionKind, val open: Boolean)

    fun categories(): List<Card> = listOf(
        Card(call("Read", """{"file_path":"/home/rodrigo/assistant/orchestrator/session.py","offset":211,"limit":3}""", output = "   212→    def end_voice(self, reason):\n   213→        if self._state == VoiceState.IDLE:\n   214→            return", durationMs = 100), SessionKind.AGENT, true),
        Card(call("Edit", """{"file_path":"/home/rodrigo/assistant/api/routes/voice.py","old_string":"    ttl = 60\n","new_string":"    ttl = settings.voice_token_ttl\n"}""", output = "The file api/routes/voice.py has been updated."), SessionKind.AGENT, true),
        Card(call("Bash", """{"command":"cd frontend-next && npm run build","description":"Build the web app"}""", ToolStatus.RUNNING, progress = 12.0), SessionKind.AGENT, true),
        Card(call("run_script", """{"script":"context/scripts/browser_cmd.py","args":["look"]}""", output = """{"exit_code": 0, "stdout": "Snapshot: 38 refs · screenshot 1280×800\n", "stderr": ""}""", durationMs = 1_400), SessionKind.ORCHESTRATOR, true),
        Card(call("WebFetch", """{"url":"https://developer.android.com/media/optimize/audio-focus","prompt":"How does ducking work?"}""", ToolStatus.RUNNING, progress = 134.0), SessionKind.AGENT, true),
        Card(call("mcp__chrome-devtools__take_screenshot", """{"filePath":"/tmp/living-room-tv.png"}""", output = "1920×1080 · 412 KB"), SessionKind.AGENT, true),
        Card(call("mcp__chrome-devtools__click", """{"uid":"14"}""", output = "Clicked · page navigated"), SessionKind.AGENT, true),
        Card(
            call(
                "TodoWrite",
                """{"todos":[{"content":"Extract VoiceStateMachine","status":"completed"},{"content":"Move transitions","status":"completed"},{"content":"Add state machine tests","status":"completed"},{"content":"Port parity tests","status":"in_progress","activeForm":"Porting parity tests"},{"content":"Update voice_subsystem.md","status":"pending"}]}""",
                output = "Todos have been modified successfully.",
            ),
            SessionKind.AGENT, true,
        ),
        Card(call("Task", """{"description":"where is voice state set?","subagent_type":"Explore","prompt":"Find every place that assigns _voice."}""", ToolStatus.RUNNING, progress = 31.0), SessionKind.AGENT, true),
        Card(call("update_assistant_config", """{"context_compaction":"auto"}""", output = "Summarized 38 turns"), SessionKind.ORCHESTRATOR, true),
        Card(call("send_to_agent_session", """{"session_id":"4f2a91c0-7d1e-4b8b","message":"Energy dashboard"}""", ToolStatus.ERROR, output = "Session is not open (404). Reopen it from history."), SessionKind.ORCHESTRATOR, true),
        Card(call("Grep", """{"pattern":"_voice =","path":"orchestrator/"}""", output = "session.py:212   self._voice = True\nsession.py:388   self._voice = False", durationMs = 200), SessionKind.AGENT, false),
    )

    data class Group(val blocks: List<ToolBlock>, val expanded: Boolean, val kind: SessionKind, val feed: Map<String, List<String>> = emptyMap())

    fun groups(): List<Group> {
        val live = call("send_to_agent_session", """{"session_id":"4f2a91c0-7d1e","message":"TV setup plan"}""", ToolStatus.RUNNING, progress = 41.0)
        return listOf(
            Group(
                listOf(
                    call("Grep", """{"pattern":"_voice","path":"orchestrator/"}""", output = "orchestrator/session.py: 14 matches", durationMs = 200),
                    call("Read", """{"file_path":"orchestrator/session.py"}""", output = "642 lines", durationMs = 100),
                    call("Read", """{"file_path":"orchestrator/providers/openai_voice.py"}""", output = "388 lines", durationMs = 100),
                ),
                expanded = false, kind = SessionKind.AGENT,
            ),
            Group(
                listOf(
                    call("Read", """{"file_path":"orchestrator/voice/state.py","limit":2}""", output = "  1  class VoiceState(Enum):\n  2      IDLE = \"idle\"; LISTENING = \"listening\"; SPEAKING = \"speaking\"", durationMs = 100),
                    call("Grep", """{"pattern":"self._voice =","path":"orchestrator/"}""", output = "session.py:212    self._voice = True\nsession.py:388    self._voice = False", durationMs = 200),
                    call(
                        "Edit",
                        """{"file_path":"orchestrator/session.py","old_string":"        if self._state == VoiceState.IDLE:\n            return\n        self._voice = False\n","new_string":"        self._voice_sm.end(reason)\n"}""",
                        output = "The file orchestrator/session.py has been updated.",
                    ),
                    call(
                        "Bash",
                        """{"command":"pytest tests/test_voice_parity.py -q","description":"Run the voice parity tests"}""",
                        output = "..............................................  [100%]\n\u001b[32m46 passed\u001b[0m in 3.82s",
                        durationMs = 3_800,
                    ),
                ),
                expanded = true, kind = SessionKind.AGENT,
            ),
            Group(
                listOf(call("run_script", """{"script":"context/scripts/connect_tv.py"}""", output = "Connected to Fire TV · 10.0.0.42"), live),
                expanded = true, kind = SessionKind.ORCHESTRATOR,
                feed = mapOf(live.toolUseId to listOf("Read assistant/devices/fire_tv.md", "Listed 23 installed apps", "Drafting plan, asking to exit plan mode")),
            ),
        )
    }

    fun solo(): ToolBlock = call("run_script", """{"script":"context/scripts/home_lights.py","args":["--room","living","--level","30"]}""", output = "living-room: 30% (3 lamps)")
}
