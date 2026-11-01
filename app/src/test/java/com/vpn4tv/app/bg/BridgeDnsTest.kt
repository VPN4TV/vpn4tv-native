package com.vpn4tv.app.bg

import com.vpn4tv.app.utils.DnsProviders
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DNS servers handed to the Outline and AmneziaWG bridges at start. */
class BridgeDnsTest {
    private fun resource(path: String): String = requireNotNull(javaClass.getResource("/config-upgrade/$path")).readText()

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    @Test
    fun theServersAreForeignIpsInPreferenceOrder() {
        val servers = DnsProviders.bridgeDnsServers
        assertEquals(listOf("9.9.9.9", "149.112.112.112", "8.8.8.8", "1.1.1.1"), servers)
        val lastResort = DnsProviders.PROVIDERS.filter { it.lastResort }.flatMap { it.udpIps }
        assertTrue(lastResort.isNotEmpty())
        assertTrue(servers.none { it in lastResort })
        // IP addresses only: a name would need the resolver this replaces.
        assertTrue(servers.all { it.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) })
    }

    @Test
    fun everySidecarGetsThemAtTheTopNextToThePassword() {
        for (sidecar in listOf("mixed.json.outline.json", "mixed.json.wg.json", "outline-udpdns.json.outline.json")) {
            val text = resource(sidecar)
            val out = JSONObject(BridgeDns.applyToEndpoints(BridgeAuth.applyToEndpoints(text)))
            assertEquals(sidecar, DnsProviders.bridgeDnsServers, out.getJSONArray("dns").strings())
            val endpoints = out.getJSONArray("endpoints")
            val original = JSONObject(text).getJSONArray("endpoints")
            assertEquals(original.length(), endpoints.length())
            for (i in 0 until endpoints.length()) {
                val endpoint = endpoints.getJSONObject(i)
                assertFalse(endpoint.has("dns"))
                assertEquals(BridgeAuth.password, endpoint.getString("password"))
                assertEquals(original.getJSONObject(i).getInt("port"), endpoint.getInt("port"))
            }
        }
    }

    @Test
    fun aSidecarThatNamesItsOwnServersKeepsThem() {
        val text = """{"dns":["192.0.2.53"],"endpoints":[{"url":"ss://x@192.0.2.10:8388","port":31000}]}"""
        assertEquals(text, BridgeDns.applyToEndpoints(text))
        assertEquals(text, BridgeDns.applyToEndpoints(text, servers = emptyList()))
        val bare = """{"endpoints":[]}"""
        assertEquals(bare, BridgeDns.applyToEndpoints(bare, servers = emptyList()))
    }
}
