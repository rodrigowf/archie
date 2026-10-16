package com.assistant.archie.tv.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvButtonStyle
import com.assistant.archie.tv.voice.MicCheckUi
import com.assistant.archie.tv.voice.MicStatus
import com.assistant.archie.tv.voice.MicStatusBox
import com.assistant.archie.tv.voice.MicTestMeter
import com.assistant.archie.tv.voice.TvVoiceOwner
import com.assistant.archie.tv.voice.WavPlayback
import com.assistant.archie.tv.voice.rememberMicCheck
import com.assistant.core.design.icons.ArchieIcons

/*
 * Settings → Remote microphone (spec 15 §3.5, mockup 14; T4): the mic's status (Ready / Alexa also
 * listens / no permission), a test meter (hold the mic → level → Play back), "Listen while other
 * apps are open" (Q2, not built yet: disabled) and the start / stop sounds.
 */

class RemoteMicActions(
    val grant: () -> Unit = {},
    val playBack: () -> Unit = {},
    val setCues: (Boolean) -> Unit = {},
)

@Composable
fun RemoteMicRoute(ctx: TvRouteContext) {
    val voice = (ctx.graph.application as? TvVoiceOwner)?.tvVoice
    val ui = rememberMicCheck(voice)
    val cues = voice?.prefs?.cues?.collectAsStateWithLifecycle()?.value ?: true
    RemoteMicPage(
        ui = ui,
        cues = cues,
        actions = RemoteMicActions(
            grant = { voice?.requestPermission() },
            playBack = { voice?.controller?.test?.value?.lastWav?.let(WavPlayback::play) },
            setCues = { voice?.prefs?.setCues(it) },
        ),
    )
}

@Composable
fun RemoteMicPage(ui: MicCheckUi, cues: Boolean, actions: RemoteMicActions, modifier: Modifier = Modifier) {
    // A detail pane of Settings (SettingsScreen publishes the chrome: "Hold to test" while focus is here).
    PaneColumn("settings-remote-mic", modifier) {
        item { MicStatusBox(ui) }
        if (ui.status == MicStatus.NO_PERMISSION) {
            item { TvButton("Allow the microphone", actions.grant, Modifier.testTag("mic-grant"), icon = ArchieIcons.Mic, style = TvButtonStyle.Primary) }
        }
        item { MicTestMeter(ui, actions.playBack) }
        item {
            // Q2 (spec 15 §4.5): the microphone-type service behind this switch is not built yet.
            SettingsToggleRow(
                "Listen while other apps are open", on = false, onToggle = {},
                modifier = Modifier.testTag("mic-background"),
                note = "Coming soon — for now Archie hears the mic button only while it is open",
                enabled = false,
                icon = ArchieIcons.Hearing,
            )
        }
        item {
            SettingsToggleRow(
                "Start and stop sounds", cues, actions.setCues,
                modifier = Modifier.testTag("mic-cues"),
                note = "A short tone when the mic opens and closes",
                icon = ArchieIcons.VolumeUp,
            )
        }
        item {
            SettingsInfoBox(
                "The setup script turned off Alexa's voice UI (com.amazon.vizzini), so the mic button belongs to Archie. To give it back, run tv_setup.sh --restore-alexa on the laptop.",
                lead = "Why Alexa doesn't answer.",
            )
        }
    }
}
