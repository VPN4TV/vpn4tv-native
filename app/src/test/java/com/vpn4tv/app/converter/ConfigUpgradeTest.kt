package com.vpn4tv.app.converter

import com.vpn4tv.app.bg.RussiaDirect
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
 * The one-time upgrade of saved configs. The fixtures in resources/config-upgrade
 * are what the 5.3.x generator wrote to disk (config and sidecars); upgraded,
 * they must match what the current generator writes for the same proxies,
 * and an upgraded or new config must need nothing more. Keys and hosts are
 * made up.
 */
class ConfigUpgradeTest {
    private val savedDns = ProxyParser.lastDns

    @After
    fun restoreDns() {
        ProxyParser.lastDns = savedDns
    }

    // The proxies of the fixtures, as the harness that wrote them defined them.
    private fun outline(tag: String) = ProxyConfig(
        tag = tag, type = "shadowsocks", server = "192.0.2.10", serverPort = 8388, outbound = JSONObject(),
        outlineUrl = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpzZWNyZXQ@192.0.2.10:8388/?outline=1&prefix=%16%03%01#OL",
    )

    private fun outlineDynamic(tag: String) = ProxyConfig(
        tag = tag, type = "shadowsocks", server = "", serverPort = 0, outbound = JSONObject(),
        outlineDynamicUrl = "https://keys.example.net/ssconf/abc",
    )

    private fun awg(tag: String) = ProxyConfig(
        tag = tag, type = "wireguard", server = "192.0.2.20", serverPort = 51820, outbound = JSONObject(),
        awgIni = "[Interface]\nPrivateKey = x\nAddress = 10.8.1.2/32\n\n[Peer]\nPublicKey = y\nEndpoint = 192.0.2.20:51820\n",
    )

    private fun olcrtc(tag: String) = ProxyConfig(
        tag = tag, type = "olcrtc", server = "", serverPort = 0, outbound = JSONObject(), olcrtcUrl = "olcrtc://example",
    )

    private fun trustTunnel(tag: String) = ProxyConfig(
        tag = tag, type = "trusttunnel", server = "", serverPort = 0, outbound = JSONObject(), ttUrl = "tt://?AAEB",
    )

    private fun native(tag: String) = ProxyConfig(
        tag = tag, type = "shadowsocks", server = "192.0.2.30", serverPort = 8388,
        outbound = JSONObject().put("type", "shadowsocks").put("tag", tag).put("server", "192.0.2.30")
            .put("server_port", 8388).put("method", "aes-256-gcm").put("password", "secret"),
    )

    private fun xray(tag: String) = ProxyConfig(
        tag = tag, type = "vless", server = "192.0.2.40", serverPort = 443, outbound = JSONObject(),
        xrayOutbound = JSONObject().put("protocol", "vless"),
    )

    private val defaultDns = SubscriptionDns(remoteDns = "https://8.8.8.8/dns-query", directDns = "9.9.9.9")

    private class Fixture(val proxies: () -> List<ProxyConfig>, val dns: SubscriptionDns, val fakeDnsDiffers: Boolean = false)

    private val fixtures = linkedMapOf(
        "outline-only" to Fixture({ listOf(outline("OL 1")) }, defaultDns),
        // The old generators forgot dynamic keys when they turned FakeDNS off for
        // bridge-only profiles; the upgrade leaves FakeDNS as it is.
        "outline-dynamic-only" to Fixture({ listOf(outlineDynamic("OLD 1")) }, defaultDns, fakeDnsDiffers = true),
        "awg-only" to Fixture({ listOf(awg("AWG 1")) }, defaultDns),
        "olcrtc-only" to Fixture({ listOf(olcrtc("OLC 1")) }, defaultDns),
        "tt-only" to Fixture({ listOf(trustTunnel("TT 1")) }, defaultDns),
        "xray-only" to Fixture({ listOf(xray("XH 1")) }, defaultDns),
        "native-only" to Fixture({ listOf(native("SS 1")) }, defaultDns),
        "xray-native" to Fixture({ listOf(xray("XH 2"), native("SS 2")) }, defaultDns),
        "mixed" to Fixture({ listOf(native("SS 1"), outline("OL 2"), awg("AWG 2"), olcrtc("OLC 2"), trustTunnel("TT 2")) }, defaultDns),
        "bridges-mixed" to Fixture({ listOf(outline("OL 3"), awg("AWG 3"), olcrtc("OLC 3"), trustTunnel("TT 3")) }, defaultDns),
        "tt-olcrtc-udpdns" to Fixture(
            { listOf(trustTunnel("TT 4"), olcrtc("OLC 4")) },
            SubscriptionDns(remoteDns = "udp://1.1.1.1", directDns = "9.9.9.9"),
        ),
        "outline-udpdns" to Fixture(
            { listOf(outline("OL 5"), outlineDynamic("OLD 5")) },
            SubscriptionDns(remoteDns = "quic://dns.example.net", directDns = "9.9.9.9"),
            fakeDnsDiffers = true,
        ),
    )

    private fun resource(path: String): String? = javaClass.getResource("/config-upgrade/$path")?.readText()

    private fun before(name: String): String = requireNotNull(resource("$name.json")) { "fixture $name" }

    private fun sidecarsOf(name: String) = ConfigUpgrade.Sidecars(
        xray = resource("$name.json.xray.json"),
        outline = resource("$name.json.outline.json"),
        wg = resource("$name.json.wg.json"),
        olcrtc = resource("$name.json.olcrtc.json"),
        tt = resource("$name.json.tt.json"),
    )

    private fun generated(fixture: Fixture): ConfigGenerator.Result {
        ProxyParser.lastDns = fixture.dns
        return ConfigGenerator.generateFull(fixture.proxies(), portBase = 30000)
    }

    private fun sidecarsOf(result: ConfigGenerator.Result) =
        ConfigUpgrade.Sidecars(result.xrayJson, result.outlineJson, result.wgJson, result.olcrtcJson, result.ttJson)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.outbound(tag: String) = getJSONArray("outbounds").objects().single { it.optString("tag") == tag }
    private fun JSONObject.routeRules() = getJSONObject("route").getJSONArray("rules").objects()
    private fun JSONObject.dnsRemote() =
        getJSONObject("dns").getJSONArray("servers").objects().single { it.optString("tag") == "dns-remote" }
    private fun udpRules(config: JSONObject) = config.routeRules().filter { it.optString("network") == "udp" }

    /** Order-insensitive for object keys, as the core reads JSON. */
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { "\"$it\":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    /** FakeDNS and the DNS strategy out, as BoxService.stripFakeDns takes them out. */
    private fun withoutFakeDns(text: String): JSONObject {
        val root = JSONObject(text)
        val dns = root.getJSONObject("dns")
        val servers = dns.getJSONArray("servers")
        for (i in servers.length() - 1 downTo 0) if (servers.getJSONObject(i).optString("tag") == "dns-fakeip") servers.remove(i)
        val rules = dns.getJSONArray("rules")
        for (i in rules.length() - 1 downTo 0) {
            val rule = rules.getJSONObject(i)
            if (rule.optString("server") == "dns-fakeip" || rule.optString("action") == "reject") rules.remove(i)
        }
        dns.remove("strategy")
        root.remove("experimental")
        return root
    }

    @Test
    fun upgradedOldConfigsMatchTheNewGenerator() {
        for ((name, fixture) in fixtures) {
            val old = before(name)
            val result = ConfigUpgrade.upgrade(old, sidecarsOf(name))
            val upgraded = result.json ?: old
            val generated = generated(fixture)
            val expected = generated.singboxJson
            if (fixture.fakeDnsDiffers) {
                assertEquals(name, canonical(withoutFakeDns(expected)), canonical(withoutFakeDns(upgraded)))
            } else {
                assertEquals(name, canonical(JSONObject(expected)), canonical(JSONObject(upgraded)))
            }
            // The xray sidecar too: the one the generator writes now.
            val oldXray = resource("$name.json.xray.json")
            if (oldXray == null) {
                assertNull(name, result.xray)
                assertNull(name, generated.xrayJson)
            } else {
                assertEquals(name, canonical(JSONObject(generated.xrayJson!!)), canonical(JSONObject(result.xray ?: oldXray)))
            }
        }
    }

    /** What a 5.4.0 release candidate wrote for xray-only: the 5.3.x shape, UDP refused last. */
    private fun xrayOnlyOfAReleaseCandidate(): String = JSONObject(before("xray-only")).apply {
        getJSONObject("route").getJSONArray("rules").put(ConfigGenerator.udpRejectRule())
    }.toString()

    private fun assertXrayBridge(outbound: JSONObject, port: Int) {
        assertEquals("vless", outbound.getString("type"))
        assertEquals("127.0.0.127", outbound.getString("server"))
        assertEquals(port, outbound.getInt("server_port"))
        assertEquals("lo", outbound.getString("bind_interface"))
        assertEquals(com.vpn4tv.app.bg.BridgeAuth.UUID_PLACEHOLDER, outbound.getString("uuid"))
        assertEquals("xudp", outbound.getString("packet_encoding"))
        for (key in listOf("network", "version", "username", "password", "udp_over_tcp")) assertFalse(key, outbound.has(key))
    }

    private fun assertVlessSidecar(text: String) {
        val root = JSONObject(text)
        val inbounds = root.getJSONArray("inbounds").objects()
        assertTrue(inbounds.isNotEmpty())
        for (inbound in inbounds) {
            assertEquals("vless", inbound.getString("protocol"))
            assertTrue(inbound.getString("tag").startsWith("vless-in-"))
            assertEquals("127.0.0.127", inbound.getString("listen"))
            val settings = inbound.getJSONObject("settings")
            assertEquals("none", settings.getString("decryption"))
            assertEquals(com.vpn4tv.app.bg.BridgeAuth.UUID_PLACEHOLDER, settings.getJSONArray("clients").getJSONObject(0).getString("id"))
            assertFalse(settings.has("udp"))
            assertFalse(settings.has("auth"))
        }
        val routed = root.getJSONObject("routing").getJSONArray("rules").objects().map { it.getJSONArray("inboundTag").getString(0) }
        assertEquals(inbounds.map { it.getString("tag") }, routed)
    }

    @Test
    fun xrayBecomesVlessWithUdpAndItsSidecarFollows() {
        for ((name, tag) in listOf("xray-only" to "XH 1", "xray-native" to "XH 2")) {
            val result = ConfigUpgrade.upgrade(before(name), sidecarsOf(name))
            val config = JSONObject(result.json!!)
            assertXrayBridge(config.outbound(tag), 30000)
            // UDP goes to the bridge: nothing refused.
            assertTrue("$name udp rules: ${udpRules(config)}", udpRules(config).isEmpty())
            assertEquals(listOf("sniff", "hijack-dns"), config.routeRules().map { it.optString("action") })
            // DoH already, TCP: stays.
            assertEquals("https", config.dnsRemote().getString("type"))
            assertVlessSidecar(result.xray!!)
            assertTrue(result.changes.toString(), result.changes.any { it.contains("VLESS to the xray bridge") })
            assertTrue(result.changes.toString(), result.changes.any { it.contains("xray bridge: VLESS inbounds") })
            // No keys in the log lines.
            assertTrue(result.changes.none { it.contains(com.vpn4tv.app.bg.BridgeAuth.UUID_PLACEHOLDER) })
        }
        // A UDP DNS server is asked over TCP through the bridge, as through the others.
        val udpDns = JSONObject(before("xray-only")).apply { dnsRemote().put("type", "udp").put("server", "1.1.1.1") }
        val config = JSONObject(ConfigUpgrade.upgrade(udpDns.toString(), sidecarsOf("xray-only")).json!!)
        assertEquals("tcp", config.dnsRemote().getString("type"))
    }

    @Test
    fun theRefusalOfAReleaseCandidateGoesOnceXrayCarriesUdp() {
        val result = ConfigUpgrade.upgrade(xrayOnlyOfAReleaseCandidate(), sidecarsOf("xray-only"))
        val config = JSONObject(result.json!!)
        assertTrue(udpRules(config).isEmpty())
        assertTrue(result.changes.toString(), result.changes.any { it.contains("UDP refusal removed") })
        ProxyParser.lastDns = defaultDns
        assertEquals(canonical(JSONObject(generated(fixtures.getValue("xray-only")).singboxJson)), canonical(config))
        // xray with olcRTC: xray carries UDP, so nothing is refused either.
        val generated = ConfigGenerator.generateFull(listOf(xray("XH 7"), olcrtc("OLC 7")), portBase = 30000)
        assertTrue(udpRules(JSONObject(generated.singboxJson)).isEmpty())
    }

    @Test
    fun aPairLeftHalfDoneIsCompleted() {
        val full = ConfigUpgrade.upgrade(before("xray-only"), sidecarsOf("xray-only"))
        // The sidecar written, the config not.
        val configOnly = ConfigUpgrade.upgrade(before("xray-only"), ConfigUpgrade.Sidecars(xray = full.xray))
        assertNull(configOnly.xray)
        assertEquals(canonical(JSONObject(full.json!!)), canonical(JSONObject(configOnly.json!!)))
        // The config written, the sidecar not.
        val sidecarOnly = ConfigUpgrade.upgrade(full.json!!, sidecarsOf("xray-only"))
        assertNull(sidecarOnly.json)
        assertNull(sidecarOnly.skipped)
        assertEquals(full.xray, sidecarOnly.xray)
        assertEquals(listOf("xray bridge: VLESS inbounds"), sidecarOnly.changes)
        // Both written: nothing more.
        val done = ConfigUpgrade.upgrade(full.json!!, ConfigUpgrade.Sidecars(xray = full.xray))
        assertNull(done.json)
        assertNull(done.xray)
        assertTrue(done.changes.isEmpty())
    }

    @Test
    fun anXraySidecarIsUpgradedOnlyWithAConfigThatReachesIt() {
        // No xray outbound in the config: the sidecar is not touched (and the
        // outline outbound is not taken for xray).
        val outline = ConfigUpgrade.upgrade(before("outline-only"),
            ConfigUpgrade.Sidecars(outline = resource("outline-only.json.outline.json"), xray = resource("xray-only.json.xray.json")))
        assertNull(outline.xray)
        assertEquals("socks", JSONObject(outline.json!!).outbound("OL 1").getString("type"))
        // A port the xray sidecar does not serve: the socks outbound is no xray bridge.
        val elsewhere = JSONObject(before("xray-only")).apply { outbound("XH 1").put("server_port", 30005) }
        val result = ConfigUpgrade.upgrade(elsewhere.toString(), sidecarsOf("xray-only"))
        assertNull(result.json)
        assertNull(result.xray)
        assertEquals(ConfigUpgrade.NO_BRIDGE, result.skipped)
        // An xray sidecar whose inbound is neither SOCKS5 nor VLESS: no bridge either.
        val odd = resource("xray-only.json.xray.json")!!.replace("\"protocol\":\"socks\"", "\"protocol\":\"http\"")
        assertEquals(ConfigUpgrade.NO_BRIDGE, ConfigUpgrade.upgrade(before("xray-only"), ConfigUpgrade.Sidecars(xray = odd)).skipped)
    }

    @Test
    fun theXraySidecarUpgradeKeepsEverythingElse() {
        val old = JSONObject(resource("xray-only.json.xray.json")!!)
        val new = JSONObject(ConfigUpgrade.upgradeXraySidecar(old.toString())!!)
        assertEquals(canonical(old.getJSONArray("outbounds")), canonical(new.getJSONArray("outbounds")))
        assertEquals(canonical(old.getJSONObject("log")), canonical(new.getJSONObject("log")))
        val oldInbound = old.getJSONArray("inbounds").getJSONObject(0)
        val newInbound = new.getJSONArray("inbounds").getJSONObject(0)
        for (key in listOf("listen", "port", "sniffing")) assertEquals(key, canonical(oldInbound.get(key)), canonical(newInbound.get(key)))
        // An inboundTag given as a plain string is renamed too; a tag that is
        // not the old generator's stays, with its rule.
        val custom = JSONObject(old.toString()).apply {
            getJSONObject("routing").getJSONArray("rules").getJSONObject(0).put("inboundTag", "socks-in-0")
        }
        val renamed = JSONObject(ConfigUpgrade.upgradeXraySidecar(custom.toString())!!)
        assertEquals("vless-in-0", renamed.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getString("inboundTag"))
        val mine = JSONObject(old.toString().replace("socks-in-0", "mine"))
        val kept = JSONObject(ConfigUpgrade.upgradeXraySidecar(mine.toString())!!)
        assertEquals("mine", kept.getJSONArray("inbounds").getJSONObject(0).getString("tag"))
        assertEquals("mine", kept.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getJSONArray("inboundTag").getString(0))
        // Nothing to do, or not an xray config: null.
        assertNull(ConfigUpgrade.upgradeXraySidecar(new.toString()))
        for (text in listOf(null, "", "{", "[]", "{\"inbounds\": 7}")) assertNull(text, ConfigUpgrade.upgradeXraySidecar(text))
    }

    @Test
    fun whatTheUpgradeChanges() {
        // Outline only: UDP through the bridge, no UDP rule, DNS through the tunnel.
        val outline = JSONObject(ConfigUpgrade.upgrade(before("outline-only"), sidecarsOf("outline-only")).json!!)
        val ol = outline.outbound("OL 1")
        assertFalse(ol.has("network"))
        assertEquals(2, ol.getJSONObject("udp_over_tcp").getInt("version"))
        assertTrue(ol.getJSONObject("udp_over_tcp").getBoolean("enabled"))
        assertTrue(udpRules(outline).isEmpty())
        assertEquals("select", outline.dnsRemote().getString("detour"))

        // AmneziaWG keeps its IPv4-only resolution and gets UDP too.
        val awg = JSONObject(ConfigUpgrade.upgrade(before("awg-only"), sidecarsOf("awg-only")).json!!).outbound("AWG 1")
        assertFalse(awg.has("network"))
        assertEquals("ipv4_only", awg.getString("domain_strategy"))

        // olcRTC + TrustTunnel with UDP DNS: TrustTunnel carries plain UDP, so nothing is
        // refused; DNS over TCP through the tunnel; olcRTC stays TCP-only.
        val ttOlc = ConfigUpgrade.upgrade(before("tt-olcrtc-udpdns"), sidecarsOf("tt-olcrtc-udpdns"))
        val config = JSONObject(ttOlc.json!!)
        assertEquals(listOf("sniff", "hijack-dns"), config.routeRules().map { it.optString("action") })
        assertTrue("tt-olcrtc udp rules: ${udpRules(config)}", udpRules(config).isEmpty())
        assertEquals("tcp", config.dnsRemote().getString("type"))
        assertEquals("select", config.dnsRemote().getString("detour"))
        assertFalse("TT 4: ${config.outbound("TT 4")}", config.outbound("TT 4").has("network"))
        assertFalse(config.outbound("TT 4").has("udp_over_tcp"))
        assertEquals("tcp", config.outbound("OLC 4").getString("network"))
        assertTrue(ttOlc.changes.toString(), ttOlc.changes.any { it.contains("udp → direct") })
        assertTrue("changes: ${ttOlc.changes}", ttOlc.changes.any { it.contains("TrustTunnel's relay") })

        // olcRTC only: no proxy carries UDP, so UDP is refused, last.
        val olc = JSONObject(ConfigUpgrade.upgrade(before("olcrtc-only"), sidecarsOf("olcrtc-only")).json!!)
        assertEquals(ConfigGenerator.udpRejectRule().toString(), olc.routeRules().last().toString())

        // xray only: VLESS to the bridge, which carries UDP now; nothing refused.
        val xray = JSONObject(ConfigUpgrade.upgrade(before("xray-only"), sidecarsOf("xray-only")).json!!)
        assertTrue(udpRules(xray).isEmpty())
        assertEquals("vless", xray.outbound("XH 1").getString("type"))
        assertFalse(xray.outbound("XH 1").has("network"))
        assertEquals("https", xray.dnsRemote().getString("type"))

        // Mixed with a native proxy: nothing refused, bridges carry UDP where they can.
        val mixed = JSONObject(ConfigUpgrade.upgrade(before("mixed"), sidecarsOf("mixed")).json!!)
        assertTrue("mixed udp rules: ${udpRules(mixed)}", udpRules(mixed).none { it.optString("action") == "reject" })
        assertFalse(mixed.outbound("OL 2").has("network"))
        assertFalse(mixed.outbound("AWG 2").has("network"))
        assertEquals("tcp", mixed.outbound("OLC 2").getString("network"))
        assertFalse("TT 2: ${mixed.outbound("TT 2")}", mixed.outbound("TT 2").has("network"))
        // FakeDNS stays.
        assertTrue(mixed.has("experimental"))
        // With FakeDNS and TrustTunnel, UDP names are resolved on the route right after hijack-dns:
        // the TrustTunnel relay takes addresses only.
        if (mixed.getJSONObject("dns").getJSONArray("servers").objects().any { it.optString("type") == "fakeip" }) {
            val actions = mixed.routeRules().map { it.optString("action") }
            assertEquals(listOf("sniff", "hijack-dns", "resolve"), actions.take(3))
            assertEquals(ConfigGenerator.udpResolveRule().toString(), mixed.routeRules()[2].toString())
        }
    }

    @Test
    fun anUpgradedConfigNeedsNothingMore() {
        for (name in fixtures.keys) {
            val first = ConfigUpgrade.upgrade(before(name), sidecarsOf(name))
            val once = first.json ?: continue
            val sidecars = sidecarsOf(name).let { ConfigUpgrade.Sidecars(first.xray ?: it.xray, it.outline, it.wg, it.olcrtc, it.tt) }
            val twice = ConfigUpgrade.upgrade(once, sidecars)
            assertNull(name, twice.json)
            assertNull(name, twice.xray)
            assertTrue(name, twice.changes.isEmpty())
            assertNull(name, twice.skipped)
        }
    }

    @Test
    fun aNewConfigNeedsNoUpgrade() {
        for ((name, fixture) in fixtures) {
            val result = generated(fixture)
            val upgrade = ConfigUpgrade.upgrade(result.singboxJson, sidecarsOf(result))
            assertNull(name, upgrade.json)
            assertNull(name, upgrade.xray)
            assertTrue(name, upgrade.changes.isEmpty())
        }
    }

    @Test
    fun configsWithoutBridgesAreLeftAlone() {
        val result = ConfigUpgrade.upgrade(before("native-only"), sidecarsOf("native-only"))
        assertNull(result.json)
        assertEquals(ConfigUpgrade.NO_BRIDGE, result.skipped)
    }

    /** A sing-box config in our frame (Hiddify uses the same tags), with the old UDP rule. */
    private fun lookalike(): JSONObject = JSONObject(before("olcrtc-only")).apply {
        // Not generated by us: a user's own local SOCKS proxy on the bridge host.
        outbound("OLC 1").put("server_port", 1080)
    }

    @Test
    fun foreignConfigsAreLeftAlone() {
        val foreign = requireNotNull(javaClass.getResource("/russia-direct/foreign-singbox.json")).readText()
        for (sidecars in listOf(ConfigUpgrade.Sidecars(), sidecarsOf("mixed"))) {
            val result = ConfigUpgrade.upgrade(foreign, sidecars)
            assertNull(result.json)
            assertTrue(result.skipped!!.startsWith("not ours"))
        }
        // Our frame and a socks outbound to the bridge host, but no sidecar
        // serves its port: left alone, the user's UDP rule stays.
        for (sidecars in listOf(ConfigUpgrade.Sidecars(), sidecarsOf("olcrtc-only"), sidecarsOf("mixed"))) {
            val result = ConfigUpgrade.upgrade(lookalike().toString(), sidecars)
            assertNull(result.json)
            assertEquals(ConfigUpgrade.NO_BRIDGE, result.skipped)
        }
        // The same with a sidecar that serves the port: ours, upgraded.
        val served = ConfigUpgrade.Sidecars(olcrtc = """{"endpoints":[{"url":"olcrtc://example","port":1080}]}""")
        assertNotNull(ConfigUpgrade.upgrade(lookalike().toString(), served).json)
    }

    @Test
    fun aBridgeCountsAsOutlineOrAwgOnlyByItsOwnSidecar() {
        // The Outline port listed by the olcRTC sidecar instead (a broken
        // profile): it is not taken for Outline, so it stays TCP only.
        val sidecars = ConfigUpgrade.Sidecars(olcrtc = resource("outline-only.json.outline.json"))
        val config = JSONObject(ConfigUpgrade.upgrade(before("outline-only"), sidecars).json!!)
        assertEquals("tcp", config.outbound("OL 1").getString("network"))
        assertFalse(config.outbound("OL 1").has("udp_over_tcp"))
        // Nothing carries UDP then: refused, not sent direct.
        assertEquals(ConfigGenerator.udpRejectRule().toString(), config.routeRules().last().toString())
        assertTrue(udpRules(config).none { it.has("outbound") })
    }

    @Test
    fun brokenInputNeverThrows() {
        for (text in listOf("", "{", "[]", "null", "42", "{\"outbounds\": 5}", "{\"outbounds\": []}")) {
            val result = ConfigUpgrade.upgrade(text, sidecarsOf("outline-only"))
            assertNull(text, result.json)
            assertNotNull(text, result.skipped)
        }
        // Our frame, but dns-remote is not an object.
        val odd = JSONObject(before("outline-only"))
        odd.getJSONObject("dns").getJSONArray("servers").put(0, "dns-remote")
        assertNull(ConfigUpgrade.upgrade(odd.toString(), sidecarsOf("outline-only")).json)
        // Sidecars that are not JSON count as none.
        val garbage = ConfigUpgrade.Sidecars(outline = "{oops", wg = "", olcrtc = "[]", tt = "null", xray = "{\"inbounds\": 7}")
        val result = ConfigUpgrade.upgrade(before("outline-only"), garbage)
        assertNull(result.json)
        assertEquals(ConfigUpgrade.NO_BRIDGE, result.skipped)
    }

    @Test
    fun russiaDirectKeepsTheRefusalLastAfterTheUpgrade() {
        val sets = RussiaDirect.Sets(geosite = "/data/geosite-category-ru.srs", geoip = "/data/geoip-ru.srs")
        for (name in listOf("olcrtc-only")) {
            val upgraded = ConfigUpgrade.upgrade(before(name), sidecarsOf(name)).json!!
            for (options in listOf(RussiaDirect.Options(sets = sets), RussiaDirect.Options(offReason = "user", sets = sets))) {
                val out = JSONObject(RussiaDirect.apply(upgraded, options).json)
                val rules = out.routeRules()
                assertEquals(name, ConfigGenerator.udpRejectRule().toString(), rules.last().toString())
                assertEquals(name, 1, udpRules(out).size)
                assertTrue(name, rules.size > 3)
            }
        }
        // xray only carries UDP now: Russia direct adds its rules, nothing refuses UDP.
        val xray = ConfigUpgrade.upgrade(before("xray-only"), sidecarsOf("xray-only")).json!!
        for (options in listOf(RussiaDirect.Options(sets = sets), RussiaDirect.Options(offReason = "user", sets = sets))) {
            val out = JSONObject(RussiaDirect.apply(xray, options).json)
            assertTrue(out.routeRules().none { RussiaDirect.isUdpReject(it) })
            assertTrue(out.routeRules().size > 2)
        }
    }

    @Test
    fun aRefusalThatIsNotLastMovesLast() {
        val upgraded = JSONObject(ConfigUpgrade.upgrade(before("olcrtc-only"), sidecarsOf("olcrtc-only")).json!!)
        upgraded.getJSONObject("route").getJSONArray("rules").put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        val result = ConfigUpgrade.upgrade(upgraded.toString(), sidecarsOf("olcrtc-only"))
        val config = JSONObject(result.json!!)
        assertEquals(ConfigGenerator.udpRejectRule().toString(), config.routeRules().last().toString())
        assertEquals(1, udpRules(config).size)
        assertTrue(result.changes.toString(), result.changes.any { it.contains("moved last") })
    }
}
