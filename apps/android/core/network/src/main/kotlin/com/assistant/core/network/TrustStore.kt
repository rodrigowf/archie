package com.assistant.core.network

import android.annotation.SuppressLint
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** Trust mode of one server (spec 14 §4.3). */
enum class TrustMode { CLEARTEXT, SYSTEM_TRUSTED, PINNED_SELF_SIGNED }

/** Persisted pins, keyed by `host:port` ([UrlScheme.hostPort]). Backed by `SettingsStore` in the apps. */
interface PinStore {
    /** SHA-256 SPKI fingerprint (base64) pinned for [hostPort], or `null`. */
    fun pinFor(hostPort: String): String?
    fun savePin(hostPort: String, spkiSha256: String)
    fun removePin(hostPort: String)
}

/** In-memory [PinStore] (tests, previews). */
class MemoryPinStore(initial: Map<String, String> = emptyMap()) : PinStore {
    private val pins = java.util.concurrent.ConcurrentHashMap(initial)
    override fun pinFor(hostPort: String): String? = pins[hostPort]
    override fun savePin(hostPort: String, spkiSha256: String) { pins[hostPort] = spkiSha256 }
    override fun removePin(hostPort: String) { pins.remove(hostPort) }
}

/** What `ServerTrustDialog` shows before the user trusts a certificate (TOFU). */
data class CertificateInfo(
    val subject: String,
    val issuer: String,
    val notBeforeMillis: Long,
    val notAfterMillis: Long,
    /** base64 SHA-256 of the SubjectPublicKeyInfo (same form as OkHttp's `sha256/…` pins). */
    val spkiSha256: String,
) {
    /** `AB:CD:…` hex form for display. */
    val spkiSha256Hex: String
        get() = (spkiSha256.decodeBase64()?.hex() ?: "").uppercase().chunked(2).joinToString(":")

    companion object {
        fun of(cert: X509Certificate) = CertificateInfo(
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            notBeforeMillis = cert.notBefore.time,
            notAfterMillis = cert.notAfter.time,
            spkiSha256 = spkiSha256(cert),
        )

        fun spkiSha256(cert: X509Certificate): String =
            cert.publicKey.encoded.toByteString().sha256().base64()
    }
}

/**
 * The server presented a certificate that the system does not trust and that matches no pin.
 * [changed] = a different certificate was pinned for this host: never proceed silently, show
 * "Review new certificate" (spec 14 §4.3).
 */
class UntrustedServerCertificateException(
    val hostPort: String,
    val certificate: CertificateInfo,
    val changed: Boolean,
) : CertificateException("Untrusted certificate for $hostPort (${certificate.subject})")

/** Finds an [UntrustedServerCertificateException] anywhere in a cause chain (OkHttp wraps it). */
fun Throwable.untrustedCertificate(): UntrustedServerCertificateException? {
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth < 16) {
        if (t is UntrustedServerCertificateException) return t
        t.suppressed.firstNotNullOfOrNull { it.untrustedCertificate() }?.let { return it }
        t = t.cause; depth++
    }
    return null
}

/**
 * TOFU trust for `https`/`wss` servers (spec 14 §4.3, decision E-6: cleartext stays the default).
 *
 * Composite [X509TrustManager]: system trust first, then an exact SPKI pin match for that host.
 * The hostname check accepts a pinned leaf too (self-signed certs often lack an IP SAN).
 * The flow: a connect fails with [UntrustedServerCertificateException] → the UI shows
 * [CertificateInfo] → the user confirms → [trust] stores the pin → reconnect succeeds.
 */
class TrustStore(
    private val pins: PinStore,
    private val system: X509TrustManager = systemTrustManager(),
) {
    fun modeFor(serverUrl: String): TrustMode = when {
        !UrlScheme.isSecure(serverUrl) -> TrustMode.CLEARTEXT
        pins.pinFor(UrlScheme.hostPort(serverUrl)) != null -> TrustMode.PINNED_SELF_SIGNED
        else -> TrustMode.SYSTEM_TRUSTED
    }

    /** Pin [certificate] for [hostPort] after the user confirmed it. Replaces an older pin (re-pin). */
    fun trust(hostPort: String, certificate: CertificateInfo) = pins.savePin(hostPort, certificate.spkiSha256)

    fun forget(hostPort: String) = pins.removePin(hostPort)

    /** Trust manager for one `host:port`. */
    fun trustManager(hostPort: String): X509TrustManager = PinningTrustManager(hostPort)

    /** Socket factory + trust manager + hostname verifier for one `host:port`. */
    fun tlsFor(hostPort: String): Tls {
        val tm = trustManager(hostPort)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val verifier = HostnameVerifier { host, session ->
            if (defaultVerifier.verify(host, session)) return@HostnameVerifier true
            val pin = pins.pinFor(hostPort) ?: return@HostnameVerifier false
            val leaf = try {
                session.peerCertificates.firstOrNull() as? X509Certificate
            } catch (_: SSLPeerUnverifiedException) {
                null
            } ?: return@HostnameVerifier false
            CertificateInfo.spkiSha256(leaf) == pin
        }
        return Tls(ctx.socketFactory, tm, verifier)
    }

    class Tls(val socketFactory: SSLSocketFactory, val trustManager: X509TrustManager, val hostnameVerifier: HostnameVerifier)

    // Not a trust-all: system validation runs first; the only fallback is an exact SPKI pin the
    // user confirmed for this host:port (spec 14 §4.3). Covered by TrustStoreTest.
    @SuppressLint("CustomX509TrustManager")
    private inner class PinningTrustManager(private val hostPort: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
                return
            } catch (_: CertificateException) {
                // fall through to the pin
            }
            val leaf = chain.firstOrNull() ?: throw CertificateException("empty chain")
            val info = CertificateInfo.of(leaf)
            val pin = pins.pinFor(hostPort)
            if (pin != null && pin == info.spkiSha256) {
                leaf.checkValidity()
                return
            }
            throw UntrustedServerCertificateException(hostPort, info, changed = pin != null)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    companion object {
        fun systemTrustManager(): X509TrustManager {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
