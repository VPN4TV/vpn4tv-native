package com.vpn4tv.app.xray

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a minimal xray-core JSON config with one SOCKS5 inbound per
 * xray-managed outbound. sing-box connects to a specific port on
 * 127.0.0.127 to reach a specific outbound, so routing is 1:1 via inbound
 * tag → outbound tag.
 *
 * Port layout:
 *   Settings.xrayPortBase + 0 → first xray outbound
 *   Settings.xrayPortBase + 1 → second xray outbound
 *   ...
 */
object XrayConfigGenerator {

    /**
     * @param xrayOutbounds outbounds in Xray JSON format (protocol/settings/streamSettings).
     *                     These come straight from the original Xray subscription JSON.
     * @return (json, portMap) where portMap[i] is the SOCKS5 port for xrayOutbounds[i]
     */
    fun build(xrayOutbounds: List<JSONObject>): Pair<String, List<Int>> {
        require(xrayOutbounds.isNotEmpty()) { "at least one xray outbound required" }

        val portBase = XrayBridge.SOCKS_PORT_BASE
        val portMap = xrayOutbounds.indices.map { portBase + it }

        val inboundsArr = JSONArray()
        val outboundsArr = JSONArray()
        val rulesArr = JSONArray()

        for ((i, xrayOb) in xrayOutbounds.withIndex()) {
            val inboundTag = "socks-in-$i"
            val outboundTag = "proxy-$i"

            inboundsArr.put(JSONObject().apply {
                put("tag", inboundTag)
                put("listen", XrayBridge.SOCKS_HOST)
                put("port", portMap[i])
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().apply { put("http"); put("tls") })
                })
            })

            val ob = JSONObject(xrayOb.toString())
            ob.put("tag", outboundTag)
            outboundsArr.put(ob)

            rulesArr.put(JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().apply { put(inboundTag) })
                put("outboundTag", outboundTag)
            })
        }

        outboundsArr.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
        })

        val config = JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "debug") })
            put("inbounds", inboundsArr)
            put("outbounds", outboundsArr)
            put("routing", JSONObject().apply {
                put("domainStrategy", "AsIs")
                put("rules", rulesArr)
            })
        }
        return config.toString() to portMap
    }
}
