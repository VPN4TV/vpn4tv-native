package com.vpn4tv.app.trusttunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TtTomlTest {
    @Test
    fun dropsAOneLineList() {
        val toml = "[endpoint]\nhostname = \"tt.example.org\"\ndns_upstreams = [\"8.8.8.8\", \"1.1.1.1\"]\nusername = \"u\"\n"
        val out = TtToml.withoutDnsUpstreams(toml)
        assertFalse(out, out.contains("dns_upstreams"))
        assertEquals("[endpoint]\nhostname = \"tt.example.org\"\nusername = \"u\"\n", out.trimEnd('\n') + "\n")
    }

    @Test
    fun dropsAMultiLineList() {
        val toml = "dns_upstreams = [\n  \"8.8.8.8\",\n  \"1.1.1.1\",\n]\n[endpoint]\nhostname = \"tt.example.org\"\n"
        val out = TtToml.withoutDnsUpstreams(toml)
        assertFalse(out, out.contains("dns_upstreams"))
        assertFalse(out, out.contains("8.8.8.8"))
        assertEquals("[endpoint]\nhostname = \"tt.example.org\"\n", out.trimEnd('\n') + "\n")
    }

    @Test
    fun leavesTheRestAlone() {
        val toml = "[endpoint]\nhostname = \"tt.example.org\"\naddresses = [\"192.0.2.1:443\"]\n"
        assertEquals(toml, TtToml.withoutDnsUpstreams(toml))
    }
}
