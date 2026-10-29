package com.vpn4tv.app.converter

import org.junit.Assert.assertEquals
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
}
