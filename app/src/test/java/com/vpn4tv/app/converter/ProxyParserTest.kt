package com.vpn4tv.app.converter

import com.vpn4tv.app.converter.ProxyParser.Hysteria2Endpoint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyParserTest {
    // A real-length TLV payload (the dead bell test key from the desktop tests).
    private val payload =
        "AAEBAQtiZWxsLmE0ZS5hcgULdnBuNHR2LXRlc3QGGGNkNzY1OWFmM2QzM2I3NDllMjIwYTcyOAIQYmVsbC5hNGUuYXI6ODQ0MwwOVlBONFRWIFRUIHRlc3Q"
    private val fingerprintTag = "TrustTunnel ${payload.takeLast(6)}"

    @Test
    fun trustTunnelLabelBecomesTheTagAndLeavesTheLink() {
        val proxy = ProxyParser.parse("tt://?$payload#US10T%20%C2%B7%20960663")!!
        assertEquals("US10T · 960663", proxy.tag)
        assertEquals("tt://?$payload", proxy.ttUrl)
    }

    @Test
    fun trustTunnelWithoutLabelKeepsTheFingerprint() {
        val proxy = ProxyParser.parse("tt://?$payload")!!
        assertEquals(fingerprintTag, proxy.tag)
        assertEquals("tt://?$payload", proxy.ttUrl)
    }

    @Test
    fun trustTunnelEmptyLabelFallsBackToTheFingerprint() {
        val proxy = ProxyParser.parse("tt://?$payload#")!!
        assertEquals(fingerprintTag, proxy.tag)
        assertEquals("tt://?$payload", proxy.ttUrl)
    }

    @Test
    fun trustTunnelBrokenLabelFallsBackToTheFingerprint() {
        val proxy = ProxyParser.parse("tt://?$payload#US10T%ZZ")!!
        assertEquals(fingerprintTag, proxy.tag)
    }

    @Test
    fun trustTunnelQuestionMarkIsOptional() {
        val proxy = ProxyParser.parse("tt://$payload#DE11T")!!
        assertEquals("DE11T", proxy.tag)
        assertEquals("tt://?$payload", proxy.ttUrl)
    }

    @Test
    fun trustTunnelPayloadMustBeBase64Url() {
        assertNull(ProxyParser.parse("tt://?Ab Cd$payload#US10T"))
        assertNull(ProxyParser.parse("tt://?#US10T"))
    }

    // hysteria2: the host and port part. android.net.Uri is a stub on the JVM,
    // so these go through the Uri-free helpers parseHysteria2 uses.

    // The shape the backend issues for hy2-only nodes (vpn-service.js, regen-keys.js).
    private val backendHy2 =
        "hysteria2://0192f0c4-7d3a-7b1e-9c2a-5f4e3d2c1b0a@hy.example.net:8443,9443,20000-40000/?sni=example.net&insecure=0#HY123_7"

    private fun endpoint(uri: String) = ProxyParser.parseHysteria2Endpoint(uri)

    private fun portFields(endpoint: Hysteria2Endpoint): JSONObject =
        JSONObject().also { ProxyParser.putHysteria2Ports(it, endpoint) }

    private fun JSONObject.stringList(key: String): List<String> {
        val array = getJSONArray(key)
        return (0 until array.length()).map { array.getString(it) }
    }

    @Test
    fun hysteria2BackendPortListHopsOverEveryEntry() {
        val hy2 = endpoint(backendHy2)!!
        assertEquals(Hysteria2Endpoint("hy.example.net", 8443, listOf("8443:8443", "9443:9443", "20000:40000")), hy2)
        val outbound = portFields(hy2)
        assertFalse(outbound.has("server_port"))
        assertEquals(listOf("8443:8443", "9443:9443", "20000:40000"), outbound.stringList("server_ports"))
        assertEquals("30s", outbound.getString("hop_interval"))
    }

    @Test
    fun hysteria2SinglePortStaysServerPort() {
        // hy2-keygen.js
        val hy2 = endpoint("hysteria2://pass@hy.example.net:443/?sni=example.net&insecure=0#HY123_7")!!
        assertEquals(Hysteria2Endpoint("hy.example.net", 443), hy2)
        val outbound = portFields(hy2)
        assertEquals(443, outbound.getInt("server_port"))
        assertFalse(outbound.has("server_ports"))
        assertFalse(outbound.has("hop_interval"))

        assertEquals(Hysteria2Endpoint("1.2.3.4", 8443), endpoint("hy2://pass@1.2.3.4:8443?sni=x#name"))
        assertEquals(Hysteria2Endpoint("1.2.3.4", 1), endpoint("hysteria2://pass@1.2.3.4:1"))
        assertEquals(Hysteria2Endpoint("1.2.3.4", 65535), endpoint("hysteria2://pass@1.2.3.4:65535#name"))
        assertEquals(8443, portFields(endpoint("hysteria2://pass@1.2.3.4:8443")!!).getInt("server_port"))
    }

    @Test
    fun hysteria2WithoutPortDialsDefault443() {
        for (link in listOf(
            "hysteria2://pass@example.com",
            "hysteria2://pass@example.com/?sni=x",
            "hysteria2://pass@example.com?sni=x",
            "hysteria2://pass@example.com#name",
            "hysteria2://pass@example.com:/?sni=x",
        )) {
            assertEquals(link, Hysteria2Endpoint("example.com", 443), endpoint(link))
        }
    }

    @Test
    fun hysteria2AnyRangeOrSeveralPortsHopInLinkOrder() {
        assertEquals(listOf("20000:40000"), endpoint("hysteria2://pass@h.example:20000-40000/?sni=x")!!.serverPorts)
        assertEquals(listOf("8443:8443"), endpoint("hysteria2://pass@h.example:8443-8443")!!.serverPorts)
        assertEquals(listOf("9443:9443", "8443:8443"), endpoint("hysteria2://pass@h.example:9443,8443")!!.serverPorts)
        assertEquals(listOf("1:65535"), endpoint("hysteria2://pass@h.example:1-65535#name")!!.serverPorts)
        assertEquals(listOf("40000:50000", "443:443"), endpoint("hysteria2://pass@h.example:40000-50000,443")!!.serverPorts)
    }

    @Test
    fun hysteria2Ipv6HostLosesBracketsAndKeepsItsPorts() {
        assertEquals(
            Hysteria2Endpoint("2001:db8::1", 8443, listOf("8443:8443", "9443:9443")),
            endpoint("hysteria2://pass@[2001:db8::1]:8443,9443/?sni=example.net"),
        )
        assertEquals(Hysteria2Endpoint("2001:db8::1", 8443), endpoint("hysteria2://pass@[2001:db8::1]:8443#name"))
        assertEquals(Hysteria2Endpoint("2001:db8::1", 443), endpoint("hysteria2://pass@[2001:db8::1]/?sni=x"))
        assertEquals(Hysteria2Endpoint("2001:db8::1", 443), endpoint("hysteria2://pass@[2001:db8::1]"))
    }

    @Test
    fun hysteria2BrokenPortListFallsBackTo443() {
        for (ports in listOf(
            "8443,,9443", ",8443", "8443,", "84a3", "8443;9443", "8443, 9443", "+443", "-443",
            "30000-20000", "0", "65536", "1-65536", "0-100", "8443-", "-8443", "20000--40000",
            "99999999999",
        )) {
            assertEquals(ports, Hysteria2Endpoint("hy.example.net", 443), endpoint("hysteria2://pass@hy.example.net:$ports/?sni=x"))
        }
        assertEquals(Hysteria2Endpoint("2001:db8::1", 443), endpoint("hysteria2://pass@[2001:db8::1]:8443,,9443"))
    }

    @Test
    fun hysteria2PasswordQueryAndFragmentStayOutOfTheHost() {
        assertEquals(Hysteria2Endpoint("hy.example.net", 443), endpoint("hysteria2://p@ss:w0rd@hy.example.net:443/"))
        assertEquals(
            Hysteria2Endpoint("hy.example.net", 8443, listOf("8443:8443", "9443:9443")),
            endpoint("hysteria2://pass@hy.example.net:8443,9443#a/b?c@d:1"),
        )
    }

    @Test
    fun hysteria2WithoutHostIsRejected() {
        assertNull(endpoint("hysteria2://pass@:443"))
        assertNull(endpoint("hysteria2://pass@/?sni=x"))
        assertNull(endpoint("hysteria2://pass@[2001:db8::1:443"))
    }

    @Test
    fun hysteria2UnbracketedIpv6WithLetterGroupStaysWhole() {
        assertEquals(Hysteria2Endpoint("2001:db8::abcd", 443), ProxyParser.parseHysteria2Endpoint("hysteria2://p@2001:db8::abcd"))
        assertEquals(Hysteria2Endpoint("host", 443), ProxyParser.parseHysteria2Endpoint("hysteria2://p@host:/?sni=x"))
        assertEquals(Hysteria2Endpoint("::ffff:1.2.3.4", 443), ProxyParser.parseHysteria2Endpoint("hysteria2://p@::ffff:1.2.3.4/?sni=x"))
    }

    @Test
    fun oneUnparsableLineDoesNotDropTheSubscription() {
        // On the JVM android.net.Uri is a stub, so the vless line throws inside
        // parse(); the tt:// line does not use Uri and must survive.
        val proxies = ProxyParser.parseSubscription("vless://uuid@host:443?security=reality#A\ntt://?$payload#DE11T")
        assertEquals(listOf("DE11T"), proxies.map { it.tag })
    }
}
