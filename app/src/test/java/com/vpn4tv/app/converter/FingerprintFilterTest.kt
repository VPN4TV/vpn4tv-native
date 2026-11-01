package com.vpn4tv.app.converter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintFilterTest {
    private fun proxy(tag: String, fp: String?) = ProxyConfig(
        tag = tag, type = "vless", server = "192.0.2.1", serverPort = 443,
        outbound = JSONObject().put("type", "vless").put("tag", tag).apply {
            if (fp != null) put("tls", JSONObject().put("enabled", true).put("utls", JSONObject().put("enabled", true).put("fingerprint", fp)))
        },
    )

    @Test
    fun knownFingerprintsPassUnknownOnesDoNot() {
        for (fp in listOf("chrome", "Chrome", "", "edge", "hellochrome_120", "HelloChrome_120", "hellochrome_120_pq", "hellofirefox_120", "randomized")) {
            assertTrue(fp, ProxyParser.knownFingerprint(proxy("A", fp)))
        }
        assertTrue("no tls", ProxyParser.knownFingerprint(proxy("A", null)))
        assertFalse(ProxyParser.knownFingerprint(proxy("C", "netscape_4")))
    }

    @Test
    fun theFingerprintIsNeverRewritten() {
        val p = proxy("B", "hellochrome_120")
        ProxyParser.knownFingerprint(p)
        assertEquals("hellochrome_120", p.outbound.getJSONObject("tls").getJSONObject("utls").getString("fingerprint"))
    }
}
