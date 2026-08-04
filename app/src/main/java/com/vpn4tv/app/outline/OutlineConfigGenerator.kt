package com.vpn4tv.app.outline

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the JSON config consumed by [OutlineBridge.start].
 *
 * Format expected by libbox.StartOutlineBridge:
 *   { "endpoints": [ { "url": "ss://...", "port": 30000 }, ... ] }
 *
 * Each endpoint becomes its own SOCKS5 inbound on 127.0.0.127:<port>.
 * The base port is randomized per install via the same Settings.xrayPortBase
 * (offset by [OUTLINE_PORT_OFFSET] so xray and outline don't collide).
 */
object OutlineConfigGenerator {

    /** Offset added to xrayPortBase to avoid colliding with xray inbounds. */
    const val OUTLINE_PORT_OFFSET = 1000

    /** Marks an entry that still has to be fetched from a dynamic key. */
    const val DYNAMIC_PREFIX = "dynamic:"

    /**
     * @param outlineUrls list of original `ss://...` URLs, in the same order as
     *                    the corresponding ProxyConfig entries
     * @param firstPort   port assigned to outlineUrls[0]; subsequent endpoints
     *                    use firstPort + 1, +2, ...
     */
    fun build(outlineUrls: List<String>, firstPort: Int): String {
        val endpoints = JSONArray()
        outlineUrls.forEachIndexed { i, url ->
            endpoints.put(JSONObject().apply {
                // "dynamic:<https url>" marks an Outline dynamic key (ssconf://).
                // It carries no server yet — OutlineDynamicKeys fills "url" in
                // before the bridge starts, and keeps the last one that worked.
                if (url.startsWith(DYNAMIC_PREFIX)) {
                    put("dynamicUrl", url.removePrefix(DYNAMIC_PREFIX))
                } else {
                    put("url", url)
                }
                put("port", firstPort + i)
            })
        }
        val cfg = JSONObject().apply { put("endpoints", endpoints) }
        return cfg.toString()
    }
}
