package com.assistant.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address

/**
 * T-14: "reconnect immediately when ConnectivityManager reports a network becoming available".
 * Emits once per `onAvailable`. API 21 (`registerNetworkCallback(NetworkRequest, …)`).
 */
class NetworkMonitor(context: Context) {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val available: Flow<Unit> = callbackFlow {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(request, cb)
        awaitClose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }

    /**
     * IPv4 /24 prefixes ("192.168.0") of the connected networks, Wi-Fi/Ethernet first. Replaces the
     * deprecated `WifiManager.connectionInfo` lookup that returns 0 off-Wi-Fi (inv03 §3.2).
     */
    fun localSubnets(): List<String> {
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        return networks
            .sortedBy { n ->
                val caps = cm.getNetworkCapabilities(n)
                if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) 0 else 1
            }
            .mapNotNull { n -> cm.getLinkProperties(n)?.let(::ipv4Prefix) }
            .distinct()
    }

    private fun ipv4Prefix(lp: LinkProperties): String? = lp.linkAddresses
        .map { it.address }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress?.substringBeforeLast('.')
}
