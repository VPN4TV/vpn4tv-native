package com.vpn4tv.app.wireproxy

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the JSON consumed by [WgBridge.start].
 *
 * Format expected by libbox.StartWireproxyBridge:
 *   { "endpoints": [ { "ini": "...wg-quick config text...", "port": 30000 } ] }
 *
 * Each endpoint becomes its own userspace AmneziaWG device + SOCKS5 inbound
 * on 127.0.0.127:<port>. Port base is [xrayPortBase] + [WG_PORT_OFFSET] so
 * xray, outline, and wireproxy buckets never collide on the same install.
 */
object WgConfigGenerator {

    /** Offset added to xrayPortBase to avoid colliding with xray/outline ports. */
    const val WG_PORT_OFFSET = 2000

    fun build(inis: List<String>, firstPort: Int): String {
        val endpoints = JSONArray()
        inis.forEachIndexed { i, ini ->
            endpoints.put(JSONObject().apply {
                put("ini", ini)
                put("port", firstPort + i)
            })
        }
        return JSONObject().apply { put("endpoints", endpoints) }.toString()
    }
}
