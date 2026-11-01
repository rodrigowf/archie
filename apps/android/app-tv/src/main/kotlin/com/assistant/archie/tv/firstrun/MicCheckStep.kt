package com.assistant.archie.tv.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.assistant.archie.tv.shell.HintGlyph
import com.assistant.archie.tv.shell.KeyHint
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvButtonStyle
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.theme.rememberInitialFocus
import com.assistant.archie.tv.voice.MicCheckUi
import com.assistant.archie.tv.voice.MicStatus
import com.assistant.archie.tv.voice.MicStatusBox
import com.assistant.archie.tv.voice.MicTestMeter
import com.assistant.archie.tv.voice.TvVoiceOwner
import com.assistant.archie.tv.voice.WavPlayback
import com.assistant.archie.tv.voice.rememberMicCheck
import com.assistant.core.design.icons.ArchieIcons

/*
 * First run, step 2 (spec 15 §3.6; T4): the remote-mic check. Asks for RECORD_AUDIO — on Fire TV the
 * system dialog never appears and `TvVoice` falls back to Fire OS's app page (see `MicPermission`) —
 * explains "hold the mic button and say hello" with a live meter, and shows Alexa's state.
 * Continue (or Skip) finishes setup.
 */

class MicCheckActions(
    val grant: () -> Unit = {},
    val playBack: () -> Unit = {},
    val done: () -> Unit = {},
)

@Composable
fun MicCheckRoute(ctx: TvRouteContext, onDone: () -> Unit) {
    val voice = (ctx.graph.application as? TvVoiceOwner)?.tvVoice
    val ui = rememberMicCheck(voice)
    MicCheckStep(
        ui,
        MicCheckActions(
            grant = { voice?.requestPermission() },
            playBack = { voice?.controller?.test?.value?.lastWav?.let(WavPlayback::play) },
            done = onDone,
        ),
    )
}

@Composable
fun MicCheckStep(ui: MicCheckUi, actions: MicCheckActions, modifier: Modifier = Modifier) {
    ScreenChromeEffect(
        ScreenChrome(title = "Remote microphone", hints = listOf(KeyHints.Ok("Select"), KeyHint(HintGlyph.MIC, "Hold to test"), KeyHints.Back)),
    )
    val c = TvTheme.colors
    val first = rememberInitialFocus(ui.status == MicStatus.NO_PERMISSION)
    Column(modifier.fillMaxSize().widthIn(max = 760.dp).testTag("mic-check"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Step 2 of 2 · Remote mic", style = TvTheme.type.labelLarge, color = c.onSurfaceVariant)
        Text(
            if (ui.heard) "Archie heard you." else "Hold the mic button on the remote and say hello.",
            style = TvTheme.type.titleLarge,
            color = c.onSurface,
        )
        Text(
            "Anywhere in the app, holding the mic button talks to Archie. In an agent conversation it dictates instead. ⏯ starts a live voice conversation.",
            style = TvTheme.type.bodyMedium,
            color = c.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        MicStatusBox(ui)
        MicTestMeter(ui, actions.playBack)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (ui.status == MicStatus.NO_PERMISSION) {
                TvButton("Allow the microphone", actions.grant, Modifier.focusRequester(first).testTag("mic-check-grant"), icon = ArchieIcons.Mic, style = TvButtonStyle.Primary)
                TvButton("Skip for now", actions.done, Modifier.testTag("mic-check-skip"), icon = ArchieIcons.ArrowForward)
            } else {
                TvButton(
                    if (ui.heard) "Continue" else "Skip the test",
                    actions.done,
                    Modifier.focusRequester(first).testTag("mic-check-continue"),
                    icon = ArchieIcons.ArrowForward,
                    style = TvButtonStyle.Primary,
                )
            }
        }
    }
}
