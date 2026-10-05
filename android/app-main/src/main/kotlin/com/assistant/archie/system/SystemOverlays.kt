package com.assistant.archie.system

import android.Manifest
import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assistant.archie.feature.settings.AppPermission
import com.assistant.archie.feature.settings.PermissionStatus
import com.assistant.archie.graph.MainAppGraph
import com.assistant.archie.shell.settingsFeatureOf
import com.assistant.core.data.SharePayload
import com.assistant.core.design.components.ArchieButton
import com.assistant.core.design.components.ArchieDialogSurface
import com.assistant.core.design.components.ButtonStyle
import com.assistant.core.model.ThemeMode

/**
 * Everything system-facing the shell renders on top of its content (spec 14 §2.8, §2.9): the share
 * sheet, the microphone rationale + system dialog, and the shortcut commands. Called once from the
 * root composable inside the theme.
 */
@Composable
fun SystemOverlays(graph: MainAppGraph, onCommand: (ShellCommand) -> Unit) {
    SystemBarsMatchTheme()
    ShareSheetHost(graph.shareFlow)
    MicPermissionHost(graph)
    val command by graph.commands.pending.collectAsStateWithLifecycle()
    LaunchedEffect(command) { if (command != null) graph.commands.take()?.let(onCommand) }
}

// ───────────────────────────── system bars (OI-1) ─────────────────────────────

/**
 * OI-1: status/navigation bar icons follow the *app* theme, not the system's night mode. The
 * shell's theme can differ from the system setting (Archie defaults to dark, D3), and
 * `enableEdgeToEdge()`'s default `SystemBarStyle.auto` reads the system mode — so a dark Archie on
 * a light system drew dark, near-invisible icons. Light icons on a dark background, dark icons on
 * a light one, decided from the theme's actual background.
 */
@Composable
fun SystemBarsMatchTheme() {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val activity = LocalContext.current.findActivity() as? ComponentActivity ?: return
    DisposableEffect(activity, dark) {
        val transparent = AndroidColor.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }
}

/**
 * API 31+: tell the system the app's night mode, so the next cold start's splash window (drawn by
 * the system before any app code runs) uses the same background and bar icons as the shell
 * (`values-night` window background + `windowLightStatusBar`). Persisted by the platform.
 */
fun applyAppNightMode(context: Context, mode: ThemeMode) {
    if (Build.VERSION.SDK_INT < 31) return
    val ui = context.getSystemService(UiModeManager::class.java) ?: return
    val want = when (mode) {
        ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
        ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
    }
    try {
        ui.setApplicationNightMode(want)
    } catch (_: Exception) {
        // Best effort: only the splash colour depends on it.
    }
}

// ───────────────────────────── microphone (spec 14 §2.9) ─────────────────────────────

@Composable
private fun MicPermissionHost(graph: MainAppGraph) {
    val context = LocalContext.current
    val pending by graph.mic.pending.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf(false) }
    val settings = remember(graph) { settingsFeatureOf(graph, context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        asking = false
        val activity = context.findActivity()
        val canAskAgain = activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ?: true
        settings.permissions.onResult(AppPermission.MICROPHONE, granted, canAskAgain)
        if (!granted) Toast.makeText(context, "Voice needs the microphone. Allow it in Settings → This device → Permissions.", Toast.LENGTH_LONG).show()
        graph.mic.resolve(granted)
    }
    val req = pending ?: return
    if (asking) return
    val blocked = settings.permissions.status(AppPermission.MICROPHONE) == PermissionStatus.BLOCKED
    Dialog(onDismissRequest = { graph.mic.resolve(false) }) {
        ArchieDialogSurface(
            "Allow the microphone",
            modifier = Modifier.testTag("mic-rationale"),
            body = {
                Text(
                    "Archie hears you in voice conversations and voice messages. Audio goes only to your Archie server, and only while you talk." +
                        if (blocked) "\n\nIt was turned off for Archie. Allow it in app settings → Permissions." else "",
                )
            },
        ) {
            ArchieButton("Not now", { graph.mic.resolve(false) }, style = ButtonStyle.Text)
            if (blocked) {
                ArchieButton("Open app settings", {
                    graph.mic.resolve(false)
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }, style = ButtonStyle.Text)
            } else {
                ArchieButton("Continue", {
                    asking = true
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }, style = ButtonStyle.Text, modifier = Modifier.testTag("mic-continue"))
            }
        }
    }
    check(req.id > 0)
}

// ───────────────────────────── share sheet (spec 14 §2.8) ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheetHost(controller: ShareController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state) {
        val s = state
        if (s is ShareState.Done) {
            Toast.makeText(context, s.message, Toast.LENGTH_SHORT).show()
            controller.dismiss()
        }
    }
    val s = state
    if (s is ShareState.Idle || s is ShareState.Done) return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { if (s !is ShareState.Uploading) controller.dismiss() },
        sheetState = sheet,
        modifier = Modifier.testTag("share-sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (s) {
                is ShareState.Choosing -> ChooseTarget(s, controller)
                is ShareState.Uploading -> {
                    Text("Sharing with Archie", style = MaterialTheme.typography.titleLarge)
                    val pct = if (s.total > 0) (s.sent * 100 / s.total).toInt() else null
                    Text(
                        (if (s.count > 1) "File ${s.index + 1} of ${s.count} · " else "") + "Uploading ${s.fileName}" + (pct?.let { " · $it%" } ?: "…"),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("share-progress"),
                    )
                    if (s.total > 0) LinearProgressIndicator(progress = { s.sent.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is ShareState.Failed -> {
                    Text("Couldn't share", style = MaterialTheme.typography.titleLarge)
                    Text(s.message, modifier = Modifier.testTag("share-error"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ArchieButton("Close", { controller.dismiss() }, style = ButtonStyle.Text)
                        ArchieButton("Retry", { controller.retry() }, style = ButtonStyle.Text)
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun ChooseTarget(s: ShareState.Choosing, controller: ShareController) {
    var selected by rememberSaveable(s.payload) { mutableStateOf(0) }
    Text("Share with", style = MaterialTheme.typography.titleLarge)
    Text(preview(s.payload), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    Column(Modifier.selectableGroup()) {
        s.targets.forEachIndexed { i, t ->
            Row(
                Modifier.fillMaxWidth().height(48.dp)
                    .selectable(selected = i == selected, onClick = { selected = i }, role = Role.RadioButton)
                    .testTag("share-target-$i"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = i == selected, onClick = null)
                Spacer(Modifier.padding(start = 8.dp))
                Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        ArchieButton("Cancel", { controller.dismiss() }, style = ButtonStyle.Text)
        ArchieButton("Share", { controller.send(s.targets[selected.coerceIn(0, s.targets.lastIndex)]) }, modifier = Modifier.testTag("share-send"))
    }
}

private fun preview(p: SharePayload): String = when (p) {
    is SharePayload.Text -> (p.subject?.let { "$it — " } ?: "") + p.text.trim()
    is SharePayload.Files -> if (p.uris.size == 1) "1 file" + (p.subject?.let { " · $it" } ?: "") else "${p.uris.size} files" + (p.subject?.let { " · $it" } ?: "")
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
