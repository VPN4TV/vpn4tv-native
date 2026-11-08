package com.vpn4tv.app.bg

import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.TunOptions
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.ktx.toIpPrefix
import com.vpn4tv.app.ktx.toList
import com.vpn4tv.app.ktx.unwrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class VPNService :
    VpnService(),
    PlatformInterfaceWrapper {
    companion object {
        private const val TAG = "VPNService"

        @Volatile
        private var running: java.lang.ref.WeakReference<VPNService>? = null

        /**
         * The app came to the front: always-on VPN may have been changed in
         * Android's settings meanwhile, and turning "block connections
         * without VPN" on or off starts nothing here. Blocking: a call to the
         * system, keep it off the main thread.
         */
        fun refreshAlwaysOn() {
            val service = running?.get() ?: return
            val now = service.alwaysOnState()
            if (now == BoxService.alwaysOn.value) return
            BoxService.alwaysOn.postValue(now)
            ServiceNotification.refresh()
        }
    }

    private val service = BoxService(this, this)

    override fun onCreate() {
        super.onCreate()
        running = java.lang.ref.WeakReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start command, the system's too: turning always-on VPN on in
        // Android's settings while this VPN runs starts it once more. A fresh
        // start learns it only once its tun is up (openTun).
        BoxService.alwaysOn.value = alwaysOnState()
        return service.onStartCommand()
    }

    override fun onBind(intent: Intent): IBinder {
        val binder = super.onBind(intent)
        if (binder != null) {
            return binder
        }
        return service.onBind()
    }

    override fun onDestroy() {
        if (running?.get() === this) running = null
        BoxService.alwaysOn.value = null
        service.onDestroy()
    }

    /**
     * Whether Android keeps this VPN on (always-on VPN) and blocks connections
     * without it (Android 10+; older versions cannot tell). Google's VPN guide:
     * the system starts and stops such a connection, so the app offers no
     * Disconnect for it. Android answers only for an established VPN (it asks
     * who owns the VPN network): false before the first tun is up.
     */
    fun alwaysOnState(): BoxService.AlwaysOnVpn? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            if (isAlwaysOn) BoxService.AlwaysOnVpn(lockdown = isLockdownEnabled) else null
        } catch (e: Exception) {
            Log.w(TAG, "always-on state unavailable: ${e.message}")
            null
        }
    }

    override fun onRevoke() {
        runBlocking {
            withContext(Dispatchers.Main) {
                service.onRevoke()
            }
        }
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    var systemProxyAvailable = false
    var systemProxyEnabled = false

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")

        val builder =
            Builder()
                .setSession("VPN4TV")
                .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (Settings.allowBypass) {
            builder.allowBypass()
        }

        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            val address = inet4Address.next()
            builder.addAddress(address.address().unwrap, address.prefix())
        }

        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val address = inet6Address.next()
            builder.addAddress(address.address().unwrap, address.prefix())
        }

        if (options.autoRoute) {
            val dnsServerAddress = options.dnsServerAddress
            while (dnsServerAddress.hasNext()) {
                builder.addDnsServer(dnsServerAddress.next())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val inet4RouteAddress = options.inet4RouteAddress
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        builder.addRoute(inet4RouteAddress.next().toIpPrefix())
                    }
                } else if (options.inet4Address.hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }

                val inet6RouteAddress = options.inet6RouteAddress
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) {
                        builder.addRoute(inet6RouteAddress.next().toIpPrefix())
                    }
                } else if (options.inet6Address.hasNext()) {
                    builder.addRoute("::", 0)
                }

                val inet4RouteExcludeAddress = options.inet4RouteExcludeAddress
                while (inet4RouteExcludeAddress.hasNext()) {
                    builder.excludeRoute(inet4RouteExcludeAddress.next().toIpPrefix())
                }

                val inet6RouteExcludeAddress = options.inet6RouteExcludeAddress
                while (inet6RouteExcludeAddress.hasNext()) {
                    builder.excludeRoute(inet6RouteExcludeAddress.next().toIpPrefix())
                }
            } else {
                val inet4RouteAddress = options.inet4RouteRange
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        val address = inet4RouteAddress.next()
                        builder.addRoute(address.address().unwrap, address.prefix())
                    }
                }

                val inet6RouteAddress = options.inet6RouteRange
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) {
                        val address = inet6RouteAddress.next()
                        builder.addRoute(address.address().unwrap, address.prefix())
                    }
                }
            }

            val includePackage = options.includePackage
            if (includePackage.hasNext()) {
                while (includePackage.hasNext()) {
                    try {
                        val nextPackage = includePackage.next()
                        builder.addAllowedApplication(nextPackage)
                        Log.d("VPNService", "addAllowedApplication: $nextPackage")
                    } catch (e: Exception) {
                        Log.e("VPNService", "addAllowedApplication failed", e)
                    }
                }
            }

            val excludePackage = options.excludePackage
            if (excludePackage.hasNext()) {
                while (excludePackage.hasNext()) {
                    try {
                        val nextPackage = excludePackage.next()
                        builder.addDisallowedApplication(nextPackage)
                        Log.d("VPNService", "addDisallowedApplication: $nextPackage")
                    } catch (e: Exception) {
                        Log.e("VPNService", "addDisallowedApplication failed", e)
                    }
                }
            }
        }

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemProxyAvailable = true
            systemProxyEnabled = Settings.systemProxyEnabled
            if (systemProxyEnabled) {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy(
                        options.httpProxyServer,
                        options.httpProxyServerPort,
                        options.httpProxyBypassDomain.toList(),
                    ),
                )
            }
        } else {
            systemProxyAvailable = false
            systemProxyEnabled = false
        }

        val pfd =
            builder.establish() ?: error("android: the application is not prepared or is revoked")
        // A reload establishes a new tun. libbox works on its own dup, so the
        // previous descriptor is only ours and would keep the old interface
        // alive until GC.
        service.fileDescriptor?.close()
        service.fileDescriptor = pfd
        BoxService.alwaysOn.postValue(alwaysOnState())
        return pfd.fd
    }

    override fun sendNotification(notification: Notification) = service.sendNotification(notification)
}
