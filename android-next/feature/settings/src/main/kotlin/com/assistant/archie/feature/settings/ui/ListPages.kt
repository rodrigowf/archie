package com.assistant.archie.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.assistant.archie.feature.settings.DraftField
import com.assistant.archie.feature.settings.HistoryEdit
import com.assistant.archie.feature.settings.McpLogic
import com.assistant.archie.feature.settings.SettingsFeature
import com.assistant.archie.feature.settings.WorkingDirectoryDraft
import com.assistant.archie.feature.settings.WorkingDirectoryLogic
import com.assistant.core.design.components.ArchieButton
import com.assistant.core.design.components.ArchieConfirmDialog
import com.assistant.core.design.components.ArchieIconButton
import com.assistant.core.design.components.ArchieSwitch
import com.assistant.core.design.components.ArchieTextField
import com.assistant.core.design.components.ArchieTopAppBar
import com.assistant.core.design.components.ButtonStyle
import com.assistant.core.design.components.EmptyState
import com.assistant.core.design.components.SegmentOption
import com.assistant.core.design.components.SegmentedChoice
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.model.ConfigPatch
import com.assistant.core.model.WorkingDirectory
import com.assistant.core.protocol.McpServersDto
import kotlinx.coroutines.launch

// ───────────────────────────── Working directories (F-33, CFG-7) ─────────────────────────────

/**
 * The radio list of directories (also used by Session settings, without edit / delete). Rows show
 * the name, SSH and Active badges and `user@host · path`; the only directory can't be removed.
 */
@Composable
internal fun WorkingDirectoryList(
    history: List<WorkingDirectory>,
    value: String?,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
    selectedBadge: String = "Active",
    onEdit: ((WorkingDirectory) -> Unit)? = null,
    onDelete: ((WorkingDirectory) -> Unit)? = null,
) {
    val c = ArchieTheme.colors
    val only = history.size <= 1
    com.assistant.core.design.components.SettingsGroup {
        for (e in history) {
            val on = e.id == value
            val name = WorkingDirectoryLogic.name(e)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (on) c.secondaryContainer else c.surfaceContainer)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { if (!on) onSelect(e.id) }
                    .testTag("wd:${e.id}")
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ArchieIcon(
                    if (on) ArchieIcons.CheckCircleFilled else if (e.sshHost != null) ArchieIcons.Terminal else ArchieIcons.Folder,
                    null, tint = if (on) c.primary else c.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f, fill = false), style = ArchieTheme.typography.bodyLarge.copy(letterSpacing = 0.sp), color = if (on) c.onSecondaryContainer else c.onSurface, maxLines = 1)
                        if (e.sshHost != null) Badge("SSH")
                        if (on) Badge(selectedBadge, active = true)
                    }
                    Text(WorkingDirectoryLogic.detail(e), style = ArchieTheme.typography.bodySmall.copy(letterSpacing = 0.sp), color = c.onSurfaceVariant, maxLines = 1)
                }
                if (onEdit != null) ArchieIconButton(ArchieIcons.Edit, "Edit $name", { onEdit(e) }, size = 40.dp, iconSize = 20.dp)
                if (onDelete != null) {
                    ArchieIconButton(
                        ArchieIcons.Delete, if (only) "Remove $name (the only directory can't be removed)" else "Remove $name",
                        { onDelete(e) }, size = 40.dp, iconSize = 20.dp, enabled = !only,
                    )
                }
            }
        }
    }
}

@Composable
internal fun WorkingDirectoriesPage(feature: SettingsFeature, onBack: (() -> Unit)?) = ServerPageFrame(feature, "Working directories", onBack) { cfg, st ->
    val m = feature.server
    val scope = rememberCoroutineScope()
    val saving = st.saving != null
    val history = WorkingDirectoryLogic.coerce(cfg.workingDirectoryHistory)
    val active = cfg.workingDirectory.orEmpty()
    var editing by rememberSaveable { mutableStateOf<String?>(null) } // id, or "" = add
    var removing by remember { mutableStateOf<WorkingDirectory?>(null) }
    val full = history.size >= WorkingDirectoryLogic.MAX
    suspend fun save(edit: HistoryEdit): Boolean =
        m.save(ConfigPatch(workingDirectoryHistory = edit.history, workingDirectory = edit.active), "working_directory_history") != null

    SettingsGroupLabel("New agent sessions start in")
    if (history.isNotEmpty()) {
        WorkingDirectoryList(
            history, active,
            onSelect = { m.launchSave(ConfigPatch(workingDirectory = it), "working_directory") },
            enabled = !saving,
            onEdit = { editing = it.id },
            onDelete = { removing = it },
        )
    } else {
        Notice(NoticeTone.WARNING, "No directories yet", body = "Add one to start agent sessions.")
    }
    Spacer(Modifier.height(12.dp))
    ArchieButton("Add directory", { editing = "" }, style = ButtonStyle.Tonal, icon = ArchieIcons.Add, enabled = !saving && !full, modifier = Modifier.testTag("add-directory"))
    HelpLine(
        if (full) "Up to ${WorkingDirectoryLogic.MAX} directories. Remove one to add another." else "SSH entries run Claude Code on that machine.",
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
    editing?.let { id ->
        val entry = history.firstOrNull { it.id == id }
        WorkingDirectoryDialog(
            entry, history, saving, st.saveError,
            onDismiss = { editing = null; m.clearSaveError() },
            onSubmit = { draft ->
                val edit = if (entry != null) WorkingDirectoryLogic.edit(history, entry.id, draft) else WorkingDirectoryLogic.add(history, draft)
                scope.launch { if (save(edit)) editing = null }
            },
        )
    }
    removing?.let { target ->
        ArchieConfirmDialog(
            title = "Remove ${WorkingDirectoryLogic.name(target)}?",
            text = "It leaves this list only; nothing is deleted on disk. Sessions already running keep their directory.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                val edit = WorkingDirectoryLogic.delete(history, target.id, active)
                if (edit == null) removing = null else scope.launch { save(edit); removing = null }
            },
            onDismissRequest = { removing = null },
        )
    }
}

@Composable
internal fun SettingsGroupLabel(text: String) {
    com.assistant.core.design.components.SettingsGroupHeader(text, Modifier.padding(top = 0.dp))
}

/** Add / edit a directory (full screen on phones, like the web's `fullScreen="compact"`). */
@Composable
private fun WorkingDirectoryDialog(
    entry: WorkingDirectory?,
    history: List<WorkingDirectory>,
    saving: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onSubmit: (WorkingDirectoryDraft) -> Unit,
) {
    var draft by remember { mutableStateOf(entry?.let(WorkingDirectoryLogic::draftOf) ?: WorkingDirectoryDraft()) }
    var submitted by remember { mutableStateOf(false) }
    val errors = WorkingDirectoryLogic.validate(draft, history, entry?.id)
    fun shown(f: DraftField) = if (submitted) errors[f] else null
    val requiredMissing = draft.path.isBlank() || (draft.ssh && draft.host.isBlank())
    val c = ArchieTheme.colors
    val uri = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.surfaceContainerHigh).testTag("wd-dialog")) {
            ArchieTopAppBar(
                if (entry != null) "Edit directory" else "Add directory",
                navigationIcon = { ArchieIconButton(ArchieIcons.Close, "Cancel", onDismiss) },
                actions = {
                    ArchieButton(
                        if (entry != null) "Save" else "Add",
                        { submitted = true; if (errors.isEmpty()) onSubmit(draft) },
                        style = ButtonStyle.Text, enabled = !requiredMissing && !saving,
                        modifier = Modifier.padding(end = 8.dp).testTag("wd-submit"),
                    )
                },
            )
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SegmentedChoice(
                    listOf(SegmentOption("On the server", ArchieIcons.Dns), SegmentOption("Over SSH", ArchieIcons.Terminal)),
                    if (draft.ssh) 1 else 0,
                    { draft = draft.copy(ssh = it == 1) },
                )
                if (draft.ssh) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ArchieTextField(draft.host, { draft = draft.copy(host = it) }, "SSH host", Modifier.weight(1.4f).testTag("wd-host"), placeholder = "192.168.0.200", errorText = shown(DraftField.HOST), keyboardOptions = uri)
                        ArchieTextField(draft.user, { draft = draft.copy(user = it) }, "User", Modifier.weight(1f).testTag("wd-user"), supportingText = "Optional", errorText = shown(DraftField.USER), keyboardOptions = uri)
                    }
                }
                ArchieTextField(
                    draft.path, { draft = draft.copy(path = it) }, if (draft.ssh) "Remote path" else "Path", Modifier.fillMaxWidth().testTag("wd-path"),
                    placeholder = "/home/rodrigo/project",
                    supportingText = if (draft.ssh) "On the remote machine." else "Must exist on the server.",
                    errorText = shown(DraftField.PATH), keyboardOptions = uri,
                )
                if (draft.ssh) {
                    ArchieTextField(draft.key, { draft = draft.copy(key = it) }, "SSH key", Modifier.fillMaxWidth(), placeholder = "~/.ssh/id_ed25519", supportingText = "Optional. A key file on the server.", keyboardOptions = uri)
                    ArchieTextField(
                        draft.configDir, { draft = draft.copy(configDir = it) }, "CLAUDE_CONFIG_DIR on the remote", Modifier.fillMaxWidth(),
                        placeholder = draft.path.trim().takeIf { it.isNotEmpty() }?.let(WorkingDirectoryLogic::defaultConfigDir) ?: "<path>/.claude_config",
                        supportingText = "Optional. Defaults to <path>/.claude_config.", keyboardOptions = uri,
                    )
                }
                ArchieTextField(draft.label, { draft = draft.copy(label = it) }, "Label", Modifier.fillMaxWidth().testTag("wd-label"), supportingText = "Optional. Shown instead of the path.")
                if (submitted && saveError != null && !saving) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("wd-error")) {
                        ArchieIcon(ArchieIcons.Error, null, size = 16.dp, tint = c.error)
                        Text(saveError, style = ArchieTheme.typography.bodySmall, color = c.error)
                    }
                }
            }
        }
    }
}

// ───────────────────────────── MCP servers (F-34, CFG-4) ─────────────────────────────

/**
 * One switch per server with the "all enabled" semantics shown correctly (fixes inv03 §8 bug 1):
 * `[]` shows every switch on; switching one off writes the explicit list of the others; switching
 * all on writes `[]`; the last server that is on can't be switched off. Shared with Session settings.
 */
@Composable
internal fun McpServerList(servers: McpServersDto, enabled: List<String>, disabled: Boolean, onChange: (List<String>) -> Unit) {
    val c = ArchieTheme.colors
    val names = servers.servers.keys.toList()
    val all = McpLogic.isAllEnabled(enabled)
    val stale = enabled.filter { it !in names }
    HelpLine(
        if (all) "All servers are on, including any added to .claude.json later."
        else "Only the servers switched on are used; servers added later start off.",
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).testTag("mcp-semantics"),
    )
    com.assistant.core.design.components.SettingsGroup {
        for (n in names) {
            val on = McpLogic.isEnabled(enabled, n)
            val blocked = McpLogic.offBlockedReason(enabled, names, n)
            val switchable = !disabled && !(on && blocked != null)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(c.surfaceContainer)
                    .selectable(selected = on, enabled = switchable, role = Role.Switch) { onChange(McpLogic.toggle(enabled, names, n, !on)) }
                    .testTag("mcp:$n")
                    .padding(start = 16.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ArchieIcon(ArchieIcons.Hub, null, tint = c.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(n, style = ArchieTheme.typography.bodyLarge.copy(letterSpacing = 0.sp), color = c.onSurface)
                    Text(
                        if (blocked != null && on) blocked else McpLogic.commandLine(servers.servers[n]),
                        style = ArchieTheme.typography.bodySmall.copy(letterSpacing = 0.sp), color = c.onSurfaceVariant, maxLines = 1,
                    )
                }
                ArchieSwitch(on, null, enabled = switchable)
            }
        }
    }
    if (!all) {
        ArchieButton("Turn all on", { onChange(emptyList()) }, style = ButtonStyle.Text, icon = ArchieIcons.Check, enabled = !disabled, modifier = Modifier.padding(top = 4.dp).testTag("mcp-all-on"))
    }
    if (stale.isNotEmpty()) HelpLine("Also saved, but not in .claude.json: ${stale.joinToString(", ")}.", modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

@Composable
internal fun McpServersPage(feature: SettingsFeature, onBack: (() -> Unit)?) = ServerPageFrame(feature, "MCP servers", onBack) { cfg, st ->
    val servers = st.catalogs.mcpServers
    when {
        servers == null -> LoadingBody("Loading MCP servers…")
        servers.servers.isEmpty() -> EmptyState(
            title = "No MCP servers",
            body = "None configured in .claude.json${servers.projectDir?.let { " for $it" } ?: ""}.",
        )
        else -> {
            SettingsGroupLabel("Servers for new agent sessions")
            McpServerList(servers, cfg.enabledMcps, st.saving != null) { next ->
                feature.server.launchSave(ConfigPatch(enabledMcps = next), "enabled_mcps")
            }
        }
    }
}
