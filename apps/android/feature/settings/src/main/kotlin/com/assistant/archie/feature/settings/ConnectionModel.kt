package com.assistant.archie.feature.settings

import com.assistant.core.data.ConnectionRepository
import com.assistant.core.data.ConnectionStatus
import com.assistant.core.model.DeviceSettings
import com.assistant.core.network.ApiResult
import com.assistant.core.network.CertificateInfo
import com.assistant.core.network.DiscoveredServer
import com.assistant.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of Settings → Connection → Servers. */
data class ServerRow(
    val url: String,
    val label: String,
    /** Second line: URL, plus "found on this network" for discovered rows. */
    val detail: String,
    val saved: Boolean,
    val current: Boolean,
    val discovered: Boolean,
    val needsTrust: Boolean = false,
)

/** A pending TOFU decision (spec 14 §4.3): the server's certificate is not trusted yet. */
data class TrustRequest(val url: String, val label: String, val hostPort: String, val certificate: CertificateInfo, val changed: Boolean)

data class ConnectionUiState(
    val status: ConnectionStatus = ConnectionStatus(),
    val settings: DeviceSettings? = null,
    val rows: List<ServerRow> = emptyList(),
    val trust: TrustRequest? = null,
    /** A switch is being probed (secure server). */
    val switching: String? = null,
)

/**
 * Settings → Connection (IA §7). Fixes two old-app bugs:
 * - "No servers yet" while connected (inv03 §1.6): the current server is always listed, saved or not.
 * - Selecting a server only changed the URL and nothing reconnected (inv03 §8 bug 3): selecting a
 *   row switches **and connects** ([ConnectionControl.changeServer], T-15).
 * Secure servers are probed first; an untrusted certificate opens the trust dialog (TOFU).
 */
class ConnectionModel(
    private val control: ConnectionControl,
    private val store: SettingsStore,
    private val messages: SettingsMessages,
    private val scope: CoroutineScope,
) {
    private val local = MutableStateFlow(ConnectionUiState())

    val state: StateFlow<ConnectionUiState> = combine(control.status, store.settings, local) { st, s, l ->
        l.copy(status = st, settings = s, rows = rows(st, s))
    }.stateIn(scope, SharingStarted.Eagerly, ConnectionUiState())

    fun scan() = control.scan()
    fun connect() = control.connect()
    fun disconnect() = control.disconnect()

    /** Tap on a row: the current server reconnects if offline; another server is switched to. */
    fun select(row: ServerRow) {
        if (row.current) {
            if (control.status.value.phase == ConnectionStatus.Phase.OFFLINE) control.connect()
            return
        }
        scope.launch { switchTo(row.url, row.label) }
    }

    suspend fun switchTo(url: String, label: String = hostLabel(url)) {
        if (isSecure(url)) {
            local.value = local.value.copy(switching = url)
            val r = control.probe(url)
            local.value = local.value.copy(switching = null)
            if (r is ApiResult.Untrusted) {
                val e = r.error
                local.value = local.value.copy(trust = TrustRequest(url, label, e.hostPort, e.certificate, e.changed))
                return
            }
        }
        control.changeServer(url)
        messages.post(SettingsMessage("Connecting to $label…"))
    }

    fun trustAndSwitch() {
        val t = local.value.trust ?: return
        local.value = local.value.copy(trust = null)
        scope.launch {
            control.trust(t.hostPort, t.certificate)
            control.changeServer(t.url)
            messages.post(SettingsMessage("Connecting to ${t.label}…"))
        }
    }

    fun cancelTrust() { local.value = local.value.copy(trust = null) }

    /** Add (or replace, same URL) a saved server. Returns an error for the URL field, or null. */
    fun save(label: String, url: String, replacing: String? = null): String? {
        val normalized = normalizeServerUrl(url) ?: return "Enter a ws://, wss://, http:// or https:// address"
        val name = label.trim().ifEmpty { hostLabel(normalized) }
        val wasCurrent = replacing != null && replacing == store.settings.value?.serverUrl
        scope.launch {
            if (replacing != null && replacing != normalized) store.removeSavedServer(replacing)
            store.addSavedServer(name, normalized)
            messages.saved()
            if (wasCurrent && replacing != normalized) switchTo(normalized, name)
        }
        return null
    }

    fun saveDiscovered(d: DiscoveredServer) {
        scope.launch { store.addSavedServer(d.host, d.serverUrl); messages.saved() }
    }

    fun remove(row: ServerRow) {
        scope.launch {
            store.removeSavedServer(row.url)
            messages.post(SettingsMessage("Removed ${row.label}", actionLabel = "Undo", retry = {
                scope.launch { store.addSavedServer(row.label, row.url) }
            }))
        }
    }

    companion object {
        fun isSecure(url: String) = url.startsWith("wss://") || url.startsWith("https://")

        fun hostLabel(url: String) = ConnectionRepository.hostOf(url)

        /** `host[:port]` without a scheme becomes `ws://…`; anything other than ws/wss/http/https is refused. */
        fun normalizeServerUrl(raw: String): String? {
            val t = raw.trim()
            if (t.isEmpty() || t.any { it.isWhitespace() }) return null
            val withScheme = if ("://" in t) t else "ws://$t"
            val scheme = withScheme.substringBefore("://").lowercase()
            if (scheme !in setOf("ws", "wss", "http", "https")) return null
            val rest = withScheme.substringAfter("://").trimEnd('/')
            val host = rest.substringBefore('/').substringBefore(':')
            if (host.isEmpty()) return null
            return "$scheme://$rest"
        }

        /**
         * Current server first (saved or not), then saved servers, then discovered ones not saved yet.
         * A discovered server matching a saved one marks that row instead of adding a duplicate.
         */
        fun rows(st: ConnectionStatus, s: DeviceSettings?): List<ServerRow> {
            if (s == null) return emptyList()
            val current = s.serverUrl
            val found = st.discovered.associateBy { it.serverUrl }
            val out = ArrayList<ServerRow>()
            fun add(url: String, label: String?, saved: Boolean) {
                if (out.any { it.url == url }) return
                val d = found[url]
                out += ServerRow(
                    url = url,
                    label = label ?: hostLabel(url),
                    detail = if (d != null && !saved) "$url · found on this network" else url,
                    saved = saved,
                    current = url == current,
                    discovered = d != null,
                    needsTrust = d?.needsTrust == true,
                )
            }
            add(current, s.savedServers.firstOrNull { it.url == current }?.label, s.savedServers.any { it.url == current })
            s.savedServers.forEach { add(it.url, it.label, true) }
            st.discovered.forEach { add(it.serverUrl, it.host, false) }
            return out
        }
    }
}

/**
 * The production [ConnectionControl]: `ConnectionRepository` (T-15 switch-and-connect, scan), the
 * orchestrator channel (disconnect), a one-off REST probe of the candidate URL, and the TOFU pin
 * store (pins are read per handshake, so trusting takes effect on the next connect).
 */
class RepositoryConnectionControl(
    private val repository: ConnectionRepository,
    private val orchestrator: com.assistant.core.session.OrchestratorChannel,
    private val http: com.assistant.core.network.HttpStack,
    private val store: SettingsStore,
) : ConnectionControl {
    override val status: StateFlow<ConnectionStatus> get() = repository.status
    override suspend fun changeServer(url: String) = repository.changeServer(url)
    override fun connect() = repository.connect()
    override fun disconnect() = orchestrator.disconnect()
    override fun scan() = repository.scan()
    override suspend fun probe(url: String): ApiResult<*> = com.assistant.core.network.ArchieApi(http) { url }.authStatus()
    override suspend fun trust(hostPort: String, certificate: CertificateInfo) {
        store.savePin(hostPort, certificate.spkiSha256)
        http.invalidate()
    }
}
