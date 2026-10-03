package com.assistant.core.design.catalog

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.assistant.core.design.ToolCategory
import com.assistant.core.design.components.ArchieButton
import com.assistant.core.design.components.ArchieChip
import com.assistant.core.design.components.ArchieDialogSurface
import com.assistant.core.design.components.ArchieExtendedFab
import com.assistant.core.design.components.ArchieFab
import com.assistant.core.design.components.ArchieIconButton
import com.assistant.core.design.components.ArchieListItem
import com.assistant.core.design.components.ArchieMark
import com.assistant.core.design.components.ArchieMenuItem
import com.assistant.core.design.components.ArchieMenuPanel
import com.assistant.core.design.components.ArchieMenuSeparator
import com.assistant.core.design.components.ArchieSnackbar
import com.assistant.core.design.components.ArchieSwitch
import com.assistant.core.design.components.ArchieTextField
import com.assistant.core.design.components.ArchieTopAppBar
import com.assistant.core.design.components.ButtonSize
import com.assistant.core.design.components.ButtonStyle
import com.assistant.core.design.components.ComposerPrimary
import com.assistant.core.design.components.ComposerShell
import com.assistant.core.design.components.ComposerTextField
import com.assistant.core.design.components.ContextRing
import com.assistant.core.design.components.DotTone
import com.assistant.core.design.components.EmptyState
import com.assistant.core.design.components.FabSize
import com.assistant.core.design.components.IconButtonStyle
import com.assistant.core.design.components.InlineCard
import com.assistant.core.design.components.InlineCardAction
import com.assistant.core.design.components.InlineCardKind
import com.assistant.core.design.components.LevelSlider
import com.assistant.core.design.components.ListItemSize
import com.assistant.core.design.components.ListLeadingIcon
import com.assistant.core.design.components.ListLeadingTile
import com.assistant.core.design.components.ListMeta
import com.assistant.core.design.components.ListSectionHeader
import com.assistant.core.design.components.LiveStatus
import com.assistant.core.design.components.OutputLineKind
import com.assistant.core.design.components.ProviderChip
import com.assistant.core.design.components.ReconnectOutcome
import com.assistant.core.design.components.ScopeChip
import com.assistant.core.design.components.SearchPill
import com.assistant.core.design.components.SegmentOption
import com.assistant.core.design.components.SegmentedChoice
import com.assistant.core.design.components.SettingsFieldSet
import com.assistant.core.design.components.SettingsGroup
import com.assistant.core.design.components.SettingsGroupHeader
import com.assistant.core.design.components.SettingsRow
import com.assistant.core.design.components.SliderBubbleHeadroom
import com.assistant.core.design.components.StatusDot
import com.assistant.core.design.components.StatusIndicator
import com.assistant.core.design.components.StatusLabel
import com.assistant.core.design.components.SuggestionChip
import com.assistant.core.design.components.SwitchRow
import com.assistant.core.design.components.SystemLine
import com.assistant.core.design.components.TabLead
import com.assistant.core.design.components.TextFieldStyle
import com.assistant.core.design.components.ToolCardPlacement
import com.assistant.core.design.components.ToolCardShell
import com.assistant.core.design.components.ToolGroup
import com.assistant.core.design.components.ToolGroupHeader
import com.assistant.core.design.components.ToolMetaDiffCount
import com.assistant.core.design.components.ToolOutput
import com.assistant.core.design.components.ToolOutputLine
import com.assistant.core.design.components.ToolStatus
import com.assistant.core.design.components.TopAppBarSubtitle
import com.assistant.core.design.components.TopBarTab
import com.assistant.core.design.components.VoiceDock
import com.assistant.core.design.components.VoiceDockControls
import com.assistant.core.design.components.VoiceDockElsewhere
import com.assistant.core.design.components.VoiceDockOutcome
import com.assistant.core.design.components.VoiceDockReconnecting
import com.assistant.core.design.components.VoiceDockState
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.design.theme.ThemeMode

/*
 * The component sheet (mockup §5) as Compose boards. Each board is one golden scene
 * (`<scene>_<compact|medium|expanded>_<dark|light>.png`) and one preview. Sample copy is the
 * mockup's, so goldens can be compared with the approved design side by side.
 */

/** Scene name → board, in mockup order. */
val ComponentBoards: List<Pair<String, @Composable () -> Unit>> = listOf(
    "buttons" to { ButtonsBoard() },
    "icon-buttons-fabs" to { IconButtonsBoard() },
    "chips-status" to { ChipsBoard() },
    "selection" to { SelectionBoard() },
    "text-fields" to { TextFieldsBoard() },
    "top-bar-tabs" to { TabsBoard() },
    "snackbars" to { SnackbarsBoard() },
    "composer" to { ComposerBoard() },
    "voice-dock" to { VoiceDockBoard() },
    "menu-dialog" to { MenuDialogBoard() },
    "inline-cards" to { InlineCardsBoard() },
    "tool-cards" to { ToolCardsBoard() },
    "tool-group" to { ToolGroupBoard() },
    "list-items" to { ListItemsBoard() },
    "settings" to { SettingsBoard() },
    "top-app-bar" to { TopAppBarBoard() },
    "empty-state" to { EmptyStateBoard() },
)

private val noop: () -> Unit = {}

@Composable
fun ButtonsBoard() = BoardFrame("Buttons", "40 dp, full radius") {
    SpecRow {
        ArchieButton("Approve", noop, style = ButtonStyle.Filled)
        ArchieButton("Save", noop, style = ButtonStyle.Tonal)
        ArchieButton("Reject", noop, style = ButtonStyle.Outlined)
        ArchieButton("Details", noop, style = ButtonStyle.Text)
        ArchieButton("Retry", noop, style = ButtonStyle.Elevated)
    }
    SpecRow {
        ArchieButton("Send", noop, icon = ArchieIcons.Send)
        ArchieButton("Show on TV", noop, style = ButtonStyle.Tonal, icon = ArchieIcons.Cast)
        ArchieButton("New agent", noop, style = ButtonStyle.Outlined, icon = ArchieIcons.Terminal)
        ArchieButton("Disabled", noop, enabled = false)
    }
    SpecRow {
        ArchieButton("Talk to Archie", noop, size = ButtonSize.Large, icon = ArchieIcons.GraphicEq)
        ArchieButton("Interrupt", noop, style = ButtonStyle.Warning)
        ArchieButton("Reconnect", noop, style = ButtonStyle.Danger, size = ButtonSize.Small, icon = ArchieIcons.Refresh)
    }
}

@Composable
fun IconButtonsBoard() = BoardFrame("Icon buttons and FABs", "48 dp targets") {
    SpecRow {
        ArchieIconButton(ArchieIcons.MoreVert, "Standard", noop)
        ArchieIconButton(ArchieIcons.Mic, "Outlined", noop, style = IconButtonStyle.Outlined)
        ArchieIconButton(ArchieIcons.VolumeUp, "Tonal", noop, style = IconButtonStyle.Tonal)
        ArchieIconButton(ArchieIcons.ArrowUpward, "Filled", noop, style = IconButtonStyle.Filled)
        ArchieIconButton(ArchieIcons.MicOffFilled, "Selected", noop, style = IconButtonStyle.Selected, checked = true)
        ArchieIconButton(ArchieIcons.CallEndFilled, "End", noop, style = IconButtonStyle.Error)
        ArchieIconButton(ArchieIcons.ArrowUpward, "Disabled", noop, style = IconButtonStyle.Filled, enabled = false)
    }
    SpecRow(gap = 16.dp) {
        ArchieFab(ArchieIcons.Add, "Small FAB", noop, size = FabSize.Small)
        ArchieFab(ArchieIcons.Add, "FAB", noop)
        ArchieFab(ArchieIcons.GraphicEq, "Large FAB", noop, size = FabSize.Large)
        ArchieExtendedFab("New", ArchieIcons.Add, noop)
    }
}

@Composable
fun ChipsBoard() = BoardFrame("Chips", "assist · filter · input · provider") {
    SpecRow {
        ArchieChip("Plan the TV setup", noop, leadingIcon = ArchieIcons.Tv)
        ArchieChip("Archie", noop, leadingIcon = ArchieIcons.Check, selected = true)
        ArchieChip("Agents", noop)
        ArchieChip("refactor.md", noop, onRemove = noop)
    }
    SpecRow {
        ProviderChip("Claude")
        ProviderChip("Qwen")
        ProviderChip("Gemini")
        ScopeChip("Archie (server)", ArchieIcons.Dns)
        ScopeChip("This device", ArchieIcons.Mobile)
    }
    SpecRow {
        StatusLabel(LiveStatus.Idle, "Open, idle")
        StatusLabel(LiveStatus.Working, "Working")
        StatusLabel(LiveStatus.NeedsYou, "Needs you")
        StatusLabel(LiveStatus.Disconnected, "Disconnected")
    }
    SpecRow {
        StatusDot()
        StatusDot(tone = DotTone.Off)
        StatusDot(tone = DotTone.Warning)
        StatusDot(tone = DotTone.Error)
    }
}

@Composable
fun SelectionBoard() = BoardFrame("Selection", "switch · slider · segmented") {
    SpecRow {
        ArchieSwitch(checked = true, onCheckedChange = {})
        ArchieSwitch(checked = false, onCheckedChange = {})
        ArchieSwitch(checked = true, onCheckedChange = {}, enabled = false)
    }
    Box(Modifier.padding(top = SliderBubbleHeadroom).widthIn(max = 560.dp)) {
        LevelSlider(value = 0.3f, onValueChange = {}, valueRange = 0f..0.7f, showValueLabel = true)
    }
    Box(Modifier.widthIn(max = 560.dp)) {
        SegmentedChoice(
            listOf(
                SegmentOption("System", ArchieIcons.BrightnessAuto),
                SegmentOption("Dark", ArchieIcons.DarkMode),
                SegmentOption("Light", ArchieIcons.LightMode),
            ),
            selectedIndex = 1,
            onSelect = {},
        )
    }
}

@Composable
fun TextFieldsBoard() = BoardFrame("Text fields", "outlined · filled · error") {
    // Drawn focused, as in the mockup (a focus interaction, so it also shows in screenshots).
    val focused = remember { MutableInteractionSource() }
    AdaptiveGrid(
        listOf(
            {
                ArchieTextField(
                    "archie.local", {}, "Server address",
                    supportingText = "Port 8765 is added for you",
                    interactionSource = focused,
                )
            },
            { ArchieTextField("Hey Archie", {}, "Wake phrase", style = TextFieldStyle.Filled) },
            {
                ArchieTextField(
                    "jetson.local:22a", {}, "SSH host",
                    errorText = "Port must be a number, e.g. jetson.local:22",
                )
            },
        ),
        minCell = 300.dp,
        gap = 16.dp,
    )
    LaunchedEffect(Unit) {
        // The field collects interactions inside the grid's subcomposition, which subscribes a
        // frame later; emitting earlier would be lost.
        repeat(2) { withFrameNanos { } }
        focused.emit(FocusInteraction.Focus())
    }
}

@Composable
fun TabsBoard() = BoardFrame("Top-bar tabs", "44 dp, tone only") {
    // Wrapped so every state shows; in the app bar the same tabs sit in a scrolling TabStrip.
    SpecRow(gap = 6.dp) {
        TopBarTab("Living-room TV", TabLead.Archie, active = true, onClick = noop, onClose = noop, status = LiveStatus.Idle)
        TopBarTab(
            "Refactor voice", TabLead.Icon(ArchieIcons.Terminal), active = false, onClick = noop, onClose = noop,
            provider = "Claude", status = LiveStatus.Working, hovered = true,
        )
        TopBarTab(
            "Gemini review", TabLead.Icon(ArchieIcons.Terminal), active = false, onClick = noop, onClose = noop,
            provider = "Gemini", status = LiveStatus.Disconnected,
        )
        TopBarTab("voice_subsystem.md", TabLead.Icon(ArchieIcons.Description), active = false, onClick = noop, onClose = noop)
    }
    Text(
        "Active (filled tone), hover (state layer, close shown), disconnected (warning).",
        style = ArchieTheme.typography.bodySmall,
        color = ArchieTheme.colors.onSurfaceVariant,
    )
}

@Composable
fun SnackbarsBoard() = BoardFrame("Snackbars", "every save confirms") {
    Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ArchieSnackbar("Saved")
        ArchieSnackbar("Server said: \"voice model gpt-realtime-mini not available\"", actionLabel = "Retry")
        ArchieSnackbar("Showing on Living-room TV", actionLabel = "Stop", onDismiss = noop)
    }
}

@Composable
fun ComposerBoard() = BoardFrame("Composer states", "primary button morphs") {
    Column(Modifier.widthIn(max = 840.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SampleComposer("", "Message Archie…", ComposerPrimary.Voice)
        SampleComposer("Dim the lights", "Message Archie…", ComposerPrimary.Send, focused = true)
        SampleComposer("", "Queue a message…", ComposerPrimary.Stop)
        SampleComposer("", "Message Refactor voice module…", ComposerPrimary.SendDisabled, ring = 0.42f)
    }
    Text(
        "Empty (Archie): Voice → text: Send → working and empty: Stop. Agent sessions, empty: Send disabled.",
        style = ArchieTheme.typography.bodySmall,
        color = ArchieTheme.colors.onSurfaceVariant,
    )
}

@Composable
private fun SampleComposer(text: String, placeholder: String, mode: ComposerPrimary, focused: Boolean = false, ring: Float? = null) {
    ComposerShell(
        primary = mode,
        onPrimary = noop,
        focused = focused,
        leading = { ArchieIconButton(ArchieIcons.Add, "Add", noop) },
        trailing = {
            if (ring != null) ContextRing(ring, noop)
            ArchieIconButton(ArchieIcons.Mic, "Record voice message", noop)
        },
    ) {
        ComposerTextField(text, {}, placeholder)
    }
}

@Composable
fun VoiceDockBoard() = BoardFrame("Voice dock", "own device · elsewhere · reconnecting") {
    AdaptiveGrid(
        listOf(
            {
                VoiceDock(VoiceDockState.Speaking, "Tap the orb to interrupt") {
                    VoiceDockControls(false, noop, true, noop, noop)
                }
            },
            {
                VoiceDock(VoiceDockState.Listening, "Speak any time") {
                    VoiceDockControls(false, noop, false, noop, noop)
                }
            },
            { VoiceDockElsewhere("Pixel 8", noop) },
            { VoiceDockReconnecting("0:07", noop) },
            { VoiceDockOutcome(ReconnectOutcome.Reconnected, "Reconnected", "Back after 0:09 · rising tone · dock returns to Listening") },
            { VoiceDockOutcome(ReconnectOutcome.Failed, "Couldn't reconnect", "5 tries over 60 s · falling tone") },
        ),
        minCell = 380.dp,
        maxWidth = 840.dp,
    )
    Box(Modifier.fillMaxWidth().widthIn(max = 840.dp), contentAlignment = Alignment.Center) {
        SystemLine("Connection lost at 21:14:32", icon = ArchieIcons.WifiOff)
    }
}

@Composable
fun MenuDialogBoard() = BoardFrame("Menu and dialog", "session ⋮ menu · confirmation") {
    SpecRow(gap = 24.dp) {
        ArchieMenuPanel {
            ArchieMenuItem("Rename", noop, icon = ArchieIcons.Edit, shortcut = "F2")
            ArchieMenuItem("Session settings", noop, icon = ArchieIcons.Tune)
            ArchieMenuItem("Compact context", noop, icon = ArchieIcons.Compress)
            ArchieMenuItem("Fork", noop, icon = ArchieIcons.CallSplit)
            ArchieMenuSeparator()
            ArchieMenuItem("Close", noop, icon = ArchieIcons.Close, shortcut = "Ctrl+W")
            ArchieMenuItem("Delete", noop, icon = ArchieIcons.Delete, destructive = true)
        }
        ArchieDialogSurface(
            "Delete this session?",
            body = { Text("\"Refactor voice module\" and its 14 turns are removed from history on jetson. Memory files are kept.") },
        ) {
            ArchieButton("Cancel", noop, style = ButtonStyle.Text)
            ArchieButton(
                "Delete", noop, style = ButtonStyle.Text,
                colors = com.assistant.core.design.components.ArchieButtonColors(androidx.compose.ui.graphics.Color.Transparent, ArchieTheme.colors.error),
            )
        }
    }
}

@Composable
fun InlineCardsBoard() = BoardFrame("Inline cards", "above the composer, never pinned to an edge") {
    AdaptiveGrid(
        listOf(
            {
                InlineCard(
                    InlineCardKind.Permission, "Allow Bash: rm -rf build/",
                    body = { Text("Claude wants to clear the build folder.") },
                ) {
                    InlineCardAction("Reject", noop, primary = false)
                    InlineCardAction("Approve", noop, primary = true)
                }
            },
            {
                InlineCard(InlineCardKind.Stall, "Bash silent for 2 min", body = { Text("npm run build stopped printing.") }) {
                    InlineCardAction("Keep waiting", noop, primary = false)
                    InlineCardAction("Interrupt", noop, primary = true)
                }
            },
            {
                InlineCard(
                    InlineCardKind.Error, "Couldn't save", onDismiss = noop,
                    body = { Text("Server: \"working directory /srv/x not found\".") },
                ) {
                    InlineCardAction("Details", noop, primary = false)
                    InlineCardAction("Retry", noop, primary = true)
                }
            },
            {
                InlineCard(InlineCardKind.Ended, "Session ended", body = { Text("The agent process exited (code 0) after 41 turns.") }) {
                    InlineCardAction("Continue in new session", noop, primary = true)
                }
            },
            {
                InlineCard(
                    InlineCardKind.Permission, "TV setup plan wants to start", onDismiss = noop,
                    hint = "Or type below to give feedback",
                    body = { Text("Claude finished planning and asks to exit plan mode.") },
                ) {
                    InlineCardAction("Reject", noop, primary = false)
                    InlineCardAction("Approve", noop, primary = true)
                }
            },
        ),
        minCell = 300.dp,
    )
}

/** The 12 categories with the mockup's sample calls. */
@Composable
fun ToolCardsBoard() = BoardFrame("Tool cards, all 12 categories", "tap a header to fold its output") {
    AdaptiveGrid(
        listOf(
            {
                SampleTool(ToolCategory.Read, ArchieIcons.Description, "Read", "orchestrator/session.py", ToolStatus.Done, "0.1s") {
                    ToolOutputLine("642 lines · showing 212–214")
                }
            },
            {
                SampleTool(ToolCategory.Write, ArchieIcons.EditDocument, "Edit", "api/routes/voice.py", ToolStatus.Done, diff = "+4 −1") {
                    ToolOutputLine("-    ttl = 60", OutputLineKind.Removed)
                    ToolOutputLine("+    ttl = settings.voice_token_ttl", OutputLineKind.Added)
                }
            },
            {
                SampleTool(ToolCategory.Execute, ArchieIcons.Terminal, "Bash", "npm run build", ToolStatus.Running, "0:12") {
                    ToolOutputLine("vite v5.4.2 building for production…")
                    ToolOutputLine("✓ 412 modules transformed")
                }
            },
            {
                SampleTool(ToolCategory.Script, ArchieIcons.Code, "browser_cmd", "browser_cmd.py look", ToolStatus.Done, "1.4s") {
                    ToolOutputLine("Snapshot: 38 refs · screenshot 1280×800")
                }
            },
            {
                SampleTool(ToolCategory.Navigate, ArchieIcons.Explore, "WebFetch", "developer.android.com/media/…", ToolStatus.Waiting, "2:14") {
                    ToolOutputLine("Waiting for response…", OutputLineKind.Dim)
                }
            },
            {
                SampleTool(ToolCategory.Capture, ArchieIcons.ScreenshotMonitor, "Screenshot", "living-room-tv.png", ToolStatus.Done) {
                    ToolOutputLine("1920×1080 · 412 KB")
                }
            },
            {
                SampleTool(ToolCategory.Interact, ArchieIcons.TouchApp, "Click", "button \"Play\" (ref 14)", ToolStatus.Done) {
                    ToolOutputLine("Clicked · page navigated")
                }
            },
            {
                SampleTool(ToolCategory.Todo, ArchieIcons.Checklist, "TodoWrite", "3 of 5 done", ToolStatus.Done) {
                    ToolOutputLine("✓ Extract VoiceStateMachine", OutputLineKind.Ok)
                    ToolOutputLine("◐ Port parity tests", OutputLineKind.Highlight)
                    ToolOutputLine("○ Update voice_subsystem.md")
                }
            },
            {
                SampleTool(ToolCategory.Task, ArchieIcons.Assignment, "Task", "Explore: where is voice state set?", ToolStatus.Running, "0:31") {
                    ToolOutputLine("Searching orchestrator/ and api/…")
                }
            },
            {
                SampleTool(ToolCategory.System, ArchieIcons.Settings, "Compact", "context 82% → 31%", ToolStatus.Done) {
                    ToolOutputLine("Summarized 38 turns")
                }
            },
            {
                SampleTool(ToolCategory.Agent, ArchieIcons.SmartToy, "send_to_agent_session", "Energy dashboard", ToolStatus.Error) {
                    ToolOutputLine("Session is not open (404). Reopen it from history.", OutputLineKind.Error)
                }
            },
            {
                SampleTool(ToolCategory.Search, ArchieIcons.Search, "Grep", "\"_voice =\" in orchestrator/", ToolStatus.Done, "0.2s", expanded = false) {
                    ToolOutputLine("session.py:212   self._voice = True")
                }
            },
        ),
        minCell = 360.dp,
    )
    Text(
        "Category color marks the icon tile and the tool name only; the card stays on a neutral surface.",
        style = ArchieTheme.typography.bodySmall,
        color = ArchieTheme.colors.onSurfaceVariant,
    )
}

@Composable
private fun SampleTool(
    category: ToolCategory,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    summary: String,
    status: ToolStatus,
    duration: String? = null,
    diff: String? = null,
    expanded: Boolean = true,
    placement: ToolCardPlacement = ToolCardPlacement.Solo,
    lines: @Composable () -> Unit,
) {
    ToolCardShell(
        category = category,
        icon = icon,
        name = name,
        summary = summary,
        status = status,
        expanded = expanded,
        onToggle = noop,
        placement = placement,
        meta = when {
            diff != null -> ({ ToolMetaDiffCount(diff) })
            duration != null -> ({ Text(duration) })
            else -> null
        },
        output = { ToolOutput { lines() } },
    )
}

@Composable
fun ToolGroupBoard() = BoardFrame("Tool groups", "\"N steps\", expanded while live") {
    Column(Modifier.widthIn(max = 840.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ToolGroup(
            header = {
                ToolGroupHeader(
                    2,
                    listOf(ToolCategory.Script to ArchieIcons.Code, ToolCategory.Agent to ArchieIcons.SmartToy),
                    "running", ToolStatus.Running, expanded = true, onToggle = noop,
                )
            },
            expanded = true,
        ) {
            SampleTool(ToolCategory.Script, ArchieIcons.Code, "run_script", "connect_tv.py", ToolStatus.Done, placement = ToolCardPlacement.Grouped) {
                ToolOutputLine("Connected to Fire TV · 10.0.0.42")
            }
            SampleTool(ToolCategory.Agent, ArchieIcons.SmartToy, "send_to_agent_session", "TV setup plan", ToolStatus.Running, placement = ToolCardPlacement.Grouped) {
                ToolOutputLine("Read assistant/devices/fire_tv.md")
                ToolOutputLine("Listed 23 installed apps")
                ToolOutputLine("Drafting plan, asking to exit plan mode", OutputLineKind.Highlight)
            }
        }
        ToolGroup(
            header = {
                ToolGroupHeader(
                    3,
                    listOf(
                        ToolCategory.Agent to ArchieIcons.SmartToy,
                        ToolCategory.Script to ArchieIcons.Code,
                        ToolCategory.Interact to ArchieIcons.TouchApp,
                    ),
                    "permission, run_script, soundbar", ToolStatus.Done, expanded = false, onToggle = noop,
                )
            },
            expanded = false,
        ) {}
        SampleTool(ToolCategory.Script, ArchieIcons.Code, "run_script", "home_lights.py --room living --level 30", ToolStatus.Done, expanded = false) {
            ToolOutputLine("living-room: 30% (3 lamps)")
        }
    }
}

@Composable
fun ListItemsBoard() = BoardFrame("List items", "drawer · list pane · session switcher") {
    AdaptiveGrid(
        listOf(
            {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SearchPill("Search conversations", noop)
                    ListSectionHeader("Open now")
                    ArchieListItem(
                        "Living-room TV", noop, selected = true,
                        leading = { ArchieMark(size = 24.dp) },
                        trailing = { StatusIndicator(LiveStatus.NeedsYou) },
                    )
                    ArchieListItem(
                        "Refactor voice module", noop,
                        leading = { ListLeadingIcon(ArchieIcons.Terminal) },
                        trailing = { ProviderChip("Claude"); StatusDot() },
                    )
                    ArchieListItem(
                        "Energy dashboard", noop,
                        leading = { ListLeadingIcon(ArchieIcons.Terminal) },
                        trailing = { ProviderChip("Qwen"); StatusIndicator(LiveStatus.Working) },
                    )
                    ListSectionHeader("Today")
                    ArchieListItem("Weekly energy report", noop, leading = { ArchieMark(size = 20.dp) }, trailing = { ListMeta("14:20") })
                    ArchieListItem("Fix context-sync delete race", noop, leading = { ListLeadingIcon(ArchieIcons.Terminal) }, trailing = { ListMeta("11:05") })
                    ArchieListItem("Settings", noop, leading = { ListLeadingIcon(ArchieIcons.Settings) })
                }
            },
            {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ListSectionHeader("Open now")
                    ArchieListItem(
                        "Living-room TV", noop, size = ListItemSize.Large, selected = true,
                        leading = { ListLeadingTile { ArchieMark(size = 40.dp) } },
                        supporting = { StatusIndicator(LiveStatus.NeedsYou); Text("Archie · waiting for approval") },
                        trailing = { ArchieIconButton(ArchieIcons.Close, "Close", noop) },
                    )
                    ArchieListItem(
                        "Refactor voice module", noop, size = ListItemSize.Large,
                        leading = { ListLeadingTile(ArchieIcons.Terminal) },
                        supporting = { ProviderChip("Claude"); StatusDot(); Text("Ready · 14 turns") },
                        trailing = { ArchieIconButton(ArchieIcons.Close, "Close", noop) },
                    )
                    ArchieListItem(
                        "voice_subsystem.md", noop, size = ListItemSize.Large,
                        leading = { ListLeadingTile(ArchieIcons.Description) },
                        supporting = { Text("Memory · assistant/architecture") },
                        trailing = { ArchieIconButton(ArchieIcons.Close, "Close", noop) },
                    )
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ArchieButton("New Archie chat", noop, Modifier.weight(1f), ButtonStyle.Tonal, icon = ArchieIcons.AddComment)
                        ArchieButton("New agent session", noop, Modifier.weight(1f), ButtonStyle.Outlined, icon = ArchieIcons.Terminal)
                    }
                }
            },
        ),
        minCell = 360.dp,
        gap = 24.dp,
    )
}

@Composable
fun SettingsBoard() = BoardFrame("Settings", "groups · rows · controls") {
    AdaptiveGrid(
        listOf(
            {
                Column {
                    SettingsGroupHeader("This device")
                    SettingsGroup {
                        SettingsRow("Connection", icon = ArchieIcons.Lan, value = "jetson · archie.local · connected", onClick = noop)
                        SettingsRow("Audio", icon = ArchieIcons.VolumeUp, value = "Mic 100% · Speaker 80% · Speaker out", onClick = noop)
                        SwitchRow("Wake word", checked = true, onCheckedChange = {}, icon = ArchieIcons.Hearing, value = "\"Hey Archie\" · assist gesture")
                    }
                    SettingsGroupHeader("Archie (server)", meta = { StatusDot(); Text("jetson · online") })
                    SettingsGroup {
                        SettingsRow("Voice", icon = ArchieIcons.RecordVoiceOver, value = "OpenAI · gpt-realtime · cedar · English", onClick = noop)
                        SettingsRow("MCP servers", icon = ArchieIcons.Hub, value = "All 6 enabled", onClick = noop)
                        SettingsRow("Account", icon = ArchieIcons.AccountCircle, value = "Server offline", onClick = noop, enabled = false)
                    }
                }
            },
            {
                Column {
                    Box(Modifier.padding(start = 4.dp, top = 18.dp, bottom = 16.dp)) {
                        ScopeChip("This device · Pixel 8", ArchieIcons.Mobile)
                    }
                    SettingsGroup {
                        SettingsFieldSet("Microphone level", value = "100%") {
                            LevelSlider(value = 1f, onValueChange = {}, valueRange = 0f..1.5f)
                        }
                        SettingsFieldSet("Speaker level", value = "80%", help = "Applies to voice replies and tones.") {
                            LevelSlider(value = 0.8f, onValueChange = {})
                        }
                        SettingsFieldSet("Output route") {
                            SegmentedChoice(
                                listOf(SegmentOption("Speaker"), SegmentOption("Earpiece"), SegmentOption("Bluetooth")),
                                selectedIndex = 0,
                                onSelect = {},
                            )
                        }
                    }
                }
            },
        ),
        minCell = 360.dp,
        gap = 24.dp,
    )
}

@Composable
fun TopAppBarBoard() = BoardFrame("Top app bar", "Compact · conversation and page") {
    Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ArchieTopAppBar(
            "Living-room TV",
            navigationIcon = { ArchieIconButton(ArchieIcons.Menu, "Open navigation", noop) },
            onTitleClick = noop,
            showMark = true,
            subtitle = { TopAppBarSubtitle("Waiting for your approval", ArchieIcons.FrontHand, ArchieTheme.extended.warning.color) },
        ) {
            ArchieIconButton(ArchieIcons.VolumeUp, "Speaker on", noop)
            ArchieIconButton(ArchieIcons.MoreVert, "Session menu", noop)
        }
        ArchieTopAppBar(
            "Living-room TV",
            navigationIcon = { ArchieIconButton(ArchieIcons.Menu, "Open navigation", noop) },
            onTitleClick = noop,
            showMark = true,
            subtitle = { TopAppBarSubtitle("Voice · Reconnecting…", ArchieIcons.Sync, ArchieTheme.extended.warning.color) },
        ) {
            ArchieIconButton(ArchieIcons.VolumeUp, "Speaker on", noop)
            ArchieIconButton(ArchieIcons.MoreVert, "Session menu", noop)
        }
        ArchieTopAppBar(
            "Settings",
            navigationIcon = { ArchieIconButton(ArchieIcons.ArrowBack, "Back", noop) },
        ) {
            ArchieIconButton(ArchieIcons.Search, "Search settings", noop)
        }
    }
}

@Composable
fun EmptyStateBoard() = BoardFrame("Empty state", "new Archie conversation") {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        EmptyState(
            title = "Good evening.\nWhat are we doing?",
            body = "Talk or type. Archie can hand work to an agent.",
            modifier = Modifier.widthIn(max = 560.dp),
            onVoice = noop,
        ) {
            SuggestionChip("Plan the living-room TV setup", noop, icon = ArchieIcons.Tv)
            SuggestionChip("This week's energy use", noop, icon = ArchieIcons.Bolt)
            SuggestionChip("What do I know about voice?", noop, icon = ArchieIcons.Book2)
            SuggestionChip("Start an agent session", noop, icon = ArchieIcons.Terminal)
        }
    }
}

// ---------------------------------------------------------------- previews

@Composable
private fun PreviewHost(mode: ThemeMode, board: @Composable () -> Unit) {
    ArchieTheme(mode = mode, reduceMotion = true) { board() }
}

@BoardPreviews @Composable private fun ButtonsPreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { ButtonsBoard() }
@BoardPreviews @Composable private fun IconButtonsPreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { IconButtonsBoard() }
@BoardPreviews @Composable private fun ChipsPreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { ChipsBoard() }
@BoardPreviews @Composable private fun SelectionPreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { SelectionBoard() }
@BoardPreviews @Composable private fun TextFieldsPreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { TextFieldsBoard() }
@BoardPreviews @Composable private fun TabsPreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { TabsBoard() }
@BoardPreviews @Composable private fun SnackbarsPreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { SnackbarsBoard() }
@BoardPreviews @Composable private fun ComposerPreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { ComposerBoard() }
@BoardPreviews @Composable private fun VoiceDockPreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { VoiceDockBoard() }
@BoardPreviews @Composable private fun MenuDialogPreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { MenuDialogBoard() }
@BoardPreviews @Composable private fun InlineCardsPreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { InlineCardsBoard() }
@BoardPreviews @Composable private fun ToolCardsPreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { ToolCardsBoard() }
@BoardPreviews @Composable private fun ToolGroupPreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { ToolGroupBoard() }
@BoardPreviews @Composable private fun ListItemsPreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { ListItemsBoard() }
@BoardPreviews @Composable private fun SettingsPreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { SettingsBoard() }
@BoardPreviews @Composable private fun TopAppBarPreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { TopAppBarBoard() }
@BoardPreviews @Composable private fun EmptyStatePreview(@PreviewParameter(ThemeModePreviews::class) m: ThemeMode) = PreviewHost(m) { EmptyStateBoard() }
