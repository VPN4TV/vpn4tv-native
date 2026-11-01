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

    /** Address ranges the FakeDNS server hands out (fc00::/18 lies inside the ULA block). */
    const val FAKEIP_INET4_RANGE = "198.18.0.0/15"
    const val FAKEIP_INET6_RANGE = "fc00::/18"

    /**
     * Serializes the writes of a profile's config and sidecars ([writeAll])
     * with SavedConfigUpgrade, which rewrites saved configs once after an
     * update: a refresh at launch must not be overwritten by an upgrade of
     * the file it replaced.
     */
    val fileLock = Any()

    /**
     * Its outbound is a socks outbound to one of our local bridges (xray
     * included). DNS through those goes over TCP, see [dnsOverTcp].
     */
    private fun ProxyConfig.reachesBridge(): Boolean =
        xrayOutbound != null || outlineUrl != null || outlineDynamicUrl != null ||
            awgIni != null || olcrtcUrl != null || ttUrl != null

    /**
     * Routed through a local bridge other than xray: what FakeDNS and the
     * IPv4-only strategy look at. An Outline dynamic key is an Outline
     * bridge like a plain one.
     */
    private fun ProxyConfig.isBridged(): Boolean =
        outlineUrl != null || outlineDynamicUrl != null || awgIni != null || olcrtcUrl != null || ttUrl != null

    /**
     * sing-box's UDP over TCP on the socks outbound to a bridge: the
     * datagrams travel framed inside the same authenticated TCP connection
     * (the core's bridges serve it since bridge-udp), so nothing listens for
     * UDP on the shared loopback and the bridge password covers UDP as well.
     */
    internal fun udpOverTcp(): JSONObject = JSONObject().put("enabled", true).put("version", 2)

    /**
     * Whether sing-box can send UDP through [outbound], one of a generated
     * config's proxies. The socks outbounds to olcRTC, TrustTunnel and xray
     * carry TCP only (network = tcp); Outline and AmneziaWG carry UDP over
     * TCP; naive carries UDP only over TCP, when that is on; http never.
     * Groups, direct and the like are not proxies and carry nothing here.
     */
    internal fun carriesUdp(outbound: JSONObject): Boolean = when (outbound.optString("type")) {
        "selector", "urltest", "direct", "block", "dns" -> false
        "http" -> false
        "naive" -> outbound.opt("udp_over_tcp") == true ||
            outbound.optJSONObject("udp_over_tcp")?.optBoolean("enabled", false) == true
        else -> outbound.optString("network") != "tcp"
    }

    /**
     * The rule that refuses UDP in a profile where no proxy carries it, the
     * last of the generated rules: apps take the refusal as "no QUIC" and go
     * over TCP at once. The rules added at start keep it last (the LAN
     * bypass goes ahead of it, RussiaDirect.keepUdpRejectLast moves it after
     * Russia direct), so LAN, Russian UDP and torrents still go direct.
     */
    internal fun udpRejectRule(): JSONObject = JSONObject().put("network", "udp").put("action", "reject")

    /**
     * True when [root] (a generated config) can send DNS through one of our
     * bridges, i.e. has a socks outbound to the bridge host. Remote DNS then
     * goes over TCP: olcRTC, TrustTunnel and xray carry nothing else (a UDP
     * query fails with "UDP is not supported by outbound"), and over Outline
     * and AmneziaWG the bridge's TCP path is the one known to work — UDP also
     * needs the server's UDP relay and a network that lets it through.
     * BoxService checks it before it puts a UDP DNS server in.
     */
    fun dnsOverTcp(root: JSONObject): Boolean {
        val outbounds = root.optJSONArray("outbounds") ?: return false
        return (0 until outbounds.length()).any { i ->
            val outbound = outbounds.optJSONObject(i) ?: return@any false
            outbound.optString("type") == "socks" &&
                outbound.optString("server") == com.vpn4tv.app.xray.XrayBridge.SOCKS_HOST
        }
    }

    /**
     * The TCP form of a DNS server type, for DNS that must not go over UDP:
     * plain DNS over TCP for udp, DNS over TLS for DNS over QUIC, DoH over
     * HTTP/2 for DoH over HTTP/3. TCP types stay as they are.
     */
    internal fun tcpDnsType(type: String): String = when (type) {
        "udp" -> "tcp"
        "quic" -> "tls"
        "h3" -> "https"
        else -> type
    }

    /** Result of [generate]. The sing-box JSON is always present; bridge
     *  sidecar JSONs are non-null only when that bridge is needed. */
    data class Result(
        val singboxJson: String,
        val xrayJson: String?,
        val outlineJson: String?,
        val wgJson: String?,
        val olcrtcJson: String? = null,
        val ttJson: String? = null,
    )

    /** Produce just the sing-box JSON string (backwards-compat shortcut). */
    fun generate(proxies: List<ProxyConfig>): String = generateFull(proxies).singboxJson

    /** Sidecar xray config path for a given sing-box config file. */
    fun xraySidecarPath(singboxConfigPath: String): String = "$singboxConfigPath.xray.json"

    /** Sidecar outline config path for a given sing-box config file. */
    fun outlineSidecarPath(singboxConfigPath: String): String = "$singboxConfigPath.outline.json"

    /** Sidecar wireproxy-awg config path for a given sing-box config file. */
    fun wgSidecarPath(singboxConfigPath: String): String = "$singboxConfigPath.wg.json"

    /** Sidecar olcrtc config path for a given sing-box config file. */
    fun olcrtcSidecarPath(singboxConfigPath: String): String = "$singboxConfigPath.olcrtc.json"

    /** Sidecar TrustTunnel config path for a given sing-box config file. */
    fun ttSidecarPath(singboxConfigPath: String): String = "$singboxConfigPath.tt.json"

    /**
     * Convenience helper: writes [singboxJson] to [configPath], plus every
     * sidecar next to it, deleting any stale sidecars when the new result
     * no longer needs them. Call after [generateFull].
     */
    fun writeAll(configPath: String, result: Result) = synchronized(fileLock) {
        File(configPath).writeText(result.singboxJson)
        writeOrDelete(xraySidecarPath(configPath), result.xrayJson)
        writeOrDelete(outlineSidecarPath(configPath), result.outlineJson)
        writeOrDelete(wgSidecarPath(configPath), result.wgJson)
        writeOrDelete(olcrtcSidecarPath(configPath), result.olcrtcJson)
        writeOrDelete(ttSidecarPath(configPath), result.ttJson)
    }

    private fun writeOrDelete(path: String, content: String?) {
        val f = File(path)
        if (content != null) f.writeText(content)
        else if (f.exists()) f.delete()
    }

    /**
     * [portBase]: where the bridge ports start; the install's random base
     * from Settings unless a test passes one (Settings needs the app).
     */
    fun generateFull(
        proxies: List<ProxyConfig>,
        portBase: Int = com.vpn4tv.app.xray.XrayBridge.SOCKS_PORT_BASE,
    ): Result {
        if (proxies.isEmpty()) throw IllegalArgumentException("No proxies to configure")

        // sing-box passthrough — return native config as-is
        if (proxies.size == 1 && proxies[0].type == "singbox") {
            return Result(proxies[0].outbound.toString(2), null, null, null)
        }

        // Replace xray-managed proxies (xhttp/splithttp) with sing-box socks outbounds
        // pointing at the local xray bridge. Each xray outbound gets its own port so
        // sing-box → xray routing is 1:1.
        val xrayOutbounds = mutableListOf<JSONObject>()
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
        // never collide on the same install. TCP and UDP: UDP rides inside the
        // TCP connection to the bridge (udp_over_tcp), and the bridge sends it
        // to the Outline server as Shadowsocks UDP.
        val outlineUrls = mutableListOf<String>()
        val outlinePortBase = portBase +
            com.vpn4tv.app.outline.OutlineConfigGenerator.OUTLINE_PORT_OFFSET
        for (proxy in proxies) {
            // A dynamic key has no URL yet — it is fetched before connecting.
            val url = proxy.outlineUrl ?: proxy.outlineDynamicUrl?.let { "" } ?: continue
            val port = outlinePortBase + outlineUrls.size
            outlineUrls.add(if (proxy.outlineDynamicUrl != null) "dynamic:" + proxy.outlineDynamicUrl else url)
            proxy.outbound.apply {
                put("type", "socks")
                put("tag", proxy.tag)
                put("server", host)
                put("server_port", port)
                put("version", "5")
                remove("network")
                put("udp_over_tcp", udpOverTcp())
                put("bind_interface", "lo")
            }
        }

        // And for AmneziaWG (awg 2.0) proxies: userspace WG via wireproxy-awg.
        // TCP and UDP, UDP inside the TCP connection like Outline's; the
        // bridge sends it through the tunnel's netstack.
        val wgInis = mutableListOf<String>()
        val wgPortBase = portBase +
            com.vpn4tv.app.wireproxy.WgConfigGenerator.WG_PORT_OFFSET
        for (proxy in proxies) {
            val ini = proxy.awgIni ?: continue
            val port = wgPortBase + wgInis.size
            wgInis.add(ini)
            proxy.outbound.apply {
                put("type", "socks")
                put("tag", proxy.tag)
                put("server", host)
                put("server_port", port)
                put("version", "5")
                remove("network")
                put("udp_over_tcp", udpOverTcp())
                put("bind_interface", "lo")
                // Force sing-box to resolve domains locally before sending
                // SOCKS CONNECT to the wireproxy bridge — netstack inside
                // wireproxy has no stable resolver for the bot's wg:// URI
                // format (which doesn't carry DNS fields). With ipv4_only,
                // sing-box hijacks DNS, looks up the IP via dns-direct, and
                // hands us an IP:port in the CONNECT request.
                put("domain_strategy", "ipv4_only")
            }
        }

        // TrustTunnel: the link goes to the bridge whole (the library decodes it).
        // The SOCKS listener resolves names itself through the tunnel, but
        // ipv4_only keeps DNS on sing-box's side like the other bridges. TCP
        // only: its SOCKS UDP relay (v1.1.5) takes datagrams from any local
        // source, so the bridge password would not cover UDP.
        val ttUrls = mutableListOf<String>()
        val ttPortBase = portBase +
            com.vpn4tv.app.trusttunnel.TtConfigGenerator.TT_PORT_OFFSET
        for (proxy in proxies) {
            val url = proxy.ttUrl ?: continue
            val port = ttPortBase + ttUrls.size
            ttUrls.add(url)
            proxy.outbound.apply {
                put("type", "socks")
                put("tag", proxy.tag)
                put("server", host)
                put("server_port", port)
                put("version", "5")
                put("network", "tcp")
                put("bind_interface", "lo")
                put("domain_strategy", "ipv4_only")
            }
        }

        // olcRTC: TCP over a WebRTC "call" on a whitelisted meeting service. The
        // link goes to the bridge whole; ipv4_only so the CONNECT names an IP —
        // the far end dials exactly what it is asked for. TCP only by design.
        val olcrtcUrls = mutableListOf<String>()
        val olcrtcPortBase = portBase +
            com.vpn4tv.app.olcrtc.OlcrtcConfigGenerator.OLCRTC_PORT_OFFSET
        for (proxy in proxies) {
            val url = proxy.olcrtcUrl ?: continue
            val port = olcrtcPortBase + olcrtcUrls.size
            olcrtcUrls.add(url)
            proxy.outbound.apply {
                put("type", "socks")
                put("tag", proxy.tag)
                put("server", host)
                put("server_port", port)
                put("version", "5")
                put("network", "tcp")
                put("bind_interface", "lo")
                put("domain_strategy", "ipv4_only")
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

        // Cache: persist fakeip mappings across service restarts — TV apps
        // cache resolved (fake) IPs aggressively; without store_fakeip a
        // restart would orphan every cached fake IP until the app re-resolves.
        // cache.db lives in the working dir; the multi-user EACCES self-heal
        // in BoxService already covers permission-denied on broken externals.
        if (fakeDnsEnabled(proxies)) {
            config.put("experimental", JSONObject().apply {
                put("cache_file", JSONObject().apply {
                    put("enabled", true)
                    put("store_fakeip", true)
                })
            })
        }

        val xrayJson = if (xrayOutbounds.isNotEmpty()) {
            XrayConfigGenerator.build(xrayOutbounds, portBase).first
        } else null

        val outlineJson = if (outlineUrls.isNotEmpty()) {
            com.vpn4tv.app.outline.OutlineConfigGenerator.build(outlineUrls, outlinePortBase)
        } else null

        val wgJson = if (wgInis.isNotEmpty()) {
            com.vpn4tv.app.wireproxy.WgConfigGenerator.build(wgInis, wgPortBase)
        } else null

        val olcrtcJson = if (olcrtcUrls.isNotEmpty()) {
            com.vpn4tv.app.olcrtc.OlcrtcConfigGenerator.build(olcrtcUrls, olcrtcPortBase)
        } else null

        val ttJson = if (ttUrls.isNotEmpty()) {
            com.vpn4tv.app.trusttunnel.TtConfigGenerator.build(ttUrls, ttPortBase)
        } else null

        return Result(config.toString(2), xrayJson, outlineJson, wgJson, olcrtcJson, ttJson)
    }

    private fun buildDns(proxies: List<ProxyConfig>): JSONObject {
        // DNS goes through the tunnel ("select") for every profile. Profiles
        // made only of bridges used to resolve without a detour, i.e. around
        // the tunnel with the real address, because no bridge carried UDP.
        // Where a bridge can be picked, the remote server is asked over TCP
        // (the default DoH already is): see dnsOverTcp(root).
        val allBridged = proxies.all { it.isBridged() }
        val dnsOverTcp = proxies.any { it.reachesBridge() }
        val proxyTag = "select"
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

        val (parsedRemoteType, remoteServer) = parseDnsUrl(dns.remoteDns)
        val remoteType = if (dnsOverTcp) tcpDnsType(parsedRemoteType) else parsedRemoteType
        val (directType, directServer) = parseDnsUrl(dns.directDns)

        val fakeDnsEnabled = fakeDnsEnabled(proxies)

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
                if (fakeDnsEnabled) {
                    put(JSONObject().apply {
                        put("type", "fakeip")
                        put("tag", "dns-fakeip")
                        put("inet4_range", FAKEIP_INET4_RANGE)
                        put("inet6_range", FAKEIP_INET6_RANGE)
                    })
                }
            })
            put("rules", JSONArray().apply {
                // Proxy-server hostnames must resolve to REAL addresses —
                // keep this rule first so it wins over the fakeip rule.
                put(JSONObject().apply {
                    put("outbound", "any")
                    put("server", "dns-direct")
                })
                if (fakeDnsEnabled) {
                    // HTTPS/SVCB (type 65/64) answers carry real IP hints —
                    // apps would use those and bypass the fakeip mapping.
                    // Reject so clients fall back to plain A/AAAA.
                    // COUPLED to BoxService.stripFakeDns: it matches this
                    // exact query_type set to undo the rule at start time.
                    put(JSONObject().apply {
                        put("query_type", JSONArray().apply { put("HTTPS"); put("SVCB") })
                        put("action", "reject")
                    })
                    // Apps get instant fake IPs; the sniffed domain is sent
                    // to the proxy server which resolves it remotely. The
                    // ISP never sees the DNS query at all — DNS-level
                    // blocking and poisoning can't touch resolution.
                    put(JSONObject().apply {
                        put("query_type", JSONArray().apply { put("A"); put("AAAA") })
                        put("server", "dns-fakeip")
                    })
                }
            })
            if (allBridged) {
                // Top-level default strategy as a belt-and-suspenders fallback
                // in case an in-app DNS resolver bypasses the rules above.
                put("strategy", "ipv4_only")
            }
        }
    }

    /**
     * FakeDNS is generated for profiles with at least one native sing-box /
     * xray outbound — those pass the sniffed domain through to the proxy
     * server (remote resolution, full DNS-blocking bypass). It stays OFF for
     * profiles made only of bridges, as before. (It was off because their UDP
     * went "direct", where a fake IP means nothing; UDP now goes through the
     * bridges or is refused, but turning FakeDNS on for them is a separate
     * decision.)
     *
     * Deliberately ignores Settings.fakeDns: the user toggle is applied at
     * service start (BoxService.stripFakeDns), same as the proxy-mode and
     * bypass-LAN rewrites — otherwise toggling would silently not take
     * effect until the next subscription refresh regenerates this file.
     */
    private fun fakeDnsEnabled(proxies: List<ProxyConfig>): Boolean {
        return !proxies.all { it.isBridged() }
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
        // UDP never goes "direct" behind the user's back any more: that sent
        // QUIC, calls, WebRTC and the like around the tunnel with the real
        // address. Outline and AmneziaWG carry UDP now. A profile where no
        // proxy carries UDP (only olcRTC, TrustTunnel, xray — naive without
        // UDP over TCP) refuses UDP outright, last: an answer apps take as
        // "no QUIC" and fall back to TCP at once, where the selector would
        // only fail each flow with "UDP is not supported by outbound: select".
        // The outbounds are final here: the bridge loops above rewrote them.
        val noUdp = proxies.none { carriesUdp(it.outbound) }
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
                // Keep it the last rule: see udpRejectRule.
                if (noUdp) put(udpRejectRule())
            })
            put("auto_detect_interface", true)
            // Always route via the "select" group; ConfigGenerator builds it
            // for both single- and multi-proxy profiles.
            put("final", "select")
            // An outbound whose server is a hostname needs a resolver, or the
            // core refuses to start the whole profile with "missing domain
            // resolver for domain server address". The Apple and desktop
            // generators have always set this; Android did not, and it surfaced
            // as soon as a subscription contained a server addressed by name.
            put("default_domain_resolver", JSONObject().apply { put("server", "dns-direct") })
        }
    }
}
