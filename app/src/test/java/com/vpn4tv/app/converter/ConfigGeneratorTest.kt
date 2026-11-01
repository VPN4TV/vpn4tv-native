package com.vpn4tv.app.converter

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UDP of the bridges in the generated sing-box config: Outline and AmneziaWG
 * carry it inside the TCP connection to the bridge (udp_over_tcp), xray inside
 * its VLESS connection (XUDP), TrustTunnel by plain UDP ASSOCIATE, olcRTC
 * carries TCP only, and no profile sends UDP or DNS around the tunnel any
 * more. Keys and hosts below are made up.
 */
class ConfigGeneratorTest {
    private val savedDns = ProxyParser.lastDns

    @After
    fun restoreDns() {
        ProxyParser.lastDns = savedDns
    }

    private fun outline(tag: String = "OL") = ProxyConfig(
        tag = tag, type = "shadowsocks", server = "192.0.2.10", serverPort = 8388, outbound = JSONObject(),
        outlineUrl = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpzZWNyZXQ@192.0.2.10:8388/?outline=1&prefix=%16%03%01#OL",
    )

    private fun outlineDynamic(tag: String = "OLD") = ProxyConfig(
        tag = tag, type = "shadowsocks", server = "", serverPort = 0, outbound = JSONObject(),
        outlineDynamicUrl = "https://keys.example.net/ssconf/abc",
    )

    private fun awg(tag: String = "AWG") = ProxyConfig(
        tag = tag, type = "wireguard", server = "192.0.2.20", serverPort = 51820, outbound = JSONObject(),
        awgIni = "[Interface]\nPrivateKey = x\nAddress = 10.8.1.2/32\n\n[Peer]\nPublicKey = y\nEndpoint = 192.0.2.20:51820\n",
    )

    private fun olcrtc(tag: String = "OLC") = ProxyConfig(
        tag = tag, type = "olcrtc", server = "", serverPort = 0, outbound = JSONObject(),
        olcrtcUrl = "olcrtc://example",
    )

    private fun trustTunnel(tag: String = "TT") = ProxyConfig(
        tag = tag, type = "trusttunnel", server = "", serverPort = 0, outbound = JSONObject(),
        ttUrl = "tt://?AAEB",
    )

    private fun native(tag: String = "SS") = ProxyConfig(
        tag = tag, type = "shadowsocks", server = "192.0.2.30", serverPort = 8388,
        outbound = JSONObject()
            .put("type", "shadowsocks").put("tag", tag).put("server", "192.0.2.30").put("server_port", 8388)
            .put("method", "aes-256-gcm").put("password", "secret"),
    )

    private fun xray(tag: String = "XH") = ProxyConfig(
        tag = tag, type = "vless", server = "192.0.2.40", serverPort = 443, outbound = JSONObject(),
        xrayOutbound = JSONObject().put("protocol", "vless"),
    )

    private fun generate(vararg proxies: ProxyConfig): JSONObject =
        JSONObject(ConfigGenerator.generateFull(proxies.toList(), portBase = 30000).singboxJson)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.outbound(tag: String) = getJSONArray("outbounds").objects().single { it.optString("tag") == tag }
    private fun JSONObject.routeRules() = getJSONObject("route").getJSONArray("rules").objects()
    private fun JSONObject.dnsServer(tag: String) =
        getJSONObject("dns").getJSONArray("servers").objects().single { it.optString("tag") == tag }

    private fun assertCarriesUdp(outbound: JSONObject) {
        assertEquals("socks", outbound.getString("type"))
        assertEquals("127.0.0.127", outbound.getString("server"))
        assertEquals("lo", outbound.getString("bind_interface"))
        assertFalse("no network limit", outbound.has("network"))
        val uot = outbound.getJSONObject("udp_over_tcp")
        assertTrue(uot.getBoolean("enabled"))
        assertEquals(2, uot.getInt("version"))
    }

    private fun assertTcpOnly(outbound: JSONObject) {
        assertEquals("socks", outbound.getString("type"))
        assertEquals("tcp", outbound.getString("network"))
        assertFalse(outbound.has("udp_over_tcp"))
    }

    /**
     * xray: VLESS without TLS to the bridge on the loopback, UDP as XUDP, the
     * UUID a placeholder BridgeAuth replaces at start. Nothing of a socks
     * outbound left, which sing-box would refuse as unknown fields.
     */
    private fun assertXrayBridge(outbound: JSONObject, port: Int) {
        assertEquals("vless", outbound.getString("type"))
        assertEquals("127.0.0.127", outbound.getString("server"))
        assertEquals(port, outbound.getInt("server_port"))
        assertEquals("lo", outbound.getString("bind_interface"))
        assertEquals(com.vpn4tv.app.bg.BridgeAuth.UUID_PLACEHOLDER, outbound.getString("uuid"))
        assertEquals("xudp", outbound.getString("packet_encoding"))
        assertFalse("no network limit", outbound.has("network"))
        for (key in listOf("flow", "tls", "transport", "multiplex", "version", "username", "password", "udp_over_tcp", "domain_strategy")) {
            assertFalse(key, outbound.has(key))
        }
        assertTrue(ConfigGenerator.carriesUdp(outbound))
    }

    /** TrustTunnel: UDP by plain SOCKS5 UDP ASSOCIATE — the library does not speak UDP over TCP. */
    private fun assertCarriesPlainUdp(outbound: JSONObject) {
        assertEquals("socks", outbound.getString("type"))
        assertEquals("127.0.0.127", outbound.getString("server"))
        assertEquals("lo", outbound.getString("bind_interface"))
        assertEquals("5", outbound.getString("version"))
        assertFalse("no network limit", outbound.has("network"))
        assertFalse("no udp_over_tcp", outbound.has("udp_over_tcp"))
    }

    private fun udpRules(config: JSONObject) = config.routeRules().filter { it.optString("network") == "udp" }

    /** Nothing sends UDP around the tunnel, and DNS goes through it. */
    private fun assertNoLeak(config: JSONObject) {
        assertTrue(udpRules(config).none { it.optString("outbound") == "direct" })
        assertEquals("select", config.dnsServer("dns-remote").getString("detour"))
        assertEquals("select", config.getJSONObject("route").getString("final"))
    }

    @Test
    fun outlineOnlyCarriesUdpThroughTheBridge() {
        ProxyParser.lastDns = SubscriptionDns(remoteDns = "udp://8.8.8.8", directDns = "9.9.9.9")
        val config = generate(outline())
        assertCarriesUdp(config.outbound("OL"))
        assertTrue(udpRules(config).isEmpty())
        assertNoLeak(config)
        // DNS through a bridge goes over its TCP path.
        assertEquals("tcp", config.dnsServer("dns-remote").getString("type"))
        // FakeDNS and the IPv4-only strategy stay as they were for bridge-only profiles.
        assertFalse(config.has("experimental"))
        assertEquals("ipv4_only", config.getJSONObject("dns").getString("strategy"))
    }

    @Test
    fun outlineDynamicKeyCarriesUdpToo() {
        val config = generate(outlineDynamic())
        assertCarriesUdp(config.outbound("OLD"))
        assertNoLeak(config)
        assertTrue(udpRules(config).isEmpty())
        // A dynamic key is an Outline bridge: no FakeDNS, IPv4 only, as for a plain key.
        assertFalse(config.has("experimental"))
        assertTrue(config.getJSONObject("dns").getJSONArray("servers").objects().none { it.optString("type") == "fakeip" })
        assertEquals("ipv4_only", config.getJSONObject("dns").getString("strategy"))
    }

    @Test
    fun outlineKeysPlainAndDynamicAreBridgeOnly() {
        val config = generate(outline(), outlineDynamic())
        assertFalse(config.has("experimental"))
        assertEquals("ipv4_only", config.getJSONObject("dns").getString("strategy"))
    }

    @Test
    fun awgOnlyCarriesUdpThroughTheBridge() {
        val config = generate(awg())
        val outbound = config.outbound("AWG")
        assertCarriesUdp(outbound)
        assertEquals("ipv4_only", outbound.getString("domain_strategy"))
        assertTrue(udpRules(config).isEmpty())
        assertNoLeak(config)
        assertFalse(config.has("experimental"))
    }

    @Test
    fun olcrtcOnlyRefusesUdpAndAsksDnsOverTcp() {
        ProxyParser.lastDns = SubscriptionDns(remoteDns = "1.1.1.1", directDns = "9.9.9.9")
        val config = generate(olcrtc())
        assertTcpOnly(config.outbound("OLC"))
        val rules = config.routeRules()
        val reject = udpRules(config).single()
        assertEquals("reject", reject.getString("action"))
        assertFalse(reject.has("outbound"))
        // Right after the sniff / hijack-dns prelude, so DNS is hijacked first.
        assertEquals(listOf("sniff", "hijack-dns", "reject"), rules.map { it.optString("action") })
        assertNoLeak(config)
        val remote = config.dnsServer("dns-remote")
        assertEquals("tcp", remote.getString("type"))
        assertEquals("1.1.1.1", remote.getString("server"))
        // dns-direct (outside the tunnel, for proxy hostnames) is left alone.
        assertEquals("udp", config.dnsServer("dns-direct").getString("type"))
    }

    @Test
    fun trustTunnelOnlyCarriesUdpByPlainAssociate() {
        ProxyParser.lastDns = SubscriptionDns(remoteDns = "udp://8.8.8.8", directDns = "9.9.9.9")
        val config = generate(trustTunnel())
        val outbound = config.outbound("TT")
        assertCarriesPlainUdp(outbound)
        assertEquals(34000, outbound.getInt("server_port"))
        // No refusal: UDP goes to the bridge.
        assertTrue(udpRules(config).isEmpty())
        assertEquals(listOf("sniff", "hijack-dns"), config.routeRules().map { it.optString("action") })
        assertNoLeak(config)
        // DNS through the bridge still goes over its TCP path.
        assertEquals("tcp", config.dnsServer("dns-remote").getString("type"))
        // No FakeDNS for a profile made only of bridges: its UDP destinations
        // are addresses, the only kind the TrustTunnel relay takes.
        assertFalse(config.has("experimental"))
        assertTrue(config.getJSONObject("dns").getJSONArray("servers").objects().none { it.optString("type") == "fakeip" })
        assertEquals("ipv4_only", config.getJSONObject("dns").getString("strategy"))
    }

    @Test
    fun trustTunnelLiftsTheRejectOfOlcrtc() {
        val config = generate(trustTunnel(), olcrtc())
        assertCarriesPlainUdp(config.outbound("TT"))
        assertTcpOnly(config.outbound("OLC"))
        assertTrue(udpRules(config).isEmpty())
        assertNoLeak(config)
        // The default remote DNS is DoH, TCP already.
        assertEquals("https", config.dnsServer("dns-remote").getString("type"))
    }

    @Test
    fun bridgesThatCarryUdpLiftTheReject() {
        ProxyParser.lastDns = SubscriptionDns(remoteDns = "udp://8.8.8.8", directDns = "9.9.9.9")
        val config = generate(outline(), olcrtc(), trustTunnel())
        assertCarriesUdp(config.outbound("OL"))
        assertTcpOnly(config.outbound("OLC"))
        assertCarriesPlainUdp(config.outbound("TT"))
        assertTrue(udpRules(config).isEmpty())
        assertNoLeak(config)
        // A TCP-only bridge can still be picked by hand: DNS over TCP.
        assertEquals("tcp", config.dnsServer("dns-remote").getString("type"))
    }

    @Test
    fun mixedProfileKeepsFakeDnsAndCarriesUdp() {
        val config = generate(native(), outline(), awg(), olcrtc(), trustTunnel())
        assertCarriesUdp(config.outbound("OL"))
        assertCarriesUdp(config.outbound("AWG"))
        assertTcpOnly(config.outbound("OLC"))
        assertCarriesPlainUdp(config.outbound("TT"))
        assertEquals("shadowsocks", config.outbound("SS").getString("type"))
        assertFalse(config.outbound("SS").has("udp_over_tcp"))
        // TrustTunnel is in it: UDP names are resolved on the route, nothing refused.
        assertEquals(listOf("resolve"), udpRules(config).map { it.getString("action") })
        assertNoLeak(config)
        assertTrue(config.getJSONObject("experimental").getJSONObject("cache_file").getBoolean("store_fakeip"))
        assertFalse(config.getJSONObject("dns").has("strategy"))
    }

    @Test
    fun fakeDnsProfileWithTrustTunnelResolvesUdpNamesThroughTheTunnel() {
        val config = generate(native(), trustTunnel())
        assertCarriesPlainUdp(config.outbound("TT"))
        // FakeDNS is on (a native outbound is in the profile): a UDP flow to a
        // fake address would reach TrustTunnel with the name.
        assertTrue(config.getJSONObject("experimental").getJSONObject("cache_file").getBoolean("store_fakeip"))
        val rules = config.routeRules()
        assertEquals(listOf("sniff", "hijack-dns", "resolve"), rules.map { it.optString("action") })
        val resolve = rules[2]
        assertEquals("udp", resolve.getString("network"))
        assertEquals("dns-remote", resolve.getString("server"))
        assertEquals("prefer_ipv4", resolve.getString("strategy"))
        assertEquals(setOf("network", "action", "server", "strategy"), resolve.keys().asSequence().toSet())
        // dns-remote goes through the tunnel.
        assertEquals("select", config.dnsServer("dns-remote").getString("detour"))
        assertNoLeak(config)
    }

    @Test
    fun noResolveWithoutFakeDnsOrWithoutTrustTunnel() {
        val profiles = listOf(
            listOf(trustTunnel()),
            listOf(trustTunnel(), olcrtc()),
            listOf(trustTunnel(), outline(), awg()),
            listOf(native()),
            listOf(native(), outline(), awg(), olcrtc()),
            listOf(native(), xray()),
            // FakeDNS on, but xray takes names in XUDP.
            listOf(xray()),
        )
        for (profile in profiles) {
            val config = generate(*profile.toTypedArray())
            val tags = profile.joinToString { it.tag }
            assertTrue(tags, config.routeRules().none { it.optString("action") == "resolve" })
        }
    }

    @Test
    fun russiaDirectKeepsTheResolveRuleInFront() {
        val config = generate(native(), trustTunnel())
        val result = com.vpn4tv.app.bg.RussiaDirect.apply(config.toString(), com.vpn4tv.app.bg.RussiaDirect.Options())
        assertTrue(result.state, result.applied)
        val rules = JSONObject(result.json).routeRules()
        assertEquals(listOf("sniff", "hijack-dns", "resolve"), rules.take(3).map { it.optString("action") })
        assertEquals(1, rules.count { it.optString("action") == "resolve" })
        // Its own rules come after: the torrents and the Russian names go direct.
        assertTrue(rules.drop(3).any { it.optString("outbound") == "direct" })
    }

    @Test
    fun nativeProfileIsUnchanged() {
        ProxyParser.lastDns = SubscriptionDns(remoteDns = "udp://8.8.8.8", directDns = "9.9.9.9")
        val config = generate(native())
        assertTrue(udpRules(config).isEmpty())
        assertEquals("udp", config.dnsServer("dns-remote").getString("type"))
        assertNoLeak(config)
    }

    @Test
    fun xrayOnlyCarriesUdpAsXudpAndAsksDnsOverTcp() {
        ProxyParser.lastDns = SubscriptionDns(remoteDns = "quic://dns.example.net", directDns = "9.9.9.9")
        val config = generate(xray())
        assertXrayBridge(config.outbound("XH"), 30000)
        // UDP goes to the bridge: nothing refused, nothing resolved on the route.
        assertTrue(udpRules(config).isEmpty())
        assertEquals(listOf("sniff", "hijack-dns"), config.routeRules().map { it.optString("action") })
        assertNoLeak(config)
        // DNS through the bridge goes over its TCP path, as through the others.
        assertEquals("tls", config.dnsServer("dns-remote").getString("type"))
        // FakeDNS stays: xray passes the sniffed domain to the server.
        assertTrue(config.getJSONObject("experimental").getJSONObject("cache_file").getBoolean("store_fakeip"))
        assertFalse(config.getJSONObject("dns").has("strategy"))
    }

    @Test
    fun xrayOutboundsGetOnePortEachFromTheBase() {
        val result = ConfigGenerator.generateFull(listOf(xray("X1"), native(), xray("X2"), outline()), portBase = 30000)
        val config = JSONObject(result.singboxJson)
        assertXrayBridge(config.outbound("X1"), 30000)
        assertXrayBridge(config.outbound("X2"), 30001)
        assertEquals(31000, config.outbound("OL").getInt("server_port"))
        // The sidecar serves exactly those ports, by VLESS.
        val inbounds = JSONObject(result.xrayJson!!).getJSONArray("inbounds").objects()
        assertEquals(listOf(30000, 30001), inbounds.map { it.getInt("port") })
        assertTrue(inbounds.all { it.getString("protocol") == "vless" })
    }

    @Test
    fun xraySidecarHasVlessInboundsWithThePlaceholder() {
        val xrayJson = ConfigGenerator.generateFull(listOf(xray("X1"), xray("X2")), portBase = 30000).xrayJson!!
        val root = JSONObject(xrayJson)
        val inbounds = root.getJSONArray("inbounds").objects()
        assertEquals(2, inbounds.size)
        for ((i, inbound) in inbounds.withIndex()) {
            assertEquals("vless-in-$i", inbound.getString("tag"))
            assertEquals("vless", inbound.getString("protocol"))
            assertEquals("127.0.0.127", inbound.getString("listen"))
            assertEquals(30000 + i, inbound.getInt("port"))
            val settings = inbound.getJSONObject("settings")
            assertEquals("none", settings.getString("decryption"))
            val clients = settings.getJSONArray("clients").objects()
            assertEquals(1, clients.size)
            assertEquals(com.vpn4tv.app.bg.BridgeAuth.UUID_PLACEHOLDER, clients[0].getString("id"))
            assertFalse(clients[0].has("flow"))
            // Nothing of the SOCKS5 inbound: no UDP port, no auth fields.
            for (key in listOf("udp", "auth", "accounts")) assertFalse(key, settings.has(key))
            assertTrue(inbound.getJSONObject("sniffing").getBoolean("enabled"))
        }
        // Routing stays 1:1, inbound tag to outbound tag.
        val rules = root.getJSONObject("routing").getJSONArray("rules").objects()
        assertEquals(listOf("vless-in-0", "vless-in-1"), rules.map { it.getJSONArray("inboundTag").getString(0) })
        assertEquals(listOf("proxy-0", "proxy-1"), rules.map { it.getString("outboundTag") })
        assertFalse(xrayJson.contains("socks"))
    }

    @Test
    fun xrayOutboundWrittenAsSocksBeforeBecomesVless() {
        // The same ProxyConfig, its outbound as the generator wrote it before 5.4.0.
        val proxy = xray()
        proxy.outbound.put("type", "socks").put("tag", "XH").put("server", "127.0.0.127").put("server_port", 30000)
            .put("version", "5").put("network", "tcp").put("bind_interface", "lo")
        val config = generate(proxy)
        assertXrayBridge(config.outbound("XH"), 30000)
        assertTrue(udpRules(config).isEmpty())
    }

    @Test
    fun theUdpRefusalIsTheLastRuleWhereNoProxyCarriesUdp() {
        val naive = ProxyConfig(
            tag = "NV", type = "naive", server = "192.0.2.50", serverPort = 443,
            outbound = JSONObject().put("type", "naive").put("tag", "NV").put("server", "192.0.2.50").put("server_port", 443),
        )
        for (proxies in listOf(listOf(olcrtc()), listOf(naive), listOf(olcrtc("O1"), naive))) {
            val config = generate(*proxies.toTypedArray())
            val rules = config.routeRules()
            assertEquals(proxies.joinToString { it.tag }, listOf("sniff", "hijack-dns", "reject"), rules.map { it.optString("action") })
            assertEquals(ConfigGenerator.udpRejectRule().toString(), rules.last().toString())
        }
    }

    @Test
    fun noUdpRefusalWhereAProxyCarriesUdp() {
        val naiveUot = ProxyConfig(
            tag = "NU", type = "naive", server = "192.0.2.51", serverPort = 443,
            outbound = JSONObject().put("type", "naive").put("tag", "NU").put("server", "192.0.2.51").put("server_port", 443)
                .put("udp_over_tcp", JSONObject().put("enabled", true)),
        )
        for (proxies in listOf(listOf(xray(), native()), listOf(xray(), outline()), listOf(olcrtc(), awg()), listOf(naiveUot), listOf(native()),
                listOf(trustTunnel()), listOf(xray("X1"), olcrtc("O1"), trustTunnel("T1")), listOf(xray()), listOf(xray("X2"), olcrtc("O2")))) {
            val config = generate(*proxies.toTypedArray())
            // No refusal; a profile with FakeDNS and TrustTunnel resolves UDP names on the route instead.
            assertTrue(proxies.joinToString { it.tag }, udpRules(config).none { it.optString("action") == "reject" })
        }
    }

    @Test
    fun whatCarriesUdp() {
        fun ob(type: String) = JSONObject().put("type", type)
        assertTrue(ConfigGenerator.carriesUdp(ob("shadowsocks")))
        assertTrue(ConfigGenerator.carriesUdp(ob("vless")))
        assertFalse(ConfigGenerator.carriesUdp(ob("vless").put("network", "tcp")))
        assertTrue(ConfigGenerator.carriesUdp(ob("hysteria2").put("network", "udp")))
        assertFalse(ConfigGenerator.carriesUdp(ob("socks").put("network", "tcp")))
        assertTrue(ConfigGenerator.carriesUdp(ob("socks").put("udp_over_tcp", ConfigGenerator.udpOverTcp())))
        assertFalse(ConfigGenerator.carriesUdp(ob("naive")))
        assertTrue(ConfigGenerator.carriesUdp(ob("naive").put("udp_over_tcp", true)))
        assertTrue(ConfigGenerator.carriesUdp(ob("naive").put("udp_over_tcp", JSONObject().put("enabled", true))))
        assertFalse(ConfigGenerator.carriesUdp(ob("http")))
        for (type in listOf("selector", "urltest", "direct", "block", "dns")) assertFalse(type, ConfigGenerator.carriesUdp(ob(type)))
        // The outbound to the xray bridge, and the socks one it replaces.
        assertTrue(ConfigGenerator.carriesUdp(ConfigGenerator.toXrayBridgeOutbound(JSONObject(), 30000)))
        assertFalse(ConfigGenerator.carriesUdp(ob("socks").put("server", "127.0.0.127").put("network", "tcp")))
    }

    @Test
    fun dnsOverTcpWhereverABridgeCanBePicked() {
        assertFalse(ConfigGenerator.dnsOverTcp(generate(native())))
        for (bridge in listOf(outline(), outlineDynamic(), awg(), olcrtc(), trustTunnel(), xray())) {
            assertTrue(bridge.tag, ConfigGenerator.dnsOverTcp(generate(bridge)))
        }
        assertTrue(ConfigGenerator.dnsOverTcp(generate(native(), awg())))
        assertTrue(ConfigGenerator.dnsOverTcp(generate(native(), xray())))
        // A config generated before UDP: every bridge outbound had network = tcp.
        val old = generate(outline())
        old.outbound("OL").apply { remove("udp_over_tcp"); put("network", "tcp") }
        assertTrue(ConfigGenerator.dnsOverTcp(old))
        // A vless outbound to a server of its own is no bridge.
        val vless = ProxyConfig(
            tag = "VL", type = "vless", server = "192.0.2.60", serverPort = 443,
            outbound = JSONObject().put("type", "vless").put("tag", "VL").put("server", "192.0.2.60").put("server_port", 443)
                .put("uuid", "a3482e88-686a-4a58-8126-99c9df64b7bf").put("packet_encoding", "xudp"),
        )
        assertFalse(ConfigGenerator.dnsOverTcp(generate(vless)))
    }

    @Test
    fun tcpDnsTypes() {
        assertEquals("tcp", ConfigGenerator.tcpDnsType("udp"))
        assertEquals("tls", ConfigGenerator.tcpDnsType("quic"))
        assertEquals("https", ConfigGenerator.tcpDnsType("h3"))
        for (type in listOf("tcp", "tls", "https")) assertEquals(type, ConfigGenerator.tcpDnsType(type))
    }

    @Test
    fun sidecarsAreStillBuilt() {
        val result = ConfigGenerator.generateFull(listOf(outline(), awg(), olcrtc(), trustTunnel()), portBase = 30000)
        assertNotNull(result.outlineJson)
        assertNotNull(result.wgJson)
        assertNotNull(result.olcrtcJson)
        assertNotNull(result.ttJson)
        assertNull(result.xrayJson)
        val config = JSONObject(result.singboxJson)
        assertEquals(31000, config.outbound("OL").getInt("server_port"))
        assertEquals(32000, config.outbound("AWG").getInt("server_port"))
        assertEquals(33000, config.outbound("OLC").getInt("server_port"))
        assertEquals(34000, config.outbound("TT").getInt("server_port"))
    }

    @Test
    fun regeneratingTheSameProxiesGivesTheSameOutbounds() {
        // generateFull rewrites ProxyConfig.outbound in place.
        val proxies = listOf(outline(), olcrtc(), trustTunnel(), xray())
        val first = ConfigGenerator.generateFull(proxies, portBase = 30000).singboxJson
        val second = ConfigGenerator.generateFull(proxies, portBase = 30000).singboxJson
        assertEquals(JSONObject(first).getJSONArray("outbounds").toString(), JSONObject(second).getJSONArray("outbounds").toString())
    }

    @Test
    fun trustTunnelOutboundWrittenTcpOnlyBeforeLosesTheLimit() {
        // The same ProxyConfig, its outbound as the generator wrote it before.
        val proxy = trustTunnel()
        proxy.outbound.put("network", "tcp")
        val config = generate(proxy)
        assertCarriesPlainUdp(config.outbound("TT"))
        assertTrue(udpRules(config).isEmpty())
    }
}
