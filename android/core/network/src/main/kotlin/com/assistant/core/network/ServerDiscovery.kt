package com.assistant.core.network

import com.assistant.core.model.AuthStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** One backend found on the LAN. [needsTrust] = TLS port whose certificate the user must confirm (TOFU). */
data class DiscoveredServer(val host: String, val port: Int, val secure: Boolean, val needsTrust: Boolean = false) {
    /** Stored server URL form (`ws://host:port`, or `wss://` for TLS). */
    val serverUrl: String get() = "${if (secure) "wss" else "ws"}://$host:$port"
}

/**
 * LAN backend discovery (spec 14 §1.2): TCP probe of `.2–.254` on 80/8765/443, then a JSON probe
 * of `GET /api/auth/status`. DISC-1 (fixes inv03 §8 bug 15): a host is a backend only if that call
 * returns a JSON object with boolean `authenticated` and `headless` — a router or printer with
 * port 80 open is rejected.
 */
class ServerDiscovery(
    private val stack: HttpStack,
    private val ports: List<Int> = NetworkTuning.DISCOVERY_PORTS,
    private val connectTimeoutMs: Int = NetworkTuning.DISCOVERY_CONNECT_TIMEOUT_MS,
    private val parallelism: Int = 48,
    private val tcpProbe: (host: String, port: Int, timeoutMs: Int) -> Boolean = ::defaultTcpProbe,
) {
    /** Scans `<subnet>.2 … <subnet>.254` (subnet = first three octets). Sorted by last octet. */
    suspend fun scan(subnet: String): List<DiscoveredServer> =
        scanHosts((2..254).map { "$subnet.$it" })
            .sortedBy { it.host.substringAfterLast('.').toIntOrNull() ?: 0 }

    /** Probes an explicit host list (at most one result per host; first matching port wins). */
    suspend fun scanHosts(hosts: List<String>): List<DiscoveredServer> = coroutineScope {
        val gate = Semaphore(parallelism)
        hosts.map { h -> async(Dispatchers.IO) { gate.withPermit { probeHost(h) } } }.awaitAll().filterNotNull()
    }

    suspend fun probeHost(host: String): DiscoveredServer? {
        for (port in ports) {
            if (!withContext(Dispatchers.IO) { tcpProbe(host, port, connectTimeoutMs) }) continue
            probeBackend(host, port)?.let { return it }
        }
        return null
    }

    /** The DISC-1 JSON probe on one open port. */
    suspend fun probeBackend(host: String, port: Int): DiscoveredServer? {
        val secure = port == 443
        val serverUrl = "${if (secure) "https" else "http"}://$host:$port"
        val client = stack.restClient(serverUrl).newBuilder()
            .connectTimeout(connectTimeoutMs.toLong() * 3, TimeUnit.MILLISECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url("$serverUrl/api/auth/status").get().build()
        return try {
            client.newCall(req).await().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && parseAuthStatus(body) != null) DiscoveredServer(host, port, secure) else null
            }
        } catch (e: IOException) {
            if (secure && e.untrustedCertificate() != null) DiscoveredServer(host, port, secure = true, needsTrust = true) else null
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** DISC-1: a JSON object with **boolean** `authenticated` and `headless`, else `null`. */
        fun parseAuthStatus(body: String): AuthStatus? {
            val o = try {
                json.parseToJsonElement(body) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null
            val auth = (o["authenticated"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
            val headless = (o["headless"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
            val url = (o["auth_url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return AuthStatus(auth, url, headless)
        }

        fun defaultTcpProbe(host: String, port: Int, timeoutMs: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
        } catch (_: IOException) {
            false
        }
    }
}
