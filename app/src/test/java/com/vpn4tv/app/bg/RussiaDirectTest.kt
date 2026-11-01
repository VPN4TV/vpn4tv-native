package com.vpn4tv.app.bg

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RussiaDirect.apply, JSON in → JSON out. The fixtures in resources/russia-direct are what the app's
 * own parser, ConfigGenerator and BoxService transforms produce from made-up links (the harness in
 * the audit folder); keys and hosts in them are fake.
 *
 * Set RUSSIA_DIRECT_OUT to a directory to get every output written there for `sing-box check`.
 */
class RussiaDirectTest {
    private val sets = RussiaDirect.Sets(
        geosite = "/data/user/0/com.vpn4tv.hiddify/files/rule-set/geosite-category-ru.srs",
        geoip = "/data/user/0/com.vpn4tv.hiddify/files/rule-set/geoip-ru.srs",
    )
    private val on = RussiaDirect.Options(sets = sets)

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/russia-direct/$name.json")) { "fixture $name" }.readText()

    private fun apply(name: String, options: RussiaDirect.Options): Pair<JSONObject, RussiaDirect.Result> {
        val input = fixture(name)
        val result = RussiaDirect.apply(input, options)
        System.getenv("RUSSIA_DIRECT_OUT")?.let { dir ->
            File(dir).mkdirs()
            val suffix = when {
                options.offReason != null -> "off-${options.offReason}"
                options.sets == null -> "suffixes"
                options.dnsRuFallback -> "dns-direct"
                else -> "on"
            }
            File(dir, "$name.$suffix.json").writeText(JSONObject(result.json).toString(2))
        }
        return JSONObject(result.json) to result
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    private fun JSONObject.dnsServers() = getJSONObject("dns").getJSONArray("servers").objects()
    private fun JSONObject.dnsRules() = getJSONObject("dns").optJSONArray("rules")?.objects().orEmpty()
    private fun JSONObject.routeRules() = getJSONObject("route").getJSONArray("rules").objects()

    /** Order-insensitive for object keys, as the core reads JSON. */
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { "\"$it\":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    private fun same(a: Any?, b: Any?): Boolean = canonical(a) == canonical(b)

    private fun isTorrent(rule: JSONObject) =
        rule.optJSONArray("protocol")?.strings() == listOf("bittorrent") && rule.optString("outbound") == "direct"

    private fun isOurLogical(rule: JSONObject) = rule.optString("type") == "logical" &&
        rule.getJSONArray("rules").objects().any { it.has("domain_suffix") && !it.optBoolean("invert") }

    /** M: the Russian names; with sets, geosite-category-ru or the suffixes. */
    private fun assertM(m: JSONObject, withSets: Boolean) {
        assertEquals(RussiaDirectLists.SUFFIXES, m.getJSONArray("domain_suffix").strings())
        if (withSets) assertEquals(listOf(RussiaDirectLists.GEOSITE.tag), m.getJSONArray("rule_set").strings())
        else assertFalse(m.has("rule_set"))
        assertFalse(m.optBoolean("invert"))
    }

    /** E: not an exception. */
    private fun assertE(e: JSONObject, extra: List<String> = emptyList()) {
        assertTrue(e.getBoolean("invert"))
        assertEquals((RussiaDirectLists.EXCEPTIONS + extra).distinct(), e.getJSONArray("domain_suffix").strings())
    }

    /** The two DNS rules and the route rules of a full block, and what must not change around them. */
    private fun assertBlock(input: JSONObject, out: JSONObject, withSets: Boolean, dnsServer: String = "dns-ru", extra: List<String> = emptyList()) {
        // DNS servers: as before, dns-ru last (TCP to Yandex, no detour); dns-remote stays first.
        val inServers = input.dnsServers()
        val outServers = out.dnsServers()
        assertEquals("dns-remote", outServers.first().getString("tag"))
        if (dnsServer == "dns-ru") {
            assertEquals(inServers.size + 1, outServers.size)
            for (i in inServers.indices) assertTrue(same(inServers[i], outServers[i]))
            val ru = outServers.last()
            assertEquals("dns-ru", ru.getString("tag"))
            assertEquals("tcp", ru.getString("type"))
            assertEquals("77.88.8.8", ru.getString("server"))
            assertFalse(ru.has("detour"))
        } else {
            assertEquals(inServers.size, outServers.size)
            assertTrue(outServers.none { it.optString("tag") == "dns-ru" })
        }

        // DNS rules: ours go right before the HTTPS/SVCB reject and the fakeip rule, or last.
        val inRules = input.dnsRules()
        val outRules = out.dnsRules()
        assertEquals(inRules.size + 2, outRules.size)
        val at = inRules.indexOfFirst { it.optString("server") == "dns-fakeip" || it.optString("action") == "reject" }
            .let { if (it < 0) inRules.size else it }
        for (i in 0 until at) assertTrue(same(inRules[i], outRules[i]))
        for (i in at until inRules.size) assertTrue(same(inRules[i], outRules[i + 2]))
        val empty = outRules[at]
        assertEquals("predefined", empty.getString("action"))
        assertFalse(empty.has("rcode"))
        val emptyParts = empty.getJSONArray("rules").objects()
        assertEquals(listOf("AAAA", "HTTPS", "SVCB"), emptyParts[0].getJSONArray("query_type").strings())
        assertM(emptyParts[1], withSets)
        assertE(emptyParts[2], extra)
        val resolve = outRules[at + 1]
        assertEquals("route", resolve.getString("action"))
        assertEquals(dnsServer, resolve.getString("server"))
        val resolveParts = resolve.getJSONArray("rules").objects()
        assertEquals(2, resolveParts.size)
        assertM(resolveParts[0], withSets)
        assertE(resolveParts[1], extra)

        // Route: everything as before, then torrents, names, addresses.
        val inRoute = input.routeRules()
        val outRoute = out.routeRules()
        assertEquals(inRoute.size + if (withSets) 3 else 2, outRoute.size)
        for (i in inRoute.indices) assertTrue(same(inRoute[i], outRoute[i]))
        assertTrue(isTorrent(outRoute[inRoute.size]))
        val names = outRoute[inRoute.size + 1]
        assertEquals("direct", names.getString("outbound"))
        val nameParts = names.getJSONArray("rules").objects()
        assertM(nameParts[0], withSets)
        assertE(nameParts[1], extra)
        val ruleSets = out.getJSONObject("route").optJSONArray("rule_set")
        if (withSets) {
            val addresses = outRoute[inRoute.size + 2]
            assertEquals("direct", addresses.getString("outbound"))
            val parts = addresses.getJSONArray("rules").objects()
            assertEquals(listOf(RussiaDirectLists.GEOIP.tag), parts[0].getJSONArray("rule_set").strings())
            assertE(parts[1], extra)
            assertEquals(4, parts[2].getInt("ip_version"))
            assertNotNull(ruleSets)
            val local = ruleSets!!.objects()
            assertEquals(listOf(RussiaDirectLists.GEOSITE.tag, RussiaDirectLists.GEOIP.tag), local.map { it.getString("tag") })
            assertTrue(local.all { it.getString("type") == "local" && it.getString("format") == "binary" })
            assertEquals(listOf(sets.geosite, sets.geoip), local.map { it.getString("path") })
        } else {
            assertNull(ruleSets)
            assertFalse(out.toString().contains(RussiaDirectLists.GEOIP.tag))
        }

        // The rest of the config is the input's.
        for (key in input.keys()) {
            if (key == "dns" || key == "route") continue
            assertTrue("$key changed", same(input.get(key), out.get(key)))
        }
        for (key in input.getJSONObject("route").keys()) {
            if (key == "rules") continue
            assertTrue("route.$key changed", same(input.getJSONObject("route").get(key), out.getJSONObject("route").get(key)))
        }
        assertNoRefusedFields(out)
    }

    /** What the 1.14 core refuses or warns about must not come from us. */
    private fun assertNoRefusedFields(out: JSONObject) {
        val text = out.toString()
        for (field in listOf("download_detour", "http_client", "initial_path", "domain_regex")) assertFalse(field, text.contains(field))
        for (rule in out.dnsRules()) {
            if (!isOurLogical(rule)) continue
            assertFalse(rule.has("strategy"))
            assertFalse(rule.toString().contains(RussiaDirectLists.GEOIP.tag))
        }
    }

    @Test
    fun ourProfileWithFakeDns() {
        val input = JSONObject(fixture("mixed-fakedns-on"))
        val (out, result) = apply("mixed-fakedns-on", on)
        assertEquals("on", result.state)
        assertTrue(result.applied)
        assertTrue(result.usesDnsRu)
        assertBlock(input, out, withSets = true)
        // dns-hosts first, the outbound rule next, then ours ahead of the HTTPS/SVCB reject.
        val rules = out.dnsRules()
        assertEquals("dns-hosts", rules[0].getString("server"))
        assertEquals("any", rules[1].getString("outbound"))
        assertEquals("predefined", rules[2].getString("action"))
        assertEquals("dns-ru", rules[3].getString("server"))
        assertEquals("reject", rules[4].getString("action"))
        assertEquals("dns-fakeip", rules[5].getString("server"))
        // The tun guard stays first, sniff and hijack-dns before the LAN bypass, ours last.
        val route = out.routeRules()
        assertEquals(listOf(OutsideVpnGuard.OUTSIDE), route[0].getJSONArray("package_name").strings())
        assertEquals("sniff", route[1].getString("action"))
        assertEquals("hijack-dns", route[2].getString("action"))
        assertTrue(route[3].toString().contains("ip_is_private"))
    }

    @Test
    fun fakeDnsOffPutsOurDnsRulesLast() {
        val input = JSONObject(fixture("mixed-fakedns-off"))
        val (out, result) = apply("mixed-fakedns-off", on)
        assertEquals("on", result.state)
        assertBlock(input, out, withSets = true)
        val rules = out.dnsRules()
        assertEquals("predefined", rules[rules.size - 2].getString("action"))
        assertEquals("dns-ru", rules.last().getString("server"))
        assertFalse(out.toString().contains("dns-fakeip"))
    }

    @Test
    fun proxyModeGetsTheBlockToo() {
        val input = JSONObject(fixture("mixed-proxy-mode"))
        val (out, result) = apply("mixed-proxy-mode", on)
        assertEquals("on", result.state)
        assertBlock(input, out, withSets = true)
        assertEquals("socks", out.getJSONArray("inbounds").getJSONObject(0).getString("type"))
        assertFalse(out.toString().contains("tun-in"))
    }

    @Test
    fun tcpBridgesOnlyKeepTheirUdpRuleAndStrategy() {
        val input = JSONObject(fixture("tcp-bridges"))
        val (out, _) = apply("tcp-bridges", on)
        assertBlock(input, out, withSets = true)
        assertEquals("ipv4_only", out.getJSONObject("dns").getString("strategy"))
        val route = out.routeRules()
        assertTrue(route.any { it.optString("network") == "udp" && it.optString("outbound") == "direct" })
        // Bridges stay socks outbounds on loopback with their own settings.
        val socks = out.getJSONArray("outbounds").objects().filter { it.optString("type") == "socks" }
        assertEquals(4, socks.size)
        assertTrue(socks.all { it.getString("server") == "127.0.0.127" && it.getString("bind_interface") == "lo" })
    }

    @Test
    fun everythingThroughVpnLeavesOnlyTheTorrentRule() {
        for (name in listOf("mixed-fakedns-on", "mixed-fakedns-off", "mixed-proxy-mode", "tcp-bridges")) {
            val input = JSONObject(fixture(name))
            val (out, result) = apply(name, RussiaDirect.Options(offReason = "user", sets = sets))
            assertEquals("off (user); torrents direct", result.state)
            assertFalse(result.applied)
            assertFalse(result.usesDnsRu)
            assertTrue(same(input.getJSONObject("dns"), out.getJSONObject("dns")))
            val inRoute = input.routeRules()
            val outRoute = out.routeRules()
            assertEquals(inRoute.size + 1, outRoute.size)
            for (i in inRoute.indices) assertTrue(same(inRoute[i], outRoute[i]))
            assertTrue(isTorrent(outRoute.last()))
            assertFalse(out.getJSONObject("route").has("rule_set"))
            assertFalse(out.toString().contains("dns-ru"))
        }
    }

    @Test
    fun headOffIsTheSameAsTheToggle() {
        val (out, result) = apply("mixed-fakedns-on", RussiaDirect.Options(offReason = "head", sets = sets))
        assertEquals("off (head); torrents direct", result.state)
        assertTrue(isTorrent(out.routeRules().last()))
        assertFalse(out.toString().contains("dns-ru"))
    }

    @Test
    fun withoutRuleSetFilesRussianNamesGoBySuffixes() {
        for (name in listOf("mixed-fakedns-on", "mixed-fakedns-off", "tcp-bridges")) {
            val input = JSONObject(fixture(name))
            val (out, result) = apply(name, RussiaDirect.Options(sets = null))
            assertEquals("on (suffixes)", result.state)
            assertTrue(result.applied)
            assertBlock(input, out, withSets = false)
        }
    }

    @Test
    fun dnsRuFallbackResolvesThroughDnsDirect() {
        val input = JSONObject(fixture("mixed-fakedns-on"))
        val (out, result) = apply("mixed-fakedns-on", RussiaDirect.Options(sets = sets, dnsRuFallback = true))
        assertEquals("on (dns-direct)", result.state)
        assertFalse(result.usesDnsRu)
        assertBlock(input, out, withSets = true, dnsServer = "dns-direct")
    }

    @Test
    fun extraExceptionsJoinTheBuiltInOnes() {
        val extra = listOf("blocked-one.ru", "jut.su", "blocked-two.su")
        val input = JSONObject(fixture("mixed-fakedns-on"))
        val (out, _) = apply("mixed-fakedns-on", RussiaDirect.Options(sets = sets, extraExceptions = extra))
        assertBlock(input, out, withSets = true, extra = extra)
    }

    @Test
    fun legacyDnsFieldsKeepTheBlockOut() {
        val input = JSONObject(fixture("legacy-dns-strategy"))
        val (out, result) = apply("legacy-dns-strategy", on)
        assertTrue(result.state, result.state.startsWith("off (legacy: dns.rules[2].strategy)"))
        assertFalse(result.applied)
        assertTrue(same(input.getJSONObject("dns"), out.getJSONObject("dns")))
        assertTrue(isTorrent(out.routeRules().last()))
        assertEquals(input.routeRules().size + 1, out.routeRules().size)
    }

    @Test
    fun everyLegacyFieldIsSeen() {
        val base = JSONObject(fixture("mixed-fakedns-off"))
        fun withRule(rule: JSONObject): JSONObject = JSONObject(base.toString()).also {
            it.getJSONObject("dns").getJSONArray("rules").put(rule)
        }
        val legacy = listOf(
            JSONObject().put("domain", JSONArray().put("a.example")).put("server", "dns-direct").put("strategy", "ipv4_only"),
            JSONObject().put("ip_cidr", JSONArray().put("10.0.0.0/8")).put("server", "dns-direct"),
            JSONObject().put("ip_is_private", true).put("server", "dns-direct"),
            JSONObject().put("ip_accept_any", true).put("server", "dns-direct"),
            JSONObject().put("response_rcode", "NXDOMAIN").put("server", "dns-direct"),
            JSONObject().put("rule_set", JSONArray().put("some-set")).put("server", "dns-direct"),
            JSONObject().put("rule_set_ip_cidr_accept_empty", true).put("server", "dns-direct"),
            JSONObject().put("type", "logical").put("mode", "or").put("server", "dns-direct")
                .put("rules", JSONArray().put(JSONObject().put("domain", JSONArray().put("a.example"))).put(JSONObject().put("ip_cidr", JSONArray().put("10.0.0.0/8")))),
        )
        for (rule in legacy) {
            assertNotNull(rule.toString(), RussiaDirect.legacyDnsReason(withRule(rule)))
            val result = RussiaDirect.apply(withRule(rule).toString(), on)
            assertFalse(rule.toString(), result.applied)
        }
        val fine = listOf(
            JSONObject().put("ip_cidr", JSONArray().put("10.0.0.0/8")).put("match_response", true).put("server", "dns-direct"),
            JSONObject().put("domain", JSONArray().put("a.example")).put("server", "dns-direct").put("strategy", "as_is"),
            JSONObject().put("ip_is_private", false).put("server", "dns-direct"),
            JSONObject().put("ip_cidr", JSONArray()).put("server", "dns-direct"),
        )
        for (rule in fine) assertNull(rule.toString(), RussiaDirect.legacyDnsReason(withRule(rule)))
    }

    @Test
    fun aForeignConfigIsLeftAlone() {
        val input = fixture("foreign-singbox")
        for (options in listOf(on, RussiaDirect.Options(offReason = "user", sets = sets), RussiaDirect.Options(sets = null))) {
            val result = RussiaDirect.apply(input, options)
            assertSame(input, result.json)
            assertFalse(result.applied)
            assertTrue(result.state, result.state.startsWith("off (not ours"))
        }
    }

    @Test
    fun ourShapeIsRequired() {
        val base = JSONObject(fixture("mixed-fakedns-on"))
        fun without(edit: (JSONObject) -> Unit) = JSONObject(base.toString()).also(edit)
        val broken = listOf(
            without { it.remove("outbounds") },
            without { root ->
                val outbounds = root.getJSONArray("outbounds")
                for (i in outbounds.length() - 1 downTo 0) if (outbounds.getJSONObject(i).optString("tag") == "direct") outbounds.remove(i)
            },
            without { root ->
                val servers = root.getJSONObject("dns").getJSONArray("servers")
                for (i in servers.length() - 1 downTo 0) if (servers.getJSONObject(i).optString("tag") == "dns-direct") servers.remove(i)
            },
            without { it.getJSONObject("dns").getJSONArray("servers").put(JSONObject().put("type", "udp").put("tag", "dns-ru").put("server", "192.0.2.1")) },
            without { it.getJSONObject("route").put("rule_set", JSONArray().put(JSONObject().put("tag", "geoip-ru").put("type", "local").put("format", "binary").put("path", "/x"))) },
            without { it.getJSONObject("route").getJSONArray("rules").put(JSONObject().put("protocol", "bittorrent").put("action", "reject")) },
            without { it.remove("route") },
        )
        for (config in broken) {
            assertNotNull(RussiaDirect.notOursReason(config))
            val text = config.toString()
            assertSame(text, RussiaDirect.apply(text, on).json)
        }
    }

    @Test
    fun applyingTwiceChangesNothing() {
        for (options in listOf(on, RussiaDirect.Options(sets = null), RussiaDirect.Options(sets = sets, dnsRuFallback = true), RussiaDirect.Options(offReason = "user"))) {
            val once = RussiaDirect.apply(fixture("mixed-fakedns-on"), options).json
            val twice = RussiaDirect.apply(once, options)
            assertSame(once, twice.json)
            assertFalse(twice.applied)
        }
    }

    @Test
    fun notJsonComesBackAsIs() {
        val text = "not a config"
        val result = RussiaDirect.apply(text, on)
        assertSame(text, result.json)
        assertFalse(result.applied)
    }

    @Test
    fun theListsHoldTogether() {
        // Generated together with the files; a suffix fully covered by an exception would be dead.
        assertTrue(RussiaDirectLists.SUFFIXES.isNotEmpty())
        assertTrue(RussiaDirectLists.EXCEPTIONS.isNotEmpty())
        assertEquals(RussiaDirectLists.SUFFIXES.size, RussiaDirectLists.SUFFIXES.toSet().size)
        assertEquals(RussiaDirectLists.EXCEPTIONS.size, RussiaDirectLists.EXCEPTIONS.toSet().size)
        assertTrue(RussiaDirectLists.SUFFIXES.none { it in RussiaDirectLists.EXCEPTIONS })
        assertEquals("tcp", RussiaDirectLists.DNS_RU_TYPE)
        assertTrue(RussiaDirectLists.DNS_RU_SERVER.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")))
    }
}
