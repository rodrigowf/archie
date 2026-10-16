package com.assistant.archie.tv.voice

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assistant.archie.tv.graph.TvAppGraph
import com.assistant.archie.tv.theme.TvDimens
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voice.ports.VoiceLevels
import com.assistant.core.voice.ports.VoiceSessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** What the overlay layer draws (one at a time; the dictation chip, then the live panel, then hold-to-talk). */
sealed interface OverlayUi {
    data object None : OverlayUi
    data class Hold(val label: String, val startedAtMs: Long) : OverlayUi
    data class Working(val text: String) : OverlayUi
    data class Live(val panel: LivePanelState) : OverlayUi
    data class Dictation(val text: String) : OverlayUi
}

/**
 * The voice layer drawn above every screen (spec 15 §3.4 "Voice overlay", §4.4): the hold-to-talk
 * meter, "Sending…", the live-voice panel and the agent dictation chip. Never takes focus except the
 * dictation chip (the user just spoke). Back closes the chip, or ends live voice.
 *
 * It also hosts the RECORD_AUDIO request (the system dialog works with the D-pad).
 */
@Composable
fun VoiceOverlayHost(graph: TvAppGraph) {
    val voice = (graph.application as? TvVoiceOwner)?.tvVoice ?: return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { voice.onPermissionResult(it) }
    DisposableEffect(voice) {
        voice.permissionRequester = { launcher.launch(Manifest.permission.RECORD_AUDIO) }
        onDispose { voice.permissionRequester = null }
    }

    val overlay by voice.controller.overlay.collectAsStateWithLifecycle()
    val session by (voice.session ?: remember { MutableStateFlow(VoiceSessionState()) }).collectAsStateWithLifecycle()
    val holdingLive by voice.controller.liveHolding.collectAsStateWithLifecycle()
    val transcript by voice.live.transcript.collectAsStateWithLifecycle()
    val levels = rememberLevelHistory(voice)
    val speaker by (graph.voiceHost?.levels ?: VoiceLevels.None).collectAsStateWithLifecycle()

    val liveOwned = session.isOwner && session.phase != SessionPhase.OFF && session.phase != SessionPhase.ERROR
    val current = overlay
    val ui: OverlayUi = when {
        current is TvVoiceOverlay.Dictation -> OverlayUi.Dictation(current.text)
        liveOwned -> OverlayUi.Live(
            LivePanelState(
                status = liveStatus(session.phase, holdingLive),
                user = transcript.user,
                assistant = transcript.assistant,
                holding = holdingLive,
                level = when {
                    holdingLive -> levels.lastOrNull() ?: 0f
                    session.phase == SessionPhase.SPEAKING -> speaker?.let { VoiceLevels.visual(it.speaker) } ?: 0f
                    else -> 0f
                },
            ),
        )
        current is TvVoiceOverlay.Holding -> OverlayUi.Hold(current.label, current.startedAtMs)
        current is TvVoiceOverlay.Working -> OverlayUi.Working(current.text)
        else -> OverlayUi.None
    }

    // Back: the chip first (Cancel), then live voice (spec 15 §4.2 "Back from the voice overlay").
    BackHandler(enabled = ui is OverlayUi.Live) { voice.live.end() }
    BackHandler(enabled = ui is OverlayUi.Dictation) { voice.controller.cancelDictation() }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    if (ui is OverlayUi.Hold) {
        LaunchedEffect(ui.startedAtMs) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(250)
            }
        }
    }

    // Dictation chip focus: Send on arrival; Edit moves to the field with the IME.
    val sendFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dictation = ui as? OverlayUi.Dictation
    LaunchedEffect(dictation?.text) {
        if (dictation != null) {
            editing = false
            draft = dictation.text
            delay(16) // after the chip's first layout
            runCatching { sendFocus.requestFocus() }
        }
    }
    LaunchedEffect(editing) {
        if (editing) {
            delay(16)
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        }
    }
    val close: () -> Unit = {
        keyboard?.hide()
        editing = false
        focus.moveFocus(FocusDirection.Up) // back into the screen above the chip
    }

    VoiceOverlayLayer(
        ui = ui,
        levels = levels,
        nowMs = now,
        editing = editing,
        draft = draft,
        onDraft = { draft = it },
        actions = DictationActions(
            send = { voice.controller.sendDictation(it); close() },
            edit = { editing = true },
            cancel = { voice.controller.cancelDictation(); close() },
        ),
        sendFocus = sendFocus,
        fieldFocus = fieldFocus,
    )
}

/**
 * Positions the overlay inside the overscan-safe box: the pills bottom-center above the hint bar
 * (mockup 7), the live panel above the composer row (mockup 8), the chip full width (mockup 9).
 * Stateless (goldens).
 */
@Composable
fun VoiceOverlayLayer(
    ui: OverlayUi,
    levels: List<Float> = emptyList(),
    nowMs: Long = 0,
    editing: Boolean = false,
    draft: String = "",
    onDraft: (String) -> Unit = {},
    actions: DictationActions = DictationActions(),
    sendFocus: FocusRequester = remember { FocusRequester() },
    fieldFocus: FocusRequester = remember { FocusRequester() },
) {
    if (ui is OverlayUi.None) return
    Box(
        Modifier.fillMaxSize().padding(horizontal = TvDimens.OverscanHorizontal, vertical = TvDimens.OverscanVertical),
        contentAlignment = Alignment.BottomCenter,
    ) {
        when (ui) {
            is OverlayUi.Hold -> HoldOverlay(ui.label, levels, nowMs - ui.startedAtMs, Modifier.padding(bottom = 42.dp))
            is OverlayUi.Working -> WorkingOverlay(ui.text, Modifier.padding(bottom = 42.dp))
            is OverlayUi.Live -> LivePanel(ui.panel, Modifier.padding(bottom = 98.dp))
            is OverlayUi.Dictation -> DictationChip(
                ui.text, editing, draft, onDraft, actions, sendFocus, fieldFocus,
                Modifier.padding(start = 52.dp, end = 52.dp, bottom = 44.dp),
            )
            OverlayUi.None -> Unit
        }
    }
}

/** The panel's status line. */
fun liveStatus(phase: SessionPhase, holding: Boolean): String = when {
    holding -> "Listening…"
    else -> when (phase) {
        SessionPhase.CONNECTING, SessionPhase.SUMMARIZING -> "Connecting…"
        SessionPhase.ACTIVE -> "Your turn — hold the mic"
        SessionPhase.THINKING, SessionPhase.TOOL_USE -> "Thinking…"
        SessionPhase.SPEAKING -> "Archie is speaking"
        SessionPhase.ENDING -> "Ending…"
        SessionPhase.OFF, SessionPhase.ERROR -> "Live voice"
    }
}

/** The last [LEVEL_BARS] meter levels (≤ 20 Hz). */
@Composable
private fun rememberLevelHistory(voice: TvVoice): SnapshotStateList<Float> {
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(voice) {
        var last = 0L
        voice.controller.level.collect { l ->
            val t = SystemClock.elapsedRealtime()
            if (t - last < 50) return@collect
            last = t
            history.add(l)
            while (history.size > LEVEL_BARS) history.removeAt(0)
        }
    }
    return history
}
