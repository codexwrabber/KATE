package com.kate.assistant.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Watches network connectivity and notifies Kate when she should switch
 * between online (Deepgram + Claude) and offline (VOSK) modes.
 *
 * Deliberately simple — no retry logic here, just state observation.
 */
class KateNetworkMonitor(context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    var onOnline:  (() -> Unit)? = null
    var onOffline: (() -> Unit)? = null

    @Volatile var isOnline: Boolean = false
        private set

    companion object { private const val TAG = "KateNetworkMonitor" }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!isOnline) {
                isOnline = true
                Log.d(TAG, "Network available — switching to online mode")
                onOnline?.invoke()
            }
        }

        override fun onLost(network: Network) {
            isOnline = false
            Log.d(TAG, "Network lost — switching to offline mode")
            onOffline?.invoke()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            caps: NetworkCapabilities
        ) {
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                              caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (hasInternet && !isOnline) {
                isOnline = true
                onOnline?.invoke()
            } else if (!hasInternet && isOnline) {
                isOnline = false
                onOffline?.invoke()
            }
        }
    }

    fun start() {
        // Snapshot current state
        isOnline = isCurrentlyOnline()

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        Log.d(TAG, "Monitoring started — currently ${if (isOnline) "online" else "offline"}")
    }

    fun stop() {
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
    }

    private fun isCurrentlyOnline(): Boolean {
        val net  = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
