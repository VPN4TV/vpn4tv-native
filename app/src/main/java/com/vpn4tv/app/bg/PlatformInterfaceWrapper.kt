package com.vpn4tv.app.bg

import android.annotation.SuppressLint
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import com.vpn4tv.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

interface PlatformInterfaceWrapper : PlatformInterface {
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
    }

    override fun openTun(options: TunOptions): Int {
        error("invalid argument")
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        try {
            val uid =
                Application.connectivity.getConnectionOwnerUid(
                    ipProtocol,
                    InetSocketAddress(sourceAddress, sourcePort),
                    InetSocketAddress(destinationAddress, destinationPort),
                )
            if (uid == Process.INVALID_UID) error("android: connection owner not found")
            val packages = Application.packageManager.getPackagesForUid(uid)
            val owner = ConnectionOwner()
            owner.userId = uid
            owner.userName = packages?.firstOrNull() ?: ""
            owner.setAndroidPackageNames(StringArray(packages?.toList()?.iterator() ?: emptyList<String>().iterator()))
            return owner
        } catch (e: Exception) {
            Log.e("PlatformInterface", "getConnectionOwnerUid", e)
            e.printStackTrace(System.err)
            throw e
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.setListener(null)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val networks = Application.connectivity.allNetworks
        val networkInterfaces = NetworkInterface.getNetworkInterfaces().toList()
        val interfaces = mutableListOf<LibboxNetworkInterface>()
        for (network in networks) {
            val boxInterface = LibboxNetworkInterface()
            val linkProperties = Application.connectivity.getLinkProperties(network) ?: continue
            val networkCapabilities =
                Application.connectivity.getNetworkCapabilities(network) ?: continue
            boxInterface.name = linkProperties.interfaceName
            val networkInterface =
                networkInterfaces.find { it.name == boxInterface.name } ?: continue
            boxInterface.dnsServer =
                StringArray(linkProperties.dnsServers.mapNotNull { it.hostAddress }.iterator())
            boxInterface.type =
                when {
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
            boxInterface.index = networkInterface.index
            runCatching {
                boxInterface.mtu = networkInterface.mtu
            }.onFailure {
                Log.e(
                    "PlatformInterface",
                    "failed to get mtu for interface ${boxInterface.name}",
                    it,
                )
            }
            boxInterface.addresses =
                StringArray(
                    networkInterface.interfaceAddresses.mapTo(mutableListOf()) { it.toPrefix() }
                        .iterator(),
                )
            var dumpFlags = 0
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                dumpFlags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (networkInterface.isLoopback) {
                dumpFlags = dumpFlags or OsConstants.IFF_LOOPBACK
            }
            if (networkInterface.isPointToPoint) {
                dumpFlags = dumpFlags or OsConstants.IFF_POINTOPOINT
            }
            if (networkInterface.supportsMulticast()) {
                dumpFlags = dumpFlags or OsConstants.IFF_MULTICAST
            }
            boxInterface.flags = dumpFlags
            boxInterface.metered =
                !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            interfaces.add(boxInterface)
        }
        return InterfaceArray(interfaces.iterator())
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() {
    }

    override fun readWIFIState(): WIFIState? {
        @Suppress("DEPRECATION")
        val wifiInfo =
            Application.wifiManager.connectionInfo ?: return null
        var ssid = wifiInfo.ssid
        if (ssid == "<unknown ssid>") {
            return WIFIState("", "")
        }
        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length - 1)
        }
        return WIFIState(ssid, wifiInfo.bssid)
    }

    override fun localDNSTransport(): LocalDNSTransport? = LocalResolver

    // New PlatformInterface methods in libbox 1.14 — default no-op implementations.
    // We don't use neighbor monitoring or interface naming.
    override fun registerMyInterface(name: String?) {}
    override fun startNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener?) {}
    override fun closeNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener?) {}

    // libbox 1.14 grew a root-shell, SFTP and bridge surface for sing-box's
    // own SSH/Tailscale features. VPN4TV has no root client and none of those
    // features; answer like the core's own stub does (config.go), so the core
    // never tries to use them.
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() {}
    override fun openShellSession(
        user: io.nekohasekai.libbox.PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): io.nekohasekai.libbox.ShellSession = throw UnsupportedOperationException("platform shell is not available")
    override fun lookupUser(username: String?): io.nekohasekai.libbox.PlatformUser =
        throw UnsupportedOperationException("user lookup is not available")
    override fun lookupSFTPServer(): String = throw UnsupportedOperationException("SFTP server is not available")
    override fun readSystemSSHHostKey(): String = throw UnsupportedOperationException("SSH host key is not available")
    override fun tailscaleHostname(): String = ""
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: io.nekohasekai.libbox.BridgeOptions?): io.nekohasekai.libbox.BridgeSession =
        throw UnsupportedOperationException("platform bridge is not available")

    // systemCertificates() is gone from the interface: the core now reads the
    // system roots itself through Go's x509.SystemCertPool, which is exactly
    // what our empty override used to force (enumerating AndroidCAStore hung
    // Box.New for 45 s on slow-flash TVs).

    private class InterfaceArray(private val iterator: Iterator<LibboxNetworkInterface>) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): LibboxNetworkInterface = iterator.next()
    }

    class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int {
            // not used by core
            return 0
        }

        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): String = iterator.next()
    }

    private fun InterfaceAddress.toPrefix(): String = if (address is Inet6Address) {
        "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
    } else {
        "${address.hostAddress}/$networkPrefixLength"
    }

    private val NetworkInterface.flags: Int
        @SuppressLint("SoonBlockedPrivateApi")
        get() {
            val getFlagsMethod = NetworkInterface::class.java.getDeclaredMethod("getFlags")
            return getFlagsMethod.invoke(this) as Int
        }
}
