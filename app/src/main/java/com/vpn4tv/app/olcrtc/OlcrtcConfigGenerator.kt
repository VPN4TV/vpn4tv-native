package com.vpn4tv.app.olcrtc

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the JSON config consumed by [OlcrtcBridge.start].
 *
 * Format expected by libbox.StartOlcrtcBridge:
 *   { "endpoints": [ { "url": "olcrtc://...", "port": 30000 }, ... ] }
 *
 * Each endpoint becomes its own SOCKS5 inbound on 127.0.0.127:<port>, in the
 * bucket after wireproxy's so the bridges never collide on one install.
 */
object OlcrtcConfigGenerator {

    /** Offset added to xrayPortBase; xray = +0, outline = +1000, wireproxy = +2000. */
    const val OLCRTC_PORT_OFFSET = 3000

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
