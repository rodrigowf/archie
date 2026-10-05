package com.assistant.core.network

import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
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
        .addNetworkInterceptor(NoHeuristicFreshness)
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

/**
 * The backend sends no `Cache-Control`, so OkHttp would compute a heuristic freshness from
 * `Last-Modified` (10 % of the file's age) and serve a memory document or the visuals list from
 * disk for hours, even on an explicit Reload. A GET response with no freshness headers is
 * rewritten to `Cache-Control: no-cache` before the cache stores it: it may still be cached, but
 * every use revalidates with the server (a conditional GET; a 304 reuses the stored body).
 * Responses that do carry `Cache-Control` / `Expires` keep the server's policy.
 */
internal object NoHeuristicFreshness : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // WebSocket upgrades and non-GETs pass through untouched.
        if (request.method != "GET" || request.header("Upgrade") != null) return chain.proceed(request)
        val response = chain.proceed(request)
        if (response.code == 101) return response
        if (response.header("Cache-Control") != null || response.header("Expires") != null) return response
        return response.newBuilder().header("Cache-Control", "no-cache").build()
    }
}
