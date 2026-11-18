package net.ts.taild80ca0.atlas.twa.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Tells the foreground cycle that the device has a network, so a lost connection is not waited out by the
 * reconnect backoff. It exists only while the app is resumed: no background service and no polling.
 */
interface NetworkWatcher {
    /** Starts watching the default network: [onAvailable] runs whenever one becomes the default. [stop] unregisters it. */
    fun start(onAvailable: () -> Unit)
    fun stop()
}

/** The platform default network while the app is in the foreground. */
class AndroidNetworkWatcher(context: Context) : NetworkWatcher {
    private val manager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized override fun start(onAvailable: () -> Unit) {
        val manager = manager ?: return
        if (callback != null) return
        val watcher = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onAvailable()
        }
        try {
            // Delivers the network already in place too: the snapshot read it asks for is shared with the one
            // the opened subscription does, so starting costs one read, never two.
            manager.registerDefaultNetworkCallback(watcher)
            callback = watcher
        } catch (_: SecurityException) {
            // Without ACCESS_NETWORK_STATE the cycle still recovers, only through its own backoff.
        }
    }

    @Synchronized override fun stop() {
        val watcher = callback ?: return
        callback = null
        try {
            manager?.unregisterNetworkCallback(watcher)
        } catch (_: IllegalArgumentException) {
            // Already unregistered by the platform (or never registered): nothing to undo.
        }
    }
}
