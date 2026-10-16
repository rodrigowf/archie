package com.assistant.archie.tv.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.assistant.archie.tv.theme.TvButton
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

/*
 * The remote-mic status + test meter (spec 15 §3.5 Settings → Remote microphone, §3.6 first-run
 * step 2; mockup 14). Shared by the settings page and the first-run check.
 */

/** What the status box says. */
enum class MicStatus { READY, ALEXA_LISTENS, NO_PERMISSION, UNAVAILABLE, NO_VOICE }

/** The status + meter as the screens render them (state-driven for the goldens). */
@Immutable
data class MicCheckUi(
    val status: MicStatus,
    /** Alexa's voice UI is absent on this TV (not a Fire TV): no "Alexa stays quiet" line. */
    val alexaAbsent: Boolean = false,
    val holding: Boolean = false,
    /** Live level while holding, the last burst's peak otherwise (0..1). */
    val level: Float = 0f,
    val canPlayBack: Boolean = false,
    val lastDurationMs: Long = 0,
    val heard: Boolean = false,
)

fun micStatus(permission: Boolean, mic: RemoteMicState, alexa: AlexaState, voice: Boolean = true): MicStatus = when {
    !voice -> MicStatus.NO_VOICE
    !permission || mic == RemoteMicState.NO_PERMISSION -> MicStatus.NO_PERMISSION
    alexa == AlexaState.LISTENS -> MicStatus.ALEXA_LISTENS
    mic == RemoteMicState.UNAVAILABLE -> MicStatus.UNAVAILABLE
    else -> MicStatus.READY
}

/** Live [MicCheckUi] from [TvVoice]; registers the mic test while composed (bursts come here). */
@Composable
fun rememberMicCheck(voice: TvVoice?): MicCheckUi {
    val context = LocalContext.current
    if (voice == null) return MicCheckUi(MicStatus.NO_VOICE)
    DisposableEffect(voice) {
        voice.controller.testing = true
        onDispose { voice.controller.testing = false }
    }
    val permission by voice.permission.collectAsStateWithLifecycle()
    val mic by voice.mic.state.collectAsStateWithLifecycle()
    val test by voice.controller.test.collectAsStateWithLifecycle()
    val level by voice.controller.level.collectAsStateWithLifecycle()
    val alexa = remember(permission, mic) { AlexaProbe.state(context) }
    return MicCheckUi(
        status = micStatus(permission, mic, alexa),
        alexaAbsent = alexa == AlexaState.ABSENT,
        holding = test.holding,
        level = if (test.holding) level else test.peak,
        canPlayBack = test.lastWav != null && !test.holding,
        lastDurationMs = test.lastDurationMs,
        heard = test.heard,
    )
}

/** The status box (icon + title + one line). */
@Composable
fun MicStatusBox(ui: MicCheckUi, modifier: Modifier = Modifier) {
    val c = TvTheme.colors
    val (icon, title, line) = statusText(ui)
    val bad = ui.status != MicStatus.READY
    Row(
        modifier
            .fillMaxWidth()
            .background(if (bad) c.errorContainer else c.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .testTag("mic-status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (bad) c.onErrorContainer else c.primary, modifier = Modifier.size(28.dp))
        Column {
            Text(title, style = TvTheme.type.titleMedium, color = if (bad) c.onErrorContainer else c.onSurface)
            Text(line, style = TvTheme.type.bodyMedium, color = if (bad) c.onErrorContainer else c.onSurfaceVariant)
        }
    }
}

private fun statusText(ui: MicCheckUi): Triple<ImageVector, String, String> = when (ui.status) {
    MicStatus.READY -> Triple(
        ArchieIcons.CheckCircle,
        "Ready — the mic button talks to Archie",
        if (ui.alexaAbsent) "Hold it anywhere in the app." else "Hold it anywhere in the app. Alexa stays quiet.",
    )
    MicStatus.ALEXA_LISTENS -> Triple(
        ArchieIcons.Warning,
        "Alexa also listens",
        "Run tv_setup.sh on the laptop to give the mic button to Archie.",
    )
    MicStatus.NO_PERMISSION -> Triple(ArchieIcons.MicOff, "No audio permission", "Allow the microphone so Archie can hear the remote.")
    MicStatus.UNAVAILABLE -> Triple(ArchieIcons.MicOff, "The microphone is busy", "Another app holds it. Archie keeps trying.")
    MicStatus.NO_VOICE -> Triple(ArchieIcons.MicOff, "Voice is not available", "This build has no voice.")
}

/** The test meter row: "Test · Hold the mic and say hello", the bar, Play back. */
@Composable
fun MicTestMeter(ui: MicCheckUi, onPlayBack: () -> Unit, modifier: Modifier = Modifier, playBackModifier: Modifier = Modifier) {
    val c = TvTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.surfaceContainer, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .testTag("mic-meter"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(ArchieIcons.Mic, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Column(Modifier.width(190.dp)) {
            Text("Test", style = TvTheme.type.titleMedium, color = c.onSurface)
            Text(
                when {
                    ui.holding -> "Listening…"
                    ui.lastDurationMs > 0 -> "Heard %.1f s — Play back to check".format(ui.lastDurationMs / 1000f)
                    else -> "Hold the mic and say hello"
                },
                style = TvTheme.type.labelMedium,
                color = c.onSurfaceVariant,
            )
        }
        Box(Modifier.weight(1f).height(12.dp).background(c.surfaceContainerHighest, RoundedCornerShape(6.dp))) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(ui.level.coerceIn(0f, 1f).coerceAtLeast(0.02f))
                    .background(ArchieTheme.extended.success.color, RoundedCornerShape(6.dp)),
            )
        }
        TvButton("Play back", onPlayBack, playBackModifier.testTag("mic-playback"), icon = ArchieIcons.VolumeUp, enabled = ui.canPlayBack)
    }
}
