package com.vpn4tv.app.utils

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
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
    private const val UDP_TIMEOUT_MS = 2000

    data class DnsResult(
        val dohUrl: String,
        val udpServer: String,
        val dohWorks: Boolean,
        /** An IP whose DoH answered this probe, for dns-direct (see [dohCapableIp]); null: none did. */
        val dohIp: String? = null,
    )

    /** What the DoH probe settled on: the first endpoint that answered, and an IP-hosted one for dns-direct. */
    data class DohChoice(val winner: String?, val ip: String?)

    /**
     * Walk [candidates] in order, asking [answers] about each. The first that answers wins (dns-remote).
     * dns-direct needs an IP whose certificate covers it ([DnsProviders.dohCapableIps]); after a winner
     * by name only such IPs are still asked, and only one that answered itself is taken. Before
     * 2026-11 the IP was guessed instead: the UDP winner or the registry's first, i.e. 9.9.9.9 right
     * after its own DoH had failed, and dns-direct stayed dead where a provider blocks 9.9.9.9:443
     * (support chat 2026-11-16: «network is unreachable», nothing opened).
     */
    fun chooseDoh(candidates: List<String>, answers: (String) -> Boolean): DohChoice {
        var winner: String? = null
        for (url in candidates) {
            val ip = ipHost(url)?.takeIf { it in DnsProviders.dohCapableIps }
            if (winner != null && ip == null) continue
            if (!answers(url)) continue
            if (winner == null) winner = url
            if (ip != null) return DohChoice(winner, ip)
        }
        return DohChoice(winner, null)
    }

    private fun ipHost(url: String): String? =
        url.removePrefix("https://").substringBefore("/").takeIf { it.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) }

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
        val choice = chooseDoh(DOH_CANDIDATES) { probeDoH(it) }
        val bestDoh = choice.winner

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
        result = DnsResult(dohUrl = doh, udpServer = udp, dohWorks = bestDoh != null, dohIp = choice.ip)

        val elapsed = System.currentTimeMillis() - startMs
        Log.i(TAG, "probe done in ${elapsed}ms: doh=$doh (works=${bestDoh != null}) doh-ip=${choice.ip} udp=$udp (answered=${bestUdp != null})")
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
            // wireformat which works on all of them. 505 too: Quad9 serves
            // DoH over HTTP/2 only and refuses this HTTP/1.1 request with
            // "HTTP Version Not Supported" (2026-11), while the core asks it
            // over HTTP/2 and gets answers. Any status is the real server
            // answering over a verified TLS connection.
            val ok = code in 100..599
            if (ok) Log.d(TAG, "DoH $dohUrl → $code ✓") else Log.i(TAG, "DoH $dohUrl → HTTP $code ✗")
            ok
        } catch (e: Exception) {
            // Info, not debug: a DoH that does not answer decides dns-direct, and release builds drop Log.d.
            Log.i(TAG, "DoH $dohUrl → ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Ask [server] on UDP 53 for example.com's A record and wait for its answer. Until 2026-11 any
     * IP counted (the check could not fail), so the first candidate, 9.9.9.9, was the UDP fallback
     * even where it was unreachable.
     */
    private fun probeUdp(server: String): Boolean {
        return try {
            val query = dnsQuery(id = (System.nanoTime() and 0xffff).toInt(), name = "example.com")
            val reply = ByteArray(512)
            DatagramSocket().use { socket ->
                socket.soTimeout = UDP_TIMEOUT_MS
                socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
                val packet = DatagramPacket(reply, reply.size)
                socket.receive(packet)
                val ok = isDnsAnswer(query, reply, packet.length)
                Log.d(TAG, "UDP $server → ${if (ok) "✓ answered" else "✗ not an answer"}")
                ok
            }
        } catch (e: Exception) {
            Log.d(TAG, "UDP $server → ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** A DNS query for [name]'s A record (RFC 1035): header with recursion desired, one question. */
    fun dnsQuery(id: Int, name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        for (label in name.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(byteArrayOf(0, 0x00, 0x01, 0x00, 0x01))
        return out.toByteArray()
    }

    /** [reply] (its first [length] bytes) answers [query]: same id, the response bit, no format or server error. */
    fun isDnsAnswer(query: ByteArray, reply: ByteArray, length: Int): Boolean =
        length >= 12 && reply[0] == query[0] && reply[1] == query[1] &&
            (reply[2].toInt() and 0x80) != 0 && (reply[3].toInt() and 0x0f) !in setOf(1, 2)

    /** Extract the server host from a DoH URL for sing-box config. */
    fun dohServer(): String {
        val url = result?.dohUrl ?: "https://8.8.8.8/dns-query"
        return url.removePrefix("https://").substringBefore("/")
    }

    fun udpServer(): String = result?.udpServer ?: "8.8.8.8"

    fun dohWorks(): Boolean = result?.dohWorks == true

    /**
     * IP that serves DoH on /dns-query with a cert valid for the raw IP and
     * that answered this probe ([chooseDoh]). Used by dns-direct, which
     * cannot have a bootstrap resolver and therefore must be addressed by
     * IP. Null when none answered: dns-direct then takes the UDP server
     * that answered ([udpServer]).
     */
    fun dohCapableIp(): String? = result?.dohIp
}
