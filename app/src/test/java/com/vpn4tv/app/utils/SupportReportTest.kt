package com.vpn4tv.app.utils

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportReportTest {
    @Test
    fun theCodeIsSixCharactersThatReadWellOffATv() {
        val random = java.util.Random(7)
        repeat(200) {
            val code = SupportReport.newCode(random)
            assertEquals(6, code.length)
            assertTrue(code, code.all { it in SupportReport.CODE_ALPHABET })
            assertFalse(code, code.any { it in "01OI" })
        }
    }

    @Test
    fun secretsStayOnTheDevice() {
        val hwid = "0123456789abcdef0123456789abcdef"
        assertNull(SupportReport.scrub("""  "password": "hunter2",""", hwid))
        assertNull(SupportReport.scrub("""{"type":"vless","uuid":"b831381d-6324-4d53-ad4f-8cda48b30811"}""", hwid))
        assertEquals(
            "import failed for <vless key>",
            SupportReport.scrub("import failed for vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=reality#DE", hwid),
        )
        assertEquals("<hy2 key> and <ss key>", SupportReport.scrub("hy2://secret@host:443 and ss://YWVzOnBhc3M@host:8388", hwid))
        // A link keeps its server and loses its path: a key's link holds the key's token.
        assertEquals(
            "key: https://api.vpn4tv.com/… failed: IOException: Connection reset",
            SupportReport.scrub("key: https://api.vpn4tv.com/configs/1234567/salt123 failed: IOException: Connection reset", hwid),
        )
        assertEquals("user <uuid> rejected", SupportReport.scrub("user b831381d-6324-4d53-ad4f-8cda48b30811 rejected", hwid))
        assertEquals("x-hwid <device id> sent", SupportReport.scrub("x-hwid $hwid sent", hwid))
        assertEquals("ERROR plain line", SupportReport.scrub("\u001B[31mERROR\u001B[0m plain line", hwid))
    }

    @Test
    fun aServersAddressStaysAsOnTheLogsScreen() {
        val line = "20:06:17 ERROR outbound/hysteria2[SG10H hy2 · 839972]: dial udp 203.0.113.9:8443: connect: network is unreachable"
        assertEquals(line, SupportReport.scrub(line, null))
    }

    @Test
    fun theBodyHasThePartsTheAnalyzerReads() {
        val body = SupportReport.body(
            context = linkedMapOf("kind" to "user-report", "client" to "android", "code" to "K7M2QX", "core" to null, "logLines" to 2),
            state = listOf("vpn: started, mode vpn", "network: none [told to the core]"),
            notes = listOf("11-21 20:05:38 — start —", "11-21 20:05:40 network: none"),
            log = listOf("20:06:17 ERROR a", """  "token": "x"""", "20:06:18 ERROR b"),
            hwid = null,
        )
        val parts = Regex("""(?m)^===== (.+) =====$""").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(listOf("vpn4tv.json", "app.txt", "log.txt"), parts)
        val json = JSONObject(body.substringAfter("===== vpn4tv.json =====\n").substringBefore("\n"))
        assertEquals("user-report", json.getString("kind"))
        assertEquals("K7M2QX", json.getString("code"))
        assertFalse(json.has("core"))
        assertEquals(
            "vpn: started, mode vpn\nnetwork: none [told to the core]\n\n11-21 20:05:38 — start —\n11-21 20:05:40 network: none",
            body.substringAfter("===== app.txt =====\n").substringBefore("\n\n===== log.txt"),
        )
        // The line with a secret is gone, the rest in order.
        assertEquals("20:06:17 ERROR a\n20:06:18 ERROR b\n\n", body.substringAfter("===== log.txt =====\n"))
    }

    @Test
    fun anEmptyLogSaysSo() {
        val body = SupportReport.body(linkedMapOf("kind" to "user-report"), emptyList(), emptyList(), emptyList(), null)
        assertTrue(body.endsWith("===== log.txt =====\n(no log: the VPN is off or the log is empty)\n\n"))
    }

    @Test
    fun aLongLogKeepsItsEnd() {
        val line = "x".repeat(999)
        val body = SupportReport.body(linkedMapOf("kind" to "user-report"), emptyList(), emptyList(), List(1000) { line } + "the last line", null)
        val log = body.substringAfter("===== log.txt =====\n")
        assertTrue(log.length <= SupportReport.MAX_LOG_CHARS + 2)
        assertTrue(log.endsWith("the last line\n\n"))
    }

    @Test
    fun anErrorsWordsLeaveWithoutAddresses() {
        assertEquals(
            "SocketTimeoutException: failed to connect to bell.a4e.ar/<address> (port 443) from /<address> (port 40212) after 10000ms",
            SupportReport.withoutAddresses(
                "SocketTimeoutException: failed to connect to bell.a4e.ar/203.0.113.7 (port 443) from /192.168.1.50 (port 40212) after 10000ms",
            ),
        )
        assertEquals(
            "ConnectException: failed to connect to /<address> (port 443): connect failed: ENETUNREACH (Network is unreachable)",
            SupportReport.withoutAddresses(
                "ConnectException: failed to connect to /2001:db8::7 (port 443): connect failed: ENETUNREACH (Network is unreachable)",
            ),
        )
        assertEquals("UnknownHostException: Unable to resolve host \"bell.a4e.ar\"", SupportReport.withoutAddresses("UnknownHostException: Unable to resolve host \"bell.a4e.ar\""))
        assertEquals("HTTP 502", SupportReport.withoutAddresses("HTTP 502"))
    }

    @Test
    fun spansOfTime() {
        assertEquals("0 min", SupportReport.span(59_000))
        assertEquals("59 min", SupportReport.span(59 * 60_000L))
        assertEquals("3 h 12 min", SupportReport.span((3 * 60 + 12) * 60_000L))
    }
}
