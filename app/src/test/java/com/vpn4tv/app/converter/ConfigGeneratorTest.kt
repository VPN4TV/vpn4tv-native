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
 * carry it inside the TCP connection to the bridge (udp_over_tcp), olcRTC and
 * TrustTunnel carry TCP only, and no profile sends UDP or DNS around the
 * tunnel any more. Keys and hosts below are made up.
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
    fun trustTunnelOnlyRefusesUdp() {
        val config = generate(trustTunnel(), olcrtc())
        assertTcpOnly(config.outbound("TT"))
        assertTcpOnly(config.outbound("OLC"))
        assertEquals("reject", udpRules(config).single().getString("action"))
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
        assertTcpOnly(config.outbound("TT"))
        assertEquals("shadowsocks", config.outbound("SS").getString("type"))
        assertFalse(config.outbound("SS").has("udp_over_tcp"))
        assertTrue(udpRules(config).isEmpty())
        assertNoLeak(config)
        assertTrue(config.getJSONObject("experimental").getJSONObject("cache_file").getBoolean("store_fakeip"))
        assertFalse(config.getJSONObject("dns").has("strategy"))
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
    fun xrayAsksDnsOverTcpButIsNotRefusedUdp() {
        ProxyParser.lastDns = SubscriptionDns(remoteDns = "quic://dns.example.net", directDns = "9.9.9.9")
        val config = generate(xray())
        assertTcpOnly(config.outbound("XH"))
        // No reject: xray profiles route as before (Russia direct still sees their UDP).
        assertTrue(udpRules(config).isEmpty())
        assertEquals("tls", config.dnsServer("dns-remote").getString("type"))
    }

    @Test
    fun dnsOverTcpWhereverABridgeCanBePicked() {
        assertFalse(ConfigGenerator.dnsOverTcp(generate(native())))
        for (bridge in listOf(outline(), outlineDynamic(), awg(), olcrtc(), trustTunnel(), xray())) {
            assertTrue(bridge.tag, ConfigGenerator.dnsOverTcp(generate(bridge)))
        }
        assertTrue(ConfigGenerator.dnsOverTcp(generate(native(), awg())))
        // A config generated before UDP: every bridge outbound had network = tcp.
        val old = generate(outline())
        old.outbound("OL").apply { remove("udp_over_tcp"); put("network", "tcp") }
        assertTrue(ConfigGenerator.dnsOverTcp(old))
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
        val proxies = listOf(outline(), olcrtc())
        val first = ConfigGenerator.generateFull(proxies, portBase = 30000).singboxJson
        val second = ConfigGenerator.generateFull(proxies, portBase = 30000).singboxJson
        assertEquals(JSONObject(first).getJSONArray("outbounds").toString(), JSONObject(second).getJSONArray("outbounds").toString())
    }
}
