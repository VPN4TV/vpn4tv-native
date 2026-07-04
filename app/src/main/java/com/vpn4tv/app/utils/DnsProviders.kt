package com.vpn4tv.app.utils

/**
 * SINGLE SOURCE OF TRUTH for every DNS resolver the app knows about.
 *
 * Before 2026-07 this knowledge was duplicated across four hand-maintained
 * lists (DnsProber DoH candidates, DnsProber UDP candidates, DOH_CAPABLE_IPS,
 * HwidService resolution chain) — and when RKN killed Google+Cloudflare DoH
 * the same day, Quad9 had to be added to each list separately. Never again:
 * add/reorder providers HERE and every consumer picks it up.
 *
 * Ordering = preference. Put survivors of the latest purge first.
 *
 * Consumers (derived views below):
 * - [DnsProber] probes [dohProbeCandidates] / [udpCandidates] at app start
 *   and rewrites sing-box dns-remote/dns-direct with the winner.
 * - [com.vpn4tv.app.converter.HwidService] walks [resolutionEndpoints] to
 *   resolve hostnames in-app (subscription bootstrap + outbound-hosts
 *   pre-resolution for urltest).
 * - sing-box dns-direct needs an IP whose DoH cert covers the raw IP —
 *   [dohCapableIps].
 */
object DnsProviders {

    data class Provider(
        val name: String,
        /** RFC 8484 wireformat endpoints (/dns-query). IP-hosted first — they
         *  need no bootstrap resolution themselves. */
        val dohUrls: List<String>,
        /** Google-style JSON API endpoints (legacy extra fallbacks). */
        val jsonUrls: List<String> = emptyList(),
        /** Plain UDP:53 resolver IPs. */
        val udpIps: List<String> = emptyList(),
        /** True when the DoH certificate carries IP SANs, i.e. the raw IP is
         *  usable as a sing-box https DNS server (dns-direct requirement). */
        val ipCertSan: Boolean = false,
        /** RU-jurisdiction resolvers: acceptable as an absolute last resort
         *  for the runtime probe, but NEVER used in the in-app resolution
         *  chain — resolving circumvention domains through a RU resolver
         *  leaks exactly the wrong queries. */
        val lastResort: Boolean = false,
    )

    /** 2026-07: RKN killed Google AND Cloudflare DoH the same day (both dead
     *  on multiple accounts, Quad9 alive) → Quad9 leads, and no consumer may
     *  assume Google/CF survive. */
    val PROVIDERS = listOf(
        Provider(
            name = "quad9",
            dohUrls = listOf("https://9.9.9.9/dns-query", "https://149.112.112.112/dns-query"),
            udpIps = listOf("9.9.9.9", "149.112.112.112"),
            ipCertSan = true,
        ),
        Provider(
            name = "adguard",   // popular in RU, rarely blocked
            dohUrls = listOf("https://dns.adguard-dns.com/dns-query"),
            jsonUrls = listOf("https://dns.adguard-dns.com/resolve"),
        ),
        Provider(
            name = "google",    // DoH blocked 2026-07; still fine through the tunnel
            dohUrls = listOf("https://8.8.8.8/dns-query", "https://dns.google/dns-query"),
            jsonUrls = listOf("https://dns.google/resolve"),
            udpIps = listOf("8.8.8.8", "8.8.4.4"),
            ipCertSan = true,
        ),
        Provider(
            name = "cloudflare", // most-blocked: MITM'd on 53/853 since long before the purge
            dohUrls = listOf("https://1.0.0.1/dns-query", "https://1.1.1.1/dns-query"),
            jsonUrls = listOf("https://one.one.one.one/dns-query"),
            udpIps = listOf("1.1.1.1"),
            ipCertSan = true,
        ),
        Provider(
            name = "yandex",    // never blocked in RU — and that's exactly why it's last-resort-only
            dohUrls = listOf("https://dns.yandex.net/dns-query"),
            udpIps = listOf("77.88.8.8"),
            lastResort = true,
        ),
    )

    /** One resolution attempt: [wire] = RFC 8484 binary, else JSON API. */
    data class Endpoint(val url: String, val wire: Boolean)

    /** DoH URLs for the startup reachability probe (all providers — a
     *  last-resort winner is still better than a dead resolver). */
    val dohProbeCandidates: List<String> =
        PROVIDERS.flatMap { it.dohUrls }

    /** Plain-UDP fallback IPs for the startup probe, preference order. */
    val udpCandidates: List<String> =
        PROVIDERS.flatMap { it.udpIps }

    /** IPs safe to use as a sing-box `https` DNS server address. */
    val dohCapableIps: Set<String> =
        PROVIDERS.filter { it.ipCertSan }
            .flatMap { p -> p.udpIps + p.dohUrls.mapNotNull { ipHostOrNull(it) } }
            .toSet()

    /** Preferred IP for dns-direct when the probe winner isn't IP-hosted. */
    val preferredDohIp: String =
        PROVIDERS.first { it.ipCertSan }.dohUrls.firstNotNullOf { ipHostOrNull(it) }

    /** In-app resolution chain: wireformat on every non-last-resort DoH
     *  endpoint (universal), then the JSON APIs as extra fallbacks. */
    val resolutionEndpoints: List<Endpoint> =
        PROVIDERS.filterNot { it.lastResort }.flatMap { p ->
            p.dohUrls.map { Endpoint(it, wire = true) }
        } + PROVIDERS.filterNot { it.lastResort }.flatMap { p ->
            p.jsonUrls.map { Endpoint(it, wire = false) }
        }

    private fun ipHostOrNull(url: String): String? {
        val host = url.removePrefix("https://").substringBefore("/")
        return if (host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) host else null
    }
}
