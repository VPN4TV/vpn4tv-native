package com.vpn4tv.app.converter

import org.json.JSONArray
import org.json.JSONObject

/**
 * Generates a complete sing-box JSON config from parsed proxy configs.
 * Includes TUN inbound, DNS, URLTest group, and routing rules.
 */
object ConfigGenerator {

    fun generate(proxies: List<ProxyConfig>): String {
        if (proxies.isEmpty()) throw IllegalArgumentException("No proxies to configure")

        // sing-box passthrough — return native config as-is
        if (proxies.size == 1 && proxies[0].type == "singbox") {
            return proxies[0].outbound.toString(2)
        }

        val config = JSONObject()

        // Log
        config.put("log", JSONObject().apply {
            put("level", "info")
            put("timestamp", true)
        })

        // DNS
        config.put("dns", buildDns(proxies))

        // Inbounds — TUN
        config.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("address", JSONArray().apply {
                    put("172.19.0.1/30")
                    put("fdfe:dcba:9876::1/126")
                })
                put("auto_route", true)
                put("strict_route", true)
                put("stack", "mixed")
            })
        })

        // Outbounds
        config.put("outbounds", buildOutbounds(proxies))

        // Route
        config.put("route", buildRoute(proxies))

        return config.toString(2)
    }

    private fun buildDns(proxies: List<ProxyConfig>): JSONObject {
        val proxyTag = if (proxies.size > 1) "select" else proxies.first().tag

        return JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "dns-remote")
                    put("address", "https://1.1.1.1/dns-query")
                    put("address_resolver", "dns-direct")
                    put("detour", proxyTag)
                })
                put(JSONObject().apply {
                    put("tag", "dns-direct")
                    put("address", "1.1.1.1")
                    put("detour", "direct")
                })
            })
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("outbound", "any")
                    put("server", "dns-direct")
                })
            })
        }
    }

    private fun buildOutbounds(proxies: List<ProxyConfig>): JSONArray {
        val outbounds = JSONArray()

        if (proxies.size > 1) {
            // URLTest group (auto-select best)
            outbounds.put(JSONObject().apply {
                put("type", "urltest")
                put("tag", "auto")
                put("outbounds", JSONArray().apply {
                    proxies.forEach { put(it.tag) }
                })
                put("url", "https://cp.cloudflare.com/")
                put("interval", "5m")
                put("tolerance", 50)
            })

            // Selector group (manual select, default = auto)
            outbounds.put(JSONObject().apply {
                put("type", "selector")
                put("tag", "select")
                put("outbounds", JSONArray().apply {
                    put("auto")
                    proxies.forEach { put(it.tag) }
                })
                put("default", "auto")
            })
        }

        // Individual proxy outbounds
        proxies.forEach { proxy ->
            outbounds.put(proxy.outbound)
        }

        // Direct
        outbounds.put(JSONObject().apply {
            put("type", "direct")
            put("tag", "direct")
        })

        return outbounds
    }

    private fun buildRoute(proxies: List<ProxyConfig>): JSONObject {
        return JSONObject().apply {
            put("rules", JSONArray().apply {
                // sing-box 1.13+: sniff via rule action
                put(JSONObject().apply {
                    put("action", "sniff")
                })
                // sing-box 1.13+: hijack DNS instead of dns outbound
                put(JSONObject().apply {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                })
            })
            put("auto_detect_interface", true)
            put("final", if (proxies.size > 1) "select" else proxies.first().tag)
        }
    }
}
