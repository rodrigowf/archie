package com.assistant.core.network

import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * One `OkHttpClient` (shared dispatcher + connection pool + optional disk cache) for the process.
 * Per-server clients are cheap `newBuilder()` derivations that add TLS for pinned hosts
 * (spec 14 §1.2). Replaces the old per-connect `OkHttpClient` (inv03 §3.3).
 */
class HttpStack(
    private val trustStore: TrustStore? = null,
    cacheDir: File? = null,
    cacheBytes: Long = 10L * 1024 * 1024,
    base: OkHttpClient? = null,
) {
    val base: OkHttpClient = (base?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(NetworkTuning.REST_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(NetworkTuning.REST_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .apply { if (cacheDir != null) cache(Cache(cacheDir, cacheBytes)) }
        .build()

    private val rest = ConcurrentHashMap<String, OkHttpClient>()
    private val ws = ConcurrentHashMap<String, OkHttpClient>()

    /** REST client for [serverUrl] (any scheme). */
    fun restClient(serverUrl: String): OkHttpClient {
        val key = UrlScheme.httpBase(serverUrl)
        return rest.getOrPut(key) { withTls(base.newBuilder(), serverUrl).build() }
    }

    /**
     * WebSocket client: protocol ping every [NetworkTuning.WS_PING_INTERVAL_MS] (T-4; OkHttp closes
     * with 1011 when a pong is missing), no read timeout (inv03 §3.3).
     */
    fun wsClient(serverUrl: String): OkHttpClient {
        val key = UrlScheme.wsBase(serverUrl)
        return ws.getOrPut(key) {
            withTls(base.newBuilder(), serverUrl)
                .pingInterval(NetworkTuning.WS_PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }
    }

    /** Drop derived clients (after a pin change, so the next call picks up the new trust). */
    fun invalidate() {
        rest.clear(); ws.clear()
    }

    private fun withTls(b: OkHttpClient.Builder, serverUrl: String): OkHttpClient.Builder {
        val ts = trustStore ?: return b
        if (!UrlScheme.isSecure(serverUrl)) return b
        val tls = ts.tlsFor(UrlScheme.hostPort(serverUrl))
        return b.sslSocketFactory(tls.socketFactory, tls.trustManager).hostnameVerifier(tls.hostnameVerifier)
    }
}
