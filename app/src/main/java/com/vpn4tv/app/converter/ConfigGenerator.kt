package com.vpn4tv.app.converter

import com.vpn4tv.app.xray.XrayConfigGenerator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Generates a complete sing-box JSON config from parsed proxy configs.
 * Includes TUN inbound, DNS, URLTest group, and routing rules.
 */
object ConfigGenerator {

    /** Result of [generate]. The sing-box JSON is always present; xrayJson is
     *  non-null only if at least one proxy is routed through the xray bridge. */
    data class Result(val singboxJson: String, val xrayJson: String?)

    /** Produce just the sing-box JSON string (backwards-compat shortcut). */
    fun generate(proxies: List<ProxyConfig>): String = generateFull(proxies).singboxJson

    /** Sidecar xray config path for a given sing-box config file. */
    fun xraySidecarPath(singboxConfigPath: String): String = "$singboxConfigPath.xray.json"

    fun generateFull(proxies: List<ProxyConfig>): Result {
        if (proxies.isEmpty()) throw IllegalArgumentException("No proxies to configure")

        // sing-box passthrough — return native config as-is
        if (proxies.size == 1 && proxies[0].type == "singbox") {
            return Result(proxies[0].outbound.toString(2), null)
        }

        // Replace xray-managed proxies (xhttp/splithttp) with sing-box socks outbounds
        // pointing at the local xray bridge. Each xray outbound gets its own port so
        // sing-box → xray routing is 1:1.
        val xrayOutbounds = mutableListOf<JSONObject>()
        val portBase = com.vpn4tv.app.xray.XrayBridge.SOCKS_PORT_BASE
        val host = com.vpn4tv.app.xray.XrayBridge.SOCKS_HOST
        for (proxy in proxies) {
            val xrayOb = proxy.xrayOutbound ?: continue
            val port = portBase + xrayOutbounds.size
            xrayOutbounds.add(xrayOb)
            // Replace the empty outbound placeholder with a sing-box socks outbound.
            // bind_interface="lo" forces this connection through loopback instead
            // of the wlan0 auto-binding that route.auto_detect_interface sets
            // globally, which would make 127.0.0.127 unreachable.
            proxy.outbound.apply {
                put("type", "socks")
                put("tag", proxy.tag)
                put("server", host)
                put("server_port", port)
                put("version", "5")
                put("network", "tcp")
                put("bind_interface", "lo")
            }
        }

        // Deduplicate tags — sing-box requires unique outbound tags
        val seenTags = mutableSetOf<String>()
        for (proxy in proxies) {
            val origTag = proxy.outbound.optString("tag", proxy.tag)
            var tag = origTag
            if (tag in seenTags) {
                var i = 2
                while ("${origTag}_$i" in seenTags) i++
                tag = "${origTag}_$i"
                proxy.outbound.put("tag", tag)
            }
            seenTags.add(tag)
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

        val xrayJson = if (xrayOutbounds.isNotEmpty()) {
            XrayConfigGenerator.build(xrayOutbounds).first
        } else null

        return Result(config.toString(2), xrayJson)
    }

    private fun buildDns(proxies: List<ProxyConfig>): JSONObject {
        val proxyTag = if (proxies.size > 1) "select" else proxies.first().tag
        val dns = ProxyParser.lastDns

        // Parse "https://host/path" → ("https", "host")
        // sing-box 1.14 requires {type, server} instead of address URL.
        fun parseDnsUrl(url: String): Pair<String, String> {
            return when {
                url.startsWith("https://") -> {
                    val host = url.removePrefix("https://").substringBefore("/")
                    "https" to host
                }
                url.startsWith("tls://") -> "tls" to url.removePrefix("tls://").substringBefore("/")
                url.startsWith("quic://") -> "quic" to url.removePrefix("quic://").substringBefore("/")
                url.startsWith("h3://") -> "h3" to url.removePrefix("h3://").substringBefore("/")
                else -> "udp" to url.removePrefix("udp://")
            }
        }

        val (remoteType, remoteServer) = parseDnsUrl(dns.remoteDns)
        val (directType, directServer) = parseDnsUrl(dns.directDns)

        return JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", remoteType)
                    put("tag", "dns-remote")
                    put("server", remoteServer)
                    put("domain_resolver", "dns-direct")
                    put("detour", proxyTag)
                })
                put(JSONObject().apply {
                    put("type", directType)
                    put("tag", "dns-direct")
                    put("server", directServer)
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
                    proxies.forEach { put(it.outbound.optString("tag", it.tag)) }
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
                    proxies.forEach { put(it.outbound.optString("tag", it.tag)) }
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
