package com.assistant.core.design.components

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.core.design.Motion
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme

/** Orb tones: listening/thinking (primary), speaking (tertiary), idle/elsewhere (outline), reconnecting (warning). */
enum class OrbTone { Listening, Speaking, Idle, Reconnecting }

private val BarX = floatArrayOf(34.5f, 43f, 51.5f, 60f)
private val BarH = floatArrayOf(14f, 28f, 22f, 12f)

/**
 * The voice level orb (mockup `.orb`, 100-unit viewBox): two pulsing halos, a solid core and four
 * level bars. [level] (0..1) drives the bars from the live audio level; null animates them.
 * Reconnecting is dimmed and warning-toned with flat bars. Still under reduce motion or [still].
 */
@Composable
fun VoiceOrb(
    tone: OrbTone,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    level: Float? = null,
    still: Boolean = false,
) {
    val c = ArchieTheme.colors
    val x = ArchieTheme.extended
    val (base, on) = when (tone) {
        OrbTone.Listening -> c.primary to c.onPrimary
        OrbTone.Speaking -> c.tertiary to c.onTertiary
        OrbTone.Idle -> c.outline to c.onPrimary
        OrbTone.Reconnecting -> x.warning.color to x.warning.onColor
    }
    val animate = !still && !ArchieTheme.reduceMotion
    val recon = tone == OrbTone.Reconnecting
    val t = if (animate) rememberInfiniteTransition(label = "orb") else null
    val pulseMs = if (recon) 1600 else 2600
    val p1 = t.pulse(pulseMs, 0, 0.84f, 1f)
    val p2 = t.pulse(pulseMs, 300, 0.84f, 1f)
    val dim = if (recon) t.pulse(1600, 0, 0.4f, 0.75f, still = 0.6f) else null
    val bars = (0 until 4).map { i -> t.pulse(1100, 150 * i, 0.55f, 1f) }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 100f
        scale(k, k, pivot = Offset.Zero) {
            val center = Offset(50f, 50f)
            val haloAlpha1 = 0.14f
            val haloAlpha2 = if (recon) 0.26f else 0.28f
            drawCircle(base.copy(alpha = haloAlpha1), radius = 48f * p1.value, center = center)
            drawCircle(base.copy(alpha = haloAlpha2), radius = 39f * p2.value, center = center)
            val coreAlpha = if (recon) (dim?.value ?: 0.6f) else 1f
            drawCircle(base.copy(alpha = coreAlpha), radius = 29f, center = center)
            for (i in 0 until 4) {
                val sy = when {
                    recon -> 0.28f
                    level != null -> 0.35f + 0.65f * level.coerceIn(0f, 1f)
                    else -> bars[i].value
                }
                val h = BarH[i] * sy
                drawRoundRect(
                    color = on,
                    topLeft = Offset(BarX[i], 50f - h / 2),
                    size = Size(6f, h),
                    cornerRadius = CornerRadius(3f, 3f),
                )
            }
        }
    }
}

/** An infinite [from]↔[to] pulse (CSS `ease`, alternate), or [still] when motion is off. */
@Composable
private fun InfiniteTransition?.pulse(periodMs: Int, delayMs: Int, from: Float, to: Float, still: Float = to): State<Float> {
    if (this == null) return remember { mutableFloatStateOf(still) }
    return animateFloat(
        initialValue = from,
        targetValue = to,
        animationSpec = infiniteRepeatable(
            tween(periodMs / 2, easing = Motion.EasingStandard),
            RepeatMode.Reverse,
            StartOffset(delayMs),
        ),
        label = "pulse",
    )
}

/** Voice state words (IA §6). */
enum class VoiceDockState(val label: String) { Listening("Listening"), Speaking("Speaking"), Thinking("Thinking"), UsingTools("Using tools") }

/**
 * Voice dock (IA §6, mockup `.dock`): the composer becomes this while voice is on — level orb,
 * state word and hint, then controls ([controls]: mic mute, speaker mute, end). r32 on
 * surface-container-high, 84 dp tall.
 */
@Composable
fun VoiceDock(
    state: VoiceDockState,
    hint: String,
    modifier: Modifier = Modifier,
    level: Float? = null,
    controls: @Composable RowScope.() -> Unit,
) {
    val tone = if (state == VoiceDockState.Speaking) OrbTone.Speaking else OrbTone.Listening
    DockFrame(modifier.semantics { liveRegion = LiveRegionMode.Polite }, minHeight = 84.dp) {
        VoiceOrb(tone, level = level)
        DockLabel(state.label, hint, titleSize = 18)
        controls()
    }
}

/** The standard dock controls: mic mute (toggle), speaker mute (toggle), end. */
@Composable
fun RowScope.VoiceDockControls(
    micMuted: Boolean,
    onToggleMic: () -> Unit,
    speakerMuted: Boolean,
    onToggleSpeaker: () -> Unit,
    onEnd: () -> Unit,
) {
    ArchieIconButton(
        if (micMuted) ArchieIcons.MicOffFilled else ArchieIcons.Mic,
        if (micMuted) "Unmute microphone" else "Mute microphone",
        onToggleMic,
        style = if (micMuted) IconButtonStyle.Selected else IconButtonStyle.Tonal,
        checked = micMuted,
    )
    ArchieIconButton(
        if (speakerMuted) ArchieIcons.VolumeOffFilled else ArchieIcons.VolumeUp,
        if (speakerMuted) "Unmute speaker" else "Mute speaker",
        onToggleSpeaker,
        style = if (speakerMuted) IconButtonStyle.Selected else IconButtonStyle.Tonal,
        checked = speakerMuted,
    )
    ArchieIconButton(ArchieIcons.CallEndFilled, "End voice", onEnd, style = IconButtonStyle.Error)
}

/**
 * Read-only dock while voice runs on another device (mockup `.dock.ro`): idle still orb,
 * "Voice active on <device>", and Take over. Text input stays usable elsewhere on screen.
 */
@Composable
fun VoiceDockElsewhere(device: String, onTakeOver: () -> Unit, modifier: Modifier = Modifier, hint: String = "Transcripts mirror here") {
    DockFrame(modifier, minHeight = 64.dp) {
        VoiceOrb(OrbTone.Idle, size = 44.dp, still = true)
        DockLabel("Voice active on $device", hint, titleSize = 16)
        ArchieButton("Take over", onTakeOver, style = ButtonStyle.Text)
    }
}

/**
 * Reconnecting (P-2, mockup `.dock.recon`): warning orb, "Reconnecting…" with the elapsed
 * [elapsed] timer, a wrapping explanation, and End (still works).
 */
@Composable
fun VoiceDockReconnecting(
    elapsed: String,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "Reconnecting to Archie · you'll hear a tone when it's back",
) {
    val x = ArchieTheme.extended
    DockFrame(modifier.semantics { liveRegion = LiveRegionMode.Polite }, minHeight = 84.dp) {
        VoiceOrb(OrbTone.Reconnecting)
        DockLabel("Reconnecting…", hint, titleSize = 18, wrapHint = true) {
            Spacer(Modifier.width(8.dp))
            Text(
                elapsed,
                style = ArchieTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.W600, fontFeatureSettings = "tnum"),
                color = x.warning.color,
            )
        }
        ArchieIconButton(ArchieIcons.CallEndFilled, "End voice", onEnd, style = IconButtonStyle.Error)
    }
}

/** The two outcomes after reconnecting (mockup `.dock.mini.ok` / `.fail`). */
enum class ReconnectOutcome { Reconnected, Failed }

/**
 * Short outcome dock: Reconnected (success-container, check tile) or Couldn't reconnect
 * (error-container, wifi-off tile, close + Reconnect on a second row).
 */
@Composable
fun VoiceDockOutcome(
    outcome: ReconnectOutcome,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    onReconnect: () -> Unit = {},
    onEnd: () -> Unit = {},
) {
    val c = ArchieTheme.colors
    val x = ArchieTheme.extended
    val ok = outcome == ReconnectOutcome.Reconnected
    val bg = if (ok) x.success.colorContainer else c.errorContainer
    val fg = if (ok) x.success.onColorContainer else c.onErrorContainer
    val tileBg = if (ok) x.success.color else c.error
    val tileFg = if (ok) x.success.onColor else c.onError
    CompositionLocalProvider(LocalContentColor provides fg) {
        Column(
            modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .background(bg, RoundedCornerShape(24.dp))
                .semantics { liveRegion = if (ok) LiveRegionMode.Polite else LiveRegionMode.Assertive }
                .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).background(tileBg, CircleShape), contentAlignment = Alignment.Center) {
                    ArchieIcon(if (ok) ArchieIcons.Check else ArchieIcons.WifiOff, null, tint = tileFg)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = ArchieTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp))
                    Text(hint, Modifier.alpha(0.85f), style = ArchieTheme.typography.bodySmall.copy(letterSpacing = 0.sp))
                }
                if (!ok) ArchieIconButton(ArchieIcons.Close, "End voice", onEnd, size = 40.dp, iconSize = 20.dp, tint = fg)
            }
            if (!ok) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ArchieButton("Reconnect", onReconnect, style = ButtonStyle.Danger, size = ButtonSize.Small, icon = ArchieIcons.Refresh)
                }
            }
        }
    }
}

@Composable
private fun DockFrame(modifier: Modifier, minHeight: Dp, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .background(ArchieTheme.colors.surfaceContainerHigh, RoundedCornerShape(32.dp))
            .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun RowScope.DockLabel(
    title: String,
    hint: String,
    titleSize: Int,
    wrapHint: Boolean = false,
    titleTrailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = ArchieTheme.colors
    Column(Modifier.weight(1f).padding(start = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = ArchieTheme.typography.titleMedium.copy(fontSize = titleSize.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            titleTrailing?.invoke(this)
        }
        Text(
            hint,
            style = ArchieTheme.typography.bodySmall.copy(letterSpacing = 0.sp),
            color = c.onSurfaceVariant,
            maxLines = if (wrapHint) 3 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
