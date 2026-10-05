package com.assistant.archie.feature.chat

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.assistant.archie.feature.chat.support.UiStates
import com.assistant.archie.feature.chat.ui.ConversationContent
import com.assistant.core.conversation.AgentApproval
import com.assistant.core.conversation.AssistantEntry
import com.assistant.core.conversation.BlockOrigin
import com.assistant.core.conversation.BlockScope
import com.assistant.core.conversation.ConversationState
import com.assistant.core.conversation.Counters
import com.assistant.core.conversation.Entry
import com.assistant.core.conversation.HistoryState
import com.assistant.core.conversation.Stall
import com.assistant.core.conversation.TextBlock
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.ToolProgressInfo
import com.assistant.core.conversation.ToolStatus
import com.assistant.core.conversation.UserEntry
import com.assistant.core.conversation.UserOrigin
import com.assistant.core.design.components.ArchieIconButton
import com.assistant.core.design.components.ArchieTopAppBar
import com.assistant.core.design.components.ProviderChip
import com.assistant.core.design.components.Spinner
import com.assistant.core.design.components.StatusDot
import com.assistant.core.design.components.TopAppBarSubtitle
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.design.theme.ThemeMode
import com.assistant.core.model.ConnectionState
import com.assistant.core.model.HarnessProvider
import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
import com.assistant.core.model.SessionStatus
import com.assistant.core.voice.ports.SessionPhase
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens of the conversation screen, reproducing the approved mockups
 * (`docs/frontend-refactor/mockups/archie-mockups.html`): phone (a) streaming tools + agent approval,
 * (d) voice, (i) empty Archie, (j) stall + error, (k) voice reconnecting (+ its two outcomes), and the
 * desktop conversation column. The top bar and status-bar band are a test frame standing in for the
 * shell (B-03 owns the real one), so the goldens compare 1:1 with the mockup frames.
 *
 *   flock /tmp/archie-locks/gradle.lock ./gradlew :feature:chat:recordRoborazziDebug   record
 *   flock /tmp/archie-locks/gradle.lock ./gradlew :feature:chat:verifyRoborazziDebug   compare
 */
@OptIn(ExperimentalRoborazziApi::class)
abstract class ConversationGoldenBase(private val size: String) {
    @get:Rule val compose = createComposeRule()

    protected fun scene(name: String, ui: ConversationUiState, draft: String = "", bar: (@Composable () -> Unit)? = null) {
        var mode by mutableStateOf(ThemeMode.Dark)
        compose.setContent {
            ArchieTheme(mode = mode, reduceMotion = true) {
                Column(Modifier.fillMaxSize().background(ArchieTheme.colors.surface)) {
                    if (bar != null) {
                        Spacer(Modifier.height(32.dp))   // mockup `.sbar`
                        bar()
                    }
                    ConversationContent(ui, draft, {}, Modifier.weight(1f), hour = 20, clock = { NOW })
                }
            }
        }
        for (theme in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            mode = theme
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            captureScreenRoboImage("$DIR/${name}_${size}_${theme.name.lowercase()}.png")
        }
    }

    companion object {
        const val DIR = "src/test/screenshots"
        const val NOW = 1_759_500_000_000L
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w443dp-h986dp-xxhdpi", application = Application::class)
class CompactConversationGoldens : ConversationGoldenBase("compact") {
    /** Phone (a): a live "2 steps" group streaming, the agent approval above the composer, Stop. */
    @Test fun chatStreamingTools() = scene("chat-streaming-tools", Scenes.a()) {
        ShellBar("Living-room TV", "Waiting for your approval", ArchieIcons.FrontHand, ArchieTheme.extended.warning.color, mark = true, speaker = true)
    }

    /** Phone (d): voice mode, the composer becomes the voice dock; transcripts in the timeline. */
    @Test fun chatVoiceActive() = scene("chat-voice-active", Scenes.d()) {
        ShellBar("Living-room TV", "Voice · Listening", ArchieIcons.GraphicEq, ArchieTheme.colors.primary, mark = true, speaker = true)
    }

    /** Phone (i): new Archie conversation, greeting, big voice button, suggestions; primary = Voice. */
    @Test fun chatEmpty() = scene("chat-empty", Scenes.i()) {
        ArchieTopAppBar(
            "New conversation",
            navigationIcon = { ArchieIconButton(ArchieIcons.Menu, "Open navigation", {}) },
            onTitleClick = {},
            subtitle = { StatusDot(size = 7.dp); TopAppBarSubtitle("Archie · connected") },
            actions = { ArchieIconButton(ArchieIcons.MoreVert, "Session menu", {}) },
        )
    }

    /** Phone (j): stall card + error card above the composer of a working agent. */
    @Test fun chatStallError() = scene("chat-stall-error", Scenes.j()) {
        ArchieTopAppBar(
            "Refactor voice module",
            navigationIcon = { ArchieIconButton(ArchieIcons.Menu, "Open navigation", {}) },
            onTitleClick = {},
            subtitle = { ProviderChip("Claude"); Spinner(size = 12.dp); TopAppBarSubtitle("Using WebFetch…") },
            actions = { ArchieIconButton(ArchieIcons.MoreVert, "Session menu", {}) },
        )
    }

    /** Phone (k): voice reconnecting, elapsed timer, End still works. */
    @Test fun chatVoiceReconnecting() = scene("chat-voice-reconnecting", Scenes.k(VoiceUi.Reconnecting(NOW - 7_000))) {
        ShellBar("Living-room TV", "Voice · Reconnecting…", ArchieIcons.Sync, ArchieTheme.extended.warning.color, mark = true, speaker = true)
    }

    /** Phone (k), first outcome: Reconnected. */
    @Test fun chatVoiceReconnected() = scene("chat-voice-reconnected", Scenes.k(VoiceUi.Reconnected(9))) {
        ShellBar("Living-room TV", "Voice · Listening", ArchieIcons.GraphicEq, ArchieTheme.colors.primary, mark = true, speaker = true)
    }

    /** Phone (k), second outcome: Couldn't reconnect, with Reconnect. */
    @Test fun chatVoiceReconnectFailed() = scene("chat-voice-reconnect-failed", Scenes.k(VoiceUi.ReconnectFailed("5 tries over 60 s · falling tone"))) {
        ShellBar("Living-room TV", "Voice ended", ArchieIcons.WifiOff, ArchieTheme.colors.error, mark = true, speaker = true)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w880dp-h1040dp-xhdpi", application = Application::class)
class ExpandedConversationGoldens : ConversationGoldenBase("expanded") {
    /** Desktop conversation column (the workspace pane beside rail + list pane is 880 dp wide). */
    @Test fun chatDesktopColumn() = scene("chat-desktop-column", Scenes.desktop(), draft = "Yes, commit it and open a PR")
}

@Composable
private fun ShellBar(title: String, subtitle: String, icon: ImageVector, tint: Color, mark: Boolean, speaker: Boolean) {
    ArchieTopAppBar(
        title,
        navigationIcon = { ArchieIconButton(ArchieIcons.Menu, "Open navigation", {}) },
        onTitleClick = {},
        showMark = mark,
        subtitle = { TopAppBarSubtitle(subtitle, icon, tint) },
        actions = {
            if (speaker) ArchieIconButton(ArchieIcons.VolumeUp, "Speaker on", {})
            ArchieIconButton(ArchieIcons.MoreVert, "Session menu", {})
        },
    )
}

/** The mockup contents as reducer-shaped state (entries and blocks exactly as spec 12 models them). */
object Scenes {
    private var n = 0
    private fun id(p: String) = "$p${n++}"

    private fun json(vararg kv: Pair<String, String>) = JsonObject(kv.associate { (k, v) -> k to JsonPrimitive(v) })

    fun user(text: String, origin: UserOrigin = UserOrigin.LOCAL, streaming: Boolean = false) = UserEntry(id("e"), text, origin, streaming = streaming)

    fun text(t: String, streaming: Boolean = false, voice: Boolean = false) =
        TextBlock(id("b"), t, streaming, if (voice) BlockScope.VOICE else BlockScope.TURN, BlockOrigin.LIVE)

    fun tool(
        tid: String,
        name: String,
        input: JsonObject,
        output: String? = null,
        status: ToolStatus = if (output != null) ToolStatus.DONE else ToolStatus.RUNNING,
        progress: ToolProgressInfo? = null,
    ) = ToolBlock(id("b"), tid, name, input, status, output, progress = progress)

    fun run(vararg blocks: com.assistant.core.conversation.Block) = AssistantEntry(id("e"), blocks.toList().toPersistentList())

    private fun state(kind: SessionKind, entries: List<Entry>, busy: Boolean, f: (ConversationState) -> ConversationState = { it }): ConversationState {
        val ref = SessionRef("L1", "sdk-1", kind, if (kind == SessionKind.AGENT) HarnessProvider.CLAUDE else null)
        return f(
            ConversationState(
                ref = ref,
                entries = entries.toPersistentList(),
                status = if (busy) SessionStatus.TOOL_USE else SessionStatus.IDLE,
                inTurn = busy,
                connection = ConnectionState.SUBSCRIBED,
                history = HistoryState(loaded = true),
            ),
        )
    }

    fun a(): ConversationUiState {
        val s = state(
            SessionKind.ORCHESTRATOR,
            listOf(
                user("Plan the living-room TV setup for movie night on Friday."),
                run(
                    text("On it. I'll check what the Fire TV can play, then ask an agent to draft the setup."),
                    tool("c1", "run_script", json("name" to "connect_tv.py"), "Connected to Fire TV · 10.0.0.42"),
                    tool(
                        "c2", "send_to_agent_session", json("session_id" to "L5", "message" to "TV setup plan"),
                        progress = ToolProgressInfo(41.0, "Read assistant/devices/fire_tv.md\nListed 23 installed apps\nDrafting plan, asking to exit plan mode"),
                    ),
                ),
            ),
            busy = true,
        ) {
            it.copy(
                agentApprovals = persistentListOf(
                    AgentApproval(
                        "L5", "r1", "ExitPlanMode",
                        json("plan" to "1. Check Kodi and Plex on the Fire TV\n2. Queue three films from the watchlist\n3. Set the soundbar to movie mode"),
                    ),
                ),
            )
        }
        return UiStates.of(s)
    }

    fun d(): ConversationUiState {
        val s = state(
            SessionKind.ORCHESTRATOR,
            listOf(
                user("Approve the plan.", UserOrigin.VOICE),
                run(
                    tool("v1", "respond_to_agent_permission", json("decision" to "allow"), "Approved ExitPlanMode"),
                    tool("v2", "run_script", json("name" to "tv_remote.py", "args" to "queue"), "3 titles queued"),
                    tool("v3", "soundbar", json("mode" to "movie"), "OK"),
                    text("Kodi and Plex are both installed. I queued the three films and set the soundbar to movie mode.", voice = true),
                ),
                user("Dim the living-room lights to thirty percent.", UserOrigin.VOICE),
                run(
                    tool("v4", "run_script", json("name" to "home_lights.py", "args" to "--room living --level 30"), "living-room: 30% (3 lamps)"),
                    text("Done, the lights are at 30 percent.", voice = true),
                ),
                user("And put the jazz playlist on the TV", UserOrigin.VOICE, streaming = true),
            ),
            busy = false,
        ) { it.copy(voiceActive = true) }
        return UiStates.of(s, voice = VoiceUi.Active(SessionPhase.ACTIVE, micMuted = false, speakerMuted = false))
    }

    fun i(): ConversationUiState = UiStates.of(state(SessionKind.ORCHESTRATOR, emptyList(), busy = false))

    fun j(): ConversationUiState {
        val s = state(
            SessionKind.AGENT,
            listOf(
                user("Make echo ducking use audio focus instead of lowering the stream volume."),
                run(
                    tool("g1", "Grep", json("pattern" to "duck", "path" to "android/"), "AudioDucker.kt: 9 matches"),
                    tool("g2", "Read", json("file_path" to "android/app/src/main/java/audio/AudioDucker.kt"), "184 lines"),
                    text("Ducking lives in `AudioDucker.kt`. Before I change it I'll check how Android hands out audio focus."),
                    tool(
                        "g3", "WebFetch", json("url" to "https://developer.android.com/media/optimize/audio-focus"),
                        progress = ToolProgressInfo(134.0, "Waiting for response…"),
                    ),
                ),
            ),
            busy = true,
        ) { it.copy(stall = Stall(120.0, "WebFetch", "g3")) }
        val ui = UiStates.of(s, title = "Refactor voice module")
        return ui.copy(
            cards = (
                ui.cards + InlineCardUi.Error(
                    "unsent",
                    "Message not sent",
                    "Lost the connection to jetson (WebSocket closed, code 1006). Your message is kept and sends when you retry.",
                    detail = "close 1006",
                    retry = RetryKind.Resend,
                )
                ).toPersistentList(),
        )
    }

    fun k(voice: VoiceUi): ConversationUiState {
        val s = state(
            SessionKind.ORCHESTRATOR,
            listOf(
                user("Dim the living-room lights to thirty percent.", UserOrigin.VOICE),
                run(
                    tool("k1", "run_script", json("name" to "home_lights.py", "args" to "--room living --level 30"), "living-room: 30% (3 lamps)"),
                    text("Done, the lights are at 30 percent.", voice = true),
                ),
                user("And put the jazz playlist on the TV.", UserOrigin.VOICE),
                run(text("Starting the jazz playlist on the", streaming = true, voice = true)),
            ),
            busy = false,
        ) { it.copy(voiceActive = true) }
        return UiStates.of(s, voice = voice)
    }

    fun desktop(): ConversationUiState {
        val s = state(
            SessionKind.AGENT,
            listOf(
                run(
                    tool("d1", "Grep", json("pattern" to "_voice", "path" to "orchestrator/"), "orchestrator/session.py: 14 matches\norchestrator/providers/openai_voice.py: 6 matches"),
                    tool("d2", "Read", json("file_path" to "orchestrator/session.py"), "642 lines"),
                    tool("d3", "Read", json("file_path" to "orchestrator/providers/openai_voice.py"), "388 lines"),
                    text(
                        "Voice state is split between `OrchestratorSession` and `OpenAIVoiceProvider`, and both flip `_voice` directly. " +
                            "That is why `end_voice` can return early. I'll move every transition into one `VoiceStateMachine` and keep the public API as it is.",
                    ),
                ),
                user("Go ahead. Keep the parity tests green."),
                run(
                    text("Extracting the state machine now."),
                    tool("d4", "Read", json("file_path" to "orchestrator/voice/state.py"), "  1  class VoiceState(Enum):\n  2      IDLE = \"idle\"; LISTENING = \"listening\"; SPEAKING = \"speaking\""),
                    tool("d5", "Grep", json("pattern" to "self._voice =", "path" to "orchestrator/"), "session.py:212    self._voice = True\nsession.py:388    self._voice = False"),
                    tool("d6", "Edit", json("file_path" to "orchestrator/session.py"), "@@ -386,4 +386,2 @@ async def end_voice(self, reason):\n-        if self._state == VoiceState.IDLE:\n-            return\n-        self._voice = False\n+        self._voice_sm.end(reason)"),
                    tool("d7", "Bash", json("command" to "pytest tests/test_voice_parity.py -q"), "..............................................  [100%]\n46 passed in 3.82s · exit 0"),
                    text("Done. `VoiceStateMachine` owns every transition, `end_voice` no longer skips cleanup when idle, and all **46 parity tests** pass. Should I commit this on `voice-refactor`?"),
                ),
            ),
            busy = false,
        ) { it.copy(counters = Counters(cost = 0.82, turns = 14, contextTokens = 84_000, contextWindow = 200_000)) }
        // The user opened the second group (a toggle wins over the automatic collapse).
        val expandedGroup = "g:t:d4"
        return UiStates.of(s, FlattenOptions(groupToggles = mapOf(expandedGroup to true), cardToggles = listOf("d4", "d5", "d6", "d7").associate { "t:$it" to true }), draft = "x", title = "Refactor voice module")
    }
}
