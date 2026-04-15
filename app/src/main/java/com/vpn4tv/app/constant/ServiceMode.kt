package com.vpn4tv.app.constant

object ServiceMode {
    const val NORMAL = "normal"
    const val VPN = "vpn"
    /**
     * Local SOCKS5 mode for devices where the system VPN permission dialog
     * has been removed by the vendor (SberBox and similar). sing-box runs
     * without a TUN, exposing a SOCKS5 listener on 127.0.0.1:12334; on-device
     * apps with manual proxy settings (e.g. SmartTube) route through it.
     */
    const val PROXY = "proxy"

    /** Fixed loopback port SmartTube users already remember. */
    const val PROXY_PORT = 12334
}
