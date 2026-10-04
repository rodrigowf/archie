package com.assistant.peripheral

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.assistant.core.network.SocketState
import com.assistant.core.voicehost.VoiceUiEvent
import com.assistant.core.voicehost.VoiceUiState
import com.assistant.core.voicehost.ports.Trigger
import com.assistant.core.voicehost.service.VoiceHostService
import com.assistant.peripheral.face.FaceAction
import com.assistant.peripheral.face.FaceModel
import com.assistant.peripheral.face.FaceScreen
import com.assistant.peripheral.settings.LiteSettings
import com.assistant.peripheral.settings.OutputAvailability
import com.assistant.peripheral.settings.SettingsActions
import com.assistant.peripheral.settings.SettingsScreen
import com.assistant.peripheral.settings.SettingsViewState
import com.assistant.peripheral.ui.LitePalette
import com.assistant.peripheral.ui.dpi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The lite app's single Activity (spec 14 §5.1), at the FQCN the companion watchdog hard-codes.
 * A plain framework Activity with two content states, Face and Settings, swapped in one root
 * FrameLayout; Back from Settings returns to the Face. It renders `VoiceHost.state` and sends
 * commands — no receivers, no voice logic (voice runs in the process-scoped runtime).
 */
class MainActivity : Activity() {
    private val graph get() = (application as LiteApplication).graph
    private val host get() = graph.voiceHost

    private lateinit var palette: LitePalette
    private lateinit var face: FaceScreen
    private var settingsView: SettingsScreen? = null
    private lateinit var root: FrameLayout
    private lateinit var outputs: OutputAvailability
    private var uiScope: CoroutineScope? = null
    private var settingsJob: Job? = null
    private var keepOn = false
    private var outputsNow = OutputAvailability.EMPTY

    /** The face currently rendered (smoke tests). */
    val faceModel: FaceModel? get() = if (::face.isInitialized) face.model else null

    /** True while the Settings view is shown (smoke tests). */
    val showingSettings: Boolean get() = settingsView?.visibility == View.VISIBLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pre-O_MR1 lock-screen flags (inv04 §5.1); the manifest attributes cover API 27+.
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        palette = LitePalette(this)
        outputs = OutputAvailability(this)
        root = FrameLayout(this)
        face = FaceScreen(this, palette).apply {
            onShapeTap = ::onFaceAction
            onSettings = { showSettings(true) }
            onMute = { host.toggleMute() }
        }
        root.addView(face, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        requestPermissionsIfNeeded()
        handleIntent(intent, fresh = savedInstanceState == null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, fresh = true)
    }

    private fun handleIntent(intent: Intent?, fresh: Boolean) {
        if (!fresh || intent == null) return
        // Notification actions (Talk / Resume / Reconnect) arriving through the launch Activity.
        if (VoiceHostService.handleActivityIntent(this, intent)) return
        // Long-press home / assist gesture. A wake-word bring-to-front carries `wake_word_triggered`:
        // voice already started in the runtime, so nothing to do here (R2).
        if (intent.action == Intent.ACTION_ASSIST) {
            Log.d(TAG, "ACTION_ASSIST → realtime voice")
            host.startVoice(Trigger.ASSIST)
        }
    }

    override fun onStart() {
        super.onStart()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        uiScope = scope
        scope.launch {
            combine(host.state, host.orchestrator.state, graph.settings.settings, graph.lastExchange.exchange, graph.lastExchange.error) { s, ch, d, ex, _ ->
                Quad(s, ch.socket, d, ex)
            }.collect { render(it) }
        }
        // The offline countdown ticks once a second while a retry is scheduled.
        scope.launch {
            while (isActive) {
                delay(1_000)
                // Offline countdown, and the Error face expiring back to Ready.
                if (host.orchestrator.state.value.socket is SocketState.Disconnected || graph.lastExchange.error.value != null) render(current())
            }
        }
        scope.launch {
            host.events.collect { e -> if (e is VoiceUiEvent.Toast) Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_SHORT).show() }
        }
        if (showingSettings) startSettingsBinding()
    }

    override fun onStop() {
        uiScope?.cancel()
        uiScope = null
        settingsJob = null
        outputs.unwatch()
        super.onStop()
    }

    /** API ≤ 32 (the A300M): Back from Settings returns to the Face. */
    @Deprecated("Deprecated in Java")
    @Suppress("GestureBackNavigation")
    override fun onBackPressed() {
        if (showingSettings) showSettings(false) else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /** API 33+ (predictive back never calls onBackPressed): a callback only while Settings is shown. */
    private val backCallback: Any? by lazy {
        if (Build.VERSION.SDK_INT >= 33) android.window.OnBackInvokedCallback { showSettings(false) } else null
    }

    private fun setBackCallback(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val cb = backCallback as android.window.OnBackInvokedCallback
        if (enabled) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
    }

    // ── face ────────────────────────────────────────────────────────────────────────────────────

    private data class Quad(val s: VoiceUiState, val socket: SocketState, val d: com.assistant.core.model.DeviceSettings?, val ex: com.assistant.core.voicehost.LastExchange)

    private fun current() = Quad(host.state.value, host.orchestrator.state.value.socket, graph.settings.settings.value, graph.lastExchange.exchange.value)

    private fun render(q: Quad) {
        val d = q.d
        val model = FaceModel.from(
            s = q.s,
            socket = q.socket,
            serverName = LiteGraph.serverName(d),
            exchange = q.ex,
            wakePhrase = d?.wakeWord ?: "wake up",
            talkPhrase = d?.talkWord ?: "my friend",
            retryInMs = graph.retry.retryInMs(SystemClock.elapsedRealtime()),
            recentError = recentError(),
            autoConnect = d?.autoConnect ?: true,
        )
        if (model.word != face.model?.word) Log.i(TAG, "$FACE_MARKER ${model.word}${model.sub?.let { " — $it" }.orEmpty()}")
        face.bind(model)
        if (model.keepScreenOn != keepOn) {
            keepOn = model.keepScreenOn
            if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (showingSettings) bindSettings()
    }

    private fun recentError(): String? {
        val e = graph.lastExchange.error.value ?: return null
        return if (SystemClock.elapsedRealtime() - e.atMs < LastExchangeSink.ERROR_HOLD_MS) e.message else null
    }

    private fun onFaceAction(a: FaceAction) {
        when (a) {
            FaceAction.START -> {
                graph.lastExchange.clearError()
                host.startVoice(Trigger.BUTTON)
            }
            FaceAction.STOP -> host.stopVoice()
            FaceAction.RECONNECT_VOICE -> {
                graph.lastExchange.clearError()
                host.reconnectVoice()
            }
            FaceAction.CONNECT -> host.connect()
            FaceAction.NONE -> Unit
        }
    }

    // ── settings ────────────────────────────────────────────────────────────────────────────────

    private fun showSettings(show: Boolean) {
        if (show) {
            val v = settingsView ?: SettingsScreen(this, palette, actions).also {
                settingsView = it
                root.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            v.visibility = View.VISIBLE
            face.visibility = View.GONE
            setBackCallback(true)
            startSettingsBinding()
        } else {
            currentFocus?.clearFocus() // commits a phrase being edited (focus loss)
            if (settingsView?.visibility == View.VISIBLE) setBackCallback(false)
            settingsView?.visibility = View.GONE
            face.visibility = View.VISIBLE
            settingsJob?.cancel()
            settingsJob = null
            outputs.unwatch()
        }
    }

    private fun startSettingsBinding() {
        val scope = uiScope ?: return
        settingsJob?.cancel()
        outputs.watch { outputsNow = it; bindSettings() }
        settingsJob = scope.launch {
            combine(graph.discovered, graph.scan) { _, _ -> Unit }.collect { bindSettings() }
        }
    }

    @Suppress("DEPRECATION")
    private fun bindSettings() {
        val v = settingsView ?: return
        val d = graph.settings.settings.value ?: return
        val scan = graph.scan.value
        @Suppress("DEPRECATION")
        val pkg = packageManager.getPackageInfo(packageName, 0)
        v.bind(
            SettingsViewState(
                settings = d,
                connection = host.state.value.connection,
                serverName = LiteGraph.serverName(d),
                discovered = graph.discovered.value,
                scanning = scan.running,
                scanMessage = scan.message,
                outputs = outputsNow,
                accessibilityEnabled = accessibilityEnabled(),
                versionName = pkg.versionName ?: BuildConfig.VERSION_NAME,
                versionCode = pkg.versionCode,
            ),
        )
    }

    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.contains("$packageName/") && enabled.contains("ButtonAccessibilityService")
    }

    private val actions = object : SettingsActions {
        override val settings: LiteSettings get() = graph.liteSettings
        override fun back() = showSettings(false)
        override fun connect() = host.connect()
        override fun disconnect() = host.disconnect()
        override fun scan() = graph.scan()
        override fun selectServer(url: String) = settings.setServer(url)

        override fun addServer() {
            val label = EditText(this@MainActivity).apply { hint = "Name (e.g. Jetson)"; setSingleLine(true) }
            val url = EditText(this@MainActivity).apply {
                hint = "ws://192.168.0.200:80"
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                val p = dpi(20f)
                setPadding(p, p / 2, p, 0)
                addView(label)
                addView(url)
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Add server")
                .setView(box)
                .setPositiveButton("Add") { _, _ ->
                    val u = url.text.toString().trim()
                    settings.addServer(label.text.toString().trim(), u)
                    if (u.isNotEmpty()) settings.setServer(u)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        override fun removeServer(label: String, url: String) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Remove $label?")
                .setMessage(url)
                .setPositiveButton("Remove") { _, _ -> settings.removeServer(url) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        override fun openAccessibilitySettings() = launchSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        override fun openAssistSettings() = launchSettings(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }

    private fun launchSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: Exception) {
            Toast.makeText(this, "Not available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) return // install-time grants on Lollipop (the A300M)
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    companion object {
        private const val TAG = "LiteMain"

        /** Logcat marker of every face change (scripted device tests grep it, G-01). */
        const val FACE_MARKER = "[FACE]"
    }
}
