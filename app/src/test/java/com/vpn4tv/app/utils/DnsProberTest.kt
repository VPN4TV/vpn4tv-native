package com.vpn4tv.app.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsProberTest {
    private val candidates = DnsProviders.dohProbeCandidates

    @Test
    fun `Quad9 answering wins both roles at once`() {
        val asked = mutableListOf<String>()
        val choice = DnsProber.chooseDoh(candidates) { asked += it; true }
        assertEquals("https://9.9.9.9/dns-query", choice.winner)
        assertEquals("9.9.9.9", choice.ip)
        assertEquals(listOf("https://9.9.9.9/dns-query"), asked)
    }

    @Test
    fun `Quad9 blocked on 443 and AdGuard winning by name never gives dns-direct 9_9_9_9`() {
        // Support chat 2026-11-16: the old guess handed dns-direct the IP whose DoH had just failed.
        val alive = setOf("https://dns.adguard-dns.com/dns-query", "https://8.8.8.8/dns-query")
        val asked = mutableListOf<String>()
        val choice = DnsProber.chooseDoh(candidates) { asked += it; it in alive }
        assertEquals("https://dns.adguard-dns.com/dns-query", choice.winner)
        assertEquals("8.8.8.8", choice.ip)
        // After the winner by name only IP-hosted candidates are asked: dns.google is skipped.
        assertFalse("https://dns.google/dns-query" in asked)
    }

    @Test
    fun `no IP answering leaves dns-direct to UDP`() {
        val choice = DnsProber.chooseDoh(candidates) { it == "https://dns.adguard-dns.com/dns-query" }
        assertEquals("https://dns.adguard-dns.com/dns-query", choice.winner)
        assertNull(choice.ip)
    }

    @Test
    fun `nothing answering gives neither`() {
        val choice = DnsProber.chooseDoh(candidates) { false }
        assertNull(choice.winner)
        assertNull(choice.ip)
    }

    @Test
    fun `the UDP query asks for an A record with recursion`() {
        val q = DnsProber.dnsQuery(id = 0x1234, name = "example.com")
        val expected = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0,
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(),
            'l'.code.toByte(), 'e'.code.toByte(), 3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0, 0x00, 0x01, 0x00, 0x01)
        assertArrayEquals(expected, q)
    }

    @Test
    fun `an answer must match the id, carry the response bit and no format or server error`() {
        val q = DnsProber.dnsQuery(id = 0x1234, name = "example.com")
        fun reply(id0: Int, flags: Int, rcode: Int) =
            byteArrayOf(id0.toByte(), 0x34, flags.toByte(), rcode.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)
        assertTrue(DnsProber.isDnsAnswer(q, reply(0x12, 0x81, 0x80), 12))
        assertTrue(DnsProber.isDnsAnswer(q, reply(0x12, 0x81, 0x83), 12)) // NXDOMAIN is still an answer
        assertFalse(DnsProber.isDnsAnswer(q, reply(0x99, 0x81, 0x80), 12)) // someone else's id
        assertFalse(DnsProber.isDnsAnswer(q, reply(0x12, 0x01, 0x80), 12)) // not a response
        assertFalse(DnsProber.isDnsAnswer(q, reply(0x12, 0x81, 0x82), 12)) // SERVFAIL
        assertFalse(DnsProber.isDnsAnswer(q, reply(0x12, 0x81, 0x80), 8)) // truncated
    }
}
