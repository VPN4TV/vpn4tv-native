package com.vpn4tv.app.xray

import com.vpn4tv.app.bg.BridgeAuth
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a minimal xray-core JSON config with one VLESS inbound per
 * xray-managed outbound. sing-box connects to a specific port on
 * 127.0.0.127 to reach a specific outbound, so routing is 1:1 via inbound
 * tag → outbound tag.
 *
 * The inbounds are VLESS without TLS, not SOCKS5: sing-box's vless outbound
 * sends UDP as XUDP inside the same TCP connection, so UDP gets through the
 * bridge without a UDP port on the shared loopback, and the client id covers
 * it as it covers TCP (SOCKS5 UDP carries no credentials). The id written
 * here is [BridgeAuth.UUID_PLACEHOLDER]; BridgeAuth puts the per-process one
 * in at start, here and in sing-box's outbound.
 *
 * Port layout:
 *   Settings.xrayPortBase + 0 → first xray outbound
 *   Settings.xrayPortBase + 1 → second xray outbound
 *   ...
 */
object XrayConfigGenerator {

    /** Tag of the inbound that leads to the [i]-th xray outbound. */
    fun inboundTag(i: Int): String = "vless-in-$i"

    /**
     * @param xrayOutbounds outbounds in Xray JSON format (protocol/settings/streamSettings).
     *                     These come straight from the original Xray subscription JSON.
     * @param portBase    first inbound port; ConfigGenerator passes its own
     * @return (json, portMap) where portMap[i] is the inbound port for xrayOutbounds[i]
     */
    fun build(xrayOutbounds: List<JSONObject>, portBase: Int = XrayBridge.SOCKS_PORT_BASE): Pair<String, List<Int>> {
        require(xrayOutbounds.isNotEmpty()) { "at least one xray outbound required" }

        val portMap = xrayOutbounds.indices.map { portBase + it }

        val inboundsArr = JSONArray()
        val outboundsArr = JSONArray()
        val rulesArr = JSONArray()

        for ((i, xrayOb) in xrayOutbounds.withIndex()) {
            val inboundTag = inboundTag(i)
            val outboundTag = "proxy-$i"

            inboundsArr.put(JSONObject().apply {
                put("tag", inboundTag)
                put("listen", XrayBridge.SOCKS_HOST)
                put("port", portMap[i])
                put("protocol", "vless")
                put("settings", vlessInboundSettings())
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

    /**
     * Settings of a bridge VLESS inbound as written to disk: one client with
     * the placeholder id, no flow, no VLESS encryption (it is the loopback).
     * Also what ConfigUpgrade gives the SOCKS5 inbounds of an old sidecar.
     */
    fun vlessInboundSettings(): JSONObject = JSONObject().apply {
        put("clients", JSONArray().put(JSONObject().put("id", BridgeAuth.UUID_PLACEHOLDER)))
        put("decryption", "none")
    }
}
