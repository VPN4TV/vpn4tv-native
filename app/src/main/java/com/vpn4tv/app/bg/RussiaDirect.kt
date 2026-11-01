package com.vpn4tv.app.bg

import org.json.JSONArray
import org.json.JSONObject

/**
 * "Russia direct" (5.4.0): Russian names and Russian IPv4 addresses leave
 * through the device's own network, everything else goes as before.
 *
 * The owner's rules (2026-10-31): our exits must not show up at Russian
 * services, so a Russian name goes direct at any address and a Russian IPv4
 * goes direct with or without a name; names blocked in Russia (the exceptions)
 * stay on the VPN; plain BitTorrent goes direct always, the "Everything
 * through VPN" toggle included (DMCA). Appended after the rules already there,
 * in this order:
 *  1. {protocol: bittorrent} → direct
 *  2. and[M, E] → direct, where M = geosite-category-ru or the built-in
 *     suffixes and E = not an exception
 *  3. and[geoip-ru, E, IPv4] → direct
 * IPv6 without a Russian name keeps going through the VPN: a Russian IPv6
 * sent direct breaks on networks without IPv6.
 *
 * DNS: Russian names resolve at Yandex (dns-ru, TCP, dialed directly), and
 * their AAAA/HTTPS/SVCB queries get an empty answer here; both rules go ahead
 * of the HTTPS/SVCB reject and the fakeip rule. Without verified rule-set
 * files M is the suffixes alone and rule 3 is left out ("on (suffixes)").
 *
 * Applied in memory at every start and reload on top of the config the other
 * transforms produced, like FakeDNS and the LAN bypass, so old and local
 * profiles get it too and the toggle needs no subscription refresh. Only
 * configs of our own shape are touched; a foreign sing-box config comes back
 * unchanged. Pure, org.json only: the JVM tests run it as is.
 */
object RussiaDirect {
    private const val SELECT = "select"
    private const val DIRECT = "direct"
    private const val DNS_REMOTE = "dns-remote"
    private const val DNS_DIRECT = "dns-direct"
    private const val DNS_FAKEIP = "dns-fakeip"
    private const val BITTORRENT = "bittorrent"

    val GEOSITE: String get() = RussiaDirectLists.GEOSITE.tag
    val GEOIP: String get() = RussiaDirectLists.GEOIP.tag

    /** Absolute paths of the verified rule-set files. */
    class Sets(val geosite: String, val geoip: String)

    class Options(
        /**
         * Null: Russia direct is wanted. Otherwise why it is not ("user",
         * "head"), for the log line; plain BitTorrent still goes direct.
         */
        val offReason: String? = null,
        /** Verified rule-set files; null routes Russian names by the built-in suffixes alone. */
        val sets: Sets? = null,
        /** dns-ru did not answer this session: our DNS rule resolves through dns-direct. */
        val dnsRuFallback: Boolean = false,
        /** More names kept on the VPN, from the subscription server. */
        val extraExceptions: List<String> = emptyList(),
    )

    /**
     * [json]: what to start. [applied]: the Russian rules are in it, not only
     * the torrent rule. [usesDnsRu]: dns-ru is in it, so its self-check is worth
     * running. [state]: the summary for the log line.
     */
    class Result(val json: String, val state: String, val applied: Boolean, val usesDnsRu: Boolean)

    fun apply(json: String, options: Options): Result {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return Result(json, "off (not a JSON object)", applied = false, usesDnsRu = false)
        }
        return try {
            val notOurs = notOursReason(root)
            if (notOurs != null) return Result(json, "off (not ours: $notOurs)", applied = false, usesDnsRu = false)

            val route = root.getJSONObject("route")
            val routeRules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }
            routeRules.put(JSONObject().put("protocol", JSONArray().put(BITTORRENT)).put("action", "route").put("outbound", DIRECT))

            // Our DNS rules carry query_type, which takes the core out of its
            // legacy DNS mode; there a few old rule fields fail the start.
            val off = options.offReason ?: legacyDnsReason(root)?.let { "legacy: $it" }
            if (off != null) {
                return Result(root.toString(), "off ($off); torrents direct", applied = false, usesDnsRu = false)
            }

            val sets = options.sets
            val exceptions = (RussiaDirectLists.EXCEPTIONS + options.extraExceptions).distinct()
            fun m() = JSONObject().apply {
                if (sets != null) put("rule_set", JSONArray().put(GEOSITE))
                put("domain_suffix", JSONArray(RussiaDirectLists.SUFFIXES))
            }
            fun e() = JSONObject().put("domain_suffix", JSONArray(exceptions)).put("invert", true)

            val dns = root.getJSONObject("dns")
            val dnsServer = if (options.dnsRuFallback) DNS_DIRECT else RussiaDirectLists.DNS_RU_TAG
            if (!options.dnsRuFallback) {
                // Last: dns-remote stays the first server, the default one. No
                // detour: the core dials a DNS server without one directly.
                dns.getJSONArray("servers").put(
                    JSONObject()
                        .put("type", RussiaDirectLists.DNS_RU_TYPE)
                        .put("tag", RussiaDirectLists.DNS_RU_TAG)
                        .put("server", RussiaDirectLists.DNS_RU_SERVER),
                )
            }
            val dnsRules = dns.optJSONArray("rules") ?: JSONArray()
            val at = (0 until dnsRules.length()).firstOrNull { i ->
                dnsRules.optJSONObject(i)?.let { isFakeDnsRule(it) } == true
            } ?: dnsRules.length()
            dns.put(
                "rules",
                insert(
                    dnsRules, at,
                    listOf(
                        // No rcode: an empty NOERROR, so Russian names get A
                        // records only (no IPv6 stalls) and no HTTPS hints.
                        allOf(JSONObject().put("query_type", JSONArray().put("AAAA").put("HTTPS").put("SVCB")), m(), e())
                            .put("action", "predefined"),
                        allOf(m(), e()).put("action", "route").put("server", dnsServer),
                    ),
                ),
            )

            routeRules.put(allOf(m(), e()).put("action", "route").put("outbound", DIRECT))
            if (sets != null) {
                routeRules.put(
                    allOf(JSONObject().put("rule_set", JSONArray().put(GEOIP)), e(), JSONObject().put("ip_version", 4))
                        .put("action", "route").put("outbound", DIRECT),
                )
                val ruleSets = route.optJSONArray("rule_set") ?: JSONArray().also { route.put("rule_set", it) }
                ruleSets.put(localRuleSet(GEOSITE, sets.geosite))
                ruleSets.put(localRuleSet(GEOIP, sets.geoip))
            }

            val details = listOfNotNull(
                if (sets == null) "suffixes" else null,
                if (options.dnsRuFallback) "dns-direct" else null,
            )
            val state = if (details.isEmpty()) "on" else "on (${details.joinToString(", ")})"
            Result(root.toString(), state, applied = true, usesDnsRu = !options.dnsRuFallback)
        } catch (e: Exception) {
            Result(json, "off (error: ${e.message})", applied = false, usesDnsRu = false)
        }
    }

    /**
     * Null when [root] has our shape: the "select" selector and the "direct"
     * outbound, dns-remote and dns-direct, and nothing of ours yet. A foreign
     * config (a sing-box JSON imported as is) is left alone, and applying
     * twice changes nothing.
     */
    internal fun notOursReason(root: JSONObject): String? {
        val outbounds = root.optJSONArray("outbounds") ?: return "no outbounds"
        if (!any(outbounds) { it.optString("type") == "selector" && it.optString("tag") == SELECT }) return "no selector \"$SELECT\""
        if (!any(outbounds) { it.optString("type") == "direct" && it.optString("tag") == DIRECT }) return "no outbound \"$DIRECT\""
        val dns = root.optJSONObject("dns") ?: return "no dns"
        val servers = dns.optJSONArray("servers") ?: return "no dns servers"
        val serverTags = tags(servers)
        if (DNS_REMOTE !in serverTags || DNS_DIRECT !in serverTags) return "no $DNS_REMOTE/$DNS_DIRECT"
        if (RussiaDirectLists.DNS_RU_TAG in serverTags) return "has ${RussiaDirectLists.DNS_RU_TAG}"
        val route = root.optJSONObject("route") ?: return "no route"
        val ruleSetTags = route.optJSONArray("rule_set")?.let { tags(it) }.orEmpty()
        if (GEOSITE in ruleSetTags || GEOIP in ruleSetTags) return "has the Russian rule sets"
        val rules = route.optJSONArray("rules")
        if (rules != null && any(rules) { rule ->
                rule.optJSONArray("protocol")?.let { p -> (0 until p.length()).any { p.optString(it) == BITTORRENT } } == true ||
                    rule.optString("protocol") == BITTORRENT
            }
        ) return "has a $BITTORRENT rule"
        return null
    }

    /**
     * Why the core would refuse [root]'s DNS rules once ours take it out of
     * the legacy DNS mode, or null: a strategy in a rule action, an address
     * filter or response field without match_response, or a rule set (it may
     * hold addresses only, which the new mode refuses in DNS rules).
     */
    internal fun legacyDnsReason(root: JSONObject): String? {
        val rules = root.optJSONObject("dns")?.optJSONArray("rules") ?: return null
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            legacyField(rule)?.let { return "dns.rules[$i].$it" }
        }
        return null
    }

    private val ADDRESS_AND_RESPONSE_FIELDS = listOf(
        "ip_cidr", "ip_is_private", "ip_accept_any",
        "response_rcode", "response_answer", "response_ns", "response_extra",
    )

    private fun legacyField(rule: JSONObject): String? {
        val strategy = rule.optString("strategy", "")
        if (strategy.isNotEmpty() && strategy != "as_is") return "strategy"
        if (rule.optBoolean("rule_set_ip_cidr_accept_empty", false)) return "rule_set_ip_cidr_accept_empty"
        if (rule.optString("type") == "logical") {
            val sub = rule.optJSONArray("rules") ?: return null
            for (j in 0 until sub.length()) {
                legacyField(sub.optJSONObject(j) ?: continue)?.let { return "rules[$j].$it" }
            }
            return null
        }
        if (rule.optBoolean("match_response", false)) return null
        for (field in ADDRESS_AND_RESPONSE_FIELDS) {
            if (present(rule.opt(field))) return field
        }
        if (present(rule.opt("rule_set"))) return "rule_set"
        return null
    }

    private fun present(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL, false -> false
        is JSONArray -> value.length() > 0
        is String -> value.isNotEmpty()
        else -> true
    }

    /** The HTTPS/SVCB reject or the fakeip rule: ours go ahead of the first of them. */
    private fun isFakeDnsRule(rule: JSONObject): Boolean {
        if (rule.optString("server") == DNS_FAKEIP) return true
        val queryTypes = rule.optJSONArray("query_type")?.let { qt -> (0 until qt.length()).map { qt.optString(it) }.toSet() }
        return rule.optString("action") == "reject" && queryTypes == setOf("HTTPS", "SVCB")
    }

    private fun allOf(vararg rules: JSONObject): JSONObject = JSONObject()
        .put("type", "logical")
        .put("mode", "and")
        .put("rules", JSONArray().apply { rules.forEach { put(it) } })

    private fun localRuleSet(tag: String, path: String): JSONObject = JSONObject()
        .put("tag", tag)
        .put("type", "local")
        .put("format", "binary")
        .put("path", path)

    /** org.json has no insert-at-index; rebuild the array. */
    private fun insert(array: JSONArray, at: Int, items: List<Any>): JSONArray {
        val out = JSONArray()
        for (i in 0 until at) out.put(array.get(i))
        items.forEach { out.put(it) }
        for (i in at until array.length()) out.put(array.get(i))
        return out
    }

    private fun tags(array: JSONArray): Set<String> =
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("tag") }.toSet()

    private inline fun any(array: JSONArray, predicate: (JSONObject) -> Boolean): Boolean =
        (0 until array.length()).any { i -> array.optJSONObject(i)?.let(predicate) == true }
}
