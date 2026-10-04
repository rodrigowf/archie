package com.assistant.core.voicehost.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import com.assistant.core.audio.DefaultAudioCore
import com.assistant.core.audio.platform.AndroidAudioFocus
import com.assistant.core.audio.platform.AndroidAudioManagerPort
import com.assistant.core.audio.platform.AndroidDeviceWatcher
import com.assistant.core.audio.ports.AppliedRoute
import com.assistant.core.audio.ports.MicOpenResult
import com.assistant.core.audio.ports.MicSource
import com.assistant.core.audio.ports.MicSourceFactory
import com.assistant.core.audio.ports.MicSpec
import com.assistant.core.audio.ports.OutputChoice
import com.assistant.core.audio.ports.ProviderKind
import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.settings.ServicePrefs
import com.assistant.core.settings.WakeServicePrefs
import com.assistant.core.voice.ports.AudioSessionPort
import com.assistant.core.voicehost.HostTuning
import com.assistant.core.voicehost.ports.WakeConfigStore
import com.assistant.core.voicehost.ports.WakeServiceConfig
import com.assistant.core.voicehost.runtime.PushToTalkRecorder
import com.assistant.core.voicehost.runtime.SpeakerMuter
import com.assistant.core.voicehost.trigger.ForegroundBringer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The voice session's OS audio session over `:core:audio` (old `VoiceManager` focus / routing /
 * device callback, inv04 §3.4): focus + STREAM_VOICE_CALL raise + device watcher on acquire,
 * `RouteDecider` + `RouteApplier` per apply, everything undone on release.
 * [onDevicesChanged] lets the runtime re-run routing when an output appears or disappears.
 */
class AndroidAudioSession(context: Context, private val sdkInt: Int, private val log: VoiceLog) : AudioSessionPort {
    private val audioCore = DefaultAudioCore()
    private val am = AndroidAudioManagerPort(context.applicationContext)
    private val focus = AndroidAudioFocus(am, sdkInt, log)
    private val applier = audioCore.routeApplier(am, sdkInt, log)
    private val watcher = AndroidDeviceWatcher(am, sdkInt, log)

    @Volatile var onDevicesChanged: () -> Unit = {}

    override fun acquireFocus() {
        focus.request()
        focus.ensureCallStreamAudible()
        watcher.start { onDevicesChanged() }
    }

    override fun applyRoute(provider: ProviderKind, desired: OutputChoice): AppliedRoute {
        val route = audioCore.routeDecider.pickRoute(desired, provider, applier.availableOutputs())
        return applier.apply(route)
    }

    override fun release() {
        watcher.stop()
        applier.release()
        focus.abandon()
    }
}

/**
 * Lite: on a wake/talk trigger, turn the screen on and bring the face to the front (old
 * `AssistantService.bringToForeground`): a [HostTuning.FOREGROUND_WAKE_LOCK_MS]
 * `SCREEN_BRIGHT | ACQUIRE_CAUSES_WAKEUP` wake lock (the reliable Lollipop path) + `startActivity`
 * with NEW_TASK | REORDER_TO_FRONT | SINGLE_TOP. Voice itself already started in the runtime, so a
 * slow Activity start can no longer lose the trigger (R2).
 */
class AndroidForegroundBringer(
    private val context: Context,
    private val activity: Class<out Activity>,
    private val log: VoiceLog,
) : ForegroundBringer {
    override fun bringToForeground() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "assistant:wakeword")
            wl.acquire(HostTuning.FOREGROUND_WAKE_LOCK_MS)
            context.startActivity(
                Intent(context, activity).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_WAKE_WORD_TRIGGERED, true)
                },
            )
        } catch (e: Exception) {
            log.w(TAG, "bringToForeground failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AssistantService"

        /** Same extra as the old `AssistantService.EXTRA_WAKE_WORD_TRIGGERED`. */
        const val EXTRA_WAKE_WORD_TRIGGERED = "wake_word_triggered"
    }
}

/** [WakeConfigStore] over `assistant_service_prefs` (`:core:settings` `ServicePrefs`, same file and keys). */
class PrefsWakeConfigStore(private val prefs: ServicePrefs) : WakeConfigStore {
    override fun load(): WakeServiceConfig = prefs.loadWake().let {
        WakeServiceConfig(it.enabled, it.talkWord, it.wakeWord, it.wakeGain, it.talkSilenceSensitivity, it.serverUrl)
    }

    override fun save(config: WakeServiceConfig) = prefs.saveWake(
        WakeServicePrefs(config.enabled, config.talkWord, config.wakeWord, config.wakeGain, config.talkSilenceSensitivity, config.serverUrl),
    )
}

/**
 * Push-to-talk (main app): one AudioRecord at 16 kHz with the API-level source policy, read on
 * [scope] (a single IO thread, so read and release share it, R4), capped at
 * [HostTuning.PTT_MAX_MS]; [stop] returns a mono PCM16 WAV.
 */
class MicPushToTalkRecorder(
    private val mics: MicSourceFactory,
    private val sdkInt: Int,
    private val scope: CoroutineScope,
    private val log: VoiceLog,
) : PushToTalkRecorder {
    private val audioCore = DefaultAudioCore()
    private val lock = Any()
    private var job: Job? = null
    private val frames = ArrayList<ShortArray>()

    override fun start(): Boolean {
        val rate = HostTuning.PTT_SAMPLE_RATE_HZ
        val spec = MicSpec(
            sampleRateHz = rate,
            source = audioCore.micSourcePolicy.sourceFor(sdkInt),
            bufferBytes = audioCore.bufferSizing.micBufferBytes(mics.minBufferBytes(rate), rate),
        )
        val mic = when (val r = mics.open(spec)) {
            is MicOpenResult.Opened -> r.mic
            is MicOpenResult.Failed -> {
                log.w(TAG, "push-to-talk mic open failed: ${r.reason}")
                return false
            }
        }
        synchronized(lock) {
            frames.clear()
            job = scope.launch { capture(mic, rate) }
        }
        return true
    }

    private suspend fun capture(mic: MicSource, rate: Int) {
        val maxSamples = rate * HostTuning.PTT_MAX_MS / 1000
        var total = 0L
        try {
            val buf = ShortArray(HostTuning.PTT_READ_SAMPLES)
            while (kotlin.coroutines.coroutineContext.isActive && total < maxSamples) {
                val n = mic.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                synchronized(lock) { frames += buf.copyOf(n) }
                total += n
            }
        } finally {
            mic.release()
        }
    }

    override suspend fun stop(): ByteArray? {
        val j = synchronized(lock) { job.also { job = null } } ?: return null
        j.cancel()
        j.join()
        val captured = synchronized(lock) { frames.toList().also { frames.clear() } }
        if (captured.isEmpty()) return null
        return audioCore.pcm.wav(captured, HostTuning.PTT_SAMPLE_RATE_HZ)
    }

    private companion object {
        const val TAG = "PushToTalk"
    }
}

/** Speaker mute: mutes the call and media streams (what Archie says), restored on unmute. */
class AndroidSpeakerMuter(context: Context, private val log: VoiceLog) : SpeakerMuter {
    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun setMuted(muted: Boolean) {
        for (stream in intArrayOf(AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_MUSIC)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.adjustStreamVolume(stream, if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
                } else {
                    @Suppress("DEPRECATION")
                    am.setStreamMute(stream, muted)
                }
            } catch (e: Exception) {
                log.w("VoiceHost", "speaker mute($muted) on stream $stream failed: ${e.message}")
            }
        }
    }
}
