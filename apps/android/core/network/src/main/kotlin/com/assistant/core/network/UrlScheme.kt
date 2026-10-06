package com.assistant.core.network

/** The two WebSocket endpoints (inv03 §3.1). */
enum class WsEndpoint(val path: String) {
    ORCHESTRATOR("/api/orchestrator/chat"),
    AGENT("/api/sessions/chat"),
}

/**
 * ws/http mapping of a stored server URL (inv03 §3.1). The stored URL may use any of
 * `ws`, `wss`, `http`, `https` and may carry a stale endpoint path; both mappings strip it.
 * A URL without a scheme is treated as `ws://`/`http://`.
 */
object UrlScheme {
    private val ENDPOINT_SUFFIXES = listOf("/api/orchestrator/chat", "/api/sessions/chat", "/api/orchestrator")

    /** `ws(s)://host[:port]/api/...` for [endpoint]. */
    fun wsUrl(serverUrl: String, endpoint: WsEndpoint): String = wsBase(serverUrl) + endpoint.path

    /** `ws(s)://host[:port]` with no trailing slash or endpoint path. */
    fun wsBase(serverUrl: String): String {
        val (scheme, rest) = split(serverUrl)
        val ws = when (scheme) {
            "https", "wss" -> "wss"
            else -> "ws"
        }
        return "$ws://${stripPath(rest)}"
    }

    /** `http(s)://host[:port]` for REST. */
    fun httpBase(serverUrl: String): String {
        val (scheme, rest) = split(serverUrl)
        val http = when (scheme) {
            "https", "wss" -> "https"
            else -> "http"
        }
        return "$http://${stripPath(rest)}"
    }

    /** True for `https`/`wss` URLs (TLS: [TrustStore] applies). */
    fun isSecure(serverUrl: String): Boolean = split(serverUrl).first.let { it == "https" || it == "wss" }

    /** `host:port` key for pins (default ports filled in). */
    fun hostPort(serverUrl: String): String {
        val secure = isSecure(serverUrl)
        val authority = stripPath(split(serverUrl).second).substringBefore('/')
        val hasPort = authority.startsWith("[").let { v6 ->
            if (v6) authority.substringAfter("]").startsWith(":") else authority.contains(':')
        }
        return if (hasPort) authority else "$authority:${if (secure) 443 else 80}"
    }

    private fun split(url: String): Pair<String, String> {
        val trimmed = url.trim()
        val idx = trimmed.indexOf("://")
        return if (idx < 0) "" to trimmed else trimmed.substring(0, idx).lowercase() to trimmed.substring(idx + 3)
    }

    private fun stripPath(rest: String): String {
        var r = rest.trimEnd('/')
        var changed = true
        while (changed) {
            changed = false
            for (s in ENDPOINT_SUFFIXES) {
                if (r.endsWith(s)) {
                    r = r.removeSuffix(s).trimEnd('/'); changed = true
                }
            }
        }
        return r
    }
}
