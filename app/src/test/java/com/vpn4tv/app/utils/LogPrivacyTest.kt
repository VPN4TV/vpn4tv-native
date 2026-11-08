package com.vpn4tv.app.utils

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogPrivacyTest {
    @Test
    fun coreLinesLoseTheSite() {
        assertEquals(
            "+0300 2026-11-08 12:00:00 INFO [1 0ms] outbound/vless[DE]: outbound connection to …",
            LogPrivacy.redactCoreLine("+0300 2026-11-08 12:00:00 INFO [1 0ms] outbound/vless[DE]: outbound connection to www.youtube.com:443"),
        )
        assertEquals(
            "ERROR [2 5.0s] connection: open connection to … using outbound/vless[DE]: dial tcp 1.2.3.4:443: i/o timeout",
            LogPrivacy.redactCoreLine("ERROR [2 5.0s] connection: open connection to youtube.com:443 using outbound/vless[DE]: dial tcp 1.2.3.4:443: i/o timeout"),
        )
        assertEquals(
            "ERROR [3 1.0s] connection: open packet connection to … using outbound/direct[direct]: EOF",
            LogPrivacy.redactCoreLine("ERROR [3 1.0s] connection: open packet connection to 8.8.8.8:53 using outbound/direct[direct]: EOF"),
        )
        assertEquals(
            "ERROR dns: exchange failed for … IN A: context deadline exceeded",
            LogPrivacy.redactCoreLine("ERROR dns: exchange failed for youtube.com. IN A: context deadline exceeded"),
        )
        assertEquals(
            "ERROR connection: open connection to … using outbound/direct[direct]: dial tcp: lookup … on 8.8.8.8:53: no such host",
            LogPrivacy.redactCoreLine("ERROR connection: open connection to youtube.com:443 using outbound/direct[direct]: dial tcp: lookup youtube.com on 8.8.8.8:53: no such host"),
        )
        assertEquals(
            "WARN router: pre-match: reject tcp connection from 172.19.0.1:4000 to fake destination …: a resolve action is required",
            LogPrivacy.redactCoreLine("WARN router: pre-match: reject tcp connection from 172.19.0.1:4000 to fake destination youtube.com: a resolve action is required"),
        )
        assertEquals("ERROR dns: lookup failed for …: timeout", LogPrivacy.redactCoreLine("ERROR dns: lookup failed for youtube.com: timeout"))
    }

    @Test
    fun infoLinesOfDebugBuildsLoseNamesAppsAndNetworks() {
        assertEquals(
            "11:18:06 INFO[0034] dns: exchanged A …",
            LogPrivacy.redactCoreLine("11:18:06 INFO[0034] dns: exchanged A example.com. 600 IN A 198.18.0.4"),
        )
        assertEquals("INFO dns: cached AAAA …", LogPrivacy.redactCoreLine("INFO dns: cached AAAA example.com. 60 IN AAAA fc00::4"))
        assertEquals(
            "INFO router: found package name: …",
            LogPrivacy.redactCoreLine("INFO router: found package name: com.google.android.youtube"),
        )
        assertEquals(
            "INFO network: WIFI state changed: …",
            LogPrivacy.redactCoreLine("INFO network: WIFI state changed: SSID=Home, BSSID=aa:bb:cc:dd:ee:ff"),
        )
        assertEquals("ERROR dns: response rejected for … IN A: refused", LogPrivacy.redactCoreLine("ERROR dns: response rejected for youtube.com. IN A: refused"))
        assertEquals("INFO router: found user id: 0", LogPrivacy.redactCoreLine("INFO router: found user id: 0"))
    }

    @Test
    fun otherCoreLinesStayAsTheyAre() {
        val lines = listOf(
            "INFO russia-direct: on [lists 7]",
            "WARN inbound/tun[tun-in]: updated default interface wlan0, index 30",
            "ERROR outbound/urltest[auto]: outbound/vless[DE]: context deadline exceeded",
        )
        lines.forEach { assertEquals(it, LogPrivacy.redactCoreLine(it)) }
    }

    @Test
    fun linksKeepTheirSchemeAndTheHostOfAnHttpLink() {
        assertEquals("https://api.vpn4tv.com/…", LogPrivacy.link("https://api.vpn4tv.com/sub/SECRET-TOKEN?flag=1"))
        assertEquals("https://example.org/…", LogPrivacy.link("https://user:pass@example.org:8443/x"))
        assertEquals("vless://…", LogPrivacy.link("vless://0b1c-uuid@1.2.3.4:443?type=tcp&security=reality#Germany"))
        assertEquals("ssconf://…", LogPrivacy.link("ssconf://keys.example.org/abc/def#Outline"))
        assertEquals("", LogPrivacy.link(""))
        assertEquals("…", LogPrivacy.link("not a link"))
    }

    @Test
    fun messagesLoseThePathsOfTheirLinks() {
        assertEquals(
            "java.io.FileNotFoundException: https://api.vpn4tv.com/…",
            LogPrivacy.redactLinks("java.io.FileNotFoundException: https://api.vpn4tv.com/sub/SECRET"),
        )
        assertEquals(
            "no answer from https://bell.a4e.ar/… in 5000 ms",
            LogPrivacy.redactLinks("no answer from https://bell.a4e.ar/sub/SECRET in 5000 ms"),
        )
        assertEquals("plain text", LogPrivacy.redactLinks("plain text"))
    }

    @Test
    fun errorsDoNotQuoteWhatTheyFailedToRead() {
        val json = LogPrivacy.error(org.json.JSONException("Unterminated object at character 12 of {\"uuid\":\"SECRET\",\"server\":\"1.2.3.4\""))
        assertEquals("JSONException: Unterminated object at character 12 of …", json)
        val value = LogPrivacy.error(org.json.JSONException("Value {\"password\":\"SECRET\"} of type org.json.JSONObject cannot be converted to JSONArray"))
        assertEquals("JSONException: Value … of type org.json.JSONObject cannot be converted to JSONArray", value)
        val uri = LogPrivacy.error(java.net.URISyntaxException("vless://SECRET@1.2.3.4:443?x=|", "Illegal character in query", 30))
        assertFalse(uri, uri.contains("SECRET"))
        assertTrue(uri, uri.startsWith("URISyntaxException: Illegal character in query at index "))
        assertEquals("", LogPrivacy.error(null))
    }

    @Test
    fun releaseBuildsRunTheCoreAtWarn() {
        val config = """{"log":{"level":"info","timestamp":true},"outbounds":[]}"""
        val release = JSONObject(LogPrivacy.applyCoreLevel(config, debug = false))
        assertEquals("warn", release.getJSONObject("log").getString("level"))
        assertTrue(release.getJSONObject("log").getBoolean("timestamp"))
        assertTrue(release.has("outbounds"))
        assertEquals(config, LogPrivacy.applyCoreLevel(config, debug = true))
        val noLog = JSONObject(LogPrivacy.applyCoreLevel("""{"outbounds":[]}""", debug = false))
        assertEquals("warn", noLog.getJSONObject("log").getString("level"))
    }

    @Test
    fun releaseBuildsRunXrayWithoutTheAccessLog() {
        val config = """{"log":{"loglevel":"debug"},"inbounds":[],"outbounds":[]}"""
        val release = JSONObject(LogPrivacy.applyXrayLog(config, debug = false)).getJSONObject("log")
        assertEquals("warning", release.getString("loglevel"))
        assertEquals("none", release.getString("access"))
        assertFalse(release.getBoolean("dnsLog"))
        assertEquals(config, LogPrivacy.applyXrayLog(config, debug = true))
    }
}
