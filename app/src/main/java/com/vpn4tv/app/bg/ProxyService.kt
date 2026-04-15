package com.vpn4tv.app.bg

import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.nekohasekai.libbox.Notification

/**
 * Non-VPN host service for [ServiceMode.PROXY][com.vpn4tv.app.constant.ServiceMode.PROXY].
 *
 * Mirrors [VPNService] but extends plain [Service] instead of
 * [android.net.VpnService], so starting it does not trigger the Android
 * VPN consent dialog. This is the only way to run sing-box on devices
 * where the vendor (SberBox and similar) stripped the system VPN
 * permission flow — the user configures on-device apps with manual
 * proxy support (SmartTube etc.) to point at 127.0.0.1:12334 instead.
 *
 * Traffic plumbing: sing-box runs with a SOCKS5 inbound (rewritten in
 * [BoxService.startService] from the profile's TUN inbound) and dials
 * outbounds directly via the default network. No TUN, no fd protection,
 * no per-app proxy — those are meaningless without a tunnel.
 */
class ProxyService : Service(), PlatformInterfaceWrapper {

    private val service = BoxService(this, this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = service.onStartCommand()

    override fun onBind(intent: Intent): IBinder = service.onBind()

    override fun onDestroy() {
        service.onDestroy()
    }

    // PlatformInterfaceWrapper's default autoDetectInterfaceControl is a
    // no-op, which is exactly what we want: without a TUN there is no
    // loopback hazard, and outbound sockets can use the default network.

    override fun sendNotification(notification: Notification) = service.sendNotification(notification)
}
