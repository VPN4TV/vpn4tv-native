package com.vpn4tv.app.bg

import com.vpn4tv.app.converter.ConfigGenerator
import com.vpn4tv.app.converter.ConfigUpgrade
import com.vpn4tv.app.converter.ProxyConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The credentials of the bridges, put in at start: the per-process UUID on
 * the xray bridge's VLESS inbounds and on sing-box's vless outbounds to it,
 * the password pair on the socks ones. Keys and hosts below are made up.
 */
class BridgeAuthTest {
    private fun resource(path: String): String = requireNotNull(javaClass.getResource("/config-upgrade/$path")).readText()

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.outbound(tag: String) = getJSONArray("outbounds").objects().single { it.optString("tag") == tag }

    private fun xray(tag: String) = ProxyConfig(
        tag = tag, type = "vless", server = "192.0.2.40", serverPort = 443, outbound = JSONObject(),
        xrayOutbound = JSONObject().put("protocol", "vless").put("settings", JSONObject().put("vnext", JSONArray().put(
            JSONObject().put("address", "192.0.2.40").put("port", 443)
                .put("users", JSONArray().put(JSONObject().put("id", "5783a3e7-e373-51cd-8642-c83782b807c5").put("encryption", "none"))),
        ))),
    )

    private fun outline(tag: String) = ProxyConfig(
        tag = tag, type = "shadowsocks", server = "192.0.2.10", serverPort = 8388, outbound = JSONObject(),
        outlineUrl = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpzZWNyZXQ@192.0.2.10:8388/?outline=1&prefix=%16%03%01#OL",
    )

    /** A vless proxy of the profile's own, on a real server. */
    private fun nativeVless(tag: String) = ProxyConfig(
        tag = tag, type = "vless", server = "192.0.2.60", serverPort = 443,
        outbound = JSONObject().put("type", "vless").put("tag", tag).put("server", "192.0.2.60").put("server_port", 443)
            .put("uuid", "a3482e88-686a-4a58-8126-99c9df64b7bf").put("packet_encoding", "xudp"),
    )

    @Test
    fun theUuidIsRandomStableAndNeverThePlaceholder() {
        val parsed = UUID.fromString(BridgeAuth.uuid)
        assertEquals(4, parsed.version())
        assertEquals(BridgeAuth.uuid, parsed.toString())
        assertNotEquals(BridgeAuth.UUID_PLACEHOLDER, BridgeAuth.uuid)
        // One per process: the reload path keeps the running bridge.
        assertSame(BridgeAuth.uuid, BridgeAuth.uuid)
        assertEquals(UUID(0, 0).toString(), BridgeAuth.UUID_PLACEHOLDER)
    }

    @Test
    fun theXraySidecarGetsTheUuidAsItsOnlyClient() {
        val generated = ConfigGenerator.generateFull(listOf(xray("X1"), xray("X2")), portBase = 30000)
        val out = JSONObject(BridgeAuth.applyToXray(generated.xrayJson!!))
        val inbounds = out.getJSONArray("inbounds").objects()
        assertEquals(2, inbounds.size)
        for (inbound in inbounds) {
            assertEquals("vless", inbound.getString("protocol"))
            val settings = inbound.getJSONObject("settings")
            val clients = settings.getJSONArray("clients").objects()
            assertEquals(1, clients.size)
            assertEquals(BridgeAuth.uuid, clients[0].getString("id"))
            assertFalse(clients[0].has("flow"))
            assertEquals("none", settings.getString("decryption"))
        }
        assertFalse(out.toString().contains(BridgeAuth.UUID_PLACEHOLDER))
        // The outbound to the server keeps its own id.
        assertTrue(out.toString().contains("5783a3e7-e373-51cd-8642-c83782b807c5"))
    }

    @Test
    fun everyClientOfAVlessInboundIsReplaced() {
        // Whatever the sidecar lists (a stray id, a flow), only the UUID gets in.
        val sidecar = JSONObject(ConfigGenerator.generateFull(listOf(xray("X1")), portBase = 30000).xrayJson!!)
        sidecar.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings").put("flow", "xtls-rprx-vision")
            .getJSONArray("clients").put(JSONObject().put("id", "0f9a8b7c-6d5e-4f3a-9b2c-1d0e9f8a7b6c"))
        val settings = JSONObject(BridgeAuth.applyToXray(sidecar.toString()))
            .getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings")
        assertEquals(listOf(BridgeAuth.uuid), settings.getJSONArray("clients").objects().map { it.getString("id") })
        assertFalse(settings.has("flow"))
    }

    @Test
    fun anOldSocksSidecarKeepsThePasswordAndNoUdp() {
        val out = JSONObject(BridgeAuth.applyToXray(resource("xray-only.json.xray.json")))
        val settings = out.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings")
        assertEquals("password", settings.getString("auth"))
        assertFalse(settings.getBoolean("udp"))
        val account = settings.getJSONArray("accounts").getJSONObject(0)
        assertEquals(BridgeAuth.username, account.getString("user"))
        assertEquals(BridgeAuth.password, account.getString("pass"))
        assertFalse(settings.has("clients"))
    }

    @Test
    fun singboxGetsTheUuidOnTheOutboundToTheXrayBridgeOnly() {
        val generated = ConfigGenerator.generateFull(listOf(xray("XH"), outline("OL"), nativeVless("VL")), portBase = 30000)
        val out = JSONObject(BridgeAuth.applyToSingbox(generated.singboxJson))
        val xh = out.outbound("XH")
        assertEquals("vless", xh.getString("type"))
        assertEquals(BridgeAuth.uuid, xh.getString("uuid"))
        assertFalse(xh.has("username"))
        assertFalse(xh.has("password"))
        // The socks outbound to the Outline bridge gets the pair, as before.
        val ol = out.outbound("OL")
        assertEquals(BridgeAuth.username, ol.getString("username"))
        assertEquals(BridgeAuth.password, ol.getString("password"))
        // A vless proxy of the profile's own is not a bridge.
        assertEquals("a3482e88-686a-4a58-8126-99c9df64b7bf", out.outbound("VL").getString("uuid"))
        assertFalse(out.toString().contains(BridgeAuth.UUID_PLACEHOLDER))
    }

    @Test
    fun aVlessOutboundToTheBridgeHostWithItsOwnIdIsLeftAlone() {
        val config = JSONObject().put("outbounds", JSONArray().put(
            JSONObject().put("type", "vless").put("tag", "mine").put("server", "127.0.0.127").put("server_port", 1080)
                .put("uuid", "0f9a8b7c-6d5e-4f3a-9b2c-1d0e9f8a7b6c"),
        )).toString()
        assertEquals(config, BridgeAuth.applyToSingbox(config))
        // Not JSON: not ours, back as it came.
        assertEquals("{oops", BridgeAuth.applyToSingbox("{oops"))
    }

    @Test
    fun anUpgradedProfileGetsTheSameCredentialsAsANewOne() {
        val upgraded = ConfigUpgrade.upgrade(resource("xray-only.json"), ConfigUpgrade.Sidecars(xray = resource("xray-only.json.xray.json")))
        val singbox = JSONObject(BridgeAuth.applyToSingbox(upgraded.json!!))
        val xray = JSONObject(BridgeAuth.applyToXray(upgraded.xray!!))
        val outbound = singbox.outbound("XH 1")
        val inbound = xray.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(outbound.getInt("server_port"), inbound.getInt("port"))
        assertEquals(outbound.getString("uuid"), inbound.getJSONObject("settings").getJSONArray("clients").getJSONObject(0).getString("id"))
        assertEquals(BridgeAuth.uuid, outbound.getString("uuid"))
    }

    @Test
    fun logLinesGiveNeitherThePasswordNorTheUuid() {
        val generated = ConfigGenerator.generateFull(listOf(xray("XH"), outline("OL")), portBase = 30000)
        val singbox = BridgeAuth.applyToSingbox(generated.singboxJson)
        val xray = BridgeAuth.applyToXray(generated.xrayJson!!)
        for (text in listOf(singbox, xray)) {
            val redacted = BridgeAuth.redact(text)
            assertFalse(redacted.contains(BridgeAuth.uuid))
            assertFalse(redacted.contains(BridgeAuth.password))
        }
        assertTrue(BridgeAuth.redact(xray).contains("<bridge-uuid>"))
        assertTrue(BridgeAuth.redact(singbox).contains("<bridge-password>"))
    }
}
