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

    /** Result of [generate]. The sing-box JSON is always present; xrayJson and
     *  outlineJson are non-null only when corresponding bridges are needed. */
    data class Result(val singboxJson: String, val xrayJson: String?, val outlineJson: String?)

    /** Produce just the sing-box JSON string (backwards-compat shortcut). */
    fun generate(proxies: List<ProxyConfig>): String = generateFull(proxies).singboxJson

    /** Sidecar xray config path for a given sing-box config file. */
    fun xraySidecarPath(singboxConfigPath: String): String = "$singboxConfigPath.xray.json"

    /** Sidecar outline config path for a given sing-box config file. */
    fun outlineSidecarPath(singboxConfigPath: String): String = "$singboxConfigPath.outline.json"

    /**
     * Convenience helper: writes [singboxJson] to [configPath], plus xray and
     * outline sidecars next to it, deleting any stale sidecars when the new
     * result no longer needs them. Returns nothing — call after [generateFull].
     */
    fun writeAll(configPath: String, result: Result) {
        File(configPath).writeText(result.singboxJson)
        val xraySidecar = File(xraySidecarPath(configPath))
        if (result.xrayJson != null) xraySidecar.writeText(result.xrayJson)
        else if (xraySidecar.exists()) xraySidecar.delete()
        val outlineSidecar = File(outlineSidecarPath(configPath))
        if (result.outlineJson != null) outlineSidecar.writeText(result.outlineJson)
        else if (outlineSidecar.exists()) outlineSidecar.delete()
    }

    fun generateFull(proxies: List<ProxyConfig>): Result {
        if (proxies.isEmpty()) throw IllegalArgumentException("No proxies to configure")

        // sing-box passthrough — return native config as-is
        if (proxies.size == 1 && proxies[0].type == "singbox") {
            return Result(proxies[0].outbound.toString(2), null, null)
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

        // Same trick for outline-managed proxies (ss:// with SIP002 prefix). Their
        // ports start where xray's end, plus a fixed offset, so the two bridges
        // never collide on the same install.
        val outlineUrls = mutableListOf<String>()
        val outlinePortBase = portBase +
            com.vpn4tv.app.outline.OutlineConfigGenerator.OUTLINE_PORT_OFFSET
        for (proxy in proxies) {
            val url = proxy.outlineUrl ?: continue
            val port = outlinePortBase + outlineUrls.size
            outlineUrls.add(url)
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

        val outlineJson = if (outlineUrls.isNotEmpty()) {
            com.vpn4tv.app.outline.OutlineConfigGenerator.build(outlineUrls, outlinePortBase)
        } else null

        return Result(config.toString(2), xrayJson, outlineJson)
    }

    private fun buildDns(proxies: List<ProxyConfig>): JSONObject {
        // ConfigGenerator always builds a "select" group, so DNS detour can
        // unconditionally point at it. Exception: if every proxy is a TCP-only
        // outline bridge (which doesn't speak SOCKS UDP), drop the detour
        // entirely so DNS just resolves locally — otherwise sing-box errors
        // with "UDP is not supported by outbound: select".
        val allOutlineOnly = proxies.all { it.outlineUrl != null }
        val proxyTag: String? = if (allOutlineOnly) null else "select"
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
                    if (proxyTag != null) put("detour", proxyTag)
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
            if (allOutlineOnly) {
                // Top-level default strategy as a belt-and-suspenders fallback
                // in case an in-app DNS resolver bypasses the rules above.
                put("strategy", "ipv4_only")
            }
        }
    }

    private fun buildOutbounds(proxies: List<ProxyConfig>): JSONArray {
        val outbounds = JSONArray()

        // Always create urltest "auto" + selector "select" so HomeScreen and
        // the rest of the app can read the active outbound from the same
        // well-known group regardless of how many proxies are in the profile.
        // sing-box's SubscribeGroups stream only triggers when there is a
        // urltest group (its observer drives the updates), so building one
        // even for single-proxy profiles keeps writeGroups flowing.
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
        outbounds.put(JSONObject().apply {
            put("type", "selector")
            put("tag", "select")
            put("outbounds", JSONArray().apply {
                put("auto")
                proxies.forEach { put(it.outbound.optString("tag", it.tag)) }
            })
            put("default", "auto")
        })

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
        // For outline-only profiles, the proxy outbound (socks → outline bridge)
        // does not speak SOCKS UDP, so any UDP traffic that ends up routed via
        // "select" causes "UDP is not supported by outbound: select" errors.
        // Bypass that by routing UDP through "direct" instead — privacy is
        // weaker but the alternative is a broken connection.
        val allOutlineOnly = proxies.all { it.outlineUrl != null }
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
                if (allOutlineOnly) {
                    put(JSONObject().apply {
                        put("network", "udp")
                        put("outbound", "direct")
                    })
                }
            })
            put("auto_detect_interface", true)
            // Always route via the "select" group; ConfigGenerator builds it
            // for both single- and multi-proxy profiles.
            put("final", "select")
        }
    }
}
