package com.assistant.archie.feature.settings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assistant.archie.feature.settings.HarnessLogic
import com.assistant.archie.feature.settings.Option
import com.assistant.archie.feature.settings.SessionKey
import com.assistant.archie.feature.settings.SessionSettingsController
import com.assistant.archie.feature.settings.SessionSettingsState
import com.assistant.archie.feature.settings.SessionSheetPhase
import com.assistant.archie.feature.settings.ServerSettingsState
import com.assistant.archie.feature.settings.SettingsFeature
import com.assistant.archie.feature.settings.WorkingDirectoryLogic
import com.assistant.core.design.components.ArchieButton
import com.assistant.core.design.components.ArchieIconButton
import com.assistant.core.design.components.ArchieSnackbarHost
import com.assistant.core.design.components.ButtonSize
import com.assistant.core.design.components.ButtonStyle
import com.assistant.core.design.components.InlineCardAction
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.model.ServerConfig
import kotlinx.coroutines.launch

/** Side sheet from this width (IA §7: side sheet on Expanded / Medium, bottom sheet on Compact). */
private val SIDE_SHEET = 600.dp

/**
 * Session settings (IA §7, from the session ⋮ menu): a side sheet on Medium / Expanded and a bottom
 * sheet on Compact, over the conversation. Body scrolls; the footer (Save / Save and restart) stays.
 */
@Composable
fun SessionSettingsSheet(feature: SettingsFeature, localId: String, onDismiss: () -> Unit, onManageDirectories: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val controller = remember(localId) { feature.sessionController(localId, scope) }
    val st by controller.state.collectAsStateWithLifecycle()
    val server by feature.server.state.collectAsStateWithLifecycle()
    LaunchedEffect(feature) { feature.server.refresh() }
    BackHandler(onBack = onDismiss)
    val host = remember { SnackbarHostState() }
    SettingsSnackbars(feature.messages, host)
    val c = ArchieTheme.colors
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(c.scrim.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .testTag("session-settings-scrim"),
    ) {
        val side = maxWidth >= SIDE_SHEET
        val shape = if (side) RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        val sheet = if (side) Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(400.dp)
        else Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * 0.92f)
        Column(
            sheet
                .background(c.surfaceContainerLow, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .testTag("session-settings"),
        ) {
            if (!side) {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp, 4.dp).background(c.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)))
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Session settings", style = ArchieTheme.typography.titleLarge, color = c.onSurface)
                    st.info?.title?.takeIf { it.isNotEmpty() }?.let { Text(it, style = ArchieTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1) }
                }
                ArchieIconButton(ArchieIcons.Close, "Close", onDismiss)
            }
            SnackbarHosted {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    SessionBody(feature, controller, st, server, onManageDirectories)
                }
            }
            if (st.phase == SessionSheetPhase.READY && server.config.value != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(st.footer, style = ArchieTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.testTag("session-footer"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        ArchieButton("Save", { scope.launch { controller.run(restart = false) } }, style = ButtonStyle.Text, enabled = st.dirty && !st.busy, modifier = Modifier.testTag("session-save"))
                        ArchieButton(
                            if (st.dirty) "Save and restart" else "Restart",
                            { scope.launch { if (controller.run(restart = true)) onDismiss() } },
                            icon = ArchieIcons.RestartAlt, enabled = st.info?.busy != true && !st.busy,
                            modifier = Modifier.testTag("session-restart"),
                        )
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter)) { ArchieSnackbarHost(host) }
    }
}

@Composable
private fun ColumnScope.SessionBody(
    feature: SettingsFeature,
    controller: SessionSettingsController,
    st: SessionSettingsState,
    server: ServerSettingsState,
    onManageDirectories: (() -> Unit)?,
) {
    val global = server.config.value
    when {
        st.phase == SessionSheetPhase.NO_ID -> Notice(NoticeTone.INFO, "Available after the first reply", body = "Session settings are saved per conversation, which gets its id with the first reply.")
        st.phase == SessionSheetPhase.READ_ONLY -> Notice(NoticeTone.INFO, "Read-only view", body = "Open the session to change its settings.")
        st.phase == SessionSheetPhase.ERROR -> Notice(
            NoticeTone.ERROR, "Couldn't load the session settings", body = st.loadError,
            actions = { InlineCardAction("Retry", controller::load, primary = true, icon = ArchieIcons.Refresh) },
        )
        st.phase == SessionSheetPhase.LOADING || global == null -> LoadingBody("Loading session settings…")
        else -> SessionFields(feature, controller, st, server, global, onManageDirectories)
    }
}

@Composable
private fun ColumnScope.SessionFields(
    feature: SettingsFeature,
    controller: SessionSettingsController,
    st: SessionSettingsState,
    server: ServerSettingsState,
    global: ServerConfig,
    onManageDirectories: (() -> Unit)?,
) {
    val disabled = st.busy
    val wd = st.workingDirectory
    val history = WorkingDirectoryLogic.coerce(global.workingDirectoryHistory)
    SettingsGroupLabel("Working directory")
    WorkingDirectoryList(
        history, wd ?: global.workingDirectory, { controller.set(SessionKey.WORKING_DIRECTORY, it) },
        enabled = !disabled, selectedBadge = if (wd == null) "Default" else "This session",
    )
    HelpLine(
        "${if (wd == null) "Uses the default from Settings." else "Pinned for this session."} Adding or editing directories changes the list for every session.",
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (wd != null) ArchieButton("Use default", { controller.set(SessionKey.WORKING_DIRECTORY, null) }, style = ButtonStyle.Text, size = ButtonSize.Small, icon = ArchieIcons.Refresh, enabled = !disabled)
        if (onManageDirectories != null) ArchieButton("Manage directories", onManageDirectories, style = ButtonStyle.Text, size = ButtonSize.Small, icon = ArchieIcons.Folder)
    }
    Spacer(Modifier.height(16.dp))

    val mcps = st.enabledMcps
    SettingsGroupLabel("MCP servers · ${if (mcps == null) "default from Settings" else "chosen for this session"}")
    val servers = server.catalogs.mcpServers
    if (servers != null && servers.servers.isNotEmpty()) {
        McpServerList(servers, mcps ?: global.enabledMcps, disabled) { controller.set(SessionKey.ENABLED_MCPS, it) }
    } else {
        HelpLine("No MCP servers configured in .claude.json.", modifier = Modifier.padding(start = 4.dp))
    }
    if (mcps != null) ArchieButton("Use default", { controller.set(SessionKey.ENABLED_MCPS, null) }, style = ButtonStyle.Text, size = ButtonSize.Small, icon = ArchieIcons.Refresh, enabled = !disabled)
    Spacer(Modifier.height(16.dp))

    val skills = server.catalogs.skills.orEmpty()
    val agents = server.catalogs.agents.orEmpty()
    var showSkills by rememberSaveable { mutableStateOf(false) }
    Section("Skills & agents") {
        com.assistant.core.design.components.SettingsRow(
            "${skills.size} ${if (skills.size == 1) "skill" else "skills"} · ${agents.size} ${if (agents.size == 1) "agent" else "agents"}",
            icon = ArchieIcons.Build,
            value = "Every session can use all of them; the server has no per-session selection.",
            onClick = { showSkills = !showSkills },
            trailing = { ArchieIcon(if (showSkills) ArchieIcons.KeyboardArrowUp else ArchieIcons.KeyboardArrowDown, null, tint = ArchieTheme.colors.onSurfaceVariant) },
        )
        if (showSkills) {
            FieldBlock {
                skills.forEach { Text("/${it.name}${if (it.description.isNotEmpty()) " · ${it.description}" else ""}", style = ArchieTheme.typography.bodySmall, color = ArchieTheme.colors.onSurfaceVariant, maxLines = 2) }
                agents.forEach { Text("${it.name} (agent)${if (it.description.isNotEmpty()) " · ${it.description}" else ""}", style = ArchieTheme.typography.bodySmall, color = ArchieTheme.colors.onSurfaceVariant, maxLines = 2) }
            }
        }
    }

    val providers = server.catalogs.providers.orEmpty()
    val provider = st.provider
    val effective = provider ?: global.provider.orEmpty()
    fun label(id: String) = providers.firstOrNull { it.id == id }?.label?.takeIf { it.isNotEmpty() } ?: id
    val providerOptions = listOf(Option("", "Default (${label(global.provider.orEmpty())})")) + providers.map { Option(it.id, it.label.ifEmpty { it.id }) }
    val harness = st.harnessModel
    val inherited = global.harnessModel[effective].orEmpty()
    val harnessOptions = (listOf(Option(INHERIT, "Default (${inherited.ifEmpty { "CLI default" }})"), Option("", "CLI default")) + HarnessLogic.models(server.catalogs.qwenModels)).toMutableList()
    if (!harness.isNullOrEmpty() && harnessOptions.none { it.id == harness }) harnessOptions += Option(harness, harness)
    val chrome = st.chromeExtension
    Section("Advanced") {
        SelectRow("Harness", providerOptions, provider.orEmpty(), { controller.set(SessionKey.PROVIDER, it.ifEmpty { null }) }, enabled = !disabled)
        if (effective == "qwen") {
            SelectRow("Qwen model", harnessOptions, harness ?: INHERIT, { controller.set(SessionKey.HARNESS_MODEL, if (it == INHERIT) null else it) }, enabled = !disabled)
        }
        FieldBlock { HelpLine("Switching the CLI behind an existing conversation can corrupt it.") }
        ToggleField(
            "Claude in Chrome", chrome ?: global.chromeExtension, { controller.set(SessionKey.CHROME_EXTENSION, it) },
            help = if (chrome == null) "Default (${if (global.chromeExtension) "on" else "off"})" else "Set for this session.",
            enabled = !disabled,
        )
    }
    if (chrome != null) ArchieButton("Use default", { controller.set(SessionKey.CHROME_EXTENSION, null) }, style = ButtonStyle.Text, size = ButtonSize.Small, icon = ArchieIcons.Refresh, enabled = !disabled)
    st.saveError?.let { Notice(NoticeTone.ERROR, "Not saved", body = it) }
}

private const val INHERIT = "__inherit__"
