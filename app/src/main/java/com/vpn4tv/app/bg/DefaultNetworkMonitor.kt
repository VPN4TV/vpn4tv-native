package com.vpn4tv.app.bg

import android.net.Network
import android.os.Build
import io.nekohasekai.libbox.InterfaceUpdateListener
import com.vpn4tv.app.Application
import java.net.NetworkInterface

object DefaultNetworkMonitor {

    var defaultNetwork: Network? = null
    private var listener: InterfaceUpdateListener? = null

    /**
     * The default interface the core was last told, its name and index; null:
     * none. Set before the core is told, since the core reads the device's
     * interfaces while it takes the update, and the line about those
     * (NetworkWatch.given) says whether this one is among them.
     */
    @Volatile
    var told: Pair<String, Int>? = null
        private set

    suspend fun start() {
        told = null
        DefaultNetworkListener.start(this) {
            defaultNetwork = it
            checkDefaultInterfaceUpdate(it)
        }
        defaultNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Application.connectivity.activeNetwork
        } else {
            DefaultNetworkListener.get()
        }
    }

    suspend fun stop() {
        DefaultNetworkListener.stop(this)
    }

    suspend fun require(): Network {
        val network = defaultNetwork
        if (network != null) {
            return network
        }
        return DefaultNetworkListener.get()
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        this.listener = listener
        checkDefaultInterfaceUpdate(defaultNetwork)
    }

    // Up to 10 tries for the interface to appear, and ONE update once it has: each
    // updateDefaultInterface makes the core re-read every interface through Java
    // (GetInterfaces: allNetworks, getLinkProperties, getNetworkCapabilities — binder
    // calls), so the old loop without the return did that ten times on every start and
    // every network change; on slow TVs the start sat there past the 45 s watchdog
    // (crash analyzer group 004423caff). Same as upstream SFA ea63fb0f8b + 6aaa84d043.
    private fun checkDefaultInterfaceUpdate(newNetwork: Network?) {
        val listener = listener ?: return
        if (newNetwork != null) {
            for (times in 0 until 10) {
                val linkProperties = Application.connectivity.getLinkProperties(newNetwork)
                if (linkProperties == null) {
                    Thread.sleep(100)
                    continue
                }
                var interfaceIndex: Int
                try {
                    interfaceIndex = NetworkInterface.getByName(linkProperties.interfaceName).index
                } catch (e: Exception) {
                    Thread.sleep(100)
                    continue
                }
                told = (linkProperties.interfaceName ?: "?") to interfaceIndex
                listener.updateDefaultInterface(linkProperties.interfaceName, interfaceIndex, false, false)
                // What the core was just told, for support: one line when it differs from the last one.
                NetworkWatch.told(newNetwork, null, linkProperties, interfaceIndex)
                return
            }
            // Nothing was told: the core keeps the interface it had.
            NetworkWatch.told(null, "the new network's interface did not appear, the core was not told")
        } else {
            told = null
            listener.updateDefaultInterface("", -1, false, false)
            NetworkWatch.told(null, null)
        }
    }

    /** The network the core goes out by, as it is now: the line after a start or a reload. */
    fun lineNow(): Pair<String, Boolean> = NetworkReport.lineFor(defaultNetwork)
}
