package net.ts.taild80ca0.atlas.twa.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Watches the default network only while the connected screen is resumed. */
class ForegroundNetworkSync(context: Context, private val sync: SyncController) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start() {
        if (callback != null) return
        val trigger = NetworkSyncTrigger(sync)
        val observer = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            override fun onAvailable(network: Network) = synchronized(this@ForegroundNetworkSync) {
                if (callback === this) current = network
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(this@ForegroundNetworkSync) {
                if (callback === this && current == network) {
                    trigger.connected(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                }
            }
            override fun onLost(network: Network) = synchronized(this@ForegroundNetworkSync) {
                if (callback === this && current == network) {
                    current = null
                    trigger.connected(false)
                }
            }
        }
        callback = observer
        manager.registerDefaultNetworkCallback(observer)
    }

    @Synchronized
    fun stop() {
        val observer = callback ?: return
        callback = null
        manager.unregisterNetworkCallback(observer)
    }
}

/** Ignore repeated capability notifications; the initial notification shares the resume throttle. */
internal class NetworkSyncTrigger(private val sync: SyncController) {
    private var previous: Boolean? = null
    fun connected(available: Boolean) {
        val before = previous
        previous = available
        if (!available || before == true) return
        if (before == null) sync.request(manual = false) else sync.changed()
    }
}
