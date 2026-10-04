package com.assistant.peripheral

import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.assistant.core.model.DeviceSettings
import com.assistant.core.network.DiscoveredServer
import com.assistant.core.network.HttpStack
import com.assistant.core.network.NetworkMonitor
import com.assistant.core.network.ServerDiscovery
import com.assistant.core.network.TrustStore
import com.assistant.core.network.UrlScheme
import com.assistant.core.session.SettingsPinStore
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.settings.SettingsStore
import com.assistant.core.voicehost.AndroidVoiceHost
import com.assistant.core.voicehost.HostConfig
import com.assistant.core.voicehost.runtime.VoiceHostRuntime
import com.assistant.peripheral.face.RetryEstimator
import com.assistant.peripheral.settings.LiteSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The lite app's whole object graph (spec 14 §5.4): SettingsStore, HttpStack, the voice host
 * runtime with `HostConfig.lite`, the lite [LastExchangeSink]. Nothing from `:core:conversation`,
 * `:core:data` or any Compose module. Built once per process by [LiteApplication].
 */
class LiteGraph(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings: SettingsStore = SettingsStore.create(app)
    val http = HttpStack(
        trustStore = TrustStore(SettingsPinStore(settings, scope)),
        base = DebugHooks.okHttpBase(),
    )
    val lastExchange = LastExchangeSink()

    /** The process-scoped voice host; the runtime owns the orchestrator channel (autoStart). */
    val voiceHost: VoiceHostRuntime by lazy {
        AndroidVoiceHost.create(app, settings, http, HostConfig.lite(MainActivity::class.java), lastExchange)
    }

    val liteSettings: LiteSettings by lazy { LiteSettings(scope, settings, voiceHost) }

    private val network = NetworkMonitor(app)
    private val discovery = ServerDiscovery(http)
    private val _discovered = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discovered: StateFlow<List<DiscoveredServer>> = _discovered.asStateFlow()
    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    /** Next-retry estimate for the Offline face (fed from the channel's socket state). */
    val retry = RetryEstimator()

    data class ScanState(val running: Boolean = false, val message: String? = null)

    /** Process start: connect lifecycle hooks the lite runtime does not own itself. */
    fun start() {
        val host = voiceHost
        // T-14: reconnect at once when a network becomes available.
        host.orchestrator.attachNetwork(network.available)
        scope.launch { host.orchestrator.state.collect { retry.onSocket(it.socket, SystemClock.elapsedRealtime()) } }
        // A session that got going clears a held Error face (only established phases: a failing
        // attempt passes through CONNECTING right before its error, so that must not clear it).
        scope.launch {
            host.state.collect { s ->
                when (s.session.phase) {
                    SessionPhase.ACTIVE, SessionPhase.SPEAKING, SessionPhase.THINKING, SessionPhase.TOOL_USE -> lastExchange.clearError()
                    else -> Unit
                }
            }
        }
    }

    fun scan() {
        if (_scan.value.running) return
        scope.launch {
            val subnets = runCatching { network.localSubnets() }.getOrDefault(emptyList())
            val subnet = subnets.firstOrNull()
            if (subnet == null) {
                _scan.value = ScanState(false, "No Wi-Fi network to scan")
                return@launch
            }
            _scan.value = ScanState(true, "Scanning $subnet.x …")
            val found = runCatching { discovery.scan(subnet) }.getOrElse {
                Log.w(TAG, "scan failed: ${it.message}")
                emptyList()
            }
            _discovered.value = found
            _scan.value = ScanState(false, if (found.isEmpty()) "No Archie server found on $subnet.x" else "Found ${found.size} on $subnet.x")
        }
    }

    /** `onTrimMemory(RUNNING_LOW)` and above: drop what can be rebuilt (spec 14 §5.6). */
    fun trimMemory() {
        _discovered.value = emptyList()
        _scan.value = ScanState()
    }

    companion object {
        private const val TAG = "LiteGraph"

        /** "jetson" for ws://192.168.0.200:80 when a saved server carries that label, else the host. */
        fun serverName(s: DeviceSettings?): String {
            val url = s?.serverUrl ?: return "server"
            s.savedServers.firstOrNull { it.url == url }?.let { return it.label.lowercase() }
            return UrlScheme.hostPort(url).substringBeforeLast(':').ifEmpty { url }
        }
    }
}
