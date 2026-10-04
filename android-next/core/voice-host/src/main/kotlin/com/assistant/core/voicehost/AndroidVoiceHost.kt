package com.assistant.core.voicehost

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.assistant.core.audio.DefaultAudioCore
import com.assistant.core.audio.platform.AndroidAudioTrackFactory
import com.assistant.core.audio.platform.AndroidMicSourceFactory
import com.assistant.core.audio.platform.ElapsedRealtimeClock
import com.assistant.core.audio.platform.LogcatVoiceLog
import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.model.DeviceSettings
import com.assistant.core.network.ArchieApi
import com.assistant.core.network.HttpStack
import com.assistant.core.network.NetLog
import com.assistant.core.network.SocketClient
import com.assistant.core.network.VoiceApi
import com.assistant.core.session.ArchiePoolApi
import com.assistant.core.session.OrchestratorChannel
import com.assistant.core.session.SettingsOrchestratorIdStore
import com.assistant.core.settings.ServicePrefs
import com.assistant.core.settings.SettingsStore
import com.assistant.core.voice.DefaultVoiceTransportFactory
import com.assistant.core.voice.platform.AndroidRtcPlatform
import com.assistant.core.voice.platform.OkHttpSdpExchange
import com.assistant.core.voice.platform.VoiceApiBackend
import com.assistant.core.voice.ports.TranscriptSink
import com.assistant.core.voice.ports.WebRtcDeps
import com.assistant.core.voice.ports.WsPcmDeps
import com.assistant.core.voicehost.cue.AndroidCuePlayer
import com.assistant.core.voicehost.platform.AndroidAudioSession
import com.assistant.core.voicehost.platform.AndroidForegroundBringer
import com.assistant.core.voicehost.platform.AndroidSpeakerMuter
import com.assistant.core.voicehost.platform.MicPushToTalkRecorder
import com.assistant.core.voicehost.platform.PrefsWakeConfigStore
import com.assistant.core.voicehost.runtime.ButtonTriggerPref
import com.assistant.core.voicehost.runtime.RuntimeDeps
import com.assistant.core.voicehost.runtime.ServiceControl
import com.assistant.core.voicehost.runtime.VoiceHostRuntime
import com.assistant.core.voicehost.service.VoiceHostService
import com.assistant.core.wakeword.DefaultWakewordCore
import com.assistant.core.wakeword.WakeTuning
import com.assistant.core.wakeword.platform.AndroidWakeword
import com.assistant.core.wakeword.platform.WakeLoopThread
import com.assistant.core.wakeword.ports.WakeConfig
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.map

/**
 * Production wiring of the [VoiceHostRuntime] (the app graphs call this once, lazily, spec 14 §2.2):
 *
 * ```kotlin
 * // lite (LiteGraph): the runtime owns the orchestrator channel (autoStart) and connects itself.
 * val voiceHost = AndroidVoiceHost.create(app, settings, http, HostConfig.lite(MainActivity::class.java), lastExchangeSink)
 * // main (MainAppGraph): share the graph's channel; ConnectionRepository keeps owning connect / switch.
 * val voiceHost by lazy { AndroidVoiceHost.create(app, settings, http, HostConfig.main(MainActivity::class.java, VoiceTrampolineActivity::class.java), transcripts, channel = orchestrator) }
 * ```
 * The Application implements [com.assistant.core.voicehost.service.VoiceHostOwner] so the service,
 * the accessibility trigger and trampolines reach the same instance.
 */
object AndroidVoiceHost {
    fun create(
        app: Application,
        settings: SettingsStore,
        http: HttpStack,
        config: HostConfig,
        transcripts: TranscriptSink,
        channel: OrchestratorChannel? = null,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        log: VoiceLog = LogcatVoiceLog,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): VoiceHostRuntime {
        val serverUrl: () -> String = { settings.settings.value?.serverUrl ?: DeviceSettings.DEFAULT_SERVER_URL }
        val voiceApi = VoiceApi(http, serverUrl)
        val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clock = ElapsedRealtimeClock
        val audioCore = DefaultAudioCore()
        val recordGranted = { ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
        val netLog = NetLog { level, tag, message -> if (level == 'W' || level == 'E') log.w("Archie/$tag", message) else log.d("Archie/$tag", message) }

        val ch = channel ?: OrchestratorChannel(
            SocketClient(http, scope, log = netLog, tag = "orch"),
            ArchiePoolApi(ArchieApi(http, serverUrl)),
            SettingsOrchestratorIdStore(settings),
            scope,
            OrchestratorChannel.Config(autoStart = true),
        )
        val rtc = AndroidRtcPlatform(app, log)
        val transports = DefaultVoiceTransportFactory(
            webRtcDeps = { WebRtcDeps(io, clock, log, sdkInt, rtc, OkHttpSdpExchange(http.base, log)) },
            wsPcmDeps = { WsPcmDeps(io, clock, log, sdkInt, AndroidMicSourceFactory(), AndroidAudioTrackFactory(app), audioCore, recordGranted) },
        )
        val audio = AndroidAudioSession(app, sdkInt, log)

        val wakeThread = WakeLoopThread()
        val wakewordCore = DefaultWakewordCore()
        val keys = AndroidWakeword.keyProvider(voiceApi)
        val servicePrefs = ServicePrefs.create(app)
        val pttScope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor { r -> Thread(r, "voicehost-ptt").apply { isDaemon = true } }.asCoroutineDispatcher())

        val runtime = VoiceHostRuntime(
            RuntimeDeps(
                scope = scope,
                clock = clock,
                log = log,
                config = config,
                channel = ch,
                settings = settings.settings.map { it?.let(VoiceHostSettings::from) },
                wakeStore = PrefsWakeConfigStore(servicePrefs),
                wakeEngines = { cfg ->
                    // Errata #5: a blank server URL leaves Whisper unconfigured (matches fire unconfirmed, as the old code).
                    val whisper = if (cfg.serverUrl.isBlank()) null else wakewordCore.whisperClient(http.base, WakeTuning.WHISPER_TRANSCRIPTIONS_URL, keys, log)
                    wakewordCore.engine(
                        AndroidWakeword.deps(app, wakeThread, log, whisper, sdkInt),
                        WakeConfig(cfg.talkWord, cfg.wakeWord, cfg.wakeGain, cfg.talkSilenceSensitivity),
                    )
                },
                voiceApi = VoiceApiBackend(voiceApi, log),
                transports = transports,
                audio = audio,
                transcripts = transcripts,
                cuePlayer = AndroidCuePlayer(app, log),
                bringer = AndroidForegroundBringer(app, config.launchActivity, log),
                pcm = audioCore.pcm,
                service = object : ServiceControl {
                    override fun startFromForeground(): Boolean = VoiceHostService.start(app, fromForeground = true)
                    override fun stop() = VoiceHostService.stop(app)
                },
                buttonTriggerPref = ButtonTriggerPref { servicePrefs.buttonTriggerEnabled = it },
                pushToTalk = MicPushToTalkRecorder(AndroidMicSourceFactory(), sdkInt, pttScope, log),
                speakerMuter = AndroidSpeakerMuter(app, log),
                manageConnection = channel == null,
            ),
        )
        // A device appearing / disappearing mid-call re-runs routing (old `registerDeviceCallback`).
        audio.onDevicesChanged = { runtime.lastSettings()?.audioOutput?.let { runtime.session.setAudioOutput(it.toChoice()) } }
        return runtime
    }
}
