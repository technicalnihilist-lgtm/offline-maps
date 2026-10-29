package com.offlinemaps.core.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reports connectivity using [ConnectivityManager]. This is what lets the app switch to downloaded
 * data the moment the network drops, instead of failing on the next request.
 */
class AndroidNetworkStateProvider(context: Context) : NetworkStateProvider {

    private val manager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _status = MutableStateFlow(readCurrent())
    override val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    init {
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            manager?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _status.value = readCurrent() }
                override fun onLost(network: Network) { _status.value = readCurrent() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _status.value = NetworkStatus(
                        online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    )
                }
            })
        }
    }

    fun refresh() { _status.value = readCurrent() }

    private fun readCurrent(): NetworkStatus {
        val cm = manager ?: return NetworkStatus.UNKNOWN
        val active = cm.activeNetwork ?: return NetworkStatus(online = false, unmetered = false)
        val caps = cm.getNetworkCapabilities(active) ?: return NetworkStatus(online = false, unmetered = false)
        return NetworkStatus(
            online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }
}
