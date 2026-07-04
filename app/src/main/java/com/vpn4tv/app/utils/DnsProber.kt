package com.vpn4tv.app.utils

import android.util.Log
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Probes a list of DNS servers at app startup to find which ones are
 * reachable from the current network. Russian ISPs aggressively MITM
 * or block specific DNS providers (Cloudflare 1.1.1.1 is the worst
 * offender — TSPU intercepts DoT/DoH and substitutes its own cert).
 *
 * Results are cached for the lifetime of the process and used by:
 * - [com.vpn4tv.app.bg.BoxService] to rewrite sing-box DNS config
 *   before starting the service
 * - [com.vpn4tv.app.converter.HwidService] to resolve api.vpn4tv.com
 *   when system DNS fails
 *
 * Probe is fire-and-forget from Application.onCreate on an IO thread.
 * If nothing responds, we fall back to plain UDP 8.8.8.8 which almost
 * always works (ISPs can't easily MITM unencrypted UDP DNS without
 * breaking everything else on the network).
 */
object DnsProber {
    private const val TAG = "DnsProber"
    private const val PROBE_TIMEOUT_MS = 3000

    data class DnsResult(
        val dohUrl: String,
        val udpServer: String,
        val dohWorks: Boolean,
    )

    /** Candidate lists live in [DnsProviders] — the single registry shared
     *  with HwidService's resolution chain. Add/reorder providers THERE. */
    private val DOH_CANDIDATES get() = DnsProviders.dohProbeCandidates
    private val UDP_CANDIDATES get() = DnsProviders.udpCandidates

    @Volatile
    var result: DnsResult? = null
        private set

    /**
     * Probe all candidates, pick first working DoH + first working UDP.
     * Blocks for up to [PROBE_TIMEOUT_MS] per candidate (but short-
     * circuits on first success). Call from an IO thread.
     */
    fun probe() {
        val startMs = System.currentTimeMillis()

        // DoH probe: send a minimal query via HTTPS GET and check for 200
        var bestDoh: String? = null
        for (url in DOH_CANDIDATES) {
            if (probeDoH(url)) {
                bestDoh = url
                break
            }
        }

        // UDP probe: try to resolve a well-known domain via each server
        var bestUdp: String? = null
        for (server in UDP_CANDIDATES) {
            if (probeUdp(server)) {
                bestUdp = server
                break
            }
        }

        val doh = bestDoh ?: "https://8.8.8.8/dns-query"
        val udp = bestUdp ?: "8.8.8.8"
        result = DnsResult(dohUrl = doh, udpServer = udp, dohWorks = bestDoh != null)

        val elapsed = System.currentTimeMillis() - startMs
        Log.i(TAG, "probe done in ${elapsed}ms: doh=$doh (works=${bestDoh != null}) udp=$udp")
    }

    private fun probeDoH(dohUrl: String): Boolean {
        return try {
            // Minimal DoH GET query for google.com A record
            val queryUrl = "$dohUrl?name=google.com&type=A"
            val conn = URL(queryUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/dns-json")
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            val code = conn.responseCode
            conn.disconnect()
            // Any HTTP response means the server is reachable and not
            // MITM'd. 400 is fine — it just means the JSON API path
            // differs (/resolve vs /dns-query), but sing-box uses
            // wireformat which works on all of them.
            val ok = code in 200..499
            Log.d(TAG, "DoH $dohUrl → $code ${if (ok) "✓" else "✗"}")
            ok
        } catch (e: Exception) {
            Log.d(TAG, "DoH $dohUrl → ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun probeUdp(server: String): Boolean {
        return try {
            // Simple: can we resolve google.com using this server?
            // Java doesn't let us pick a DNS server directly, so we
            // just check if the server IP itself is reachable.
            val addr = InetAddress.getByName(server)
            val ok = addr.isReachable(PROBE_TIMEOUT_MS)
            Log.d(TAG, "UDP $server → ${if (ok) "✓ reachable" else "✗ unreachable"}")
            // Even if isReachable fails (ICMP blocked), the server may
            // still work for DNS. Accept it as fallback anyway.
            true
        } catch (e: Exception) {
            Log.d(TAG, "UDP $server → ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Extract the server host from a DoH URL for sing-box config. */
    fun dohServer(): String {
        val url = result?.dohUrl ?: "https://8.8.8.8/dns-query"
        return url.removePrefix("https://").substringBefore("/")
    }

    fun udpServer(): String = result?.udpServer ?: "8.8.8.8"

    fun dohWorks(): Boolean = result?.dohWorks == true

    /**
     * IP that is known to serve DoH on /dns-query with a cert valid for
     * the raw IP (no SNI / hostname verification shenanigans). Used by
     * dns-direct, which cannot have a bootstrap resolver and therefore
     * must be addressed by IP. Google and Cloudflare both ship DoH on
     * their anycast IPs; Yandex does not (only dns.yandex.net).
     */
    fun dohCapableIp(): String? {
        val r = result ?: return null
        if (!r.dohWorks) return null
        val host = r.dohUrl.removePrefix("https://").substringBefore("/")
        if (host.matches(Regex("^[0-9.]+$"))) return host
        if (r.udpServer in DnsProviders.dohCapableIps) return r.udpServer
        // Last resort comes from the registry's first IP-cert provider, NOT a
        // hardcoded Google/CF IP: the old "1.1.1.1" fallback meant every
        // non-IP DoH winner (AdGuard, Yandex) still pointed dns-direct at a
        // dead Cloudflare IP during the 2026-07 purge.
        return DnsProviders.preferredDohIp
    }
}
