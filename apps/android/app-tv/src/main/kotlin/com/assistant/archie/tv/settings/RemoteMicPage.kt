package com.assistant.archie.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.assistant.archie.tv.shell.KeyHint
import com.assistant.archie.tv.shell.HintGlyph
import com.assistant.archie.tv.shell.KeyHints
import com.assistant.archie.tv.shell.ScreenChrome
import com.assistant.archie.tv.shell.ScreenChromeEffect
import com.assistant.archie.tv.shell.TvRouteContext
import com.assistant.archie.tv.shell.TvSettingsPageId
import com.assistant.archie.tv.theme.CardMeta
import com.assistant.archie.tv.theme.FocusCard
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
    ScreenChromeEffect(
        ScreenChrome(
            title = TvSettingsPageId.REMOTE_MIC.title,
            hints = listOf(KeyHints.Ok("Change"), KeyHint(HintGlyph.MIC, "Hold to test"), KeyHints.Back),
        ),
    )
    val c = TvTheme.colors
    val first = rememberInitialFocus()
    LazyColumn(
        modifier.fillMaxSize().widthIn(max = 760.dp).testTag("settings-remote-mic"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
    ) {
        item { MicStatusBox(ui) }
        if (ui.status == MicStatus.NO_PERMISSION) {
            item { TvButton("Allow the microphone", actions.grant, Modifier.focusRequester(first).testTag("mic-grant"), icon = ArchieIcons.Mic, style = TvButtonStyle.Primary) }
            item { MicTestMeter(ui, actions.playBack) }
        } else {
            item { MicTestMeter(ui, actions.playBack, playBackModifier = if (ui.canPlayBack) Modifier.focusRequester(first) else Modifier) }
        }
        item {
            // Q2 (spec 15 §4.5): the microphone-type service behind this toggle is not built yet.
            FocusCard(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().testTag("mic-background")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ArchieIcons.Hearing, contentDescription = null, modifier = Modifier.size(26.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.size(18.dp))
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text("Listen while other apps are open", style = TvTheme.type.titleMedium)
                        CardMeta("Coming soon — for now Archie hears the mic button only while it is open")
                    }
                    Text("Off", style = TvTheme.type.labelLarge)
                }
            }
        }
        item {
            SettingsRow(
                "Start and stop sounds",
                if (cues) "On" else "Off",
                ArchieIcons.VolumeUp,
                onClick = { actions.setCues(!cues) },
                modifier = (if (!ui.canPlayBack && ui.status != MicStatus.NO_PERMISSION) Modifier.focusRequester(first) else Modifier).testTag("mic-cues"),
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(ArchieIcons.Info, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.onSurface)) { append("Why Alexa doesn't answer. ") }
                        append("The setup script turned off Alexa's voice UI (com.amazon.vizzini), so the mic button belongs to Archie. ")
                        append("To give it back, run tv_setup.sh --restore-alexa on the laptop.")
                    },
                    style = TvTheme.type.bodyMedium,
                    color = c.onSurfaceVariant,
                )
            }
        }
    }
}
