package com.vpn4tv.app.trusttunnel

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the sidecar consumed by [TrustTunnelBridge.start].
 *
 * Format:
 *   { "endpoints": [ { "url": "tt://?...", "port": 30000 }, ... ] }
 *
 * Each link becomes its own TrustTunnel client with a SOCKS5 listener on
 * 127.0.0.127:<port>, in the bucket after olcrtc's so the bridges never
 * collide on one install. The link itself is decoded on the device by the
 * TrustTunnel library (DeepLink.decode), not here.
 */
object TtConfigGenerator {

    /** Offset added to xrayPortBase; xray = +0, outline = +1000, wireproxy = +2000, olcrtc = +3000. */
    const val TT_PORT_OFFSET = 4000

    fun build(urls: List<String>, firstPort: Int): String {
        val endpoints = JSONArray()
        urls.forEachIndexed { i, url ->
            endpoints.put(JSONObject().apply {
                put("url", url)
                put("port", firstPort + i)
            })
        }
        return JSONObject().apply { put("endpoints", endpoints) }.toString()
    }
}
