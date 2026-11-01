package com.assistant.archie.tv.voice

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.assistant.archie.tv.graph.TvAppGraph
import com.assistant.archie.tv.shell.Conversation
import com.assistant.archie.tv.shell.MainActivity
import com.assistant.core.audio.platform.ElapsedRealtimeClock
import com.assistant.core.audio.ports.MicSourceType
import com.assistant.core.data.ConversationKey
import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
import com.assistant.core.network.RestCaller
import com.assistant.core.voice.ports.TranscriptSink
import com.assistant.core.voicehost.AndroidVoiceHost
import com.assistant.core.voicehost.HostAudio
import com.assistant.core.voicehost.HostConfig
import com.assistant.core.voicehost.cue.AndroidCuePlayer
import com.assistant.core.voicehost.cue.CueKind
import com.assistant.core.voicehost.runtime.VoiceHostRuntime
import com.assistant.core.audio.platform.LogcatVoiceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Reached as `(application as TvVoiceOwner).tvVoice` (null in JVM tests: no voice). */
interface TvVoiceOwner {
    val tvVoice: TvVoice?
}

/** What a finished RECORD_AUDIO request leaves the app able to do. */
enum class MicPermission {
    GRANTED,

    /** Denied, but the platform will ask again — the next press can still succeed. */
    RETRY,

    /**
     * Denied with no way to ask again, so only app settings can grant it. "Don't ask again" gets
     * here, and so does **every** request on Fire TV: it answers denied without ever showing the
     * dialog, which is why the mic is provisioned by `tv_setup.sh` rather than asked for (spec 15
     * §4). Before this existed the app just toasted and the user was stuck needing adb.
     */
    BLOCKED,

    ;

    companion object {
        fun of(granted: Boolean, canAskAgain: Boolean): MicPermission = when {
            granted -> GRANTED
            canAskAgain -> RETRY
            else -> BLOCKED
        }
    }
}

/**
 * Archie TV's voice (spec 15 §4), process-scoped: the remote's microphone ([mic]), the router and
 * overlay state ([controller]), live voice ([live]) over the shared voice host, and the settings
 * ([prefs]). [install] plugs it into the graph's seams (`voice`, `liveVoice`) and the process
 * lifecycle (the mic is armed while the app is started).
 */
class TvVoice(private val app: Application, private val graph: TvAppGraph, val bridge: LiveMicBridge) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = ElapsedRealtimeClock
    val prefs = TvVoicePrefs(app)
    private val cues by lazy { AndroidCuePlayer(app, LogcatVoiceLog) }

    private val _permission = MutableStateFlow(granted())
    /** RECORD_AUDIO granted (re-read on every start and after the system dialog). */
    val permission: StateFlow<Boolean> = _permission.asStateFlow()

    /** Set by the overlay host while the Activity is composed: launches the system permission dialog. */
    @Volatile var permissionRequester: (() -> Unit)? = null

    val live = TvLiveVoice(
        host = graph.voiceHost,
        bridge = bridge,
        scope = scope,
        clock = clock,
        hasPermission = ::granted,
        requestPermission = ::requestPermission,
        toast = { t, e -> graph.toasts.show(t, error = e) },
        keepAwake = { graph.launcherPing.ping(seconds = KEEP_AWAKE_SECONDS, force = true) },
        idleSeconds = { prefs.liveIdleSeconds },
        log = { Log.i(TAG, it) },
    )

    val controller = TvVoiceController(
        scope = scope,
        inputs = ::routeInputs,
        messages = OrchestratorVoiceMessages(graph.orchestrator, graph.conversations) { Log.i(TAG, it) },
        transcriber = TranscribeClient(RestCaller(graph.http, graph::currentServerUrl)),
        live = live,
        toast = { t, e -> graph.toasts.show(t, error = e) },
        cue = { start -> if (prefs.cues.value) cues.play(if (start) CueKind.WAKE_ACK else CueKind.TALK_ACK) },
        labelFor = ::label,
        sendDictation = ::sendDictation,
        log = { Log.d(TAG, it) },
    )

    val mic = RemoteMicSource(AndroidRemoteRecorders, clock, ::granted, controller::onBurst, log = { Log.i(TAG, it) })

    /** The chip / overlay's view of the live session (Idle when the host is missing). */
    val session = graph.voiceHost?.state?.map { it.session }?.stateIn(scope, SharingStarted.Eagerly, graph.voiceHost!!.state.value.session)

    fun install() {
        graph.voice = TvVoicePresence(session, controller, scope)
        graph.liveVoice = live
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _permission.value = granted()
                mic.start()
            }

            override fun onStop(owner: LifecycleOwner) {
                // Q2 "Listen while other apps are open" is not built yet (spec 15 §4.5): disarm.
                mic.stop()
            }
        })
    }

    /**
     * The system dialog answered. [canAskAgain] is the Activity's
     * `shouldShowRequestPermissionRationale` *after* the result: false means the platform will not
     * put that dialog up again, so repeating the request can only fail — see [MicPermission].
     */
    fun onPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        _permission.value = granted
        when (MicPermission.of(granted, canAskAgain)) {
            MicPermission.GRANTED -> mic.start()
            MicPermission.RETRY -> graph.toasts.show("Archie needs the microphone to hear the remote", error = true)
            MicPermission.BLOCKED -> openAppSettings()
        }
    }

    fun requestPermission() {
        val r = permissionRequester
        if (r != null) r() else openAppSettings()
    }

    /**
     * The one screen that can still grant the mic: Fire OS's app page, which opens on "Permissions"
     * with Microphone first. The in-app [TvToasts] would be hidden behind it, so the explanation is
     * a platform toast, which outlives the switch (as `:app-main`'s SystemOverlays does).
     */
    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { app.startActivity(intent) }.isSuccess
        if (opened) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(app, "Archie needs the microphone: choose Permissions → Microphone", Toast.LENGTH_LONG).show()
            }
        } else {
            // No app-details screen (not a Fire TV): the path still names where to look.
            graph.toasts.show("Allow the microphone for Archie in Settings → Applications", error = true)
        }
        Log.i(TAG, "mic permission blocked — app settings opened=$opened")
    }

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun routeInputs(): RouteInputs {
        val screen = graph.navHolder.current.value
        return RouteInputs(
            liveOwned = live.liveOwned(),
            screen = screen,
            agentTitle = (screen as? Conversation)?.let { agentName(it) },
        )
    }

    private fun agentName(c: Conversation): String? {
        val key = c.localId?.let { ConversationKey.agent(it) } ?: return null
        val provider = graph.conversations.current(key)?.ref?.provider ?: return null
        return provider.label
    }

    private fun label(d: VoiceDestination): String = TvVoiceController.defaultLabel(d)

    /**
     * Dictation Send (mockup 9): the text goes to the agent conversation the way its composer sends
     * it (`ConversationRepository.send`). The conversation is open (it is on screen); the lookup
     * falls back to `openAgent`, which returns the already-open view for that session.
     */
    private fun sendDictation(target: VoiceDestination.Dictation, text: String): Boolean {
        val c = graph.conversations
        val direct = target.localId?.let { ConversationKey.agent(it) }?.takeIf { c.current(it) != null }
        val key = direct ?: run {
            val localId = target.localId ?: return false
            c.openAgent(SessionRef(localId, target.sdkId, SessionKind.AGENT, provider = null, live = true))
        }
        val sent = c.send(key, text)
        Log.i(TAG, "dictation → ${key.value} (${text.length} chars): $sent")
        return sent != null
    }

    /** Runs [block] on the voice scope (UI callbacks that suspend). */
    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)

    companion object {
        private const val TAG = "ArchieTv/Voice"

        /** KEEP_AWAKE window per live-voice ping (pings come every 60 s). */
        const val KEEP_AWAKE_SECONDS = 180

        /**
         * The voice host factory for `TvAppGraph` (spec 15 §4.2): `HostConfig.tv` — no wake word,
         * the remote's audio through the bridge into both transports (WS relay: a self-paced mic;
         * WebRTC: the ADM's buffers replaced, its own recorder on VOICE_COMMUNICATION as a
         * non-blocking clock — see spec 15 §4.2), the call on the media stream.
         */
        fun hostFactory(b: LiveMicBridge): (Application, TvAppGraph) -> VoiceHostRuntime = { app, graph ->
            AndroidVoiceHost.create(
                app = app,
                settings = graph.settings,
                http = graph.http,
                config = HostConfig.tv(
                    launchActivity = MainActivity::class.java,
                    audio = HostAudio(
                        micSource = TvVoiceTuning.REMOTE_MIC_SOURCE,
                        wsMics = b.mics,
                        rtcSource = MicSourceType.VOICE_COMMUNICATION,
                        rtcFeed = b.rtcFeed,
                        mediaPlayback = true,
                    ),
                ),
                transcripts = TvTranscriptSink(onVoiceMessageSent = graph.conversations::voiceMessageSent),
                channel = graph.orchestrator,
                scope = graph.scope,
                dataChannelTap = graph.conversations::voiceDataChannelEvent,
            )
        }
    }
}

/**
 * As `:app-main`'s `MainTranscriptSink`: the Archie timeline gets the transcripts from the reducer
 * (WS providers via `voice_event`, the OpenAI owner via `dataChannelTap`); the live panel reads the
 * host's transcript events. Only the voice-message bubble is fed from here.
 */
internal class TvTranscriptSink(private val onVoiceMessageSent: () -> Unit) : TranscriptSink {
    override fun userTranscript(text: String, final: Boolean) = Unit
    override fun assistantTranscript(text: String, final: Boolean) = Unit
    override fun system(text: String) { Log.i("ArchieTv/Voice", text) }
    override fun voiceMessageSent() = onVoiceMessageSent()
    override fun turnComplete() = Unit
    override fun voiceEnded() = Unit
}
